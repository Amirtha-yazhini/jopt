package jopt.ir;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;

import static org.objectweb.asm.Opcodes.DLOAD;
import static org.objectweb.asm.Opcodes.DSTORE;
import static org.objectweb.asm.Opcodes.LLOAD;
import static org.objectweb.asm.Opcodes.LSTORE;

/**
 * A non-branching instruction inside a basic block. Control transfer lives in {@link Terminator}.
 *
 * <p>The IR stays stack-based (like bytecode) so it maps back to class files without register
 * allocation, but it normalizes encodings: every constant push ({@code iconst_*}, {@code bipush},
 * {@code ldc}, ...) is a single {@link Const}, and the writer picks the shortest encoding.
 */
public sealed interface Insn {

    /** Opcodes without operands: arithmetic, conversions, array access, stack shuffles, monitors. */
    record Simple(int opcode) implements Insn {}

    /**
     * Pushes a constant: Integer, Long, Float, Double, String, {@code null} (aconst_null), or an ASM
     * Type / Handle / ConstantDynamic for class literals, method handles and dynamic constants.
     */
    record Const(Object value) implements Insn {
        public int size() {
            if (value instanceof Long || value instanceof Double) return 2;
            if (value instanceof ConstantDynamic cd) return cd.getSize();
            return 1;
        }

        /** True if pushing this constant can be removed without changing behaviour. */
        public boolean isPure() {
            return value == null || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof String;
        }
    }

    record Load(int opcode, int var) implements Insn {
        public int size() { return opcode == LLOAD || opcode == DLOAD ? 2 : 1; }

        /** The store opcode for the same type (ILOAD -> ISTORE, ...). */
        public int storeOpcode() { return opcode + 33; }
    }

    record Store(int opcode, int var) implements Insn {
        public int size() { return opcode == LSTORE || opcode == DSTORE ? 2 : 1; }

        public int loadOpcode() { return opcode - 33; }
    }

    record Iinc(int var, int increment) implements Insn {}

    record Field(int opcode, String owner, String name, String desc) implements Insn {}

    record Invoke(int opcode, String owner, String name, String desc, boolean itf) implements Insn {}

    record Indy(String name, String desc, Handle bsm, Object[] bsmArgs) implements Insn {}

    /** NEW, ANEWARRAY, CHECKCAST, INSTANCEOF. */
    record TypeOp(int opcode, String type) implements Insn {}

    /** NEWARRAY of a primitive type ({@code T_INT}, ...). */
    record NewArray(int atype) implements Insn {}

    record MultiNewArray(String desc, int dims) implements Insn {}

    /** Pseudo-instruction carrying a source line number; generates no code. */
    record Line(int line) implements Insn {}
}
