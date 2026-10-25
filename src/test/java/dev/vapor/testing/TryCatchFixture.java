package dev.vapor.testing;

/**
 * Compiler-produced exception control flow used by {@code FlowFlattenTryCatchTest}.
 * Every method is deterministic, has no side effects outside its own locals and is
 * compared one-to-one against the same method in the transformed class.
 */
public final class TryCatchFixture {

    private static final Object LOCK = new Object();

    private TryCatchFixture() {
    }

    /** try + return inside try, catch + return inside handler (single dispatched block). */
    public static int guarded(int v) {
        try {
            return 103 / v + v * 3;
        } catch (ArithmeticException e) {
            return -1000 + v;
        }
    }

    /** try/catch/finally with a shared catch type and a normal path after the region. */
    public static int finallyFlow(int v) {
        int r = v;
        try {
            if (v == 0) {
                throw new IllegalStateException("zero");
            }
            r += 100 / v;
        } catch (IllegalStateException | ArithmeticException e) {
            r = -999;
        } finally {
            r = r * 3 + 1;
        }
        return r;
    }

    /** An exception thrown by finally replaces the exception thrown by try. */
    public static int finallyOverrides(int v) {
        try {
            if (v == 0) {
                throw new IllegalStateException("try");
            }
            return 7;
        } finally {
            if (v == 0) {
                throw new IllegalArgumentException("finally");
            }
        }
    }

    /** A return in finally overrides both the try and catch outcomes. */
    public static int finallyReturn(int v) {
        try {
            return v;
        } finally {
            if ((v & 1) == 0) {
                return -v;
            }
        }
    }

    /** The inner finally handler is itself covered by the outer try range. */
    public static int nestedFinally(int v) {
        int r = v;
        try {
            try {
                r += 100 / v;
            } finally {
                r += 1;
            }
        } catch (ArithmeticException e) {
            return -r;
        }
        return r;
    }

    /** A try/catch inside a loop: the handler is re-entered on every iteration. */
    public static int loopTry(int v) {
        int acc = 0;
        for (int i = 1; i <= 6; i++) {
            try {
                acc += 120 / (i - 3) + v;
            } catch (ArithmeticException e) {
                acc -= 100;
            }
        }
        return acc;
    }

    /** Two handlers with different catch types and a shared throwing region. */
    public static int multiCatch(int v) {
        Object[] values = new Object[2];
        try {
            Object boxed = Integer.valueOf(v);
            if ((v & 1) == 0) {
                return ((String) values[0]).length();
            }
            return ((String) boxed).length();
        } catch (NullPointerException e) {
            return -10;
        } catch (ClassCastException e) {
            return -20;
        }
    }

    /** Catch-all entry ({@code type == null}) directly around the throwing code. */
    public static int catchAll(int v) {
        try {
            if (v < 0) {
                throw new IllegalArgumentException("negative");
            }
            return v + 1;
        } catch (RuntimeException e) {
            return -v;
        }
    }

    /** javac's synthetic monitorexit catch-all wraps the flattened region. */
    public static int synchronizedBlock(int v) {
        synchronized (LOCK) {
            try {
                return 100 / v;
            } catch (ArithmeticException e) {
                return -1;
            }
        }
    }

    /** The handler rethrows the same exception object. */
    public static int rethrow(int v) {
        try {
            if (v == 0) {
                throw new IllegalStateException("zero");
            }
            return v + 5;
        } catch (IllegalStateException e) {
            throw e;
        }
    }

    /** The handler itself contains a switch, so it leaves through several state edges. */
    public static int catchSwitch(int v) {
        try {
            return 10 / v;
        } catch (ArithmeticException e) {
            switch (v & 3) {
                case 0:
                    return -1;
                case 1:
                    return -2;
                default:
                    return -3;
            }
        }
    }

    /** A long live across a branch inside the protected region (wide spill + handler). */
    public static long longTry(long v, int w) {
        try {
            long scaled = w > 0 ? v * 3 : v / 3;
            return scaled + 10 / w;
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    /** Code before and after the protected region. */
    public static int tryAfterCode(int v) {
        int a = v * 2;
        try {
            a += 10 / v;
        } catch (ArithmeticException e) {
            a = -a;
        }
        int b = a * 3;
        return b;
    }

    /** A handler constructs a new exception (NEW live across the handler's own edge). */
    public static int nestedCatchRethrow(int v) {
        try {
            try {
                if (v == 0) {
                    throw new ArithmeticException("zero");
                }
                return 100 / v;
            } catch (ArithmeticException e) {
                throw new IllegalStateException("wrapped", e);
            }
        } catch (IllegalStateException e) {
            return e.getCause() == null ? -1 : -2;
        }
    }
}
