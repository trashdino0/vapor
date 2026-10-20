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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * v2: control-flow noise, three in-place techniques that need no block
 * reordering (and therefore no dependency analysis):
 *
 * <ol>
 *   <li><b>condition perturbation</b> — every int comparison gets a runtime-zero
 *       XOR'd into one operand: {@code if (x != 0)} becomes
 *       {@code if ((x ^ z) != 0)} where {@code z} is an opaque zero. Stack-neutral
 *       and valid mid-expression;</li>
 *   <li><b>jump inversion</b> — {@code if (c) goto L} becomes
 *       {@code if (!c) goto S; goto L; S:}. Consumes the same operands, adds a
 *       real branch;</li>
 *   <li><b>guard splits</b> — {@code C:} becomes
 *       {@code if (&lt;always true&gt;) goto C; &lt;junk&gt;; C:}. The decoy block is
 *       self-contained junk that falls through harmlessly — even if the guard
 *       were wrong, behavior is unchanged.</li>
 * </ol>
 *
 * <p>Constructors are skipped entirely (v2 keeps the pre-super() region
 * untouched). Artificial methods (decryptors) are skipped. Budgeted via
 * {@code hardening.maxOverhead} and {@code hardening.flowSplitsPerMethod}.</p>
 */
public final class FlowShuffle extends Transformer {

    private static final int MIN_INSTRUCTIONS = 8;
    private static final int MAX_INSTRUCTIONS = 20000;
    private static final int PERTURB_COST = 12;

    private final VaporConfig config;

    public FlowShuffle(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "FlowShuffle";
    }

    @Override
    public boolean enabled() {
        return config.transformers.flowShuffle;
    }

    @Override
    public void run(Context context) {
        for (JClass clazz : context.classes()) {
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
        if ("<init>".equals(method.name)) {
            return; // v2 leaves the pre-super() region (and constructors) alone
        }
        if (context != null && context.isArtificialMethod(owner.name, method.name, method.desc)) {
            return;
        }
        int count = method.instructions.size();
        if (count < MIN_INSTRUCTIONS || count > MAX_INSTRUCTIONS) {
            return;
        }
        int cap = config.hardening.flowSplitsPerMethod;
        if (cap <= 0) {
            return;
        }
        double overhead = Math.max(0.0, config.hardening.maxOverhead - 1.0);
        int budget = (int) (overhead * count);
        if (budget < 8) {
            return;
        }

        // Guard splits resume at the anchor, so the anchor must start with an empty operand
        // stack: otherwise the resume label becomes a jump target with values live across it,
        // which blocks FlowFlatten (its dispatcher requires empty-stack block entries).
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
        } catch (Throwable e) {
            frames = null; // unsupported shape: skip guard splits, keep the other techniques
        }

        Mix.Rand rand = new Mix.Rand(Mix.of(config.seed, owner.name, method.name + method.desc, 0xF10F));
        int saltSlot = -1;
        int junkSlot = -1;
        int added = 0;
        int edits = 0;

        List<JumpInsnNode> jumps = new ArrayList<>();
        List<AbstractInsnNode> anchors = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            if (insn instanceof JumpInsnNode jump
                    && jump.getOpcode() != Opcodes.GOTO
                    && jump.getOpcode() != Opcodes.JSR) {
                jumps.add(jump);
            } else if (frames != null) {
                Frame<BasicValue> frame = index < frames.length ? frames[index] : null;
                if (frame != null && frame.getStackSize() == 0) {
                    anchors.add(insn);
                }
            }
        }

        // 1. guard splits (anchors exclude jumps, so later jump edits are unaffected)
        int guards = Math.max(1, cap / 3);
        while (guards > 0 && !anchors.isEmpty() && edits < cap) {
            int junkSize = 6 + rand.nextInt(10);
            if (added + junkSize + PERTURB_COST > budget) {
                break;
            }
            AbstractInsnNode anchor = anchors.remove(rand.nextInt(anchors.size()));
            if (saltSlot < 0) {
                saltSlot = allocSlot(method);
                insertSaltInit(method, saltSlot, rand);
                added += 2;
            }
            if (junkSlot < 0) {
                junkSlot = allocSlot(method);
            }
            LabelNode resume = new LabelNode();
            InsnList seq = new InsnList();
            OpaquePredicates.jumpTrue(seq, saltSlot, rand.nextInt(OpaquePredicates.TRUE_FORMS), resume);
            Junk.emit(seq, rand, junkSlot);
            seq.add(resume);
            method.instructions.insertBefore(anchor, seq);
            added += PERTURB_COST + junkSize;
            edits++;
            guards--;
        }

        // 2. condition perturbation (all int comparisons, capped)
        if (edits < cap && added + PERTURB_COST <= budget) {
            int perturb = cap;
            for (JumpInsnNode jump : jumps) {
                if (perturb <= 0 || edits >= cap || added + PERTURB_COST > budget) {
                    break;
                }
                if (!isIntCondition(jump.getOpcode())) {
                    continue;
                }
                if (saltSlot < 0) {
                    saltSlot = allocSlot(method);
                    insertSaltInit(method, saltSlot, rand);
                    added += 2;
                }
                InsnList seq = new InsnList();
                OpaquePredicates.pushZero(seq, saltSlot, rand.nextInt(OpaquePredicates.ZERO_FORMS));
                seq.add(new InsnNode(Opcodes.IXOR));
                method.instructions.insertBefore(jump, seq);
                added += PERTURB_COST;
                edits++;
                perturb--;
            }
        }

        // 3. jump inversion last: it detaches nodes, so nobody anchors on them afterwards
        int inversions = Math.max(1, cap / 3);
        for (int i = 0; i < jumps.size() && inversions > 0 && edits < cap; i++) {
            if ((i & 1) == 0 || added + 2 > budget) {
                continue;
            }
            invert(method, jumps.get(i));
            added += 2;
            edits++;
            inversions--;
        }

        if (edits > 0) {
            // Inserted predicates/junk push a few slots. FlowFlatten sizes its analyzer
            // frames from maxStack (the writer recomputes the real value with COMPUTE_MAXS).
            method.maxStack += 4;
        }
    }

    /** Allocates a fresh local slot (writer recomputes maxLocals, the counter must not collide). */
    private static int allocSlot(MethodNode method) {
        return method.maxLocals++;
    }

    /** Inserts {@code salt = <constant>} at the head; dominates every later use. */
    private static void insertSaltInit(MethodNode method, int slot, Mix.Rand rand) {
        InsnList init = new InsnList();
        init.add(Insns.intNode(rand.nextInt()));
        init.add(new VarInsnNode(Opcodes.ISTORE, slot));
        method.instructions.insert(init);
    }

    private static boolean isIntCondition(int opcode) {
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE)
                || (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE);
    }

    /** {@code if (c) goto L} becomes {@code if (!c) goto S; goto L; S:}. */
    private static void invert(MethodNode method, JumpInsnNode jump) {
        int inverse = inverseOf(jump.getOpcode());
        if (inverse < 0) {
            return;
        }
        LabelNode skip = new LabelNode();
        InsnList seq = new InsnList();
        seq.add(new JumpInsnNode(inverse, skip));
        seq.add(new JumpInsnNode(Opcodes.GOTO, jump.label));
        seq.add(skip);
        method.instructions.insertBefore(jump, seq);
        method.instructions.remove(jump);
    }

    private static int inverseOf(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> -1;
        };
    }
}
