package testcases;

// Loop dispatching through a field. Monomorphization should eliminate
// the interface-invoke virtual dispatch.
public class test8 {
    interface Op { int apply(int x); }
    static class Inc implements Op { public int apply(int x) { return x + 1; } }

    static class Holder {
        Op op;
        void set(Op o) { this.op = o; }
        int run(int x) { return op.apply(x); }
    }

    public static void main(String[] args) {
        Holder h = new Holder();
        h.set(new Inc());
        int x = 0;
        int n = 400_000_000;
        for (int i = 0; i < n; i++) {
            x = h.run(x);
        }
        System.out.println(x);
    }
}
