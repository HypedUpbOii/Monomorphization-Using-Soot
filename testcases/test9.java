package testcases;

// loop dispatching through array elements.
public class test9 {
    interface Op { int apply(int x); }
    static class Inc implements Op { public int apply(int x) { return x + 1; } }

    public static void main(String[] args) {
        int sz = 1000;
        Op[] ops = new Op[sz];
        for (int i = 0; i < sz; i++) ops[i] = new Inc();
        int x = 0;
        int n = 400_000;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < sz; i++) {
                x = ops[i].apply(x);
            }
        }
        System.out.println(x);
    }
}
