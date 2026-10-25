package dev.vapor.pipeline.postprocess;

import dev.vapor.context.Context;
import dev.vapor.naming.Mappings;
import dev.vapor.pipeline.IPass;
import dev.vapor.tree.JClass;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a ProGuard-style mapping file so renamed names can be reversed:
 *
 * <pre>
 * dev.sample.Main -&gt; a:
 *     int x -&gt; b
 *     java.lang.String classify(java.lang.String) -&gt; c
 * </pre>
 */
public final class MappingExport implements IPass {

    /** null = export disabled (no {@code -mapping} flag). */
    private final Path path;

    public MappingExport(Path path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "MappingExport";
    }

    @Override
    public void run(Context context) throws IOException {
        if (path == null) {
            return;   // no -mapping flag
        }
        context.checkCancelled();
        StringBuilder out = new StringBuilder();
        if (context.mappings().export().isEmpty()) {
            // Renaming never ran (disabled or skipped): emit identity mappings.
            for (JClass clazz : context.classes()) {
                context.checkCancelled();
                String dotted = clazz.name().replace('/', '.');
                out.append(dotted).append(" -> ").append(dotted).append(":\n");
                for (FieldNode field : clazz.node().fields) {
                    out.append("    ").append(fieldLine(field.name, field.desc, field.name)).append('\n');
                }
                for (MethodNode method : clazz.node().methods) {
                    if (!method.name.startsWith("<")) {
                        out.append("    ").append(methodLine(method.name, method.desc, method.name)).append('\n');
                    }
                }
            }
        } else {
            for (Mappings.ClassMapping mapping : context.mappings().export()) {
                context.checkCancelled();
                out.append(mapping.original().replace('/', '.')).append(" -> ")
                        .append(mapping.renamed().replace('/', '.')).append(":\n");
                for (Mappings.MemberMapping member : mapping.members()) {
                    String line = member.method()
                            ? methodLine(member.name(), member.descriptor(), member.renamed())
                            : fieldLine(member.name(), member.descriptor(), member.renamed());
                    out.append("    ").append(line).append('\n');
                }
            }
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static String fieldLine(String name, String descriptor, String renamed) {
        return pretty(descriptor) + " " + name + " -> " + renamed;
    }

    private static String methodLine(String name, String descriptor, String renamed) {
        Type method = Type.getMethodType(descriptor);
        StringBuilder args = new StringBuilder();
        for (Type argument : method.getArgumentTypes()) {
            if (args.length() > 0) {
                args.append(',');
            }
            args.append(pretty(argument.getDescriptor()));
        }
        return pretty(method.getReturnType().getDescriptor()) + " " + name + "(" + args + ") -> " + renamed;
    }

    /** Descriptor to Java-ish type name ({@code [Ljava/lang/String;} -&gt; java.lang.String[]). */
    private static String pretty(String descriptor) {
        try {
            return Type.getType(descriptor).getClassName();
        } catch (RuntimeException e) {
            return descriptor;
        }
    }
}
