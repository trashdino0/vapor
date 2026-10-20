package dev.vapor.pipeline;

import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;

import java.util.ArrayList;
import java.util.List;

/**
 * Execution order: preprocess passes (jar load, hierarchy prep) →
 * enabled transformers → postprocess passes (jar write, mapping export).
 */
public final class Pipeline {

    private final List<IPass> preprocess = new ArrayList<>();
    private final List<Transformer> transformers = new ArrayList<>();
    private final List<IPass> postprocess = new ArrayList<>();

    public Pipeline preprocess(IPass pass) {
        preprocess.add(pass);
        return this;
    }

    public Pipeline transformer(Transformer transformer) {
        transformers.add(transformer);
        return this;
    }

    public Pipeline postprocess(IPass pass) {
        postprocess.add(pass);
        return this;
    }

    public void run(Context context) throws Exception {
        for (IPass pass : preprocess) {
            runPass("pre", pass, context);
        }
        for (Transformer transformer : transformers) {
            if (transformer.enabled()) {
                runPass("transform", transformer, context);
            } else {
                System.out.printf("  [%-9s] %-22s skipped (disabled)%n", "transform", transformer.name());
            }
        }
        for (IPass pass : postprocess) {
            runPass("post", pass, context);
        }
    }

    private static void runPass(String stage, IPass pass, Context context) throws Exception {
        long start = System.nanoTime();
        pass.run(context);
        double millis = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("  [%-9s] %-22s %8.1f ms%n", stage, pass.name(), millis);
    }
}
