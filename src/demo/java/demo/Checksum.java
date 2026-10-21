package demo;

public class Checksum {
    /** A 31-based rolling hash, plus an Adler-style second sum. */
    public static long of(String s) {
        int multiplier = 32;
        int modulo = 65521;
        boolean debug = false;
        int hash = 0;
        int a = 1;
        int b = 0;
        int i = 0;
        while (i < s.length()) {
            int c = s.charAt(i);
            hash = hash * multiplier - hash + c;   // hash * 31 + c
            a = (a + c) % modulo;
            b = (b + a) % modulo;
            if (debug) {
                System.out.println(i + ": " + hash);
            }
            i = i + 1;
        }
        long high = (long) b * 65536;
        long combined = high + a;
        return combined * 1 + 0 ^ hash;
    }
}
