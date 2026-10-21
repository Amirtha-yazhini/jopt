package jopt.analysis;

import static org.objectweb.asm.Opcodes.*;

/**
 * Evaluates arithmetic and comparisons on constants with exact JVM semantics (Java's own operators
 * implement them: wrapping integer overflow, shift distances masked to 5/6 bits, IEEE floats,
 * saturating float-to-int conversion). Returns null when the result is not a compile-time constant,
 * e.g. for integer division by zero, which must still throw at run time.
 */
public final class Fold {
    private Fold() {}

    /** Evaluates arithmetic opcode {@code op} (96..152); {@code a} holds operands, first operand first. */
    public static Object eval(int op, Object[] a) {
        try {
            return switch (op) {
                case IADD -> i(a[0]) + i(a[1]);
                case LADD -> l(a[0]) + l(a[1]);
                case FADD -> f(a[0]) + f(a[1]);
                case DADD -> d(a[0]) + d(a[1]);
                case ISUB -> i(a[0]) - i(a[1]);
                case LSUB -> l(a[0]) - l(a[1]);
                case FSUB -> f(a[0]) - f(a[1]);
                case DSUB -> d(a[0]) - d(a[1]);
                case IMUL -> i(a[0]) * i(a[1]);
                case LMUL -> l(a[0]) * l(a[1]);
                case FMUL -> f(a[0]) * f(a[1]);
                case DMUL -> d(a[0]) * d(a[1]);
                case IDIV -> i(a[1]) == 0 ? null : (Object) (i(a[0]) / i(a[1]));
                case LDIV -> l(a[1]) == 0 ? null : (Object) (l(a[0]) / l(a[1]));
                case FDIV -> f(a[0]) / f(a[1]);
                case DDIV -> d(a[0]) / d(a[1]);
                case IREM -> i(a[1]) == 0 ? null : (Object) (i(a[0]) % i(a[1]));
                case LREM -> l(a[1]) == 0 ? null : (Object) (l(a[0]) % l(a[1]));
                case FREM -> f(a[0]) % f(a[1]);
                case DREM -> d(a[0]) % d(a[1]);
                case INEG -> -i(a[0]);
                case LNEG -> -l(a[0]);
                case FNEG -> -f(a[0]);
                case DNEG -> -d(a[0]);
                case ISHL -> i(a[0]) << i(a[1]);
                case LSHL -> l(a[0]) << i(a[1]);
                case ISHR -> i(a[0]) >> i(a[1]);
                case LSHR -> l(a[0]) >> i(a[1]);
                case IUSHR -> i(a[0]) >>> i(a[1]);
                case LUSHR -> l(a[0]) >>> i(a[1]);
                case IAND -> i(a[0]) & i(a[1]);
                case LAND -> l(a[0]) & l(a[1]);
                case IOR -> i(a[0]) | i(a[1]);
                case LOR -> l(a[0]) | l(a[1]);
                case IXOR -> i(a[0]) ^ i(a[1]);
                case LXOR -> l(a[0]) ^ l(a[1]);
                case I2L -> (long) i(a[0]);
                case I2F -> (float) i(a[0]);
                case I2D -> (double) i(a[0]);
                case L2I -> (int) l(a[0]);
                case L2F -> (float) l(a[0]);
                case L2D -> (double) l(a[0]);
                case F2I -> (int) f(a[0]);
                case F2L -> (long) f(a[0]);
                case F2D -> (double) f(a[0]);
                case D2I -> (int) d(a[0]);
                case D2L -> (long) d(a[0]);
                case D2F -> (float) d(a[0]);
                case I2B -> (int) (byte) i(a[0]);
                case I2C -> (int) (char) i(a[0]);
                case I2S -> (int) (short) i(a[0]);
                case LCMP -> Long.compare(l(a[0]), l(a[1]));
                case FCMPL -> cmp(f(a[0]), f(a[1]), -1);
                case FCMPG -> cmp(f(a[0]), f(a[1]), 1);
                case DCMPL -> cmp(d(a[0]), d(a[1]), -1);
                case DCMPG -> cmp(d(a[0]), d(a[1]), 1);
                default -> null;
            };
        } catch (ClassCastException e) {
            return null; // operand is not a constant of the expected type
        }
    }

    /**
     * Decides a conditional branch on constant operands: TRUE if taken, FALSE if not, null if unknown.
     * Reference operands are {@link Frame#NULL} or String literals (which the JVM interns, so equal
     * literals are the same object).
     */
    public static Boolean branch(int op, Object[] a) {
        if (op >= IFEQ && op <= IFLE) {
            if (!(a[0] instanceof Integer x)) return null;
            return compare(op - IFEQ, x, 0);
        }
        if (op >= IF_ICMPEQ && op <= IF_ICMPLE) {
            if (!(a[0] instanceof Integer x) || !(a[1] instanceof Integer y)) return null;
            return compare(op - IF_ICMPEQ, x, y);
        }
        if (op == IF_ACMPEQ || op == IF_ACMPNE) {
            Boolean eq = sameReference(a[0], a[1]);
            return eq == null ? null : (op == IF_ACMPEQ) == eq;
        }
        if (op == IFNULL || op == IFNONNULL) {
            if (a[0] == Frame.NULL) return op == IFNULL;
            if (a[0] instanceof String) return op == IFNONNULL;
        }
        return null;
    }

    private static boolean compare(int kind, int x, int y) {
        return switch (kind) {
            case 0 -> x == y;
            case 1 -> x != y;
            case 2 -> x < y;
            case 3 -> x >= y;
            case 4 -> x > y;
            default -> x <= y;
        };
    }

    private static Boolean sameReference(Object x, Object y) {
        boolean xs = x instanceof String, ys = y instanceof String;
        if (x == Frame.NULL && y == Frame.NULL) return true;
        if ((x == Frame.NULL && ys) || (xs && y == Frame.NULL)) return false;
        if (xs && ys) return x.equals(y);
        return null;
    }

    private static int cmp(double x, double y, int nan) {
        if (Double.isNaN(x) || Double.isNaN(y)) return nan;
        return x < y ? -1 : x > y ? 1 : 0;
    }

    private static int i(Object o) { return (Integer) o; }

    private static long l(Object o) { return (Long) o; }

    private static float f(Object o) { return (Float) o; }

    private static double d(Object o) { return (Double) o; }
}
