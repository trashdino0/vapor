package demo.lab;

public final class Hashing {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Hashing() {
    }

    /** FNV-1a, 64-bit. The two long constants above are prime material for constant obfuscation. */
    public static long fnv1a(String text) {
        long hash = OFFSET_BASIS;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    public static String hex(long value) {
        return String.format("%016x", value);
    }
}
