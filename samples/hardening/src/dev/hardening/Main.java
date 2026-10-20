package dev.hardening;

/**
 * Rig sample for v2 hardening transforms: arithmetic torture, recursion, switches
 * (dense + sparse), string switching, loops, try/finally, synchronized, enums,
 * records, lambdas, anonymous classes and a big straight-line method.
 *
 * <p>Deliberately name-independent: nothing printed depends on class names
 * (no getSimpleName / record toString / getClass), so baseline and obfuscated
 * stdout must match exactly.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("=== vapor sample: hardening ===");
        System.out.println(echo("hardening-marker-alpha"));
        System.out.println("math=" + Computations.workload(7));
        System.out.println("straight=" + Computations.straightLine(12345));
        System.out.println("loops=" + Loops.run(5));
        System.out.println("recursion=" + Computations.fib(20) + "," + Computations.mutual(10));
        System.out.println("switch=" + Computations.classify("hardening-marker-beta"));
        System.out.println("gcd=" + Computations.gcd(1071, 462));
        System.out.println("shapes=" + Shapes.describeAll());
        System.out.println("bonus=" + Bonus.summary());
        System.out.println(String.format("marker=%s", "hardening-marker-gamma"));
        System.out.println("=== done ===");
    }

    private static String echo(String s) {
        return s;
    }
}
