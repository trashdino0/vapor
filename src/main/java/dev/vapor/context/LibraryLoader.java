package dev.vapor.context;

import dev.vapor.context.hierarchy.IHierarchy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves class headers for classes that are not part of the input jar:
 * user-supplied library jars/directories ({@code -lib}) and the JDK itself
 * (via the {@code jrt:/} filesystem, so no rt.jar/jmods handling is needed).
 */
public final class LibraryLoader implements Closeable {

    private record LibRef(ZipFile zip, String entryName) {
    }

    /** Declared member keys of a class: {@code name+descriptor} plus bare names. */
    public record MemberInfo(Set<String> methodKeys, Set<String> methodNames,
                             Set<String> fieldKeys, Set<String> fieldNames) {

        public static final MemberInfo EMPTY = new MemberInfo(Set.of(), Set.of(), Set.of(), Set.of());

        public boolean declaresMethod(String key) {
            return methodKeys.contains(key);
        }

        public boolean declaresField(String key) {
            return fieldKeys.contains(key);
        }
    }

    private final List<ZipFile> openZips = new ArrayList<>();
    private final Map<String, Object> index = new HashMap<>();   // internal name -> LibRef | Path
    private final Map<String, IHierarchy.ClassInfo> cache = new HashMap<>();
    private final Map<String, MemberInfo> memberCache = new HashMap<>();
    private final Set<String> misses = new HashSet<>();
    private final Map<String, List<String>> packageModules = new HashMap<>();

    private static FileSystem jrt;

    public LibraryLoader(List<Path> paths) throws IOException {
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                indexDirectory(path);
            } else {
                indexJar(path);
            }
        }
    }

    private void indexDirectory(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                index.putIfAbsent(relative.substring(0, relative.length() - ".class".length()), file);
            }
        }
    }

    private void indexJar(Path jar) throws IOException {
        ZipFile zip = new ZipFile(jar.toFile());
        openZips.add(zip);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                index.putIfAbsent(name.substring(0, name.length() - ".class".length()), new LibRef(zip, name));
            }
        }
    }

    /** @return class header info, or {@code null} when the class cannot be found */
    public IHierarchy.ClassInfo classInfo(String internalName) {
        IHierarchy.ClassInfo cached = cache.get(internalName);
        if (cached != null) {
            return cached;
        }
        if (misses.contains(internalName)) {
            return null;
        }
        byte[] bytes = bytesOf(internalName);
        IHierarchy.ClassInfo info = bytes == null ? null : parse(bytes);
        if (info == null) {
            misses.add(internalName);
        } else {
            cache.put(internalName, info);
        }
        return info;
    }

    /**
     * Declared members of a library class (methods and fields as
     * {@code name+descriptor} keys, plus bare names). Used by the renamer to
     * anchor names that must stay in sync with the library.
     *
     * @return member info, or {@code null} when the class cannot be found
     */
    public MemberInfo members(String internalName) {
        MemberInfo cached = memberCache.get(internalName);
        if (cached != null) {
            return cached;
        }
        if (misses.contains(internalName)) {
            return null;
        }
        byte[] bytes = bytesOf(internalName);
        if (bytes == null) {
            misses.add(internalName);
            return null;
        }
        MemberInfo info = parseMembers(bytes);
        memberCache.put(internalName, info);
        return info;
    }

    private byte[] bytesOf(String internalName) {
        Object ref = index.get(internalName);
        if (ref != null) {
            try {
                if (ref instanceof LibRef lib) {
                    try (InputStream in = lib.zip().getInputStream(lib.zip().getEntry(lib.entryName()))) {
                        return in.readAllBytes();
                    }
                }
                return Files.readAllBytes((Path) ref);
            } catch (Exception e) {
                return null;   // unreadable/corrupt library class — treat as unknown
            }
        }
        return fromJdkBytes(internalName);
    }

    private byte[] fromJdkBytes(String internalName) {
        int slash = internalName.lastIndexOf('/');
        if (slash < 0) {
            return null;   // default package: not reachable via /packages
        }
        String packageName = internalName.substring(0, slash).replace('/', '.');
        List<String> modules = packageModules.get(packageName);
        if (modules == null) {
            modules = new ArrayList<>();
            try {
                Path packages = jrt().getPath("/packages/" + packageName);
                if (Files.isDirectory(packages)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(packages)) {
                        for (Path module : stream) {
                            modules.add(module.getFileName().toString());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            Collections.sort(modules);   // deterministic module preference
            packageModules.put(packageName, modules);
        }
        for (String module : modules) {
            try {
                Path classFile = jrt().getPath("/modules/" + module + "/" + internalName + ".class");
                if (Files.isRegularFile(classFile)) {
                    return Files.readAllBytes(classFile);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static IHierarchy.ClassInfo parse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        boolean isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
        return new IHierarchy.ClassInfo(reader.getClassName(), reader.getSuperName(),
                reader.getInterfaces(), isInterface);
    }

    private static MemberInfo parseMembers(byte[] bytes) {
        Set<String> methodKeys = new HashSet<>();
        Set<String> methodNames = new HashSet<>();
        Set<String> fieldKeys = new HashSet<>();
        Set<String> fieldNames = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methodKeys.add(name + descriptor);
                methodNames.add(name);
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                fieldKeys.add(name + descriptor);
                fieldNames.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new MemberInfo(methodKeys, methodNames, fieldKeys, fieldNames);
    }

    private static synchronized FileSystem jrt() {
        if (jrt == null) {
            URI uri = URI.create("jrt:/");
            try {
                jrt = FileSystems.getFileSystem(uri);
            } catch (FileSystemNotFoundException e) {
                try {
                    jrt = FileSystems.newFileSystem(uri, Map.of());
                } catch (Exception inner) {
                    throw new IllegalStateException("cannot open jrt:/ filesystem", inner);
                }
            }
        }
        return jrt;
    }

    @Override
    public void close() throws IOException {
        for (ZipFile zip : openZips) {
            zip.close();
        }
        openZips.clear();
    }
}
