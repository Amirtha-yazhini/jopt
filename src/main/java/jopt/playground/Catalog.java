package jopt.playground;

import java.util.List;

/**
 * Documentation for the passes and for every counter name the passes emit. The counter names are
 * the passes' own (see {@code stats.add(...)} in {@code jopt.opt}); a test checks that each one that
 * shows up when optimizing the examples has an entry here.
 */
public final class Catalog {
    private Catalog() {}

    public record PassInfo(String id, String name, String summary) {}

    /** What one counter means when emitted by {@code pass}. */
    public record Rewrite(String pass, String counter, String explanation, String example) {}

    public static final List<PassInfo> PASSES = List.of(
            new PassInfo("fold", "Constant folding",
                    "A forward dataflow analysis tracks which locals and stack slots hold known constants. Loads of "
                            + "constant locals become constant pushes, arithmetic on constants is evaluated with exact "
                            + "JVM semantics, and branches or switches on constants become plain jumps. It emits "
                            + "'pop operands; push result' and leaves the clean-up to peephole."),
            new PassInfo("peephole", "Peephole rewrites",
                    "Local rewrites over short instruction windows inside one basic block: push/pop pairs, unused "
                            + "pure computations, algebraic identities, strength reduction, iinc formation, "
                            + "store/load to dup/store, and comparisons against 0 or null."),
            new PassInfo("dse", "Dead-store elimination",
                    "A backward liveness analysis over local variable slots. A store to a slot that is never read "
                            + "again becomes a pop, and an iinc of a dead slot is deleted. Inside a try block, every "
                            + "slot the handler reads stays live."),
            new PassInfo("cfg", "CFG simplification",
                    "Control-flow clean-up: jump threading through empty goto blocks, branches whose targets are "
                            + "identical, deleting unreachable blocks, and merging a block into its only predecessor."));

    public static final List<Rewrite> REWRITES = List.of(
            new Rewrite("fold", "loads replaced by constants",
                    "A local variable held the same known constant on every path reaching this load, so the load "
                            + "was replaced by pushing the constant.",
                    "int a = 3; return x * a;   →   return x * 3;"),
            new Rewrite("fold", "expressions folded",
                    "An arithmetic, shift, conversion or comparison instruction had only constant operands, so it "
                            + "was evaluated now (wrapping overflow, masked shift distances, IEEE floats). The operands "
                            + "are popped and the result pushed. Integer division by zero is never folded.",
                    "CONST 3; CONST 4; IMUL   →   POP2; CONST 12 (the pops are then cancelled by peephole)"),
            new Rewrite("fold", "branches folded",
                    "A conditional branch compared known constants, so its outcome is fixed. It became a pop of the "
                            + "operands and a GOTO to the taken side; the other side may become unreachable.",
                    "boolean debug = false; if (debug) {...}   →   GOTO past the block"),
            new Rewrite("fold", "switches folded",
                    "A switch on a known constant key became a GOTO to the matching case.",
                    "int mode = 2; switch (mode) {...}   →   GOTO case 2"),
            new Rewrite("peephole", "unused values removed",
                    "A value was pushed (constant, local load or dup) and immediately popped, so both instructions "
                            + "were deleted.",
                    "CONST 5; POP   →   (nothing)"),
            new Rewrite("peephole", "unused computations removed",
                    "A side-effect-free operation's result was discarded, so the operation was replaced by popping "
                            + "its inputs. Division and remainder are excluded because they can throw.",
                    "ILOAD 0; CONST 7; IMUL; POP   →   ILOAD 0; CONST 7; POP2   →   (nothing)"),
            new Rewrite("peephole", "algebraic identities",
                    "An operation with an identity operand was removed or simplified: x+0, x-0, x|0, x^0, x*1, "
                            + "x/1, x&-1, shifts by 0 (after masking), x*0 → 0, x&0 → 0, x|-1 → -1, x*-1 → -x, "
                            + "double negation, double swap, and iinc by 0.",
                    "ILOAD 0; CONST 1; IMUL   →   ILOAD 0"),
            new Rewrite("peephole", "redundant load/store removed",
                    "A local was loaded and stored straight back into the same slot, which changes nothing.",
                    "ILOAD 2; ISTORE 2   →   (nothing)"),
            new Rewrite("peephole", "store/load pairs",
                    "A value stored to a local and immediately loaded again is kept on the stack with dup instead. "
                            + "If the store later turns out dead, dse and peephole remove it, which adds up to copy "
                            + "propagation.",
                    "ISTORE 1; ILOAD 1   →   DUP; ISTORE 1"),
            new Rewrite("peephole", "iinc formed",
                    "i = i + k (load, push constant, add or subtract, store to the same int local) became one iinc "
                            + "instruction.",
                    "ILOAD 2; CONST 1; IADD; ISTORE 2   →   IINC 2 1"),
            new Rewrite("peephole", "strength reductions",
                    "A multiplication by a power of two became a left shift.",
                    "ILOAD 0; CONST 8; IMUL   →   ILOAD 0; CONST 3; ISHL"),
            new Rewrite("peephole", "compare-with-zero branches",
                    "A comparison against the constant 0 (or null) became the dedicated single-operand branch.",
                    "CONST 0; IF_ICMPLE   →   IFLE"),
            new Rewrite("dse", "dead stores removed",
                    "Liveness analysis found a store whose value is never read again on any path, so the store "
                            + "became a pop (or an iinc of a dead local was deleted).",
                    "int unused = x * 7;   →   ...; POP   (then removed by peephole)"),
            new Rewrite("cfg", "jumps threaded",
                    "A jump targeted an empty block that only jumps on, so it now goes straight to the final target.",
                    "GOTO B3, B3: GOTO B7   →   GOTO B7"),
            new Rewrite("cfg", "branches folded",
                    "A conditional branch had the same block as both targets, so it became a pop and a GOTO.",
                    "IFEQ B4 else B4   →   POP; GOTO B4"),
            new Rewrite("cfg", "switches folded",
                    "Every case of a switch went to the same block, so it became a pop and a GOTO.",
                    "SWITCH { 1: B2, default: B2 }   →   POP; GOTO B2"),
            new Rewrite("cfg", "unreachable blocks removed",
                    "No path from the method entry (normal or exceptional) reaches these blocks any more, so they "
                            + "were deleted.",
                    "the body of if (false) {...}"),
            new Rewrite("cfg", "blocks merged",
                    "A block ending in GOTO was the only predecessor of its target, so the two were joined into "
                            + "one block.",
                    "B1: ...; GOTO B2   B2: ...   →   B1: ... ..."));

    public static boolean isPass(String id) {
        return PASSES.stream().anyMatch(p -> p.id().equals(id));
    }
}
