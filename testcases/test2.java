package testcases;

// Virtual call through an array element. Every element is a Square, so the
// dispatch at s.area() has a singleton receiver type under array tracking.
public class test2 {
    interface Shape { int area(); }
    static class Square implements Shape {
        int side;
        Square(int s) { this.side = s; }
        public int area() { return side * side; }
    }

    public static void main(String[] args) {
        Shape[] shapes = new Shape[3];
        shapes[0] = new Square(2);
        shapes[1] = new Square(3);
        shapes[2] = new Square(4);
        int total = 0;
        for (int i = 0; i < shapes.length; i++) {
            Shape s = shapes[i];
            total += s.area();
        }
        System.out.println(total);
    }
}
