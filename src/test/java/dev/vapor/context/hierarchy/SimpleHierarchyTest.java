package dev.vapor.context.hierarchy;

import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises hierarchy resolution across all three sources: synthetic jar
 * classes, the JDK (via {@code jrt:/}) and unresolved-type fallbacks.
 */
class SimpleHierarchyTest {

    private static Context context;
    private static SimpleHierarchy hierarchy;

    @BeforeAll
    static void setUp() throws Exception {
        context = new Context(null, null, List.of());
        // Synthetic "jar" hierarchy:
        //   Base (implements Cloneable) <- Mid (implements Serializable) <- Leaf (implements SubIface)
        //   Iface <- SubIface
        addClass(clazz("dev/test/Base", "java/lang/Object", "java/lang/Cloneable"));
        addClass(clazz("dev/test/Mid", "dev/test/Base", "java/io/Serializable"));
        addClass(clazz("dev/test/Leaf", "dev/test/Mid", "dev/test/SubIface"));
        addClass(iface("dev/test/Iface"));
        addClass(iface("dev/test/SubIface", "dev/test/Iface"));
        hierarchy = context.hierarchy();
    }

    private static void addClass(ClassNode node) {
        context.addEntry(new JarEntry.ClassEntry(new JClass(node)));
    }

    private static ClassNode clazz(String name, String superName, String... interfaces) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = superName;
        for (String itf : interfaces) {
            node.interfaces.add(itf);
        }
        return node;
    }

    private static ClassNode iface(String name, String... superInterfaces) {
        ClassNode node = clazz(name, "java/lang/Object", superInterfaces);
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        return node;
    }

    // --- JDK hierarchy (jrt) ------------------------------------------------

    @Test
    void jdkSiblingsMeetAtObject() {
        assertEquals("java/lang/Object",
                hierarchy.getCommonSuperClass("java/lang/String", "java/lang/Integer"));
    }

    @Test
    void jdkSiblingsMeetAtCommonSuperclass() {
        assertEquals("java/util/AbstractList",
                hierarchy.getCommonSuperClass("java/util/ArrayList", "java/util/LinkedList"));
        assertEquals("java/lang/Number",
                hierarchy.getCommonSuperClass("java/lang/Integer", "java/lang/Long"));
    }

    @Test
    void subtypeWinsOverSupertype() {
        assertEquals("java/lang/Number", hierarchy.getCommonSuperClass("java/lang/Number", "java/lang/Integer"));
        assertEquals("java/lang/Number", hierarchy.getCommonSuperClass("java/lang/Integer", "java/lang/Number"));
    }

    @Test
    void classAndInterfaceMeetAtInterface() {
        assertEquals("java/util/List",
                hierarchy.getCommonSuperClass("java/util/AbstractList", "java/util/List"));
    }

    @Test
    void twoInterfacesMeetAtObject() {
        assertEquals("java/lang/Object",
                hierarchy.getCommonSuperClass("java/util/List", "java/io/Serializable"));
    }

    @Test
    void assignabilityThroughInterfaces() {
        assertTrue(hierarchy.isAssignableFrom("java/lang/CharSequence", "java/lang/String"));
        assertTrue(hierarchy.isAssignableFrom("java/util/Collection", "java/util/List"));
        assertFalse(hierarchy.isAssignableFrom("java/lang/String", "java/lang/CharSequence"));
        assertTrue(hierarchy.isAssignableFrom("java/lang/Object", "java/lang/String"));
    }

    // --- jar hierarchy ------------------------------------------------------

    @Test
    void jarClassesWalkSuperChain() {
        assertEquals("dev/test/Base", hierarchy.getCommonSuperClass("dev/test/Leaf", "dev/test/Base"));
        assertEquals("dev/test/Mid", hierarchy.getCommonSuperClass("dev/test/Mid", "dev/test/Leaf"));
    }

    @Test
    void jarInterfaceAssignability() {
        assertTrue(hierarchy.isAssignableFrom("dev/test/Iface", "dev/test/Leaf"));      // via SubIface
        assertTrue(hierarchy.isAssignableFrom("java/lang/Cloneable", "dev/test/Base"));
        assertTrue(hierarchy.isAssignableFrom("java/io/Serializable", "dev/test/Mid"));
        assertFalse(hierarchy.isAssignableFrom("dev/test/Leaf", "dev/test/Base"));      // wrong direction
    }

    @Test
    void jarAndJdkMix() {
        assertEquals("java/lang/Object", hierarchy.getCommonSuperClass("dev/test/Leaf", "java/lang/String"));
        assertEquals("java/lang/Cloneable", hierarchy.getCommonSuperClass("dev/test/Base", "java/lang/Cloneable"));
    }

    // --- arrays -------------------------------------------------------------

    @Test
    void arraysUseElementTypes() {
        assertEquals("[Ljava/lang/CharSequence;",
                hierarchy.getCommonSuperClass("[Ljava/lang/String;", "[Ljava/lang/CharSequence;"));
        assertEquals("[Ljava/lang/Object;",
                hierarchy.getCommonSuperClass("[Ljava/lang/String;", "[Ljava/lang/Integer;"));
        assertEquals("java/lang/Object",
                hierarchy.getCommonSuperClass("[I", "java/lang/String"));
        assertEquals("[I", hierarchy.getCommonSuperClass("[I", "[I"));
    }

    @Test
    void arrayAssignability() {
        assertTrue(hierarchy.isAssignableFrom("java/lang/Object", "[Ljava/lang/String;"));
        assertTrue(hierarchy.isAssignableFrom("java/lang/Cloneable", "[I"));
        assertTrue(hierarchy.isAssignableFrom("[Ljava/lang/Object;", "[Ljava/lang/String;"));
        assertFalse(hierarchy.isAssignableFrom("[Ljava/lang/String;", "[Ljava/lang/Object;"));
        assertFalse(hierarchy.isAssignableFrom("java/lang/String", "[Ljava/lang/String;"));
    }

    // --- fallbacks ----------------------------------------------------------

    @Test
    void unknownClassesFallBackToObject() {
        assertEquals("java/lang/Object", hierarchy.getCommonSuperClass("com/absent/A", "com/absent/B"));
        assertEquals("java/lang/Object", hierarchy.getCommonSuperClass("java/lang/String", "com/absent/A"));
        assertFalse(hierarchy.isAssignableFrom("com/absent/A", "java/lang/String"));
        assertNull(hierarchy.getClassInfo("com/absent/A"));
    }
}
