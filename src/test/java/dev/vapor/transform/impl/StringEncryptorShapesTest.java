package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.testing.Reload;
import dev.vapor.util.Mix;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every one of the twelve (math × loop) decryptor shapes must decode exactly
 * the string it encoded. Candidate class names are brute-forced until each
 * shape has been selected by the deterministic picker, then each shape is
 * exercised end-to-end: synthetic class → encrypt → load → invoke → compare.
 */
class StringEncryptorShapesTest {

    private static final int SHAPES = 12;

    @Test
    void everyShapeRoundTrips() throws Exception {
        VaporConfig config = new VaporConfig();
        Map<Integer, String> reached = new LinkedHashMap<>();
        for (int i = 0; i < 10000 && reached.size() < SHAPES; i++) {
            String className = "dev/test/ShapeHost" + i;
            int pick = Mix.of(config.seed, className, "<decryptor>", 0x5A17);
            int shape = Math.floorMod(pick, 4) * 3 + Math.floorMod(pick >>> 8, 3);
            if (reached.containsKey(shape)) {
                continue;
            }
            reached.put(shape, className);
            String value = "secret-payload-" + i + "-" + shape;
            assertEquals(value, roundTrip(className, value), "shape " + shape + " (" + className + ")");
        }
        assertEquals(SHAPES, reached.size(), "all twelve shapes should be reachable by the deterministic picker");
    }

    /** Encrypts the constant, then loads the class and invokes the method: the decryptor must restore it. */
    private static String roundTrip(String className, String value) throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = className;
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode(value));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);

        String decryptor = new StringEncryptor(new VaporConfig()).encrypt(node);
        assertNotNull(decryptor, "decryptor should have been injected");

        Class<?> host = Reload.define(className.replace('/', '.'), Reload.write(node));
        return (String) host.getMethod("get").invoke(null);
    }
}
