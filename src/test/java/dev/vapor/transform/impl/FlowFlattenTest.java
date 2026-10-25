package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.testing.FlowFixture;
import dev.vapor.testing.Reload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.3 differential execution: the flattened fixture must compute exactly the same
 * values as the original for a table of extreme and ordinary inputs. The fixture
 * covers loops, dense and sparse switches, early exits, a try/catch method (flattened
 * with handlers kept outside the dispatcher), ternary/live-stack spills and an
 * uninitialized {@code NEW} in a constructor argument; a second test checks that
 * transformation is deterministic.
 */
class FlowFlattenTest {

    private static final int[] INTS = {
            Integer.MIN_VALUE, -1000000, -1000, -13, -7, -2, -1, 0, 1, 2, 3, 7, 8, 13,
            100, 255, 1000, 999983, Integer.MAX_VALUE
    };

    private static final long[] LONGS = {
            Long.MIN_VALUE, -1000000000000L, -1L, 0L, 1L, 999983L, Long.MAX_VALUE
    };

    @Test
    void behaviorIsPreserved() throws Exception {
        ClassNode original = Reload.read(FlowFixture.class);
        ClassNode transformed = Reload.read(FlowFixture.class);
        FlowFlatten flatten = new FlowFlatten(new VaporConfig());
        for (MethodNode method : transformed.methods) {
            flatten.process(null, transformed, method);
        }

        assertTrue(switches(transformed) > switches(original),
                "expected dispatchers to add switches (" + switches(original) + " -> " + switches(transformed) + ")");
        assertTrue(switchCount(transformed, "state4") > switchCount(original, "state4"),
                "ternary values are spilled, so state4 should now be flattened");
        assertTrue(switchCount(transformed, "guarded") > switchCount(original, "guarded"),
                "methods with an exception table are flattened with handlers outside the dispatcher");
        assertTrue(switchCount(transformed, "state5") > switchCount(original, "state5"),
                "dense-switch method should be flattened (dispatcher switch added)");
        assertTrue(switchCount(transformed, "state6") > switchCount(original, "state6"),
                "sparse-switch method should be flattened (dispatcher switch added)");
        for (String method : new String[] {"ternary1", "ternary2", "ternary3", "ternaryNull", "ctorTernary"}) {
            assertTrue(switchCount(transformed, method) > switchCount(original, method),
                    method + " should be flattened via stack spills");
        }

        Class<?> fixture = Reload.define(FlowFixture.class, Reload.write(transformed));
        for (int v : INTS) {
            assertEquals(FlowFixture.state1(v), invoke(fixture, "state1", v), "state1(" + v + ")");
            assertEquals(FlowFixture.state4(v), invoke(fixture, "state4", v), "state4(" + v + ")");
            assertEquals(FlowFixture.state5(v), invoke(fixture, "state5", v), "state5(" + v + ")");
            assertEquals(FlowFixture.state6(v), invoke(fixture, "state6", v), "state6(" + v + ")");
            for (int w : INTS) {
                assertEquals(FlowFixture.state2(v, w), invoke(fixture, "state2", v, w),
                        "state2(" + v + ", " + w + ")");
                assertEquals(FlowFixture.ternary1(v, w), invoke(fixture, "ternary1", v, w),
                        "ternary1(" + v + ", " + w + ")");
            }
        }
        for (long v : LONGS) {
            assertEquals(FlowFixture.state3(v),
                    fixture.getMethod("state3", long.class).invoke(null, v), "state3(" + v + ")");
            for (int w : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
                assertEquals(FlowFixture.ternary2(v, w),
                        (long) fixture.getMethod("ternary2", long.class, int.class).invoke(null, v, w),
                        "ternary2(" + v + ", " + w + ")");
            }
        }
        for (boolean flag : new boolean[] {true, false}) {
            assertEquals(FlowFixture.ternary3(flag, "left", "right"),
                    fixture.getMethod("ternary3", boolean.class, String.class, String.class)
                            .invoke(null, flag, "left", "right"), "ternary3(" + flag + ")");
            assertEquals(FlowFixture.ternaryNull(flag),
                    fixture.getMethod("ternaryNull", boolean.class).invoke(null, flag),
                    "ternaryNull(" + flag + ")");
            assertEquals(FlowFixture.ctorTernary(flag),
                    fixture.getMethod("ctorTernary", boolean.class).invoke(null, flag),
                    "ctorTernary(" + flag + ")");
        }
        for (int v : new int[] {Integer.MIN_VALUE, -7, -1, 0, 1, 3, 42}) {
            assertEquals(FlowFixture.guarded(v), fixture.getMethod("guarded", int.class).invoke(null, v),
                    "guarded(" + v + ")");
        }
    }

    @Test
    void outputIsDeterministic() throws Exception {
        ClassNode first = Reload.read(FlowFixture.class);
        ClassNode second = Reload.read(FlowFixture.class);
        FlowFlatten flatten = new FlowFlatten(new VaporConfig());
        for (MethodNode method : first.methods) {
            flatten.process(null, first, method);
        }
        for (MethodNode method : second.methods) {
            flatten.process(null, second, method);
        }
        assertArrayEquals(Reload.write(first), Reload.write(second), "same input + seed must produce identical bytes");
    }

    /**
     * Pipeline-order regression: dead-code decoys and flow-shuffle guard splits run first.
     * Their inserted code pushes a few slots (insufficient {@code maxStack} used to make the
     * analyzer fail) and guard splits used to resume at non-empty stack points (which made
     * whole methods ineligible for flattening). Flattening must still fire.
     */
    @Test
    void flatteningSurvivesEarlierNoiseTransforms() throws Exception {
        VaporConfig config = new VaporConfig();
        ClassNode node = Reload.read(FlowFixture.class);
        List<MethodNode> methods = new ArrayList<>(node.methods);
        new StringEncryptor(config).encrypt(node);

        DeadCode deadCode = new DeadCode(config);
        FlowShuffle shuffle = new FlowShuffle(config);
        for (MethodNode method : methods) {
            deadCode.process(null, node, method);
            shuffle.process(null, node, method);
        }

        int before = switches(node);
        FlowFlatten flatten = new FlowFlatten(config);
        for (MethodNode method : methods) {
            flatten.process(null, node, method);
        }
        assertTrue(switches(node) > before,
                "flattening must still fire after DeadCode + FlowShuffle (" + before + " -> " + switches(node) + ")");
    }

    private static int invoke(Class<?> fixture, String name, int v) throws Exception {
        return (int) fixture.getMethod(name, int.class).invoke(null, v);
    }

    private static int invoke(Class<?> fixture, String name, int v, int w) throws Exception {
        return (int) fixture.getMethod(name, int.class, int.class).invoke(null, v, w);
    }

    private static int switches(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            count += switchCount(method);
        }
        return count;
    }

    private static int switchCount(ClassNode node, String methodName) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName)) {
                return switchCount(method);
            }
        }
        return -1;
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
}
