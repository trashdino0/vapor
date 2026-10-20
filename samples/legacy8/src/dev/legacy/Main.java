package dev.legacy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Vapor sample app ("legacy8", release 8).
 *
 * <p>Exercises what javac emits for an old target: StringBuilder-based string
 * concat, invokedynamic lambdas, anonymous inner classes, and no records or
 * sealed types.</p>
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("=== vapor sample: legacy8 ===");

        Greeter greeter = new LoudGreeter("Rusty");
        System.out.println(greeter.greet());
        System.out.println(greeter.exclaim("yo"));

        for (Color color : Color.values()) {
            System.out.println("color=" + color.describe());
        }

        Runnable lambda = () -> System.out.println("lambda=8");
        lambda.run();

        System.out.println("classify=" + classify("carrot"));

        int x = 6;
        System.out.println("concat=" + ("sum=" + (1 + x)));

        Box box = new Box(3);
        System.out.println("inner=" + box.open());

        Runnable anon = new Runnable() {
            @Override
            public void run() {
                System.out.println("anon=8");
            }
        };
        anon.run();

        try {
            System.out.println("nope=" + (1 / (x - 6)));
        } catch (ArithmeticException e) {
            System.out.println("caught=" + e.getClass().getSimpleName());
        }

        try {
            Blob blob = new Blob("legacy", 8);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(buffer);
            out.writeObject(blob);
            out.close();
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()));
            Blob copy = (Blob) in.readObject();
            in.close();
            System.out.println("serial=" + copy.name + ':' + copy.level);
        } catch (Exception e) {
            System.out.println("serial=FAILED:" + e.getClass().getSimpleName());
        }

        System.out.println("=== done ===");
    }

    static String classify(String s) {
        if ("apple".equals(s)) {
            return "fruit";
        } else if ("carrot".equals(s)) {
            return "vegetable";
        }
        return "unknown";
    }
}
