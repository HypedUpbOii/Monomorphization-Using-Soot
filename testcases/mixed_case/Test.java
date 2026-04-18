package testcases.mixed_case;

// A field holds an array; virtual calls happen on array elements. Requires
// tracking types through both the field read and the array read to
// devirtualize the .tag() call.
public class Test {
    interface Tagged { String tag(); }
    static class Red implements Tagged { public String tag() { return "R"; } }

    static class Bag {
        Tagged[] items;
        Bag(int n) { items = new Tagged[n]; }
        void put(int i, Tagged t) { items[i] = t; }
        String tagAt(int i) { return items[i].tag(); }
    }

    public static void main(String[] args) {
        Bag b = new Bag(2);
        b.put(0, new Red());
        b.put(1, new Red());
        System.out.println(b.tagAt(0) + b.tagAt(1));
    }
}
