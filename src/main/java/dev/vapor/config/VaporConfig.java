package dev.vapor.config;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * User-facing configuration (JSON, Gson-bound). Every field has a working
 * default, so an empty file (or no {@code -config} at all) means "full v1
 * pipeline": rename everything that can be renamed, clean metadata, encrypt
 * strings.
 */
public final class VaporConfig {

    /** Dictionary seed. {@code 0} = plain sequential names; non-zero shuffles deterministically. */
    public long seed = 0;

    /** Failsafe mode: a transformer that blows up is skipped with a warning instead of failing the build. */
    public boolean failsafe = true;

    /** Class name globs (dotted form, {@code *} and {@code ?} wildcards) whose names and members are kept. */
    public List<String> keep = new ArrayList<>();

    public Transformers transformers = new Transformers();

    public Strings strings = new Strings();

    /** Optional watermark: embedded as a constant in the output (phase 5). */
    public String watermark = null;

    public Hardening hardening = new Hardening();

    public static final class Transformers {
        public boolean renameClasses = true;
        public boolean renameMethods = true;
        public boolean renameFields = true;
        public boolean stripLineNumbers = true;
        public boolean stripLocalVariables = true;
        public boolean stripMethodParameters = true;
        public boolean stripSignatures = true;
        public boolean stripSourceFile = true;
        public boolean encryptStrings = true;
        // ---- v2 ----
        public boolean deadCode = true;
        public boolean integerObfuscation = true;
        public boolean flowShuffle = true;
    }

    public static final class Strings {
        /** Only strings at least this long are encrypted. */
        public int minLength = 2;
        /** v2: vary decryptor math and loop shape per class instead of always emitting the XOR form. */
        public boolean polymorphic = true;
    }

    /** v2 hardening knobs (all transforms share the budget rule). */
    public static final class Hardening {
        /** Added instructions per method ≤ (maxOverhead − 1) × instructions present at transform start. */
        public double maxOverhead = 2.0;
        /** Decoy `if (alwaysFalse) { ... }` blocks injected per method (cap). */
        public int deadBranchesPerMethod = 3;
        /** Flow-noise edits (splits + inversions + condition perturbations) per method (cap). */
        public int flowSplitsPerMethod = 6;
    }

    public static VaporConfig defaults() {
        return new VaporConfig();
    }

    public static VaporConfig load(Path path) throws IOException {
        VaporConfig config;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config = new Gson().fromJson(reader, VaporConfig.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("invalid config " + path + ": " + e.getMessage(), e);
        }
        if (config == null) {
            config = new VaporConfig();
        }
        if (config.keep == null) {
            config.keep = new ArrayList<>();
        }
        if (config.transformers == null) {
            config.transformers = new Transformers();
        }
        if (config.strings == null) {
            config.strings = new Strings();
        }
        if (config.hardening == null) {
            config.hardening = new Hardening();
        }
        return config;
    }
}
