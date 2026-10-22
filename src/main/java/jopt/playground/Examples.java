package jopt.playground;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Example programs for the playground, simplest first. {@code expected} lists counter names the real
 * optimizer must report for the example; {@code PlaygroundServiceTest} checks every one of them.
 */
public final class Examples {
    private Examples() {}

    private record Entry(String id, String title, String concept, String description, List<String> expected) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry("ConstantLocals", "Constants held in locals", "fold",
                    "javac keeps `int a = 3` as a real local variable. Constant folding replaces the loads with "
                            + "the value, evaluates 3 * 4 + 3 at optimization time, and dead-store elimination "
                            + "removes the locals.",
                    List.of("loads replaced by constants", "expressions folded", "dead stores removed")),
            new Entry("DebugFlag", "Switched-off debug code", "fold + cfg",
                    "A `debug` flag that is always false. The branch on it is folded into a jump, the logging "
                            + "block becomes unreachable and is deleted, and `x * 2` becomes a shift.",
                    List.of("branches folded", "unreachable blocks removed", "strength reductions")),
            new Entry("Identities", "Algebra and strength reduction", "peephole",
                    "Multiplying by 1, adding 0 and or-ing with 0 do nothing; multiplying by 8 is a shift by 3.",
                    List.of("algebraic identities", "strength reductions")),
            new Entry("Counting", "i = i + 1 becomes iinc", "peephole",
                    "javac only emits `iinc` for `i++` and `i += k`. Written as `i = i + 1`, the loop counter "
                            + "update is four instructions until peephole fuses them.",
                    List.of("iinc formed")),
            new Entry("Temporaries", "Copies and unused temporaries", "peephole + dse",
                    "Values copied through temporaries and a computed value nobody reads. store/load → dup/store "
                            + "plus dead-store elimination add up to copy propagation.",
                    List.of("store/load pairs", "dead stores removed", "unused computations removed")),
            new Entry("Modes", "Switch on a constant", "fold + cfg",
                    "The switch key is a constant local, so the switch becomes a jump to `case 2` and the other "
                            + "cases are deleted.",
                    List.of("switches folded", "unreachable blocks removed")),
            new Entry("Safety", "What jopt refuses to fold", "fold",
                    "`x / zero` is known to divide by zero, but folding it would remove the ArithmeticException, "
                            + "so IDIV stays. The load of `zero` is still replaced by the constant 0.",
                    List.of("loads replaced by constants")),
            new Entry("TryFinally", "Exception handlers", "fold + dse + cfg",
                    "A constant flag inside try/finally. Handlers stay correct: locals read by the handler are "
                            + "kept live across the whole try block.",
                    List.of("branches folded")),
            new Entry("Invoice", "A realistic class", "all passes",
                    "Application code in the style of the demo app: configuration flags, constants in locals, "
                            + "`i = i + 1` and a dead `trace` branch, across several methods.",
                    List.of("loads replaced by constants", "branches folded", "iinc formed", "dead stores removed")),
            new Entry("AlreadyTight", "Nothing to optimize", "decision",
                    "Code without constants in locals, dead stores or identities. The passes find nothing, so "
                            + "the method is left exactly as javac wrote it.",
                    List.of()));

    public static List<Model.Example> all() {
        return ENTRIES.stream().map(e -> new Model.Example(e.id(), e.title(), e.concept(), e.description(),
                e.expected(), source(e.id()))).toList();
    }

    private static String source(String id) {
        String path = "examples/" + id + ".java";
        try (InputStream in = Examples.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing example resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
