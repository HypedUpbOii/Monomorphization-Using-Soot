package testcases;

// Two levels of field indirection before the virtual call: Box.inner.op.apply().
public class test6 {
    interface Op { int apply(int x); }
    static class Double implements Op { public int apply(int x) { return x * 2; } }

    static class Inner {
        Op op;
        Inner(Op op) { this.op = op; }
    }
    static class Box {
        Inner inner;
        Box(Inner inner) { this.inner = inner; }
    }

    public static void main(String[] args) {
        Box b = new Box(new Inner(new Double()));
        int y = b.inner.op.apply(21);
        System.out.println(y);
    }
}
