package dev.vapor.transform.impl;

import dev.vapor.testing.Reload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential tests for the virtualization prototype: transform the compiled
 * fixture, load owner + generated helpers in a private loader, and compare every
 * supported method against the original class.
 */
class VirtualizerTest {

    private static final int[] INTS = {
            Integer.MIN_VALUE, -1000000, -1000, -13, -7, -2, -1, 0, 1, 2, 3, 7, 8, 13,
            100, 255, 1000, 999983, Integer.MAX_VALUE
    };

    private static final int[] SMALL = {
            Integer.MIN_VALUE, -101, -7, -2, -1, 0, 1, 2, 3, 7, 13, 100, Integer.MAX_VALUE
    };

    private static final Set<String> EXPECTED_TRANSFORMED = Set.of(
            "constant", "minValue", "maxValue", "identity", "voidEmpty",
            "add", "sub", "mul", "div", "rem", "neg", "bitwise", "shifts",
            "three", "four",
            "loopSum", "downLoop", "gcd", "nested", "mixed", "countBits",
            "abs", "overflow", "branchy", "voidLoop", "voidDiv",
            "selectPlus", "compare", "compareZero");

    private static final Set<String> REJECTED = Set.of(
            "<init>", "<clinit>", "withField", "withCall", "withTryCatch", "withSwitch",
            "withLong", "withArray", "withCast", "withNew", "withDup", "instanceAdd", "withFloat");

    @Test
    void behaviorIsPreserved() throws Exception {
        ClassNode transformed = Reload.read(VirtualFixture.class);
        Virtualizer virtualizer = new Virtualizer(0x5EED);
        Map<String, ClassNode> helpers = new LinkedHashMap<>();
        Set<String> changed = new LinkedHashSet<>();

        for (MethodNode method : new ArrayList<>(transformed.methods)) {
            Virtualizer.Result result = virtualizer.transform(transformed, method);
            if (!result.transformed()) {
                assertNotNull(result.skipReason(), method.name);
                continue;
            }
            changed.add(method.name);
            assertNotNull(result.program(), method.name);
            assertTrue(result.programLength() > 0, method.name);
            assertEquals(method.desc, result.descriptor(), method.name);
            assertEquals(result.helperName(), result.helper().name, method.name);
            assertEquals(1, helperCalls(method, result), method.name);
            helpers.put(result.helperName(), result.helper());
        }

        assertEquals(EXPECTED_TRANSFORMED, changed, "transformed method set");
        for (String rejected : REJECTED) {
            assertFalse(changed.contains(rejected), rejected + " must be skipped");
        }

        Class<?> fixture = load(transformed, helpers);
        for (int value : INTS) {
            assertEquals(VirtualFixture.constant(), invokeInt(fixture, "constant"));
            assertEquals(VirtualFixture.minValue(), invokeInt(fixture, "minValue"));
            assertEquals(VirtualFixture.maxValue(), invokeInt(fixture, "maxValue"));
            assertEquals(VirtualFixture.identity(value), invokeInt(fixture, "identity", value));
            assertEquals(VirtualFixture.three(value, -13, 7), invokeInt(fixture, "three", value, -13, 7));
            assertEquals(VirtualFixture.four(value, -13, 7, 1000),
                    invokeInt(fixture, "four", value, -13, 7, 1000));
            assertEquals(VirtualFixture.neg(value), invokeInt(fixture, "neg", value));
            assertEquals(VirtualFixture.abs(value), invokeInt(fixture, "abs", value));
            assertEquals(VirtualFixture.countBits(value), invokeInt(fixture, "countBits", value));
            assertEquals(VirtualFixture.branchy(value), invokeInt(fixture, "branchy", value));
            if (value != Integer.MIN_VALUE) {
                // downLoop subtracts 3; MIN_VALUE would overflow and loop forever
                // in both the original and virtualized method.
                assertEquals(VirtualFixture.downLoop(value), invokeInt(fixture, "downLoop", value));
            }
            assertEquals(VirtualFixture.compareZero(value), invokeInt(fixture, "compareZero", value));
            assertEquals(VirtualFixture.bitwise(value, -7), invokeInt(fixture, "bitwise", value, -7));

            assertEquals(VirtualFixture.shifts(value, -33), invokeInt(fixture, "shifts", value, -33));
            assertEquals(VirtualFixture.shifts(value, 37), invokeInt(fixture, "shifts", value, 37));
            assertEquals(VirtualFixture.shifts(value, value), invokeInt(fixture, "shifts", value, value));

            int loops = value & 0x3FF;
            assertEquals(VirtualFixture.loopSum(loops), invokeInt(fixture, "loopSum", loops));
            assertEquals(VirtualFixture.loopSum(-5), invokeInt(fixture, "loopSum", -5));

            invokeVoid(fixture, "voidEmpty");
            invokeVoid(fixture, "voidLoop", loops);
        }

        for (int a : INTS) {
            for (int b : INTS) {
                assertEquals(VirtualFixture.add(a, b), invokeInt(fixture, "add", a, b), "add");
                assertEquals(VirtualFixture.sub(a, b), invokeInt(fixture, "sub", a, b), "sub");
                assertEquals(VirtualFixture.mul(a, b), invokeInt(fixture, "mul", a, b), "mul");
                assertEquals(VirtualFixture.bitwise(a, b), invokeInt(fixture, "bitwise", a, b), "bitwise");
                assertEquals(VirtualFixture.shifts(a, b), invokeInt(fixture, "shifts", a, b), "shifts");
                assertEquals(VirtualFixture.gcd(a, b), invokeInt(fixture, "gcd", a, b), "gcd");
                assertEquals(VirtualFixture.mixed(a, b), invokeInt(fixture, "mixed", a, b), "mixed");
                assertEquals(VirtualFixture.overflow(a, b), invokeInt(fixture, "overflow", a, b), "overflow");
                assertEquals(VirtualFixture.selectPlus(a, b), invokeInt(fixture, "selectPlus", a, b), "selectPlus");
                assertEquals(VirtualFixture.compare(a, b), invokeInt(fixture, "compare", a, b), "compare");
                assertEquals(VirtualFixture.nested(a & 15, b & 15),
                        invokeInt(fixture, "nested", a & 15, b & 15), "nested");

                if (b != 0) {
                    assertEquals(VirtualFixture.div(a, b), invokeInt(fixture, "div", a, b), "div");
                    assertEquals(VirtualFixture.rem(a, b), invokeInt(fixture, "rem", a, b), "rem");
                } else {
                    assertThrows(ArithmeticException.class, () -> VirtualFixture.div(a, b));
                    assertThrows(ArithmeticException.class, () -> VirtualFixture.rem(a, b));
                    assertVirtualArithmetic(fixture, "div", a, b);
                    assertVirtualArithmetic(fixture, "rem", a, b);
                }
            }
        }

        for (int value : SMALL) {
            assertThrows(ArithmeticException.class, () -> VirtualFixture.voidDiv(value, 0));
            assertVirtualArithmetic(fixture, "voidDiv", value, 0);
            invokeVoid(fixture, "voidDiv", value, 1);
        }

        // MIN_VALUE / -1 and MIN_VALUE % -1 are the overflow corners.
        assertEquals(Integer.MIN_VALUE, invokeInt(fixture, "div", Integer.MIN_VALUE, -1));
        assertEquals(0, invokeInt(fixture, "rem", Integer.MIN_VALUE, -1));
        assertEquals(Integer.MIN_VALUE, invokeInt(fixture, "abs", Integer.MIN_VALUE));
        assertEquals(Integer.MIN_VALUE, invokeInt(fixture, "neg", Integer.MIN_VALUE));
    }

    @Test
    void outputIsDeterministic() throws Exception {
        ClassNode first = Reload.read(VirtualFixture.class);
        ClassNode second = Reload.read(VirtualFixture.class);
        List<ClassNode> firstHelpers = transformAll(first, 0x1234ABCDL);
        List<ClassNode> secondHelpers = transformAll(second, 0x1234ABCDL);

        assertArrayEquals(Reload.write(first), Reload.write(second),
                "same input and seed must produce identical owner bytes");
        assertEquals(firstHelpers.size(), secondHelpers.size());
        for (int i = 0; i < firstHelpers.size(); i++) {
            assertEquals(firstHelpers.get(i).name, secondHelpers.get(i).name);
            assertArrayEquals(Reload.write(firstHelpers.get(i)), Reload.write(secondHelpers.get(i)),
                    "helper " + i + " must be deterministic");
        }
    }

    @Test
    void unsupportedMethodsAreLeftUntouched() throws Exception {
        ClassNode node = Reload.read(VirtualFixture.class);
        Virtualizer virtualizer = new Virtualizer(7);
        for (String name : REJECTED) {
            MethodNode method = find(node, name);
            AbstractInsnNode first = method.instructions.getFirst();
            int size = method.instructions.size();
            Virtualizer.Result result = virtualizer.transform(node, method);
            assertFalse(result.transformed(), name);
            assertNotNull(result.skipReason(), name);
            assertNull(result.helper(), name);
            assertSame(first, method.instructions.getFirst(), name + " instruction list changed");
            assertEquals(size, method.instructions.size(), name + " instruction count changed");
        }

        assertTrue(Virtualizer.isEligible(node, find(node, "withDup")),
                "DUP passes the cheap type checks and must be rejected by translation");
        assertFalse(Virtualizer.isEligible(node, find(node, "withLong")));
        assertFalse(Virtualizer.isEligible(node, find(node, "instanceAdd")));
    }

    @Test
    void programEncodingIsWellFormed() throws Exception {
        ClassNode node = Reload.read(VirtualFixture.class);
        Virtualizer virtualizer = new Virtualizer(0xBEEF);
        int checked = 0;

        for (MethodNode method : new ArrayList<>(node.methods)) {
            Virtualizer.Result result = virtualizer.transform(node, method);
            if (!result.transformed()) {
                continue;
            }
            checked++;
            int[] program = result.program();
            assertNotNull(program);
            boolean[] starts = new boolean[program.length];
            List<Integer> targetOperands = new ArrayList<>();
            int pc = 0;

            while (pc < program.length) {
                starts[pc] = true;
                int opcode = program[pc++];
                switch (opcode) {
                    case VirtualTranslator.OP_PUSH, VirtualTranslator.OP_LOAD,
                         VirtualTranslator.OP_STORE -> {
                        assertTrue(pc + 1 <= program.length, method.name + ": truncated operand");
                        pc += 1;
                    }
                    case VirtualTranslator.OP_IINC -> {
                        assertTrue(pc + 2 <= program.length, method.name + ": truncated IINC");
                        pc += 2;
                    }
                    case VirtualTranslator.OP_IFEQ, VirtualTranslator.OP_IFNE,
                         VirtualTranslator.OP_IFLT, VirtualTranslator.OP_IFGE,
                         VirtualTranslator.OP_IFGT, VirtualTranslator.OP_IFLE,
                         VirtualTranslator.OP_IF_ICMPEQ, VirtualTranslator.OP_IF_ICMPNE,
                         VirtualTranslator.OP_IF_ICMPLT, VirtualTranslator.OP_IF_ICMPGE,
                         VirtualTranslator.OP_IF_ICMPGT, VirtualTranslator.OP_IF_ICMPLE,
                         VirtualTranslator.OP_GOTO -> {
                        assertTrue(pc + 1 <= program.length, method.name + ": truncated target");
                        targetOperands.add(pc);
                        pc += 1;
                    }
                    case VirtualTranslator.OP_ADD, VirtualTranslator.OP_SUB,
                         VirtualTranslator.OP_MUL, VirtualTranslator.OP_DIV,
                         VirtualTranslator.OP_REM, VirtualTranslator.OP_NEG,
                         VirtualTranslator.OP_AND, VirtualTranslator.OP_OR,
                         VirtualTranslator.OP_XOR, VirtualTranslator.OP_SHL,
                         VirtualTranslator.OP_SHR, VirtualTranslator.OP_USHR,
                         VirtualTranslator.OP_IRETURN, VirtualTranslator.OP_RETURN -> {
                        // no operands
                    }
                    default -> fail(method.name + ": unknown opcode " + opcode);
                }
            }
            assertEquals(program.length, pc, method.name + ": trailing bytes");

            for (int operand : targetOperands) {
                int target = program[operand];
                assertTrue(target >= 0 && target < program.length,
                        method.name + ": target out of range " + target);
                assertTrue(starts[target], method.name + ": target is not an instruction start " + target);
            }
        }
        assertTrue(checked >= EXPECTED_TRANSFORMED.size());
    }

    @Test
    void helperBytesAreSelfContained() throws Exception {
        ClassNode node = Reload.read(VirtualFixture.class);
        Virtualizer.Result result = new Virtualizer(0x99).transform(node, find(node, "add"));
        assertTrue(result.transformed());
        byte[] helperBytes = result.helperBytes();
        assertNotNull(helperBytes);
        assertEquals(52, ((helperBytes[6] & 0xFF) << 8) | (helperBytes[7] & 0xFF),
                "helper must remain a Java 8 class file (no version patching)");

        Map<String, byte[]> definitions = new LinkedHashMap<>();
        definitions.put(binaryName(node.name), Reload.write(node));
        definitions.put(binaryName(result.helperName()), helperBytes);
        Class<?> fixture = new VirtualClassLoader(definitions).loadClass(binaryName(node.name));
        assertEquals(42, invokeInt(fixture, "add", 40, 2));
        assertEquals(-7, invokeInt(fixture, "add", 3, -10));
    }

    @Test
    void handBuiltLoopIsVirtualized() throws Exception {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V1_8;
        owner.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        owner.name = "dev/vapor/transform/impl/HandBuiltFixture";
        owner.superName = "java/lang/Object";

        MethodNode sum = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "sum", "(I)I", null, null);
        LabelNode head = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList code = sum.instructions;
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(head);
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new IincInsnNode(2, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, head));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new InsnNode(Opcodes.IRETURN));
        // Deliberately under-reported: the translator must compute these itself.
        sum.maxStack = 0;
        sum.maxLocals = 0;
        owner.methods.add(sum);

        MethodNode dup = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dup", "(I)I", null, null);
        dup.instructions.add(new InsnNode(Opcodes.ICONST_1));
        dup.instructions.add(new InsnNode(Opcodes.DUP));
        dup.instructions.add(new InsnNode(Opcodes.IADD));
        dup.instructions.add(new InsnNode(Opcodes.IRETURN));
        dup.maxStack = 2;
        dup.maxLocals = 1;
        owner.methods.add(dup);

        Virtualizer virtualizer = new Virtualizer(13);
        Virtualizer.Result sumResult = virtualizer.transform(owner, sum);
        assertTrue(sumResult.transformed());
        assertEquals(2, sumResult.maxStack());
        assertEquals(3, sumResult.maxLocals());

        AbstractInsnNode dupFirst = dup.instructions.getFirst();
        Virtualizer.Result dupResult = virtualizer.transform(owner, dup);
        assertFalse(dupResult.transformed());
        assertSame(dupFirst, dup.instructions.getFirst());

        Map<String, byte[]> definitions = new LinkedHashMap<>();
        definitions.put(binaryName(owner.name), Reload.write(owner));
        definitions.put(binaryName(sumResult.helperName()), Reload.write(sumResult.helper()));
        Class<?> fixture = new VirtualClassLoader(definitions).loadClass(binaryName(owner.name));
        assertEquals(45, invokeInt(fixture, "sum", 10));
        assertEquals(0, invokeInt(fixture, "sum", 0));
        assertEquals(0, invokeInt(fixture, "sum", -3));
        assertEquals(4950, invokeInt(fixture, "sum", 100));
    }

    private static List<ClassNode> transformAll(ClassNode node, long seed) {
        Virtualizer virtualizer = new Virtualizer(seed);
        List<ClassNode> helpers = new ArrayList<>();
        for (MethodNode method : new ArrayList<>(node.methods)) {
            Virtualizer.Result result = virtualizer.transform(node, method);
            if (result.transformed()) {
                helpers.add(result.helper());
            }
        }
        return helpers;
    }

    private static Class<?> load(ClassNode owner, Map<String, ClassNode> helpers) throws Exception {
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        definitions.put(binaryName(owner.name), Reload.write(owner));
        for (Map.Entry<String, ClassNode> entry : helpers.entrySet()) {
            definitions.put(binaryName(entry.getKey()), Reload.write(entry.getValue()));
        }
        return new VirtualClassLoader(definitions).loadClass(binaryName(owner.name));
    }

    private static int helperCalls(MethodNode method, Virtualizer.Result result) {
        int calls = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(result.helperName())
                    && call.name.equals(result.helperRunName())
                    && call.desc.equals(method.desc)) {
                calls++;
            }
        }
        return calls;
    }

    private static MethodNode find(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException("missing method " + name);
    }

    private static int invokeInt(Class<?> fixture, String name, int... arguments) throws Exception {
        return (Integer) method(fixture, name, arguments.length).invoke(null, box(arguments));
    }

    private static void invokeVoid(Class<?> fixture, String name, int... arguments) throws Exception {
        method(fixture, name, arguments.length).invoke(null, box(arguments));
    }

    private static Method method(Class<?> fixture, String name, int argumentCount) throws NoSuchMethodException {
        Class<?>[] types = new Class<?>[argumentCount];
        Arrays.fill(types, int.class);
        return fixture.getMethod(name, types);
    }

    private static Object[] box(int[] arguments) {
        Object[] boxed = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            boxed[i] = arguments[i];
        }
        return boxed;
    }

    private static void assertVirtualArithmetic(Class<?> fixture, String name, int... arguments) {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method(fixture, name, arguments.length).invoke(null, box(arguments)), name);
        assertInstanceOf(ArithmeticException.class, thrown.getCause(), name);
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }
}
