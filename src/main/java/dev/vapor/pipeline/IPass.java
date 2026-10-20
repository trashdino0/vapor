package dev.vapor.pipeline;

import dev.vapor.context.Context;

/** A single pipeline stage (preprocess, transform, postprocess). */
public interface IPass {

    void run(Context context) throws Exception;

    default String name() {
        return getClass().getSimpleName();
    }
}
