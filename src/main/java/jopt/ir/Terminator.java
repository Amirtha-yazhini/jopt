package jopt.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** The last instruction of a basic block: where control goes next. */
public sealed interface Terminator {

    List<Block> successors();

    /** Returns a terminator with every target mapped through {@code f}, or {@code this} if none changed. */
    Terminator mapTargets(UnaryOperator<Block> f);

    record Goto(Block target) implements Terminator {
        public List<Block> successors() { return List.of(target); }

        public Terminator mapTargets(UnaryOperator<Block> f) {
            Block t = f.apply(target);
            return t == target ? this : new Goto(t);
        }
    }

    /** Conditional branch: jumps to {@code target} if the condition holds, else continues at {@code next}. */
    record If(int opcode, Block target, Block next) implements Terminator {
        public List<Block> successors() { return List.of(target, next); }

        public Terminator mapTargets(UnaryOperator<Block> f) {
            Block t = f.apply(target), n = f.apply(next);
            return t == target && n == next ? this : new If(opcode, t, n);
        }

        /** Number of stack words the comparison consumes. */
        public int popWords() { return opcode >= 159 && opcode <= 166 ? 2 : 1; }
    }

    /** tableswitch/lookupswitch; {@code keys} are sorted ascending and parallel to {@code targets}. */
    record Switch(int[] keys, Block[] targets, Block dflt) implements Terminator {
        public List<Block> successors() {
            List<Block> s = new ArrayList<>(List.of(targets));
            s.add(dflt);
            return s;
        }

        public Block targetFor(int key) {
            for (int i = 0; i < keys.length; i++) if (keys[i] == key) return targets[i];
            return dflt;
        }

        public Terminator mapTargets(UnaryOperator<Block> f) {
            boolean changed = false;
            Block[] ts = new Block[targets.length];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = f.apply(targets[i]);
                changed |= ts[i] != targets[i];
            }
            Block d = f.apply(dflt);
            return changed || d != dflt ? new Switch(keys, ts, d) : this;
        }
    }

    record Return(int opcode) implements Terminator {
        public List<Block> successors() { return List.of(); }

        public Terminator mapTargets(UnaryOperator<Block> f) { return this; }
    }

    record Throw() implements Terminator {
        public List<Block> successors() { return List.of(); }

        public Terminator mapTargets(UnaryOperator<Block> f) { return this; }
    }
}
