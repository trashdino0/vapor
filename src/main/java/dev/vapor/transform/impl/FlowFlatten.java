package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.transform.Transformer;
import dev.vapor.tree.JClass;
import dev.vapor.util.Insns;
import dev.vapor.util.Mix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.3: bounded control-flow flattening.
 *
 * <p>An eligible method becomes a state machine. Every block is moved into a
 * dispatcher section and reached only through a {@code lookupswitch} on a state
 * local; each block's terminator sets the successor's state and jumps back to the
 * dispatcher. Return/throw blocks stay untouched. Physical block order and every
 * state value are derived from {@link Mix}, so output stays deterministic.</p>
 *
 * <p><b>Safety rules</b> (verifier constraints, informed by the reference study of
 * Aidsfuscator's flattening):</p>
 * <ul>
 *   <li><b>Empty entry stacks only.</b> A dispatcher is a frame-merge point; a block
 *       that expects values on the stack cannot be reached through it. Methods with
 *       any such block are skipped wholesale (ternaries and short-circuit
 *       expressions routinely leave values live across branches).</li>
 *   <li><b>One dispatcher per frame signature.</b> The switch merges the frames of
 *       all paths that reach it; blocks with different local types must not share a
 *       dispatcher or the merged frame would be weaker than what the block's code
 *       verifies against. Signatures are computed with an <em>exact-type</em>
 *       interpreter — ASM's {@code BasicInterpreter} collapses every reference type
 *       to {@code Object} and would be unsound here.</li>
 *   <li><b>Random, non-sequential states.</b> Unique ints from {@link Mix}, not
 *       {@code 0..n}; the default case throws {@code IllegalStateException}
 *       (defense in depth).</li>
 *   <li><b>Fail-safe skips:</b> abstract/native, constructors, artificials,
 *       try/catch (also covers synchronized blocks), JSR/RET, analyzer failure,
 *       fewer than {@link #MIN_BLOCKS} blocks, more than
 *       {@code hardening.flattenMaxBlocks} blocks, or more than
 *       {@link #MAX_INSTRUCTIONS} instructions.</li>
 * </ul>
 */
public final class FlowFlatten extends Transformer {

    private static final int MIN_BLOCKS = 3;
    private static final int MAX_INSTRUCTIONS = 2000;

    private final VaporConfig config;

    public FlowFlatten(VaporConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "FlowFlatten";
    }

    @Override
    public boolean enabled() {
        return config.transformers.flowFlatten;
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
            return; // uninitializedThis across the dispatcher is not worth the risk
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return;
        }
        if (context != null && context.isArtificialMethod(owner.name, method.name, method.desc)) {
            return; // decryptors stay fast/small
        }
        int instructionCount = method.instructions.size();
        if (instructionCount < MIN_BLOCKS || instructionCount > MAX_INSTRUCTIONS) {
            return;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                return;
            }
        }

        List<Block> blocks = split(method);
        if (blocks == null || blocks.size() < MIN_BLOCKS
                || blocks.size() > Math.max(2, config.hardening.flattenMaxBlocks)) {
            return;
        }

        ensureMaxLocals(method);
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new ExactInterpreter()).analyze(owner.name, method);
        } catch (AnalyzerException e) {
            return;
        }

        Map<LabelNode, Integer> labelBlocks = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            for (LabelNode label : block.leadingLabels) {
                labelBlocks.put(label, i);
            }
            int index = method.instructions.indexOf(block.start);
            Frame<BasicValue> frame = index < 0 ? null : frames[index];
            if (frame == null || frame.getStackSize() != 0) {
                return; // frame unavailable / values live across a branch: skip method
            }
            block.frame = frame;
        }
        if (!resolve(blocks, labelBlocks)) {
            return;
        }

        // Deterministic, unique, non-sequential state values.
        int[] keys = new int[blocks.size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            int candidate = Mix.of(config.seed, owner.name, method.name + method.desc, 0x51F000 + i * 0x9E37);
            while (!seen.add(candidate)) {
                candidate += 0x9E3779B9;
            }
            keys[i] = candidate;
        }

        // One dispatcher per exact entry-frame signature, ordered by first block use.
        // Ambiguous frames (null signature) get a private dispatcher each.
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            String signature = signature(blocks.get(i).frame);
            String key = signature == null ? "#" + i : signature;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> groups = new ArrayList<>(grouped.values());
        Map<Integer, Integer> groupOf = new HashMap<>();
        for (int group = 0; group < groups.size(); group++) {
            for (int block : groups.get(group)) {
                groupOf.put(block, group);
            }
        }

        int stateSlot = method.maxLocals;
        method.maxLocals = stateSlot + 1;

        LabelNode[] blockLabels = new LabelNode[blocks.size()];
        for (int i = 0; i < blockLabels.length; i++) {
            blockLabels[i] = new LabelNode();
        }
        LabelNode[] loopLabels = new LabelNode[groups.size()];
        LabelNode[] defaultLabels = new LabelNode[groups.size()];
        for (int group = 0; group < groups.size(); group++) {
            loopLabels[group] = new LabelNode();
            defaultLabels[group] = new LabelNode();
        }

        // Detach the original nodes so they can be re-linked in the flattened order.
        InsnList original = method.instructions;
        while (original.size() > 0) {
            original.remove(original.getFirst());
        }

        Emitter emitter = new Emitter(stateSlot, keys, groupOf, loopLabels);
        InsnList out = new InsnList();
        out.add(Insns.intNode(keys[0]));
        out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
        out.add(new JumpInsnNode(Opcodes.GOTO, loopLabels[groupOf.get(0)]));

        for (int group = 0; group < groups.size(); group++) {
            List<Integer> members = groups.get(group);
            Mix.Rand rand = new Mix.Rand(
                    Mix.of(config.seed, owner.name, method.name + method.desc, 0x9A5 + group));
            for (int i = members.size() - 1; i > 0; i--) {
                int j = rand.nextInt(i + 1);
                int swap = members.get(i);
                members.set(i, members.get(j));
                members.set(j, swap);
            }

            int[] caseKeys = new int[members.size()];
            LabelNode[] caseLabels = new LabelNode[members.size()];
            for (int i = 0; i < members.size(); i++) {
                int block = members.get(i);
                caseKeys[i] = keys[block];
                caseLabels[i] = blockLabels[block];
            }
            sortByKey(caseKeys, caseLabels);

            out.add(loopLabels[group]);
            out.add(new VarInsnNode(Opcodes.ILOAD, stateSlot));
            out.add(new LookupSwitchInsnNode(defaultLabels[group], caseKeys, caseLabels));

            for (int block : members) {
                emitter.block(out, blocks, block, blockLabels);
            }

            out.add(defaultLabels[group]);
            out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
            out.add(new InsnNode(Opcodes.DUP));
            out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
            out.add(new InsnNode(Opcodes.ATHROW));
        }

        method.instructions = out;

        // Reordering blocks invalidates local-variable ranges. The default pipeline strips
        // them earlier (MetadataHygiene), but stay self-contained and safe when stripping is
        // disabled in the config: debug ranges are dropped for rewritten methods only.
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    /** Emits state/value plumbing for one method. */
    private static final class Emitter {

        private final int stateSlot;
        private final int[] keys;
        private final Map<Integer, Integer> groupOf;
        private final LabelNode[] loopLabels;

        Emitter(int stateSlot, int[] keys, Map<Integer, Integer> groupOf, LabelNode[] loopLabels) {
            this.stateSlot = stateSlot;
            this.keys = keys;
            this.groupOf = groupOf;
            this.loopLabels = loopLabels;
        }

        void block(InsnList out, List<Block> blocks, int index, LabelNode[] blockLabels) {
            Block block = blocks.get(index);
            out.add(blockLabels[index]);
            for (AbstractInsnNode node : block.nodes) {
                if (node != block.terminator) {
                    out.add(node);
                }
            }

            AbstractInsnNode terminator = block.terminator;
            if (terminator == null) {
                // Block ended because the next instruction is a leader: fallthrough edge.
                state(out, block.successors[0]);
            } else if (terminator instanceof JumpInsnNode jump) {
                if (jump.getOpcode() == Opcodes.GOTO) {
                    state(out, block.successors[0]);
                } else {
                    int taken = block.successors[0];
                    int fallthrough = block.successors[1];
                    LabelNode takenLabel = new LabelNode();
                    out.add(new JumpInsnNode(jump.getOpcode(), takenLabel));
                    state(out, fallthrough);
                    out.add(takenLabel);
                    state(out, taken);
                }
            } else if (terminator instanceof TableSwitchInsnNode table) {
                LabelNode[] targets = switchTargets(out, block);
                out.add(new TableSwitchInsnNode(table.min, table.max, targets[0],
                        java.util.Arrays.copyOfRange(targets, 1, targets.length)));
                emitSwitchTrampolines(out, block);
            } else if (terminator instanceof LookupSwitchInsnNode lookup) {
                LabelNode[] targets = switchTargets(out, block);
                int[] sortedKeys = lookup.keys.stream().mapToInt(Integer::intValue).toArray();
                LabelNode[] sortedLabels = java.util.Arrays.copyOfRange(targets, 1, targets.length);
                sortByKey(sortedKeys, sortedLabels);
                out.add(new LookupSwitchInsnNode(targets[0], sortedKeys, sortedLabels));
                emitSwitchTrampolines(out, block);
            } else {
                // IRETURN..RETURN / ATHROW: the block leaves the method as before.
                out.add(terminator);
            }
        }

        private LabelNode[] switchTargets(InsnList out, Block block) {
            LabelNode[] targets = new LabelNode[block.successors.length];
            Map<Integer, LabelNode> byTarget = new LinkedHashMap<>();
            for (int i = 0; i < targets.length; i++) {
                int target = block.successors[i];
                LabelNode label = byTarget.computeIfAbsent(target, ignored -> new LabelNode());
                targets[i] = label;
            }
            block.switchTrampolines = byTarget;
            return targets;
        }

        private void emitSwitchTrampolines(InsnList out, Block block) {
            for (Map.Entry<Integer, LabelNode> entry : block.switchTrampolines.entrySet()) {
                out.add(entry.getValue());
                state(out, entry.getKey());
            }
            block.switchTrampolines = null;
        }

        private void state(InsnList out, int target) {
            out.add(Insns.intNode(keys[target]));
            out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
            out.add(new JumpInsnNode(Opcodes.GOTO, loopLabels[groupOf.get(target)]));
        }
    }

    /** @return basic blocks in instruction order, or {@code null} for unsupported shapes. */
    private static List<Block> split(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                targets.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }

        Set<AbstractInsnNode> leaders = new HashSet<>();
        Set<LabelNode> pendingLabels = new HashSet<>();
        AbstractInsnNode previous = null;
        boolean afterTerminator = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                pendingLabels.add(label);
                continue;
            }
            if (insn.getOpcode() < 0) {
                continue;
            }
            boolean leader = previous == null || afterTerminator;
            if (!leader) {
                for (LabelNode label : pendingLabels) {
                    if (targets.contains(label)) {
                        leader = true;
                        break;
                    }
                }
            }
            if (leader) {
                leaders.add(insn);
            }
            pendingLabels.clear();
            previous = insn;
            afterTerminator = endsBlock(insn);
        }

        List<Block> blocks = new ArrayList<>();
        Block current = null;
        List<AbstractInsnNode> pending = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                pending.add(insn);
                continue;
            }
            if (current == null || leaders.contains(insn)) {
                current = new Block();
                blocks.add(current);
                for (AbstractInsnNode node : pending) {
                    current.nodes.add(node);
                    if (node instanceof LabelNode label) {
                        current.leadingLabels.add(label);
                    }
                }
                pending.clear();
            } else {
                current.nodes.addAll(pending);
                pending.clear();
            }
            current.nodes.add(insn);
            if (current.start == null) {
                current.start = insn;
            }
            if (endsBlock(insn)) {
                current.terminator = insn;
                current = null;
            }
        }

        Set<LabelNode> mapped = new HashSet<>();
        for (Block block : blocks) {
            mapped.addAll(block.leadingLabels);
        }
        return mapped.containsAll(targets) ? blocks : null;
    }

    private static boolean endsBlock(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                || opcode == Opcodes.GOTO
                || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH
                || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN
                || opcode == Opcodes.ATHROW;
    }

    /** Fills {@link Block#successors}; false when an edge cannot be resolved. */
    private static boolean resolve(List<Block> blocks, Map<LabelNode, Integer> labelBlocks) {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int next = i + 1;
            AbstractInsnNode terminator = block.terminator;
            if (terminator == null) {
                if (next >= blocks.size()) {
                    return false;
                }
                block.successors = new int[] {next};
            } else if (terminator instanceof JumpInsnNode jump) {
                Integer target = labelBlocks.get(jump.label);
                if (target == null) {
                    return false;
                }
                if (jump.getOpcode() == Opcodes.GOTO) {
                    block.successors = new int[] {target};
                } else {
                    if (next >= blocks.size()) {
                        return false;
                    }
                    block.successors = new int[] {target, next};
                }
            } else if (terminator instanceof TableSwitchInsnNode table) {
                int[] successors = new int[table.labels.size() + 1];
                successors[0] = indexOf(labelBlocks, table.dflt);
                for (int j = 0; j < table.labels.size(); j++) {
                    successors[j + 1] = indexOf(labelBlocks, table.labels.get(j));
                }
                block.successors = successors;
            } else if (terminator instanceof LookupSwitchInsnNode lookup) {
                int[] successors = new int[lookup.labels.size() + 1];
                successors[0] = indexOf(labelBlocks, lookup.dflt);
                for (int j = 0; j < lookup.labels.size(); j++) {
                    successors[j + 1] = indexOf(labelBlocks, lookup.labels.get(j));
                }
                block.successors = successors;
            } else {
                block.successors = new int[0]; // return / athrow
            }
            for (int successor : block.successors) {
                if (successor < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int indexOf(Map<LabelNode, Integer> labelBlocks, LabelNode label) {
        Integer index = labelBlocks.get(label);
        return index == null ? -1 : index;
    }

    /**
     * Exact local-slot type key. Three cases matter:
     * <ul>
     *   <li>a typed slot → its descriptor;</li>
     *   <li>a Java-null slot → no path ever initialized it (real frame: TOP, unreadable) —
     *       shared marker, safe to group;</li>
     *   <li>a wide second slot → {@code W} (not independently addressable, the preceding
     *       slot's descriptor disambiguates);</li>
     *   <li>any other {@code UNINITIALIZED_VALUE} → the analyzer merged incompatible values
     *       (the real verifier may still see a usable common supertype), so the block gets a
     *       private dispatcher — merging it with unrelated wide/empty slots would turn a
     *       readable local into TOP.</li>
     * </ul>
     */
    private static String signature(Frame<BasicValue> frame) {
        StringBuilder builder = new StringBuilder(frame.getLocals() * 8);
        for (int i = 0; i < frame.getLocals(); i++) {
            BasicValue value = frame.getLocal(i);
            if (value == null) {
                builder.append("?;");
                continue;
            }
            Type type = value.getType();
            if (type == null) {
                BasicValue previous = i > 0 ? frame.getLocal(i - 1) : null;
                if (previous != null && previous.getType() != null && previous.getType().getSize() == 2) {
                    builder.append("W;");
                    continue;
                }
                return null; // ambiguous merge result: dispatcher must be private
            }
            builder.append(type.getDescriptor()).append(';');
        }
        return builder.toString();
    }

    private static boolean allLocalsKnown(Frame<BasicValue> frame) {
        for (int i = 0; i < frame.getLocals(); i++) {
            BasicValue value = frame.getLocal(i);
            if (value == null) {
                return false;
            }
        }
        return true;
    }

    /** Guards against code generators that under-report {@code maxLocals}. */
    private static void ensureMaxLocals(MethodNode method) {
        int max = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode variable) {
                boolean wide = variable.getOpcode() == Opcodes.LLOAD || variable.getOpcode() == Opcodes.DLOAD
                        || variable.getOpcode() == Opcodes.LSTORE || variable.getOpcode() == Opcodes.DSTORE;
                max = Math.max(max, variable.var + (wide ? 2 : 1));
            } else if (insn instanceof IincInsnNode increment) {
                max = Math.max(max, increment.var + 1);
            }
        }
        if (max > method.maxLocals) {
            method.maxLocals = max;
        }
    }

    /** Keeps switch keys ascending (the class-file format requires it; prudence over writer behavior). */
    private static void sortByKey(int[] keys, LabelNode[] labels) {
        for (int i = 1; i < keys.length; i++) {
            int key = keys[i];
            LabelNode label = labels[i];
            int j = i - 1;
            while (j >= 0 && keys[j] > key) {
                keys[j + 1] = keys[j];
                labels[j + 1] = labels[j];
                j--;
            }
            keys[j + 1] = key;
            labels[j + 1] = label;
        }
    }

    /** Keeps exact reference types (BasicInterpreter would collapse them all to Object). */
    private static final class ExactInterpreter extends BasicInterpreter {

        ExactInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                return new BasicValue(type);
            }
            return super.newValue(type);
        }
    }

    /** One basic block: its original nodes (pseudo nodes included) and resolved edges. */
    private static final class Block {

        final List<AbstractInsnNode> nodes = new ArrayList<>();
        final List<LabelNode> leadingLabels = new ArrayList<>();
        AbstractInsnNode start;
        AbstractInsnNode terminator;
        Frame<BasicValue> frame;
        int[] successors = new int[0];
        Map<Integer, LabelNode> switchTrampolines;
    }
}
