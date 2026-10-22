package jopt.playground;

import jopt.io.ClassFiles;
import jopt.io.ClassHierarchy;
import jopt.io.LinkCheck;
import jopt.opt.Optimizer;
import jopt.opt.Pass;
import jopt.opt.Report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One playground run: compile the source with javac, optimize every class with the real optimizer
 * while recording each step, then load and link the output in HotSpot (no user code is executed).
 */
public final class PlaygroundService {
    public static final int MAX_SOURCE_CHARS = 64 * 1024;
    static final int MAX_CLASSES = 64;
    static final int MAX_TRACED_METHODS = 200;

    /** A request the service refuses; {@code code} is a stable identifier for clients. */
    public static final class InvalidRequestException extends RuntimeException {
        private final String code;

        InvalidRequestException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public Model.Session optimize(String source, Set<String> disabled) {
        validate(source, disabled);
        List<String> enabled = Optimizer.allPasses().stream().map(Pass::id).filter(p -> !disabled.contains(p)).toList();
        List<String> off = List.copyOf(new TreeSet<>(disabled));

        long t0 = System.nanoTime();
        SourceCompiler.Result compiled = SourceCompiler.compile(source);
        double compileMs = millisSince(t0);
        Model.Compilation compilation = new Model.Compilation(compiled.fileName(), SourceCompiler.RELEASE,
                compiled.diagnostics());
        if (!compiled.success())
            return new Model.Session("compile-error", compilation, enabled, off, List.of(), List.of(), 0, null,
                    List.of(), new Model.Timings(compileMs, 0, 0));
        if (compiled.classes().size() > MAX_CLASSES)
            throw new InvalidRequestException("too_many_classes",
                    "the source compiles to " + compiled.classes().size() + " classes; the limit is " + MAX_CLASSES);

        long t1 = System.nanoTime();
        Report report = new Report();
        TraceRecorder recorder = new TraceRecorder(MAX_TRACED_METHODS);
        Map<String, byte[]> output = optimizeAll(compiled.classes(), disabled, report, recorder);
        double optimizeMs = millisSince(t1);

        long t2 = System.nanoTime();
        LinkCheck.Result check = linkCheck(output);
        double verifyMs = millisSince(t2);

        List<Model.MethodTrace> traces = recorder.traces();
        return new Model.Session("optimized", compilation, enabled, off,
                classResults(compiled.classes(), output, check, traces), traces, recorder.notTraced(),
                summary(report), List.copyOf(report.warnings), new Model.Timings(compileMs, optimizeMs, verifyMs));
    }

    private static void validate(String source, Set<String> disabled) {
        if (source == null || source.isBlank())
            throw new InvalidRequestException("empty_source", "enter some Java source code first");
        if (source.length() > MAX_SOURCE_CHARS)
            throw new InvalidRequestException("source_too_large",
                    "source is " + source.length() + " characters; the limit is " + MAX_SOURCE_CHARS);
        for (String p : disabled)
            if (!Catalog.isPass(p)) throw new InvalidRequestException("unknown_pass", "unknown pass '" + p + "'");
    }

    private static Map<String, byte[]> optimizeAll(Map<String, byte[]> classes, Set<String> disabled, Report report,
                                                   TraceRecorder recorder) {
        Map<String, byte[]> out = new TreeMap<>();
        try (ClassHierarchy hierarchy = new ClassHierarchy(List.of())) {
            classes.values().forEach(hierarchy::add);
            Optimizer optimizer = new Optimizer(disabled, hierarchy, report, false, recorder);
            classes.forEach((name, bytes) -> out.put(name, optimizer.optimizeClass(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static LinkCheck.Result linkCheck(Map<String, byte[]> classes) {
        List<ClassFiles.Entry> entries = new ArrayList<>();
        classes.forEach((name, bytes) -> entries.add(new ClassFiles.Entry(name + ".class", bytes)));
        try {
            return LinkCheck.run(entries, List.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Model.ClassResult> classResults(Map<String, byte[]> before, Map<String, byte[]> after,
                                                        LinkCheck.Result check, List<Model.MethodTrace> traces) {
        List<Model.ClassResult> out = new ArrayList<>();
        before.forEach((name, in) -> {
            byte[] written = after.get(name);
            String dotted = name.replace('/', '.');
            String failure = find(check.failures(), dotted), skipped = find(check.skipped(), dotted);
            String verification = failure != null ? "failed" : skipped != null ? "skipped" : "passed";
            String unchangedReason = traces.stream()
                    .filter(t -> t.owner().equals(name) && t.outcome().equals(TraceRecorder.CLASS_UNCHANGED))
                    .map(Model.MethodTrace::outcomeDetail).findFirst().orElse(null);
            out.add(new Model.ClassResult(dotted, in.length, written.length, written != in, unchangedReason,
                    verification, failure != null ? failure : skipped));
        });
        return out;
    }

    /** LinkCheck reports "a.b.C: error"; returns the error for {@code dotted}, or null. */
    private static String find(List<String> results, String dotted) {
        for (String r : results) if (r.startsWith(dotted + ": ")) return r.substring(dotted.length() + 2);
        return null;
    }

    private static Model.Summary summary(Report r) {
        return new Model.Summary(r.classes, r.classesChanged, r.methods, r.methodsChanged,
                r.instructionsBefore, r.instructionsAfter, r.codeBytesBefore, r.codeBytesAfter,
                r.fileBytesBefore, r.fileBytesAfter, new LinkedHashMap<>(r.counters.asMap()));
    }

    private static double millisSince(long start) {
        return (System.nanoTime() - start) / 1e6;
    }
}
