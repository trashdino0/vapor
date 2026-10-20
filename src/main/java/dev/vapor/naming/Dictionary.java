package dev.vapor.naming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates obfuscated names: {@code a, b, ..., z, aa, ab, ...}.
 *
 * <p>Names already present in the input (or already handed out) are skipped, so
 * a generated name can never collide with an original one. With a non-zero
 * seed the sequence is shuffled in deterministic blocks — same seed + same
 * input still produce the same output.</p>
 */
public final class Dictionary {

    private static final int BLOCK = 512;

    private final long seed;
    private final Set<String> taken = new HashSet<>();
    private final List<String> block = new ArrayList<>();
    private int blockIndex = -1;
    private int position;
    private int counter;

    public Dictionary(long seed, Collection<String> reserved) {
        this.seed = seed;
        this.taken.addAll(reserved);
    }

    /** @return the next unused generated name */
    public String next() {
        while (true) {
            if (position >= block.size()) {
                refill();
            }
            String candidate = block.get(position++);
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Try to use an exact composed name (e.g. {@code outer$inner}).
     *
     * @return true when the name was free and is now claimed
     */
    public boolean claim(String name) {
        return taken.add(name);
    }

    private void refill() {
        block.clear();
        position = 0;
        blockIndex++;
        for (int i = 0; i < BLOCK; i++) {
            block.add(nameFor(counter++));
        }
        if (seed != 0) {
            Collections.shuffle(block, new Random(seed * 0x9E3779B97F4A7C15L + blockIndex));
        }
    }

    /** Bijective base-26: 0 -&gt; a, 25 -&gt; z, 26 -&gt; aa, 27 -&gt; ab, ... */
    public static String nameFor(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            n--;
            sb.append((char) ('a' + n % 26));
            n /= 26;
        }
        return sb.reverse().toString();
    }
}
