public class Temporaries {
    public static int add(int a, int b) {
        int t = a;
        int u = t;
        int unused = a * 7;
        return u + b;
    }
}
