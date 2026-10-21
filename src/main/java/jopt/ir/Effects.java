package jopt.ir;

import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * Stack effects, in JVM words (long/double take two), and the operand layout of the arithmetic
 * opcodes 96..152 that the folding and peephole passes reason about.
 */
public final class Effects {
    private Effects() {}

    public static int popWords(Insn i) {
        return switch (i) {
            case Insn.Simple s -> simple(s.opcode())[0];
            case Insn.Const c -> 0;
            case Insn.Load l -> 0;
            case Insn.Store s -> s.size();
            case Insn.Iinc c -> 0;
            case Insn.Field f -> switch (f.opcode()) {
                case GETSTATIC -> 0;
                case PUTSTATIC -> Type.getType(f.desc()).getSize();
                case GETFIELD -> 1;
                default -> 1 + Type.getType(f.desc()).getSize();
            };
            case Insn.Invoke m -> argWords(m.desc()) + (m.opcode() == INVOKESTATIC ? 0 : 1);
            case Insn.Indy d -> argWords(d.desc());
            case Insn.TypeOp t -> t.opcode() == NEW ? 0 : 1;
            case Insn.NewArray n -> 1;
            case Insn.MultiNewArray m -> m.dims();
            case Insn.Line l -> 0;
        };
    }

    public static int pushWords(Insn i) {
        return switch (i) {
            case Insn.Simple s -> simple(s.opcode())[1];
            case Insn.Const c -> c.size();
            case Insn.Load l -> l.size();
            case Insn.Store s -> 0;
            case Insn.Iinc c -> 0;
            case Insn.Field f -> f.opcode() == GETSTATIC || f.opcode() == GETFIELD ? Type.getType(f.desc()).getSize() : 0;
            case Insn.Invoke m -> Type.getReturnType(m.desc()).getSize();
            case Insn.Indy d -> Type.getReturnType(d.desc()).getSize();
            case Insn.TypeOp t -> 1;
            case Insn.NewArray n -> 1;
            case Insn.MultiNewArray m -> 1;
            case Insn.Line l -> 0;
        };
    }

    public static int popWords(Terminator t) {
        return switch (t) {
            case Terminator.Goto g -> 0;
            case Terminator.If f -> f.popWords();
            case Terminator.Switch s -> 1;
            case Terminator.Return r -> switch (r.opcode()) {
                case RETURN -> 0;
                case LRETURN, DRETURN -> 2;
                default -> 1;
            };
            case Terminator.Throw th -> 1;
        };
    }

    private static int argWords(String desc) {
        int w = 0;
        for (Type t : Type.getArgumentTypes(desc)) w += t.getSize();
        return w;
    }

    /** {pop, push} in words for a {@link Insn.Simple} opcode. */
    public static int[] simple(int op) {
        if (op >= IADD && op <= DCMPG && op != IINC) {
            int pop = 0;
            for (int s : inputSizes(op)) pop += s;
            return new int[]{pop, resultSize(op)};
        }
        return switch (op) {
            case NOP -> new int[]{0, 0};
            case IALOAD, FALOAD, AALOAD, BALOAD, CALOAD, SALOAD -> new int[]{2, 1};
            case LALOAD, DALOAD -> new int[]{2, 2};
            case IASTORE, FASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> new int[]{3, 0};
            case LASTORE, DASTORE -> new int[]{4, 0};
            case POP -> new int[]{1, 0};
            case POP2 -> new int[]{2, 0};
            case DUP -> new int[]{1, 2};
            case DUP_X1 -> new int[]{2, 3};
            case DUP_X2 -> new int[]{3, 4};
            case DUP2 -> new int[]{2, 4};
            case DUP2_X1 -> new int[]{3, 5};
            case DUP2_X2 -> new int[]{4, 6};
            case SWAP -> new int[]{2, 2};
            case ARRAYLENGTH -> new int[]{1, 1};
            case MONITORENTER, MONITOREXIT -> new int[]{1, 0};
            default -> throw new IllegalArgumentException("not a simple opcode: " + op);
        };
    }

    private static int typeSize(int t) {
        return t == 1 || t == 3 ? 2 : 1; // 0 int, 1 long, 2 float, 3 double
    }

    /** Operand sizes for opcodes 96..152, bottom (first operand) first. */
    public static int[] inputSizes(int op) {
        if (op >= IADD && op <= DREM) {
            int s = typeSize((op - IADD) % 4);
            return new int[]{s, s};
        }
        if (op >= INEG && op <= DNEG) return new int[]{typeSize(op - INEG)};
        if (op >= ISHL && op <= LUSHR) return new int[]{(op - ISHL) % 2 == 1 ? 2 : 1, 1};
        if (op >= IAND && op <= LXOR) {
            int s = (op - IAND) % 2 == 1 ? 2 : 1;
            return new int[]{s, s};
        }
        return switch (op) {
            case I2L, I2F, I2D, F2I, F2L, F2D, I2B, I2C, I2S -> new int[]{1};
            case L2I, L2F, L2D, D2I, D2L, D2F -> new int[]{2};
            case LCMP, DCMPL, DCMPG -> new int[]{2, 2};
            case FCMPL, FCMPG -> new int[]{1, 1};
            default -> throw new IllegalArgumentException("not an arithmetic opcode: " + op);
        };
    }

    /** Result size for opcodes 96..152. */
    public static int resultSize(int op) {
        if (op >= IADD && op <= DREM) return typeSize((op - IADD) % 4);
        if (op >= INEG && op <= DNEG) return typeSize(op - INEG);
        if (op >= ISHL && op <= LUSHR) return (op - ISHL) % 2 == 1 ? 2 : 1;
        if (op >= IAND && op <= LXOR) return (op - IAND) % 2 == 1 ? 2 : 1;
        return switch (op) {
            case I2L, I2D, F2L, F2D, L2D, D2L -> 2;
            default -> 1;
        };
    }

    /** True for arithmetic the folder can evaluate (96..152). */
    public static boolean isArithmetic(int op) {
        return op >= IADD && op <= DCMPG && op != IINC;
    }

    /** Arithmetic that can never throw, so an unused result can be dropped along with the operation. */
    public static boolean isPure(int op) {
        return isArithmetic(op) && op != IDIV && op != LDIV && op != IREM && op != LREM;
    }

    /** POP/POP2 instructions that discard values of the given sizes (bottom first), never splitting a long. */
    public static java.util.List<Insn> pops(int... sizes) {
        java.util.List<Insn> out = new java.util.ArrayList<>();
        int k = sizes.length - 1;
        while (k >= 0) {
            if (sizes[k] == 2) {
                out.add(new Insn.Simple(POP2));
                k--;
            } else if (k >= 1 && sizes[k - 1] == 1) {
                out.add(new Insn.Simple(POP2));
                k -= 2;
            } else {
                out.add(new Insn.Simple(POP));
                k--;
            }
        }
        return out;
    }
}
