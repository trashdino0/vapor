package dev.hardening;

import java.util.ArrayList;
import java.util.List;

public final class Shapes {

    private Shapes() {
    }

    public interface Shape {
        double area();

        default String tag() {
            return "shape:" + Math.round(area());
        }
    }

    public static final class Rect implements Shape {
        private final int w;
        private final int h;

        public Rect(int w, int h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public double area() {
            return w * h;
        }
    }

    public static final class Tri implements Shape {
        private final int b;
        private final int h;

        public Tri(int b, int h) {
            this.b = b;
            this.h = h;
        }

        @Override
        public double area() {
            return b * h / 2.0;
        }
    }

    public static Shape scaled(final int factor) {
        return new Shape() {
            @Override
            public double area() {
                return factor * factor;
            }

            @Override
            public String tag() {
                return "anon:" + factor;
            }
        };
    }

    public static String describeAll() {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Rect(3, 4));
        shapes.add(new Tri(6, 5));
        shapes.add(scaled(3));
        shapes.add(() -> 2.5);
        shapes.add(new Rect(10, 10));

        StringBuilder sb = new StringBuilder();
        for (Shape s : shapes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.tag());
        }
        double total = 0;
        for (Shape s : shapes) {
            total += s.area();
        }
        return sb + "|total=" + Math.round(total);
    }
}
