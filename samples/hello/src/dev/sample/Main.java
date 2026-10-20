package dev.sample;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ServiceLoader;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Vapor sample app ("hello", release 21).
 *
 * <p>Every printed value is deliberately independent of class/method/field
 * names so the test rig can compare stdout before and after obfuscation.</p>
 */
public final class Main {

    public static void main(String[] args) {
        System.out.println("=== vapor sample: hello ===");

        // Interfaces, default methods, inheritance, overrides
        Greeter alice = new EnglishGreeter("Alice");
        Greeter bob = new FancyGreeter("Bob");
        System.out.println(alice.greet());
        System.out.println(bob.greet());
        System.out.println(bob.exclaim("hi"));
        System.out.println("tostring=" + bob);

        // Enum with abstract method, anonymous subclasses, values()/valueOf()
        for (Day day : Day.values()) {
            System.out.println("day=" + day.describe());
        }
        System.out.println("valueOf=" + Day.valueOf("FRIDAY").describe());

        // Lambda (invokedynamic)
        IntUnaryOperator doubler = x -> x * 2 + 1;
        System.out.println("lambda=" + doubler.applyAsInt(20));

        // Lambda capturing a local
        int base = 7;
        IntSupplier captured = () -> base + 1;
        System.out.println("capture=" + captured.getAsInt());

        // switch on string
        System.out.println("classify=" + classify("apple"));

        // String concat (invokedynamic concat on 21)
        int x = 4;
        System.out.println("concat=" + ("sum=" + (3 + x)));

        // Legacy StringBuilder concat
        System.out.println("sb=" + new StringBuilder().append("v=").append(x).append('!').toString());

        // String.format
        System.out.println("fmt=" + String.format("%d-%s", x, "z"));

        // Record: accessor + equals + hashCode (name-independent outputs only)
        Point p = new Point(1, 2);
        System.out.println("record=" + p.x() + ',' + p.y());
        System.out.println("recordEq=" + p.equals(new Point(1, 2)));
        System.out.println("recordHashStable=" + (p.hashCode() == new Point(1, 2).hashCode()));

        // Sealed interface + pattern instanceof
        System.out.println("shape=" + describe(new Circle(2.0)));
        System.out.println("shape=" + describe(new Square(3.0)));

        // Inner class
        Outer outer = new Outer(5);
        System.out.println("inner=" + outer.innerValue());

        // Anonymous class
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                System.out.println("anon=42");
            }
        };
        runnable.run();

        // Static initializer
        System.out.println("init=" + Init.VALUE);

        // try/catch
        try {
            System.out.println("nope=" + (10 / (x - 4)));
        } catch (ArithmeticException e) {
            System.out.println("caught=" + e.getClass().getSimpleName());
        }

        // Reflection against a kept class (see keep rules in phase 2)
        try {
            Class<?> type = Class.forName("dev.sample.EnglishGreeter");
            Object instance = type.getDeclaredConstructor(String.class).newInstance("Reflect");
            Object result = type.getMethod("greet").invoke(instance);
            System.out.println("reflect=" + result);
        } catch (ReflectiveOperationException e) {
            System.out.println("reflect=FAILED:" + e.getClass().getSimpleName());
        }

        // Reflection against an @VaporKeep'd class (kept without config)
        try {
            Class<?> type = Class.forName("dev.sample.FancyGreeter");
            Object instance = type.getDeclaredConstructor(String.class).newInstance("Keep");
            Object result = type.getMethod("greet").invoke(instance);
            System.out.println("annotationKeep=" + result);
        } catch (ReflectiveOperationException e) {
            System.out.println("annotationKeep=FAILED:" + e.getClass().getSimpleName());
        }

        // Reflection against an @VaporKeep'd member (the class itself is renamed)
        try {
            Object inner = new Outer(5).new Inner();
            java.lang.reflect.Method get = inner.getClass().getDeclaredMethod("get");
            System.out.println("memberKeep=" + get.invoke(inner));
        } catch (ReflectiveOperationException e) {
            System.out.println("memberKeep=FAILED:" + e.getClass().getSimpleName());
        }

        // Serializable round trip
        try {
            Config config = new Config("vapor", 7);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(config);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                Config copy = (Config) in.readObject();
                System.out.println("serial=" + copy.name + ':' + copy.level);
            }
        } catch (Exception e) {
            System.out.println("serial=FAILED:" + e.getClass().getSimpleName());
        }

        // ServiceLoader (META-INF/services)
        for (Plugin plugin : ServiceLoader.load(Plugin.class)) {
            System.out.println("plugin=" + plugin.name());
        }

        System.out.println("=== done ===");
    }

    static String classify(String s) {
        switch (s) {
            case "apple":
                return "fruit";
            case "carrot":
                return "vegetable";
            default:
                return "unknown";
        }
    }

    static String describe(Shape shape) {
        if (shape instanceof Circle circle) {
            return "circle:" + circle.radius();
        }
        if (shape instanceof Square square) {
            return "square:" + square.side();
        }
        return "unknown";
    }
}
