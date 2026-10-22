package jopt.playground;

import jopt.io.Bytecode.Row;

import java.util.List;
import java.util.Map;

/** The JSON shapes the playground API returns. All values are measured or recorded, never estimated. */
public final class Model {
    private Model() {}

    /**
     * One optimization run over one source file.
     *
     * @param status        "optimized" or "compile-error"
     * @param passes        enabled passes, in the order they run each round
     * @param methodsNotTraced methods with code beyond the tracing limit (optimized, but without snapshots)
     */
    public record Session(String status, Compilation compilation, List<String> passes, List<String> disabled,
                          List<ClassResult> classes, List<MethodTrace> methods, int methodsNotTraced,
                          Summary summary, List<String> warnings, Timings timings) {}

    public record Compilation(String fileName, String release, List<SourceCompiler.Message> diagnostics) {}

    /**
     * @param verification "passed", "failed" or "skipped": HotSpot loading and linking (which runs its
     *                     bytecode verifier) of the class jopt wrote; static initializers are not run.
     */
    public record ClassResult(String name, int fileBytesBefore, int fileBytesAfter, boolean rewritten,
                              String unchangedReason, String verification, String verificationDetail) {}

    /**
     * Everything recorded for one method.
     *
     * @param outcome  an {@code OptimizerTrace.Outcome} name, or "CLASS_UNCHANGED" when the method was
     *                 optimized but its class could not be written
     * @param steps    input, IR build, every pass run, and the decision, in order
     * @param rounds   rounds of the pass pipeline that ran
     */
    public record MethodTrace(String id, String owner, String name, String desc, String outcome, String outcomeDetail,
                              List<Step> steps, int rounds, Map<String, Long> counters) {}

    /**
     * One recorded step.
     *
     * @param kind      "input" (javac output), "build" (IR built), "pass" (one pass run) or "decision"
     * @param round     pipeline round for "pass" steps, else 0
     * @param pass      pass id for "pass" steps, else null
     * @param changes   the change count the pass returned
     * @param counters  the named counts this pass run recorded
     * @param sameAsPrevious true if the IR is textually identical to the previous step's (snapshot then omitted)
     * @param snapshot  the code after this step, or null when {@code sameAsPrevious}
     */
    public record Step(int index, String kind, int round, String pass, int changes, Map<String, Long> counters,
                       boolean sameAsPrevious, Snapshot snapshot) {}

    /**
     * @param ir            the IR as text (null for the javac input step)
     * @param bytecode      bytecode listing: javac output for "input", else the IR lowered by IRWriter
     * @param loweringError why the IR could not be lowered at this point, if it could not
     * @param instructions  bytecode instructions (labels, line numbers, frames excluded); null if not lowered
     * @param codeBytes     size of the code array in bytes (Bytecode.bytes); null if not lowered
     * @param irBlocks      basic blocks in the IR; null for the input step
     * @param irInstructions IR instructions including one terminator per block; null for the input step
     */
    public record Snapshot(List<Row> ir, List<Row> bytecode, String loweringError, Integer instructions,
                           Integer codeBytes, Integer irBlocks, Integer irInstructions) {}

    /** Totals from jopt's own {@code Report}, plus pass counters summed over all methods that were replaced. */
    public record Summary(int classes, int classesRewritten, int methods, int methodsReplaced,
                         long instructionsBefore, long instructionsAfter, long codeBytesBefore, long codeBytesAfter,
                         long fileBytesBefore, long fileBytesAfter, Map<String, Long> counters) {}

    /** Server wall-clock time in milliseconds; optimization time includes recording the snapshots. */
    public record Timings(double compileMs, double optimizeMs, double verifyMs) {}

    public record Example(String id, String title, String concept, String description, List<String> expected,
                          String source) {}

    public record Meta(String version, String release, int maxSourceChars, List<Catalog.PassInfo> passes,
                       List<Catalog.Rewrite> rewrites) {}
}
