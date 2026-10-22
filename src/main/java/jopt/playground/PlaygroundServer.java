package jopt.playground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Local HTTP server for the playground: a small JSON API plus the built frontend.
 *
 * <p>Trust boundary: it listens on the loopback interface only and rejects requests whose Host header
 * is not a loopback name (DNS-rebinding protection). User code is compiled and verified, never run.
 * Requests are size-, time- and concurrency-limited, but this is a local developer tool, not a
 * service hardened for the public internet.
 */
public final class PlaygroundServer implements AutoCloseable {
    static final int MAX_BODY_BYTES = PlaygroundService.MAX_SOURCE_CHARS * 4;
    static final long OPTIMIZE_TIMEOUT_SECONDS = 20;
    private static final int MAX_CONCURRENT_RUNS = 2;
    private static final Pattern LOOPBACK_HOST = Pattern.compile("(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d+)?");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json", "svg", "image/svg+xml",
            "ttf", "font/ttf", "woff2", "font/woff2", "png", "image/png", "ico", "image/x-icon");
    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "worker-src 'self' blob:; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

    private final HttpServer server;
    /** Header the frontend sends on POST; a custom header forces a CORS preflight, which this server never grants. */
    static final String CLIENT_HEADER = "X-Jopt-Client";
    private static final int READ_TIMEOUT_SECONDS = 10;

    private final ExecutorService requests = Executors.newFixedThreadPool(8);
    // At most MAX_CONCURRENT_RUNS worker threads. A run that ignores its timeout (javac and the optimizer do
    // not check interruption) keeps its slot until it really ends, so stuck runs cannot pile up.
    private final ExecutorService runs = new ThreadPoolExecutor(0, MAX_CONCURRENT_RUNS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private final Semaphore runSlots = new Semaphore(MAX_CONCURRENT_RUNS);
    private final PlaygroundService service = new PlaygroundService();
    private final Path staticRoot;

    private PlaygroundServer(int port, Path staticRoot) throws IOException {
        this.staticRoot = staticRoot == null ? null : staticRoot.toAbsolutePath().normalize();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", this::api);
        server.createContext("/", this::files);
        server.setExecutor(requests);
    }

    /** Starts listening on 127.0.0.1:{@code port} (0 picks a free port); {@code staticRoot} may be null. */
    public static PlaygroundServer start(int port, Path staticRoot) throws IOException {
        // Drop connections that stall while sending a request, so slow clients cannot hold request threads.
        System.setProperty("sun.net.httpserver.maxReqTime", String.valueOf(READ_TIMEOUT_SECONDS));
        PlaygroundServer s = new PlaygroundServer(port, staticRoot);
        s.server.start();
        return s;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        requests.shutdownNow();
        runs.shutdownNow();
    }

    // ---- API -----------------------------------------------------------------------------------

    private record Failure(int status, String code, String message) {}

    private void api(HttpExchange ex) throws IOException {
        try (ex) {
            if (!loopbackHost(ex)) {
                error(ex, new Failure(403, "forbidden_host", "requests must use a loopback host name"));
                return;
            }
            if (!sameOriginOrNone(ex)) {
                error(ex, new Failure(403, "forbidden_origin", "cross-origin requests are not allowed"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/api/health" -> requireGet(ex, method, () -> Map.of("status", "ok"));
                case "/api/meta" -> requireGet(ex, method, () -> new Model.Meta(version(), SourceCompiler.RELEASE,
                        PlaygroundService.MAX_SOURCE_CHARS, Catalog.PASSES, Catalog.REWRITES));
                case "/api/examples" -> requireGet(ex, method, Examples::all);
                case "/api/optimize" -> {
                    if (!method.equals("POST")) error(ex, new Failure(405, "method_not_allowed", "use POST"));
                    else if (ex.getRequestHeaders().getFirst(CLIENT_HEADER) == null)
                        error(ex, new Failure(403, "missing_client_header", "send the " + CLIENT_HEADER + " header"));
                    else optimize(ex);
                }
                default -> error(ex, new Failure(404, "not_found", "no such endpoint"));
            }
        } catch (RuntimeException e) {
            System.err.println("playground: unexpected error handling " + ex.getRequestURI());
            e.printStackTrace();
        }
    }

    private interface Body {
        Object get();
    }

    private void requireGet(HttpExchange ex, String method, Body body) throws IOException {
        if (!method.equals("GET")) error(ex, new Failure(405, "method_not_allowed", "use GET"));
        else ok(ex, body.get());
    }

    private void optimize(HttpExchange ex) throws IOException {
        String source;
        try {
            source = readBody(ex);
        } catch (Failed f) {
            error(ex, f.failure);
            return;
        }
        Set<String> disabled = disabledPasses(ex.getRequestURI().getRawQuery());
        Failure busy = new Failure(503, "busy", "the maximum number of runs is in progress; try again in a moment");
        if (!runSlots.tryAcquire()) {
            error(ex, busy);
            return;
        }
        Future<Model.Session> run;
        try {
            run = runs.submit(() -> {
                try {
                    return service.optimize(source, disabled);
                } finally {
                    runSlots.release(); // only when the work has really stopped
                }
            });
        } catch (RejectedExecutionException e) {
            runSlots.release();
            error(ex, busy);
            return;
        }
        try {
            ok(ex, run.get(OPTIMIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            run.cancel(true);
            error(ex, new Failure(504, "timeout", "compiling and optimizing took longer than "
                    + OPTIMIZE_TIMEOUT_SECONDS + " s"));
        } catch (ExecutionException e) {
            error(ex, failureFor(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(ex, new Failure(503, "interrupted", "the server is shutting down"));
        }
    }

    /** No Origin header (same-origin GETs, curl) or one of this server's own loopback origins. */
    private boolean sameOriginOrNone(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String o = origin.toLowerCase();
        int port = port();
        return o.equals("http://localhost:" + port) || o.equals("http://127.0.0.1:" + port)
                || o.equals("http://[::1]:" + port) || DEV_ORIGIN.matcher(o).matches();
    }

    /** The Vite dev server proxies to this one and keeps its own origin. */
    private static final Pattern DEV_ORIGIN = Pattern.compile("http://(localhost|127\\.0\\.0\\.1):5173");

    private static Failure failureFor(Throwable t) {
        if (t instanceof PlaygroundService.InvalidRequestException e) return new Failure(400, e.code(), e.getMessage());
        if (t instanceof SourceCompiler.CompilerUnavailableException e)
            return new Failure(500, "no_compiler", e.getMessage());
        System.err.println("playground: optimization failed");
        t.printStackTrace();
        return new Failure(500, "internal_error", "the optimizer failed unexpectedly; see the server log");
    }

    private static final class Failed extends Exception {
        final Failure failure;

        Failed(Failure failure) {
            super(failure.message(), null, false, false);
            this.failure = failure;
        }
    }

    private static String readBody(HttpExchange ex) throws IOException, Failed {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES)
            throw new Failed(new Failure(413, "source_too_large", "request body exceeds " + MAX_BODY_BYTES + " bytes"));
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new Failed(new Failure(400, "bad_encoding", "the request body must be UTF-8 text"));
        }
    }

    /** {@code disable=fold,dse} from the query string; unknown names are rejected by the service. */
    static Set<String> disabledPasses(String rawQuery) {
        Set<String> out = new LinkedHashSet<>();
        if (rawQuery == null) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !pair.substring(0, eq).equals("disable")) continue;
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(out::add);
        }
        return out;
    }

    private static boolean loopbackHost(HttpExchange ex) {
        List<String> host = ex.getRequestHeaders().get("Host");
        return host != null && host.size() == 1 && LOOPBACK_HOST.matcher(host.get(0).toLowerCase()).matches();
    }

    private static void ok(HttpExchange ex, Object data) throws IOException {
        send(ex, 200, Json.write(Map.of("ok", true, "data", data)));
    }

    private static void error(HttpExchange ex, Failure f) throws IOException {
        send(ex, f.status(), Json.write(Map.of("ok", false, "error", Map.of("code", f.code(), "message", f.message()))));
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- static files --------------------------------------------------------------------------

    private void files(HttpExchange ex) throws IOException {
        try (ex) {
            if (!loopbackHost(ex)) {
                text(ex, 403, "Forbidden: use http://localhost or http://127.0.0.1");
                return;
            }
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                text(ex, 405, "Method not allowed");
                return;
            }
            if (staticRoot == null) {
                text(ex, 404, "The frontend is not built. Run `npm run build` in web/, or use the Vite dev server.");
                return;
            }
            Path file = resolve(staticRoot, ex.getRequestURI().getPath());
            if (file == null) {
                text(ex, 404, "Not found");
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
            // Vite puts content hashes in asset file names, so those can be cached for good.
            ex.getResponseHeaders().set("Cache-Control",
                    file.getParent().endsWith("assets") ? "public, max-age=31536000, immutable" : "no-cache");
            byte[] bytes = Files.readAllBytes(file);
            boolean head = ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(200, head ? -1 : bytes.length);
            if (!head) try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** Maps a URL path to a regular file under {@code root}, or null; never escapes {@code root}. */
    static Path resolve(Path root, String urlPath) {
        String rel = urlPath == null || urlPath.equals("/") ? "index.html" : urlPath.replaceFirst("^/+", "");
        if (rel.isEmpty() || rel.indexOf('\0') >= 0) return null;
        Path p;
        try {
            p = root.resolve(rel).normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (!p.startsWith(root) || !Files.isRegularFile(p)) return null;
        try {
            if (!p.toRealPath().startsWith(root.toRealPath())) return null; // no symlinks out of the root
        } catch (IOException e) {
            return null;
        }
        return p;
    }

    private static void text(HttpExchange ex, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String version() {
        String v = PlaygroundServer.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
