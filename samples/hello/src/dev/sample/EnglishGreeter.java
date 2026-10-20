package dev.sample;

public class EnglishGreeter implements Greeter {

    private final String name;

    public EnglishGreeter(String name) {
        this.name = name;
    }

    @Override
    public String greet() {
        return "Hello, " + name + "!";
    }
}
