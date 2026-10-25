package dev.vapor.pipeline.postprocess;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.context.asm.VaporClassWriter;
import dev.vapor.file.JarIO;
import dev.vapor.pipeline.IPass;
import dev.vapor.protect.ClassProtector;
import dev.vapor.tree.JClass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the context into the output jar in original entry order: resources
 * as raw bytes, classes re-serialized from their (possibly transformed) ASM
 * trees with freshly computed stack map frames.
 *
 * <p>When {@code protection.encryptClasses} is enabled and the manifest has a
 * Main-Class, class bytes are encrypted at this final boundary and an
 * unencrypted bootstrap loader is appended.</p>
 */
public final class JarWrite implements IPass {

    private final Path output;
    private final VaporConfig config;

    public JarWrite(Path output) {
        this(output, VaporConfig.defaults());
    }

    public JarWrite(Path output, VaporConfig config) {
        this.output = output;
        this.config = config;
    }

    @Override
    public String name() {
        return "JarWrite";
    }

    @Override
    public void run(Context context) throws Exception {
        ClassProtector.Plan protection = ClassProtector.prepare(context, config);
        List<JarIO.Entry> written = new ArrayList<>(context.entries().size() + 1);
        for (JarEntry entry : context.entries()) {
            context.checkCancelled();
            switch (entry) {
                case JarEntry.Resource resource -> {
                    byte[] bytes = resource.bytes();
                    if (protection != null && "META-INF/MANIFEST.MF".equals(resource.name())) {
                        bytes = protection.manifestBytes();
                    }
                    written.add(new JarIO.Entry(resource.name(), resource.directory(), bytes));
                }
                case JarEntry.ClassEntry classEntry -> {
                    JClass clazz = classEntry.clazz();
                    byte[] bytes = VaporClassWriter.write(clazz.node(), context.hierarchy());
                    if (protection != null) {
                        bytes = ClassProtector.encrypt(bytes, clazz.name(), protection.key(),
                                protection.verifyIntegrity());
                    }
                    written.add(new JarIO.Entry(clazz.name() + ".class", false, bytes));
                }
            }
        }
        if (protection != null) {
            written.add(new JarIO.Entry(protection.loaderInternalName() + ".class", false,
                    protection.loaderBytes()));
        }
        JarIO.write(output, new JarIO.JarContents(List.copyOf(written)));
    }
}
