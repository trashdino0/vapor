package dev.vapor.transform;

import dev.vapor.pipeline.IPass;

/**
 * Base class for all bytecode transforms. Transformers are ordinary pipeline
 * stages; the config-driven `Setting<T>` framework arrives with the first real
 * transformer (phase 2 — renaming).
 */
public abstract class Transformer implements IPass {

    /** Disabled transformers are skipped (will be driven by config in phase 2). */
    public boolean enabled() {
        return true;
    }
}
