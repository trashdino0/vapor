package dev.vapor;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.pipeline.Pipeline;
import dev.vapor.pipeline.postprocess.JarWrite;
import dev.vapor.pipeline.postprocess.MappingExport;
import dev.vapor.pipeline.postprocess.ResourceFixups;
import dev.vapor.pipeline.preprocess.JarLoad;
import dev.vapor.transform.impl.DeadCode;
import dev.vapor.transform.impl.FlowFlatten;
import dev.vapor.transform.impl.FlowShuffle;
import dev.vapor.transform.impl.IntObfuscation;
import dev.vapor.transform.impl.MetadataHygiene;
import dev.vapor.transform.impl.Renamer;
import dev.vapor.transform.impl.StringEncryptor;
import dev.vapor.transform.impl.Watermark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vapor — Java obfuscator.
 *
 * <p>v1 pipeline: load → rename (classes/methods/fields, keep rules, mapping
 * export) → resource fix-ups → write. Metadata hygiene and string encryption
 * plug into the same pipeline.</p>
 */
public final class Main {

    private static final String VERSION = "0.2.0";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("fatal: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    static int run(String[] args) throws Exception {
        Path input = null;
        Path output = null;
        Path configPath = null;
        Path mappingPath = null;
        List<Path> libraries = new ArrayList<>();
        List<String> cliKeep = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-i", "--input" -> input = Path.of(requireValue(args, ++i, arg));
                case "-o", "--output" -> output = Path.of(requireValue(args, ++i, arg));
                case "-lib", "--library" -> libraries.add(Path.of(requireValue(args, ++i, arg)));
                case "-config", "--config" -> configPath = Path.of(requireValue(args, ++i, arg));
                case "-mapping", "--mapping" -> mappingPath = Path.of(requireValue(args, ++i, arg));
                case "-keep", "--keep" -> cliKeep.add(requireValue(args, ++i, arg));
                case "-v", "--version" -> {
                    System.out.println("vapor " + VERSION);
                    return 0;
                }
                case "-h", "--help" -> {
                    printHelp();
                    return 0;
                }
                default -> throw new IllegalArgumentException("unknown option: " + arg + " (try --help)");
            }
        }

        if (input == null || output == null) {
            printHelp();
            return 1;
        }
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("input jar not found: " + input);
        }
        for (Path library : libraries) {
            if (!Files.exists(library)) {
                throw new IllegalArgumentException("library not found: " + library);
            }
        }

        VaporConfig config = configPath == null ? VaporConfig.defaults() : VaporConfig.load(configPath);
        config.keep.addAll(cliKeep);

        long start = System.nanoTime();

        try (Context context = new Context(input, output, libraries)) {
            new Pipeline()
                    .preprocess(new JarLoad(input))
                    .transformer(new Watermark(config))
                    .transformer(new Renamer(config))
                    .transformer(new MetadataHygiene(config))
                    .transformer(new StringEncryptor(config))
                    .transformer(new DeadCode(config))
                    .transformer(new FlowShuffle(config))
                    .transformer(new FlowFlatten(config))
                    .transformer(new IntObfuscation(config))
                    .postprocess(new ResourceFixups())
                    .postprocess(new MappingExport(mappingPath))
                    .postprocess(new JarWrite(output))
                    .run(context);

            int classCount = context.classes().size();
            int resourceCount = context.entries().size() - classCount;

            System.out.println("vapor " + VERSION);
            System.out.println("in:   " + input.toAbsolutePath());
            System.out.println("out:  " + output.toAbsolutePath());
            System.out.println("classes: " + classCount + ", resources: " + resourceCount);
            System.out.printf("size: %,d -> %,d bytes%n", Files.size(input), Files.size(output));
            System.out.printf("time: %.0f ms%n", (System.nanoTime() - start) / 1_000_000.0);
        }
        return 0;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[index];
    }

    private static void printHelp() {
        System.out.println("""
                vapor — Java obfuscator

                usage:
                  vapor -i <input.jar> -o <output.jar> [options]

                options:
                  -i, --input <jar>       input jar
                  -o, --output <jar>      output jar
                  -lib, --library <path>  library jar or class directory (repeatable)
                  -config <file>          JSON config (defaults: full v1 pipeline)
                  -mapping <file>         write a ProGuard-style mapping file
                  -keep <glob>            keep class names matching the dotted glob (repeatable)
                  -v, --version           print version
                  -h, --help              this help
                """);
    }
}
