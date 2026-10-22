public class DebugFlag {
    public static int compute(int x) {
        boolean debug = false;
        int r = x * 2;
        if (debug) {
            System.out.println("r = " + r);
        }
        return r;
    }
}
