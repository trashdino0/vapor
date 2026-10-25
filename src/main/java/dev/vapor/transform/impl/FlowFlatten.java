package dev.vapor.transform.impl;

import dev.vapor.config.VaporConfig;
import dev.vapor.context.Context;
import dev.vapor.context.hierarchy.IHierarchy;
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
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * v3.3+: bounded control-flow flattening (v3.6 adds entry-stack spilling).
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
 *   <li><b>Entry stacks are spilled.</b> A dispatcher is a frame-merge point and must
 *       see an empty operand stack, but a block may expect values on the stack
 *       (ternaries and short-circuit expressions routinely leave values live across a
 *       branch). Every edge into such a block stores the incoming values into fresh
 *       locals; the target block reloads them inside its case. Dispatchers are grouped
 *       by local <em>and</em> entry-stack signature, and every edge into a group writes
 *       all of its spill locals, so the loop-head merge stays typed.</li>
 *   <li><b>One dispatcher per frame signature.</b> The switch merges the frames of
 *       all paths that reach it; blocks with different local types must not share a
 *       dispatcher or the merged frame would be weaker than what the block's code
 *       verifies against. Signatures are computed with an <em>exact-type</em>
 *       interpreter — ASM's {@code BasicInterpreter} collapses every reference type
 *       to {@code Object} and would be unsound here. Uninitialized {@code NEW}
 *       values are tracked by allocation site and transition to their initialized type
 *       at {@code <init>} (as the verifier does), so two different {@code new}
 *       instructions never share a spill slot and a value stored after construction
 *       keeps its real frame type.</li>
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

    /**
     * Set {@code -Dvapor.debugFlatten=true} to log per-method skip reasons on stderr; add
     * {@code -Dvapor.debugFlattenMethod=<name>} to dump one method's block signatures and groups.
     */
    private static final boolean DEBUG = Boolean.getBoolean("vapor.debugFlatten");

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
            skip(method, "abstract/native");
            return;
        }
        if ("<init>".equals(method.name)) {
            skip(method, "constructor");
            return; // uninitializedThis across the dispatcher is not worth the risk
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            skip(method, "try/catch table");
            return;
        }
        if (context != null && context.isArtificialMethod(owner.name, method.name, method.desc)) {
            skip(method, "artificial");
            return; // decryptors stay fast/small
        }
        int instructionCount = method.instructions.size();
        if (instructionCount < MIN_BLOCKS || instructionCount > MAX_INSTRUCTIONS) {
            skip(method, "instruction count " + instructionCount);
            return;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                skip(method, "JSR/RET");
                return;
            }
        }

        List<Block> blocks = split(method);
        if (blocks == null) {
            skip(method, "unresolved jump target");
            return;
        }
        int maxBlocks = Math.max(2, config.hardening.flattenMaxBlocks);
        if (blocks.size() < MIN_BLOCKS || blocks.size() > maxBlocks) {
            skip(method, "block count " + blocks.size());
            return;
        }

        IHierarchy hierarchy = context == null ? null : context.hierarchy();
        ensureMaxLocals(method);
        Map<AbstractInsnNode, Integer> newIds = new IdentityHashMap<>();
        int nextNew = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.NEW) {
                newIds.put(insn, nextNew++);
            }
        }
        Frame<BasicValue>[] frames;
        try {
            frames = new InitAnalyzer(new MergeInterpreter(hierarchy, newIds)).analyze(owner.name, method);
        } catch (AnalyzerException e) {
            skip(method, "analyzer: " + e.getMessage());
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
            if (frame == null) {
                skip(method, "unreachable block " + i);
                return;
            }
            block.frame = frame;
            int stackSize = frame.getStackSize();
            block.stackTypes = new Type[stackSize];
            StringBuilder stackSignature = new StringBuilder(stackSize * 8);
            for (int slot = 0; slot < stackSize; slot++) {
                BasicValue value = frame.getStack(slot);
                Type type = value == null ? null : value.getType();
                if (type == null || type.getSort() == Type.METHOD || type.getSort() == Type.VOID) {
                    skip(method, "unsupported stack value in block " + i);
                    return;
                }
                block.stackTypes[slot] = type;
                if (value instanceof UninitializedValue uninitialized) {
                    stackSignature.append('U').append(uninitialized.id);
                } else {
                    stackSignature.append(type.getDescriptor());
                }
                stackSignature.append(';');
            }
            block.stackSignature = stackSignature.toString();
        }
        if (blocks.get(0).stackTypes.length != 0) {
            skip(method, "non-empty entry stack");
            return;
        }
        if (!resolve(blocks, labelBlocks)) {
            skip(method, "edge resolution");
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

        // One dispatcher per local+stack signature, ordered by first block use. With hierarchy
        // knowledge (the pipeline) the merge matches the verifier, so TOP slots may group;
        // without it (unit fixtures) an ambiguous merge still gets a private dispatcher.
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            String locals = signature(blocks.get(i).frame, hierarchy != null);
            String key = locals == null ? "#" + i : locals + "||" + blocks.get(i).stackSignature;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> groups = new ArrayList<>(grouped.values());
        Map<Integer, Integer> groupOf = new HashMap<>();
        for (int group = 0; group < groups.size(); group++) {
            for (int block : groups.get(group)) {
                groupOf.put(block, group);
            }
        }

        String debugTarget = System.getProperty("vapor.debugFlattenMethod");
        if (DEBUG && debugTarget != null && debugTarget.equals(method.name)) {
            System.err.println("[flatten-target] " + owner.name + "." + method.name + method.desc
                    + " maxLocals=" + method.maxLocals + " blocks=" + blocks.size());
            for (int group = 0; group < groups.size(); group++) {
                System.err.println("[flatten-target] group " + group + " = " + groups.get(group));
            }
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                System.err.println("[flatten-target] block " + i + " group=" + groupOf.get(i)
                        + " stack=" + block.stackSignature
                        + " locals=" + signature(block.frame, hierarchy != null)
                        + " successors=" + java.util.Arrays.toString(block.successors));
            }
        }

        // Spill locals are shared by every block of a group (same entry-stack shape) and are
        // written on every edge into the group, so the loop-head merge keeps their types.
        int stateSlot = method.maxLocals;
        int cursor = stateSlot + 1;
        int spilled = 0;
        for (List<Integer> members : groups) {
            Block first = blocks.get(members.get(0));
            int count = first.stackTypes.length;
            if (count == 0) {
                continue;
            }
            int[] slots = new int[count];
            for (int slot = 0; slot < count; slot++) {
                slots[slot] = cursor;
                cursor += first.stackTypes[slot].getSize();
                spilled++;
            }
            if (cursor > 65535) {
                skip(method, "spill local overflow");
                return;
            }
            for (int member : members) {
                Block block = blocks.get(member);
                if (block.stackTypes.length != count) {
                    skip(method, "inconsistent spill shape");
                    return;
                }
                block.spillLocals = slots;
            }
        }
        method.maxLocals = cursor;

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

        Emitter emitter = new Emitter(stateSlot, keys, groupOf, loopLabels, blocks);
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
                emitter.block(out, block, blockLabels);
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
        if (DEBUG) {
            System.err.println("[flatten] ok " + method.name + method.desc
                    + ": blocks=" + blocks.size() + ", dispatchers=" + groups.size()
                    + ", spilled=" + spilled);
        }
    }

    private static void skip(MethodNode method, String reason) {
        if (DEBUG) {
            System.err.println("[flatten] skip " + method.name + method.desc + ": " + reason);
        }
    }

    /** Emits state/value plumbing for one method. */
    private static final class Emitter {

        private final int stateSlot;
        private final int[] keys;
        private final Map<Integer, Integer> groupOf;
        private final LabelNode[] loopLabels;
        private final List<Block> blocks;

        Emitter(int stateSlot, int[] keys, Map<Integer, Integer> groupOf, LabelNode[] loopLabels,
                List<Block> blocks) {
            this.stateSlot = stateSlot;
            this.keys = keys;
            this.groupOf = groupOf;
            this.loopLabels = loopLabels;
            this.blocks = blocks;
        }

        void block(InsnList out, int index, LabelNode[] blockLabels) {
            Block block = blocks.get(index);
            out.add(blockLabels[index]);
            reload(out, block);
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

        /** Reloads a block's incoming operand stack inside its case (bottom to top). */
        private static void reload(InsnList out, Block block) {
            int[] slots = block.spillLocals;
            if (slots == null) {
                return;
            }
            for (int slot = 0; slot < slots.length; slot++) {
                out.add(new VarInsnNode(block.stackTypes[slot].getOpcode(Opcodes.ILOAD), slots[slot]));
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
            Block block = blocks.get(target);
            int[] slots = block.spillLocals;
            if (slots != null) {
                for (int slot = slots.length - 1; slot >= 0; slot--) {
                    out.add(new VarInsnNode(block.stackTypes[slot].getOpcode(Opcodes.ISTORE), slots[slot]));
                }
            }
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
     * Local-slot type key for dispatcher grouping.
     * <ul>
     *   <li>{@code NEW} value → {@code U<id>} allocation-site marker until its
     *       {@code <init>} transition;</li>
     *   <li>typed slot → descriptor;</li>
     *   <li>null slot → no path ever initialized it (TOP, unreadable) — {@code ?};</li>
     *   <li>wide second slot → {@code W} (the preceding descriptor disambiguates);</li>
     *   <li>{@code UNINITIALIZED_VALUE} → TOP when merging follows verifier semantics
     *       ({@code topGroupable}); otherwise an ambiguous analyzer merge that may hide a
     *       usable common supertype, so the block gets a private dispatcher ({@code null}).</li>
     * </ul>
     */
    private static String signature(Frame<BasicValue> frame, boolean topGroupable) {
        StringBuilder builder = new StringBuilder(frame.getLocals() * 8);
        for (int i = 0; i < frame.getLocals(); i++) {
            BasicValue value = frame.getLocal(i);
            if (value == null) {
                builder.append("?;");
                continue;
            }
            if (value instanceof UninitializedValue uninitialized) {
                builder.append('U').append(uninitialized.id).append(';');
                continue;
            }
            Type type = value.getType();
            if (type == null) {
                BasicValue previous = i > 0 ? frame.getLocal(i - 1) : null;
                if (previous != null && previous.getType() != null && previous.getType().getSize() == 2) {
                    builder.append("W;");
                    continue;
                }
                if (!topGroupable) {
                    return null; // ambiguous merge result: dispatcher must be private
                }
                builder.append("?;");
                continue;
            }
            builder.append(type.getDescriptor()).append(';');
        }
        return builder.toString();
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

    /**
     * Exact reference types plus verifier-style reference merging. ASM's
     * {@code BasicInterpreter} collapses every reference to {@code Object}; the exact
     * variant alone marks merged references as ambiguous, which fragments methods into a
     * dispatcher per block. With a hierarchy we compute the same common supertype the
     * writer's {@code getCommonSuperClass} will use, so merged slots keep their real frame
     * type; without one (unit fixtures) merging stays exact/conservative.
     */
    private static final class MergeInterpreter extends BasicInterpreter {

        private final IHierarchy hierarchy;
        private final Map<AbstractInsnNode, Integer> newIds;

        MergeInterpreter(IHierarchy hierarchy, Map<AbstractInsnNode, Integer> newIds) {
            super(Opcodes.ASM9);
            this.hierarchy = hierarchy;
            this.newIds = newIds;
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.NEW) {
                Integer id = newIds.get(insn);
                if (id != null) {
                    return new UninitializedValue(Type.getObjectType(((TypeInsnNode) insn).desc), id);
                }
            }
            return super.newOperation(insn);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                return new BasicValue(type);
            }
            return super.newValue(type);
        }

        @Override
        public BasicValue merge(BasicValue value1, BasicValue value2) {
            if (value1.equals(value2)) {
                return value1;
            }
            if (value1 instanceof UninitializedValue || value2 instanceof UninitializedValue) {
                return BasicValue.UNINITIALIZED_VALUE; // different allocation sites never merge
            }
            Type type1 = value1.getType();
            Type type2 = value2.getType();
            if (type1 != null && type2 != null) {
                // The verifier's null type is assignable to every reference; merging it with
                // a class must keep the class (a plain Object fallback would widen frames).
                if (isNullType(type1)) {
                    return value2;
                }
                if (isNullType(type2)) {
                    return value1;
                }
            }
            if (hierarchy == null || type1 == null || type2 == null) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            boolean reference1 = type1.getSort() == Type.OBJECT || type1.getSort() == Type.ARRAY;
            boolean reference2 = type2.getSort() == Type.OBJECT || type2.getSort() == Type.ARRAY;
            if (!reference1 || !reference2) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            String common = hierarchy.getCommonSuperClass(name(type1), name(type2));
            return reference(common);
        }

        private static boolean isNullType(Type type) {
            return type.getSort() == Type.OBJECT && "null".equals(type.getInternalName());
        }

        private static String name(Type type) {
            return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
        }

        private static BasicValue reference(String name) {
            return new BasicValue(name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name));
        }
    }

    /** A value produced by {@code NEW} before its {@code <init>} call. Two sites never merge. */
    private static final class UninitializedValue extends BasicValue {

        final int id;

        UninitializedValue(Type type, int id) {
            super(type);
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UninitializedValue value)) {
                return false;
            }
            return id == value.id && Objects.equals(getType(), value.getType());
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(getType()) + id;
        }

        @Override
        public String toString() {
            return "uninitialized(" + id + ") " + getType();
        }
    }

    /** Analyzer that produces {@link InitFrame}s (verifier-style NEW/&lt;init&gt; tracking). */
    private static final class InitAnalyzer extends Analyzer<BasicValue> {

        InitAnalyzer(MergeInterpreter interpreter) {
            super(interpreter);
        }

        @Override
        protected Frame<BasicValue> newFrame(int numLocals, int numStack) {
            return new InitFrame<>(numLocals, numStack);
        }

        @Override
        protected Frame<BasicValue> newFrame(Frame<? extends BasicValue> frame) {
            return new InitFrame<>(frame);
        }
    }

    /**
     * The analysis interpreter marks each {@code NEW} with an allocation-site id, but copies of
     * that value (on the stack or in locals) are only uninitialized until the matching
     * {@code invokespecial <init>}. The verifier then replaces <em>every</em> copy with the
     * initialized type; without that transition a value stored after its constructor (common:
     * {@code new X(...); astore n}) would keep the marker and degrade joins to TOP, which is
     * weaker than the real frame. This frame applies the same transition.
     */
    private static final class InitFrame<V extends BasicValue> extends Frame<V> {

        InitFrame(int numLocals, int numStack) {
            super(numLocals, numStack);
        }

        InitFrame(Frame<? extends V> frame) {
            super(frame);
        }

        @Override
        public void execute(AbstractInsnNode insn, Interpreter<V> interpreter) throws AnalyzerException {
            int initId = -1;
            Type initType = null;
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode method = (MethodInsnNode) insn;
                if ("<init>".equals(method.name)) {
                    int index = getStackSize() - Type.getArgumentTypes(method.desc).length - 1;
                    if (index >= 0) {
                        V reference = getStack(index);
                        if (reference instanceof UninitializedValue uninitialized) {
                            initId = uninitialized.id;
                            initType = uninitialized.getType();
                        }
                    }
                }
            }
            super.execute(insn, interpreter);
            if (initId < 0) {
                return;
            }
            @SuppressWarnings("unchecked")
            V initialized = (V) new BasicValue(initType);
            for (int i = 0; i < getLocals(); i++) {
                V value = getLocal(i);
                if (value instanceof UninitializedValue uninitialized && uninitialized.id == initId) {
                    setLocal(i, initialized);
                }
            }
            Object[] stack = new Object[getStackSize()];
            for (int i = 0; i < stack.length; i++) {
                V value = getStack(i);
                stack[i] = value instanceof UninitializedValue uninitialized && uninitialized.id == initId
                        ? initialized : value;
            }
            clearStack();
            for (Object value : stack) {
                @SuppressWarnings("unchecked")
                V typed = (V) value;
                push(typed);
            }
        }
    }

    /** One basic block: its original nodes (pseudo nodes included) and resolved edges. */
    private static final class Block {

        final List<AbstractInsnNode> nodes = new ArrayList<>();
        final List<LabelNode> leadingLabels = new ArrayList<>();
        AbstractInsnNode start;
        AbstractInsnNode terminator;
        Frame<BasicValue> frame;
        Type[] stackTypes = new Type[0];
        String stackSignature = "";
        int[] spillLocals;
        int[] successors = new int[0];
        Map<Integer, LabelNode> switchTrampolines;
    }
}
