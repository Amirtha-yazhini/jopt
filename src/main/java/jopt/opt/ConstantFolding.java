package jopt.opt;

import jopt.analysis.ConstantAnalysis;
import jopt.analysis.Fold;
import jopt.analysis.Frame;
import jopt.analysis.Interp;
import jopt.ir.Block;
import jopt.ir.Effects;
import jopt.ir.Insn;
import jopt.ir.MethodIR;
import jopt.ir.Terminator;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Constant propagation and folding, driven by {@link ConstantAnalysis}:
 * <ul>
 *   <li>a load of a local known to hold a constant becomes that constant;</li>
 *   <li>arithmetic on constant operands becomes {@code pop operands; push result};</li>
 *   <li>a branch or switch on constants becomes {@code pop operands; goto}.</li>
 * </ul>
 * The leftover "push constant; pop" pairs are removed by {@link Peephole}, which keeps this pass
 * simple: it never has to find where in the stack code an operand was produced.
 */
public final class ConstantFolding implements Pass {
    @Override
    public String id() {
        return "fold";
    }

    @Override
    public int run(MethodIR ir, Counters stats) {
        ConstantAnalysis analysis = ConstantAnalysis.run(ir);
        int changes = 0;
        for (Block b : ir.blocks) {
            Frame f = analysis.entryFrame(b);
            if (f == null) continue; // unreachable; CfgSimplify removes it
            List<Insn> out = new ArrayList<>(b.insns.size());
            for (Insn x : b.insns) {
                Insn folded = foldLoad(f, x);
                if (folded != null) {
                    out.add(folded);
                    stats.add("loads replaced by constants");
                    changes++;
                } else if (x instanceof Insn.Simple s && Effects.isArithmetic(s.opcode())) {
                    Frame probe = f.copy();
                    Object[] args = Interp.popOperands(probe, s.opcode());
                    Object r = Interp.allConstant(args) ? Fold.eval(s.opcode(), args) : null;
                    if (r != null) {
                        out.addAll(Effects.pops(Effects.inputSizes(s.opcode())));
                        out.add(new Insn.Const(r));
                        stats.add("expressions folded");
                        changes++;
                    } else {
                        out.add(x);
                    }
                } else {
                    out.add(x);
                }
                Interp.exec(f, x);
            }
            b.insns = out;

            if (b.term instanceof Terminator.If i) {
                Boolean taken = Fold.branch(i.opcode(), Interp.popBranchOperands(f.copy(), i.popWords()));
                if (taken != null) {
                    b.insns.addAll(Effects.pops(i.popWords() == 2 ? new int[]{1, 1} : new int[]{1}));
                    b.term = new Terminator.Goto(taken ? i.target() : i.next());
                    stats.add("branches folded");
                    changes++;
                }
            } else if (b.term instanceof Terminator.Switch s && f.copy().pop() instanceof Integer key) {
                b.insns.add(new Insn.Simple(POP));
                b.term = new Terminator.Goto(s.targetFor(key));
                stats.add("switches folded");
                changes++;
            }
        }
        return changes;
    }

    /** The constant a load can be replaced with, or null. */
    private static Insn foldLoad(Frame f, Insn x) {
        if (!(x instanceof Insn.Load l)) return null;
        Object v = f.local(l.var());
        boolean ok = switch (l.opcode()) {
            case ILOAD -> v instanceof Integer;
            case LLOAD -> v instanceof Long;
            case FLOAD -> v instanceof Float;
            case DLOAD -> v instanceof Double;
            default -> v == Frame.NULL || v instanceof String; // literals are interned: same object
        };
        if (!ok) return null;
        return new Insn.Const(v == Frame.NULL ? null : v);
    }
}
