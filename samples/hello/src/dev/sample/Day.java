package dev.sample;

public enum Day {
    MONDAY {
        @Override
        String mood() {
            return "grumpy";
        }
    },
    FRIDAY {
        @Override
        String mood() {
            return "happy";
        }
    };

    abstract String mood();

    public String describe() {
        return name().toLowerCase() + "/" + mood();
    }
}
