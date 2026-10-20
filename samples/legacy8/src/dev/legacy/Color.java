package dev.legacy;

public enum Color {
    RED, GREEN, BLUE;

    public String describe() {
        return name().toLowerCase() + '#' + ordinal();
    }
}
