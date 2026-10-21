package jopt.ir;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A method as a control-flow graph. {@code blocks} is in layout order; the first block is the entry. */
public final class MethodIR {
    public final String owner;
    public final String name;
    public final String desc;
    public final int access;
    public List<Block> blocks = new ArrayList<>();
    /** All handlers in priority order (the order of the class file's exception table). */
    public final List<Handler> handlers = new ArrayList<>();
    private int nextId;

    public MethodIR(String owner, String name, String desc, int access) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.access = access;
    }

    public Block newBlock() {
        return new Block(nextId++);
    }

    public Block entry() {
        return blocks.get(0);
    }

    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    /** Local slots used by the receiver and parameters. */
    public int paramWords() {
        int w = isStatic() ? 0 : 1;
        for (Type t : Type.getArgumentTypes(desc)) w += t.getSize();
        return w;
    }

    public int maxLocals() {
        int max = paramWords();
        for (Block b : blocks) {
            for (Insn i : b.insns) {
                switch (i) {
                    case Insn.Load l -> max = Math.max(max, l.var() + l.size());
                    case Insn.Store s -> max = Math.max(max, s.var() + s.size());
                    case Insn.Iinc c -> max = Math.max(max, c.var() + 1);
                    default -> {}
                }
            }
        }
        return max;
    }

    public Set<Block> handlerTargets() {
        Set<Block> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Handler h : handlers) s.add(h.target);
        return s;
    }

    /** Number of control-flow edges into each block (handler edges excluded). */
    public Map<Block, Integer> predecessorCounts() {
        Map<Block, Integer> preds = new IdentityHashMap<>();
        for (Block b : blocks) {
            if (b.term == null) continue;
            for (Block s : b.term.successors()) preds.merge(s, 1, Integer::sum);
        }
        return preds;
    }

    /** Blocks reachable from the entry, following normal and exceptional edges. */
    public Set<Block> reachable() {
        Set<Block> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Block> work = new ArrayList<>(List.of(entry()));
        while (!work.isEmpty()) {
            Block b = work.remove(work.size() - 1);
            if (!seen.add(b)) continue;
            if (b.term != null) work.addAll(b.term.successors());
            for (Handler h : b.handlers) work.add(h.target);
        }
        return seen;
    }

    /** Drops handlers no block refers to any more. */
    public void pruneHandlers() {
        Set<Handler> used = new HashSet<>();
        for (Block b : blocks) used.addAll(b.handlers);
        handlers.removeIf(h -> !used.contains(h));
    }

    public int instructionCount() {
        int n = 0;
        for (Block b : blocks) {
            for (Insn i : b.insns) if (!(i instanceof Insn.Line)) n++;
            n++;
        }
        return n;
    }
}
