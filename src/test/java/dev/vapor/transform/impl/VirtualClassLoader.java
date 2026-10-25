package dev.vapor.transform.impl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines a fixed set of class files under one loader, so a transformed owner
 * and its generated helper resolve each other while shadowing the originals.
 */
final class VirtualClassLoader extends ClassLoader {

    private final Map<String, byte[]> definitions;

    VirtualClassLoader(Map<String, byte[]> definitions) {
        super(VirtualClassLoader.class.getClassLoader());
        this.definitions = new LinkedHashMap<>(definitions);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (definitions.containsKey(name)) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = definitions.get(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
        return super.loadClass(name, resolve);
    }
}
