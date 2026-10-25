package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.testing.CtorBase;
import dev.vapor.testing.CtorFixture;
import dev.vapor.testing.Reload;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constructor flattening is only attempted when the pre-super prefix is provably
 * straight-line and has exactly one this/super {@code <init>} call. These tests
 * check the prefix stays byte-identical, compare constructed state, and verify an
 * exception thrown by the superclass happens at the same point.
 */
class FlowFlattenConstructorTest {

    @Test
    void constructorOutputIsDeterministic() throws Exception {
        ClassNode first = Reload.read(CtorFixture.class);
        ClassNode second = Reload.read(CtorFixture.class);
        flattenAll(first);
        flattenAll(second);
        assertArrayEquals(Reload.write(first), Reload.write(second),
                "same input + seed must produce identical bytes");
    }

    private static final String INT_CTOR = "(I)V";
    private static final String LOOP_CTOR = "(IJ)V";
    private static final String DELEGATING_CTOR = "(IZ)V";
    private static final String HELPER_ARG_CTOR = "(IC)V";
    private static final String SYNC_CTOR = "(ID)V";
    private static final String UNPROVABLE_CTOR = "(ILjava/lang/String;)V";

    @Test
    void provableConstructorsAreFlattened() throws Exception {
        ClassNode original = Reload.read(CtorFixture.class);
        ClassNode transformed = Reload.read(CtorFixture.class);
        flattenAll(transformed);

        for (String desc : new String[] {INT_CTOR, LOOP_CTOR, DELEGATING_CTOR, HELPER_ARG_CTOR, SYNC_CTOR}) {
            assertTrue(constructorSwitches(transformed, desc) > constructorSwitches(original, desc),
                    "<init>" + desc + " must be flattened after the this/super call");
            assertEquals(prefix(original, desc), prefix(transformed, desc),
                    "<init>" + desc + " pre-super prefix must stay untouched");
        }
        assertEquals(0, constructorSwitches(transformed, UNPROVABLE_CTOR),
                "a branch in the pre-super prefix must keep the constructor skipped");
        assertEquals(0, constructorSwitches(original, UNPROVABLE_CTOR));
    }

    @Test
    void constructorBehaviorAndSuperExceptionsArePreserved() throws Exception {
        ClassNode transformed = Reload.read(CtorFixture.class);
        flattenAll(transformed);
        Class<?> fixture = Reload.define(CtorFixture.class, Reload.write(transformed));

        int[] values = {
                Integer.MIN_VALUE, -1001, -1000, -101, -13, -1, 0, 1, 2, 4, 7, 100, Integer.MAX_VALUE
        };
        for (int v : values) {
            assertConstructed(CtorFixture.class, fixture, new Class<?>[] {int.class}, new Object[] {v});
            for (long salt : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, 99L, Long.MAX_VALUE}) {
                assertConstructed(CtorFixture.class, fixture,
                        new Class<?>[] {int.class, long.class}, new Object[] {v, salt});
            }
            for (boolean flag : new boolean[] {true, false}) {
                assertConstructed(CtorFixture.class, fixture,
                        new Class<?>[] {int.class, boolean.class}, new Object[] {v, flag});
            }
            for (char c : new char[] {'a', '\u00e9'}) {
                assertConstructed(CtorFixture.class, fixture,
                        new Class<?>[] {int.class, char.class}, new Object[] {v, c});
            }
            for (double d : new double[] {-0.0d, 0.5d, 1.0d, 99.25d}) {
                assertConstructed(CtorFixture.class, fixture,
                        new Class<?>[] {int.class, double.class}, new Object[] {v, d});
            }
        }
        for (String tag : new String[] {null, "", "tag"}) {
            for (int v : new int[] {-13, 0, 7}) {
                assertConstructed(CtorFixture.class, fixture,
                        new Class<?>[] {int.class, String.class}, new Object[] {v, tag});
            }
        }
    }

    /** Registers the fixture and its superclass so hierarchy merges are complete. */
    private static void flattenAll(ClassNode node) throws Exception {
        ClassNode base = Reload.read(CtorBase.class);
        try (Context context = new Context(null, null, List.of())) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(node)));
            context.addEntry(new JarEntry.ClassEntry(new JClass(base)));
            FlowFlatten flatten = new FlowFlatten(new VaporConfig());
            for (MethodNode method : node.methods) {
                flatten.process(context, node, method);
            }
        }
    }

    private static void assertConstructed(Class<?> original, Class<?> transformed,
            Class<?>[] types, Object[] args) {
        assertEquals(describe(original, types, args), describe(transformed, types, args),
                "CtorFixture" + signature(types) + " args=" + java.util.Arrays.toString(args));
    }

    private static String describe(Class<?> fixture, Class<?>[] types, Object[] args) {
        try {
            Object instance = fixture.getConstructor(types).newInstance(args);
            return instance.toString();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return "throw:" + cause.getClass().getName() + ":" + cause.getMessage();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String signature(Class<?>[] types) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> type : types) {
            builder.append(type.getSimpleName()).append(',');
        }
        return builder.append(')').toString();
    }

    private static int constructorSwitches(ClassNode node, String desc) {
        MethodNode method = constructor(node, desc);
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) {
                count++;
            }
        }
        return count;
    }

    /** Opcodes up to and including the this/super constructor call. */
    private static List<Integer> prefix(ClassNode node, String desc) {
        MethodNode method = constructor(node, desc);
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode < 0) {
                continue;
            }
            opcodes.add(opcode);
            if (opcode == Opcodes.INVOKESPECIAL) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("<init>".equals(call.name)) {
                    break;
                }
            }
        }
        return opcodes;
    }

    private static MethodNode constructor(ClassNode node, String desc) {
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError("missing constructor " + desc);
    }
}
