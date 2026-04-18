package testcases;

// Linked list of Step nodes; each node holds an Op in a field. Walking the
// chain dispatches on the Op at each node, so the op field read needs to
// flow through the node field.
public class test5 {
    interface Op { int apply(int x); }
    static class Plus1 implements Op { public int apply(int x) { return x + 1; } }

    static class Step {
        Step next;
        Op op;
        Step(Op op, Step next) { this.op = op; this.next = next; }
    }

    static int run(Step head, int x) {
        Step cur = head;
        while (cur != null) {
            x = cur.op.apply(x);
            cur = cur.next;
        }
        return x;
    }

    public static void main(String[] args) {
        Step a = new Step(new Plus1(), null);
        Step b = new Step(new Plus1(), a);
        Step c = new Step(new Plus1(), b);
        System.out.println(run(c, 10));
    }
}
