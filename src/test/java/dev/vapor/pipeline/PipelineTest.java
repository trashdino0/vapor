package dev.vapor.pipeline;

import dev.vapor.context.Context;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineTest {

    @Test
    void reportsStageBoundariesWithFullTotal() throws Exception {
        List<String> events = new ArrayList<>();
        PipelineListener listener = new PipelineListener() {
            @Override
            public void stageStarted(String stage, String name, int index, int total) {
                events.add("start:" + stage + ":" + name + ":" + index + "/" + total);
            }

            @Override
            public void stageFinished(String stage, String name, int index, int total, double millis) {
                events.add("finish:" + stage + ":" + name + ":" + index + "/" + total);
            }
        };

        try (Context context = new Context(null, null, List.of())) {
            new Pipeline()
                    .preprocess(new NamedPass("load"))
                    .postprocess(new NamedPass("write"))
                    .run(context, listener);
        }

        assertEquals(List.of(
                "start:pre:load:1/2",
                "finish:pre:load:1/2",
                "start:post:write:2/2",
                "finish:post:write:2/2"), events);
    }

    @Test
    void cancellationStopsBetweenStages() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (Context context = new Context(null, null, List.of(), cancelled::get)) {
            Pipeline pipeline = new Pipeline()
                    .preprocess(new IPass() {
                        @Override
                        public void run(Context context) {
                            cancelled.set(true);
                        }

                        @Override
                        public String name() {
                            return "stopAfterThis";
                        }
                    })
                    .postprocess(new IPass() {
                        @Override
                        public void run(Context context) {
                            throw new AssertionError("cancelled pipeline reached a later stage");
                        }

                        @Override
                        public String name() {
                            return "mustNotRun";
                        }
                    });

            assertThrows(CancellationException.class, () -> pipeline.run(context));
        }
    }

    private record NamedPass(String name) implements IPass {
        @Override
        public void run(Context context) {
            // no-op
        }
    }
}
