package dev.vapor.pipeline;

/**
 * Optional progress hook for front-ends. The CLI ignores it; the desktop UI
 * uses it to drive its stage label and progress bar while the pipeline runs on
 * a background thread.
 *
 * <p>Callbacks are made on the pipeline thread, not the Swing event thread.</p>
 */
public interface PipelineListener {

    PipelineListener NONE = new PipelineListener() {
    };

    /**
     * @param stage {@code pre}, {@code transform} or {@code post}
     * @param name  pass name (for example {@code Renamer})
     * @param index 1-based index of this pass
     * @param total number of passes that will actually run
     */
    default void stageStarted(String stage, String name, int index, int total) {
    }

    default void stageFinished(String stage, String name, int index, int total, double millis) {
    }
}
