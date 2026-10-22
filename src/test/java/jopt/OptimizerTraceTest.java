package jopt;

import jopt.io.ClassHierarchy;
import jopt.ir.MethodIR;
import jopt.opt.Counters;
import jopt.opt.Optimizer;
import jopt.opt.OptimizerTrace;
import jopt.opt.Report;
import jopt.samples.Samples;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tracing observes the optimizer; it must never change what the optimizer produces. */
class OptimizerTraceTest {

    private static byte[] optimize(byte[] in, Report report, OptimizerTrace trace) throws Exception {
        try (ClassHierarchy h = new ClassHierarchy(List.of())) {
            h.add(in);
            return new Optimizer(Set.of(), h, report, true, trace).optimizeClass(in);
        }
    }

    @Test
    void tracingDoesNotChangeTheOutput() throws Exception {
        byte[] in = TestSupport.classBytes(Samples.class);
        Report plain = new Report(), traced = new Report();
        List<String> events = new ArrayList<>();
        OptimizerTrace trace = (owner, original) -> new OptimizerTrace.MethodTrace() {
            public void irBuilt(MethodIR ir) {
                events.add("build " + original.name);
            }

            public void passRan(int round, String pass, int changes, Counters what, MethodIR ir) {
                events.add(pass);
            }

            public void finished(OptimizerTrace.Outcome outcome, String detail, MethodNode lowered) {
                events.add(outcome.name());
            }
        };
        byte[] a = optimize(in, plain, OptimizerTrace.NONE);
        byte[] b = optimize(in, traced, trace);

        assertArrayEquals(a, b);
        assertEquals(plain.counters.asMap(), traced.counters.asMap());
        assertEquals(plain.methodsChanged, traced.methodsChanged);
        long replaced = events.stream().filter(e -> e.equals("REPLACED")).count();
        assertEquals(plain.methodsChanged, replaced, "one REPLACED event per optimized method");
        assertTrue(events.contains("fold") && events.contains("cfg"));
    }
}
