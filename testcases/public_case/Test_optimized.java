/**
 * Manually-optimized version of Test.java.
 *
 * Mirrors what MonomorphizationTransformer would emit:
 *
 *  1. Circle.area$mono$Circle(Circle) and Circle.describe$mono$Circle(Circle)
 *     are added to the Circle class (see Circle.java).  The bodies are copied
 *     from the originals; @this becomes @parameter0.
 *
 *  2. Every virtualinvoke on a single-type receiver is replaced by a
 *     staticinvoke on the matching clone:
 *
 *     Case 1 – direct Circle local
 *       virtualinvoke c.<Shape: double area()>()
 *       → staticinvoke <Circle: double area$mono$Circle(Circle)>(c)
 *
 *     Case 2 – field read (ShapeBox.stored always holds a Circle)
 *       $r0 = box.<ShapeBox: Shape stored>
 *       virtualinvoke $r0.<Shape: double area()>()
 *       → staticinvoke <Circle: double area$mono$Circle(Circle)>($r0)
 *
 *     Case 3 – Circle[] array element
 *       $r1 = arr[i % len]
 *       virtualinvoke $r1.<Shape: double area()>()
 *       → staticinvoke <Circle: double area$mono$Circle(Circle)>($r1)
 *
 *  3. Case 4 (mixed Shape[] with Circle AND Rectangle) is NOT devirtualized:
 *     HeapAbstraction records Shape[] → {Circle, Rectangle} (two types),
 *     so the call stays as virtualinvoke / invokeinterface.
 */
public class Test_optimized {

    static final int WARMUP  = 500_000;
    static final int MEASURE = 5_000_000;

    public static void main(String[] args) {

        Circle direct = new Circle(5.0);
        ShapeBox box  = new ShapeBox("b", new Circle(3.0));

        Circle[] circles = new Circle[4];
        circles[0] = new Circle(1.0);
        circles[1] = new Circle(2.0);
        circles[2] = new Circle(3.0);
        circles[3] = new Circle(4.0);

        Shape[] mixed = new Shape[4];
        mixed[0] = new Circle(1.0);
        mixed[1] = new Rectangle(2.0, 3.0);
        mixed[2] = new Circle(2.0);
        mixed[3] = new Rectangle(1.0, 5.0);

        warmup1(direct);
        long t0 = System.nanoTime();
        double s1 = bench1(direct);
        long t1 = System.nanoTime();
        System.out.printf("Case 1 direct   : %.3f ms  (checksum=%.4f)%n",
                          (t1 - t0) / 1e6, s1);

        warmup2(box);
        long t2 = System.nanoTime();
        double s2 = bench2(box);
        long t3 = System.nanoTime();
        System.out.printf("Case 2 field    : %.3f ms  (checksum=%.4f)%n",
                          (t3 - t2) / 1e6, s2);

        warmup3(circles);
        long t4 = System.nanoTime();
        double s3 = bench3(circles);
        long t5 = System.nanoTime();
        System.out.printf("Case 3 array    : %.3f ms  (checksum=%.4f)%n",
                          (t5 - t4) / 1e6, s3);

        warmup4(mixed);
        long t6 = System.nanoTime();
        double s4 = bench4(mixed);
        long t7 = System.nanoTime();
        System.out.printf("Case 4 mixed    : %.3f ms  (checksum=%.4f)%n",
                          (t7 - t6) / 1e6, s4);
    }

    // Case 1: direct Circle local → static call on clone
    static void warmup1(Circle c) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += Circle.area$mono$Circle(c);
        if (s < 0) throw new RuntimeException("sink");
    }

    static double bench1(Circle c) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += Circle.area$mono$Circle(c);
        return s;
    }

    // Case 2: field read (ShapeBox.stored is always Circle) → static clone
    static void warmup2(ShapeBox b) {
        double s = 0;
        // $r0 = b.stored; staticinvoke Circle.area$mono$Circle($r0)
        for (int i = 0; i < WARMUP; i++) s += Circle.area$mono$Circle((Circle) b.stored);
        if (s < 0) throw new RuntimeException("sink");
    }

    static double bench2(ShapeBox b) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += Circle.area$mono$Circle((Circle) b.stored);
        return s;
    }

    // Case 3: Circle[] array element → static clone
    static void warmup3(Circle[] arr) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += Circle.area$mono$Circle(arr[i % arr.length]);
        if (s < 0) throw new RuntimeException("sink");
    }

    static double bench3(Circle[] arr) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += Circle.area$mono$Circle(arr[i % arr.length]);
        return s;
    }

    // Case 4: mixed Shape[] – NOT devirtualized, stays virtual
    static void warmup4(Shape[] arr) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += arr[i % arr.length].area();
        if (s < 0) throw new RuntimeException("sink");
    }

    static double bench4(Shape[] arr) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += arr[i % arr.length].area();
        return s;
    }
}
