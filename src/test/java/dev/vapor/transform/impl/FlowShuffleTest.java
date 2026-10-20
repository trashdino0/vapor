package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.testing.FlowFixture;
import dev.vapor.testing.Reload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential execution: the shuffled fixture must compute exactly the same
 * values as the original for a table of extreme and ordinary inputs.
 */
class FlowShuffleTest {

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
        FlowShuffle shuffle = new FlowShuffle(new VaporConfig());
        for (MethodNode method : transformed.methods) {
            shuffle.process(null, transformed, method);
        }

        assertTrue(gotos(transformed) > gotos(original),
                "expected the shuffle to add branches (gotos " + gotos(original)
                        + " -> " + gotos(transformed) + ")");

        Class<?> fixture = Reload.define(FlowFixture.class, Reload.write(transformed));

        for (int v : INTS) {
            assertEquals(FlowFixture.state1(v), invoke(fixture, "state1", v), "state1(" + v + ")");
            assertEquals(FlowFixture.state4(v), invoke(fixture, "state4", v), "state4(" + v + ")");
            for (int w : INTS) {
                assertEquals(FlowFixture.state2(v, w), invoke(fixture, "state2", v, w),
                        "state2(" + v + ", " + w + ")");
            }
        }
        for (long v : LONGS) {
            Method method = fixture.getMethod("state3", long.class);
            assertEquals(FlowFixture.state3(v), method.invoke(null, v), "state3(" + v + ")");
        }
    }

    private static int invoke(Class<?> fixture, String name, int v) throws Exception {
        return (int) fixture.getMethod(name, int.class).invoke(null, v);
    }

    private static int invoke(Class<?> fixture, String name, int v, int w) throws Exception {
        return (int) fixture.getMethod(name, int.class, int.class).invoke(null, v, w);
    }

    private static int gotos(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.GOTO) {
                    count++;
                }
            }
        }
        return count;
    }
}
