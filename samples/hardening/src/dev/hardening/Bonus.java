package dev.hardening;

import java.util.Arrays;

public final class Bonus {

    private Bonus() {
    }

    /** Static initializer with concat + arithmetic (runs in &lt;clinit&gt;). */
    private static final String TAG = "bonus-tag".toUpperCase() + "-" + (3 * 7);

    public enum Mode {
        FAST(1) {
            @Override
            public int cost() {
                return base() * 2;
            }
        },
        SAFE(3) {
            @Override
            public int cost() {
                return base() + 10;
            }
        };

        private final int base;

        Mode(int base) {
            this.base = base;
        }

        public int base() {
            return base;
        }

        public abstract int cost();
    }

    public record Pair(int left, int right) {
        public Pair {
            if (left > right) {
                int swap = left;
                left = right;
                right = swap;
            }
        }

        public int span() {
            return right - left;
        }
    }

    public static long summary() {
        long acc = 0;
        for (Mode m : Mode.values()) {
            acc += m.cost() + m.base();
        }
        Pair p = new Pair(9, 4);

        int[][] grid = new int[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                grid[i][j] = (i + 1) * (j + 1);
            }
        }
        int diag = 0;
        for (int i = 0; i < 3; i++) {
            diag += grid[i][i];
        }

        Integer boxedA = 120;
        Integer boxedB = 120;
        Integer boxedC = 130;
        boolean cache = (boxedA == boxedB) && (boxedA != boxedC);

        int[] copy = Arrays.copyOf(new int[]{5, 1, 3}, 5);
        Arrays.sort(copy);

        int vararg = varargs(1, 2, 3, 4, 5);

        return acc + p.span() + p.left() + p.right() + diag + copy[0]
                + (cache ? 1 : 0) + TAG.length() + vararg + Mode.valueOf("SAFE").cost();
    }

    public static int varargs(int... xs) {
        int t = 0;
        for (int x : xs) {
            t += x;
        }
        return t;
    }
}
