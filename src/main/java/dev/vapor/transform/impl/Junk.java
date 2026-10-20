package dev.vapor.transform.impl;

import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Self-contained junk emitter shared by {@link DeadCode} and {@link FlowShuffle}:
 * every operation consumes only values this block pushed first, and the block is
 * balanced (stack in = stack out). It never divides, never dereferences, never
 * calls — it cannot throw even if it (mistakenly) ran.
 */
final class Junk {

    private Junk() {
    }

    /** @return approximate number of instructions appended. */
    static int emit(InsnList out, Mix.Rand rand, int junkSlot) {
        int count = 0;
        int blocks = 2 + rand.nextInt(3);
        for (int i = 0; i < blocks; i++) {
            Insns.pushInt(out, rand.nextInt());
            Insns.pushInt(out, rand.nextInt());
            switch (rand.nextInt(4)) {
                case 0 -> out.add(new InsnNode(Opcodes.IADD));
                case 1 -> out.add(new InsnNode(Opcodes.IMUL));
                case 2 -> out.add(new InsnNode(Opcodes.IXOR));
                default -> out.add(new InsnNode(Opcodes.ISUB));
            }
            out.add(new VarInsnNode(Opcodes.ISTORE, junkSlot));
            count += 4;
        }
        if (rand.nextBoolean()) {
            LabelNode head = new LabelNode();
            LabelNode end = new LabelNode();
            Insns.pushInt(out, 2 + rand.nextInt(8));
            out.add(new VarInsnNode(Opcodes.ISTORE, junkSlot));
            out.add(head);
            out.add(new VarInsnNode(Opcodes.ILOAD, junkSlot));
            Insns.pushInt(out, 40 + rand.nextInt(40));
            out.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
            out.add(new IincInsnNode(junkSlot, 1));
            out.add(new JumpInsnNode(Opcodes.GOTO, head));
            out.add(end);
            count += 7;
        }
        return count;
    }
}
