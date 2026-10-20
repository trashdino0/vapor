package dev.hardening;

public final class Loops {

    private Loops() {
    }

    public static String run(int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            if (i == 2) {
                continue;
            }
            for (int j = 0; j < n; j++) {
                if (j == 3) {
                    break;
                }
                total += i * j;
            }
        }

        int k = 0;
        int sum = 0;
        outer:
        while (k < n) {
            int m = 0;
            do {
                if (m == k) {
                    k++;
                    continue outer;
                }
                sum += m;
                m++;
            } while (m < n);
            k++;
        }

        try {
            total += risky(n);
        } catch (IllegalStateException e) {
            total -= 7;
        } finally {
            total++;
        }

        synchronized (Loops.class) {
            total += 2;
        }

        int nested = 0;
        try {
            try {
                nested = risky(n + 1);
            } finally {
                nested++;
            }
        } catch (IllegalStateException e) {
            nested = -nested;
        }

        return total + ":" + sum + ":" + finallyReturn(n) + ":" + nested + ":" + Computations.gcd(n, 24);
    }

    private static int risky(int n) {
        if (n % 2 == 0) {
            throw new IllegalStateException("even input");
        }
        return n * 11;
    }

    @SuppressWarnings("finally")
    private static int finallyReturn(int n) {
        int r = n + 1;
        try {
            if (n > 3) {
                return r * 2;
            }
            return r;
        } finally {
            r += 100;
        }
    }
}
