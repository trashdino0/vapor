package dev.vapor.pipeline.postprocess;

import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.context.asm.VaporClassWriter;
import dev.vapor.file.JarIO;
import dev.vapor.pipeline.IPass;
import dev.vapor.tree.JClass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the context into the output jar in original entry order: resources
 * as raw bytes, classes re-serialized from their (possibly transformed) ASM
 * trees with freshly computed stack map frames.
 */
public final class JarWrite implements IPass {

    private final Path output;

    public JarWrite(Path output) {
        this.output = output;
    }

    @Override
    public String name() {
        return "JarWrite";
    }

    @Override
    public void run(Context context) throws Exception {
        List<JarIO.Entry> written = new ArrayList<>(context.entries().size());
        for (JarEntry entry : context.entries()) {
            switch (entry) {
                case JarEntry.Resource resource ->
                        written.add(new JarIO.Entry(resource.name(), resource.directory(), resource.bytes()));
                case JarEntry.ClassEntry classEntry -> {
                    JClass clazz = classEntry.clazz();
                    byte[] bytes = VaporClassWriter.write(clazz.node(), context.hierarchy());
                    written.add(new JarIO.Entry(clazz.name() + ".class", false, bytes));
                }
            }
        }
        JarIO.write(output, new JarIO.JarContents(List.copyOf(written)));
    }
}
