package dev.vapor.util;

/**
 * Deterministic mixing helpers shared by every transform's decision making.
 * Same inputs → same outputs, independent of JDK randomness and map order.
 */
public final class Mix {

    private Mix() {
    }

    /** Order-sensitive mix of a seed and three ints. */
    public static int of(long seed, int a, int b, int c) {
        int h = (int) (seed ^ (seed >>> 32));
        h = h * 0x9E3779B9 + a;
        h = Integer.rotateLeft(h, 13) * 0x85EBCA6B + b;
        h = Integer.rotateLeft(h, 17) * 0xC2B2AE35 + c;
        h ^= h >>> 15;
        h *= 0x2545F491;
        return h ^ (h >>> 13);
    }

    /** Same, keyed by owner/method names (hashed to ints first). */
    public static int of(long seed, String owner, String method, int site) {
        return of(seed, owner.hashCode(), method.hashCode(), site);
    }

    /** Tiny deterministic PRNG (xorshift32) for generated bodies. */
    public static final class Rand {
        private int state;

        public Rand(int seed) {
            state = seed == 0 ? 0x9E3779B9 : seed;
        }

        public int nextInt() {
            int x = state;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            state = x;
            return x;
        }

        /** Uniform-ish in [0, bound). {@code bound} must be positive. */
        public int nextInt(int bound) {
            return Math.floorMod(nextInt(), bound);
        }

        public boolean nextBoolean() {
            return (nextInt() & 1) != 0;
        }
    }
}
