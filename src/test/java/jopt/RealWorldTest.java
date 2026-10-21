package jopt;

import jopt.io.ClassFiles;
import jopt.io.ClassHierarchy;
import jopt.io.LinkCheck;
import jopt.ir.IRBuilder;
import jopt.ir.IRWriter;
import jopt.ir.MethodIR;
import jopt.opt.Optimizer;
import jopt.opt.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the optimizer on real-world bytecode: the ASM library jar itself (hundreds of classes, big
 * switch-heavy methods, exception handlers). The optimized ASM must pass the JVM verifier and still
 * produce byte-for-byte the same output as the original when used to transform class files.
 */
class RealWorldTest {
    static Path asmJar;
    static List<ClassFiles.Entry> original;

    @BeforeAll
    static void load() throws Exception {
        asmJar = Path.of(ClassReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        original = ClassFiles.read(asmJar);
    }

    private static List<ClassFiles.Entry> optimize(Report r) throws Exception {
        List<ClassFiles.Entry> out = new ArrayList<>();
        try (ClassHierarchy h = new ClassHierarchy(List.of())) {
            for (ClassFiles.Entry e : original) if (e.isClass()) h.add(e.data());
            Optimizer opt = new Optimizer(Set.of(), h, r, false);
            for (ClassFiles.Entry e : original)
                out.add(e.isClass() ? new ClassFiles.Entry(e.path(), opt.optimizeClass(e.data())) : e);
        }
        return out;
    }

    @Test
    void irRoundTripWithoutPassesKeepsEveryClassValid() throws Exception {
        List<ClassFiles.Entry> out = new ArrayList<>();
        try (ClassHierarchy h = new ClassHierarchy(List.of())) {
            for (ClassFiles.Entry e : original) if (e.isClass()) h.add(e.data());
            for (ClassFiles.Entry e : original) {
                if (!e.isClass()) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(e.data()).accept(cn, 0);
                List<MethodNode> methods = new ArrayList<>();
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions.size() == 0) {
                        methods.add(mn);
                        continue;
                    }
                    MethodIR ir = IRBuilder.build(cn.name, mn);
                    MethodNode back = IRWriter.toMethodNode(ir, mn);
                    back.visibleAnnotations = mn.visibleAnnotations;
                    methods.add(back);
                }
                cn.methods = methods;
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected String getCommonSuperClass(String a, String b) {
                        return h.commonSuperClass(a, b);
                    }
                };
                cn.accept(cw);
                out.add(new ClassFiles.Entry(e.path(), cw.toByteArray()));
            }
        }
        LinkCheck.Result res = LinkCheck.run(out, List.of());
        assertTrue(res.ok(), () -> String.join("\n", res.failures()));
        assertTrue(res.linked() > 30, "linked " + res.linked());
    }

    @Test
    void optimizedAsmPassesTheJvmVerifier() throws Exception {
        Report r = new Report();
        List<ClassFiles.Entry> out = optimize(r);
        assertTrue(r.methodsChanged > 0, "nothing optimized");
        assertTrue(r.warnings.isEmpty(), () -> String.join("\n", r.warnings));
        LinkCheck.Result res = LinkCheck.run(out, List.of());
        assertTrue(res.ok(), () -> String.join("\n", res.failures()));
        assertEquals(0, res.skipped().size(), () -> String.join("\n", res.skipped()));
    }

    @Test
    void optimizedAsmBehavesExactlyLikeTheOriginal() throws Exception {
        List<ClassFiles.Entry> out = optimize(new Report());
        Map<String, byte[]> before = new HashMap<>(), after = new HashMap<>();
        for (ClassFiles.Entry e : original) if (e.isClass()) before.put(name(e), e.data());
        for (ClassFiles.Entry e : out) if (e.isClass()) after.put(name(e), e.data());
        ClassLoader lb = new TestSupport.IsolatedLoader(before), la = new TestSupport.IsolatedLoader(after);

        // Use each version of ASM to rewrite every class of the jar (with frame expansion, the
        // most code-heavy path) and compare the produced bytes.
        int compared = 0;
        for (ClassFiles.Entry e : original) {
            if (!e.isClass()) continue;
            assertArrayEquals(transform(lb, e.data()), transform(la, e.data()), e.path());
            compared++;
        }
        assertTrue(compared > 30);
    }

    private static String name(ClassFiles.Entry e) {
        return e.path().substring(0, e.path().length() - 6).replace('/', '.');
    }

    private static byte[] transform(ClassLoader asm, byte[] input) throws Exception {
        Class<?> reader = asm.loadClass("org.objectweb.asm.ClassReader");
        Class<?> writer = asm.loadClass("org.objectweb.asm.ClassWriter");
        Class<?> visitor = asm.loadClass("org.objectweb.asm.ClassVisitor");
        Object cr = reader.getConstructor(byte[].class).newInstance((Object) input);
        Object cw = writer.getConstructor(int.class).newInstance(ClassWriter.COMPUTE_MAXS);
        reader.getMethod("accept", visitor, int.class).invoke(cr, cw, ClassReader.EXPAND_FRAMES);
        return (byte[]) writer.getMethod("toByteArray").invoke(cw);
    }
}
