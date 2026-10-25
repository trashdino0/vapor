package dev.vapor.testing;

/** Superclass for {@link CtorFixture}; its constructor can throw before super() returns. */
public class CtorBase {

    protected final int base;

    public CtorBase(int v) {
        if (v < -1000) {
            throw new IllegalArgumentException("base " + v);
        }
        this.base = v;
    }

    public int base() {
        return base;
    }
}
