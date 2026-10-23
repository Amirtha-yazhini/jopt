package jopt;

import jopt.io.ClassFiles;
import jopt.io.ClassHierarchy;
import jopt.io.LinkCheck;
import jopt.opt.Optimizer;
import jopt.opt.Pass;
import jopt.opt.Report;
import jopt.playground.PlaygroundServer;
import jopt.report.HtmlReport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Main {
    private static final String USAGE = """
            jopt - a Java bytecode optimizer

            usage:
              jopt optimize <in> -o <out> [--report report.html] [--cp a.jar%sb.jar] [--disable pass,...]
              jopt show <in> <class> [method]     print a method before and after optimization
              jopt verify <in> [--cp ...]         load and link every class (runs the JVM's verifier)
              jopt demo <classes-dir> <work-dir>  optimize, verify and run the bundled demo app
              jopt playground [--port 7070] [--static web/dist]
                                                  interactive playground on http://localhost:<port>

            <in>/<out> may be a .jar, a directory of classes, or a single .class file.
            passes: %s
            """.formatted(File.pathSeparator, String.join(", ", Optimizer.allPasses().stream().map(Pass::id).toList()));

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0) {
            System.out.print(USAGE);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int code = switch (args[0]) {
            case "optimize" -> optimize(rest);
            case "show" -> show(rest);
            case "verify" -> verify(rest);
            case "demo" -> demo(rest);
            case "playground" -> playground(rest);
            default -> {
                System.err.print(USAGE);
                yield 2;
            }
        };
        if (code != 0) System.exit(code);
    }

    /** Parsed common options. */
    private record Options(List<String> positional, Path out, Path report, List<Path> classpath, Set<String> disabled) {
        static Options parse(String[] args) {
            List<String> pos = new ArrayList<>();
            Path out = null, report = null;
            List<Path> cp = new ArrayList<>();
            Set<String> disabled = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-o", "--out" -> out = Path.of(args[++i]);
                    case "--report" -> report = Path.of(args[++i]);
                    case "--cp", "--classpath" -> {
                        for (String p : args[++i].split(File.pathSeparator)) if (!p.isBlank()) cp.add(Path.of(p));
                    }
                    case "--disable" -> disabled.addAll(List.of(args[++i].split(",")));
                    default -> {
                        if (args[i].startsWith("-")) throw new IllegalArgumentException("unknown option " + args[i]);
                        pos.add(args[i]);
                    }
                }
            }
            return new Options(pos, out, report, cp, disabled);
        }
    }

    // ---- optimize ------------------------------------------------------------------------------

    private static int optimize(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o.positional().size() != 1 || o.out() == null) {
            System.err.print(USAGE);
            return 2;
        }
        Path in = Path.of(o.positional().get(0));
        long t0 = System.nanoTime();
        Report r = new Report();
        List<ClassFiles.Entry> out = run(in, o, r, o.report() != null);
        ClassFiles.write(o.out(), out);
        printSummary(r, (System.nanoTime() - t0) / 1e9);
        System.out.println("  wrote " + o.out());
        if (o.report() != null) {
            HtmlReport.write(o.report(), r, in.toString(), o.out().toString());
            System.out.println("  report " + o.report().toAbsolutePath());
        }
        return 0;
    }

    /** Optimizes every class in {@code in}; returns the output entries (other files copied as-is). */
    private static List<ClassFiles.Entry> run(Path in, Options o, Report r, boolean listings) throws Exception {
        List<ClassFiles.Entry> entries = ClassFiles.read(in);
        List<ClassFiles.Entry> out = new ArrayList<>();
        try (ClassHierarchy hierarchy = new ClassHierarchy(o.classpath())) {
            for (ClassFiles.Entry e : entries) if (e.isClass()) hierarchy.add(e.data());
            Optimizer opt = new Optimizer(o.disabled(), hierarchy, r, listings);
            for (ClassFiles.Entry e : entries)
                out.add(e.isClass() && !e.path().startsWith("META-INF/versions/")
                        ? new ClassFiles.Entry(e.path(), opt.optimizeClass(e.data())) : e);
        }
        if (r.classesChanged > 0) out.removeIf(e -> ClassFiles.isSignatureFile(e.path()));
        return out;
    }

    private static void printSummary(Report r, double seconds) {
        System.out.printf("Optimized %d of %d methods in %d of %d classes (%.2fs)%n",
                r.methodsChanged, r.methods, r.classesChanged, r.classes, seconds);
        System.out.printf("  instructions  %,8d -> %,8d   (%s)%n", r.instructionsBefore, r.instructionsAfter,
                Report.change(r.instructionsBefore, r.instructionsAfter));
        System.out.printf("  bytecode      %,8d -> %,8d B (%s)%n", r.codeBytesBefore, r.codeBytesAfter,
                Report.change(r.codeBytesBefore, r.codeBytesAfter));
        System.out.printf("  class files   %,8d -> %,8d B (%s)%n", r.fileBytesBefore, r.fileBytesAfter,
                Report.change(r.fileBytesBefore, r.fileBytesAfter));
        for (Map.Entry<String, Long> e : r.counters.asMap().entrySet())
            System.out.printf("    %-32s %,6d%n", e.getKey(), e.getValue());
        if (!r.warnings.isEmpty()) {
            System.out.println("  " + r.warnings.size() + " method(s)/class(es) left unchanged:");
            r.warnings.stream().limit(10).forEach(w -> System.out.println("    " + w));
        }
    }

    // ---- show ----------------------------------------------------------------------------------

    private static int show(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o.positional().size() < 2) {
            System.err.print(USAGE);
            return 2;
        }
        String cls = o.positional().get(1).replace('.', '/');
        String method = o.positional().size() > 2 ? o.positional().get(2) : null;
        Report r = new Report();
        run(Path.of(o.positional().get(0)), o, r, true);
        int n = 0;
        for (Report.MethodChange m : r.changes) {
            if (!(m.owner().equals(cls) || m.owner().endsWith("/" + cls))) continue;
            if (method != null && !m.name().equals(method)) continue;
            printSideBySide(m);
            n++;
        }
        if (n == 0) System.out.println("No optimized methods match " + o.positional().get(1) + (method == null ? "" : "." + method));
        return 0;
    }

    private static void printSideBySide(Report.MethodChange m) {
        System.out.printf("%n== %s   %d -> %d bytes, %d -> %d instructions%n", m.displayName(),
                m.bytesBefore(), m.bytesAfter(), m.instructionsBefore(), m.instructionsAfter());
        List<String> what = new ArrayList<>();
        m.what().asMap().forEach((k, v) -> what.add(v + "x " + k));
        System.out.println("   " + String.join(", ", what));
        String[] a = m.before().split("\n"), b = m.after().split("\n");
        int w = 44;
        System.out.printf("   %-" + w + "s %s%n", "BEFORE", "AFTER");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String l = i < a.length ? a[i] : "";
            if (l.length() > w - 1) l = l.substring(0, w - 2) + "~";
            System.out.printf("   %-" + w + "s %s%n", l, i < b.length ? b[i] : "");
        }
    }

    // ---- verify --------------------------------------------------------------------------------

    private static int verify(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o.positional().size() != 1) {
            System.err.print(USAGE);
            return 2;
        }
        LinkCheck.Result res = LinkCheck.run(ClassFiles.read(Path.of(o.positional().get(0))), o.classpath());
        System.out.printf("%d classes linked and verified, %d failed, %d skipped (missing dependencies)%n",
                res.linked(), res.failures().size(), res.skipped().size());
        res.failures().forEach(f -> System.out.println("  FAIL " + f));
        res.skipped().stream().limit(10).forEach(s -> System.out.println("  skip " + s));
        return res.ok() ? 0 : 1;
    }

    // ---- playground ----------------------------------------------------------------------------

    private static int playground(String[] args) throws Exception {
        int port = 7070;
        String envPort = System.getenv("PORT");
        if (envPort == null || envPort.isBlank()) envPort = System.getenv("JOPT_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        Path web = Path.of("web", "dist");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--static" -> web = Path.of(args[++i]);
                default -> {
                    System.err.print(USAGE);
                    return 2;
                }
            }
        }
        Path root = java.nio.file.Files.isDirectory(web) ? web : null;
        PlaygroundServer server = PlaygroundServer.start(port, root);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("jopt playground listening on http://localhost:" + server.port() + "/");
        if (root == null)
            System.out.println("  (API only: " + web.toAbsolutePath() + " not found; run `npm run build` in web/ or `npm run dev`)");
        else
            System.out.println("  serving " + root.toAbsolutePath());
        System.out.println("  user code is compiled and verified, never executed. Ctrl+C to stop.");
        return 0;
    }

    // ---- demo ----------------------------------------------------------------------------------

    private static int demo(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.print(USAGE);
            return 2;
        }
        Path classes = Path.of(args[0]), work = Path.of(args[1]);
        Path optimized = work.resolve("optimized"), report = work.resolve("report.html");
        Options o = Options.parse(new String[0]);

        System.out.println("\n=== 1. Optimize " + classes);
        Report r = new Report();
        ClassFiles.write(optimized, run(classes, o, r, true));
        printSummary(r, 0);

        System.out.println("\n=== 2. Verify: link every optimized class in the JVM");
        LinkCheck.Result check = LinkCheck.run(ClassFiles.read(optimized), List.of());
        System.out.printf("  %d classes passed HotSpot's verifier, %d failed%n", check.linked(), check.failures().size());
        check.failures().forEach(f -> System.out.println("  FAIL " + f));

        System.out.println("\n=== 3. Run both versions of demo.Main");
        String before = runMain(classes), after = runMain(optimized);
        boolean same = before.equals(after);
        System.out.print(indent(before));
        System.out.println(same ? "  -> original and optimized output are identical" : "  -> OUTPUT DIFFERS:\n" + indent(after));

        System.out.println("\n=== 4. Biggest wins");
        r.changes.stream().sorted(Comparator.comparingInt(Report.MethodChange::bytesSaved).reversed())
                .limit(3).forEach(Main::printSideBySide);

        HtmlReport.write(report, r, classes.toString(), optimized.toString());
        System.out.println("\nFull report: " + report.toAbsolutePath());
        return check.ok() && same ? 0 : 1;
    }

    /** Runs demo.Main from {@code dir} in an isolated class loader and returns what it printed. */
    private static String runMain(Path dir) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Method main = loader.loadClass("demo.Main").getMethod("main", String[].class);
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String indent(String s) {
        return s.lines().map(l -> "  | " + l + "\n").reduce("", String::concat);
    }
}
