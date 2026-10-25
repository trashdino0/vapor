package dev.vapor.naming;

import org.objectweb.asm.commons.Remapper;

/**
 * ASM remapper backed by {@link Mappings}.
 *
 * <p>Functionality intentionally not remapped:</p>
 * <ul>
 *   <li>invokedynamic call-site names in general (string concat, record
 *       {@code toString}, switch bootstraps) — lambda call sites are handled
 *       separately by {@link VaporClassRemapper};</li>
 *   <li>record component names unless {@code obfuscateRecords} is enabled; the
 *       renamer keeps the backing field, accessor and Record attribute in sync;</li>
 *   <li>{@code <init>} / {@code <clinit>}.</li>
 * </ul>
 */
public final class RemapperImpl extends Remapper {

    private final Mappings mappings;

    public RemapperImpl(Mappings mappings) {
        this.mappings = mappings;
    }

    @Override
    public String map(String internalName) {
        return mappings.mappedClass(internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (name.startsWith("<")) {
            return name;
        }
        return mappings.mappedMethod(name, descriptor);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return mappings.mappedField(name, descriptor);
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        return name;
    }

    @Override
    public String mapInnerClassName(String name, String ownerName, String innerName) {
        if (innerName == null || innerName.isEmpty()) {
            return innerName;
        }
        String mapped = mapType(name);
        int dollar = mapped.lastIndexOf('$');
        return dollar < 0 ? mapped : mapped.substring(dollar + 1);
    }

    @Override
    public String mapRecordComponentName(String owner, String name, String descriptor) {
        return mappings.mappedRecordComponent(name, descriptor);
    }
}
