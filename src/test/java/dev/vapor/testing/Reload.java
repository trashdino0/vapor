package dev.vapor.testing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;

/**
 * Test helpers: read a compiled fixture's bytes off the classpath, transform the
 * ClassNode, and load the result under the same name (shadowing the parent copy)
 * so behavior can be compared one-to-one.
 */
public final class Reload {

    private Reload() {
    }

    public static ClassNode read(Class<?> fixture) throws IOException {
        String resource = "/" + fixture.getName().replace('.', '/') + ".class";
        try (InputStream in = fixture.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class resource missing: " + resource);
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    public static byte[] write(ClassNode node) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** Loads transformed bytes under the fixture's own binary name. */
    public static Class<?> define(Class<?> fixture, byte[] bytes) throws ClassNotFoundException {
        return define(fixture.getName(), bytes);
    }

    /** Loads transformed bytes under an explicit binary name (shadowing any parent copy). */
    public static Class<?> define(String binaryName, byte[] bytes) throws ClassNotFoundException {
        ClassLoader parent = Reload.class.getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (requested.equals(binaryName)) {
                    Class<?> loaded = findLoadedClass(requested);
                    return loaded != null ? loaded : defineClass(requested, bytes, 0, bytes.length);
                }
                return super.loadClass(requested, resolve);
            }
        };
        return loader.loadClass(binaryName);
    }
}
