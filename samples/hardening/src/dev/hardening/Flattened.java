package dev.hardening;

/**
 * v3.3 rig coverage: branch shapes that are eligible for bounded control-flow
 * flattening (no try/catch, no ternary values live across branches). The
 * obfuscated run must print exactly the same value as the baseline.
 */
public final class Flattened {

    private Flattened() {
    }

    public static int run(int n) {
        int acc = n;
        for (int i = 0; i < 8; i++) {
            switch ((acc ^ i) & 3) {
                case 0:
                    acc += i * 3;
                    break;
                case 1:
                    acc ^= 0x2D;
                    break;
                case 2:
                    acc -= 7;
                    break;
                default:
                    acc = -acc;
            }
            if (acc == 3) {
                continue;
            }
            if (acc > 100000) {
                acc %= 100000;
            } else if (acc < -100000) {
                acc %= 100000;
            }
            if ((i & 1) == 0) {
                acc += n;
            } else {
                acc -= n;
            }
        }
        int guard = 0;
        while (guard < 4) {
            acc += guard * 2;
            guard++;
        }
        return acc;
    }
}
