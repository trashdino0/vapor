package demo.lab;

public final class Mandelbrot {

    private static final int WIDTH = 72;
    private static final int HEIGHT = 26;
    private static final int MAX_ITER = 80;

    private Mandelbrot() {
    }

    public static void render() {
        char[] ramp = " .:-=+*#%@".toCharArray();
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < HEIGHT; row++) {
            double y0 = (row * 2.0 / HEIGHT) - 1.0;
            for (int col = 0; col < WIDTH; col++) {
                double x0 = (col * 3.2 / WIDTH) - 2.2;
                double x = 0.0;
                double y = 0.0;
                int iter = 0;
                while (x * x + y * y <= 4.0 && iter < MAX_ITER) {
                    double next = x * x - y * y + x0;
                    y = 2.0 * x * y + y0;
                    x = next;
                    iter++;
                }
                out.append(iter >= MAX_ITER ? ramp[ramp.length - 1] : ramp[iter * ramp.length / MAX_ITER]);
            }
            out.append('\n');
        }
        System.out.print(out);
    }
}
