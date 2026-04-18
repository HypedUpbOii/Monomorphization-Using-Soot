package testcases;

// Factory returns an Op; the returned object is parked in a field and then
// dispatched through. Mixes return-type summarization with field flow.
public class test7 {
    interface Op { int apply(int x); }
    static class Neg implements Op { public int apply(int x) { return -x; } }

    static Op make() { return new Neg(); }

    static class Holder {
        Op op;
        void install() { op = make(); }
        int fire(int x) { return op.apply(x); }
    }

    public static void main(String[] args) {
        Holder h = new Holder();
        h.install();
        System.out.println(h.fire(7));
    }
}
