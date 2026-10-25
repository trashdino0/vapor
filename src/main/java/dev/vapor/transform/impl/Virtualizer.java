package dev.vapor.transform.impl;

import dev.vapor.util.Mix;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative prototype of method virtualization.
 *
 * <p>An eligible method body is translated into a compact {@code int[]} stack
 * program (documented on {@link VirtualTranslator}) and replaced by a static call
 * to a generated helper class containing a dedicated interpreter. The original
 * arithmetic is executed by the helper through the corresponding JVM opcodes, so
 * overflow, integer division/remainder by zero, and shift-count masking behave
 * exactly as before. The interpreter is deterministic and uses no reflection.</p>
 *
 * <h2>Supported subset</h2>
 *
 * <ul>
 *   <li>static, non-abstract/non-native methods, not {@code <init>}/{@code <clinit>};</li>
 *   <li>all parameters and the return type are {@code int} or the return type is
 *       {@code void}; no objects, arrays, {@code long}, {@code float}, {@code double};</li>
 *   <li>no exception table, no {@code new}, no field access, no method calls, no
 *       {@code invokedynamic}, no {@code switch}, no monitor, no {@code jsr}/{@code ret};</li>
 *   <li>only the opcodes listed in {@link VirtualTranslator}: int constants,
 *       {@code ILOAD}/{@code ISTORE}/{@code IINC}, the twelve int arithmetic/
 *       bitwise shifts, {@code IFEQ..IF_ICMPLE}, {@code GOTO}, {@code IRETURN}
 *       and {@code RETURN}.</li>
 * </ul>
 *
 * <p>Anything else is rejected before the method is touched; the caller can
 * inspect {@link Result#skipReason()} for diagnostics.</p>
 *
 * <h2>Integration sketch</h2>
 *
 * <pre>{@code
 * Virtualizer virtualizer = new Virtualizer(config.seed);
 * List<ClassNode> helpers = new ArrayList<>();
 * for (MethodNode method : new ArrayList<>(owner.methods)) {
 *     Virtualizer.Result result = virtualizer.transform(owner, method);
 *     if (result.transformed()) {
 *         helpers.add(result.helper());
 *     }
 * }
 * // Add the helpers as JClass entries after iterating context.classes().
 * // Renaming them together with the owner also remaps the call site.
 * }</pre>
 *
 * <p>The generated helper has class-file version 52 and no stack-map frames in
 * its tree; serialize it with a writer using {@link ClassWriter#COMPUTE_FRAMES}
 * (the project's {@code VaporClassWriter} does), or use
 * {@link Result#helperBytes()} which already does this. A helper's interpreter
 * keeps all mutable state in local arrays, so virtualized methods are reentrant
 * and thread-safe. Multiple methods get distinct helper classes; the name is
 * derived from the seed, owner name and method name/descriptor. The integration
 * pass should still check for a name collision with an existing class.</p>
 */
public final class Virtualizer {

    /** Name of the generated interpreter method; the owner's call site uses it. */
    public static final String RUN_METHOD = "run";

    private static final int HELPER_NAME_LIMIT = 65535;

    private final long seed;

    public Virtualizer() {
        this(0L);
    }

    public Virtualizer(long seed) {
        this.seed = seed;
    }

    /**
     * Translates {@code method} in place and returns the generated helper, or a
     * skip result. The method is not modified when the result is not
     * {@link Result#transformed() transformed}.
     */
    public Result transform(ClassNode owner, MethodNode method) {
        String ineligible = VirtualTranslator.ineligibility(owner, method);
        if (ineligible != null) {
            return Result.skipped(ineligible);
        }

        VirtualTranslator.Translated translated;
        try {
            translated = VirtualTranslator.translate(method);
        } catch (VirtualTranslator.Unsupported | RuntimeException e) {
            return Result.skipped(reason(e));
        }

        String helperName = helperName(owner, method);
        if (helperName.length() > HELPER_NAME_LIMIT) {
            return Result.skipped("helper name too long");
        }

        ClassNode helper;
        try {
            helper = VirtualInterpreterEmitter.build(helperName, method, translated);
        } catch (RuntimeException e) {
            // The owner method is still untouched at this point.
            return Result.skipped("helper generation: " + reason(e));
        }

        rewrite(method, helperName, Type.getArgumentTypes(method.desc));
        return new Result(helper, translated.program, translated.maxStack, translated.maxLocals,
                method.desc, null);
    }

    /** True when the method passes the cheap subset checks; translation can still reject it. */
    public static boolean isEligible(ClassNode owner, MethodNode method) {
        return VirtualTranslator.ineligibility(owner, method) == null;
    }

    /**
     * Convenience API using the default seed: transforms the method and appends
     * the helper to {@code helpers} when successful.
     *
     * @return {@code true} iff the method was rewritten and a helper was added
     */
    public static boolean transform(ClassNode owner, MethodNode method, List<ClassNode> helpers) {
        Result result = new Virtualizer().transform(owner, method);
        if (!result.transformed()) {
            return false;
        }
        helpers.add(result.helper());
        return true;
    }

    private static String reason(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private String helperName(ClassNode owner, MethodNode method) {
        String key = method.name + method.desc;
        int high = Mix.of(seed, owner.name, key, 0x564D);
        int low = Mix.of(seed, owner.name, key, 0x9E37);
        return owner.name + "$vapor$vm$" + Integer.toHexString(high) + "$" + Integer.toHexString(low);
    }

    private static void rewrite(MethodNode method, String helperName, Type[] arguments) {
        InsnList body = new InsnList();
        int slot = 0;
        for (Type argument : arguments) {
            body.add(new VarInsnNode(Opcodes.ILOAD, slot));
            slot += argument.getSize(); // all supported arguments are ints, size 1
        }
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helperName, RUN_METHOD, method.desc, false));
        boolean returnsInt = Type.getReturnType(method.desc).getSort() == Type.INT;
        body.add(new InsnNode(returnsInt ? Opcodes.IRETURN : Opcodes.RETURN));

        method.instructions = body;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = Math.max(arguments.length, returnsInt ? 1 : 0);
        method.maxLocals = slot;
    }

    /**
     * Result of one {@link Virtualizer#transform(ClassNode, MethodNode)} call. A skipped
     * result carries a reason and no helper; the input method is unchanged.
     */
    public static final class Result {

        private final ClassNode helper;
        private final int[] program;
        private final int maxStack;
        private final int maxLocals;
        private final String descriptor;
        private final String skipReason;

        private Result(ClassNode helper, int[] program, int maxStack, int maxLocals,
                       String descriptor, String skipReason) {
            this.helper = helper;
            this.program = program;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
            this.descriptor = descriptor;
            this.skipReason = skipReason;
        }

        private static Result skipped(String reason) {
            return new Result(null, null, 0, 0, null, reason);
        }

        /** True when the owner method was rewritten and {@link #helper()} is available. */
        public boolean transformed() {
            return helper != null;
        }

        /** The generated helper class, or {@code null} when skipped. */
        public ClassNode helper() {
            return helper;
        }

        /** Internal name of the helper, or {@code null} when skipped. */
        public String helperName() {
            return helper == null ? null : helper.name;
        }

        /** Name of the generated interpreter method ({@link Virtualizer#RUN_METHOD}). */
        public String helperRunName() {
            return RUN_METHOD;
        }

        /** Descriptor of the virtualized method and of the helper's interpreter. */
        public String descriptor() {
            return descriptor;
        }

        /**
         * Copy of the encoded program, or {@code null} when skipped. The cell
         * layout is documented on {@link VirtualTranslator}; branch operands are
         * absolute indexes into this array.
         */
        public int[] program() {
            return program == null ? null : program.clone();
        }

        public int programLength() {
            return program == null ? 0 : program.length;
        }

        public int maxStack() {
            return maxStack;
        }

        public int maxLocals() {
            return maxLocals;
        }

        /** Why the method was left untouched, or {@code null} on success. */
        public String skipReason() {
            return skipReason;
        }

        /**
         * Serializes the helper with computed frames/maxs, independent of the
         * caller's writer. Returns {@code null} when skipped.
         */
        public byte[] helperBytes() {
            if (helper == null) {
                return null;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    // The helper only references java/lang/Object and
                    // java/lang/IllegalStateException; no user hierarchy is needed.
                    return "java/lang/Object";
                }
            };
            helper.accept(writer);
            return writer.toByteArray();
        }
    }
}
