package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.LibraryLoader;
import dev.vapor.context.hierarchy.IHierarchy;
import dev.vapor.naming.Dictionary;
import dev.vapor.naming.Mappings;
import dev.vapor.naming.RemapperImpl;
import dev.vapor.naming.VaporClassRemapper;
import dev.vapor.tree.JClass;
import dev.vapor.transform.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renames classes, methods and fields.
 *
 * <p>Design notes (the load-bearing bits):</p>
 * <ul>
 *   <li>Member mappings are keyed by {@code name + descriptor} globally, so
 *       every override chain, bridge and interface dispatch renames as one
 *       unit. Anything that must keep its name — library overrides (Object
 *       methods, Runnable.run, ...), enum {@code values/valueOf} and
 *       constants, serialization hooks, record components, annotation
 *       elements, {@code main}, agent entry points, {@code @VaporKeep} — is
 *       collected up front and excluded.</li>
 *   <li>Renamed classes are flattened to the root package; inner classes keep
 *       their outer's new name plus {@code $suffix} so the InnerClasses
 *       metadata stays consistent.</li>
 *   <li>The whole rename is planned before anything is mutated, and applied to
 *       every class as a single remap pass (compute → commit), so a failure
 *       cannot leave a half-renamed jar behind.</li>
 * </ul>
 */
public final class Renamer extends Transformer {

    /** Class/method/field annotation (matched by simple name) that keeps an element's name. */
    public static final String KEEP_ANNOTATION = "VaporKeep";

    private static final String SERIALIZABLE = "java/io/Serializable";

    private static final Set<String> SERIALIZATION_METHODS = Set.of(
            "writeObject(Ljava/io/ObjectOutputStream;)V",
            "readObject(Ljava/io/ObjectInputStream;)V",
            "readObjectNoData()V",
            "writeReplace()Ljava/lang/Object;",
            "readResolve()Ljava/lang/Object;");

    private static final Set<String> AGENT_METHODS = Set.of(
            "premain(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
            "premain(Ljava/lang/instrument/Instrumentation;)V",
            "agentmain(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
            "agentmain(Ljava/lang/instrument/Instrumentation;)V");

    private final VaporConfig config;

    public Renamer(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Renamer";
    }

    @Override
    public boolean enabled() {
        return config.transformers.renameClasses
                || config.transformers.renameMethods
                || config.transformers.renameFields;
    }

    @Override
    public void run(Context context) {
        if (context.resource("module-info.class") != null) {
            System.err.println("  warn: module-info.class present — keeping all names (modular jar)");
            return;
        }
        try {
            rename(context);
        } catch (RuntimeException | Error e) {
            if (!config.failsafe) {
                throw e;
            }
            System.err.println("  warn: renaming failed, jar left un-renamed: " + e);
        }
    }

    private void rename(Context context) {
        List<JClass> classes = new ArrayList<>(context.classes());
        Mappings mappings = context.mappings();

        // ---- keep rules: config globs + @VaporKeep (class / method / field) ----
        Set<String> keptClasses = new HashSet<>();
        Set<String> anchoredMethodKeys = new HashSet<>();
        Set<String> anchoredFieldKeys = new HashSet<>();
        List<Pattern> keepPatterns = new ArrayList<>();
        for (String pattern : config.keep) {
            keepPatterns.add(glob(pattern));
        }
        for (JClass clazz : classes) {
            String dotted = clazz.name().replace('/', '.');
            boolean kept = keepPatterns.stream().anyMatch(p -> p.matcher(dotted).matches())
                    || hasKeepAnnotation(clazz.node().visibleAnnotations)
                    || hasKeepAnnotation(clazz.node().invisibleAnnotations);
            if (kept) {
                keptClasses.add(clazz.name());
                for (MethodNode method : clazz.node().methods) {
                    anchoredMethodKeys.add(key(method));
                }
                for (FieldNode field : clazz.node().fields) {
                    anchoredFieldKeys.add(key(field));
                }
            }
            for (MethodNode method : clazz.node().methods) {
                if (hasKeepAnnotation(method.visibleAnnotations) || hasKeepAnnotation(method.invisibleAnnotations)) {
                    anchoredMethodKeys.add(key(method));
                }
            }
            for (FieldNode field : clazz.node().fields) {
                if (hasKeepAnnotation(field.visibleAnnotations) || hasKeepAnnotation(field.invisibleAnnotations)) {
                    anchoredFieldKeys.add(key(field));
                }
            }
        }

        // ---- automatic anchors: names the runtime/dispatch resolves by name ----
        for (JClass clazz : classes) {
            ClassNode node = clazz.node();
            boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
            boolean isAnnotation = (node.access & Opcodes.ACC_ANNOTATION) != 0;
            boolean isSerializable = isSerializable(context, clazz);
            for (MethodNode method : node.methods) {
                if (method.name.startsWith("<")) {
                    continue;   // constructors / static initializers are never renamed
                }
                String methodKey = key(method);
                if (isEnum && (isEnumValuesOrValueOf(clazz, method))) {
                    anchoredMethodKeys.add(methodKey);
                }
                if (method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V")) {
                    anchoredMethodKeys.add(methodKey);
                }
                if (AGENT_METHODS.contains(methodKey)) {
                    anchoredMethodKeys.add(methodKey);
                }
                if (isAnnotation) {
                    anchoredMethodKeys.add(methodKey);   // annotation element names are resolved by name
                }
                if (isSerializable && SERIALIZATION_METHODS.contains(methodKey)) {
                    anchoredMethodKeys.add(methodKey);
                }
            }
            for (FieldNode field : node.fields) {
                if (isEnum && (field.access & Opcodes.ACC_ENUM) != 0) {
                    anchoredFieldKeys.add(key(field));   // constant names are looked up by valueOf/Enum.valueOf
                }
            }
            if (isSerializable) {
                anchoredFieldKeys.add("serialVersionUIDJ");   // ObjectStreamClass looks the field up by name
            }
            if (clazz.isRecord()) {
                // v1: record component names (field + accessor + Record attribute) are preserved.
                for (RecordComponentNode component : node.recordComponents) {
                    anchoredFieldKeys.add(component.name + component.descriptor);
                    anchoredMethodKeys.add(component.name + "()" + component.descriptor);
                }
            }
        }

        // ---- anchors from library supertypes: a renamed member must not
        // accidentally override or hide a library member, so keep the key ----
        for (JClass clazz : classes) {
            ClassNode node = clazz.node();
            if (node.methods.isEmpty() && node.fields.isEmpty()) {
                continue;
            }
            for (String superName : transitiveSupers(context, clazz)) {
                if (context.getClass(superName) != null) {
                    continue;   // jar-internal override chains rename as one unit via global keys
                }
                LibraryLoader.MemberInfo lib = context.libraries().members(superName);
                if (lib == null) {
                    continue;   // missing library: cannot prove a clash, best effort
                }
                for (MethodNode method : node.methods) {
                    if (!method.name.startsWith("<") && lib.declaresMethod(key(method))) {
                        anchoredMethodKeys.add(key(method));
                    }
                }
                for (FieldNode field : node.fields) {
                    if (lib.declaresField(key(field))) {
                        anchoredFieldKeys.add(key(field));
                    }
                }
            }
        }

        for (JClass clazz : classes) {
            stripKeepAnnotations(clazz.node());
        }

        // ---- anchors from member references: a reference whose resolution
        // lands on a library member (e.g. name() inherited from java/lang/Enum,
        // invoked with a jar-class owner) must keep that member name ----
        anchorFromReferences(context, classes, anchoredMethodKeys, anchoredFieldKeys);

        // ---- class names ----
        if (config.transformers.renameClasses) {
            assignClassNames(context, classes, keptClasses, mappings);
        }

        // ---- member names ----
        // Generated names avoid every name already used anywhere in the jar or
        // in reachable library supertypes, so a rename can never create an
        // accidental override/hide relationship.
        if (config.transformers.renameMethods) {
            Set<String> reserved = new HashSet<>();
            for (JClass clazz : classes) {
                for (MethodNode method : clazz.node().methods) {
                    reserved.add(method.name);
                }
            }
            addLibraryNames(context, classes, reserved, true);
            Dictionary dict = new Dictionary(config.seed, reserved);
            Set<String> done = new HashSet<>();
            for (JClass clazz : classes) {
                for (MethodNode method : clazz.node().methods) {
                    String methodKey = key(method);
                    if (method.name.startsWith("<") || anchoredMethodKeys.contains(methodKey) || !done.add(methodKey)) {
                        continue;
                    }
                    String newName = dict.next();
                    mappings.mapMethodKey(method.name, method.desc, newName);
                }
            }
        }
        if (config.transformers.renameFields) {
            Set<String> reserved = new HashSet<>();
            for (JClass clazz : classes) {
                for (FieldNode field : clazz.node().fields) {
                    reserved.add(field.name);
                }
            }
            addLibraryNames(context, classes, reserved, false);
            Dictionary dict = new Dictionary(config.seed, reserved);
            Set<String> done = new HashSet<>();
            for (JClass clazz : classes) {
                for (FieldNode field : clazz.node().fields) {
                    String fieldKey = key(field);
                    if (anchoredFieldKeys.contains(fieldKey) || !done.add(fieldKey)) {
                        continue;
                    }
                    String newName = dict.next();
                    mappings.mapFieldKey(field.name, field.desc, newName);
                }
            }
        }

        // ---- export data for the mapping file (original names, before remap) ----
        for (JClass clazz : classes) {
            List<Mappings.MemberMapping> members = new ArrayList<>();
            for (FieldNode field : clazz.node().fields) {
                members.add(new Mappings.MemberMapping(false, field.name, field.desc,
                        mappings.mappedField(field.name, field.desc)));
            }
            for (MethodNode method : clazz.node().methods) {
                if (method.name.startsWith("<")) {
                    continue;
                }
                members.add(new Mappings.MemberMapping(true, method.name, method.desc,
                        mappings.mappedMethod(method.name, method.desc)));
            }
            mappings.addExport(new Mappings.ClassMapping(clazz.name(), mappings.mappedClass(clazz.name()), members));
        }

        // ---- apply: compute all remapped copies first, then commit in one go ----
        RemapperImpl remapper = new RemapperImpl(mappings);
        List<ClassNode> outputs = new ArrayList<>(classes.size());
        for (JClass clazz : classes) {
            ClassNode output = new ClassNode();
            clazz.node().accept(new VaporClassRemapper(output, remapper));
            outputs.add(output);
        }
        for (int i = 0; i < classes.size(); i++) {
            classes.get(i).setNode(outputs.get(i));
        }
        context.rekeyClasses();
    }

    /** Plan class names: kept classes stay, the rest is flattened, inner classes follow their outer. */
    private void assignClassNames(Context context, List<JClass> classes,
                                  Set<String> keptClasses, Mappings mappings) {
        Set<String> reserved = new HashSet<>();
        for (JClass clazz : classes) {
            reserved.add(clazz.name());
        }
        Dictionary dict = new Dictionary(config.seed, reserved);
        Map<JClass, String> newNames = new LinkedHashMap<>();

        for (JClass clazz : classes) {
            if (!keptClasses.contains(clazz.name()) && findOuter(context, clazz) == null) {
                newNames.put(clazz, dict.next());
            }
        }

        // Inner classes depend on their outer's new name, which may itself be
        // assigned later in jar order — resolve until a fixed point.
        List<JClass> pending = new ArrayList<>();
        for (JClass clazz : classes) {
            if (!keptClasses.contains(clazz.name()) && findOuter(context, clazz) != null) {
                pending.add(clazz);
            }
        }
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (Iterator<JClass> it = pending.iterator(); it.hasNext(); ) {
                JClass clazz = it.next();
                String outer = findOuter(context, clazz);
                String outerNew = null;
                if (keptClasses.contains(outer)) {
                    outerNew = outer;
                } else {
                    JClass outerClass = context.getClass(outer);
                    if (outerClass != null) {
                        outerNew = newNames.get(outerClass);
                    }
                }
                if (outerNew != null) {
                    String name;
                    do {
                        name = outerNew + "$" + dict.next();
                    } while (!dict.claim(name));
                    newNames.put(clazz, name);
                    it.remove();
                    progress = true;
                }
            }
        }
        for (JClass clazz : pending) {
            newNames.put(clazz, dict.next());   // broken metadata: fall back to a flat name
        }

        for (Map.Entry<JClass, String> entry : newNames.entrySet()) {
            mappings.mapClass(entry.getKey().name(), entry.getValue());
        }
    }

    /** Reserve every member name reachable through library supertypes (override/hide safety). */
    private static void addLibraryNames(Context context, List<JClass> classes,
                                        Set<String> reserved, boolean methods) {
        for (JClass clazz : classes) {
            for (String superName : transitiveSupers(context, clazz)) {
                if (context.getClass(superName) != null) {
                    continue;
                }
                LibraryLoader.MemberInfo lib = context.libraries().members(superName);
                if (lib != null) {
                    reserved.addAll(methods ? lib.methodNames() : lib.fieldNames());
                }
            }
        }
    }

    private static Set<String> transitiveSupers(Context context, JClass clazz) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (clazz.superName() != null) {
            queue.add(clazz.superName());
        }
        queue.addAll(clazz.interfaces());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name == null || !result.add(name)) {
                continue;
            }
            IHierarchy.ClassInfo info = context.hierarchy().getClassInfo(name);
            if (info == null) {
                continue;
            }
            if (info.superName() != null) {
                queue.add(info.superName());
            }
            Collections.addAll(queue, info.interfaces());
        }
        return result;
    }

    /** Scan method/field references (and method handles) and anchor keys that resolve to library members. */
    private static void anchorFromReferences(Context context, List<JClass> classes,
                                             Set<String> anchoredMethods, Set<String> anchoredFields) {
        Map<String, Boolean> resolution = new HashMap<>();
        for (JClass clazz : classes) {
            for (MethodNode method : clazz.node().methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call) {
                        anchorReference(context, resolution, anchoredMethods, anchoredFields,
                                call.owner, call.name, call.desc, false);
                    } else if (insn instanceof FieldInsnNode ref) {
                        anchorReference(context, resolution, anchoredMethods, anchoredFields,
                                ref.owner, ref.name, ref.desc, true);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle) {
                                anchorReference(context, resolution, anchoredMethods, anchoredFields,
                                        handle.getOwner(), handle.getName(), handle.getDesc(), isFieldHandle(handle));
                            }
                        }
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                        anchorReference(context, resolution, anchoredMethods, anchoredFields,
                                handle.getOwner(), handle.getName(), handle.getDesc(), isFieldHandle(handle));
                    }
                }
            }
        }
    }

    private static void anchorReference(Context context, Map<String, Boolean> resolution,
                                        Set<String> anchoredMethods, Set<String> anchoredFields,
                                        String owner, String name, String descriptor, boolean field) {
        if (owner.startsWith("[")) {
            return;   // array member (clone/length): nothing renamable
        }
        if (!field && name.startsWith("<")) {
            return;
        }
        if (resolvesInJar(context, resolution, owner, name + descriptor)) {
            return;
        }
        (field ? anchoredFields : anchoredMethods).add(name + descriptor);
    }

    /** True when the nearest declaration of {@code key} on {@code owner}'s hierarchy is a jar class. */
    private static boolean resolvesInJar(Context context, Map<String, Boolean> cache, String owner, String key) {
        String cacheKey = owner + '\u0000' + key;
        Boolean cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean result = resolvesInJarUncached(context, owner, key);
        cache.put(cacheKey, result);
        return result;
    }

    private static boolean resolvesInJarUncached(Context context, String owner, String key) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !seen.add(current)) {
                continue;
            }
            JClass jarClass = context.getClass(current);
            if (jarClass != null) {
                for (MethodNode method : jarClass.node().methods) {
                    if ((method.name + method.desc).equals(key)) {
                        return true;
                    }
                }
                for (FieldNode field : jarClass.node().fields) {
                    if ((field.name + field.desc).equals(key)) {
                        return true;
                    }
                }
                if (jarClass.superName() != null) {
                    queue.add(jarClass.superName());
                }
                queue.addAll(jarClass.interfaces());
                continue;
            }
            IHierarchy.ClassInfo info = context.hierarchy().getClassInfo(current);
            if (info == null) {
                continue;   // unresolvable branch: cannot prove a jar declaration
            }
            LibraryLoader.MemberInfo lib = context.libraries().members(current);
            if (lib != null && (lib.declaresMethod(key) || lib.declaresField(key))) {
                return false;   // declared in a library class: must not rename
            }
            if (info.superName() != null) {
                queue.add(info.superName());
            }
            Collections.addAll(queue, info.interfaces());
        }
        return false;   // unresolvable or nowhere-declared: keep conservatively
    }

    private static boolean isFieldHandle(Handle handle) {
        return switch (handle.getTag()) {
            case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> true;
            default -> false;
        };
    }

    private static boolean isSerializable(Context context, JClass clazz) {
        return context.hierarchy().isAssignableFrom(SERIALIZABLE, clazz.name());
    }

    private static boolean isEnumValuesOrValueOf(JClass clazz, MethodNode method) {
        String name = clazz.name();
        return (method.name.equals("values") && method.desc.equals("()[L" + name + ";"))
                || (method.name.equals("valueOf") && method.desc.equals("(Ljava/lang/String;)L" + name + ";"));
    }

    /**
     * Nearest enclosing class: InnerClasses / EnclosingMethod metadata first,
     * {@code Name$Part} prefix as a fallback for stripped metadata.
     */
    private static String findOuter(Context context, JClass clazz) {
        for (InnerClassNode inner : clazz.node().innerClasses) {
            if (inner.name.equals(clazz.name()) && inner.outerName != null) {
                return inner.outerName;
            }
        }
        if (clazz.node().outerClass != null) {
            return clazz.node().outerClass;   // set (with EnclosingMethod) for local/anonymous classes
        }
        int dollar = clazz.name().lastIndexOf('$');
        if (dollar > 0) {
            String prefix = clazz.name().substring(0, dollar);
            if (context.getClass(prefix) != null) {
                return prefix;
            }
        }
        return null;
    }

    private static void stripKeepAnnotations(ClassNode node) {
        removeKeepAnnotations(node.visibleAnnotations);
        removeKeepAnnotations(node.invisibleAnnotations);
        if (node.methods != null) {
            for (MethodNode method : node.methods) {
                removeKeepAnnotations(method.visibleAnnotations);
                removeKeepAnnotations(method.invisibleAnnotations);
            }
        }
        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                removeKeepAnnotations(field.visibleAnnotations);
                removeKeepAnnotations(field.invisibleAnnotations);
            }
        }
    }

    private static void removeKeepAnnotations(List<AnnotationNode> annotations) {
        if (annotations != null) {
            annotations.removeIf(annotation -> isKeepAnnotation(annotation.desc));
        }
    }

    private static boolean hasKeepAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (isKeepAnnotation(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    /** Matches by simple name, so the sample/app can define its own {@code @VaporKeep}. */
    private static boolean isKeepAnnotation(String descriptor) {
        if (descriptor == null || descriptor.length() < 2) {
            return false;
        }
        int end = descriptor.endsWith(";") ? descriptor.length() - 1 : descriptor.length();
        int slash = descriptor.lastIndexOf('/', end - 1);
        int start = slash < 0 ? 1 : slash + 1;
        return descriptor.regionMatches(start, KEEP_ANNOTATION, 0, end - start)
                && end - start == KEEP_ANNOTATION.length();
    }

    private static String key(MethodNode method) {
        return method.name + method.desc;
    }

    private static String key(FieldNode field) {
        return field.name + field.desc;
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
