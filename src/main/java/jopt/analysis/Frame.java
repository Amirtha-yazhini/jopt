package jopt.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Abstract machine state for constant propagation, one entry per JVM word. A word is a constant
 * (Integer, Long, Float, Double, String or {@link #NULL}), {@link #TOP} (unknown), or {@link #HIGH}
 * (the second word of a long/double, which sits above / after the word holding the value).
 */
public final class Frame {
    public static final Object TOP = new Sentinel("?");
    public static final Object HIGH = new Sentinel("^");
    public static final Object NULL = new Sentinel("null");

    final Object[] locals;
    final List<Object> stack;

    public Frame(int nLocals) {
        locals = new Object[nLocals];
        Arrays.fill(locals, TOP);
        stack = new ArrayList<>();
    }

    private Frame(Object[] locals, List<Object> stack) {
        this.locals = locals;
        this.stack = stack;
    }

    public Frame copy() {
        return new Frame(locals.clone(), new ArrayList<>(stack));
    }

    /** The frame an exception handler starts with: same locals, stack holding just the exception. */
    public Frame forHandler() {
        List<Object> s = new ArrayList<>();
        s.add(TOP);
        return new Frame(locals.clone(), s);
    }

    public static boolean isConstant(Object v) {
        return v != TOP && v != HIGH;
    }

    public Object local(int i) {
        return locals[i];
    }

    void setLocal(int var, Object v, int size) {
        if (locals[var] == HIGH && var > 0) locals[var - 1] = TOP; // overwrote half of a long/double
        locals[var] = v;
        if (size == 2) locals[var + 1] = HIGH;
    }

    public void push(Object v) {
        stack.add(v);
    }

    public void push2(Object v) {
        stack.add(v);
        stack.add(HIGH);
    }

    public void push(Object v, int size) {
        if (size == 2) push2(v);
        else if (size == 1) push(v);
    }

    public Object pop() {
        return stack.remove(stack.size() - 1);
    }

    public Object pop2() {
        pop();
        return pop();
    }

    public Object pop(int size) {
        return size == 2 ? pop2() : pop();
    }

    public int stackSize() {
        return stack.size();
    }

    /** Merges {@code other} into this frame (pointwise: equal constants stay, anything else becomes TOP). */
    public boolean mergeFrom(Frame other) {
        if (other.stack.size() != stack.size())
            throw new IllegalStateException("stack height mismatch: " + stack.size() + " vs " + other.stack.size());
        boolean changed = false;
        for (int i = 0; i < locals.length; i++) {
            if (locals[i] != TOP && !Objects.equals(locals[i], other.locals[i])) {
                locals[i] = TOP;
                changed = true;
            }
        }
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i) != TOP && !Objects.equals(stack.get(i), other.stack.get(i))) {
                stack.set(i, TOP);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public String toString() {
        return "locals=" + Arrays.toString(locals) + " stack=" + stack;
    }

    private record Sentinel(String name) {
        @Override
        public String toString() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
