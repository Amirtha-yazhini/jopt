package jopt.opt;

import jopt.io.Bytecode;
import jopt.io.ClassHierarchy;
import jopt.ir.IRBuilder;
import jopt.ir.IRWriter;
import jopt.ir.MethodIR;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the passes over every method of a class until none of them changes anything, then keeps the
 * result only if it is smaller and passes ASM's bytecode verifier. Anything unexpected leaves the
 * method (or class) exactly as it was: the optimizer may miss opportunities but must never break code.
 */
public final class Optimizer {
    private static final int MAX_ROUNDS = 32;

    public static List<Pass> allPasses() {
        return List.of(new ConstantFolding(), new Peephole(), new DeadStoreElimination(), new CfgSimplify());
    }

    private final List<Pass> passes;
    private final ClassHierarchy hierarchy;
    private final Report report;
    private final boolean keepListings;

    public Optimizer(Set<String> disabled, ClassHierarchy hierarchy, Report report, boolean keepListings) {
        this.passes = allPasses().stream().filter(p -> !disabled.contains(p.id())).toList();
        this.hierarchy = hierarchy;
        this.report = report;
        this.keepListings = keepListings;
    }

    /** Returns the optimized class file, or {@code classFile} itself if nothing improved. */
    public byte[] optimizeClass(byte[] classFile) {
        ClassNode cn = new ClassNode();
        new ClassReader(classFile).accept(cn, 0);
        report.classes++;
        report.fileBytesBefore += classFile.length;

        List<Report.MethodChange> changes = new ArrayList<>();
        long insBefore = 0, insAfter = 0, bytesBefore = 0, bytesAfter = 0;
        for (MethodNode mn : cn.methods) {
            report.methods++;
            int ib = Bytecode.instructions(mn.instructions), bb = Bytecode.bytes(mn.instructions);
            Report.MethodChange c = ib == 0 ? null : optimizeMethod(cn.name, mn);
            insBefore += ib;
            bytesBefore += bb;
            insAfter += c == null ? ib : c.instructionsAfter();
            bytesAfter += c == null ? bb : c.bytesAfter();
            if (c != null) changes.add(c);
        }

        byte[] out = classFile;
        if (!changes.isEmpty()) {
            try {
                // Stack map frames exist since Java 6 (version 50); older class files must not gain them.
                int flags = (cn.version & 0xFFFF) >= Opcodes.V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                ClassWriter cw = new ClassWriter(flags) {
                    @Override
                    protected String getCommonSuperClass(String a, String b) {
                        return hierarchy.commonSuperClass(a, b);
                    }
                };
                cn.accept(cw);
                out = cw.toByteArray();
            } catch (ClassHierarchy.TypeNotFoundException e) {
                report.warnings.add(cn.name.replace('/', '.') + ": left unchanged, type " + e.getMessage()
                        + " not found (add its jar with --cp)");
                changes.clear();
                insAfter = insBefore;
                bytesAfter = bytesBefore;
            }
        }
        if (!changes.isEmpty()) {
            report.classesChanged++;
            report.methodsChanged += changes.size();
            report.changes.addAll(changes);
            for (Report.MethodChange c : changes) report.counters.addAll(c.what());
        }
        report.instructionsBefore += insBefore;
        report.instructionsAfter += insAfter;
        report.codeBytesBefore += bytesBefore;
        report.codeBytesAfter += bytesAfter;
        report.fileBytesAfter += out.length;
        return out;
    }

    /** Optimizes {@code mn} in place; returns what changed, or null if the method was left alone. */
    private Report.MethodChange optimizeMethod(String owner, MethodNode mn) {
        String where = owner.replace('/', '.') + "." + mn.name + mn.desc;
        try {
            MethodIR ir = IRBuilder.build(owner, mn);
            Counters what = new Counters();
            for (int round = 0; round < MAX_ROUNDS; round++) {
                int changes = 0;
                for (Pass p : passes) changes += p.run(ir, what);
                if (changes == 0) break;
            }
            if (what.isEmpty()) return null;

            MethodNode opt = IRWriter.toMethodNode(ir, mn);
            int ib = Bytecode.instructions(mn.instructions), bb = Bytecode.bytes(mn.instructions);
            int ia = Bytecode.instructions(opt.instructions), ba = Bytecode.bytes(opt.instructions);
            if (ba > bb || (ba == bb && ia >= ib)) return null; // no gain: keep the original (and its debug info)

            try {
                new Analyzer<>(new BasicVerifier()).analyze(owner, opt);
            } catch (AnalyzerException e) {
                report.warnings.add(where + ": optimized code failed verification, kept original (" + e.getMessage() + ")");
                return null;
            }

            String before = keepListings ? Bytecode.listing(mn) : null;
            String after = keepListings ? Bytecode.listing(opt) : null;
            mn.instructions = opt.instructions;
            mn.tryCatchBlocks = opt.tryCatchBlocks;
            mn.maxStack = opt.maxStack;
            mn.maxLocals = opt.maxLocals;
            mn.localVariables = null;
            mn.visibleLocalVariableAnnotations = null;
            mn.invisibleLocalVariableAnnotations = null;
            return new Report.MethodChange(owner, mn.name, mn.desc, ib, ia, bb, ba, before, after, what);
        } catch (IRBuilder.UnsupportedCodeException e) {
            return null;
        } catch (RuntimeException e) {
            report.warnings.add(where + ": internal error, kept original (" + e + ")");
            return null;
        }
    }
}
