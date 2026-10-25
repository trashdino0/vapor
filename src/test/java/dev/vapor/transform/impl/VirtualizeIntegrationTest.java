package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.testing.Reload;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the pipeline wrapper: translation happens before the
 * flow passes, while helper classes are only committed afterwards.
 */
class VirtualizeIntegrationTest {

    @Test
    void helpersAreCommittedAndTheOwnerStillRuns() throws Exception {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V1_8;
        owner.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        owner.name = "dev/vapor/testing/VmIntegrationHost";
        owner.superName = "java/lang/Object";
        MethodNode compute = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(I)I", null, null);
        compute.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compute.instructions.add(new InsnNode(Opcodes.ICONST_3));
        compute.instructions.add(new InsnNode(Opcodes.IMUL));
        compute.instructions.add(new InsnNode(Opcodes.ICONST_1));
        compute.instructions.add(new InsnNode(Opcodes.IADD));
        compute.instructions.add(new InsnNode(Opcodes.IRETURN));
        compute.maxStack = 2;
        compute.maxLocals = 1;
        owner.methods.add(compute);

        VaporConfig config = VaporConfig.defaults();
        config.transformers.virtualize = true;
        Virtualize virtualize = new Virtualize(config);
        Map<String, byte[]> definitions = new LinkedHashMap<>();

        try (Context context = new Context(null, null, List.of())) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(owner)));
            virtualize.run(context);
            assertEquals(1, context.classes().size(), "helpers must not be visible to flow passes yet");
            assertTrue(owner.methods.get(0).instructions.getFirst() instanceof VarInsnNode,
                    "owner method must be rewritten during the early pass");

            virtualize.commit(context);
            assertEquals(2, context.classes().size(), "commit must add exactly one helper");
            for (JClass clazz : context.classes()) {
                definitions.put(clazz.name().replace('/', '.'), Reload.write(clazz.node()));
            }
        }

        Class<?> loaded = new VirtualClassLoader(definitions).loadClass(
                owner.name.replace('/', '.'));
        assertEquals(16, loaded.getMethod("compute", int.class).invoke(null, 5));

        boolean helperCall = false;
        for (var insn = owner.methods.get(0).instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.name.equals(Virtualizer.RUN_METHOD)) {
                helperCall = true;
            }
        }
        assertTrue(helperCall, "owner must call the generated interpreter");
    }
}
