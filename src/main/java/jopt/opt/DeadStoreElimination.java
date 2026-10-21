package jopt.opt;

import jopt.ir.Block;
import jopt.ir.Handler;
import jopt.ir.Insn;
import jopt.ir.MethodIR;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;

/**
 * Liveness analysis over local variable slots, then: a store to a slot that is never read again
 * becomes a pop (which {@link Peephole} usually cancels against the push before it), and an
 * {@code iinc} of a dead slot disappears.
 *
 * <p>Exceptions: inside a try block, every slot the handler reads is live at every instruction,
 * because any of them may throw.
 */
public final class DeadStoreElimination implements Pass {
    @Override
    public String id() {
        return "dse";
    }

    @Override
    public int run(MethodIR ir, Counters stats) {
        Map<Block, BitSet> liveIn = new IdentityHashMap<>();
        for (Block b : ir.blocks) liveIn.put(b, new BitSet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = ir.blocks.size() - 1; i >= 0; i--) {
                Block b = ir.blocks.get(i);
                BitSet in = scan(b, liveOut(b, liveIn), handlerLive(b, liveIn), null);
                if (!in.equals(liveIn.get(b))) {
                    liveIn.put(b, in);
                    changed = true;
                }
            }
        }
        int removed = 0;
        for (Block b : ir.blocks) {
            int[] count = new int[1];
            scan(b, liveOut(b, liveIn), handlerLive(b, liveIn), count);
            removed += count[0];
        }
        stats.add("dead stores removed", removed);
        return removed;
    }

    /**
     * Walks {@code b} backwards from {@code out} and returns the live-in set. With {@code removed}
     * non-null, also rewrites dead stores and counts them.
     */
    private static BitSet scan(Block b, BitSet out, BitSet handlers, int[] removed) {
        BitSet live = (BitSet) out.clone();
        live.or(handlers);
        List<Insn> insns = b.insns;
        for (int k = insns.size() - 1; k >= 0; k--) {
            switch (insns.get(k)) {
                case Insn.Store s -> {
                    if (!live.get(s.var())) {
                        if (removed != null) {
                            insns.set(k, new Insn.Simple(s.size() == 2 ? POP2 : POP));
                            removed[0]++;
                        }
                    } else {
                        live.clear(s.var());
                    }
                }
                case Insn.Load l -> live.set(l.var());
                case Insn.Iinc i -> {
                    if (!live.get(i.var())) {
                        if (removed != null) {
                            insns.remove(k);
                            removed[0]++;
                        }
                    } else {
                        live.set(i.var());
                    }
                }
                default -> {}
            }
            live.or(handlers);
        }
        return live;
    }

    private static BitSet liveOut(Block b, Map<Block, BitSet> liveIn) {
        BitSet out = new BitSet();
        if (b.term != null) for (Block s : b.term.successors()) out.or(liveIn.get(s));
        return out;
    }

    private static BitSet handlerLive(Block b, Map<Block, BitSet> liveIn) {
        BitSet h = new BitSet();
        for (Handler hd : b.handlers) h.or(liveIn.get(hd.target));
        return h;
    }
}
