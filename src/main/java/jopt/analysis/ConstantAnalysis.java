package jopt.analysis;

import jopt.ir.Block;
import jopt.ir.Handler;
import jopt.ir.Insn;
import jopt.ir.MethodIR;
import jopt.ir.Terminator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Forward dataflow analysis computing, for every block, which locals and stack words hold a known
 * constant on entry. Branches whose outcome is known propagate only along the taken edge
 * (conditional constant propagation), so dead code does not dilute facts at joins. A block with no
 * entry frame is unreachable.
 */
public final class ConstantAnalysis {
    private final Map<Block, Frame> entry = new IdentityHashMap<>();
    private final Deque<Block> work = new ArrayDeque<>();
    private final Set<Block> queued = Collections.newSetFromMap(new IdentityHashMap<>());

    private ConstantAnalysis() {}

    /** A copy of the frame on entry to {@code b}, or null if {@code b} is unreachable. */
    public Frame entryFrame(Block b) {
        Frame f = entry.get(b);
        return f == null ? null : f.copy();
    }

    public static ConstantAnalysis run(MethodIR ir) {
        ConstantAnalysis a = new ConstantAnalysis();
        a.flow(ir.entry(), new Frame(ir.maxLocals()));
        while (!a.work.isEmpty()) {
            Block b = a.work.poll();
            a.queued.remove(b);
            Frame f = a.entry.get(b).copy();
            for (Insn x : b.insns) {
                a.toHandlers(b, f); // an exception may be thrown before any instruction
                Interp.exec(f, x);
            }
            a.toHandlers(b, f);
            switch (b.term) {
                case Terminator.Goto g -> a.flow(g.target(), f);
                case Terminator.If i -> {
                    Boolean taken = Fold.branch(i.opcode(), Interp.popBranchOperands(f, i.popWords()));
                    if (taken == null || taken) a.flow(i.target(), f);
                    if (taken == null || !taken) a.flow(i.next(), f);
                }
                case Terminator.Switch s -> {
                    if (f.pop() instanceof Integer key) a.flow(s.targetFor(key), f);
                    else for (Block t : s.successors()) a.flow(t, f);
                }
                case null, default -> {}
            }
        }
        return a;
    }

    private void toHandlers(Block b, Frame f) {
        for (Handler h : b.handlers) flow(h.target, f.forHandler());
    }

    private void flow(Block to, Frame f) {
        Frame cur = entry.get(to);
        boolean changed;
        if (cur == null) {
            entry.put(to, f.copy());
            changed = true;
        } else {
            changed = cur.mergeFrom(f);
        }
        if (changed && queued.add(to)) work.add(to);
    }
}
