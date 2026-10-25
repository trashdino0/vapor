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
        run(context, PipelineListener.NONE);
    }

    /**
     * Run all passes, reporting stage boundaries and checking the context's
     * cancellation flag between every stage. Individual passes also poll the
     * flag while iterating classes/entries, so Cancel does not wait for a whole
     * transformer to finish.
     */
    public void run(Context context, PipelineListener listener) throws Exception {
        PipelineListener events = listener == null ? PipelineListener.NONE : listener;
        int total = preprocess.size();
        for (Transformer transformer : transformers) {
            if (transformer.enabled()) {
                total++;
            }
        }
        total += postprocess.size();

        int index = 0;
        for (IPass pass : preprocess) {
            context.checkCancelled();
            index = runPass("pre", pass, context, events, index, total);
        }
        for (Transformer transformer : transformers) {
            if (transformer.enabled()) {
                context.checkCancelled();
                index = runPass("transform", transformer, context, events, index, total);
            } else {
                System.out.printf("  [%-9s] %-22s skipped (disabled)%n", "transform", transformer.name());
            }
        }
        for (IPass pass : postprocess) {
            context.checkCancelled();
            index = runPass("post", pass, context, events, index, total);
        }
    }

    private static int runPass(String stage, IPass pass, Context context,
                               PipelineListener listener, int index, int total) throws Exception {
        int current = index + 1;
        listener.stageStarted(stage, pass.name(), current, total);
        long start = System.nanoTime();
        pass.run(context);
        double millis = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("  [%-9s] %-22s %8.1f ms%n", stage, pass.name(), millis);
        listener.stageFinished(stage, pass.name(), current, total, millis);
        return current;
    }
}
