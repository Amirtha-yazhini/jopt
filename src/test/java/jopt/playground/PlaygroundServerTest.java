package jopt.playground;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP API, end to end over a real socket. */
class PlaygroundServerTest {
    @TempDir
    static Path web;
    static PlaygroundServer server;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        Files.writeString(web.resolve("index.html"), "<!doctype html><title>t</title>");
        server = PlaygroundServer.start(0, web);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header(PlaygroundServer.CLIENT_HEADER, "test")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    @Test
    void healthAndMeta() throws Exception {
        assertEquals(200, get("/api/health").statusCode());
        HttpResponse<String> meta = get("/api/meta");
        assertEquals(200, meta.statusCode());
        assertTrue(meta.body().startsWith("{") && meta.body().contains("\"ok\":true"));
        for (String pass : List.of("fold", "peephole", "dse", "cfg")) assertTrue(meta.body().contains("\"id\":\"" + pass + "\""));
        assertTrue(meta.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void examplesAreServed() throws Exception {
        HttpResponse<String> r = get("/api/examples");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"id\":\"ConstantLocals\""));
    }

    @Test
    void optimizeReturnsASession() throws Exception {
        HttpResponse<String> r = post("/api/optimize?disable=cfg",
                "public class A { static int f(int x) { int k = 4; return x * k; } }");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\":\"optimized\""));
        assertTrue(r.body().contains("\"disabled\":[\"cfg\"]"));
        assertTrue(r.body().contains("\"kind\":\"decision\""));
    }

    @Test
    void compileErrorsAreA200WithDiagnostics() throws Exception {
        HttpResponse<String> r = post("/api/optimize", "public class A { int f() { return; } }");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"compile-error\""));
        assertTrue(r.body().contains("\"kind\":\"error\""));
    }

    @Test
    void badRequestsGetStructuredErrors() throws Exception {
        HttpResponse<String> empty = post("/api/optimize", "");
        assertEquals(400, empty.statusCode());
        assertTrue(empty.body().contains("\"code\":\"empty_source\""));

        assertEquals(400, post("/api/optimize?disable=inline", "class A {}").statusCode());
        assertEquals(405, get("/api/optimize").statusCode());
        assertEquals(404, get("/api/nope").statusCode());

        String tooBig = "x".repeat(PlaygroundServer.MAX_BODY_BYTES + 1);
        HttpResponse<String> big = post("/api/optimize", tooBig);
        assertEquals(413, big.statusCode());
        assertTrue(big.body().contains("\"ok\":false"));
    }

    @Test
    void foreignHostHeaderIsRejected() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 403"), response);
        }
    }

    @Test
    void crossOriginAndHeaderlessPostsAreRejected() throws Exception {
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(uri("/api/optimize"))
                .header(PlaygroundServer.CLIENT_HEADER, "x").header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("class A {}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreign.statusCode());
        assertTrue(foreign.body().contains("forbidden_origin"));
        HttpResponse<String> plain = http.send(HttpRequest.newBuilder(uri("/api/optimize"))
                .POST(HttpRequest.BodyPublishers.ofString("class A {}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, plain.statusCode());
        assertTrue(plain.body().contains("missing_client_header"));
    }

    @Test
    void staticFilesAreServedWithoutEscapingTheRoot() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(404, get("/missing.js").statusCode());

        Path root = web.toAbsolutePath().normalize();
        assertNotNull(PlaygroundServer.resolve(root, "/index.html"));
        assertNull(PlaygroundServer.resolve(root, "/../" + root.getFileName() + "/../etc/passwd"));
        assertNull(PlaygroundServer.resolve(root, "/%2e%2e/secret"));
        assertNull(PlaygroundServer.resolve(root, "/a\0b"));
    }

    @Test
    void disabledPassesAreParsedFromTheQuery() {
        assertEquals(Set.of("fold", "dse"), PlaygroundServer.disabledPasses("disable=fold,dse"));
        assertEquals(Set.of("fold", "dse"), PlaygroundServer.disabledPasses("disable=fold%2Cdse&x=1"));
        assertEquals(Set.of(), PlaygroundServer.disabledPasses(null));
        assertEquals(Set.of(), PlaygroundServer.disabledPasses("disable="));
    }

    @Test
    void jsonEscapesAndSerializesRecords() {
        record Point(int x, String label, List<Double> values) {}
        String json = Json.write(Map.of("p", new Point(1, "a\"b\n</script>", List.of(1.5, 2.0, Double.NaN))));
        assertEquals("{\"p\":{\"x\":1,\"label\":\"a\\\"b\\n\\u003c/script\\u003e\",\"values\":[1.5,2,null]}}", json);
        assertEquals("null", Json.write(null));
    }
}
