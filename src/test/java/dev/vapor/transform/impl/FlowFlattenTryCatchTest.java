package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.testing.Reload;
import dev.vapor.testing.TryCatchFixture;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential coverage for try/catch flattening. Handlers stay outside the
 * dispatchers; every moved block gets its own exception-table copy. These tests
 * compare both return values and thrown exceptions against the original class.
 */
class FlowFlattenTryCatchTest {

    private static final int[] INTS = {
            Integer.MIN_VALUE, -1000, -100, -13, -7, -2, -1, 0, 1, 2, 3, 7, 13,
            100, 999983, Integer.MAX_VALUE
    };

    private static final long[] LONGS = {
            Long.MIN_VALUE, -999983L, -1L, 0L, 1L, 999983L, Long.MAX_VALUE
    };

    private static final String[] TRY_METHODS = {
            "guarded", "finallyFlow", "finallyOverrides", "finallyReturn", "nestedFinally",
            "loopTry", "multiCatch", "catchAll", "synchronizedBlock", "rethrow",
            "catchSwitch", "longTry", "tryAfterCode", "nestedCatchRethrow"
    };

    @Test
    void tryCatchMethodsAreFlattened() throws Exception {
        ClassNode original = Reload.read(TryCatchFixture.class);
        ClassNode transformed = Reload.read(TryCatchFixture.class);
        flattenAll(transformed);

        for (String name : TRY_METHODS) {
            assertTrue(switchCount(transformed, name) > switchCount(original, name),
                    name + " must be flattened with a dispatcher");
            assertTrue(tryEntries(transformed, name) >= tryEntries(original, name),
                    name + " must keep its exception table");
            MethodNode method = method(transformed, name);
            Set<LabelNode> cases = switchTargets(method);
            for (TryCatchBlockNode entry : method.tryCatchBlocks) {
                assertFalse(cases.contains(entry.handler),
                        name + ": handler labels must stay outside every dispatcher");
            }
        }

        assertTrue(tryEntries(transformed, "nestedFinally") > tryEntries(original, "nestedFinally"),
                "overlapping ranges are copied once per moved block");
    }

    @Test
    void behaviorAndExceptionFlowArePreserved() throws Exception {
        ClassNode transformed = Reload.read(TryCatchFixture.class);
        flattenAll(transformed);
        Class<?> fixture = Reload.define(TryCatchFixture.class, Reload.write(transformed));

        for (int v : INTS) {
            for (String name : TRY_METHODS) {
                if ("longTry".equals(name)) {
                    continue;
                }
                assertEquals(outcome(TryCatchFixture.class, name, v), outcome(fixture, name, v),
                        name + "(" + v + ")");
            }
        }
        for (long v : LONGS) {
            for (int w : new int[] {Integer.MIN_VALUE, -7, -1, 0, 1, 13, Integer.MAX_VALUE}) {
                assertEquals(longOutcome(TryCatchFixture.class, v, w), longOutcome(fixture, v, w),
                        "longTry(" + v + ", " + w + ")");
            }
        }
    }

    /**
     * Without a hierarchy a shared handler for two catch types merges its stack to
     * TOP. Handler blocks are never reloaded or spilled, so that must not make the
     * whole method ineligible.
     */
    @Test
    void handlersDoNotNeedAHierarchyForTheirOwnStack() throws Exception {
        ClassNode original = Reload.read(TryCatchFixture.class);
        ClassNode transformed = Reload.read(TryCatchFixture.class);
        FlowFlatten flatten = new FlowFlatten(new VaporConfig());
        for (MethodNode method : transformed.methods) {
            flatten.process(null, transformed, method);
        }
        assertTrue(switchCount(transformed, "finallyFlow") > switchCount(original, "finallyFlow"),
                "shared catch handlers must not require hierarchy resolution");
        Class<?> fixture = Reload.define(TryCatchFixture.class, Reload.write(transformed));
        for (int v : INTS) {
            assertEquals(outcome(TryCatchFixture.class, "finallyFlow", v),
                    outcome(fixture, "finallyFlow", v), "finallyFlow(" + v + ")");
        }
    }

    @Test
    void outputIsDeterministicWithHandlers() throws Exception {
        ClassNode first = Reload.read(TryCatchFixture.class);
        ClassNode second = Reload.read(TryCatchFixture.class);
        flattenAll(first);
        flattenAll(second);
        assertArrayEquals(Reload.write(first), Reload.write(second),
                "same input + seed must produce identical bytes");
    }

    /** Uses a real hierarchy so multi-catch handler stacks merge to Throwable. */
    private static void flattenAll(ClassNode node) throws Exception {
        try (Context context = new Context(null, null, List.of())) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(node)));
            FlowFlatten flatten = new FlowFlatten(new VaporConfig());
            for (MethodNode method : node.methods) {
                flatten.process(context, node, method);
            }
        }
    }

    private static String outcome(Class<?> fixture, String name, int v) {
        try {
            Object result = fixture.getMethod(name, int.class).invoke(null, v);
            return "ok:" + result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return "throw:" + cause.getClass().getName() + ":" + cause.getMessage();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String longOutcome(Class<?> fixture, long v, int w) {
        try {
            Object result = fixture.getMethod("longTry", long.class, int.class).invoke(null, v, w);
            return "ok:" + result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return "throw:" + cause.getClass().getName() + ":" + cause.getMessage();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static MethodNode method(ClassNode node, String methodName) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("missing method " + methodName);
    }

    private static Set<LabelNode> switchTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        return targets;
    }

    private static int switchCount(ClassNode node, String methodName) {
        return switchCount(method(node, methodName));
    }

    private static int switchCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) {
                count++;
            }
        }
        return count;
    }

    private static int tryEntries(ClassNode node, String methodName) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName)) {
                return method.tryCatchBlocks == null ? 0 : method.tryCatchBlocks.size();
            }
        }
        return -1;
    }
}
