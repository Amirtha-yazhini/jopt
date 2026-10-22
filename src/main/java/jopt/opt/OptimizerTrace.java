package jopt.opt;

import jopt.ir.MethodIR;
import org.objectweb.asm.tree.MethodNode;

/**
 * Observes what {@link Optimizer} does to each method, step by step. Used by the playground to record
 * snapshots of the IR after every pass; the CLI does not trace. Implementations must not modify the
 * IR or method nodes they are given and must not throw.
 */
public interface OptimizerTrace {

    /** No tracing. */
    OptimizerTrace NONE = (owner, original) -> null;

    /**
     * Called before a method with code is optimized, while {@code original} is still unchanged.
     * Returns the observer for this method, or null to skip it.
     */
    MethodTrace method(String owner, MethodNode original);

    /** The class was written back unchanged even though methods were optimized (e.g. a type was missing). */
    default void classUnchanged(String owner, String reason) {}

    /** Why a method was, or was not, replaced. */
    enum Outcome {
        /** Optimized code was smaller and passed verification; it replaced the original. */
        REPLACED,
        /** No pass changed anything. */
        NOTHING_APPLIED,
        /** Passes ran, but the lowered code was not smaller, so the original was kept. */
        NO_GAIN,
        /** The lowered code failed ASM's verifier, so the original was kept. */
        VERIFY_FAILED,
        /** The method uses constructs the IR does not support (jsr/ret). */
        UNSUPPORTED,
        /** An unexpected exception; the original was kept. */
        INTERNAL_ERROR
    }

    interface MethodTrace {
        MethodTrace NONE = new MethodTrace() {
            public void irBuilt(MethodIR ir) {}

            public void passRan(int round, String pass, int changes, Counters what, MethodIR ir) {}

            public void finished(Outcome outcome, String detail, MethodNode lowered) {}
        };

        /** The IR right after it was built from bytecode, before any pass. */
        void irBuilt(MethodIR ir);

        /** One pass ran in {@code round} (1-based); {@code what} holds only this run's counts. */
        void passRan(int round, String pass, int changes, Counters what, MethodIR ir);

        /** The decision for this method; {@code lowered} is the code the writer produced, if it got that far. */
        void finished(Outcome outcome, String detail, MethodNode lowered);
    }
}
