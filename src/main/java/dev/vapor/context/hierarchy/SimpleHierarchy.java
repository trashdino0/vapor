package dev.vapor.context.hierarchy;

import dev.vapor.context.Context;
import dev.vapor.tree.JClass;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hierarchy backed by the input jar, the {@code -lib} classpath and the JDK
 * ({@code jrt:/}). Class headers are resolved lazily and cached.
 *
 * <p>Unresolvable types fall back to {@code java/lang/Object} conservatively,
 * matching the fallback documented in PLAN.md — the rig catches the rare
 * verification failures this could cause.</p>
 */
public final class SimpleHierarchy implements IHierarchy {

    private static final String OBJECT = "java/lang/Object";
    private static final String CLONEABLE = "java/lang/Cloneable";
    private static final String SERIALIZABLE = "java/io/Serializable";

    private final Context context;
    private final Map<String, ClassInfo> infoCache = new HashMap<>();
    private final Set<String> unresolved = new HashSet<>();

    public SimpleHierarchy(Context context) {
        this.context = context;
    }

    @Override
    public ClassInfo getClassInfo(String internalName) {
        if (internalName == null) {
            return null;
        }
        ClassInfo cached = infoCache.get(internalName);
        if (cached != null) {
            return cached;
        }
        if (unresolved.contains(internalName)) {
            return null;
        }
        ClassInfo info = resolve(internalName);
        if (info == null) {
            unresolved.add(internalName);
        } else {
            infoCache.put(internalName, info);
        }
        return info;
    }

    private ClassInfo resolve(String internalName) {
        JClass clazz = context.getClass(internalName);
        if (clazz != null) {
            return new ClassInfo(clazz.name(), clazz.superName(),
                    clazz.interfaces().toArray(String[]::new), clazz.isInterface());
        }
        return context.libraries().classInfo(internalName);
    }

    @Override
    public boolean isInterface(String internalName) {
        ClassInfo info = getClassInfo(internalName);
        return info != null && info.isInterface();
    }

    @Override
    public boolean isAssignableFrom(String type, String subtype) {
        if (type == null || subtype == null) {
            return false;
        }
        if (type.equals(subtype)) {
            return true;
        }
        if (type.equals(OBJECT)) {
            return !isPrimitiveDescriptor(subtype);
        }
        boolean typeArray = isArray(type);
        boolean subArray = isArray(subtype);
        if (typeArray || subArray) {
            if (!subArray) {
                return false;
            }
            if (!typeArray) {
                return type.equals(CLONEABLE) || type.equals(SERIALIZABLE);
            }
            String typeElement = type.substring(1);
            String subElement = subtype.substring(1);
            if (isPrimitiveDescriptor(typeElement) || isPrimitiveDescriptor(subElement)) {
                return typeElement.equals(subElement);
            }
            return isAssignableFrom(elementName(typeElement), elementName(subElement));
        }
        // BFS over the superclass chain and interfaces of the subtype.
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(subtype);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(type)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            ClassInfo info = getClassInfo(current);
            if (info == null) {
                continue;   // unresolvable branch — cannot expand further
            }
            if (info.superName() != null) {
                queue.add(info.superName());
            }
            Collections.addAll(queue, info.interfaces());
        }
        return false;
    }

    @Override
    public String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) {
            return type1;
        }
        if (isArray(type1) || isArray(type2)) {
            return arrayCommonSuper(type1, type2);
        }
        ClassInfo info1 = getClassInfo(type1);
        ClassInfo info2 = getClassInfo(type2);
        if (info1 == null || info2 == null) {
            return OBJECT;   // conservative fallback for unknown types
        }
        if (isAssignableFrom(type1, type2)) {
            return type1;
        }
        if (isAssignableFrom(type2, type1)) {
            return type2;
        }
        if (info1.isInterface() || info2.isInterface()) {
            return OBJECT;
        }
        String superName = info1.superName();
        while (superName != null) {
            if (isAssignableFrom(superName, type2)) {
                return superName;
            }
            ClassInfo info = getClassInfo(superName);
            superName = info == null ? null : info.superName();
        }
        return OBJECT;
    }

    private String arrayCommonSuper(String type1, String type2) {
        boolean array1 = isArray(type1);
        boolean array2 = isArray(type2);
        if (array1 && array2) {
            String element1 = type1.substring(1);
            String element2 = type2.substring(1);
            if (isPrimitiveDescriptor(element1) || isPrimitiveDescriptor(element2)) {
                return OBJECT;
            }
            String merged = getCommonSuperClass(elementName(element1), elementName(element2));
            // recursion returns an internal name for classes and a descriptor for arrays
            return merged.charAt(0) == '[' ? "[" + merged : "[L" + merged + ";";
        }
        String other = array1 ? type2 : type1;
        if (other.equals(OBJECT) || other.equals(CLONEABLE) || other.equals(SERIALIZABLE)) {
            return other;
        }
        return OBJECT;
    }

    private static boolean isArray(String typeName) {
        return !typeName.isEmpty() && typeName.charAt(0) == '[';
    }

    /** {@code Ljava/lang/String;} -> {@code java/lang/String}; array descriptors stay as-is. */
    private static String elementName(String descriptor) {
        return descriptor.charAt(0) == 'L' ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
    }

    private static boolean isPrimitiveDescriptor(String descriptor) {
        return descriptor.length() == 1 && "VZCBSIFJD".indexOf(descriptor.charAt(0)) >= 0;
    }
}
