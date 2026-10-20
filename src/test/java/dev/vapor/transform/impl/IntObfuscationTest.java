package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.util.Insns;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes obfuscated bytecode: every rewritten constant must return exactly
 * its original value, and the rewrite must actually have happened.
 */
class IntObfuscationTest {

    private static final String HOST = "obf/test/ConstantHost";

    private static final int[] INT_VALUES = {
            -1, 0, 1, 5, 6, -7, 127, 128, -129, 32767, 32768, -32769,
            123456, -123456, Integer.MIN_VALUE, Integer.MAX_VALUE
    };

    private static final long[] LONG_VALUES = {
            0L, 1L, -1L, 5L, -7L, 123456789012345L, Long.MIN_VALUE, Long.MAX_VALUE
    };

    @Test
    void rewrittenConstantsKeepTheirValues() throws Exception {
        ClassNode node = buildClass();
        IntObfuscation transform = new IntObfuscation(new VaporConfig());
        for (MethodNode method : node.methods) {
            transform.process(null, node, method);
        }

        int xors = 0;
        for (MethodNode method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.IXOR || insn.getOpcode() == Opcodes.LXOR) {
                    xors++;
                }
            }
        }
        assertTrue(xors >= INT_VALUES.length + LONG_VALUES.length,
                "expected every constant to be rewritten (found " + xors + " xors)");

        Class<?> host = new Loader().define(write(node));
        for (int value : INT_VALUES) {
            Method method = host.getMethod(intName(value));
            assertEquals(value, method.invoke(null), "int " + value);
        }
        for (long value : LONG_VALUES) {
            Method method = host.getMethod(longName(value));
            assertEquals(value, method.invoke(null), "long " + value);
        }
    }

    private static ClassNode buildClass() {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = HOST;
        node.superName = "java/lang/Object";

        for (int value : INT_VALUES) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, intName(value), "()I", null, null);
            method.instructions.add(Insns.intNode(value));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            node.methods.add(method);
        }
        for (long value : LONG_VALUES) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, longName(value), "()J", null, null);
            method.instructions.add(Insns.longNode(value));
            method.instructions.add(new InsnNode(Opcodes.LRETURN));
            node.methods.add(method);
        }
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return cw.toByteArray();
    }

    private static String intName(int value) {
        return "i" + Integer.toString(value).replace("-", "m");
    }

    private static String longName(long value) {
        return "l" + Long.toString(value).replace("-", "m");
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(HOST.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
