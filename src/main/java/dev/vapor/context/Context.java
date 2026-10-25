package dev.vapor.context;

import dev.vapor.context.hierarchy.SimpleHierarchy;
import dev.vapor.naming.Mappings;
import dev.vapor.tree.JClass;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The world model shared by all pipeline passes: jar classes, non-class
 * resources (in original archive order), the class hierarchy and the external
 * classpath. Passes mutate the context; the final write pass replays it.
 */
public final class Context implements Closeable {

    private final Path input;
    private final Path output;
    private final LibraryLoader libraries;
    private final BooleanSupplier cancelled;

    private final Map<String, JClass> classes = new LinkedHashMap<>();
    private final List<JarEntry> entries = new ArrayList<>();
    private final Map<String, JarEntry.Resource> resources = new HashMap<>();
    private final Mappings mappings = new Mappings();
    private final Set<String> artificialMethods = new HashSet<>();

    private SimpleHierarchy hierarchy;

    public Context(Path input, Path output, List<Path> libraryPaths) throws IOException {
        this(input, output, libraryPaths, () -> false);
    }

    /**
     * @param cancelled polled by passes at safe points; the desktop UI flips it
     *                  when the user presses Cancel. A cancelled run throws
     *                  {@link CancellationException}, which must not be treated
     *                  as a transform failure.
     */
    public Context(Path input, Path output, List<Path> libraryPaths, BooleanSupplier cancelled)
            throws IOException {
        this.input = input;
        this.output = output;
        this.libraries = new LibraryLoader(libraryPaths);
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    /** Abort the current run at the next safe point. */
    public void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("vapor: obfuscation cancelled");
        }
    }

    /**
     * Register a jar entry. Class entries are indexed by (internal) class name;
     * resources are additionally indexed by entry name for fast lookup.
     */
    public void addEntry(JarEntry entry) {
        entries.add(entry);
        if (entry instanceof JarEntry.Resource resource) {
            resources.put(resource.name(), resource);
        } else if (entry instanceof JarEntry.ClassEntry classEntry) {
            classes.put(classEntry.clazz().name(), classEntry.clazz());
        }
    }

    public JClass getClass(String internalName) {
        return classes.get(internalName);
    }

    public Collection<JClass> classes() {
        return classes.values();
    }

    public List<JarEntry> entries() {
        return entries;
    }

    public JarEntry.Resource resource(String name) {
        return resources.get(name);
    }

    public Map<String, JarEntry.Resource> resources() {
        return resources;
    }

    public LibraryLoader libraries() {
        return libraries;
    }

    /** Rename mapping, filled by the renamer and consumed by fix-up / export passes. */
    public Mappings mappings() {
        return mappings;
    }

    /**
     * Record a method this run generated itself (string decryptors, …). Noise
     * transforms ({@code DeadCode}, {@code FlowShuffle}) skip such methods —
     * they are on hot paths and already artificial.
     */
    public void addArtificialMethod(String owner, String name, String desc) {
        artificialMethods.add(owner + '|' + name + desc);
    }

    public boolean isArtificialMethod(String owner, String name, String desc) {
        return artificialMethods.contains(owner + '|' + name + desc);
    }

    /** Re-index classes under their current (possibly renamed) names and drop cached hierarchy data. */
    public void rekeyClasses() {
        classes.clear();
        for (JarEntry entry : entries) {
            if (entry instanceof JarEntry.ClassEntry classEntry) {
                classes.put(classEntry.clazz().name(), classEntry.clazz());
            }
        }
        hierarchy = null;
    }

    /** Swap one entry for a rewritten copy, keeping entry order and the resource index consistent. */
    public void replaceEntry(JarEntry entry, JarEntry replacement) {
        int index = entries.indexOf(entry);
        if (index >= 0) {
            entries.set(index, replacement);
        }
        if (entry instanceof JarEntry.Resource resource) {
            resources.remove(resource.name());
        }
        if (replacement instanceof JarEntry.Resource resource) {
            resources.put(resource.name(), resource);
        }
    }

    public SimpleHierarchy hierarchy() {
        if (hierarchy == null) {
            hierarchy = new SimpleHierarchy(this);
        }
        return hierarchy;
    }

    public Path input() {
        return input;
    }

    public Path output() {
        return output;
    }

    @Override
    public void close() throws IOException {
        libraries.close();
    }
}
