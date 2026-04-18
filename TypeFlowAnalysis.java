import java.util.*;

import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JAssignStmt;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

public class TypeFlowAnalysis extends ForwardFlowAnalysis<Unit, TypeState> {

    // caller-provided parameter types: index -> types (-1 is the receiver)
    Map<Integer, Set<SootClass>> paramCtx;
    // method -> (ctx -> set of classes returned by that method under that ctx)
    Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries;
    // field -> union of types stored into it anywhere in the program so far
    Map<SootField, Set<SootClass>> fieldTypes;
    // array element type -> union of types stored into arrays of that element type
    Map<Type, Set<SootClass>> arrayElemTypes;

    Map<Local, Integer> paramOf = new HashMap<>();

    // results collected by finalizeResults()
    Map<Stmt, Set<SootClass>> receiverTypesAtSite = new HashMap<>();
    Map<Stmt, Map<Integer, Set<SootClass>>> callSiteArgCtx = new HashMap<>();
    Set<SootClass> returnTypes = new HashSet<>();

    public TypeFlowAnalysis(DirectedGraph<Unit> g,
                            Map<Integer, Set<SootClass>> paramCtx,
                            Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries,
                            Map<SootField, Set<SootClass>> fieldTypes,
                            Map<Type, Set<SootClass>> arrayElemTypes) {
        super(g);
        this.paramCtx = paramCtx;
        this.summaries = summaries;
        this.fieldTypes = fieldTypes;
        this.arrayElemTypes = arrayElemTypes;

        // walk the identity stmts first so we know which local is which param
        for (Unit u : g) {
            if (!(u instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) u;
            if (!(id.getLeftOp() instanceof Local)) continue;
            Local l = (Local) id.getLeftOp();
            Value r = id.getRightOp();
            if (r instanceof ThisRef) paramOf.put(l, -1);
            else if (r instanceof ParameterRef) paramOf.put(l, ((ParameterRef) r).getIndex());
        }

        doAnalysis();
        finalizeResults();
    }

    private void finalizeResults() {
        for (Unit tail : graph.getTails()) {
            if (!(tail instanceof ReturnStmt)) continue;
            Value op = ((ReturnStmt) tail).getOp();
            if (op instanceof Local) {
                returnTypes.addAll(getFlowBefore(tail).get((Local) op));
            }
        }

        for (Unit u : graph) {
            if (!(u instanceof Stmt)) continue;
            Stmt s = (Stmt) u;
            if (!s.containsInvokeExpr()) continue;

            TypeState before = getFlowBefore(u);
            InvokeExpr ie = s.getInvokeExpr();

            // build the arg context for this call site
            Map<Integer, Set<SootClass>> ctx = new HashMap<>();
            if (ie instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (base instanceof Local) {
                    Set<SootClass> t = before.get((Local) base);
                    if (!t.isEmpty()) ctx.put(-1, new HashSet<>(t));
                }
            }
            for (int i = 0; i < ie.getArgCount(); i++) {
                Value a = ie.getArg(i);
                if (!(a instanceof Local)) continue;
                Set<SootClass> t = before.get((Local) a);
                if (!t.isEmpty()) ctx.put(i, new HashSet<>(t));
            }
            if (!ctx.isEmpty()) callSiteArgCtx.put(s, ctx);

            // dispatched calls: remember what the receiver can be
            if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (base instanceof Local) {
                    Set<SootClass> t = before.get((Local) base);
                    if (!t.isEmpty()) receiverTypesAtSite.put(s, new HashSet<>(t));
                }
            }
        }
    }

    @Override
    protected void flowThrough(TypeState in, Unit u, TypeState out) {
        in.copy(out);

        // parameter/this seeds -- only fills lhs when caller gave us a type
        if (u instanceof IdentityStmt) {
            IdentityStmt id = (IdentityStmt) u;
            if (!(id.getLeftOp() instanceof Local)) return;
            Local l = (Local) id.getLeftOp();
            Integer idx = paramOf.get(l);
            if (idx == null) return;
            Set<SootClass> t = paramCtx.get(idx);
            if (t != null && !t.isEmpty()) out.set(l, t);
            return;
        }

        if (!(u instanceof JAssignStmt)) return;
        JAssignStmt stmt = (JAssignStmt) u;
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // stores into fields / arrays: accumulate into the global table
        if (lhs instanceof FieldRef) {
            if (!(rhs instanceof Local)) return;
            Set<SootClass> t = out.get((Local) rhs);
            if (t.isEmpty()) return;
            SootField f = ((FieldRef) lhs).getField();
            fieldTypes.computeIfAbsent(f, k -> new HashSet<>()).addAll(t);
            return;
        }
        if (lhs instanceof ArrayRef) {
            if (!(rhs instanceof Local)) return;
            Set<SootClass> t = out.get((Local) rhs);
            if (t.isEmpty()) return;
            Type et = ((ArrayRef) lhs).getType();
            arrayElemTypes.computeIfAbsent(et, k -> new HashSet<>()).addAll(t);
            return;
        }

        if (!(lhs instanceof Local)) return;
        Local left = (Local) lhs;

        if (rhs instanceof NewExpr) {
            SootClass c = ((NewExpr) rhs).getBaseType().getSootClass();
            out.set(left, Collections.singleton(c));
            return;
        }

        if (rhs instanceof CastExpr) {
            CastExpr ce = (CastExpr) rhs;
            if (!(ce.getOp() instanceof Local)) return;
            if (!(ce.getCastType() instanceof RefType)) return;
            SootClass target = ((RefType) ce.getCastType()).getSootClass();
            FastHierarchy fh = Scene.v().getOrMakeFastHierarchy();
            Set<SootClass> keep = new HashSet<>();
            for (SootClass c : out.get((Local) ce.getOp())) {
                if (fh.canStoreClass(c, target)) keep.add(c);
            }
            if (!keep.isEmpty()) out.set(left, keep);
            return;
        }

        if (rhs instanceof Local) {
            out.set(left, out.get((Local) rhs));
            return;
        }

        if (rhs instanceof InvokeExpr) {
            InvokeExpr ie = (InvokeExpr) rhs;
            var calleeSummary = summaries.get(ie.getMethod());
            if (calleeSummary == null || calleeSummary.isEmpty()) return;

            // replicate the context build so we can look the summary up
            Map<Integer, Set<SootClass>> ctx = new HashMap<>();
            if (ie instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (base instanceof Local) {
                    Set<SootClass> t = out.get((Local) base);
                    if (!t.isEmpty()) ctx.put(-1, new HashSet<>(t));
                }
            }
            for (int i = 0; i < ie.getArgCount(); i++) {
                Value a = ie.getArg(i);
                if (!(a instanceof Local)) continue;
                Set<SootClass> t = out.get((Local) a);
                if (!t.isEmpty()) ctx.put(i, new HashSet<>(t));
            }
            Set<SootClass> ret = calleeSummary.get(ctx);
            if (ret != null && !ret.isEmpty()) out.set(left, ret);
            return;
        }

        if (rhs instanceof FieldRef) {
            SootField f = ((FieldRef) rhs).getField();
            Set<SootClass> t = fieldTypes.get(f);
            out.set(left, t == null ? Collections.emptySet() : t);
            return;
        }
        if (rhs instanceof ArrayRef) {
            Type et = ((ArrayRef) rhs).getType();
            Set<SootClass> t = arrayElemTypes.get(et);
            out.set(left, t == null ? Collections.emptySet() : t);
            return;
        }

        // constants and anything else we don't model -- forget whatever we had
        out.set(left, Collections.emptySet());
    }

    @Override
    protected void copy(TypeState src, TypeState dst) {
        src.copy(dst);
    }

    @Override
    protected void merge(TypeState in1, TypeState in2, TypeState out) {
        in1.union(in2, out);
    }

    @Override
    protected TypeState entryInitialFlow() {
        return new TypeState();
    }

    @Override
    protected TypeState newInitialFlow() {
        return new TypeState();
    }
};
