package dev.vapor.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

/** Small instruction-building helpers shared across transforms. */
public final class Insns {

    private Insns() {
    }

    /** The smallest instruction sequence pushing an int constant. */
    public static AbstractInsnNode intNode(int value) {
        if (value == -1) {
            return new InsnNode(Opcodes.ICONST_M1);
        }
        if (value >= 0 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    public static void pushInt(InsnList out, int value) {
        out.add(intNode(value));
    }

    /** The smallest instruction sequence pushing a long constant. */
    public static AbstractInsnNode longNode(long value) {
        if (value == 0L) {
            return new InsnNode(Opcodes.LCONST_0);
        }
        if (value == 1L) {
            return new InsnNode(Opcodes.LCONST_1);
        }
        return new LdcInsnNode(value);
    }
}
