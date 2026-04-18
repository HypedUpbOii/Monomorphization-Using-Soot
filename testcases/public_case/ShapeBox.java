/**
 * Holds a single Shape in a field.
 *
 * In this test, only Circle objects are ever stored here.  Our HeapAbstraction
 * will record  ShapeBox.stored → {Circle},  so the virtual call in computeArea()
 * can be devirtualized even though the declared type of the field is Shape.
 */
class ShapeBox {
    Shape stored;          // declared type is interface Shape
    String label;

    ShapeBox(String label, Shape s) {
        this.label = label;
        this.stored = s;   // store: heap learns stored → {whatever type s is}
    }

    double computeArea() {
        return stored.area();   // field read + virtual call
    }

    String computeDescribe() {
        return stored.describe();  // second field-read virtual call on same field
    }
}
