package demo.lab;

public final class Collatz {

    private Collatz() {
    }

    public static int stepsFor(int n) {
        int count = 0;
        long value = n;
        while (value != 1) {
            value = (value % 2 == 0) ? value / 2 : 3 * value + 1;
            count++;
        }
        return count;
    }

    /** Start value under {@code limit} with the longest trajectory. */
    public static int longest(int limit) {
        int best = 1;
        int bestSteps = 0;
        for (int n = 2; n <= limit; n++) {
            int steps = stepsFor(n);
            if (steps > bestSteps) {
                bestSteps = steps;
                best = n;
            }
        }
        return best;
    }
}
