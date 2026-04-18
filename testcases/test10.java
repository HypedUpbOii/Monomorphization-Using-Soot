package testcases;

// Field holding an array of Op; two dispatch levels (field + array) in a loop.
public class test10 {
    interface Op { int apply(int x); }
    static class Inc implements Op { public int apply(int x) { return x + 1; } }

    static class Pipeline {
        Op[] ops;
        Pipeline(int sz) { ops = new Op[sz]; for (int i = 0; i < sz; i++) ops[i] = new Inc(); }
        int run(int x) {
            for (int i = 0; i < ops.length; i++) x = ops[i].apply(x);
            return x;
        }
    }

    public static void main(String[] args) {
        Pipeline p = new Pipeline(1000);
        int x = 0;
        int n = 400_000;
        for (int j = 0; j < n; j++) x = p.run(x);
        System.out.println(x);
    }
}
