package testcases.field_case;

// Virtual call through an instance field. Without field tracking the call
// site cannot be devirtualized; with field tracking we know it's always Dog.
public class Test {
    interface Animal { String speak(); }
    static class Dog implements Animal { public String speak() { return "woof"; } }

    static class Kennel {
        Animal resident;
        void move(Animal a) { this.resident = a; }
        String call() { return resident.speak(); }
    }

    public static void main(String[] args) {
        Kennel k = new Kennel();
        k.move(new Dog());
        System.out.println(k.call());
    }
}
