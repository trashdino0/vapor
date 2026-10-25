package dev.vapor.protect;

import dev.vapor.Obfuscator;
import dev.vapor.config.VaporConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box acceptance test for {@code protection.encryptClasses}.
 *
 * <p>Builds a real executable jar with javac, obfuscates it in-process through
 * {@link Obfuscator#run}, and then inspects and executes the result. The test
 * deliberately does not call {@link ClassProtector} internals so it can catch a
 * loader/entry mismatch that unit tests would miss.</p>
 */
class ClassEncryptionAcceptanceTest {

    private static final String MAIN_CLASS = "fixture.Main";
    private static final String LOADER_CLASS = "dev.vapor.rt.VaporLoader";
    private static final String LOADER_ENTRY = LOADER_CLASS.replace('.', '/') + ".class";
    private static final long SEED = 0x5EED_1234L;

    private static final String MAIN_SOURCE = """
            package fixture;

            public final class Main {
                public static void main(String[] args) {
                    int total = 0;
                    for (String arg : args) {
                        total += Helper.weight(arg);
                    }
                    System.out.println("greeting=" + Helper.greeting("vapor"));
                    System.out.println("total=" + total);
                    System.out.println("args=" + args.length);
                    System.exit(7);
                }
            }
            """;

    private static final String HELPER_SOURCE = """
            package fixture;

            final class Helper {
                static String greeting(String name) {
                    return "[" + name.toUpperCase() + "]";
                }

                static int weight(String name) {
                    int hash = 7;
                    for (int i = 0; i < name.length(); i++) {
                        hash = hash * 31 + name.charAt(i);
                    }
                    return hash & 1023;
                }
            }
            """;

    @TempDir
    static Path tempDir;

    private static Path inputJar;
    private static Path protectedJar;

    @BeforeAll
    static void buildAndObfuscateFixture() throws Exception {
        inputJar = buildExecutableJar(tempDir);
        protectedJar = tempDir.resolve("protected.jar");
        obfuscateWithClassEncryption(inputJar, protectedJar, SEED);
    }

    @Test
    void encryptsClassEntriesAndInstallsJava8Loader() throws Exception {
        Map<String, byte[]> originalClasses = readClassEntries(inputJar);
        assertTrue(originalClasses.containsKey("fixture/Main.class"), "fixture main class missing from input");
        assertTrue(originalClasses.containsKey("fixture/Helper.class"), "fixture helper class missing from input");

        int encryptedEntries = 0;
        boolean loaderSeen = false;
        try (JarFile out = new JarFile(protectedJar.toFile())) {
            Manifest manifest = out.getManifest();
            assertNotNull(manifest, "protected jar has no manifest");
            Attributes attributes = manifest.getMainAttributes();
            assertEquals(LOADER_CLASS, attributes.getValue(Attributes.Name.MAIN_CLASS),
                    "Main-Class must point at the bootstrap loader");
            String vaporMain = attributes.getValue("Vapor-Main-Class");
            assertNotNull(vaporMain, "manifest must retain the original main class");
            assertNotNull(out.getJarEntry(vaporMain.replace('.', '/') + ".class"),
                    "Vapor-Main-Class does not resolve to an output entry: " + vaporMain);

            Enumeration<JarEntry> entries = out.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = readAll(out.getInputStream(entry));
                if (LOADER_ENTRY.equals(entry.getName())) {
                    loaderSeen = true;
                    assertTrue(startsWith(bytes, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}),
                            "embedded loader must be a plain, loadable class file");
                    assertEquals(52, classMajorVersion(bytes),
                            "embedded loader must target Java 8 (classfile major 52)");
                    continue;
                }

                encryptedEntries++;
                assertTrue(startsWith(bytes, new byte[]{'V', 'P', '0', '1'}),
                        "class entry must carry the VP01 encryption header: " + entry.getName());

                for (Map.Entry<String, byte[]> original : originalClasses.entrySet()) {
                    assertFalse(Arrays.equals(original.getValue(), bytes),
                            entry.getName() + " still contains the plaintext bytes of " + original.getKey());
                    assertEquals(-1, indexOf(bytes, original.getValue()),
                            entry.getName() + " contains the plaintext class bytes of " + original.getKey());
                }
            }
        }

        assertTrue(loaderSeen, "loader class missing from protected jar");
        assertEquals(2, encryptedEntries, "both fixture classes must be encrypted (main + helper)");
    }

    @Test
    void protectedJarRunsLikeTheOriginalJar() throws Exception {
        RunResult original = runJar(inputJar, "alpha", "beta");
        RunResult protectedRun = runJar(protectedJar, "alpha", "beta");

        assertEquals(7, original.exitCode(), "fixture itself should exit with 7");
        assertEquals(original.exitCode(), protectedRun.exitCode(),
                "protected jar exit code differs; stderr:\n" + protectedRun.stderr());
        assertEquals(normalize(original.stdout()), normalize(protectedRun.stdout()),
                "protected jar stdout differs; stderr:\n" + protectedRun.stderr());
        assertEquals(normalize(original.stderr()), normalize(protectedRun.stderr()),
                "protected jar wrote unexpected stderr");

        String stdout = normalize(protectedRun.stdout());
        assertTrue(stdout.contains("greeting=[VAPOR]"),
                "helper class was not loaded/executed correctly:\n" + stdout);
        assertTrue(stdout.contains("args=2"),
                "main arguments were not forwarded by the loader:\n" + stdout);
    }

    @Test
    void sameConfigProducesByteIdenticalJar() throws Exception {
        Path secondRun = tempDir.resolve("protected-second.jar");
        obfuscateWithClassEncryption(inputJar, secondRun, SEED);

        assertArrayEquals(Files.readAllBytes(protectedJar), Files.readAllBytes(secondRun),
                "same input + same config (including seed) must be byte-identical");
    }

    @Test
    void tamperedCiphertextIsRejectedByIntegrityCheck() throws Exception {
        Path tampered = tempDir.resolve("tampered.jar");
        try (JarFile in = new JarFile(protectedJar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(tampered))) {
            Enumeration<JarEntry> entries = in.entries();
            boolean flipped = false;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] bytes = readAll(in.getInputStream(entry));
                if (!flipped && !entry.isDirectory() && entry.getName().endsWith(".class")
                        && !LOADER_ENTRY.equals(entry.getName()) && bytes.length > 16) {
                    bytes[12] ^= 0x01;
                    flipped = true;
                }
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(0L);
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
            assertTrue(flipped, "test jar had no encrypted class to tamper with");
        }

        RunResult result = runJar(tampered);
        assertFalse(result.exitCode() == 0, "tampered jar unexpectedly ran successfully");
        String diagnostics = normalize(result.stderr());
        assertTrue(diagnostics.contains("integrity") || diagnostics.contains("SecurityException")
                        || diagnostics.contains("ClassFormatError"),
                "tamper was not diagnosed as an integrity failure:\n" + diagnostics);
    }

    private static void obfuscateWithClassEncryption(Path input, Path output, long seed) throws Exception {
        VaporConfig config = VaporConfig.defaults();
        config.seed = seed;
        config.protection.encryptClasses = true;
        Obfuscator.run(new Obfuscator.Request(input, output, config, null, List.of(), null, null));
    }

    private static Path buildExecutableJar(Path root) throws Exception {
        Path sourceDir = Files.createDirectories(root.resolve("fixture-src"));
        Path classesDir = Files.createDirectories(root.resolve("fixture-classes"));
        Path mainSource = sourceDir.resolve("fixture/Main.java");
        Path helperSource = sourceDir.resolve("fixture/Helper.java");
        Files.createDirectories(mainSource.getParent());
        Files.writeString(mainSource, MAIN_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(helperSource, HELPER_SOURCE, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "acceptance test requires a JDK (javax.tools.JavaCompiler)");
        ByteArrayOutputStream compilerErrors = new ByteArrayOutputStream();
        int rc = compiler.run(null, OutputStream.nullOutputStream(), compilerErrors,
                "-proc:none", "-d", classesDir.toString(),
                mainSource.toString(), helperSource.toString());
        assertEquals(0, rc, () -> "fixture compilation failed:\n" + compilerErrors.toString(StandardCharsets.UTF_8));

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS);

        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(classesDir)) {
            classFiles = files.filter(Files::isRegularFile).sorted().toList();
        }
        assertFalse(classFiles.isEmpty(), "fixture compiler produced no classes");

        Path jar = root.resolve("fixture-input.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Path classFile : classFiles) {
                String entryName = classesDir.relativize(classFile).toString()
                        .replace(File.separatorChar, '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                out.putNextEntry(entry);
                Files.copy(classFile, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> readClassEntries(Path jar) throws Exception {
        Map<String, byte[]> classes = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    classes.put(entry.getName(), readAll(file.getInputStream(entry)));
                }
            }
        }
        return classes;
    }

    private static RunResult runJar(Path jar, String... args) throws Exception {
        Path stdoutFile = Files.createTempFile(tempDir, "run-", ".stdout");
        Path stderrFile = Files.createTempFile(tempDir, "run-", ".stderr");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(tempDir.toFile());
        builder.redirectOutput(stdoutFile.toFile());
        builder.redirectError(stderrFile.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            fail("timed out launching protected jar: " + String.join(" ", command));
        }
        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Files.deleteIfExists(stdoutFile);
        Files.deleteIfExists(stderrFile);
        return new RunResult(process.exitValue(), stdout, stderr);
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path candidate = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Files.isRegularFile(candidate) ? candidate : Path.of("java");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int classMajorVersion(byte[] bytes) {
        if (bytes.length < 8) {
            return -1;
        }
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
