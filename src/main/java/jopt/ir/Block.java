package jopt.ir;

import java.util.ArrayList;
import java.util.List;

/** A basic block: straight-line instructions followed by one terminator. Identity-compared. */
public final class Block {
    public final int id;
    public List<Insn> insns = new ArrayList<>();
    /** Null only for a block that falls off the end of the method (must be unreachable). */
    public Terminator term;
    /** Exception handlers covering every instruction of this block, in priority order. */
    public List<Handler> handlers = new ArrayList<>();

    Block(int id) {
        this.id = id;
    }

    /** True if the block has instructions that generate code (line markers don't count). */
    public boolean hasCode() {
        for (Insn i : insns) if (!(i instanceof Insn.Line)) return true;
        return false;
    }

    @Override
    public String toString() {
        return "B" + id;
    }
}
