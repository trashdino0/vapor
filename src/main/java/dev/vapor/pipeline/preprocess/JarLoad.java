package dev.vapor.pipeline.preprocess;

import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.file.JarIO;
import dev.vapor.pipeline.IPass;
import dev.vapor.tree.JClass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;

/**
 * Reads the input jar, parsing class entries into ASM trees and keeping
 * everything else (manifest, services files, directories, nested versioned
 * classes) as raw resources in original order.
 */
public final class JarLoad implements IPass {

    private final Path input;

    public JarLoad(Path input) {
        this.input = input;
    }

    @Override
    public String name() {
        return "JarLoad";
    }

    @Override
    public void run(Context context) throws Exception {
        JarIO.JarContents contents = JarIO.read(input);
        int classes = 0;
        for (JarIO.Entry entry : contents.entries()) {
            context.checkCancelled();
            String name = entry.name();
            if (!entry.directory() && name.equals("module-info.class")) {
                System.err.println("  warn: module-info.class left untouched (modules are phase-5 work)");
                context.addEntry(new JarEntry.Resource(name, entry.bytes()));
                continue;
            }
            // META-INF/* (incl. versions/9/... nested classes), directories and
            // any other non-class payload flow through untouched in phase 1.
            if (entry.directory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                context.addEntry(new JarEntry.Resource(name, entry.bytes()));
                continue;
            }
            ClassNode node = new ClassNode();
            new ClassReader(entry.bytes()).accept(node, 0);
            context.addEntry(new JarEntry.ClassEntry(new JClass(node)));
            classes++;
        }
        if (classes == 0) {
            System.err.println("  warn: no class entries found — is the input really a jar?");
        }
    }
}
