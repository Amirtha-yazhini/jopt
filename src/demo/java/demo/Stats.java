package demo;

public class Stats {
    public static String describe(int[] values) {
        int n = values.length;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (int i = 0; i < n; i = i + 1) {
            int v = values[i];
            int copy = v;
            if (copy < min) min = copy;
            if (copy > max) max = copy;
            sum = sum + copy;
        }
        int mode = 2;
        String label;
        switch (mode) {
            case 1:
                label = "basic";
                break;
            case 2:
                label = "full";
                break;
            default:
                label = "none";
        }
        long mean100 = sum * 100 / n;
        return label + " n=" + n + " min=" + min + " max=" + max + " mean=" + (mean100 / 100) + "." + (mean100 % 100);
    }
}
