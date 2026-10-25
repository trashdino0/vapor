package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.testing.ConcatFixture;
import dev.vapor.testing.Reload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.1: indy string-concat call sites must be rewritten into decryptor-backed
 * StringBuilder chains — digesting every recipe shape javac can emit (multi-part,
 * folded literals, all primitive widths, char/boolean traps, String/Object/char[]
 * references, surrogate pairs) and failing safe on control characters. The
 * comparison is differential: the fixture transcript is byte-for-byte identical and
 * the recipe literals are gone from the class file.
 */
class ConcatEncryptionTest {

    @Test
    void concatLiteralsAreEncryptedAndBehaviorIsPreserved() throws Exception {
        ClassNode node = Reload.read(ConcatFixture.class);
        int before = countConcatSites(node);
        assertTrue(before >= 7, "fixture should contain several concat sites (found " + before + ")");

        String decryptor = new StringEncryptor(new VaporConfig()).encrypt(node);
        assertNotNull(decryptor, "decryptor should have been injected");

        int after = countConcatSites(node);
        assertEquals(1, after,
                "only the control-character recipe (\\u0002 bootstrap constant) may keep its invokedynamic (before=" + before + ")");

        byte[] bytes = Reload.write(node);
        String latin = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(latin.contains("concat-literal-alpha"), "recipe literal leaked into the class file");
        assertFalse(latin.contains("loop-literal"), "loop literal leaked into the class file");
        assertFalse(latin.contains("folded-middle"), "folded literal leaked into the class file");
        assertFalse(latin.contains("nums:"), "numeric-recipe literal leaked into the class file");
        assertFalse(latin.contains("emoji-"), "emoji literal leaked into the class file");

        Class<?> reloaded = Reload.define(ConcatFixture.class, bytes);
        assertEquals(ConcatFixture.run(), reloaded.getMethod("run").invoke(null), "behavior changed");
    }

    @Test
    void interfaceConcatGetsStaticDecryptor() throws Exception {
        ClassNode node = Reload.read(ConcatFixture.Iface.class);
        String decryptor = new StringEncryptor(new VaporConfig()).encrypt(node);
        assertNotNull(decryptor, "decryptor should have been injected into the interface");

        byte[] bytes = Reload.write(node);
        String latin = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(latin.contains("iface-literal"), "interface recipe literal leaked into the class file");

        Class<?> reloaded = Reload.define(ConcatFixture.Iface.class, bytes);
        assertEquals(ConcatFixture.Iface.greet("world"), reloaded.getMethod("greet", String.class).invoke(null, "world"));
    }

    /**
     * javac pre-converts reference operands with {@code String.valueOf(Object)}, so
     * {@code [C} / {@code Object} descriptor arguments only appear in classes from
     * other compilers (ecj, kotlinc…). Build such call sites by hand and verify the
     * rewritten version converts the argument exactly like the factory does — the
     * measured ground truth is {@code valueOf(Object)} semantics ({@code [C} →
     * {@code [C@hash}, not the characters), which is why every reference type maps
     * to {@code append(Object)}.
     */
    @Test
    void rawDescriptorTypesMatchFactorySemantics() throws Exception {
        assertSameAcrossLoaders("([C)Ljava/lang/String;", char[].class, new char[] {'a', 'b', 'c'});
        assertSameAcrossLoaders("(Ljava/lang/Object;)Ljava/lang/String;", Object.class, new char[] {'x', 'y'});
        assertSameAcrossLoaders("(Ljava/lang/String;)Ljava/lang/String;", String.class, null);
    }

    private static void assertSameAcrossLoaders(String desc, Class<?> argType, Object arg) throws Exception {
        Class<?> plain = Reload.define(SYNTHETIC, Reload.write(syntheticConcat(desc)));

        ClassNode node = syntheticConcat(desc);
        assertNotNull(new StringEncryptor(new VaporConfig()).encrypt(node), "decryptor should have been injected");
        Class<?> obfuscated = Reload.define(SYNTHETIC, Reload.write(node));

        Object expected = plain.getMethod("go", argType).invoke(null, arg);
        Object actual = obfuscated.getMethod("go", argType).invoke(null, arg);
        assertEquals(expected, actual, desc + " (expected=" + expected + ")");
    }

    /** Synthetic host with a single {@code makeConcatWithConstants} site and a one-argument recipe. */
    private static ClassNode syntheticConcat(String desc) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = SYNTHETIC.replace('.', '/');
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", desc, null, null);
        Type argument = Type.getArgumentTypes(desc)[0];
        method.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), 0));
        method.instructions.add(new InvokeDynamicInsnNode("makeConcatWithConstants", desc,
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
                "x\u0001"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return node;
    }

    private static final String SYNTHETIC = "dev.vapor.testing.SyntheticConcat";

    private static int countConcatSites(ClassNode node) {
        int sites = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy && indy.bsm != null
                        && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                        && "makeConcatWithConstants".equals(indy.bsm.getName())) {
                    sites++;
                }
            }
        }
        return sites;
    }
}
