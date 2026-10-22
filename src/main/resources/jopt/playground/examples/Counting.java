public class Counting {
    public static int sumTo(int n) {
        int s = 0;
        for (int i = 0; i < n; i = i + 1) {
            s = s + i;
        }
        return s;
    }
}
