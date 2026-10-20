package dev.legacy;

public class LoudGreeter implements Greeter {

    private final String name;

    public LoudGreeter(String name) {
        this.name = name;
    }

    @Override
    public String greet() {
        return "HELLO " + name + "!";
    }
}
