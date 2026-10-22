package jopt.playground;

import jopt.io.Bytecode.Row;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real compiler and optimizer through the playground service. */
class PlaygroundServiceTest {
    private final PlaygroundService service = new PlaygroundService();

    private Model.Session run(String source, String... disabled) {
        return service.optimize(source, Set.of(disabled));
    }

    private static Model.Example example(String id) {
        return Examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private static Model.MethodTrace method(Model.Session s, String name) {
        return s.methods().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no traced method " + name));
    }

    private static List<String> code(Model.Step step) {
        return step.snapshot().bytecode().stream().map(Row::text).map(String::strip).toList();
    }

    private static Model.Step last(Model.MethodTrace m) {
        return m.steps().get(m.steps().size() - 1);
    }

    /** Every example compiles, verifies in HotSpot, and shows the transformations it claims to show. */
    @TestFactory
    Stream<DynamicTest> examplesDoWhatTheySay() {
        return Examples.all().stream().map(e -> DynamicTest.dynamicTest(e.id(), () -> {
            Model.Session s = run(e.source());
            assertEquals("optimized", s.status(), () -> "diagnostics: " + s.compilation().diagnostics());
            assertTrue(s.warnings().isEmpty(), () -> "warnings: " + s.warnings());
            for (Model.ClassResult c : s.classes())
                assertEquals("passed", c.verification(), () -> c.name() + ": " + c.verificationDetail());
            Set<String> seen = new HashSet<>();
            s.methods().forEach(m -> seen.addAll(m.counters().keySet()));
            for (String expected : e.expected())
                assertTrue(seen.contains(expected), () -> e.id() + " did not report '" + expected + "'; got " + seen);
        }));
    }

    /** The explanation catalog covers every (pass, counter) pair the examples produce. */
    @Test
    void everyReportedCounterIsExplained() {
        for (Model.Example e : Examples.all()) {
            for (Model.MethodTrace m : run(e.source()).methods()) {
                for (Model.Step st : m.steps()) {
                    for (String counter : st.counters().keySet()) {
                        boolean explained = Catalog.REWRITES.stream()
                                .anyMatch(r -> r.pass().equals(st.pass()) && r.counter().equals(counter));
                        assertTrue(explained, () -> "no catalog entry for " + st.pass() + " / " + counter);
                    }
                }
            }
        }
    }

    @Test
    void constantLocalsEndsAsOneMultiply() {
        Model.MethodTrace m = method(run(example("ConstantLocals").source()), "scaled");
        assertEquals("REPLACED", m.outcome());
        assertEquals(List.of("ILOAD 0", "BIPUSH 15", "IMUL", "IRETURN"), code(last(m)));
    }

    @Test
    void stepsAreOrderedAndEndAtAFixedPoint() {
        Model.MethodTrace m = method(run(example("ConstantLocals").source()), "scaled");
        List<Model.Step> steps = m.steps();
        assertEquals("input", steps.get(0).kind());
        assertEquals("build", steps.get(1).kind());
        assertEquals("decision", last(m).kind());
        for (int i = 0; i < steps.size(); i++) assertEquals(i, steps.get(i).index());

        List<Model.Step> passes = steps.stream().filter(s -> s.kind().equals("pass")).toList();
        assertEquals(m.rounds() * 4, passes.size());
        // The last round changed nothing: that is why the optimizer stopped.
        passes.stream().filter(s -> s.round() == m.rounds()).forEach(s -> assertEquals(0, s.changes()));
    }

    @Test
    void unchangedStepsOmitTheirSnapshot() {
        Model.MethodTrace m = method(run(example("ConstantLocals").source()), "scaled");
        for (Model.Step s : m.steps()) {
            if (s.sameAsPrevious()) assertNull(s.snapshot());
            else assertNotNull(s.snapshot());
            // A pass that reports no changes leaves the IR textually identical.
            if (s.kind().equals("pass") && s.changes() == 0) assertTrue(s.sameAsPrevious(), () -> "step " + s.index());
        }
    }

    @Test
    void snapshotsCarrySourceLines() {
        Model.MethodTrace m = method(run(example("ConstantLocals").source()), "scaled");
        Model.Snapshot input = m.steps().get(0).snapshot();
        assertNull(input.ir());
        assertTrue(input.bytecode().stream().anyMatch(r -> r.line() == 4), "int a = 3 is on line 4");
        Model.Snapshot built = m.steps().get(1).snapshot();
        assertTrue(built.ir().stream().anyMatch(r -> r.text().contains("ISTORE") && r.line() == 4));
        assertEquals(input.instructions(), built.instructions(), "lowering the untouched IR keeps the instruction count");
    }

    @Test
    void divisionByZeroIsNotFolded() {
        Model.MethodTrace m = method(run(example("Safety").source()), "risky");
        assertTrue(code(last(m)).contains("IDIV"));
        assertTrue(m.counters().containsKey("loads replaced by constants"));
    }

    @Test
    void tightCodeIsLeftAlone() {
        Model.MethodTrace m = method(run(example("AlreadyTight").source()), "clamp");
        assertEquals("NOTHING_APPLIED", m.outcome());
        assertEquals(m.steps().get(0).snapshot(), last(m).snapshot(), "the class keeps javac's code");
    }

    @Test
    void disabledPassesDoNotRun() {
        Model.Session s = run(example("ConstantLocals").source(), "fold");
        assertEquals(List.of("peephole", "dse", "cfg"), s.passes());
        assertEquals(List.of("fold"), s.disabled());
        Model.MethodTrace m = method(s, "scaled");
        assertFalse(m.counters().containsKey("loads replaced by constants"));
        assertTrue(m.steps().stream().noneMatch(st -> "fold".equals(st.pass())));
    }

    @Test
    void compileErrorsAreReportedWithLocations() {
        Model.Session s = run("public class Broken {\n  int f() { return \"x\"; }\n}\n");
        assertEquals("compile-error", s.status());
        assertTrue(s.methods().isEmpty());
        SourceCompiler.Message d = s.compilation().diagnostics().get(0);
        assertEquals("error", d.kind());
        assertEquals(2, d.line());
        assertEquals("Broken.java", s.compilation().fileName());
    }

    @Test
    void userCodeCannotSeeTheServersClassPath() {
        // jopt.Main is on the server's own class path; the compiler must not resolve it.
        Model.Session s = run("class A { Object o = new jopt.Main(); }");
        assertEquals("compile-error", s.status());
    }

    @Test
    void sourceWithoutPublicClassStillCompiles() {
        Model.Session s = run("class Hidden { static int f(int x) { int k = 2; return x * k; } }");
        assertEquals("optimized", s.status());
        assertEquals("Playground.java", s.compilation().fileName());
    }

    @Test
    void invalidRequestsAreRejected() {
        assertEquals("empty_source", assertThrows(PlaygroundService.InvalidRequestException.class, () -> run("  ")).code());
        assertEquals("unknown_pass", assertThrows(PlaygroundService.InvalidRequestException.class,
                () -> run("class A {}", "inline")).code());
        String huge = "class A {}" + " ".repeat(PlaygroundService.MAX_SOURCE_CHARS);
        assertEquals("source_too_large", assertThrows(PlaygroundService.InvalidRequestException.class,
                () -> run(huge)).code());
    }

    @Test
    void summaryMatchesTheOptimizerReport() {
        Model.Session s = run(example("Invoice").source());
        Model.Summary sum = s.summary();
        assertTrue(sum.methodsReplaced() > 0);
        assertTrue(sum.codeBytesAfter() < sum.codeBytesBefore());
        long replaced = s.methods().stream().filter(m -> m.outcome().equals("REPLACED")).count();
        assertEquals(sum.methodsReplaced(), replaced);
    }
}
