package jopt;

import jopt.analysis.Fold;
import jopt.analysis.Frame;
import jopt.io.ClassFiles;
import jopt.io.LinkCheck;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.objectweb.asm.Opcodes.*;

class UnitTest {

    @Test
    void foldKeepsResultTypes() {
        assertEquals(Integer.valueOf(5), Fold.eval(IADD, new Object[]{2, 3}));
        assertEquals(Long.valueOf(5), Fold.eval(LADD, new Object[]{2L, 3L}));
        assertEquals(Integer.valueOf(1), Fold.eval(LCMP, new Object[]{3L, 2L}));
        assertEquals(Integer.MIN_VALUE, Fold.eval(IDIV, new Object[]{Integer.MIN_VALUE, -1}));
        assertEquals(Integer.valueOf(2), Fold.eval(ISHL, new Object[]{1, 33}));
        assertEquals(Integer.MAX_VALUE, Fold.eval(F2I, new Object[]{Float.POSITIVE_INFINITY}));
        assertEquals(Integer.valueOf(-1), Fold.eval(FCMPL, new Object[]{Float.NaN, 1f}));
        assertEquals(Integer.valueOf(1), Fold.eval(FCMPG, new Object[]{Float.NaN, 1f}));
    }

    @Test
    void foldRefusesDivisionByZero() {
        assertNull(Fold.eval(IDIV, new Object[]{1, 0}));
        assertNull(Fold.eval(LREM, new Object[]{1L, 0L}));
    }

    @Test
    void branchesOnReferences() {
        assertEquals(Boolean.TRUE, Fold.branch(IFNULL, new Object[]{Frame.NULL}));
        assertEquals(Boolean.TRUE, Fold.branch(IFNONNULL, new Object[]{"s"}));
        assertEquals(Boolean.FALSE, Fold.branch(IF_ACMPEQ, new Object[]{"a", Frame.NULL}));
        assertNull(Fold.branch(IF_ACMPEQ, new Object[]{Frame.TOP, Frame.NULL}));
    }

    /** The link check must actually catch invalid bytecode, or every other test proves nothing. */
    @Test
    void linkCheckDetectsBrokenBytecode() throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "Broken", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(FCONST_1); // returns a float from an int method
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        LinkCheck.Result r = LinkCheck.run(List.of(new ClassFiles.Entry("Broken.class", cw.toByteArray())), List.of());
        assertFalse(r.ok());
    }
}
