package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.JarEntry;
import dev.vapor.transform.Transformer;
import dev.vapor.tree.JClass;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in integration pass for the conservative int-only virtualizer.
 *
 * <p>The expensive eligibility/translation work runs before the flow-noise
 * transforms, while the original method shapes are still available. Helper
 * classes are only committed after {@link FlowFlatten}; that keeps the generated
 * interpreter out of the flow passes but still lets {@link IntObfuscation}
 * process its program constants. Helper names are derived from the already
 * renamed owner/method and are deterministic.</p>
 */
public final class Virtualize extends Transformer {

    private final VaporConfig config;
    private final List<ClassNode> helpers = new ArrayList<>();
    private final Set<String> helperNames = new HashSet<>();

    public Virtualize(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Virtualize";
    }

    @Override
    public boolean enabled() {
        return config.transformers.virtualize;
    }

    @Override
    public void run(Context context) {
        helpers.clear();
        helperNames.clear();
        Virtualizer virtualizer = new Virtualizer(config.seed);
        List<JClass> classes = new ArrayList<>(context.classes());
        for (JClass clazz : classes) {
            context.checkCancelled();
            ClassNode owner = clazz.node();
            for (MethodNode method : owner.methods) {
                if (method.instructions.size() > config.hardening.virtualizeMaxInstructions) {
                    continue;
                }
                if (context.isArtificialMethod(owner.name, method.name, method.desc)) {
                    continue;
                }
                Virtualizer.Result result = virtualizer.transform(owner, method);
                if (!result.transformed()) {
                    continue;
                }
                if (context.getClass(result.helperName()) != null
                        || !helperNames.add(result.helperName())) {
                    throw new IllegalStateException(
                            "virtualizer helper name collision: " + result.helperName());
                }
                helpers.add(result.helper());
            }
        }
    }

    /**
     * Append the helper classes selected by {@link #run(Context)}. Called after
     * the flow transforms and before {@link IntObfuscation}.
     */
    public void commit(Context context) {
        for (ClassNode helper : helpers) {
            context.addEntry(new JarEntry.ClassEntry(new JClass(helper)));
        }
        helpers.clear();
    }

    /** Pipeline stage that commits the helpers selected by {@link Virtualize}. */
    public static final class Commit extends Transformer {

        private final Virtualize virtualize;

        public Commit(Virtualize virtualize) {
            this.virtualize = virtualize;
        }

        @Override
        public String name() {
            return "VirtualizeCommit";
        }

        @Override
        public boolean enabled() {
            return virtualize.enabled();
        }

        @Override
        public void run(Context context) {
            virtualize.commit(context);
        }
    }
}
