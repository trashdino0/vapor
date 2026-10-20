package dev.vapor.context.asm;

import dev.vapor.context.hierarchy.IHierarchy;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * ClassWriter that computes stack map frames ({@link ClassWriter#COMPUTE_FRAMES})
 * using the project hierarchy instead of ASM's reflection-based default — the
 * input jar's own classes are not on the obfuscator's runtime classpath.
 */
public final class VaporClassWriter extends ClassWriter {

    private final IHierarchy hierarchy;

    public VaporClassWriter(int flags, IHierarchy hierarchy) {
        super(flags);
        this.hierarchy = hierarchy;
    }

    /** Serialize a class tree with freshly computed frames. */
    public static byte[] write(ClassNode node, IHierarchy hierarchy) {
        VaporClassWriter writer = new VaporClassWriter(ClassWriter.COMPUTE_FRAMES, hierarchy);
        node.accept(writer);
        return writer.toByteArray();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        return hierarchy.getCommonSuperClass(type1, type2);
    }
}
