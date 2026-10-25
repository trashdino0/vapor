package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.naming.Mappings;
import dev.vapor.testing.RecordFixture;
import dev.vapor.testing.Reload;
import dev.vapor.tree.JClass;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Record component obfuscation: the backing field, accessor and RecordComponent
 * attribute must be renamed together (otherwise reflection, the canonical
 * constructor and the ObjectMethods bootstrap disagree), while behavior stays
 * identical. One scenario keeps the class name so the transformed fixture can be
 * loaded directly; another exercises the default class renaming as well.
 */
class RecordRenamingTest {

    private static final String NAME_DESC = "Ljava/lang/String;";
    private static final String COUNT_DESC = "I";
    private static final String NAME_ACCESSOR = "()" + NAME_DESC;
    private static final String COUNT_ACCESSOR = "()" + COUNT_DESC;

    @Test
    void obfuscatedComponentsStayConsistentAndKeepBehavior() throws Exception {
        VaporConfig config = recordConfig();
        config.transformers.obfuscateRecords = true;

        Transformed transformed = rename(config);
        Mappings mappings = transformed.mappings();

        String name = mappings.mappedRecordComponent("name", NAME_DESC);
        String count = mappings.mappedRecordComponent("count", COUNT_DESC);

        assertNotEquals("name", name, "record component must be renamed");
        assertNotEquals("count", count, "record component must be renamed");
        assertNotEquals(name, count, "distinct components must keep distinct names");

        // The RecordComponent attribute must agree with the backing field and accessor.
        assertEquals(name, mappings.mappedField("name", NAME_DESC), "component/field names diverged");
        assertEquals(name, mappings.mappedMethod("name", NAME_ACCESSOR), "component/accessor names diverged");
        assertEquals(count, mappings.mappedField("count", COUNT_DESC), "component/field names diverged");
        assertEquals(count, mappings.mappedMethod("count", COUNT_ACCESSOR), "component/accessor names diverged");

        Class<?> type = transformed.type();
        assertTrue(type.isRecord(), "transformed class must still be a record");
        assertEquals(Set.of(name, count), componentNames(type), "Record attribute names are stale");

        Object instance = type.getConstructor(String.class, int.class).newInstance("vapor", 7);

        // Accessors must exist under the new component names and return the values.
        assertEquals("vapor", type.getMethod(name).invoke(instance));
        assertEquals(7, type.getMethod(count).invoke(instance));
        assertEquals("vapor", findComponent(type, name).getAccessor().invoke(instance),
                "RecordComponent.getAccessor() must resolve the renamed accessor");
        assertEquals(7, findComponent(type, count).getAccessor().invoke(instance),
                "RecordComponent.getAccessor() must resolve the renamed accessor");

        // The private final backing fields follow the same names.
        assertEquals(String.class, type.getDeclaredField(name).getType());
        assertEquals(int.class, type.getDeclaredField(count).getType());
        assertThrows(NoSuchFieldException.class, () -> type.getDeclaredField("name"),
                "original backing field name leaked");
        assertThrows(NoSuchFieldException.class, () -> type.getDeclaredField("count"),
                "original backing field name leaked");

        assertRecordBehavior(type, instance);

        // toString must be generated from the rewritten ObjectMethods name list.
        String text = instance.toString();
        assertTrue(text.contains(name + "=vapor"), "toString missing renamed component: " + text);
        assertTrue(text.contains(count + "=7"), "toString missing renamed component: " + text);
        assertFalse(text.contains("name=vapor"), "toString leaked the original component name: " + text);
        assertFalse(text.contains("count=7"), "toString leaked the original component name: " + text);

        // Ordinary method bodies that read components must be remapped too.
        String summary = mappings.mappedMethod("summary", "()Ljava/lang/String;");
        assertNotEquals("summary", summary);
        assertEquals("vapor#7", type.getMethod(summary).invoke(instance));

        String withCountDesc = "(I)L" + RecordFixture.class.getName().replace('.', '/') + ";";
        String withCount = mappings.mappedMethod("withCount", withCountDesc);
        Object changed = type.getMethod(withCount, int.class).invoke(instance, 9);
        assertEquals("vapor#9", type.getMethod(summary).invoke(changed));
    }

    @Test
    void renamedRecordClassStillLoadsWithConsistentComponents() throws Exception {
        VaporConfig config = new VaporConfig();
        config.failsafe = false;
        config.transformers.obfuscateRecords = true;   // renameClasses/renameMethods/renameFields stay on

        Transformed transformed = rename(config);
        Mappings mappings = transformed.mappings();

        String originalClass = RecordFixture.class.getName().replace('.', '/');
        String mappedClass = mappings.mappedClass(originalClass);
        assertNotEquals(originalClass, mappedClass, "class must be renamed for this scenario");
        assertEquals(mappedClass.replace('/', '.'), transformed.type().getName());

        Class<?> type = transformed.type();
        assertTrue(type.isRecord(), "transformed class must still be a record");
        String name = mappings.mappedRecordComponent("name", NAME_DESC);
        String count = mappings.mappedRecordComponent("count", COUNT_DESC);
        assertEquals(Set.of(name, count), componentNames(type));

        Object instance = type.getConstructor(String.class, int.class).newInstance("vapor", 7);
        assertEquals("vapor", type.getMethod(name).invoke(instance));
        assertEquals(7, type.getMethod(count).invoke(instance));
        assertRecordBehavior(type, instance);

        String text = instance.toString();
        assertTrue(text.contains(name + "=vapor"), "toString missing renamed component: " + text);
        assertTrue(text.contains(count + "=7"), "toString missing renamed component: " + text);
        assertFalse(text.contains("name=vapor"), "toString leaked the original component name: " + text);

        String summary = mappings.mappedMethod("summary", "()Ljava/lang/String;");
        assertEquals("vapor#7", type.getMethod(summary).invoke(instance));
    }

    @Test
    void defaultKeepsComponentNamesAndBehaviorStable() throws Exception {
        VaporConfig config = recordConfig();
        assertFalse(config.transformers.obfuscateRecords, "obfuscateRecords defaults to off");

        Transformed transformed = rename(config);
        Mappings mappings = transformed.mappings();

        assertEquals("name", mappings.mappedRecordComponent("name", NAME_DESC));
        assertEquals("count", mappings.mappedRecordComponent("count", COUNT_DESC));
        assertEquals("name", mappings.mappedField("name", NAME_DESC));
        assertEquals("name", mappings.mappedMethod("name", NAME_ACCESSOR));
        assertEquals("count", mappings.mappedField("count", COUNT_DESC));
        assertEquals("count", mappings.mappedMethod("count", COUNT_ACCESSOR));

        Class<?> type = transformed.type();
        assertTrue(type.isRecord(), "transformed class must still be a record");
        assertEquals(Set.of("name", "count"), componentNames(type), "default mode must keep component names");

        Object instance = type.getConstructor(String.class, int.class).newInstance("vapor", 7);
        assertEquals("vapor", type.getMethod("name").invoke(instance));
        assertEquals(7, type.getMethod("count").invoke(instance));
        assertEquals("vapor", findComponent(type, "name").getAccessor().invoke(instance));
        assertEquals(7, findComponent(type, "count").getAccessor().invoke(instance));

        assertRecordBehavior(type, instance);

        String text = instance.toString();
        assertTrue(text.contains("name=vapor"), "default toString must keep component names: " + text);
        assertTrue(text.contains("count=7"), "default toString must keep component names: " + text);
    }

    private static void assertRecordBehavior(Class<?> type, Object instance) throws Exception {
        Object twin = type.getConstructor(String.class, int.class).newInstance("vapor", 7);
        Object different = type.getConstructor(String.class, int.class).newInstance("vapor", 8);

        assertEquals(instance, twin);
        assertEquals(instance.hashCode(), twin.hashCode());
        assertNotEquals(instance, different);
        assertTrue(instance.equals(twin) && !instance.equals(different));
    }

    private static RecordComponent findComponent(Class<?> type, String name) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new AssertionError("record component not found: " + name);
    }

    private static Set<String> componentNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static VaporConfig recordConfig() {
        VaporConfig config = new VaporConfig();
        config.failsafe = false;   // surface a renamer failure instead of leaving the class untouched
        config.transformers.renameClasses = false;
        return config;
    }

    private static Transformed rename(VaporConfig config) throws Exception {
        try (Context context = new Context(null, null, List.of())) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(Reload.read(RecordFixture.class))));
            new Renamer(config).run(context);

            assertEquals(1, context.classes().size(), "renamer dropped the fixture class");
            JClass clazz = context.classes().iterator().next();
            byte[] bytes = Reload.write(clazz.node());
            return new Transformed(Reload.define(clazz.name().replace('/', '.'), bytes), context.mappings());
        }
    }

    private record Transformed(Class<?> type, Mappings mappings) {
    }
}
