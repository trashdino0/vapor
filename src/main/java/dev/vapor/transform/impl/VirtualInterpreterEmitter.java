package dev.vapor.transform.impl;

import dev.vapor.util.Insns;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Builds the synthetic helper class that interprets one virtualized method.
 *
 * <p>The helper is deliberately self-contained: it owns its {@code int[]}
 * program, its interpreter and nothing else. No runtime class has to be embedded
 * in the output, and the helper targets class-file version 52 (Java 8), so no
 * class-version patching is involved. The only reference types it mentions are
 * {@code int[]}, {@code java/lang/Object} and
 * {@code java/lang/IllegalStateException}.</p>
 *
 * <p>Shape of the generated class:</p>
 *
 * <pre>
 *   public final synthetic class Owner$vapor$vm$HASH {
 *       private static final int[] p;
 *       static { p = new int[]{...}; }
 *
 *       public static int run(int a0, int a1) {   // original descriptor
 *           int[] l = new int[maxLocals];          // original local slots
 *           l[0] = a0; l[1] = a1;
 *           int[] s = new int[maxStack];           // VM operand stack
 *           int[] p = PROGRAM;
 *           int pc = 0;
 *           int sp = 0;
 *           for (;;) {
 *               switch (p[pc++]) {
 *                   case PUSH:  s[sp++] = p[pc++]; break;
 *                   ...
 *                   case IRETURN: return s[--sp];
 *               }
 *               // every non-return case jumps back to the dispatch head
 *           }
 *       }
 *   }
 * </pre>
 *
 * <p>All working state lives in method-local arrays, so calls are reentrant and
 * thread-safe. The program field is only read after class initialization.
 * Arithmetic uses the corresponding JVM opcode, preserving Java's division,
 * remainder, overflow and shift-mask behavior exactly.</p>
 */
final class VirtualInterpreterEmitter {

    static final String PROGRAM_FIELD = "p";
    private static final String OBJECT = "java/lang/Object";
    private static final String ILLEGAL_STATE = "java/lang/IllegalStateException";

    private VirtualInterpreterEmitter() {
    }

    static ClassNode build(String name, MethodNode original, VirtualTranslator.Translated translated) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        node.name = name;
        node.superName = OBJECT;
        node.interfaces = new ArrayList<>();
        node.fields = new ArrayList<>();
        node.methods = new ArrayList<>();
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                PROGRAM_FIELD, "[I", null, null));
        node.methods.add(programInitializer(name, translated.program));
        node.methods.add(interpreter(name, original, translated));
        return node;
    }

    /**
     * {@code static { int[] a = new int[N]; a[0] = ...; p = a; }}.
     *
     * <p>The translator caps the program at {@link VirtualTranslator#MAX_PROGRAM_INTS}
     * so this straight-line initializer stays well below the 64 KiB method limit.</p>
     */
    private static MethodNode programInitializer(String owner, int[] program) {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "<clinit>", "()V", null, null);
        InsnList code = method.instructions;

        Insns.pushInt(code, program.length);
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        code.add(new VarInsnNode(Opcodes.ASTORE, 0));
        for (int i = 0; i < program.length; i++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            Insns.pushInt(code, i);
            Insns.pushInt(code, program[i]);
            code.add(new InsnNode(Opcodes.IASTORE));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, PROGRAM_FIELD, "[I"));
        code.add(new InsnNode(Opcodes.RETURN));

        method.maxStack = 4;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode interpreter(String owner, MethodNode original, VirtualTranslator.Translated translated) {
        Type[] arguments = Type.getArgumentTypes(original.desc);
        boolean returnsInt = Type.getReturnType(original.desc).getSort() == Type.INT;
        int argumentCount = arguments.length;

        Slots slots = new Slots(
                argumentCount,          // int[] locals
                argumentCount + 1,      // int[] stack
                argumentCount + 2,      // int[] program
                argumentCount + 3,      // int pc
                argumentCount + 4,      // int sp
                argumentCount + 5,      // scratch top
                argumentCount + 6);     // scratch second

        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                Virtualizer.RUN_METHOD, original.desc, null, null);
        InsnList code = method.instructions;

        // int[] locals = new int[maxLocals];
        Insns.pushInt(code, translated.maxLocals);
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        code.add(new VarInsnNode(Opcodes.ASTORE, slots.locals()));

        // locals[i] = arg_i;
        for (int i = 0; i < argumentCount; i++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, slots.locals()));
            Insns.pushInt(code, i);
            code.add(new VarInsnNode(Opcodes.ILOAD, i));
            code.add(new InsnNode(Opcodes.IASTORE));
        }

        // int[] stack = new int[maxStack];
        Insns.pushInt(code, translated.maxStack);
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        code.add(new VarInsnNode(Opcodes.ASTORE, slots.stack()));

        // int[] program = P;
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, PROGRAM_FIELD, "[I"));
        code.add(new VarInsnNode(Opcodes.ASTORE, slots.program()));

        // int pc = 0; int sp = 0;
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, slots.pc()));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, slots.sp()));

        LabelNode loop = new LabelNode();
        LabelNode fallback = new LabelNode();
        int[] opcodes = translated.opcodes.stream().mapToInt(Integer::intValue).sorted().toArray();
        if (opcodes.length == 0) {
            throw new IllegalStateException("empty virtual program");
        }
        int min = opcodes[0];
        int max = opcodes[opcodes.length - 1];
        if (max - min + 1 > 64) {
            throw new IllegalStateException("unexpected opcode range");
        }
        LabelNode[] table = new LabelNode[max - min + 1];
        Arrays.fill(table, fallback);
        for (int opcode : opcodes) {
            table[opcode - min] = new LabelNode();
        }

        // for (;;) switch (program[pc++]) { ... }
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
        code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
        code.add(new IincInsnNode(slots.pc(), 1));
        code.add(new InsnNode(Opcodes.IALOAD));
        code.add(new TableSwitchInsnNode(min, max, fallback, table));

        for (int opcode : opcodes) {
            code.add(table[opcode - min]);
            emitCase(code, opcode, slots, loop, returnsInt);
        }

        // Unknown opcode (corrupt/tampered program): fail loudly instead of
        // executing an arbitrary cell.
        code.add(fallback);
        code.add(new TypeInsnNode(Opcodes.NEW, ILLEGAL_STATE));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ILLEGAL_STATE, "<init>", "()V", false));
        code.add(new InsnNode(Opcodes.ATHROW));

        method.maxStack = 8;
        method.maxLocals = argumentCount + 7;
        return method;
    }

    private static void emitCase(InsnList code, int opcode, Slots slots, LabelNode loop, boolean returnsInt) {
        switch (opcode) {
            case VirtualTranslator.OP_PUSH -> {
                pushSlotStart(code, slots);
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new IincInsnNode(slots.pc(), 1));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_LOAD -> {
                pushSlotStart(code, slots);
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.locals()));
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new IincInsnNode(slots.pc(), 1));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_STORE -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.locals()));
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new IincInsnNode(slots.pc(), 1));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.stack()));
                code.add(new IincInsnNode(slots.sp(), -1));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.sp()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_IINC -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new IincInsnNode(slots.pc(), 1));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ISTORE, slots.top()));

                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new IincInsnNode(slots.pc(), 1));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ISTORE, slots.second()));

                code.add(new VarInsnNode(Opcodes.ALOAD, slots.locals()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.locals()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.second()));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_NEG -> {
                popInto(code, slots, slots.top());
                pushSlotStart(code, slots);
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new InsnNode(Opcodes.INEG));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_GOTO -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ISTORE, slots.pc()));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_IFEQ, VirtualTranslator.OP_IFNE, VirtualTranslator.OP_IFLT,
                 VirtualTranslator.OP_IFGE, VirtualTranslator.OP_IFGT, VirtualTranslator.OP_IFLE -> {
                int jvmOpcode = VirtualTranslator.jvmCondition(opcode);
                popInto(code, slots, slots.top());
                LabelNode taken = new LabelNode();
                LabelNode notTaken = new LabelNode();
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new JumpInsnNode(jvmOpcode, taken));
                code.add(new JumpInsnNode(Opcodes.GOTO, notTaken));

                code.add(taken);
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ISTORE, slots.pc()));
                jumpBack(code, loop);

                code.add(notTaken);
                code.add(new IincInsnNode(slots.pc(), 1));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_IF_ICMPEQ, VirtualTranslator.OP_IF_ICMPNE,
                 VirtualTranslator.OP_IF_ICMPLT, VirtualTranslator.OP_IF_ICMPGE,
                 VirtualTranslator.OP_IF_ICMPGT, VirtualTranslator.OP_IF_ICMPLE -> {
                int jvmOpcode = VirtualTranslator.jvmCondition(opcode);
                popInto(code, slots, slots.top());
                popInto(code, slots, slots.second());
                LabelNode taken = new LabelNode();
                LabelNode notTaken = new LabelNode();
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.second()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new JumpInsnNode(jvmOpcode, taken));
                code.add(new JumpInsnNode(Opcodes.GOTO, notTaken));

                code.add(taken);
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.program()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.pc()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new VarInsnNode(Opcodes.ISTORE, slots.pc()));
                jumpBack(code, loop);

                code.add(notTaken);
                code.add(new IincInsnNode(slots.pc(), 1));
                jumpBack(code, loop);
            }
            case VirtualTranslator.OP_IRETURN -> {
                if (!returnsInt) {
                    throw new IllegalStateException("IRETURN in void helper");
                }
                code.add(new IincInsnNode(slots.sp(), -1));
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.stack()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.sp()));
                code.add(new InsnNode(Opcodes.IALOAD));
                code.add(new InsnNode(Opcodes.IRETURN));
            }
            case VirtualTranslator.OP_RETURN -> {
                if (returnsInt) {
                    throw new IllegalStateException("RETURN in int helper");
                }
                code.add(new InsnNode(Opcodes.RETURN));
            }
            default -> {
                int binary = VirtualTranslator.jvmBinaryOpcode(opcode);
                if (binary < 0) {
                    throw new IllegalStateException("unknown vm opcode " + opcode);
                }
                popInto(code, slots, slots.top());
                popInto(code, slots, slots.second());
                pushSlotStart(code, slots);
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.second()));
                code.add(new VarInsnNode(Opcodes.ILOAD, slots.top()));
                code.add(new InsnNode(binary));
                code.add(new InsnNode(Opcodes.IASTORE));
                jumpBack(code, loop);
            }
        }
    }

    /** Leaves {@code [stack, sp]} on the operand stack and increments {@code sp}. */
    private static void pushSlotStart(InsnList code, Slots slots) {
        code.add(new VarInsnNode(Opcodes.ALOAD, slots.stack()));
        code.add(new VarInsnNode(Opcodes.ILOAD, slots.sp()));
        code.add(new IincInsnNode(slots.sp(), 1));
    }

    /** Decrements {@code sp}, reads the top VM stack value and stores it in a JVM local. */
    private static void popInto(InsnList code, Slots slots, int destination) {
        code.add(new VarInsnNode(Opcodes.ALOAD, slots.stack()));
        code.add(new IincInsnNode(slots.sp(), -1));
        code.add(new VarInsnNode(Opcodes.ILOAD, slots.sp()));
        code.add(new InsnNode(Opcodes.IALOAD));
        code.add(new VarInsnNode(Opcodes.ISTORE, destination));
    }

    private static void jumpBack(InsnList code, LabelNode loop) {
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
    }

    /** JVM local-slot layout of the generated interpreter. */
    private record Slots(int locals, int stack, int program, int pc, int sp, int top, int second) {
    }
}
