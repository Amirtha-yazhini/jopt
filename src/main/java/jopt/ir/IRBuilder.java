package jopt.ir;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Builds the control-flow graph of a method from ASM's tree representation. */
public final class IRBuilder {
    private IRBuilder() {}

    /** Thrown for code the IR does not model (pre-Java-6 subroutines). */
    public static final class UnsupportedCodeException extends Exception {
        public UnsupportedCodeException(String msg) {
            super(msg);
        }
    }

    public static MethodIR build(String owner, MethodNode mn) throws UnsupportedCodeException {
        MethodIR ir = new MethodIR(owner, mn.name, mn.desc, mn.access);

        // 1. Labels that start a block: jump/switch targets and exception range boundaries.
        Set<LabelNode> leaders = new HashSet<>();
        for (AbstractInsnNode n : mn.instructions) {
            if (n.getOpcode() == JSR || n.getOpcode() == RET) throw new UnsupportedCodeException("jsr/ret subroutine");
            if (n instanceof JumpInsnNode j) leaders.add(j.label);
            else if (n instanceof TableSwitchInsnNode t) {
                leaders.add(t.dflt);
                leaders.addAll(t.labels);
            } else if (n instanceof LookupSwitchInsnNode l) {
                leaders.add(l.dflt);
                leaders.addAll(l.labels);
            }
        }
        for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
            leaders.add(tc.start);
            leaders.add(tc.end);
            leaders.add(tc.handler);
        }

        // 2. Split the instruction stream into blocks.
        Map<LabelNode, Block> labelBlocks = new HashMap<>();
        List<Block> order = new ArrayList<>();
        Block cur = ir.newBlock();
        order.add(cur);
        for (AbstractInsnNode n : mn.instructions) {
            if (n instanceof LabelNode l && leaders.contains(l)) {
                Block b = labelBlocks.computeIfAbsent(l, k -> ir.newBlock());
                if (cur != null && cur.term == null) cur.term = new Terminator.Goto(b);
                order.add(b);
                cur = b;
                continue;
            }
            if (n instanceof LabelNode || n instanceof FrameNode) continue;
            if (cur == null) { // dead code after an unconditional transfer
                cur = ir.newBlock();
                order.add(cur);
            }
            int op = n.getOpcode();
            switch (n) {
                case LineNumberNode ln -> cur.insns.add(new Insn.Line(ln.line));
                case InsnNode i -> {
                    if (op >= IRETURN && op <= RETURN) {
                        cur.term = new Terminator.Return(op);
                        cur = null;
                    } else if (op == ATHROW) {
                        cur.term = new Terminator.Throw();
                        cur = null;
                    } else {
                        cur.insns.add(constantOrSimple(op));
                    }
                }
                case IntInsnNode i -> cur.insns.add(op == NEWARRAY ? new Insn.NewArray(i.operand) : new Insn.Const(i.operand));
                case LdcInsnNode l -> cur.insns.add(new Insn.Const(l.cst));
                case VarInsnNode v -> cur.insns.add(op >= ISTORE ? new Insn.Store(op, v.var) : new Insn.Load(op, v.var));
                case IincInsnNode i -> cur.insns.add(new Insn.Iinc(i.var, i.incr));
                case FieldInsnNode f -> cur.insns.add(new Insn.Field(op, f.owner, f.name, f.desc));
                case MethodInsnNode m -> cur.insns.add(new Insn.Invoke(op, m.owner, m.name, m.desc, m.itf));
                case InvokeDynamicInsnNode d -> cur.insns.add(new Insn.Indy(d.name, d.desc, d.bsm, d.bsmArgs));
                case TypeInsnNode t -> cur.insns.add(new Insn.TypeOp(op, t.desc));
                case MultiANewArrayInsnNode m -> cur.insns.add(new Insn.MultiNewArray(m.desc, m.dims));
                case JumpInsnNode j -> {
                    Block target = labelBlocks.computeIfAbsent(j.label, k -> ir.newBlock());
                    if (op == GOTO) {
                        cur.term = new Terminator.Goto(target);
                        cur = null;
                    } else {
                        Block next = ir.newBlock();
                        cur.term = new Terminator.If(op, target, next);
                        order.add(next);
                        cur = next;
                    }
                }
                case TableSwitchInsnNode t -> {
                    int[] keys = new int[t.labels.size()];
                    Block[] targets = new Block[keys.length];
                    for (int k = 0; k < keys.length; k++) {
                        keys[k] = t.min + k;
                        targets[k] = labelBlocks.computeIfAbsent(t.labels.get(k), x -> ir.newBlock());
                    }
                    cur.term = new Terminator.Switch(keys, targets, labelBlocks.computeIfAbsent(t.dflt, x -> ir.newBlock()));
                    cur = null;
                }
                case LookupSwitchInsnNode l -> {
                    int[] keys = l.keys.stream().mapToInt(Integer::intValue).toArray();
                    Block[] targets = new Block[keys.length];
                    for (int k = 0; k < keys.length; k++)
                        targets[k] = labelBlocks.computeIfAbsent(l.labels.get(k), x -> ir.newBlock());
                    cur.term = new Terminator.Switch(keys, targets, labelBlocks.computeIfAbsent(l.dflt, x -> ir.newBlock()));
                    cur = null;
                }
                default -> throw new IllegalStateException("unexpected node " + n.getClass().getSimpleName());
            }
        }
        // A trailing block without terminator would fall off the method: only legal if unreachable.
        ir.blocks = order;

        // 3. Exception handlers: each covers the blocks laid out between its start and end labels.
        Map<Block, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < order.size(); i++) position.put(order.get(i), i);
        for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
            Handler h = new Handler(tc.type, labelBlocks.get(tc.handler));
            ir.handlers.add(h);
            int from = position.get(labelBlocks.get(tc.start)), to = position.get(labelBlocks.get(tc.end));
            for (int i = from; i < to; i++) order.get(i).handlers.add(h);
        }
        return ir;
    }

    private static Insn constantOrSimple(int op) {
        if (op >= ICONST_M1 && op <= ICONST_5) return new Insn.Const(op - ICONST_0);
        return switch (op) {
            case ACONST_NULL -> new Insn.Const(null);
            case LCONST_0 -> new Insn.Const(0L);
            case LCONST_1 -> new Insn.Const(1L);
            case FCONST_0 -> new Insn.Const(0f);
            case FCONST_1 -> new Insn.Const(1f);
            case FCONST_2 -> new Insn.Const(2f);
            case DCONST_0 -> new Insn.Const(0d);
            case DCONST_1 -> new Insn.Const(1d);
            default -> new Insn.Simple(op);
        };
    }
}
