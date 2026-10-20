package dev.sample;

public class Outer {

    private final int value;

    public Outer(int value) {
        this.value = value;
    }

    public int innerValue() {
        return new Inner().get();
    }

    public class Inner {
        @VaporKeep
        public int get() {
            return value * 2;
        }
    }
}
