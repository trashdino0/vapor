package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.testing.Reload;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockSplitTest {

    @Test
    void straightLineMethodGetsAnEntryBranchAndKeepsBehavior() throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = "dev/vapor/testing/SplitHost";
        node.superName = "java/lang/Object";

        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(I)I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);

        try (Context context = new Context(null, null, List.of())) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(node)));
            new BlockSplit(new VaporConfig()).run(context);
        }

        boolean branch = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode) {
                branch = true;
            }
        }
        assertTrue(branch, "BlockSplit must add an opaque entry branch");

        Class<?> host = Reload.define(node.name.replace('/', '.'), Reload.write(node));
        assertEquals(27, host.getMethod("compute", int.class).invoke(null, 3));
    }
}
