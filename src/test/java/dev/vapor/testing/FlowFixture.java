package dev.vapor.testing;

/**
 * Compiler-produced control flow used by transform tests as a differential
 * fixture: the baseline result and the post-transform result must be identical
 * for every input. Methods must stay pure, deterministic and throw-free for
 * every int/long input.
 */
public final class FlowFixture {

    private FlowFixture() {
    }

    public static int state1(int v) {
        int acc = 0;
        for (int i = 0; i < 30; i++) {
            if (i % 3 == 0) {
                continue;
            }
            acc += i * (v + 1);
            if (acc > 500) {
                break;
            }
            acc ^= acc >>> 2;
        }
        if (v < 0) {
            acc = -acc;
        }
        return acc;
    }

    public static int state2(int v, int w) {
        int r = v;
        int k = w & 15;
        while (k > 0) {
            if ((r & 1) != 0) {
                r = r * 3 + 1;
            } else {
                r >>= 1;
            }
            r ^= w;
            k--;
        }
        return r;
    }

    public static long state3(long v) {
        long s = v;
        for (int i = 0; i < 10; i++) {
            if ((s & 1L) == 0L) {
                s = s / 2 + i;
            } else {
                s = s * 3 - i;
            }
        }
        return s;
    }

    public static int state4(int v) {
        int r;
        switch (v & 7) {
            case 0:
                r = 11;
                break;
            case 1:
                r = 23;
                break;
            case 2:
                r = 37;
                break;
            default:
                r = v ^ 0x55;
        }
        int t = v;
        do {
            if ((t & 3) == 0) {
                r += t;
            } else {
                r -= t;
            }
            t /= 3;
        } while (t != 0 && r < 1000);
        return r + (v > 0 ? v % 13 : -(v % 13));
    }

    /** Dense switch plus loop, no values live across branches (flattening-eligible). */
    public static int state5(int v) {
        int r = v;
        switch (v & 3) {
            case 0:
                r += 7;
                break;
            case 1:
                r ^= 0x55;
                break;
            case 2:
                r -= 3;
                break;
            default:
                r = -r;
        }
        for (int i = 0; i < 5; i++) {
            r = r * 31 + i;
        }
        return r;
    }

    /** Sparse switch: compiled to lookupswitch (flattening-eligible). */
    public static int state6(int v) {
        int r;
        switch (v) {
            case -100:
                r = 1;
                break;
            case 7:
                r = 2;
                break;
            case 1000:
                r = 3;
                break;
            case 999983:
                r = 4;
                break;
            default:
                r = v;
        }
        return r;
    }

    /** Ternary results live across the branch: spilled into locals, reloaded in the case. */
    public static int ternary1(int v, int w) {
        int r = (v & 1) == 0 ? v + w : v - w;
        return r ^ (w > 0 ? 0x55 : -0x55);
    }

    public static long ternary2(long v, int w) {
        long a = w > 0 ? v * 3 : v / 3;
        long b = w < 0 ? v + 11 : v - 7;
        return a ^ b;
    }

    public static String ternary3(boolean flag, String left, String right) {
        return (flag ? left : right) + ":" + (flag ? right : left);
    }

    /** Null merged with a reference: the verifier keeps the class, not Object. */
    public static Object ternaryNull(boolean flag) {
        Object value = flag ? null : "value";
        return value == null ? "null" : value;
    }

    /** A NEW value is live across the ternary in the constructor argument. */
    public static String ctorTernary(boolean flag) {
        return new StringBuilder(flag ? "left" : "right").append('!').toString();
    }

    /** Exception table: the flattener must skip this method wholesale. */
    public static int guarded(int v) {
        try {
            return 10 / v + state1(v);
        } catch (ArithmeticException e) {
            return -1;
        }
    }
}
