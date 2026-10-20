package dev.vapor.testing;

/**
 * Differential fixture for indy string-concat encryption. Compiled with the Java 21
 * toolchain, so javac emits {@code StringConcatFactory.makeConcatWithConstants} call
 * sites. Every method is pure and deterministic; {@link #run()} covers every supported
 * recipe shape plus the fail-safe control-character case, and the transform test
 * compares its transcript before/after obfuscation.
 */
public final class ConcatFixture {

    private ConcatFixture() {
    }

    public static String basic(String name, int count) {
        return "concat-literal-alpha-" + name + "|" + count;
    }

    /** One call site with every primitive width and the two "trap" types (char, boolean). */
    public static String numbers(long a, float b, double c, byte d, short e, char f, boolean g) {
        return "nums:" + a + "|" + b + "|" + c + "|" + d + "|" + e + "|" + f + "|" + g;
    }

    /** Reference types: String / Object / char[] — the append overload must match the static type. */
    public static String refs(String a, Object b, char[] c) {
        return "n(" + a + "," + b + "," + c + ")";
    }

    public static String loop(String s, int n) {
        String acc = "";
        for (int i = 0; i < n; i++) {
            acc = "loop-literal-" + s + "#" + i + "/" + acc;
        }
        return acc;
    }

    /** Adjacent literal parts get folded into one recipe literal by javac. */
    public static String folded(String s) {
        return "constant-left-" + "folded-middle-" + s;
    }

    /** Literal control characters must keep the call site working (fail-safe skip). */
    public static String controlChar(String s) {
        return "cc\u0001-" + s;
    }

    /** Non-ASCII (surrogate pair) literal part. */
    public static String emoji(String s) {
        return "emoji-\uD83D\uDE00-" + s;
    }

    public static String run() {
        StringBuilder out = new StringBuilder();
        String s = "VARIABLE";
        out.append(basic(s, 7)).append('\n');
        out.append(basic("second-site", -1)).append('\n');
        out.append(numbers(1234567890123L, 3.5f, -2.75d, (byte) -7, (short) 1234, 'Q', true)).append('\n');
        out.append(refs(null, null, null)).append('\n');
        // The identity hash of a fresh char[] differs per invocation/loader; normalize it.
        // (If the transform switched to contents semantics, "abc" vs "[C@id" still fails.)
        out.append(refs(s, "obj-value", new char[] {'a', 'b', 'c'})
                .replaceAll("\\[C@[0-9a-f]+", "[C@id")).append('\n');
        out.append(loop(s, 5)).append('\n');
        out.append(folded(s)).append('\n');
        out.append(controlChar(s)).append('\n');
        out.append(emoji(s)).append('\n');
        return out.toString();
    }

    /** Static interface method: the injected decryptor must be public static and called with itf=true. */
    public interface Iface {
        static String greet(String name) {
            return "iface-literal-" + name;
        }
    }
}
