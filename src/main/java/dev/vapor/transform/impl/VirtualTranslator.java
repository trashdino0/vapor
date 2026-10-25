package dev.vapor.transform.impl;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the supported JVM subset into the compact {@code int[]} program
 * consumed by {@link VirtualInterpreterEmitter}.
 *
 * <h2>Program encoding</h2>
 *
 * <p>The program is a flat array of 32-bit cells. Every instruction starts with
 * an opcode cell; operands follow in the next cells. Branch targets are
 * absolute indexes into the same array and always point at an opcode cell.</p>
 *
 * <pre>
 *   PUSH v          [OP_PUSH, v]
 *   LOAD s          [OP_LOAD, s]
 *   STORE s         [OP_STORE, s]
 *   IINC s d        [OP_IINC, s, d]
 *   ADD             [OP_ADD]
 *   SUB             [OP_SUB]
 *   MUL             [OP_MUL]
 *   DIV             [OP_DIV]
 *   REM             [OP_REM]
 *   NEG             [OP_NEG]
 *   AND             [OP_AND]
 *   OR              [OP_OR]
 *   XOR             [OP_XOR]
 *   SHL             [OP_SHL]
 *   SHR             [OP_SHR]
 *   USHR            [OP_USHR]
 *   IFEQ t          [OP_IFEQ, t]
 *   IFNE t          [OP_IFNE, t]
 *   IFLT t          [OP_IFLT, t]
 *   IFGE t          [OP_IFGE, t]
 *   IFGT t          [OP_IFGT, t]
 *   IFLE t          [OP_IFLE, t]
 *   IF_ICMPEQ t     [OP_IF_ICMPEQ, t]
 *   IF_ICMPNE t     [OP_IF_ICMPNE, t]
 *   IF_ICMPLT t     [OP_IF_ICMPLT, t]
 *   IF_ICMPGE t     [OP_IF_ICMPGE, t]
 *   IF_ICMPGT t     [OP_IF_ICMPGT, t]
 *   IF_ICMPLE t     [OP_IF_ICMPLE, t]
 *   GOTO t          [OP_GOTO, t]
 *   IRETURN         [OP_IRETURN]
 *   RETURN          [OP_RETURN]
 * </pre>
 *
 * <p>The value model is a single signed 32-bit {@code int} stack. Binary
 * operations pop the top value ({@code b}), then the next one ({@code a}), and
 * push {@code a op b}; this is the JVM operand order. The interpreter executes
 * the real JVM arithmetic opcodes (including {@code IDIV}/{@code IREM}/{@code ISHL}
 * and friends), so division by zero, overflow and shift masking keep their exact
 * Java semantics.</p>
 */
final class VirtualTranslator {

    /** Helper {@code <clinit>} initializes the program with one array store per cell. */
    static final int MAX_PROGRAM_INTS = 4096;
    static final int MAX_LOCALS = 8192;
    static final int MAX_STACK = 8192;

    // Encoding opcodes. 0 is reserved so an uninitialized/corrupt cell can never
    // silently behave as a valid instruction.
    static final int OP_PUSH = 1;
    static final int OP_LOAD = 2;
    static final int OP_STORE = 3;
    static final int OP_IINC = 4;
    static final int OP_ADD = 5;
    static final int OP_SUB = 6;
    static final int OP_MUL = 7;
    static final int OP_DIV = 8;
    static final int OP_REM = 9;
    static final int OP_NEG = 10;
    static final int OP_AND = 11;
    static final int OP_OR = 12;
    static final int OP_XOR = 13;
    static final int OP_SHL = 14;
    static final int OP_SHR = 15;
    static final int OP_USHR = 16;
    static final int OP_IFEQ = 17;
    static final int OP_IFNE = 18;
    static final int OP_IFLT = 19;
    static final int OP_IFGE = 20;
    static final int OP_IFGT = 21;
    static final int OP_IFLE = 22;
    static final int OP_IF_ICMPEQ = 23;
    static final int OP_IF_ICMPNE = 24;
    static final int OP_IF_ICMPLT = 25;
    static final int OP_IF_ICMPGE = 26;
    static final int OP_IF_ICMPGT = 27;
    static final int OP_IF_ICMPLE = 28;
    static final int OP_GOTO = 29;
    static final int OP_IRETURN = 30;
    static final int OP_RETURN = 31;

    private VirtualTranslator() {
    }

    /** @return a human-readable skip reason, or {@code null} when the types look eligible. */
    static String ineligibility(ClassNode owner, MethodNode method) {
        if (owner == null || owner.name == null) {
            return "missing owner";
        }
        if (method == null) {
            return "missing method";
        }
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            return "not static";
        }
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return "abstract or native";
        }
        if (method.name == null || method.name.startsWith("<")) {
            return "initializer";
        }
        if (method.desc == null) {
            return "missing descriptor";
        }
        final Type returnType;
        final Type[] arguments;
        try {
            returnType = Type.getReturnType(method.desc);
            arguments = Type.getArgumentTypes(method.desc);
        } catch (RuntimeException e) {
            return "malformed descriptor";
        }
        int returnSort = returnType.getSort();
        if (returnSort != Type.INT && returnSort != Type.VOID) {
            return "return type " + returnType;
        }
        for (Type argument : arguments) {
            if (argument.getSort() != Type.INT) {
                return "argument type " + argument;
            }
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return "try/catch";
        }
        if (method.instructions == null || method.instructions.size() == 0) {
            return "no instructions";
        }
        return null;
    }

    /**
     * Validates and translates one method body. The caller's method is not
     * touched; on failure {@link Unsupported} explains why.
     */
    static Translated translate(MethodNode method) throws Unsupported {
        List<AbstractInsnNode> real = new ArrayList<>();
        Map<LabelNode, Integer> labelToReal = new IdentityHashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                if (insn instanceof LabelNode label) {
                    labelToReal.put(label, real.size());
                }
                continue;
            }
            checkSupported(insn);
            real.add(insn);
        }

        int count = real.size();
        if (count == 0) {
            throw new Unsupported("no real instructions");
        }

        // Stack-depth dataflow. Merging paths must agree exactly; the original
        // verifier already guarantees this for valid input, but checking keeps
        // malformed/generated input from producing a broken helper.
        int[] depth = new int[count];
        Arrays.fill(depth, -1);
        int[] work = new int[count];
        int head = 0;
        int tail = 0;
        depth[0] = 0;
        work[tail++] = 0;
        int maxStack = 0;

        while (head < tail) {
            int index = work[head++];
            AbstractInsnNode insn = real.get(index);
            int opcode = insn.getOpcode();
            int before = depth[index];
            int after = before + stackDelta(insn);
            if (after < 0) {
                throw new Unsupported("stack underflow at instruction " + index);
            }
            if (before > MAX_STACK || after > MAX_STACK) {
                throw new Unsupported("stack larger than " + MAX_STACK);
            }
            if (before > maxStack) {
                maxStack = before;
            }
            if (after > maxStack) {
                maxStack = after;
            }
            if (opcode == Opcodes.IRETURN && before != 1) {
                throw new Unsupported("IRETURN with stack depth " + before);
            }
            if (opcode == Opcodes.RETURN && before != 0) {
                throw new Unsupported("RETURN with stack depth " + before);
            }

            if (opcode == Opcodes.GOTO) {
                tail = merge(depth, work, tail, targetOf(insn, labelToReal), after, count);
            } else if (isConditional(opcode)) {
                tail = merge(depth, work, tail, targetOf(insn, labelToReal), after, count);
                tail = merge(depth, work, tail, index + 1, after, count);
            } else if (opcode != Opcodes.IRETURN && opcode != Opcodes.RETURN) {
                tail = merge(depth, work, tail, index + 1, after, count);
            }
        }

        Type returnType = Type.getReturnType(method.desc);
        boolean returnsInt = returnType.getSort() == Type.INT;
        for (AbstractInsnNode insn : real) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.IRETURN && !returnsInt) {
                throw new Unsupported("IRETURN in void method");
            }
            if (opcode == Opcodes.RETURN && returnsInt) {
                throw new Unsupported("RETURN in int method");
            }
        }

        int maxLocals = Type.getArgumentTypes(method.desc).length;
        for (AbstractInsnNode insn : real) {
            if (insn instanceof VarInsnNode variable) {
                maxLocals = Math.max(maxLocals, variable.var + 1);
            } else if (insn instanceof IincInsnNode increment) {
                maxLocals = Math.max(maxLocals, increment.var + 1);
            }
        }
        if (maxLocals > MAX_LOCALS) {
            throw new Unsupported("more than " + MAX_LOCALS + " locals");
        }

        Program program = new Program();
        int[] pcOfReal = new int[count];
        List<Fixup> fixups = new ArrayList<>();
        Set<Integer> opcodes = new HashSet<>();

        for (int index = 0; index < count; index++) {
            AbstractInsnNode insn = real.get(index);
            int opcode = insn.getOpcode();
            pcOfReal[index] = program.size();

            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                emit(program, opcodes, OP_PUSH);
                program.add(opcode - Opcodes.ICONST_0);
            } else if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                emit(program, opcodes, OP_PUSH);
                program.add(((IntInsnNode) insn).operand);
            } else if (opcode == Opcodes.LDC) {
                emit(program, opcodes, OP_PUSH);
                program.add((Integer) ((LdcInsnNode) insn).cst);
            } else if (opcode == Opcodes.ILOAD) {
                emit(program, opcodes, OP_LOAD);
                program.add(((VarInsnNode) insn).var);
            } else if (opcode == Opcodes.ISTORE) {
                emit(program, opcodes, OP_STORE);
                program.add(((VarInsnNode) insn).var);
            } else if (opcode == Opcodes.IINC) {
                IincInsnNode increment = (IincInsnNode) insn;
                emit(program, opcodes, OP_IINC);
                program.add(increment.var);
                program.add(increment.incr);
            } else if (opcode == Opcodes.INEG) {
                emit(program, opcodes, OP_NEG);
            } else if (opcode == Opcodes.GOTO) {
                emit(program, opcodes, OP_GOTO);
                fixups.add(new Fixup(program.size(), targetOf(insn, labelToReal)));
                program.add(0);
            } else if (isConditional(opcode)) {
                emit(program, opcodes, vmCondition(opcode));
                fixups.add(new Fixup(program.size(), targetOf(insn, labelToReal)));
                program.add(0);
            } else if (opcode == Opcodes.IRETURN) {
                emit(program, opcodes, OP_IRETURN);
            } else if (opcode == Opcodes.RETURN) {
                emit(program, opcodes, OP_RETURN);
            } else {
                int binary = vmBinary(opcode);
                if (binary < 0) {
                    throw new Unsupported("unsupported opcode " + opcode);
                }
                emit(program, opcodes, binary);
            }

            if (program.size() > MAX_PROGRAM_INTS) {
                throw new Unsupported("program larger than " + MAX_PROGRAM_INTS + " cells");
            }
        }

        for (Fixup fixup : fixups) {
            if (fixup.labelReal < 0 || fixup.labelReal >= count) {
                throw new Unsupported("branch target at end of method");
            }
            program.set(fixup.operand, pcOfReal[fixup.labelReal]);
        }

        return new Translated(program.toArray(), maxStack, maxLocals, Set.copyOf(opcodes));
    }

    private static int merge(int[] depth, int[] work, int tail, int index, int value, int count)
            throws Unsupported {
        if (index < 0 || index >= count) {
            throw new Unsupported("control falls off the end of the method");
        }
        if (depth[index] < 0) {
            depth[index] = value;
            work[tail++] = index;
            return tail;
        }
        if (depth[index] != value) {
            throw new Unsupported("inconsistent stack depth at instruction " + index);
        }
        return tail;
    }

    private static int targetOf(AbstractInsnNode insn, Map<LabelNode, Integer> labelToReal) throws Unsupported {
        if (!(insn instanceof JumpInsnNode jump)) {
            throw new Unsupported("branch without a jump node");
        }
        Integer target = labelToReal.get(jump.label);
        if (target == null) {
            throw new Unsupported("branch target label is not in the method");
        }
        return target;
    }

    private static void emit(Program program, Set<Integer> opcodes, int opcode) {
        program.add(opcode);
        opcodes.add(opcode);
    }

    private static int stackDelta(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return 1;
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH || opcode == Opcodes.LDC
                || opcode == Opcodes.ILOAD) {
            return 1;
        }
        if (opcode == Opcodes.ISTORE || opcode == Opcodes.IRETURN) {
            return -1;
        }
        if (opcode == Opcodes.IINC || opcode == Opcodes.INEG || opcode == Opcodes.GOTO
                || opcode == Opcodes.RETURN) {
            return 0;
        }
        if (isConditional(opcode)) {
            return opcode <= Opcodes.IFLE ? -1 : -2;
        }
        if (vmBinary(opcode) >= 0) {
            return -1;
        }
        throw new IllegalArgumentException("unchecked opcode " + opcode);
    }

    private static void checkSupported(AbstractInsnNode insn) throws Unsupported {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return;
        }
        switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                if (!(insn instanceof IntInsnNode)) {
                    throw new Unsupported("malformed int push");
                }
            }
            case Opcodes.LDC -> {
                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Integer)) {
                    throw new Unsupported("LDC is not an int constant");
                }
            }
            case Opcodes.ILOAD, Opcodes.ISTORE -> {
                if (!(insn instanceof VarInsnNode)) {
                    throw new Unsupported("malformed local access");
                }
            }
            case Opcodes.IINC -> {
                if (!(insn instanceof IincInsnNode)) {
                    throw new Unsupported("malformed IINC");
                }
            }
            case Opcodes.INEG, Opcodes.IRETURN, Opcodes.RETURN -> {
                // no operands
            }
            case Opcodes.GOTO -> {
                if (!(insn instanceof JumpInsnNode)) {
                    throw new Unsupported("malformed GOTO");
                }
            }
            default -> {
                if (isConditional(opcode)) {
                    if (!(insn instanceof JumpInsnNode)) {
                        throw new Unsupported("malformed conditional branch");
                    }
                    return;
                }
                if (vmBinary(opcode) < 0) {
                    throw new Unsupported("unsupported opcode " + opcode);
                }
            }
        }
    }

    private static boolean isConditional(int opcode) {
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE)
                || (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE);
    }

    private static int vmBinary(int jvmOpcode) {
        return switch (jvmOpcode) {
            case Opcodes.IADD -> OP_ADD;
            case Opcodes.ISUB -> OP_SUB;
            case Opcodes.IMUL -> OP_MUL;
            case Opcodes.IDIV -> OP_DIV;
            case Opcodes.IREM -> OP_REM;
            case Opcodes.IAND -> OP_AND;
            case Opcodes.IOR -> OP_OR;
            case Opcodes.IXOR -> OP_XOR;
            case Opcodes.ISHL -> OP_SHL;
            case Opcodes.ISHR -> OP_SHR;
            case Opcodes.IUSHR -> OP_USHR;
            default -> -1;
        };
    }

    private static int vmCondition(int jvmOpcode) {
        return switch (jvmOpcode) {
            case Opcodes.IFEQ -> OP_IFEQ;
            case Opcodes.IFNE -> OP_IFNE;
            case Opcodes.IFLT -> OP_IFLT;
            case Opcodes.IFGE -> OP_IFGE;
            case Opcodes.IFGT -> OP_IFGT;
            case Opcodes.IFLE -> OP_IFLE;
            case Opcodes.IF_ICMPEQ -> OP_IF_ICMPEQ;
            case Opcodes.IF_ICMPNE -> OP_IF_ICMPNE;
            case Opcodes.IF_ICMPLT -> OP_IF_ICMPLT;
            case Opcodes.IF_ICMPGE -> OP_IF_ICMPGE;
            case Opcodes.IF_ICMPGT -> OP_IF_ICMPGT;
            case Opcodes.IF_ICMPLE -> OP_IF_ICMPLE;
            default -> -1;
        };
    }

    /** Reverse map used by the interpreter emitter. */
    static int jvmBinaryOpcode(int vmOpcode) {
        return switch (vmOpcode) {
            case OP_ADD -> Opcodes.IADD;
            case OP_SUB -> Opcodes.ISUB;
            case OP_MUL -> Opcodes.IMUL;
            case OP_DIV -> Opcodes.IDIV;
            case OP_REM -> Opcodes.IREM;
            case OP_AND -> Opcodes.IAND;
            case OP_OR -> Opcodes.IOR;
            case OP_XOR -> Opcodes.IXOR;
            case OP_SHL -> Opcodes.ISHL;
            case OP_SHR -> Opcodes.ISHR;
            case OP_USHR -> Opcodes.IUSHR;
            default -> -1;
        };
    }

    /** Reverse map used by the interpreter emitter. */
    static int jvmCondition(int vmOpcode) {
        return switch (vmOpcode) {
            case OP_IFEQ -> Opcodes.IFEQ;
            case OP_IFNE -> Opcodes.IFNE;
            case OP_IFLT -> Opcodes.IFLT;
            case OP_IFGE -> Opcodes.IFGE;
            case OP_IFGT -> Opcodes.IFGT;
            case OP_IFLE -> Opcodes.IFLE;
            case OP_IF_ICMPEQ -> Opcodes.IF_ICMPEQ;
            case OP_IF_ICMPNE -> Opcodes.IF_ICMPNE;
            case OP_IF_ICMPLT -> Opcodes.IF_ICMPLT;
            case OP_IF_ICMPGE -> Opcodes.IF_ICMPGE;
            case OP_IF_ICMPGT -> Opcodes.IF_ICMPGT;
            case OP_IF_ICMPLE -> Opcodes.IF_ICMPLE;
            default -> -1;
        };
    }

    static final class Translated {
        final int[] program;
        final int maxStack;
        final int maxLocals;
        final Set<Integer> opcodes;

        Translated(int[] program, int maxStack, int maxLocals, Set<Integer> opcodes) {
            this.program = program;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
            this.opcodes = opcodes;
        }
    }

    static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    private record Fixup(int operand, int labelReal) {
    }

    /** Growable int buffer used only during translation. */
    private static final class Program {
        private int[] cells = new int[64];
        private int size;

        void add(int value) {
            ensure(1);
            cells[size++] = value;
        }

        int size() {
            return size;
        }

        void set(int index, int value) {
            cells[index] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(cells, size);
        }

        private void ensure(int extra) {
            if (size + extra > cells.length) {
                cells = Arrays.copyOf(cells, Math.max(cells.length * 2, size + extra));
            }
        }
    }
}
