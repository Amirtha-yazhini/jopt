package jopt.ir;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Lowers the IR back to an ASM {@link MethodNode}: block layout, branch encoding, exception table. */
public final class IRWriter {
    private IRWriter() {}

    /**
     * Returns a new MethodNode with the same signature as {@code original} and code from {@code ir}.
     * Local variable tables are not carried over: they describe slots and ranges that no longer exist.
     */
    public static MethodNode toMethodNode(MethodIR ir, MethodNode original) {
        MethodNode m = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc, original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        List<Block> layout = ir.blocks;
        Map<Block, LabelNode> labels = new IdentityHashMap<>();
        for (Block b : layout) labels.put(b, new LabelNode());
        LabelNode end = new LabelNode();
        boolean[] emitted = new boolean[layout.size()];

        InsnList out = new InsnList();
        int lastLine = -1, pendingLine = -1;
        for (int i = 0; i < layout.size(); i++) {
            Block b = layout.get(i);
            Block next = i + 1 < layout.size() ? layout.get(i + 1) : null;
            out.add(labels.get(b));
            int before = out.size();
            for (Insn x : b.insns) {
                if (x instanceof Insn.Line ln) pendingLine = ln.line(); // emitted lazily, with the next instruction
                else lastLine = emit(out, lower(x), pendingLine, lastLine);
            }
            List<AbstractInsnNode> branch = new ArrayList<>(2);
            switch (b.term) {
                case Terminator.Goto g -> {
                    if (g.target() != next) branch.add(new JumpInsnNode(GOTO, labels.get(g.target())));
                }
                case Terminator.If f -> {
                    if (f.next() == next) {
                        branch.add(new JumpInsnNode(f.opcode(), labels.get(f.target())));
                    } else if (f.target() == next) {
                        branch.add(new JumpInsnNode(invert(f.opcode()), labels.get(f.next())));
                    } else {
                        branch.add(new JumpInsnNode(f.opcode(), labels.get(f.target())));
                        branch.add(new JumpInsnNode(GOTO, labels.get(f.next())));
                    }
                }
                case Terminator.Switch s -> branch.add(lowerSwitch(s, labels));
                case Terminator.Return r -> branch.add(new InsnNode(r.opcode()));
                case Terminator.Throw t -> branch.add(new InsnNode(ATHROW));
                case null -> throw new IllegalStateException(b + " falls off the end of " + ir.name);
            }
            for (AbstractInsnNode n : branch) lastLine = emit(out, n, pendingLine, lastLine);
            emitted[i] = hasRealInsn(out, before);
        }
        out.add(end);
        m.instructions = out;

        // Exception table: one entry per maximal run of consecutive blocks sharing a handler. Emitting
        // all runs of handler 0 first, then handler 1, ... preserves the original priority order.
        m.tryCatchBlocks = new ArrayList<>();
        for (Handler h : ir.handlers) {
            int i = 0;
            while (i < layout.size()) {
                if (!layout.get(i).handlers.contains(h)) {
                    i++;
                    continue;
                }
                int j = i;
                boolean code = false;
                while (j < layout.size() && layout.get(j).handlers.contains(h)) code |= emitted[j++];
                if (code) // a zero-length range is illegal in a class file
                    m.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(layout.get(i)),
                            j < layout.size() ? labels.get(layout.get(j)) : end, labels.get(h.target), h.type));
                i = j;
            }
        }
        m.maxLocals = ir.maxLocals();
        m.maxStack = maxStack(ir);
        return m;
    }

    /**
     * Appends an instruction, preceded by a line-number entry if the source line changed. Line
     * markers whose code was optimized away therefore leave no trace. Returns the current line.
     */
    private static int emit(InsnList out, AbstractInsnNode insn, int pendingLine, int lastLine) {
        if (pendingLine != -1 && pendingLine != lastLine) {
            LabelNode l = new LabelNode();
            out.add(l);
            out.add(new LineNumberNode(pendingLine, l));
            lastLine = pendingLine;
        }
        out.add(insn);
        return lastLine;
    }

    private static boolean hasRealInsn(InsnList list, int from) {
        AbstractInsnNode n = list.size() > from ? list.get(from) : null;
        for (; n != null; n = n.getNext()) if (n.getOpcode() >= 0) return true;
        return false;
    }

    public static int invert(int op) {
        if (op == IFNULL) return IFNONNULL;
        if (op == IFNONNULL) return IFNULL;
        return ((op - IFEQ) ^ 1) + IFEQ; // IFEQ/IFNE, IFLT/IFGE, ... are adjacent pairs
    }

    static AbstractInsnNode lower(Insn x) {
        return switch (x) {
            case Insn.Simple s -> new InsnNode(s.opcode());
            case Insn.Const c -> constant(c.value());
            case Insn.Load l -> new VarInsnNode(l.opcode(), l.var());
            case Insn.Store s -> new VarInsnNode(s.opcode(), s.var());
            case Insn.Iinc i -> new IincInsnNode(i.var(), i.increment());
            case Insn.Field f -> new FieldInsnNode(f.opcode(), f.owner(), f.name(), f.desc());
            case Insn.Invoke m -> new MethodInsnNode(m.opcode(), m.owner(), m.name(), m.desc(), m.itf());
            case Insn.Indy d -> new InvokeDynamicInsnNode(d.name(), d.desc(), d.bsm(), d.bsmArgs());
            case Insn.TypeOp t -> new TypeInsnNode(t.opcode(), t.type());
            case Insn.NewArray n -> new IntInsnNode(NEWARRAY, n.atype());
            case Insn.MultiNewArray m -> new MultiANewArrayInsnNode(m.desc(), m.dims());
            case Insn.Line l -> throw new IllegalArgumentException("line markers are lowered separately");
        };
    }

    /** The shortest encoding of a constant push. */
    static AbstractInsnNode constant(Object v) {
        if (v == null) return new InsnNode(ACONST_NULL);
        if (v instanceof Integer boxed) {
            int i = boxed;
            if (i >= -1 && i <= 5) return new InsnNode(ICONST_0 + i);
            if (i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, i);
            if (i >= Short.MIN_VALUE && i <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, i);
        } else if (v instanceof Long l) {
            if (l == 0L) return new InsnNode(LCONST_0);
            if (l == 1L) return new InsnNode(LCONST_1);
        } else if (v instanceof Float f) {
            if (Float.floatToRawIntBits(f) == 0) return new InsnNode(FCONST_0); // +0.0f only
            if (f == 1f) return new InsnNode(FCONST_1);
            if (f == 2f) return new InsnNode(FCONST_2);
        } else if (v instanceof Double d) {
            if (Double.doubleToRawLongBits(d) == 0L) return new InsnNode(DCONST_0);
            if (d == 1d) return new InsnNode(DCONST_1);
        }
        return new LdcInsnNode(v);
    }

    /** tableswitch when the key range is dense enough, using javac's cost heuristic; else lookupswitch. */
    private static AbstractInsnNode lowerSwitch(Terminator.Switch s, Map<Block, LabelNode> labels) {
        int[] keys = s.keys();
        int n = keys.length;
        LabelNode dflt = labels.get(s.dflt());
        if (n > 0) {
            long lo = keys[0], hi = keys[n - 1];
            long tableCost = 4 + (hi - lo + 1) + 3 * 3;
            long lookupCost = 3 + 2L * n + 3L * n;
            if (tableCost <= lookupCost) {
                LabelNode[] table = new LabelNode[(int) (hi - lo + 1)];
                java.util.Arrays.fill(table, dflt);
                for (int i = 0; i < n; i++) table[(int) (keys[i] - lo)] = labels.get(s.targets()[i]);
                return new TableSwitchInsnNode((int) lo, (int) hi, dflt, table);
            }
        }
        LabelNode[] ls = new LabelNode[n];
        for (int i = 0; i < n; i++) ls[i] = labels.get(s.targets()[i]);
        return new LookupSwitchInsnNode(dflt, keys, ls);
    }

    /** Maximum operand stack depth in words; also checks that heights agree at every join. */
    public static int maxStack(MethodIR ir) {
        Map<Block, Integer> height = new IdentityHashMap<>();
        Deque<Block> work = new ArrayDeque<>();
        height.put(ir.entry(), 0);
        work.add(ir.entry());
        int max = 0;
        while (!work.isEmpty()) {
            Block b = work.poll();
            int h = height.get(b);
            for (Insn x : b.insns) {
                h += Effects.pushWords(x) - Effects.popWords(x);
                if (h < 0) throw new IllegalStateException("stack underflow in " + b + " of " + ir.name);
                max = Math.max(max, h);
            }
            if (b.term == null) continue;
            h -= Effects.popWords(b.term);
            List<Block> next = new ArrayList<>(b.term.successors());
            for (Block s : next) flow(height, work, s, h, ir);
            for (Handler hd : b.handlers) flow(height, work, hd.target, 1, ir);
            if (!b.handlers.isEmpty()) max = Math.max(max, 1);
        }
        return max;
    }

    private static void flow(Map<Block, Integer> height, Deque<Block> work, Block s, int h, MethodIR ir) {
        Integer old = height.putIfAbsent(s, h);
        if (old == null) work.add(s);
        else if (old != h) throw new IllegalStateException("stack height mismatch at " + s + " in " + ir.name);
    }
}
