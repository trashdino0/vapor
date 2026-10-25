package dev.vapor.testing;

/**
 * Constructor shapes for {@code FlowFlattenConstructorTest}: a provable pre-super
 * prefix (plain arguments), a super call that can throw, a post-super loop, a
 * {@code this()} delegation, and an intentionally unprovable ternary argument.
 */
public class CtorFixture extends CtorBase {

    private static final Object LOCK = new Object();

    private int value;
    private String text;
    private final int seed;

    /** super(int) then try/catch: the prefix is four straight-line instructions. */
    public CtorFixture(int v) {
        super(v);
        try {
            this.value = 100 / v;
        } catch (ArithmeticException e) {
            this.value = -1;
        }
        this.text = "v" + value;
        this.seed = v;
    }

    /** super(int) then a loop (no exception table): constructor flattening without handlers. */
    public CtorFixture(int v, long salt) {
        super(v);
        this.seed = (int) salt;
        int acc = 0;
        for (int i = 0; i < 4; i++) {
            acc = acc * 31 + (v + i);
        }
        this.value = acc;
        this.text = "s" + acc;
    }

    /** this(int) delegation with a straight-line prefix, then try/catch in the suffix. */
    public CtorFixture(int v, boolean flag) {
        this(v + 1);
        try {
            this.value = flag ? 50 / v : 0;
        } catch (ArithmeticException e) {
            this.value = -2;
        }
        this.text = "d" + value;
    }

    /** super(int) then a synchronized block with a nested try/catch (two handler shapes). */
    public CtorFixture(int v, double d) {
        super(v);
        int acc;
        synchronized (LOCK) {
            try {
                acc = 100 / v;
            } catch (ArithmeticException e) {
                acc = -5;
            }
        }
        for (int i = 0; i < 2; i++) {
            acc += i;
        }
        this.value = acc;
        this.text = "m" + d;
        this.seed = v;
    }

    /** super(helper(v)): the prefix calls a method that can throw, but never branches. */
    public CtorFixture(int v, char c) {
        super(check(v));
        int acc = 0;
        for (int i = 0; i < 3; i++) {
            acc += v + i;
        }
        this.value = acc;
        this.text = "c" + c;
        this.seed = v;
    }

    /**
     * Deliberately unprovable: the pre-super prefix branches while evaluating the
     * delegation argument. The suffix is flattenable, so a zero switch count proves
     * the prefix guard (not the block minimum) kept it untouched.
     */
    public CtorFixture(int v, String tag) {
        this(tag == null ? v : v + tag.length());
        try {
            this.value = 100 / v;
        } catch (ArithmeticException e) {
            this.value = -3;
        }
        this.text = tag;
    }

    private static int check(int v) {
        if (v == 4) {
            throw new IllegalStateException("four");
        }
        return v;
    }

    public int value() {
        return value;
    }

    public String text() {
        return text;
    }

    public int seed() {
        return seed;
    }

    @Override
    public String toString() {
        return base + "/" + value + "/" + text + "/" + seed;
    }
}
