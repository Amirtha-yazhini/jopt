package jopt.opt;

import jopt.ir.Block;
import jopt.ir.Effects;
import jopt.ir.Insn;
import jopt.ir.MethodIR;
import jopt.ir.Terminator;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Local rewrites over short instruction windows inside a block. Line markers are skipped when
 * matching, so patterns work across statement boundaries. Every rewrite makes the code smaller or
 * enables one that does, so repeating until nothing matches terminates.
 */
public final class Peephole implements Pass {
    @Override
    public String id() {
        return "peephole";
    }

    private record Rewrite(int length, List<Insn> replacement, String kind) {}

    @Override
    public int run(MethodIR ir, Counters stats) {
        int total = 0;
        for (Block b : ir.blocks) {
            boolean again = true;
            while (again) {
                again = false;
                List<Integer> pos = codePositions(b.insns);
                for (int r = 0; r < pos.size() && !again; r++) {
                    Rewrite rw = match(b.insns, pos, r);
                    if (rw != null) {
                        apply(b.insns, pos, r, rw);
                        stats.add(rw.kind());
                        total++;
                        again = true;
                    }
                }
                if (!again && simplifyBranch(b, pos, stats)) {
                    total++;
                    again = true;
                }
            }
        }
        return total;
    }

    private static List<Integer> codePositions(List<Insn> insns) {
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < insns.size(); i++) if (!(insns.get(i) instanceof Insn.Line)) pos.add(i);
        return pos;
    }

    private static void apply(List<Insn> insns, List<Integer> pos, int r, Rewrite rw) {
        for (int k = rw.length() - 1; k >= 0; k--) insns.remove((int) pos.get(r + k));
        insns.addAll(pos.get(r), rw.replacement());
    }

    private static Insn at(List<Insn> insns, List<Integer> pos, int r) {
        return r < pos.size() ? insns.get(pos.get(r)) : null;
    }

    private static Rewrite match(List<Insn> insns, List<Integer> pos, int r) {
        Insn a = at(insns, pos, r), b = at(insns, pos, r + 1), c = at(insns, pos, r + 2), d = at(insns, pos, r + 3);
        int bop = opcode(b);

        // push x; pop  ->  (nothing)
        int s = pureSize(a);
        if (s > 0 && (bop == POP || bop == POP2)) {
            if ((s == 1 && bop == POP) || (s == 2 && bop == POP2)) return rw(2, "unused values removed");
            if (s == 1) return rw(2, "unused values removed", new Insn.Simple(POP));
        }
        int aop = opcode(a);
        if ((aop == DUP && bop == POP) || (aop == DUP2 && bop == POP2)) return rw(2, "unused values removed");

        // pure computation whose result is discarded -> discard its operands instead
        if (aop >= 0 && Effects.isPure(aop) && (bop == POP || bop == POP2)
                && Effects.resultSize(aop) == (bop == POP ? 1 : 2))
            return new Rewrite(2, Effects.pops(Effects.inputSizes(aop)), "unused computations removed");
        if (a instanceof Insn.TypeOp t && t.opcode() == INSTANCEOF && bop == POP)
            return rw(2, "unused computations removed", new Insn.Simple(POP));

        // double negation, double swap
        if ((aop == INEG && bop == INEG) || (aop == LNEG && bop == LNEG) || (aop == SWAP && bop == SWAP))
            return rw(2, "algebraic identities");

        // load x; store x  ->  (nothing)
        if (a instanceof Insn.Load l && b instanceof Insn.Store st && st.var() == l.var() && st.opcode() == l.storeOpcode())
            return rw(2, "redundant load/store removed");
        // store x; load x  ->  dup; store x   (the store often dies later, leaving dup; pop)
        if (a instanceof Insn.Store st && b instanceof Insn.Load l && st.var() == l.var() && l.opcode() == st.loadOpcode())
            return rw(2, "store/load pairs", new Insn.Simple(st.size() == 2 ? DUP2 : DUP), st);

        // iload x; const k; iadd/isub; istore x  ->  iinc x k
        if (a instanceof Insn.Load l && l.opcode() == ILOAD && b instanceof Insn.Const k && k.value() instanceof Integer n
                && (opcode(c) == IADD || opcode(c) == ISUB)
                && d instanceof Insn.Store st && st.opcode() == ISTORE && st.var() == l.var()) {
            long inc = opcode(c) == IADD ? (long) n : -(long) n;
            if (inc >= Short.MIN_VALUE && inc <= Short.MAX_VALUE) return rw(4, "iinc formed", new Insn.Iinc(l.var(), (int) inc));
        }
        if (a instanceof Insn.Iinc i && i.increment() == 0) return rw(1, "algebraic identities");

        // x op const
        if (a instanceof Insn.Const k) {
            Rewrite alg = algebra(k.value(), bop);
            if (alg != null) return alg;
            // const op x  (commutative)  ->  x op const, when that enables an identity
            int cop = opcode(c);
            if (isCommutative(cop) && pureSize(b) == k.size() && b instanceof Insn.Load) {
                Rewrite swapped = algebra(k.value(), cop);
                if (swapped != null) {
                    List<Insn> repl = new ArrayList<>();
                    repl.add(b);
                    repl.addAll(swapped.replacement());
                    return new Rewrite(3, repl, swapped.kind());
                }
            }
        }
        return null;
    }

    /** Rewrites of {@code <const value> <op>}, where the constant is the second operand. */
    private static Rewrite algebra(Object v, int op) {
        if (op < 0) return null;
        if (v instanceof Integer boxed) {
            int k = boxed;
            switch (op) {
                case IADD, ISUB, IOR, IXOR -> { if (k == 0) return rw(2, "algebraic identities"); }
                case IMUL -> {
                    if (k == 1) return rw(2, "algebraic identities");
                    if (k == 0) return rw(2, "algebraic identities", new Insn.Simple(POP), new Insn.Const(0));
                    if (k == -1) return rw(2, "algebraic identities", new Insn.Simple(INEG));
                    if (Integer.bitCount(k) == 1)
                        return rw(2, "strength reductions", new Insn.Const(Integer.numberOfTrailingZeros(k)), new Insn.Simple(ISHL));
                }
                case IDIV -> {
                    if (k == 1) return rw(2, "algebraic identities");
                    if (k == -1) return rw(2, "algebraic identities", new Insn.Simple(INEG));
                }
                case IAND -> {
                    if (k == -1) return rw(2, "algebraic identities");
                    if (k == 0) return rw(2, "algebraic identities", new Insn.Simple(POP), new Insn.Const(0));
                }
                case ISHL, ISHR, IUSHR -> { if ((k & 31) == 0) return rw(2, "algebraic identities"); }
                case LSHL, LSHR, LUSHR -> { if ((k & 63) == 0) return rw(2, "algebraic identities"); }
                default -> {}
            }
            if (op == IOR && k == -1) return rw(2, "algebraic identities", new Insn.Simple(POP), new Insn.Const(-1));
        } else if (v instanceof Long boxed) {
            long k = boxed;
            switch (op) {
                case LADD, LSUB, LOR, LXOR -> { if (k == 0) return rw(2, "algebraic identities"); }
                case LMUL -> {
                    if (k == 1) return rw(2, "algebraic identities");
                    if (k == 0) return rw(2, "algebraic identities", new Insn.Simple(POP2), new Insn.Const(0L));
                    if (k == -1) return rw(2, "algebraic identities", new Insn.Simple(LNEG));
                    if (Long.bitCount(k) == 1)
                        return rw(2, "strength reductions", new Insn.Const(Long.numberOfTrailingZeros(k)), new Insn.Simple(LSHL));
                }
                case LDIV -> {
                    if (k == 1) return rw(2, "algebraic identities");
                    if (k == -1) return rw(2, "algebraic identities", new Insn.Simple(LNEG));
                }
                case LAND -> {
                    if (k == -1) return rw(2, "algebraic identities");
                    if (k == 0) return rw(2, "algebraic identities", new Insn.Simple(POP2), new Insn.Const(0L));
                }
                default -> {}
            }
            if (op == LOR && k == -1) return rw(2, "algebraic identities", new Insn.Simple(POP2), new Insn.Const(-1L));
        }
        return null;
    }

    /**
     * {@code iconst_0; if_icmpXX} -> {@code ifXX} and {@code aconst_null; if_acmpXX} -> {@code if[non]null}.
     */
    private static boolean simplifyBranch(Block b, List<Integer> pos, Counters stats) {
        if (!(b.term instanceof Terminator.If f) || pos.isEmpty()) return false;
        int last = pos.get(pos.size() - 1);
        if (!(b.insns.get(last) instanceof Insn.Const k)) return false;
        int op = f.opcode();
        int newOp;
        if (Integer.valueOf(0).equals(k.value()) && op >= IF_ICMPEQ && op <= IF_ICMPLE) newOp = op - (IF_ICMPEQ - IFEQ);
        else if (k.value() == null && op == IF_ACMPEQ) newOp = IFNULL;
        else if (k.value() == null && op == IF_ACMPNE) newOp = IFNONNULL;
        else return false;
        b.insns.remove(last);
        b.term = new Terminator.If(newOp, f.target(), f.next());
        stats.add("compare-with-zero branches");
        return true;
    }

    private static Rewrite rw(int length, String kind, Insn... replacement) {
        return new Rewrite(length, List.of(replacement), kind);
    }

    private static int opcode(Insn i) {
        return i instanceof Insn.Simple s ? s.opcode() : -1;
    }

    /** Size of the value pushed by a side-effect-free push (constant or local load), else 0. */
    private static int pureSize(Insn i) {
        if (i instanceof Insn.Const c && c.isPure()) return c.size();
        if (i instanceof Insn.Load l) return l.size();
        return 0;
    }

    private static boolean isCommutative(int op) {
        return switch (op) {
            case IADD, IMUL, IAND, IOR, IXOR, LADD, LMUL, LAND, LOR, LXOR -> true;
            default -> false;
        };
    }
}
