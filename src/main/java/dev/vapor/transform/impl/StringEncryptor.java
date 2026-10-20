package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.naming.Dictionary;
import dev.vapor.tree.JClass;
import dev.vapor.transform.Transformer;
import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * <p>v3.1 also covers {@code invokedynamic} string concatenation: call sites of
 * {@code StringConcatFactory.makeConcatWithConstants} are rewritten into explicit
 * {@code StringBuilder} chains whose literal parts go through the same decryptor.
 * The writer then drops the now-unreferenced {@code BootstrapMethods} entry, so the
 * recipe text leaves the class file entirely. Sites whose recipe contains
 * {@code \u0002} constants (literal control characters) or that do not parse are
 * left untouched (fail-safe). Record {@code ObjectMethods} bootstraps are never
 * touched.</p>
 *
 * <p>Deliberately not encrypted (documented): strings in {@code ConstantValue}
 * field attributes, annotation values, and record {@code toString} bootstrap
 * arguments — those are not reachable as constants in method bodies.</p>
 */
public final class StringEncryptor extends Transformer {

    private static final String STRING = "java/lang/String";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";
    private static final String CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
    private static final String MAKE_CONCAT = "makeConcatWithConstants";
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
            boolean methodChanged = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                        && eligible(value, minLength)) {
                    int key = keyFor(node.name, (int) config.seed, counter++);
                    method.instructions.insert(ldc,
                            callSequence(node.name, decryptorName, encode(value, key, math), key, isInterface));
                    method.instructions.remove(ldc);
                    replaced = true;
                    methodChanged = true;
                } else if (insn instanceof InvokeDynamicInsnNode indy && isStringConcat(indy)) {
                    int[] shared = {counter};
                    InsnList sequence = concatSequence(node, method, indy, decryptorName, isInterface, minLength, math, shared);
                    counter = shared[0];
                    if (sequence != null) {
                        method.instructions.insertBefore(indy, sequence);
                        method.instructions.remove(indy);
                        replaced = true;
                        methodChanged = true;
                    }
                }
                insn = next;
            }
            if (methodChanged) {
                // Decryption/concat sequences push a few slots more than the instruction they
                // replace; downstream analyzers (DeadCode, FlowFlatten) size their frames from
                // maxStack. The writer recomputes the real value with COMPUTE_MAXS.
                method.maxStack += 4;
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

    /** Same rule for {@code LDC} constants and for concat-recipe literals. */
    private static boolean eligible(String value, int minLength) {
        return value.length() >= minLength && !value.isEmpty();
    }

    private static boolean isStringConcat(InvokeDynamicInsnNode indy) {
        return indy.bsm != null
                && CONCAT_FACTORY.equals(indy.bsm.getOwner())
                && MAKE_CONCAT.equals(indy.bsm.getName());
    }

    /**
     * Rewrites one {@code makeConcatWithConstants} call site into an explicit
     * {@code StringBuilder} chain:
     *
     * <pre>
     *   "a" + x + "b"    // recipe "a\u0001b", descriptor (Ljava/lang/String;)…
     *   -&gt;
     *   astore tmp; new StringBuilder
     *   ldc &lt;encoded "a"&gt;; push key; invokestatic decryptor; append(String)
     *   aload tmp; append(String)
     *   ldc &lt;encoded "b"&gt;; push key; invokestatic decryptor; append(String)
     *   toString
     * </pre>
     *
     * <p>Arguments are popped into fresh locals in reverse descriptor order and
     * appended in source order with the overload matching their static type
     * ({@code append(int)} for a {@code char} would print its code point). The
     * sequence is straight-line: no original jump can land inside it and no frame
     * merge ever observes the temporary locals. Returns {@code null} — leaving the
     * call site untouched — when the bootstrap carries {@code \u0002} constants
     * (literal control characters ride in that way), when the recipe and the
     * descriptor disagree, or on any other unexpected shape.</p>
     */
    private InsnList concatSequence(ClassNode node, MethodNode method, InvokeDynamicInsnNode indy,
                                    String decryptorName, boolean isInterface, int minLength,
                                    int math, int[] counter) {
        if (indy.bsmArgs == null || indy.bsmArgs.length != 1 || !(indy.bsmArgs[0] instanceof String recipe)) {
            return null;
        }
        Type[] args = Type.getArgumentTypes(indy.desc);

        // Parse the recipe into ordered parts: String for literals, Integer for arguments.
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int consumed = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char c = recipe.charAt(i);
            if (c == '\u0001') {
                if (literal.length() > 0) {
                    parts.add(literal.toString());
                    literal.setLength(0);
                }
                if (consumed >= args.length) {
                    return null; // more arguments than the descriptor declares
                }
                parts.add(consumed++);
            } else if (c == '\u0002') {
                return null; // literal control characters ride in as bootstrap constants
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            parts.add(literal.toString());
        }
        if (consumed != args.length) {
            return null; // descriptor arguments the recipe never consumes
        }

        // Pop into fresh locals (reverse order) and reserve the slots for the method.
        int[] slots = new int[args.length];
        int slot = method.maxLocals;
        for (int i = 0; i < args.length; i++) {
            slots[i] = slot;
            slot += args[i].getSize();
        }
        method.maxLocals = slot;

        InsnList code = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            code.add(new VarInsnNode(storeOpcode(args[i]), slots[i]));
        }
        code.add(new TypeInsnNode(Opcodes.NEW, STRING_BUILDER));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false));
        for (Object part : parts) {
            if (part instanceof Integer argument) {
                code.add(new VarInsnNode(loadOpcode(args[argument]), slots[argument]));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                        appendDescriptor(args[argument]), false));
            } else {
                String value = (String) part;
                if (eligible(value, minLength)) {
                    int key = keyFor(node.name, (int) config.seed, counter[0]++);
                    code.add(callSequence(node.name, decryptorName, encode(value, key, math), key, isInterface));
                } else {
                    code.add(new LdcInsnNode(value));
                }
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString",
                "()Ljava/lang/String;", false));
        return code;
    }

    private static int storeOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    private static int loadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    /**
     * The {@code StringBuilder.append} overload matching the call-site descriptor
     * type. Primitives must use their exact overload ({@code append(int)} for a
     * {@code char} or {@code boolean} would print the code point / 0-1), but every
     * reference type — including {@code String}, {@code CharSequence} and
     * {@code char[]} — must use {@code append(Object)}: the concat factory converts
     * reference arguments with {@code String.valueOf} semantics (measured: a
     * {@code [C} argument yields {@code [C@hash}, not its characters — exactly
     * {@code valueOf(Object)} behavior), so {@code append(char[])} would silently
     * change the result.
     */
    private static String appendDescriptor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case Type.CHAR -> "(C)Ljava/lang/StringBuilder;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;";
            case Type.LONG -> "(J)Ljava/lang/StringBuilder;";
            case Type.FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            default -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
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
