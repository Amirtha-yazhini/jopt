public class TryFinally {
    public static int guarded(int a) {
        int r = 0;
        boolean flag = true;
        try {
            if (flag) {
                r = a + 1;
            }
        } finally {
            r = r * 2;
        }
        return r;
    }
}
