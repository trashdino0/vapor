package dev.vapor;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.pipeline.Pipeline;
import dev.vapor.pipeline.PipelineListener;
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
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Shared obfuscation entry point used by both the CLI and the desktop UI.
 *
 * <p>The CLI is deliberately thin: it parses options, loads a config and calls
 * this class. The GUI builds the same {@link Request} from its form, so both
 * front-ends run exactly the same pipeline and produce identical bytes for the
 * same config and input.</p>
 */
public final class Obfuscator {

    private Obfuscator() {
    }

    /**
     * @param cancelled polled between stages and while iterating classes; a
     *                  cancelled run throws {@link java.util.concurrent.CancellationException}
     * @param listener  optional stage progress listener
     */
    public record Request(
            Path input,
            Path output,
            VaporConfig config,
            Path mapping,
            List<Path> libraries,
            BooleanSupplier cancelled,
            PipelineListener listener) {
    }

    public record Result(
            int classCount,
            int resourceCount,
            long inputSize,
            long outputSize,
            double millis) {
    }

    public static Result run(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        Path input = Objects.requireNonNull(request.input(), "input");
        Path output = Objects.requireNonNull(request.output(), "output");
        VaporConfig config = request.config() == null ? VaporConfig.defaults() : request.config();
        Path mapping = request.mapping();
        List<Path> libraries = request.libraries() == null ? List.of() : List.copyOf(request.libraries());
        BooleanSupplier cancelled = request.cancelled() == null ? () -> false : request.cancelled();
        PipelineListener listener = request.listener() == null ? PipelineListener.NONE : request.listener();

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("input jar not found: " + input);
        }
        if (Files.isDirectory(output)) {
            throw new IllegalArgumentException("output is a directory: " + output);
        }
        Path absoluteInput = input.toAbsolutePath().normalize();
        Path absoluteOutput = output.toAbsolutePath().normalize();
        if (absoluteInput.equals(absoluteOutput)) {
            throw new IllegalArgumentException("input and output must be different files");
        }
        if (mapping != null) {
            if (Files.isDirectory(mapping)) {
                throw new IllegalArgumentException("mapping path is a directory: " + mapping);
            }
            if (mapping.toAbsolutePath().normalize().equals(absoluteOutput)) {
                throw new IllegalArgumentException("mapping and output must be different files");
            }
        }
        for (Path library : libraries) {
            if (!Files.exists(library)) {
                throw new IllegalArgumentException("library not found: " + library);
            }
        }

        long start = System.nanoTime();
        try (Context context = new Context(input, output, libraries, cancelled)) {
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
                    .postprocess(new MappingExport(mapping))
                    .postprocess(new JarWrite(output))
                    .run(context, listener);

            int classCount = context.classes().size();
            int resourceCount = context.entries().size() - classCount;
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            return new Result(classCount, resourceCount, Files.size(input), Files.size(output), millis);
        }
    }
}
