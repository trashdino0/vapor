package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import dev.vapor.tree.JClass;
import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * v2: injects decoy branches guarded by opaque predicates:
 *
 * <pre>
 *   if (&lt;always false&gt;) {   // never taken at runtime, both paths valid
 *       &lt;junk: side-effect-free arithmetic + a counter loop&gt;
 *   }
 * </pre>
 *
 * <p>Safety rules that make this transformer boring (in a good way):</p>
 * <ul>
 *   <li>insertion points are restricted to instructions where the operand stack
 *       is <em>empty</em> (computed with ASM's {@code Analyzer}) — junk can never
 *       disturb values in flight;</li>
 *   <li>junk is self-contained by construction (it only reads/writes its own
 *       locals, pushes before it pops) — even if a predicate were wrong, the
 *       guarded block can neither throw nor change behavior;</li>
 *   <li>constructors are only touched after the {@code super()} / {@code this()}
 *       call;</li>
 *   <li>synthetic decryptors registered as artificials are skipped;</li>
 *   <li>per-method budgets cap growth ({@code hardening.maxOverhead}) and huge
 *       methods are left alone.</li>
 * </ul>
 *
 * <p>Every choice (position, predicate form, junk size, constants) is derived
 * deterministically from seed + class + method.</p>
 */
public final class DeadCode extends Transformer {

    private static final int MIN_INSTRUCTIONS = 12;
    private static final int MAX_INSTRUCTIONS = 20000;

    private final VaporConfig config;

    public DeadCode(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "DeadCode";
    }

    @Override
    public boolean enabled() {
        return config.transformers.deadCode;
    }

    @Override
    public void run(Context context) {
        for (JClass clazz : context.classes()) {
            context.checkCancelled();
            ClassNode owner = clazz.node();
            for (MethodNode method : owner.methods) {
                try {
                    process(context, owner, method);
                } catch (Throwable e) {
                    if (!config.failsafe) {
                        throw e;
                    }
                }
            }
        }
    }

    void process(Context context, ClassNode owner, MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return;
        }
        if (context != null && context.isArtificialMethod(owner.name, method.name, method.desc)) {
            return;
        }
        int count = method.instructions.size();
        if (count < MIN_INSTRUCTIONS || count > MAX_INSTRUCTIONS) {
            return;
        }
        int branches = config.hardening.deadBranchesPerMethod;
        if (branches <= 0) {
            return;
        }
        double overhead = Math.max(0.0, config.hardening.maxOverhead - 1.0);
        int budget = (int) (overhead * count);
        if (budget < 8) {
            return;
        }

        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
        } catch (Throwable e) {
            return; // unsupported shape (e.g. JSR) — leave the method alone
        }

        AbstractInsnNode floor = constructorFloor(method);
        List<AbstractInsnNode> candidates = new ArrayList<>();
        AbstractInsnNode[] instructions = method.instructions.toArray();
        boolean pastFloor = floor == null;
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode insn = instructions[i];
            if (insn == floor) {
                pastFloor = true;
                continue;
            }
            if (!pastFloor || insn.getOpcode() < 0) {
                continue;
            }
            Frame<BasicValue> frame = frames[i];
            if (frame == null || frame.getStackSize() != 0) {
                continue;
            }
            candidates.add(insn);
        }
        if (candidates.isEmpty()) {
            return;
        }

        Mix.Rand rand = new Mix.Rand(Mix.of(config.seed, owner.name, method.name + method.desc, 0xD00D));
        int saltSlot = method.maxLocals++;
        int junkSlot = method.maxLocals++;

        InsnList init = new InsnList();
        init.add(Insns.intNode(rand.nextInt()));
        init.add(new VarInsnNode(Opcodes.ISTORE, saltSlot));
        if (floor != null) {
            method.instructions.insert(floor, init);
        } else {
            method.instructions.insert(init);
        }

        int added = 2;
        for (int b = 0; b < branches && !candidates.isEmpty(); b++) {
            if (added + 24 > budget) {
                break;
            }
            AbstractInsnNode anchor = candidates.remove(rand.nextInt(candidates.size()));
            added += inject(method, anchor, saltSlot, junkSlot, rand);
        }

        // Decoys (and the salt init) push up to a few slots at stack-empty anchors. FlowShuffle
        // and FlowFlatten size their analyzer frames from maxStack; the writer recomputes the
        // real value with COMPUTE_MAXS, so keeping the bound truthful costs nothing.
        method.maxStack += 4;
    }

    /** The first {@code invokespecial <init>} of a constructor — everything before it is off limits. */
    private static AbstractInsnNode constructorFloor(MethodNode method) {
        if (!"<init>".equals(method.name)) {
            return null;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)) {
                return insn;
            }
        }
        return null;
    }

    /** @return approximate number of instructions added. */
    private static int inject(MethodNode method, AbstractInsnNode anchor, int saltSlot, int junkSlot, Mix.Rand rand) {
        LabelNode junk = new LabelNode();
        LabelNode after = new LabelNode();
        InsnList out = new InsnList();

        OpaquePredicates.jumpFalse(out, saltSlot, rand.nextInt(OpaquePredicates.FALSE_FORMS), junk);
        out.add(new JumpInsnNode(Opcodes.GOTO, after));
        out.add(junk);
        int junkCount = Junk.emit(out, rand, junkSlot);
        out.add(new JumpInsnNode(Opcodes.GOTO, after));
        out.add(after);

        method.instructions.insertBefore(anchor, out);
        return 5 + junkCount;
    }
}
