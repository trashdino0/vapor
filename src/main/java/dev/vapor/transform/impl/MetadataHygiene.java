package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

/**
 * Strips debug metadata that leaks structure and makes naming reversible:
 *
 * <ul>
 *   <li>line number tables (stack traces lose exact lines);</li>
 *   <li>local variable tables / local variable type tables;</li>
 *   <li>{@code MethodParameters} (real parameter names);</li>
 *   <li>generic signatures (class / method / field / record component);</li>
 *   <li>source file name (rewritten to {@code "a"}) and SMAP debug extensions.</li>
 * </ul>
 *
 * <p>{@code InnerClasses}, nest attributes and runtime annotations are kept —
 * they carry semantics (nest-based access control, reflection behavior) and
 * their names are already consistently remapped.
 * {@code transformers.stripAnnotations} opts into dropping annotation attributes
 * when the target application does not reflect on them.</p>
 */
public final class MetadataHygiene extends Transformer {

    private final VaporConfig config;

    public MetadataHygiene(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "MetadataHygiene";
    }

    @Override
    public boolean enabled() {
        VaporConfig.Transformers flags = config.transformers;
        return flags.stripLineNumbers || flags.stripLocalVariables || flags.stripMethodParameters
                || flags.stripSignatures || flags.stripSourceFile || flags.stripAnnotations;
    }

    @Override
    public void run(Context context) {
        for (var clazz : context.classes()) {
            context.checkCancelled();
            strip(clazz.node());
        }
    }

    private void strip(ClassNode node) {
        VaporConfig.Transformers flags = config.transformers;
        if (flags.stripSignatures) {
            node.signature = null;
        }
        if (flags.stripSourceFile) {
            node.sourceFile = "a";
            node.sourceDebug = null;
        }
        if (flags.stripAnnotations) {
            node.visibleAnnotations = null;
            node.invisibleAnnotations = null;
            node.visibleTypeAnnotations = null;
            node.invisibleTypeAnnotations = null;
        }
        for (MethodNode method : node.methods) {
            if (flags.stripLineNumbers) {
                stripLineNumbers(method);
            }
            if (flags.stripLocalVariables) {
                method.localVariables = null;
            }
            if (flags.stripMethodParameters) {
                method.parameters = null;
            }
            if (flags.stripSignatures) {
                method.signature = null;
            }
            if (flags.stripAnnotations) {
                method.visibleAnnotations = null;
                method.invisibleAnnotations = null;
                method.visibleTypeAnnotations = null;
                method.invisibleTypeAnnotations = null;
                method.visibleParameterAnnotations = null;
                method.invisibleParameterAnnotations = null;
                method.visibleLocalVariableAnnotations = null;
                method.invisibleLocalVariableAnnotations = null;
            }
        }
        for (var field : node.fields) {
            if (flags.stripSignatures) {
                field.signature = null;
            }
            if (flags.stripAnnotations) {
                field.visibleAnnotations = null;
                field.invisibleAnnotations = null;
                field.visibleTypeAnnotations = null;
                field.invisibleTypeAnnotations = null;
            }
        }
        if (node.recordComponents != null) {
            for (RecordComponentNode component : node.recordComponents) {
                if (flags.stripSignatures) {
                    component.signature = null;
                }
                if (flags.stripAnnotations) {
                    component.visibleAnnotations = null;
                    component.invisibleAnnotations = null;
                    component.visibleTypeAnnotations = null;
                    component.invisibleTypeAnnotations = null;
                }
            }
        }
    }

    private static void stripLineNumbers(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof LineNumberNode) {
                method.instructions.remove(insn);
            }
            insn = next;
        }
    }
}
