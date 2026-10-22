public class ConstantLocals {
    // javac keeps these "constants" as ordinary local variables.
    public static int scaled(int x) {
        int a = 3;
        int b = a * 4;
        int c = b + a;
        return x * c;
    }
}
