package dev.legacy;

public class Box {

    private final int size;

    public Box(int size) {
        this.size = size;
    }

    public int open() {
        return new Lid().value();
    }

    public class Lid {
        public int value() {
            return size * 10;
        }
    }
}
