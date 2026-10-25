package dev.vapor.transform.impl;

/**
 * Compiled fixture for {@link VirtualizerTest}. Only the methods documented by
 * {@link Virtualizer} are expected to be transformed; the rest deliberately use
 * opcodes or types outside the subset.
 */
public final class VirtualFixture {

    private static int state = 1;

    private VirtualFixture() {
    }

    // ---- supported subset -------------------------------------------------

    public static int constant() {
        return 42;
    }

    public static int minValue() {
        return Integer.MIN_VALUE;
    }

    public static int maxValue() {
        return Integer.MAX_VALUE;
    }

    public static int add(int a, int b) {
        return a + b;
    }

    public static int three(int a, int b, int c) {
        return a + b * 3 + c * 5;
    }

    public static int four(int a, int b, int c, int d) {
        return a ^ (b << 3) ^ (c >> 1) ^ (d * 7);
    }

    public static int identity(int a) {
        return a;
    }

    public static void voidEmpty() {
    }

    public static int sub(int a, int b) {
        return a - b;
    }

    public static int mul(int a, int b) {
        return a * b;
    }

    public static int div(int a, int b) {
        return a / b;
    }

    public static int rem(int a, int b) {
        return a % b;
    }

    public static int neg(int a) {
        return -a;
    }

    public static int bitwise(int a, int b) {
        return (a & b) ^ (a | b) ^ (a & ~b);
    }

    public static int shifts(int value, int count) {
        return (value << count) ^ (value >> count) ^ (value >>> count);
    }

    public static int loopSum(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    public static int downLoop(int floor) {
        int sum = 0;
        for (int i = 10; i > floor; i -= 3) {
            sum += i;
        }
        return sum;
    }

    public static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    public static int nested(int rows, int columns) {
        int sum = 0;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                if (((i ^ j) & 1) == 0) {
                    sum += i * j;
                } else {
                    sum -= i + j;
                }
            }
        }
        return sum;
    }

    public static int mixed(int a, int b) {
        int value = a - b;
        if (value > 0) {
            value = value * 3 + 1;
        } else if (value < 0) {
            value = value / 2;
        } else {
            value = 7;
        }
        return value;
    }

    /** Leaves a value live on the operand stack across the ternary merge. */
    public static int selectPlus(int a, int b) {
        return (a > b ? a : b) + 1;
    }

    /** Exercises every IF_ICMPxx condition. */
    public static int compare(int a, int b) {
        int result = 0;
        if (a < b) {
            result += 1;
        }
        if (a <= b) {
            result += 2;
        }
        if (a == b) {
            result += 4;
        }
        if (a >= b) {
            result += 8;
        }
        if (a > b) {
            result += 16;
        }
        if (a != b) {
            result += 32;
        }
        return result;
    }

    /** Exercises the single-value IFxx conditions. */
    public static int compareZero(int value) {
        int result = 0;
        if (value < 0) {
            result += 1;
        }
        if (value <= 0) {
            result += 2;
        }
        if (value >= 0) {
            result += 4;
        }
        if (value > 0) {
            result += 8;
        }
        return result;
    }

    public static int countBits(int value) {
        int count = 0;
        while (value != 0) {
            count += value & 1;
            value >>>= 1;
        }
        return count;
    }

    public static int abs(int value) {
        return value < 0 ? -value : value;
    }

    public static int overflow(int a, int b) {
        return a * b + Integer.MAX_VALUE;
    }

    public static int branchy(int value) {
        if (value < -1000) {
            return value / 3;
        }
        if (value == -1) {
            return 11;
        }
        if (value <= 0) {
            return value - 1;
        }
        return value % 7;
    }

    public static void voidLoop(int n) {
        int value = 0;
        while (value < n) {
            value += 3;
        }
    }

    public static void voidDiv(int a, int b) {
        int ignored = a / b;
    }

    // ---- rejected shapes --------------------------------------------------

    public static int withField(int a) {
        return state + a;
    }

    public static int withCall(int a) {
        return add(a, 1);
    }

    public static int withTryCatch(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    public static int withSwitch(int a) {
        switch (a) {
            case 0:
                return 1;
            case 10:
                return 2;
            default:
                return 3;
        }
    }

    public static long withLong(long value) {
        return value + 1L;
    }

    public static int withArray(int[] values) {
        return values.length;
    }

    public static int withCast(int value) {
        return (byte) value;
    }

    public static int withNew(int value) {
        Object object = new Object();
        return object == null ? 0 : value;
    }

    public static int withDup(int a, int b) {
        a = b = a;
        return a + b;
    }

    public int instanceAdd(int value) {
        return value + 1;
    }

    public static float withFloat(float value) {
        return value + 1.0f;
    }
}
