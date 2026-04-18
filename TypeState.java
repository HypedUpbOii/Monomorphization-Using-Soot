import java.util.*;

import soot.*;

public class TypeState {
    Map<Local, Set<SootClass>> pts;

    TypeState() {
        this.pts = new HashMap<>();
    }

    Set<SootClass> get(Local l) {
        if (!pts.containsKey(l)) return Collections.emptySet();
        return pts.get(l);
    }

    void set(Local l, Set<SootClass> types) {
        if (types == null || types.isEmpty()) {
            pts.remove(l);
            return;
        }
        pts.put(l, new HashSet<>(types));
    }

    void copy(TypeState dst) {
        dst.pts.clear();
        for (var e : pts.entrySet()) {
            dst.pts.put(e.getKey(), new HashSet<>(e.getValue()));
        }
    }

    void union(TypeState other, TypeState dst) {
        dst.pts.clear();
        Set<Local> all = new HashSet<>();
        all.addAll(this.pts.keySet());
        all.addAll(other.pts.keySet());
        for (Local l : all) {
            Set<SootClass> u = new HashSet<>();
            if (this.pts.containsKey(l))  u.addAll(this.pts.get(l));
            if (other.pts.containsKey(l)) u.addAll(other.pts.get(l));
            if (!u.isEmpty()) dst.pts.put(l, u);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TypeState)) return false;
        return ((TypeState) o).pts.equals(this.pts);
    }

    @Override
    public int hashCode() {
        return pts.hashCode();
    }
};
