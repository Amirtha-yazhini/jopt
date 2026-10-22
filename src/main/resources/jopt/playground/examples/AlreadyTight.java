public class AlreadyTight {
    public static int clamp(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }
}
