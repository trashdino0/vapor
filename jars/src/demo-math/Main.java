package demo.lab;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("== mandelbrot ==");
        Mandelbrot.render();

        System.out.println();
        System.out.println("== collatz ==");
        System.out.println("longest start under 10000: " + Collatz.longest(10000));
        System.out.println("steps(27): " + Collatz.stepsFor(27));

        System.out.println();
        System.out.println("== fnv-1a ==");
        for (String word : new String[] {"vapor", "obfuscation", "deepseek"}) {
            System.out.println(word + " -> " + Hashing.hex(Hashing.fnv1a(word)));
        }

        System.out.println();
        System.out.println("== primes ==");
        System.out.println("primes below 5000: " + Primes.countBelow(5000));
    }
}
