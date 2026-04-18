/**
 * Monomorphization test case.
 *
 * Three virtual-dispatch patterns are exercised:
 *
 *   Case 1 – Direct local
 *     Circle c = new Circle(...);  c.area()
 *     The receiver local is a fresh new-expression; trivially devirtualizable
 *     even without heap analysis (worked before our changes).
 *
 *   Case 2 – Field read  (requires HeapAbstraction)
 *     ShapeBox.stored is always assigned a Circle.
 *     HeapAbstraction records:  ShapeBox.stored → {Circle}
 *     When computeArea() reads this.stored, the local gets type {Circle}, so
 *     the virtual call is devirtualized to a static call.
 *
 *   Case 3 – Array element read  (requires HeapAbstraction)
 *     A Shape[] array is filled only with Circle objects.
 *     HeapAbstraction records:  Shape[] elements → {Circle}
 *     The array load in the loop gets type {Circle}, devirtualizing area().
 *
 *   Case 4 – Mixed array  (NOT devirtualizable – shown for contrast)
 *     The same Shape[] type now receives both Circle and Rectangle objects.
 *     HeapAbstraction records:  Shape[] elements → {Circle, Rectangle}
 *     Two receiver types → optimizer leaves the call as virtualinvoke.
 *     NOTE: because array element types are keyed only on the array's declared
 *     type (Shape[]), both Case-3 and Case-4 share the same heap entry.
 *     If both are in the same program, the Case-3 array also becomes
 *     non-devirtualizable.  To keep Case 3 devirtualizable we use a
 *     separate Circle[] (keyed differently) for that benchmark.
 *
 * Running without optimizer  (plain javac / java):
 *   All four cases use bytecode INVOKEINTERFACE at every call site.
 *
 * Running through Optimizer then java:
 *   Cases 1–3 become INVOKESTATIC on the generated Circle$mono$Circle clone.
 *   Case 4 retains INVOKEINTERFACE.
 */
public class Test {

    static final int WARMUP  = 500_000;
    static final int MEASURE = 5_000_000;

    public static void main(String[] args) {

        // ----------------------------------------------------------------
        // Case 1: direct local  (baseline)
        // ----------------------------------------------------------------
        Circle direct = new Circle(5.0);
        warmup1(direct);
        long t0 = System.nanoTime();
        double s1 = bench1(direct);
        long t1 = System.nanoTime();
        System.out.printf("Case 1 direct   : %.3f ms  (checksum=%.4f)%n",
                          (t1 - t0) / 1e6, s1);

        // ----------------------------------------------------------------
        // Case 2: field read  (ShapeBox.stored is always a Circle)
        // ----------------------------------------------------------------
        ShapeBox box = new ShapeBox("b", new Circle(3.0));
        warmup2(box);
        long t2 = System.nanoTime();
        double s2 = bench2(box);
        long t3 = System.nanoTime();
        System.out.printf("Case 2 field    : %.3f ms  (checksum=%.4f)%n",
                          (t3 - t2) / 1e6, s2);

        // ----------------------------------------------------------------
        // Case 3: Circle[] array  (element type Shape, always Circle)
        // Note: we use Circle[] (not Shape[]) so the heap key Circle[]→{Circle}
        // is separate from the Shape[] used by Case 4.
        // ----------------------------------------------------------------
        Circle[] circles = new Circle[4];
        circles[0] = new Circle(1.0);
        circles[1] = new Circle(2.0);
        circles[2] = new Circle(3.0);
        circles[3] = new Circle(4.0);
        warmup3(circles);
        long t4 = System.nanoTime();
        double s3 = bench3(circles);
        long t5 = System.nanoTime();
        System.out.printf("Case 3 array    : %.3f ms  (checksum=%.4f)%n",
                          (t5 - t4) / 1e6, s3);

        // ----------------------------------------------------------------
        // Case 4: mixed Shape[] – NOT devirtualizable
        // ----------------------------------------------------------------
        Shape[] mixed = new Shape[4];
        mixed[0] = new Circle(1.0);
        mixed[1] = new Rectangle(2.0, 3.0);
        mixed[2] = new Circle(2.0);
        mixed[3] = new Rectangle(1.0, 5.0);
        warmup4(mixed);
        long t6 = System.nanoTime();
        double s4 = bench4(mixed);
        long t7 = System.nanoTime();
        System.out.printf("Case 4 mixed    : %.3f ms  (checksum=%.4f)%n",
                          (t7 - t6) / 1e6, s4);
    }

    // ---- warmup helpers (prevent dead-code elimination) ----------------

    static void warmup1(Circle c) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += c.area();
        if (s < 0) throw new RuntimeException("sink");
    }

    static void warmup2(ShapeBox b) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += b.computeArea();
        if (s < 0) throw new RuntimeException("sink");
    }

    static void warmup3(Circle[] arr) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += arr[i % arr.length].area();
        if (s < 0) throw new RuntimeException("sink");
    }

    static void warmup4(Shape[] arr) {
        double s = 0;
        for (int i = 0; i < WARMUP; i++) s += arr[i % arr.length].area();
        if (s < 0) throw new RuntimeException("sink");
    }

    // ---- measurement helpers -------------------------------------------

    static double bench1(Circle c) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += c.area();
        return s;
    }

    static double bench2(ShapeBox b) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += b.computeArea();
        return s;
    }

    static double bench3(Circle[] arr) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += arr[i % arr.length].area();
        return s;
    }

    static double bench4(Shape[] arr) {
        double s = 0;
        for (int i = 0; i < MEASURE; i++) s += arr[i % arr.length].area();
        return s;
    }
}
