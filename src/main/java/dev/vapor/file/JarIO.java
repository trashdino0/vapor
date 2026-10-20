package dev.vapor.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Low-level jar reading/writing.
 *
 * <p>Phase 0 keeps every entry as raw bytes and preserves archive order
 * (the manifest stays wherever it was in the input). Later phases parse
 * class entries into ASM trees and rewrite references, while non-class
 * entries keep flowing through as bytes unless a resource handler touches
 * them (manifest Main-Class, META-INF/services, ...).</p>
 *
 * <p>Reads through {@link ZipFile} (central directory) rather than
 * {@link java.util.zip.ZipInputStream} (sequential local headers): streaming
 * readers silently stop early on archives whose local headers were laid out
 * unusually by another writer (e.g. .NET {@code ZipArchive} rewrites), while
 * the central directory is authoritative. Unlike
 * {@link java.util.jar.JarInputStream}, {@code ZipFile} does not swallow the
 * MANIFEST.MF entry.</p>
 */
public final class JarIO {

    private JarIO() {
    }

    /** A single jar entry, in original archive order. */
    public record Entry(String name, boolean directory, byte[] bytes) {
    }

    /** Full contents of a jar, preserving entry order. */
    public record JarContents(List<Entry> entries) {
    }

    public static JarContents read(Path jar) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> e = zipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                byte[] bytes = new byte[0];
                if (!entry.isDirectory()) {
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                }
                entries.add(new Entry(entry.getName(), entry.isDirectory(), bytes));
            }
        }
        return new JarContents(List.copyOf(entries));
    }

    public static void write(Path jar, JarContents contents) throws IOException {
        Path parent = jar.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream fileOut = Files.newOutputStream(jar);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (Entry entry : contents.entries()) {
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setTime(0L);   // deterministic output (stores 1980-01-01, DOS epoch)
                zipOut.putNextEntry(zipEntry);
                if (!entry.directory()) {
                    zipOut.write(entry.bytes());
                }
                zipOut.closeEntry();
            }
        }
    }
}
