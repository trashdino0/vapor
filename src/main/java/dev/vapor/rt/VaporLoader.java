package dev.vapor.rt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.zip.CRC32;

/**
 * Runtime bootstrap for encrypted class bodies.
 *
 * <p>This class is intentionally dependency-free and Java 8 compatible. The
 * obfuscator embeds bytecode generated from this source, then patches the
 * {@code __VAPOR_MAIN__} placeholder and the {@link #KEY} constant for the
 * current build. Encrypted class entries carry a {@code VP01} header (CRC32 plus
 * ciphertext) or {@code VP00} (ciphertext only); {@code VP01} bodies are verified
 * before definition. Plain class resources from libraries are ignored by
 * {@link #findClass} and fall back to the parent loader.</p>
 */
public final class VaporLoader extends ClassLoader {

    /**
     * Placeholder replaced by {@code ClassProtector} with the per-build stream key.
     * Keep the value a compile-time constant so javac inlines it into {@link #decrypt}.
     */
    private static final long KEY = 0x1122334455667788L;

    private final String mainClass;

    public VaporLoader(String mainClass) {
        super(VaporLoader.class.getClassLoader());
        this.mainClass = mainClass;
    }

    public static void main(String[] args) throws Exception {
        VaporLoader loader = new VaporLoader("__VAPOR_MAIN__");
        Thread thread = Thread.currentThread();
        thread.setContextClassLoader(loader);
        Class<?> main = Class.forName(loader.mainClass, true, loader);
        Method method = main.getMethod("main", new Class<?>[]{String[].class});
        try {
            method.invoke(null, new Object[]{args});
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isPlatform(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missing) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isPlatform(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("com.sun.")
                || name.equals("dev.vapor.rt.VaporLoader");
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] data = readEncrypted(name);
        if (data == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, data, 0, data.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name.endsWith(".class")) {
            String binary = name.substring(0, name.length() - 6).replace('/', '.');
            byte[] data = readEncrypted(binary);
            if (data != null) {
                return new ByteArrayInputStream(data);
            }
        }
        return super.getResourceAsStream(name);
    }

    private static byte[] readEncrypted(String name) {
        StringBuilder path = new StringBuilder(name.length() + 8);
        path.append(name.replace('.', '/')).append(".class");
        InputStream in = ClassLoader.getSystemResourceAsStream(path.toString());
        if (in == null) {
            return null;
        }
        try {
            byte[] raw = readAll(in);
            if (!isEncrypted(raw)) {
                return null;
            }
            return decrypt(raw, name);
        } catch (SecurityException tampered) {
            throw tampered;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isEncrypted(byte[] raw) {
        return raw.length >= 4 && raw[0] == 'V' && raw[1] == 'P'
                && raw[2] == '0' && (raw[3] == '0' || raw[3] == '1');
    }

    private static byte[] decrypt(byte[] raw, String name) {
        boolean checksum = raw[3] == '1';
        int header = checksum ? 8 : 4;
        byte[] data = new byte[raw.length - header];
        long state = KEY ^ (((long) name.hashCode()) * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < data.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            data[i] = (byte) (raw[i + header] ^ (byte) (state >>> 56));
        }
        if (checksum) {
            int expected = ((raw[4] & 0xFF) << 24)
                    | ((raw[5] & 0xFF) << 16)
                    | ((raw[6] & 0xFF) << 8)
                    | (raw[7] & 0xFF);
            CRC32 crc = new CRC32();
            crc.update(data);
            if ((int) crc.getValue() != expected) {
                throw new SecurityException("vapor: class integrity check failed");
            }
        }
        return data;
    }
}
