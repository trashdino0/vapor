package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.util.Insns;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes obfuscated bytecode: every rewritten constant (int/long/float/double)
 * must return exactly its original value — floats/doubles compared bit-for-bit, so
 * {@code -0.0} and NaN payloads cannot slip through — and the rewrite must actually
 * have happened. NaN constants are deliberately left untouched.
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

    private static final float[] FLOAT_VALUES = {
            0f, 1f, 2f, -0.0f, 3.14159f, -2.75f, 1.0e-30f,
            Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
    };

    private static final double[] DOUBLE_VALUES = {
            0d, 1d, -0.0d, 3.141592653589793d, -2.75d, 1.0e-300d,
            Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
    };

    @Test
    void rewrittenConstantsKeepTheirValues() throws Exception {
        ClassNode node = buildClass();
        IntObfuscation transform = new IntObfuscation(new VaporConfig());
        for (MethodNode method : node.methods) {
            transform.process(null, node, method);
        }

        int xors = countOpcode(node, Opcodes.IXOR) + countOpcode(node, Opcodes.LXOR);
        assertTrue(xors >= INT_VALUES.length + LONG_VALUES.length + FLOAT_VALUES.length + DOUBLE_VALUES.length,
                "expected every constant to be rewritten (found " + xors + " xors)");
        assertEquals(FLOAT_VALUES.length + 3, countCalls(node, "java/lang/Float", "intBitsToFloat"),
                "every float site (incl. FCONST_0/1/2) should be rewritten");
        assertEquals(DOUBLE_VALUES.length + 2, countCalls(node, "java/lang/Double", "longBitsToDouble"),
                "every double site (incl. DCONST_0/1) should be rewritten");

        Class<?> host = new Loader().define(write(node));
        for (int value : INT_VALUES) {
            assertEquals(value, host.getMethod(intName(value)).invoke(null), "int " + value);
        }
        for (long value : LONG_VALUES) {
            assertEquals(value, host.getMethod(longName(value)).invoke(null), "long " + value);
        }
        for (float value : FLOAT_VALUES) {
            Object actual = host.getMethod(floatName(value)).invoke(null);
            assertEquals(Float.floatToRawIntBits(value),
                    Float.floatToRawIntBits((Float) actual), "float " + value);
        }
        for (double value : DOUBLE_VALUES) {
            Object actual = host.getMethod(doubleName(value)).invoke(null);
            assertEquals(Double.doubleToRawLongBits(value),
                    Double.doubleToRawLongBits((Double) actual), "double " + value);
        }
        assertEquals(0f, host.getMethod("fc0").invoke(null));
        assertEquals(1f, host.getMethod("fc1").invoke(null));
        assertEquals(2f, host.getMethod("fc2").invoke(null));
        assertEquals(0d, host.getMethod("dc0").invoke(null));
        assertEquals(1d, host.getMethod("dc1").invoke(null));
    }

    @Test
    void nanConstantsAreLeftBitExact() throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = HOST;
        node.superName = "java/lang/Object";

        MethodNode nan = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nan", "()F", null, null);
        nan.instructions.add(new LdcInsnNode(Float.NaN));
        nan.instructions.add(new InsnNode(Opcodes.FRETURN));
        node.methods.add(nan);

        MethodNode dnan = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dnan", "()D", null, null);
        dnan.instructions.add(new LdcInsnNode(Double.NaN));
        dnan.instructions.add(new InsnNode(Opcodes.DRETURN));
        node.methods.add(dnan);

        IntObfuscation transform = new IntObfuscation(new VaporConfig());
        for (MethodNode method : node.methods) {
            transform.process(null, node, method);
        }
        assertEquals(0, countCalls(node, "java/lang/Float", "intBitsToFloat")
                        + countCalls(node, "java/lang/Double", "longBitsToDouble"),
                "NaN constants must be skipped to keep their payload bits exact");

        Class<?> host = new Loader().define(write(node));
        assertTrue(Float.isNaN((Float) host.getMethod("nan").invoke(null)));
        assertTrue(Double.isNaN((Double) host.getMethod("dnan").invoke(null)));
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
        for (float value : FLOAT_VALUES) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, floatName(value), "()F", null, null);
            method.instructions.add(new LdcInsnNode(value));
            method.instructions.add(new InsnNode(Opcodes.FRETURN));
            node.methods.add(method);
        }
        for (double value : DOUBLE_VALUES) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, doubleName(value), "()D", null, null);
            method.instructions.add(new LdcInsnNode(value));
            method.instructions.add(new InsnNode(Opcodes.DRETURN));
            node.methods.add(method);
        }
        for (int i = 0; i < 3; i++) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fc" + i, "()F", null, null);
            method.instructions.add(new InsnNode(Opcodes.FCONST_0 + i));
            method.instructions.add(new InsnNode(Opcodes.FRETURN));
            node.methods.add(method);
        }
        for (int i = 0; i < 2; i++) {
            MethodNode method = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dc" + i, "()D", null, null);
            method.instructions.add(new InsnNode(Opcodes.DCONST_0 + i));
            method.instructions.add(new InsnNode(Opcodes.DRETURN));
            node.methods.add(method);
        }
        return node;
    }

    private static int countOpcode(ClassNode node, int opcode) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == opcode) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countCalls(ClassNode node, String owner, String name) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
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

    private static String floatName(float value) {
        return "f" + Integer.toHexString(Float.floatToRawIntBits(value));
    }

    private static String doubleName(double value) {
        return "d" + Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(HOST.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
