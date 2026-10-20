package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import dev.vapor.tree.JClass;
import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * v2: constant obfuscation. Every int/long constant that is <em>pushed on the
 * stack</em> becomes an exclusive-or against a per-site key:
 *
 * <pre>
 *   iconst_3
 *   -&gt;
 *   push (3 ^ key); push key; ixor        // still 3 at runtime, no plain 3 anywhere
 * </pre>
 *
 * <p>Only real constant push instructions are touched ({@code ICONST_*},
 * {@code LCONST_*}, {@code BIPUSH}, {@code SIPUSH}, {@code LDC int/long}).
 * Constants that are not stack pushes are untouched by construction: switch
 * keys and bounds ({@code tableswitch}/{@code lookupswitch} are their own node
 * types), {@code iinc} operands, array dimensions ({@code multianewarray} node),
 * {@code newarray} types, and invokedynamic/bootstrap arguments. Float/double
 * constants are left for v3.</p>
 *
 * <p>The rewrite is a pure stack-neutral replacement (consumes nothing,
 * produces the same single value), so it cannot disturb control flow, exception
 * ranges or frames, and no stack analysis is needed. Budgeted with
 * {@code hardening.maxOverhead}; artificial methods (string decryptors) are
 * skipped.</p>
 */
public final class IntObfuscation extends Transformer {

    private static final int MAX_INSTRUCTIONS = 20000;

    private final VaporConfig config;

    public IntObfuscation(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "IntObfuscation";
    }

    @Override
    public boolean enabled() {
        return config.transformers.integerObfuscation;
    }

    @Override
    public void run(Context context) {
        for (JClass clazz : context.classes()) {
            ClassNode owner = clazz.node();
            for (MethodNode method : owner.methods) {
                try {
                    process(context, owner, method);
                } catch (Throwable e) {
                    if (!config.failsafe) {
                        throw e;
                    }
                }
            }
        }
    }

    void process(Context context, ClassNode owner, MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return;
        }
        if (context != null && context.isArtificialMethod(owner.name, method.name, method.desc)) {
            return;
        }
        int count = method.instructions.size();
        if (count > MAX_INSTRUCTIONS) {
            return;
        }
        double overhead = Math.max(0.0, config.hardening.maxOverhead - 1.0);
        int budget = (int) (overhead * count);
        if (budget < 2) {
            return;
        }

        int added = 0;
        int site = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            Integer intValue = pushIntValue(insn);
            Long longValue = intValue != null ? null : pushLongValue(insn);
            if (intValue == null && longValue == null) {
                insn = next;
                continue;
            }
            if (added + 2 > budget) {
                break;
            }
            int key = Mix.of(config.seed, owner.name, method.name + method.desc, site++);
            if (intValue != null) {
                replaceInt(method, insn, intValue, key);
            } else {
                replaceLong(method, insn, longValue, key);
            }
            added += 2;
            insn = next;
        }
    }

    /** @return the pushed value if this instruction pushes an int constant, else null. */
    private static Integer pushIntValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return push.operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
            return value;
        }
        return null;
    }

    /** @return the pushed value if this instruction pushes a long constant, else null. */
    private static Long pushLongValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0) {
            return 0L;
        }
        if (opcode == Opcodes.LCONST_1) {
            return 1L;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long value) {
            return value;
        }
        return null;
    }

    private static void replaceInt(MethodNode method, AbstractInsnNode insn, int value, int key) {
        InsnList list = new InsnList();
        Insns.pushInt(list, value ^ key);
        Insns.pushInt(list, key);
        list.add(new InsnNode(Opcodes.IXOR));
        method.instructions.insertBefore(insn, list);
        method.instructions.remove(insn);
    }

    private static void replaceLong(MethodNode method, AbstractInsnNode insn, long value, int key) {
        long longKey = ((long) key << 32) | (key & 0xFFFFFFFFL);
        InsnList list = new InsnList();
        list.add(Insns.longNode(value ^ longKey));
        list.add(Insns.longNode(longKey));
        list.add(new InsnNode(Opcodes.LXOR));
        method.instructions.insertBefore(insn, list);
        method.instructions.remove(insn);
    }
}
