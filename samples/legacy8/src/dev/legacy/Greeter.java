package dev.legacy;

public interface Greeter {

    String greet();

    default String exclaim(String what) {
        return greet() + " -> " + what.toUpperCase() + "!";
    }
}
