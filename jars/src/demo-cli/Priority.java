package demo.tasks;

import java.util.Locale;

public enum Priority {
    LOW(1, "low"),
    NORMAL(2, "normal"),
    HIGH(3, "high"),
    CRITICAL(4, "critical");

    private final int weight;
    private final String label;

    Priority(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    public int weight() {
        return weight;
    }

    public String label() {
        return label;
    }

    /** Case-insensitive lookup; compiles to a hash-switch over the string literals. */
    public static Priority of(String name) {
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "low":
                return LOW;
            case "normal":
                return NORMAL;
            case "high":
                return HIGH;
            case "critical":
                return CRITICAL;
            default:
                throw new IllegalArgumentException("unknown priority: " + name);
        }
    }
}
