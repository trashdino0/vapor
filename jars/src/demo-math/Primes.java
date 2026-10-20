package demo.lab;

public final class Primes {

    private Primes() {
    }

    /** Sieve of Eratosthenes: count of primes below {@code limit}. */
    public static int countBelow(int limit) {
        boolean[] composite = new boolean[limit];
        int count = 0;
        for (int n = 2; n < limit; n++) {
            if (!composite[n]) {
                count++;
                if ((long) n * n < limit) {
                    for (int k = n * n; k < limit; k += n) {
                        composite[k] = true;
                    }
                }
            }
        }
        return count;
    }
}
