package jopt.samples;

/**
 * Methods compiled by javac and then optimized in the tests. Every public static method is run on
 * random arguments in both versions and the results (or thrown exceptions) must match.
 */
@SuppressWarnings({"unused", "ConstantValue", "PointlessArithmeticExpression"})
public class Samples {

    public static int constantLocals(int x) {
        int a = 3;
        int b = a * 4;
        int c = b + a;
        return x * c;
    }

    public static int deadBranch(int x) {
        boolean debug = false;
        int r = x * 2;
        if (debug) {
            r += 100;
            System.out.println(r);
        }
        return r;
    }

    public static int deadStores(int x) {
        int r = 0;
        r = x + 1;
        int unused = x * 7;
        return r;
    }

    public static int copies(int a, int b) {
        int t = a;
        int u = t;
        return u + b;
    }

    public static int loopIncrement(int n) {
        int s = 0;
        for (int i = 0; i < (n & 1023); i = i + 1) {
            s = s + i;
        }
        return s;
    }

    public static int identities(int x) {
        return (x * 8 + x * 1 + 0) | 0;
    }

    public static int multiplyByZero(int x, int y) {
        return x * 0 + (y & 0);
    }

    public static long longs(long a, long b) {
        long k = 2;
        long m = a * k;
        long z = 0;
        return m + b * 16 + z;
    }

    public static double doubles(double a) {
        double half = 0.5;
        double two = 2;
        return a * half * two + half * two;
    }

    public static float negativeZero(float a) {
        float z = -0.0f;
        return a + z;
    }

    public static int switchOnConstant(int x) {
        int mode = 2;
        switch (mode) {
            case 1:
                return x;
            case 2:
                return x * 2;
            default:
                return -x;
        }
    }

    public static int switchOnInput(int x) {
        switch (x & 7) {
            case 0: return 10;
            case 1: return 20;
            case 5: return x;
            default: return -1;
        }
    }

    public static int tryCatch(int a, int b) {
        int fallback = -1;
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return fallback;
        }
    }

    public static int tryFinally(int a) {
        int r = 0;
        boolean flag = true;
        try {
            if (flag) r = a + 1;
        } finally {
            r = r * 2;
        }
        return r;
    }

    public static int divisionByZeroStillThrows(int x) {
        int zero = 0;
        if (x > 100) return x / zero;
        return x;
    }

    public static int minDivMinusOne(int x) {
        int m = Integer.MIN_VALUE;
        int d = -1;
        return m / d + m % d + x;
    }

    public static int maskedShift(int x) {
        int s = 33;
        long t = 65;
        return (x << s) + (int) (1L << t);
    }

    public static String strings(int x) {
        String prefix = "v";
        boolean upper = false;
        String s = prefix + x;
        if (upper) s = s.toUpperCase();
        return s;
    }

    public static boolean nullChecks(int x) {
        String s = null;
        Object o = "lit";
        return s == null && o != null && x > 0;
    }

    public static int nestedLoops(int a, int b) {
        int r = 0;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 3; j = j + 1) {
                if (a > b) r += i * j;
                else r -= i;
            }
        }
        return r;
    }

    public static int ternary(int a) {
        boolean flag = true;
        return flag ? a : -a;
    }

    public static long conversions(int x) {
        int big = 300;
        byte b = (byte) big;
        char c = (char) -1;
        double d = 1e20;
        return b + c + (long) d + (int) Double.NaN + (long) x;
    }

    public static int arrays(int x) {
        int size = 4;
        int[] arr = new int[size];
        for (int i = 0; i < arr.length; i = i + 1) arr[i] = x + i;
        int sum = 0;
        for (int v : arr) sum += v;
        return sum;
    }

    public static int whileTrue(int n) {
        int i = 0;
        while (true) {
            i = i + 1;
            if (i >= (n & 255)) break;
        }
        return i;
    }

    public static int synchronizedBlock(int x) {
        Object lock = Samples.class;
        int r;
        synchronized (lock) {
            int k = 3;
            r = x * k;
        }
        return r;
    }

    public static int arrayIndexThrows(int x) {
        int[] a = new int[2];
        int unused = a[x & 3]; // must still throw for index 2 and 3
        return x;
    }
}
