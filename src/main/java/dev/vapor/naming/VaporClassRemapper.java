package dev.vapor.naming;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * {@link ClassRemapper} with one fix-up: lambda invokedynamic call sites.
 *
 * <p>For {@code LambdaMetafactory} the call-site name is the functional
 * interface's SAM name, and the JVM validates it against the interface at
 * bootstrap time. When the interface lives in the jar and gets renamed, the
 * call site must be renamed too. The SAM descriptor is not in the call-site
 * descriptor, but LambdaMetafactory receives it as the first bootstrap
 * argument ({@code samMethodType}), so it can be looked up exactly.</p>
 */
public final class VaporClassRemapper extends ClassRemapper {

    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    public VaporClassRemapper(ClassVisitor delegate, Remapper remapper) {
        super(Opcodes.ASM9, delegate, remapper);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (delegate == null) {
            return null;
        }
        return new MethodVisitor(api, delegate) {
            @Override
            public void visitInvokeDynamicInsn(String callSiteName, String callSiteDescriptor,
                                               Handle bootstrap, Object... bootstrapArgs) {
                String mappedName = callSiteName;
                if (bootstrap != null && LAMBDA_METAFACTORY.equals(bootstrap.getOwner())
                        && bootstrapArgs.length > 0 && bootstrapArgs[0] instanceof Type samType
                        && samType.getSort() == Type.METHOD) {
                    mappedName = remapper.mapMethodName("", callSiteName, samType.getDescriptor());
                }
                delegate.visitInvokeDynamicInsn(mappedName, callSiteDescriptor, bootstrap, bootstrapArgs);
            }
        };
    }
}
