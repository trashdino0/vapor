package dev.vapor.protect;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassProtectorTest {

    private static final String NAME = "a";
    private static final byte[] PLAIN = "class a { int x = 42; }".getBytes(StandardCharsets.UTF_8);

    @Test
    void checksumHeaderIsDeterministicAndHidesPlaintext() {
        byte[] first = ClassProtector.encrypt(PLAIN, NAME, 0x1020304050607080L, true);
        byte[] second = ClassProtector.encrypt(PLAIN, NAME, 0x1020304050607080L, true);

        assertArrayEquals(first, second, "encryption must be deterministic");
        assertEquals('V', first[0]);
        assertEquals('P', first[1]);
        assertEquals('0', first[2]);
        assertEquals('1', first[3]);
        assertEquals(PLAIN.length + 8, first.length);
        assertFalse(indexOf(first, PLAIN), "plaintext leaked into checksum header payload");
    }

    @Test
    void checksumCanBeDisabled() {
        byte[] encrypted = ClassProtector.encrypt(PLAIN, NAME, 1L, false);
        assertEquals('0', encrypted[3]);
        assertEquals(PLAIN.length + 4, encrypted.length);
        assertFalse(indexOf(encrypted, PLAIN), "plaintext leaked into header-only payload");
    }

    @Test
    void differentClassesProduceDifferentCiphertext() {
        byte[] a = ClassProtector.encrypt(PLAIN, "a", 7L, true);
        byte[] b = ClassProtector.encrypt(PLAIN, "b", 7L, true);
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 8, a.length),
                Arrays.copyOfRange(b, 8, b.length)));
    }

    private static boolean indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
