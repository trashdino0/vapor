package dev.vapor.context.hierarchy;

/**
 * Type-graph knowledge used for frame computation and (later) rename scopes.
 *
 * <p>Type names follow ASM conventions: internal names ({@code java/lang/String})
 * for classes, descriptors ({@code [Ljava/lang/String;}) for arrays.</p>
 */
public interface IHierarchy {

    /** Lightweight class header: everything needed to walk the type graph. */
    record ClassInfo(String name, String superName, String[] interfaces, boolean isInterface) {
    }

    /** @return header info, or {@code null} when the class cannot be resolved */
    ClassInfo getClassInfo(String internalName);

    boolean isInterface(String internalName);

    /** True when {@code subtype} is {@code type} or inherits/implements it. */
    boolean isAssignableFrom(String type, String subtype);

    /** JVMS-style common superclass of two reference types (frame computation). */
    String getCommonSuperClass(String type1, String type2);
}
