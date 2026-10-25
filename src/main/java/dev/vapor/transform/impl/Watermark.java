package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * Embeds the configured watermark as a {@code private static final String}
 * constant in the output — proof of provenance, findable with
 * {@code javap -v} / {@code strings}.
 *
 * <p>The field is injected into the lexicographically first eligible class
 * (no interfaces, no annotation types, no {@code module-info}), so the choice
 * is deterministic and independent of jar entry order. It is added
 * <em>before</em> the renamer runs, so its name is obfuscated like any other
 * member and can never collide with existing fields or create a
 * hide/override relationship.</p>
 *
 * <p>The watermark text itself is a {@code ConstantValue} attribute, not an
 * {@code LDC}: string encryption deliberately leaves it readable — that is
 * the point of a watermark.</p>
 */
public final class Watermark extends Transformer {

    /** Placeholder name; the renamer replaces it unless the target class is kept. */
    private static final String BASE_NAME = "watermark";

    private final VaporConfig config;

    public Watermark(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Watermark";
    }

    @Override
    public boolean enabled() {
        return config.watermark != null && !config.watermark.isEmpty();
    }

    @Override
    public void run(Context context) {
        ClassNode target = null;
        for (var clazz : context.classes()) {
            context.checkCancelled();
            ClassNode node = clazz.node();
            if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }
            if ("module-info".equals(node.name)) {
                continue;
            }
            if (target == null || node.name.compareTo(target.name) < 0) {
                target = node;
            }
        }
        if (target == null) {
            System.err.println("  warn: watermark skipped — no eligible class in the jar");
            return;
        }
        String fieldName = BASE_NAME;
        for (int i = 2; hasField(target, fieldName); i++) {
            fieldName = BASE_NAME + i;
        }
        target.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                fieldName, "Ljava/lang/String;", null, config.watermark));
    }

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
