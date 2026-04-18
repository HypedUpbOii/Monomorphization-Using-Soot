import java.util.*;

import soot.FastHierarchy;
import soot.Local;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.CastExpr;
import soot.jimple.FieldRef;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.NewExpr;
import soot.jimple.ParameterRef;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.ThisRef;
import soot.jimple.VirtualInvokeExpr;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

public class TypeFlowAnalysis extends ForwardFlowAnalysis<Unit, TypeState> {

    // Context: parameter index (-1 = this) → concrete types provided by caller
    private final Map<Integer, Set<SootClass>> paramCtx;
    // Interprocedural summaries: method → (paramCtx → return types)
    private final Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries;
    // Field-based global: field → union of concrete types ever written to it
    private final Map<SootField, Set<SootClass>> fieldTypes;
    // Array-element-type-based global: element Type → union of concrete types ever stored
    private final Map<Type, Set<SootClass>> arrayElemTypes;

    // Maps parameter locals back to their index, built from IdentityStmts
    private final Map<Local, Integer> localToParam = new HashMap<>();

    // Outputs populated after doAnalysis() + finalizeResults()
    final Map<Stmt, Set<SootClass>> receiverTypesAtSite = new HashMap<>();
    final Map<Stmt, Map<Integer, Set<SootClass>>> callSiteArgCtx = new HashMap<>();
    final Set<SootClass> returnTypes = new HashSet<>();

    public TypeFlowAnalysis(
        DirectedGraph<Unit> graph,
        Map<Integer, Set<SootClass>> paramCtx,
        Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries,
        Map<SootField, Set<SootClass>> fieldTypes,
        Map<Type, Set<SootClass>> arrayElemTypes
    ) {
        super(graph);
        this.paramCtx = paramCtx;
        this.summaries = summaries;
        this.fieldTypes = fieldTypes;
        this.arrayElemTypes = arrayElemTypes;
        initializeParams();
        doAnalysis();
        finalizeResults();
    }

    private void initializeParams() {
        for (Unit unit : graph) {
            if (!(unit instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) unit;
            if (!(id.getLeftOp() instanceof Local)) continue;
            Local l = (Local) id.getLeftOp();
            if (id.getRightOp() instanceof ThisRef) {
                localToParam.put(l, -1);
            } else if (id.getRightOp() instanceof ParameterRef) {
                localToParam.put(l, ((ParameterRef) id.getRightOp()).getIndex());
            }
        }
    }

    private void finalizeResults() {
        // Collect return types from all exit nodes
        for (Unit tail : graph.getTails()) {
            if (!(tail instanceof ReturnStmt)) continue;
            Value op = ((ReturnStmt) tail).getOp();
            if (op instanceof Local) {
                returnTypes.addAll(getFlowBefore(tail).get((Local) op));
            }
        }

        // Collect receiver types and arg contexts at every call site
        for (Unit unit : graph) {
            if (!(unit instanceof Stmt)) continue;
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;

            TypeState before = getFlowBefore(unit);
            InvokeExpr ie = stmt.getInvokeExpr();

            // Build arg context: index → concrete types of that argument
            Map<Integer, Set<SootClass>> argCtx = new HashMap<>();
            if (ie instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (base instanceof Local) {
                    Set<SootClass> baseTypes = before.get((Local) base);
                    if (!baseTypes.isEmpty()) argCtx.put(-1, new HashSet<>(baseTypes));
                }
            }
            for (int i = 0; i < ie.getArgCount(); i++) {
                Value arg = ie.getArg(i);
                if (arg instanceof Local) {
                    Set<SootClass> argTypes = before.get((Local) arg);
                    if (!argTypes.isEmpty()) argCtx.put(i, new HashSet<>(argTypes));
                }
            }
            if (!argCtx.isEmpty()) callSiteArgCtx.put(stmt, argCtx);

            // For dispatched calls (virtual or interface) record receiver concrete types
            if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (base instanceof Local) {
                    Set<SootClass> types = before.get((Local) base);
                    if (!types.isEmpty()) receiverTypesAtSite.put(stmt, new HashSet<>(types));
                }
            }
        }
    }

    @Override
    protected void flowThrough(TypeState in, Unit unit, TypeState out) {
        out.copyFrom(in);

        if (unit instanceof IdentityStmt) {
            handleIdentity((IdentityStmt) unit, out);
            return;
        }

        if (unit instanceof AssignStmt) {
            handleAssignment((AssignStmt) unit, out);
        }
    }

    private void handleIdentity(IdentityStmt stmt, TypeState out) {
        if (!(stmt.getLeftOp() instanceof Local)) return;
        Local lhs = (Local) stmt.getLeftOp();
        Integer paramIdx = localToParam.get(lhs);
        if (paramIdx == null) return;
        Set<SootClass> types = paramCtx.get(paramIdx);
        if (types != null && !types.isEmpty()) out.set(lhs, types);
    }

    private void handleAssignment(AssignStmt stmt, TypeState out) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // Writes to fields or arrays: contribute rhs types to the global map
        if (lhs instanceof FieldRef) {
            recordFieldWrite((FieldRef) lhs, rhs, out);
            return;
        }
        if (lhs instanceof ArrayRef) {
            recordArrayWrite((ArrayRef) lhs, rhs, out);
            return;
        }

        if (!(lhs instanceof Local)) return;
        Local lhsLocal = (Local) lhs;

        if (rhs instanceof NewExpr) {
            SootClass cls = ((NewExpr) rhs).getBaseType().getSootClass();
            out.set(lhsLocal, Collections.singleton(cls));
            return;
        }

        if (rhs instanceof CastExpr) {
            handleCast((CastExpr) rhs, lhsLocal, out);
            return;
        }

        if (rhs instanceof Local) {
            Set<SootClass> types = out.get((Local) rhs);
            out.set(lhsLocal, types);
            return;
        }

        if (rhs instanceof InvokeExpr) {
            handleInvokeResult((InvokeExpr) rhs, lhsLocal, out);
            return;
        }

        // Reads from fields or arrays: fill lhs with the current global type set
        if (rhs instanceof FieldRef) {
            SootField f = ((FieldRef) rhs).getField();
            Set<SootClass> t = fieldTypes.get(f);
            out.set(lhsLocal, t == null ? Collections.emptySet() : t);
            return;
        }
        if (rhs instanceof ArrayRef) {
            Type et = ((ArrayRef) rhs).getType();
            Set<SootClass> t = arrayElemTypes.get(et);
            out.set(lhsLocal, t == null ? Collections.emptySet() : t);
            return;
        }

        // Any other rhs (constants, etc.) — clear to avoid stale type info
        out.set(lhsLocal, Collections.emptySet());
    }

    private void recordFieldWrite(FieldRef fieldRef, Value rhs, TypeState state) {
        if (!(rhs instanceof Local)) return;
        Set<SootClass> types = state.get((Local) rhs);
        if (types.isEmpty()) return;
        SootField f = fieldRef.getField();
        fieldTypes.computeIfAbsent(f, k -> new HashSet<>()).addAll(types);
    }

    private void recordArrayWrite(ArrayRef arrayRef, Value rhs, TypeState state) {
        if (!(rhs instanceof Local)) return;
        Set<SootClass> types = state.get((Local) rhs);
        if (types.isEmpty()) return;
        Type et = arrayRef.getType();
        arrayElemTypes.computeIfAbsent(et, k -> new HashSet<>()).addAll(types);
    }

    private void handleCast(CastExpr cast, Local lhs, TypeState out) {
        if (!(cast.getOp() instanceof Local)) return;
        if (!(cast.getCastType() instanceof RefType)) return;
        SootClass castClass = ((RefType) cast.getCastType()).getSootClass();
        FastHierarchy fh = Scene.v().getOrMakeFastHierarchy();
        Set<SootClass> narrowed = new HashSet<>();
        for (SootClass c : out.get((Local) cast.getOp())) {
            if (fh.canStoreClass(c, castClass)) narrowed.add(c);
        }
        if (!narrowed.isEmpty()) out.set(lhs, narrowed);
    }

    private void handleInvokeResult(InvokeExpr ie, Local lhs, TypeState out) {
        SootMethod callee = ie.getMethod();
        Map<Map<Integer, Set<SootClass>>, Set<SootClass>> calleeSummary = summaries.get(callee);
        if (calleeSummary == null || calleeSummary.isEmpty()) return;

        Map<Integer, Set<SootClass>> argCtx = buildArgCtx(ie, out);
        Set<SootClass> returnedTypes = calleeSummary.get(argCtx);
        if (returnedTypes != null && !returnedTypes.isEmpty()) out.set(lhs, returnedTypes);
    }

    private Map<Integer, Set<SootClass>> buildArgCtx(InvokeExpr ie, TypeState state) {
        Map<Integer, Set<SootClass>> ctx = new HashMap<>();
        if (ie instanceof InstanceInvokeExpr) {
            Value base = ((InstanceInvokeExpr) ie).getBase();
            if (base instanceof Local) {
                Set<SootClass> t = state.get((Local) base);
                if (!t.isEmpty()) ctx.put(-1, new HashSet<>(t));
            }
        }
        for (int i = 0; i < ie.getArgCount(); i++) {
            Value arg = ie.getArg(i);
            if (arg instanceof Local) {
                Set<SootClass> t = state.get((Local) arg);
                if (!t.isEmpty()) ctx.put(i, new HashSet<>(t));
            }
        }
        return ctx;
    }

    @Override
    protected void copy(TypeState src, TypeState dest) {
        dest.copyFrom(src);
    }

    @Override
    protected void merge(TypeState in1, TypeState in2, TypeState out) {
        out.copyFrom(in1);
        out.unionFrom(in2);
    }

    @Override
    protected TypeState entryInitialFlow() {
        return new TypeState();
    }

    @Override
    protected TypeState newInitialFlow() {
        return new TypeState();
    }
}
