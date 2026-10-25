package dev.vapor;

import dev.vapor.config.VaporConfig;
import dev.vapor.gui.VaporGui;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vapor — Java obfuscator.
 *
 * <p>CLI front-end. With no arguments (or {@code --gui}) it opens the desktop
 * UI; otherwise it parses the options and runs the shared
 * {@link Obfuscator} pipeline.</p>
 */
public final class Main {

    public static final String VERSION = "0.4.0";

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || hasGuiFlag(args)) {
            launchGui(args.length > 0);
            return;
        }
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("fatal: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static boolean hasGuiFlag(String[] args) {
        for (String arg : args) {
            if (arg.equals("-g") || arg.equals("--gui")) {
                return true;
            }
        }
        return false;
    }

    private static void launchGui(boolean explicit) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("vapor: desktop GUI is unavailable in a headless environment");
            if (explicit) {
                System.exit(1);
            }
            printHelp();
            return;
        }
        VaporGui.launch();
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

        Obfuscator.Result result = Obfuscator.run(new Obfuscator.Request(
                input, output, config, mappingPath, libraries, () -> false, null));

        System.out.println("vapor " + VERSION);
        System.out.println("in:   " + input.toAbsolutePath());
        System.out.println("out:  " + output.toAbsolutePath());
        System.out.println("classes: " + result.classCount() + ", resources: " + result.resourceCount());
        System.out.printf("size: %,d -> %,d bytes%n", result.inputSize(), result.outputSize());
        System.out.printf("time: %.0f ms%n", result.millis());
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
                  vapor                         launch the desktop GUI
                  vapor --gui                   launch the desktop GUI
                  vapor -i <input.jar> -o <output.jar> [options]

                options:
                  -i, --input <jar>       input jar
                  -o, --output <jar>      output jar
                  -lib, --library <path>  library jar or class directory (repeatable)
                  -config <file>          JSON config (defaults: full v3 pipeline, hardening on)
                  -mapping <file>         write a ProGuard-style mapping file
                  -keep <glob>            keep class names matching the dotted glob (repeatable)
                  -g, --gui               launch the desktop GUI
                  -v, --version           print version
                  -h, --help              this help
                """);
    }
}
