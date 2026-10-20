package dev.hardening;

public final class Computations {

    private Computations() {
    }

    /** Mixed int/long arithmetic with overflow, shifts, masks, fall-through loops. */
    public static int workload(int x) {
        int acc = x * 3 + 1;
        for (int i = 0; i < 8; i++) {
            acc += (x + i) * (i ^ 3);
            acc ^= acc << 3;
            acc -= i * i;
        }
        long wide = 0x5DEECE66DL;
        for (int i = 0; i < 5; i++) {
            wide = wide * 6364136223846793005L + 1442695040888963407L + (x + i);
        }
        int dense = denseSwitch(x % 5);
        int sparse = sparseSwitch(x);
        long folded = wide ^ (wide >>> 32);
        return acc + (int) folded + dense * 31 + sparse;
    }

    /** Dense switch: javac emits tableswitch. */
    public static int denseSwitch(int v) {
        switch (v) {
            case 0:
                return 11;
            case 1:
                return 23;
            case 2:
                return 37;
            case 3:
                return 53;
            case 4:
                return 71;
            default:
                return -1;
        }
    }

    /** Sparse switch: javac emits lookupswitch. */
    public static int sparseSwitch(int v) {
        switch (v) {
            case -1000:
                return 3;
            case 7:
                return 17;
            case 100:
                return 29;
            case 123456:
                return 41;
            default:
                return 5;
        }
    }

    /** Long straight-line body: exercises insert/split budgets. */
    public static int straightLine(int v) {
        int a = v;
        int b = a * 7 + 13;
        b ^= a >>> 3;
        int c = b - a * 11;
        c |= 0x0F0F;
        int d = c ^ (b << 2);
        d += a % 97;
        int e = d * 3 - b;
        e ^= e >>> 5;
        int f = e + c * 2;
        f &= 0x7FFF;
        int g = f ^ (d + 1);
        g -= a & 255;
        int h = g * 5 + f;
        h ^= b >>> 2;
        int i = h - e + d - c;
        i ^= i << 1;
        int j = i + g * 3;
        j &= 0x3FFFF;
        int k = j ^ f ^ e ^ d;
        k += c - b + a;
        int l = k * 2 + j;
        l ^= l >>> 4;
        int m = l + i - h;
        m &= 0xFFFF;
        int n = m ^ (k << 1);
        n -= g % 61;
        int o = n + f - e + d - c + b - a;
        o ^= o >>> 2;
        int p = o * 9 + m;
        p -= n ^ l;
        int q = p + k - j + i - h + g;
        q ^= q << 2;
        int r = q & 0x1FFFF ^ (p * 3);
        r += o - n + m - l;
        int s = r ^ (q >>> 1);
        s -= k & 31;
        int t = s * 4 + r - q;
        t ^= t >>> 3;
        return t + s - r + q - p + o - n + m - l + k - j + i - h + g - f + e - d + c - b + a;
    }

    public static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    /** Mutual recursion (isEven/isOdd) so the recursion cycle spans two methods. */
    public static int mutual(int n) {
        return isEven(n) ? 1 : 0;
    }

    private static boolean isEven(int n) {
        return n == 0 || isOdd(n - 1);
    }

    private static boolean isOdd(int n) {
        return n != 0 && isEven(n - 1);
    }

    public static String classify(String cmd) {
        switch (cmd) {
            case "hardening-marker-beta":
                return "beta";
            case "alpha-command":
                return "a";
            case "gamma-command":
                return "g";
            default:
                return "unknown";
        }
    }

    public static int gcd(int a, int b) {
        int x = Math.abs(a);
        int y = Math.abs(b);
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return x;
    }
}
