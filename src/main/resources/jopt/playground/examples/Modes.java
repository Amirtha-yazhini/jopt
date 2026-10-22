public class Modes {
    public static int apply(int x) {
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
}
