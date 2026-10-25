package dev.vapor.naming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rename mapping: classes by internal name, methods/fields keyed by
 * {@code name + descriptor}.
 *
 * <p>Member keys are deliberately global (not per-owner): any method with the
 * same name and descriptor is renamed identically everywhere. That makes
 * virtual/interface dispatch, bridge methods and jar-internal overrides
 * trivially consistent, at a small cost in name variety. Anything that could
 * clash with a library member is excluded before planning (see the renamer).</p>
 */
public final class Mappings {

    private final Map<String, String> classes = new LinkedHashMap<>();
    private final Map<String, String> methods = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Map<String, String> recordComponents = new HashMap<>();

    /** Export data for the mapping file, in jar order. */
    private final List<ClassMapping> export = new ArrayList<>();

    public record ClassMapping(String original, String renamed, List<MemberMapping> members) {
    }

    public record MemberMapping(boolean method, String name, String descriptor, String renamed) {
    }

    public void mapClass(String original, String renamed) {
        classes.put(original, renamed);
    }

    public void mapMethodKey(String name, String descriptor, String renamed) {
        methods.put(name + descriptor, renamed);
    }

    public void mapFieldKey(String name, String descriptor, String renamed) {
        fields.put(name + descriptor, renamed);
    }

    public void mapRecordComponentKey(String name, String descriptor, String renamed) {
        recordComponents.put(name + descriptor, renamed);
    }

    /** Identity when the class is not renamed. */
    public String mappedClass(String internalName) {
        if (internalName == null) {
            return null;
        }
        String mapped = classes.get(internalName);
        return mapped == null ? internalName : mapped;
    }

    /** Identity when the method key is not renamed. */
    public String mappedMethod(String name, String descriptor) {
        String mapped = methods.get(name + descriptor);
        return mapped == null ? name : mapped;
    }

    /** Identity when the field key is not renamed. */
    public String mappedField(String name, String descriptor) {
        String mapped = fields.get(name + descriptor);
        return mapped == null ? name : mapped;
    }

    /** Identity when the record component key is not renamed. */
    public String mappedRecordComponent(String name, String descriptor) {
        String mapped = recordComponents.get(name + descriptor);
        return mapped == null ? name : mapped;
    }

    public void addExport(ClassMapping mapping) {
        export.add(mapping);
    }

    public List<ClassMapping> export() {
        return export;
    }
}
