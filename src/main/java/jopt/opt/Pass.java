package jopt.opt;

import jopt.ir.MethodIR;

/** A transformation of one method's IR. */
public interface Pass {
    /** Short identifier used on the command line (e.g. {@code --disable peephole}). */
    String id();

    /** Rewrites {@code ir} in place, records what it did in {@code stats}, and returns the number of changes. */
    int run(MethodIR ir, Counters stats);
}
