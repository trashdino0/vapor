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
}
