package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.naming.Dictionary;
import dev.vapor.tree.JClass;
import dev.vapor.transform.Transformer;
import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Replaces string constants with a call to a per-class decryptor:
 *
 * <pre>
 *   ldc  "secret"
 *   -&gt;
 *   ldc  "&lt;encoded, same length&gt;"   // unreadable constant
 *   push key
 *   invokestatic  a.&lt;decryptor&gt;(Ljava/lang/String;I)Ljava/lang/String;
 * </pre>
 *
 * <p>v2: the decryptor is <b>polymorphic</b> — each class picks one of four math
 * shapes (XOR+index / ADD+index / multiply by an odd constant mod 2¹⁶ with the
 * modular inverse embedded / byte-swap+XOR) and one of three loop forms
 * (forward / backward / equality-tested), all derived deterministically from
 * the seed and the class name. A single decryptor signature no longer matches
 * every class. With {@code strings.polymorphic=false} v1's XOR shape is used.</p>
 *
 * <p>The decryptor is a synthetic {@code static} method injected into every
 * class that had at least one eligible string; it is registered as an
 * artificial so noise transforms leave it alone. Keys are derived from the
 * class name, the configured seed and a per-class counter, so output stays
 * deterministic.</p>
 *
 * <p>Deliberately not encrypted (documented): strings in {@code ConstantValue}
 * field attributes, annotation values, and invokedynamic bootstrap arguments
 * (string-concat recipes, record {@code toString} recipes) — those are not
 * {@code LDC} instructions in method bodies and changing them would require
 * runtime wrappers.</p>
 */
public final class StringEncryptor extends Transformer {

    private static final String STRING = "java/lang/String";
    private static final String DECRYPTOR_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    /** Decryptor math: XOR with key+i. */
    static final int MATH_XOR = 0;
    /** Decryptor math: subtract key+i. */
    static final int MATH_ADD = 1;
    /** Decryptor math: multiply by an odd constant, modular inverse embedded. */
    static final int MATH_MUL = 2;
    /** Decryptor math: byte-swap then XOR with key+i. */
    static final int MATH_SWAP = 3;

    private final VaporConfig config;

    public StringEncryptor(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "StringEncryptor";
    }

    @Override
    public boolean enabled() {
        return config.transformers.encryptStrings;
    }

    @Override
    public void run(Context context) {
        for (JClass clazz : context.classes()) {
            String decryptor = encrypt(clazz.node());
            if (decryptor != null) {
                context.addArtificialMethod(clazz.node().name, decryptor, DECRYPTOR_DESC);
            }
        }
    }

    /** @return the decryptor method name if any string was replaced, else {@code null}. */
    String encrypt(ClassNode node) {
        int minLength = Math.max(0, config.strings.minLength);
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        String decryptorName = freshName(node);
        int math = MATH_XOR;
        int loop = 0;
        if (config.strings.polymorphic) {
            int pick = Mix.of(config.seed, node.name, "<decryptor>", 0x5A17);
            math = Math.floorMod(pick, 4);
            loop = Math.floorMod(pick >>> 8, 3);
        }
        int counter = 0;
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                        && value.length() >= minLength && !value.isEmpty()) {
                    int key = keyFor(node.name, (int) config.seed, counter++);
                    method.instructions.insert(ldc,
                            callSequence(node.name, decryptorName, encode(value, key, math), key, isInterface));
                    method.instructions.remove(ldc);
                    replaced = true;
                }
                insn = next;
            }
        }
        if (replaced) {
            if (isInterface && node.version < Opcodes.V1_8) {
                // Static interface methods only exist from class-file version 52 (Java 8).
                // Some jars still ship older interfaces (cfr-0.152.jar has v50 ones); the
                // decryptor we are about to inject would be illegal at their original
                // version, so the class is promoted. Output targets Java 8+ anyway.
                node.version = Opcodes.V1_8;
            }
            node.methods.add(decryptor(decryptorName, isInterface, math, loop));
            return decryptorName;
        }
        return null;
    }

    /** Encodes with the same math shape the decryptor will invert. */
    static String encode(String value, int key, int math) {
        int multiplier = key | 1;
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int c = chars[i];
            chars[i] = (char) switch (math) {
                case MATH_ADD -> c + key + i;
                case MATH_MUL -> c * multiplier + i;
                case MATH_SWAP -> swap16(c) ^ (key + i);
                default -> c ^ (key + i);
            };
        }
        return new String(chars);
    }

    private static int swap16(int value) {
        return ((value & 0xFF) << 8) | ((value >>> 8) & 0xFF);
    }

    /** Deterministic per-string key from class name, seed and index. */
    private static int keyFor(String className, int seed, int index) {
        int h = className.hashCode() * 0x9E3779B9;
        int s = seed * 0x85EBCA6B;
        int i = (index + 1) * 0xC2B2AE35;
        int key = h ^ s ^ i;
        return key == 0 ? 0x5F3759DF : key;
    }

    private static InsnList callSequence(String className, String decryptorName, String encoded, int key,
                                         boolean isInterface) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(encoded));
        Insns.pushInt(list, key);
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, className, decryptorName, DECRYPTOR_DESC, isInterface));
        return list;
    }

    /** First name from the dictionary sequence not used by any method of the class. */
    private static String freshName(ClassNode node) {
        Set<String> taken = new HashSet<>();
        for (MethodNode method : node.methods) {
            taken.add(method.name);
        }
        for (int i = 0; ; i++) {
            String candidate = Dictionary.nameFor(i);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Builds the synthetic decryptor:
     *
     * <pre>
     *   static String x(String encoded, int key) {
     *       char[] c = encoded.toCharArray();
     *       &lt;loop over i&gt; c[i] = (char) &lt;inverse of the encode math&gt;;
     *       return new String(c);
     *   }
     * </pre>
     *
     * <p>Interfaces get {@code public static} decryptors: private interface
     * methods require class-file version 53+, and output must stay loadable on
     * Java 8 (v52).</p>
     */
    private static MethodNode decryptor(String name, boolean isInterface, int math, int loop) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                | (isInterface ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
        MethodNode method = new MethodNode(Opcodes.ASM9, access, name, DECRYPTOR_DESC, null, null);
        InsnList code = method.instructions;
        LabelNode head = new LabelNode();
        LabelNode end = new LabelNode();

        // char[] a = encoded.toCharArray();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING, "toCharArray", "()[C", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // int i = <start>;
        if (loop == 1) { // backward
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new InsnNode(Opcodes.ARRAYLENGTH));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.ISUB));
        } else {
            Insns.pushInt(code, 0);
        }
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));

        // MATH_MUL: int m = key | 1; int inv = m; inv *= 2 - m * inv;  x4
        // (Newton iteration doubling correct bits: 1 → 2 → 4 → 8 → 16, all we need
        // for the inverse modulo 2^16; derived per call from the runtime key.)
        if (math == MATH_MUL) {
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IOR));
            code.add(new VarInsnNode(Opcodes.ISTORE, 4));
            code.add(new VarInsnNode(Opcodes.ILOAD, 4));
            code.add(new VarInsnNode(Opcodes.ISTORE, 5));
            for (int round = 0; round < 4; round++) {
                code.add(new VarInsnNode(Opcodes.ILOAD, 5));
                code.add(new InsnNode(Opcodes.ICONST_2));
                code.add(new VarInsnNode(Opcodes.ILOAD, 4));
                code.add(new VarInsnNode(Opcodes.ILOAD, 5));
                code.add(new InsnNode(Opcodes.IMUL));
                code.add(new InsnNode(Opcodes.ISUB));
                code.add(new InsnNode(Opcodes.IMUL));
                code.add(new VarInsnNode(Opcodes.ISTORE, 5));
            }
        }

        code.add(head);
        switch (loop) {
            case 1 -> { // i >= 0 ?
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new JumpInsnNode(Opcodes.IFLT, end));
            }
            case 2 -> { // i == length ?
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new InsnNode(Opcodes.ARRAYLENGTH));
                code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, end));
            }
            default -> { // i >= length ?
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new InsnNode(Opcodes.ARRAYLENGTH));
                code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
            }
        }

        // a[i] = (char) <inverse math>(a[i], key, i);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.CALOAD));
        switch (math) {
            case MATH_ADD -> { // c = v - key - i
                code.add(new VarInsnNode(Opcodes.ILOAD, 1));
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new InsnNode(Opcodes.ISUB));
            }
            case MATH_MUL -> { // c = (v - i) * inv, inv = inverse(key | 1) mod 2^16 (computed above)
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new InsnNode(Opcodes.ISUB));
                code.add(new VarInsnNode(Opcodes.ILOAD, 5));
                code.add(new InsnNode(Opcodes.IMUL));
            }
            case MATH_SWAP -> { // c = swap16(v ^ (key + i)), byte-masked swap ((v & 0xFF) << 8) | ((v >>> 8) & 0xFF)
                code.add(new VarInsnNode(Opcodes.ILOAD, 1));
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new InsnNode(Opcodes.IXOR));
                code.add(new InsnNode(Opcodes.DUP));
                Insns.pushInt(code, 0xFF);
                code.add(new InsnNode(Opcodes.IAND));
                Insns.pushInt(code, 8);
                code.add(new InsnNode(Opcodes.ISHL));
                code.add(new InsnNode(Opcodes.SWAP));
                Insns.pushInt(code, 8);
                code.add(new InsnNode(Opcodes.IUSHR));
                Insns.pushInt(code, 0xFF);
                code.add(new InsnNode(Opcodes.IAND));
                code.add(new InsnNode(Opcodes.IOR));
            }
            default -> { // c = v ^ (key + i)
                code.add(new VarInsnNode(Opcodes.ILOAD, 1));
                code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new InsnNode(Opcodes.IXOR));
            }
        }
        code.add(new InsnNode(Opcodes.I2C));
        code.add(new InsnNode(Opcodes.CASTORE));

        code.add(new IincInsnNode(3, loop == 1 ? -1 : 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, head));

        code.add(end);
        code.add(new TypeInsnNode(Opcodes.NEW, STRING));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, STRING, "<init>", "([C)V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }
}
