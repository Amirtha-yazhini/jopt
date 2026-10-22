public class Safety {
    public static int risky(int x) {
        int zero = 0;
        if (x > 100) {
            return x / zero; // must still throw ArithmeticException
        }
        return x;
    }
}
