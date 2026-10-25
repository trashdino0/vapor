package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.testing.Reload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringPoolTest {

    @Test
    void poolEncryptsMethodsAndConstantValueFields() throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = "dev/vapor/testing/PoolHost";
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "CONST", "Ljava/lang/String;", null, "constant-secret"));

        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("method-secret"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);

        assertNotNull(new StringEncryptor(new VaporConfig()).encrypt(node));
        assertNull(node.fields.get(0).value, "ConstantValue must be moved into <clinit>");

        byte[] bytes = Reload.write(node);
        String latin = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(latin.contains("constant-secret"), "ConstantValue plaintext leaked");
        assertFalse(latin.contains("method-secret"), "method plaintext leaked");

        Class<?> host = Reload.define(node.name.replace('/', '.'), bytes);
        assertEquals("constant-secret", host.getField("CONST").get(null));
        assertEquals("method-secret", host.getMethod("get").invoke(null));
    }
}
