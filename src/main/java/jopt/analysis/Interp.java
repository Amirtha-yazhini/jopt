package jopt.analysis;

import jopt.ir.Effects;
import jopt.ir.Insn;
import org.objectweb.asm.ConstantDynamic;

import static org.objectweb.asm.Opcodes.*;

/** Transfer function of constant propagation: executes one instruction on an abstract {@link Frame}. */
public final class Interp {
    private Interp() {}

    public static void exec(Frame f, Insn x) {
        switch (x) {
            case Insn.Line l -> {}
            case Insn.Const c -> {
                Object v = c.value();
                if (v == null) f.push(Frame.NULL);
                else if (v instanceof Integer || v instanceof Float || v instanceof String) f.push(v);
                else if (v instanceof Long || v instanceof Double) f.push2(v);
                else if (v instanceof ConstantDynamic cd) f.push(Frame.TOP, cd.getSize());
                else f.push(Frame.TOP); // class literal, method type, method handle
            }
            case Insn.Load l -> {
                Object v = f.local(l.var());
                f.push(v == Frame.HIGH ? Frame.TOP : v, l.size());
            }
            case Insn.Store s -> f.setLocal(s.var(), f.pop(s.size()), s.size());
            case Insn.Iinc i -> {
                Object v = f.local(i.var());
                f.setLocal(i.var(), v instanceof Integer n ? (Object) (n + i.increment()) : Frame.TOP, 1);
            }
            case Insn.Simple s -> simple(f, s.opcode());
            default -> {
                for (int k = Effects.popWords(x); k > 0; k--) f.pop();
                f.push(Frame.TOP, Effects.pushWords(x));
            }
        }
    }

    /** Pops the operands of arithmetic opcode {@code op}, first operand first. */
    public static Object[] popOperands(Frame f, int op) {
        int[] sizes = Effects.inputSizes(op);
        Object[] args = new Object[sizes.length];
        for (int k = sizes.length - 1; k >= 0; k--) args[k] = f.pop(sizes[k]);
        return args;
    }

    /** Pops the operands of a conditional branch, first operand first. */
    public static Object[] popBranchOperands(Frame f, int popWords) {
        if (popWords == 1) return new Object[]{f.pop()};
        Object b = f.pop(), a = f.pop();
        return new Object[]{a, b};
    }

    public static boolean allConstant(Object[] args) {
        for (Object a : args) if (!Frame.isConstant(a) || a == Frame.NULL) return false;
        return true;
    }

    private static void simple(Frame f, int op) {
        switch (op) {
            case NOP -> {}
            case POP -> f.pop();
            case POP2 -> {
                f.pop();
                f.pop();
            }
            case DUP -> {
                Object a = f.pop();
                f.push(a);
                f.push(a);
            }
            case DUP_X1 -> {
                Object a = f.pop(), b = f.pop();
                f.push(a);
                f.push(b);
                f.push(a);
            }
            case DUP_X2 -> {
                Object a = f.pop(), b = f.pop(), c = f.pop();
                f.push(a);
                f.push(c);
                f.push(b);
                f.push(a);
            }
            case DUP2 -> {
                Object a = f.pop(), b = f.pop();
                f.push(b);
                f.push(a);
                f.push(b);
                f.push(a);
            }
            case DUP2_X1 -> {
                Object a = f.pop(), b = f.pop(), c = f.pop();
                f.push(b);
                f.push(a);
                f.push(c);
                f.push(b);
                f.push(a);
            }
            case DUP2_X2 -> {
                Object a = f.pop(), b = f.pop(), c = f.pop(), d = f.pop();
                f.push(b);
                f.push(a);
                f.push(d);
                f.push(c);
                f.push(b);
                f.push(a);
            }
            case SWAP -> {
                Object a = f.pop(), b = f.pop();
                f.push(a);
                f.push(b);
            }
            default -> {
                if (Effects.isArithmetic(op)) {
                    Object[] args = popOperands(f, op);
                    Object r = allConstant(args) ? Fold.eval(op, args) : null;
                    f.push(r == null ? Frame.TOP : r, Effects.resultSize(op));
                } else {
                    int[] e = Effects.simple(op);
                    for (int k = e[0]; k > 0; k--) f.pop();
                    f.push(Frame.TOP, e[1]);
                }
            }
        }
    }
}
