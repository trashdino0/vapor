package dev.vapor.protect;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.rt.VaporLoader;
import dev.vapor.util.Mix;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * Optional runtime barrier for executable jars.
 *
 * <p>Class entries are serialized normally, prefixed with a {@code VP01} marker
 * (CRC32 followed by ciphertext) or {@code VP00} (ciphertext only) and encrypted
 * with a per-class stream derived from a build key and the class name. A small
 * Java 8-compatible {@link VaporLoader} decrypts classes on demand. The key is
 * embedded in the loader (obfuscation, not secret-key cryptography); the goal is
 * to defeat static jar/decompiler inspection and detect tampering, not to resist
 * a process dump.</p>
 */
public final class ClassProtector {

    private static final byte[] MAGIC = {'V', 'P', '0', '1'};
    private static final long KEY_PLACEHOLDER = 0x1122334455667788L;
    private static final String MAIN_PLACEHOLDER = "__VAPOR_MAIN__";
    private static final String DEFAULT_LOADER = "dev.vapor.rt.VaporLoader";

    private ClassProtector() {
    }

    public static Plan prepare(Context context, VaporConfig config) throws IOException {
        if (config.protection == null || !config.protection.encryptClasses) {
            return null;
        }
        context.checkCancelled();

        JarEntry.Resource manifestEntry = context.resource("META-INF/MANIFEST.MF");
        if (manifestEntry == null) {
            warn("skipped class encryption: no META-INF/MANIFEST.MF");
            return null;
        }
        Manifest manifest = new Manifest(new ByteArrayInputStream(manifestEntry.bytes()));
        Attributes attributes = manifest.getMainAttributes();
        String main = attributes.getValue(Attributes.Name.MAIN_CLASS);
        if (main == null || main.isBlank()) {
            warn("skipped class encryption: manifest has no Main-Class");
            return null;
        }
        main = main.trim();
        if (attributes.getValue("Agent-Class") != null
                || attributes.getValue("Launcher-Agent-Class") != null) {
            warn("skipped class encryption: agent manifests need an unencrypted agent class");
            return null;
        }
        for (JarEntry entry : context.entries()) {
            String name = entryName(entry);
            if (name.startsWith("META-INF/versions/")) {
                warn("skipped class encryption: multi-release jars are not supported yet");
                return null;
            }
            if (name.startsWith("META-INF/") && isSignature(name)) {
                warn("skipped class encryption: signed jars would be invalidated");
                return null;
            }
        }
        if (context.getClass("module-info") != null) {
            warn("skipped class encryption: modular jars are not supported yet");
            return null;
        }

        String loaderDotted = config.protection.loaderClass;
        if (loaderDotted == null || loaderDotted.isBlank()) {
            loaderDotted = DEFAULT_LOADER;
        }
        loaderDotted = loaderDotted.trim();
        if (!isBinaryName(loaderDotted)) {
            throw new IllegalArgumentException("invalid protection.loaderClass: " + loaderDotted);
        }
        String loaderInternal = loaderDotted.replace('.', '/');
        if (context.getClass(loaderInternal) != null) {
            throw new IllegalArgumentException("loader class collides with input class: " + loaderDotted);
        }
        String mainInternal = main.replace('.', '/');
        if (context.getClass(mainInternal) == null) {
            warn("skipped class encryption: Main-Class not present in input: " + main);
            return null;
        }

        long key = deriveKey(config.seed);
        boolean verifyIntegrity = config.protection.verifyIntegrity;
        byte[] loader = loadLoaderBytes();
        loader = patchLoader(loader, main, key);
        byte[] manifestBytes = patchManifest(manifest, loaderDotted, main);
        System.out.println("[protect] encrypted classes, main=" + main + ", loader=" + loaderDotted);
        return new Plan(loaderInternal, loaderDotted, main, key, loader, manifestBytes, verifyIntegrity);
    }

    public static byte[] encrypt(byte[] plain, String internalName, long key) {
        return encrypt(plain, internalName, key, true);
    }

    public static byte[] encrypt(byte[] plain, String internalName, long key, boolean verifyIntegrity) {
        int header = verifyIntegrity ? 8 : 4;
        byte[] out = new byte[plain.length + header];
        out[0] = MAGIC[0];
        out[1] = MAGIC[1];
        out[2] = MAGIC[2];
        out[3] = verifyIntegrity ? (byte) '1' : (byte) '0';
        if (verifyIntegrity) {
            CRC32 crc = new CRC32();
            crc.update(plain);
            int value = (int) crc.getValue();
            out[4] = (byte) (value >>> 24);
            out[5] = (byte) (value >>> 16);
            out[6] = (byte) (value >>> 8);
            out[7] = (byte) value;
        }
        long state = key ^ (((long) internalName.hashCode()) * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < plain.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            out[i + header] = (byte) (plain[i] ^ (byte) (state >>> 56));
        }
        return out;
    }

    private static byte[] patchLoader(byte[] source, String main, long key) {
        ClassNode node = new ClassNode();
        new ClassReader(source).accept(node, 0);
        boolean mainPatched = false;
        boolean keyPatched = false;
        for (int i = 0; i < node.methods.size(); i++) {
            for (AbstractInsnNode insn = node.methods.get(i).instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc) {
                    if (MAIN_PLACEHOLDER.equals(ldc.cst)) {
                        ldc.cst = main;
                        mainPatched = true;
                    } else if (Long.valueOf(KEY_PLACEHOLDER).equals(ldc.cst)) {
                        ldc.cst = key;
                        keyPatched = true;
                    }
                }
            }
        }
        for (FieldNode field : node.fields) {
            if ("KEY".equals(field.name)) {
                field.value = key;
            }
        }
        if (!mainPatched || !keyPatched) {
            throw new IllegalStateException("embedded VaporLoader is missing its patch placeholders");
        }
        node.version = Opcodes.V1_8;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchManifest(Manifest original, String loaderDotted, String main) throws IOException {
        Manifest copy = new Manifest(original);
        Attributes attributes = copy.getMainAttributes();
        attributes.put(Attributes.Name.MAIN_CLASS, loaderDotted);
        attributes.putValue("Vapor-Main-Class", main);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy.write(out);
        return out.toByteArray();
    }

    private static long deriveKey(long seed) {
        long high = Integer.toUnsignedLong(Mix.of(seed, "vapor", "class-key", 0));
        long low = Integer.toUnsignedLong(Mix.of(seed, "vapor", "class-key", 1));
        long key = (high << 32) | low;
        return key == 0L ? 0x5A17C0DE9E3779B9L : key;
    }

    private static byte[] loadLoaderBytes() throws IOException {
        try (InputStream in = VaporLoader.class.getResourceAsStream("VaporLoader.class")) {
            if (in == null) {
                throw new IllegalStateException("VaporLoader.class is missing from the obfuscator resource path");
            }
            return in.readAllBytes();
        }
    }

    private static boolean isBinaryName(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String entryName(JarEntry entry) {
        if (entry instanceof JarEntry.Resource resource) {
            return resource.name();
        }
        return ((JarEntry.ClassEntry) entry).clazz().name() + ".class";
    }

    private static boolean isSignature(String name) {
        return name.endsWith(".SF") || name.endsWith(".RSA")
                || name.endsWith(".DSA") || name.endsWith(".EC");
    }

    private static void warn(String message) {
        System.out.println("[protect] " + message);
    }

    public record Plan(String loaderInternalName, String loaderDottedName, String mainClass,
                       long key, byte[] loaderBytes, byte[] manifestBytes, boolean verifyIntegrity) {
    }
}
