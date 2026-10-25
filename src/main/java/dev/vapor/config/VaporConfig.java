package dev.vapor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * User-facing configuration (JSON, Gson-bound). Every field has a working
 * default, so an empty file (or no {@code -config} at all) means "full default
 * pipeline": rename everything that can be renamed, clean metadata, encrypt
 * strings, and apply the v2/v3 hardening transforms.
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

    /** Runtime protection for executable jars (opt-in). */
    public Protection protection = new Protection();

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
        // ---- v3 ----
        public boolean flowFlatten = true;
        // ---- hardening (opt-in behavior changes) ----
        /** Rename record component fields/accessors and rewrite ObjectMethods metadata. */
        public boolean obfuscateRecords = false;
        /** Drop runtime-visible/invisible annotations (breaks frameworks that reflect on them). */
        public boolean stripAnnotations = false;
        /** Translate eligible pure-int methods into a small custom VM. */
        public boolean virtualize = false;
    }

    public static final class Strings {
        /** Only strings at least this long are encrypted; 0 encrypts every non-empty string. */
        public int minLength = 0;
        /** v2: vary decryptor math and loop shape per class instead of always emitting the XOR form. */
        public boolean polymorphic = true;
        /**
         * Store all encoded strings in a per-class String[] pool and decode by integer index.
         * This removes per-call encoded literals and keys and is the preferred mode.
         */
        public boolean pool = true;
        /** Move static final String ConstantValue attributes into an encrypted <clinit> assignment. */
        public boolean encryptConstantValues = true;
    }

    /** v2 hardening knobs (all transforms share the budget rule). */
    public static final class Hardening {
        /** Added instructions per method ≤ (maxOverhead − 1) × instructions present at transform start. */
        public double maxOverhead = 2.0;
        /** Decoy `if (alwaysFalse) { ... }` blocks injected per method (cap). */
        public int deadBranchesPerMethod = 3;
        /** Flow-noise edits (splits + inversions + condition perturbations) per method (cap). */
        public int flowSplitsPerMethod = 6;
        /** v3.3: methods with more basic blocks than this stay unflattened. */
        public int flattenMaxBlocks = 128;
        /** Minimum number of basic blocks required before flattening (2 catches simple branches). */
        public int flattenMinBlocks = 2;
        /** Add an opaque entry branch to straight-line methods so FlowFlatten can split them. */
        public boolean splitSingleBlock = true;
        /** Do not split tiny methods; flattening only a handful of instructions is mostly overhead. */
        public int blockSplitMinInstructions = 6;
        /** Upper bound for methods considered by the optional int-only virtualizer. */
        public int virtualizeMaxInstructions = 400;
    }

    /** Strengthening that changes the runtime/distribution shape rather than just bytecode. */
    public static final class Protection {
        /**
         * Encrypt class entries and add a bootstrap loader. Only executable jars
         * (a manifest with Main-Class) are eligible; libraries are written normally.
         */
        public boolean encryptClasses = false;
        /** Store a CRC32 of each plaintext class and verify it in the bootstrap loader. */
        public boolean verifyIntegrity = true;
        /** Dotted name of the bootstrap class embedded into protected jars. */
        public String loaderClass = "dev.vapor.rt.VaporLoader";
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
        if (config.protection == null) {
            config.protection = new Protection();
        }
        return config;
    }

    /** Write this config as pretty-printed JSON (the inverse of {@link #load(Path)}). */
    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(this, writer);
        }
    }
}
