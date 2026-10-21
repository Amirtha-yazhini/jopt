package jopt.opt;

import jopt.ir.Block;
import jopt.ir.Effects;
import jopt.ir.Insn;
import jopt.ir.MethodIR;
import jopt.ir.Terminator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.POP;

/**
 * Control-flow cleanup:
 * <ul>
 *   <li>jump threading: a jump to an empty block that just jumps on goes straight to the final target;</li>
 *   <li>a conditional branch or switch whose targets are all the same becomes a pop and a goto;</li>
 *   <li>unreachable blocks are deleted;</li>
 *   <li>a block that is the only predecessor of its single successor absorbs it.</li>
 * </ul>
 */
public final class CfgSimplify implements Pass {
    @Override
    public String id() {
        return "cfg";
    }

    @Override
    public int run(MethodIR ir, Counters stats) {
        int changes = 0;

        for (Block b : ir.blocks) {
            if (b.term == null) continue;
            Terminator t = b.term.mapTargets(CfgSimplify::thread);
            if (t != b.term) {
                b.term = t;
                stats.add("jumps threaded");
                changes++;
            }
        }

        for (Block b : ir.blocks) {
            if (b.term instanceof Terminator.If f && f.target() == f.next()) {
                b.insns.addAll(Effects.pops(f.popWords() == 2 ? new int[]{1, 1} : new int[]{1}));
                b.term = new Terminator.Goto(f.target());
                stats.add("branches folded");
                changes++;
            } else if (b.term instanceof Terminator.Switch s && allSame(s)) {
                b.insns.add(new Insn.Simple(POP));
                b.term = new Terminator.Goto(s.dflt());
                stats.add("switches folded");
                changes++;
            }
        }

        Set<Block> reachable = ir.reachable();
        int before = ir.blocks.size();
        ir.blocks.removeIf(b -> !reachable.contains(b));
        int dead = before - ir.blocks.size();
        if (dead > 0) {
            ir.pruneHandlers();
            stats.add("unreachable blocks removed", dead);
            changes += dead;
        }

        Map<Block, Integer> preds = ir.predecessorCounts();
        Set<Block> handlerTargets = ir.handlerTargets();
        Set<Block> merged = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block b : ir.blocks) {
            if (merged.contains(b)) continue;
            while (b.term instanceof Terminator.Goto g) {
                Block s = g.target();
                if (s == b || s == ir.entry() || preds.getOrDefault(s, 0) != 1 || handlerTargets.contains(s)) break;
                if (!b.handlers.equals(s.handlers)) {
                    if (b.hasCode()) break;
                    b.handlers = new ArrayList<>(s.handlers); // nothing in b can throw
                }
                b.insns.addAll(s.insns);
                b.term = s.term;
                merged.add(s);
                stats.add("blocks merged");
                changes++;
            }
        }
        if (!merged.isEmpty()) ir.blocks.removeIf(merged::contains);
        return changes;
    }

    /** Follows chains of empty goto-only blocks (bounded, in case of an empty infinite loop). */
    private static Block thread(Block t) {
        for (int hops = 0; hops < 64 && !t.hasCode() && t.term instanceof Terminator.Goto g && g.target() != t; hops++)
            t = g.target();
        return t;
    }

    private static boolean allSame(Terminator.Switch s) {
        for (Block t : s.targets()) if (t != s.dflt()) return false;
        return true;
    }
}
