package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import dev.vapor.tree.JClass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

/**
 * Turns a straight-line method into a two-block method by adding an opaque
 * entry branch whose target is the next instruction. This gives
 * {@link FlowFlatten} a branch boundary without changing semantics. Methods
 * with an existing branch, exception table, or an uninitialized-this
 * constructor are left alone.
 */
public final class BlockSplit extends Transformer {

    private final VaporConfig config;

    public BlockSplit(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "BlockSplit";
    }

    @Override
    public boolean enabled() {
        return config.transformers.flowFlatten && config.hardening.splitSingleBlock;
    }

    @Override
    public void run(Context context) {
        for (JClass clazz : context.classes()) {
            context.checkCancelled();
            ClassNode owner = clazz.node();
            for (MethodNode method : owner.methods) {
                if (shouldSplit(context, owner, method)) {
                    LabelNode next = new LabelNode();
                    InsnList branch = new InsnList();
                    branch.add(new InsnNode(Opcodes.ICONST_0));
                    branch.add(new JumpInsnNode(Opcodes.IFNE, next));
                    branch.add(next);
                    method.instructions.insert(branch);
                    method.maxStack = Math.max(method.maxStack, 1);
                }
            }
        }
    }

    private boolean shouldSplit(Context context, ClassNode owner, MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }
        if (context.isArtificialMethod(owner.name, method.name, method.desc)) {
            return false;
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }
        if (method.instructions.size() < Math.max(2, config.hardening.blockSplitMinInstructions)) {
            return false;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode
                    || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                return false;
            }
        }
        return true;
    }
}
