package dev.sample;

@VaporKeep
public final class FancyGreeter extends EnglishGreeter {

    public FancyGreeter(String name) {
        super(name);
    }

    @Override
    public String greet() {
        return "Ahoy! " + super.greet();
    }

    @Override
    public String toString() {
        return "Fancy(" + super.greet() + ")";
    }
}
