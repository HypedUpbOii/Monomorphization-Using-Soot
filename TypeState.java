import java.util.*;

import soot.Local;
import soot.SootClass;

public class TypeState {
    // Maps each local to the set of concrete classes it may refer to at this point.
    // Absence means we have no type information (unknown / not yet seen allocated).
    final Map<Local, Set<SootClass>> pointsTo = new HashMap<>();

    Set<SootClass> get(Local l) {
        return pointsTo.getOrDefault(l, Collections.emptySet());
    }

    void set(Local l, Set<SootClass> types) {
        if (types.isEmpty()) {
            pointsTo.remove(l);
        } else {
            pointsTo.put(l, new HashSet<>(types));
        }
    }

    void add(Local l, SootClass type) {
        pointsTo.computeIfAbsent(l, k -> new HashSet<>()).add(type);
    }

    void copyFrom(TypeState other) {
        pointsTo.clear();
        other.pointsTo.forEach((l, types) -> pointsTo.put(l, new HashSet<>(types)));
    }

    void unionFrom(TypeState other) {
        other.pointsTo.forEach((l, types) ->
            pointsTo.computeIfAbsent(l, k -> new HashSet<>()).addAll(types));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TypeState && pointsTo.equals(((TypeState) o).pointsTo);
    }

    @Override
    public int hashCode() {
        return pointsTo.hashCode();
    }
}
