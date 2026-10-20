package dev.sample;

public final class Init {

    public static final int VALUE;

    static {
        VALUE = compute();
    }

    private Init() {
    }

    private static int compute() {
        return 40 + 2;
    }
}
