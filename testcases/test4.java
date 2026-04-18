package testcases;

// A static field written with a single concrete type, read from a different
// method.
public class test4 {
    interface Greeter { String hi(); }
    static class En implements Greeter { public String hi() { return "hello"; } }

    static Greeter G;

    static void install() { G = new En(); }
    static String useIt() { return G.hi(); }

    public static void main(String[] args) {
        install();
        System.out.println(useIt());
    }
}
