package jopt;

import jopt.opt.Report;
import jopt.samples.Samples;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Differential testing: original and optimized Samples must behave identically on random inputs. */
class SamplesTest {
    private static final int RUNS = 2000;
    private static final int[] INT_EDGES = {0, 1, -1, 2, 7, 100, 101, -100, Integer.MAX_VALUE, Integer.MIN_VALUE};
    private static final long[] LONG_EDGES = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE};
    private static final double[] DOUBLE_EDGES = {0.0, -0.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, -1e300};

    static Report report;
    static Class<?> optimized;

    @BeforeAll
    static void optimizeSamples() throws Exception {
        report = new Report();
        byte[] out = TestSupport.optimize(TestSupport.classBytes(Samples.class), report);
        optimized = new TestSupport.IsolatedLoader(Map.of(Samples.class.getName(), out)).loadClass(Samples.class.getName());
        assertTrue(report.warnings.isEmpty(), () -> "warnings: " + report.warnings);
    }

    @TestFactory
    Stream<DynamicTest> sameBehaviourOnRandomInputs() {
        return Arrays.stream(Samples.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .sorted(Comparator.comparing(Method::getName))
                .map(m -> DynamicTest.dynamicTest(m.getName(), () -> compare(m)));
    }

    private static void compare(Method original) throws Exception {
        Method opt = optimized.getMethod(original.getName(), original.getParameterTypes());
        SplittableRandom rnd = new SplittableRandom(original.getName().hashCode());
        for (int run = 0; run < RUNS; run++) {
            Object[] args = new Object[original.getParameterCount()];
            for (int i = 0; i < args.length; i++) args[i] = arg(original.getParameterTypes()[i], rnd, run);
            Object expected = call(original, args), actual = call(opt, args);
            assertEquals(expected, actual, () -> original.getName() + Arrays.toString(args));
        }
    }

    private static Object arg(Class<?> t, SplittableRandom rnd, int run) {
        boolean edge = run % 4 == 0;
        if (t == int.class) return edge ? INT_EDGES[rnd.nextInt(INT_EDGES.length)] : rnd.nextInt();
        if (t == long.class) return edge ? LONG_EDGES[rnd.nextInt(LONG_EDGES.length)] : rnd.nextLong();
        if (t == double.class) return edge ? DOUBLE_EDGES[rnd.nextInt(DOUBLE_EDGES.length)] : rnd.nextDouble() * 1e6 - 5e5;
        if (t == float.class) return edge ? (float) DOUBLE_EDGES[rnd.nextInt(DOUBLE_EDGES.length)] : (float) rnd.nextDouble();
        throw new IllegalArgumentException("unsupported parameter type " + t);
    }

    /** The return value, or the exception class for a call that throws. */
    private static Object call(Method m, Object[] args) throws IllegalAccessException {
        try {
            Object r = m.invoke(null, args);
            // Compare floating point by bits so that -0.0 vs 0.0 and NaN are checked exactly.
            if (r instanceof Double d) return Double.doubleToRawLongBits(d);
            if (r instanceof Float f) return Float.floatToRawIntBits(f);
            return r;
        } catch (InvocationTargetException e) {
            return e.getCause().getClass();
        }
    }

    // ---- the optimizer actually optimizes ---------------------------------------------------

    private static Report.MethodChange change(String method) {
        return report.changes.stream().filter(c -> c.name().equals(method)).findFirst()
                .orElseThrow(() -> new AssertionError(method + " was not optimized"));
    }

    private static List<String> code(String method) {
        return change(method).after().lines().map(String::strip).toList();
    }

    @Test
    void foldsConstantLocalsIntoOneMultiply() {
        assertEquals(List.of("ILOAD 0", "BIPUSH 15", "IMUL", "IRETURN"), code("constantLocals"));
    }

    @Test
    void removesDeadBranchAndStrengthReduces() {
        assertEquals(List.of("ILOAD 0", "ICONST_1", "ISHL", "IRETURN"), code("deadBranch"));
    }

    @Test
    void removesDeadStoresAndUnusedComputations() {
        assertEquals(List.of("ILOAD 0", "ICONST_1", "IADD", "IRETURN"), code("deadStores"));
    }

    @Test
    void propagatesCopies() {
        assertEquals(List.of("ILOAD 0", "ILOAD 1", "IADD", "IRETURN"), code("copies"));
    }

    @Test
    void foldsSwitchOnConstant() {
        assertEquals(List.of("ILOAD 0", "ICONST_1", "ISHL", "IRETURN"), code("switchOnConstant"));
    }

    @Test
    void formsIinc() {
        assertTrue(code("loopIncrement").contains("IINC 2 1"), () -> String.join("\n", code("loopIncrement")));
    }

    @Test
    void keepsDivisionByZero() {
        assertTrue(code("divisionByZeroStillThrows").contains("IDIV"));
    }

    @Test
    void keepsArrayAccessThatMayThrow() {
        assertTrue(Objects.requireNonNullElse(
                report.changes.stream().filter(c -> c.name().equals("arrayIndexThrows")).findFirst()
                        .map(Report.MethodChange::after).orElse(null), "IALOAD").contains("IALOAD"));
    }
}
