import soot.ArrayType;
import soot.SootClass;
import soot.SootField;

import java.util.*;

/**
 * Whole-program heap abstraction: tracks which concrete types can flow into
 * each field slot and each array-element slot.
 *
 * All objects on the heap have a fixed type determined by their allocation
 * site (new-expression). This class accumulates, across the entire program,
 * the set of allocation-site types that are ever stored into a given field or
 * array. TypeFlowAnalysis queries this map when it encounters a field read or
 * array load so that the receiving local gets a non-empty type set.
 *
 * The map is monotone (types are only added, never removed), so repeated
 * analysis passes converge to a fixed point.
 */
public class HeapAbstraction {

    private final Map<SootField, Set<SootClass>> fieldTypes = new HashMap<>();
    private final Map<ArrayType, Set<SootClass>> arrayElementTypes = new HashMap<>();

    // Set to true whenever either map grows; cleared at the start of each pass.
    private boolean changed = false;

    // -----------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------

    Set<SootClass> getFieldTypes(SootField f) {
        return fieldTypes.getOrDefault(f, Collections.emptySet());
    }

    Set<SootClass> getArrayElementTypes(ArrayType t) {
        return arrayElementTypes.getOrDefault(t, Collections.emptySet());
    }

    // -----------------------------------------------------------------
    // Updates (called from TypeFlowAnalysis when it processes stores)
    // -----------------------------------------------------------------

    boolean addFieldTypes(SootField f, Set<SootClass> types) {
        if (types.isEmpty()) return false;
        boolean added = fieldTypes.computeIfAbsent(f, k -> new HashSet<>()).addAll(types);
        if (added) changed = true;
        return added;
    }

    boolean addArrayElementTypes(ArrayType t, Set<SootClass> types) {
        if (types.isEmpty()) return false;
        boolean added = arrayElementTypes.computeIfAbsent(t, k -> new HashSet<>()).addAll(types);
        if (added) changed = true;
        return added;
    }

    // -----------------------------------------------------------------
    // Fixed-point control
    // -----------------------------------------------------------------

    boolean isChanged() { return changed; }

    void clearChanged() { changed = false; }
}
