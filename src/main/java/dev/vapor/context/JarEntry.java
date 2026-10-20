package dev.vapor.context;

import dev.vapor.tree.JClass;

/**
 * One entry of the input jar, replayed in original order when writing output.
 *
 * <p>Class entries hold a {@link JClass} reference rather than a name, so later
 * phases (renaming) automatically write the class under its new name; resource
 * entries keep their raw name and bytes until a resource handler rewrites them.</p>
 */
public sealed interface JarEntry {

    /** A non-class entry (manifest, services file, directory, ...), preserved as bytes. */
    record Resource(String name, byte[] bytes) implements JarEntry {

        public boolean directory() {
            return name.endsWith("/");
        }
    }

    /** A class file, parsed into an ASM tree. */
    record ClassEntry(JClass clazz) implements JarEntry {
    }
}
