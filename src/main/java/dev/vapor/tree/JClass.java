package dev.vapor.tree;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/**
 * Thin wrapper around an ASM {@link ClassNode} — the unit the pipeline operates on.
 */
public final class JClass {

    private ClassNode node;

    public JClass(ClassNode node) {
        this.node = node;
    }

    public ClassNode node() {
        return node;
    }

    /** Replace the tree (used by the renamer to swap in the remapped copy). */
    public void setNode(ClassNode node) {
        this.node = node;
    }

    /** Internal name, e.g. {@code dev/sample/Main}. */
    public String name() {
        return node.name;
    }

    public String superName() {
        return node.superName;
    }

    public List<String> interfaces() {
        return node.interfaces;
    }

    public int access() {
        return node.access;
    }

    public boolean isInterface() {
        return (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isRecord() {
        return (node.access & Opcodes.ACC_RECORD) != 0;
    }

    @Override
    public String toString() {
        return name();
    }
}
