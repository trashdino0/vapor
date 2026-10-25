package dev.vapor;

import dev.vapor.config.VaporConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscatorTest {

    @Test
    void cancelledRunStopsBeforeLoadingAndWritesNothing(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("input.jar");
        Files.write(input, new byte[0]);
        Path output = dir.resolve("output.jar");
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThrows(CancellationException.class, () -> Obfuscator.run(new Obfuscator.Request(
                input, output, VaporConfig.defaults(), null, List.of(),
                cancelled::get, null)));
        assertFalse(Files.exists(output));
    }

    @Test
    void inputAndOutputMustDiffer(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("app.jar");
        Files.write(input, new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> Obfuscator.run(new Obfuscator.Request(
                input, input, VaporConfig.defaults(), null, List.of(), null, null)));
    }

    @Test
    void configRoundTripsThroughJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        VaporConfig original = VaporConfig.defaults();
        original.seed = 0x5EEDL;
        original.failsafe = false;
        original.watermark = "build-42";
        original.keep = List.of("com.example.api.*", "com.example.Plugin");
        original.transformers.flowFlatten = false;
        original.transformers.stripSignatures = false;
        original.strings.minLength = 5;
        original.strings.polymorphic = false;
        original.hardening.maxOverhead = 1.5;
        original.hardening.deadBranchesPerMethod = 7;
        original.hardening.flowSplitsPerMethod = 9;
        original.hardening.flattenMaxBlocks = 64;
        original.save(file);
        assertTrue(Files.isRegularFile(file));

        VaporConfig loaded = VaporConfig.load(file);
        assertEquals(original.seed, loaded.seed);
        assertEquals(original.failsafe, loaded.failsafe);
        assertEquals(original.watermark, loaded.watermark);
        assertEquals(original.keep, loaded.keep);
        assertEquals(original.transformers.flowFlatten, loaded.transformers.flowFlatten);
        assertEquals(original.transformers.stripSignatures, loaded.transformers.stripSignatures);
        assertEquals(original.strings.minLength, loaded.strings.minLength);
        assertEquals(original.strings.polymorphic, loaded.strings.polymorphic);
        assertEquals(original.hardening.maxOverhead, loaded.hardening.maxOverhead);
        assertEquals(original.hardening.deadBranchesPerMethod, loaded.hardening.deadBranchesPerMethod);
        assertEquals(original.hardening.flowSplitsPerMethod, loaded.hardening.flowSplitsPerMethod);
        assertEquals(original.hardening.flattenMaxBlocks, loaded.hardening.flattenMaxBlocks);
    }
}
