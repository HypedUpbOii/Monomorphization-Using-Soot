import java.util.*;

import soot.Body;
import soot.Local;
import soot.Modifier;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.ParameterRef;
import soot.jimple.Stmt;
import soot.jimple.ThisRef;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.BriefUnitGraph;

public class MonomorphizationTransformer extends SceneTransformer {

    // Interprocedural summaries: method → (paramCtx → return types)
    private final Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries = new HashMap<>();

    // Accumulated receiver types per call site across all contexts:
    // method → stmt → union of receiver types seen across all contexts
    private final Map<SootMethod, Map<Stmt, Set<SootClass>>> receiverTypesPerSite = new HashMap<>();

    // Cache of created static clones: concreteMethod → concreteType → static clone
    private final Map<SootMethod, Map<SootClass, SootMethod>> monoClones = new HashMap<>();

    // Field-based global: field → union of concrete types ever written to it
    private final Map<SootField, Set<SootClass>> fieldTypes = new HashMap<>();
    // Array-element-type-based global: element Type → union of concrete types stored
    private final Map<Type, Set<SootClass>> arrayElemTypes = new HashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph callGraph = Scene.v().getCallGraph();
        List<SootMethod> reachable = collectReachableMethods(callGraph);

        runContextSensitiveAnalysis(reachable);

        int rewritten = rewriteCallSites(reachable);
        System.out.println("Monomorphized call sites: " + rewritten);
    }

    // -------------------------------------------------------------------------
    // Interprocedural context-sensitive analysis
    // -------------------------------------------------------------------------

    private void runContextSensitiveAnalysis(List<SootMethod> reachable) {
        // Outer fixed-point: when field/array global maps grow, re-run the worklist
        // so that readers of those fields/arrays pick up the newly-known types.
        int prevFieldSize = -1;
        int prevArraySize = -1;
        int iter = 0;
        int maxIter = 10;
        while (iter++ < maxIter) {
            runWorklistOnce(reachable);
            int fieldSize = totalSize(fieldTypes);
            int arraySize = totalSize(arrayElemTypes);
            if (fieldSize == prevFieldSize && arraySize == prevArraySize) break;
            prevFieldSize = fieldSize;
            prevArraySize = arraySize;
        }
    }

    private void runWorklistOnce(List<SootMethod> reachable) {
        Deque<AnalysisTask> worklist = new ArrayDeque<>();
        // Track which (method, context) pairs have already been processed this pass
        Map<SootMethod, Set<Map<Integer, Set<SootClass>>>> processed = new HashMap<>();

        // Seed: all reachable application methods with an empty (unknown) context.
        for (SootMethod m : reachable) {
            worklist.add(new AnalysisTask(m, new HashMap<>()));
        }

        while (!worklist.isEmpty()) {
            AnalysisTask task = worklist.removeFirst();
            SootMethod method = task.method;
            Map<Integer, Set<SootClass>> paramCtx = task.paramCtx;

            if (!isAnalyzable(method)) continue;

            Set<Map<Integer, Set<SootClass>>> methodCtxs =
                processed.computeIfAbsent(method, k -> new HashSet<>());
            if (!methodCtxs.add(deepCopyContext(paramCtx))) continue;

            Body body;
            try {
                body = method.retrieveActiveBody();
            } catch (RuntimeException e) {
                continue;
            }

            TypeFlowAnalysis tfa = new TypeFlowAnalysis(
                new BriefUnitGraph(body), paramCtx, summaries, fieldTypes, arrayElemTypes
            );

            summaries.computeIfAbsent(method, k -> new HashMap<>())
                     .put(deepCopyContext(paramCtx), new HashSet<>(tfa.returnTypes));

            Map<Stmt, Set<SootClass>> sitesForMethod =
                receiverTypesPerSite.computeIfAbsent(method, k -> new HashMap<>());
            tfa.receiverTypesAtSite.forEach((stmt, types) ->
                sitesForMethod.computeIfAbsent(stmt, k -> new HashSet<>()).addAll(types));

            for (Map.Entry<Stmt, Map<Integer, Set<SootClass>>> entry : tfa.callSiteArgCtx.entrySet()) {
                Stmt callSite = entry.getKey();
                Map<Integer, Set<SootClass>> argCtx = entry.getValue();
                InvokeExpr ie = callSite.getInvokeExpr();

                if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                    Set<SootClass> receiverTypes = tfa.receiverTypesAtSite.get(callSite);
                    if (receiverTypes == null) continue;
                    for (SootClass receiverType : receiverTypes) {
                        SootMethod concreteTarget = resolveDispatch(receiverType, ie.getMethod());
                        if (concreteTarget == null || !isAnalyzable(concreteTarget)) continue;
                        Map<Integer, Set<SootClass>> calleeCtx = deepCopyContext(argCtx);
                        calleeCtx.put(-1, Collections.singleton(receiverType));
                        worklist.add(new AnalysisTask(concreteTarget, calleeCtx));
                    }
                } else {
                    SootMethod callee = ie.getMethod();
                    if (isAnalyzable(callee)) {
                        worklist.add(new AnalysisTask(callee, deepCopyContext(argCtx)));
                    }
                }
            }
        }
    }

    private static <K> int totalSize(Map<K, Set<SootClass>> m) {
        int n = 0;
        for (Set<SootClass> v : m.values()) n += v.size();
        return n;
    }

    // -------------------------------------------------------------------------
    // Rewriting: VirtualInvokeExpr → StaticInvokeExpr on a cloned static method
    // -------------------------------------------------------------------------

    private int rewriteCallSites(List<SootMethod> reachable) {
        int count = 0;
        for (SootMethod method : reachable) {
            Map<Stmt, Set<SootClass>> sites = receiverTypesPerSite.get(method);
            if (sites == null) continue;

            Body body;
            try {
                body = method.retrieveActiveBody();
            } catch (RuntimeException e) {
                continue;
            }

            for (Unit unit : new ArrayList<>(body.getUnits())) {
                if (!(unit instanceof Stmt)) continue;
                Stmt stmt = (Stmt) unit;
                if (!stmt.containsInvokeExpr()) continue;
                InvokeExpr invokeExpr = stmt.getInvokeExpr();
                if (!(invokeExpr instanceof VirtualInvokeExpr || invokeExpr instanceof InterfaceInvokeExpr)) continue;

                Set<SootClass> receiverTypes = sites.get(stmt);
                // Only devirtualize if all observed contexts agree on a single concrete type
                if (receiverTypes == null || receiverTypes.size() != 1) continue;

                SootClass concreteType = receiverTypes.iterator().next();
                InstanceInvokeExpr iie = (InstanceInvokeExpr) invokeExpr;
                SootMethod concreteMethod = resolveDispatch(concreteType, iie.getMethod());
                if (concreteMethod == null || !concreteMethod.isConcrete()) continue;
                // Never materialize $mono clones on JDK classes — the JVM loads those
                // from the boot classpath at runtime and won't see our rewritten copies.
                if (isJdkClass(concreteMethod.getDeclaringClass())) continue;

                SootMethod staticClone = getOrCreateStaticClone(concreteMethod, concreteType);

                // The static clone expects the receiver typed as concreteType. If the
                // base local has a wider declared type (e.g. an interface), insert a
                // cast: tmp = (concreteType) base; then pass tmp.
                Value receiverValue = iie.getBase();
                if (receiverValue instanceof Local
                        && !concreteType.getType().equals(((Local) receiverValue).getType())) {
                    Local castLocal = Jimple.v().newLocal(
                        "$mono_cast_" + count, concreteType.getType());
                    body.getLocals().add(castLocal);
                    AssignStmt castStmt = Jimple.v().newAssignStmt(
                        castLocal,
                        Jimple.v().newCastExpr(receiverValue, concreteType.getType()));
                    body.getUnits().insertBefore(castStmt, stmt);
                    receiverValue = castLocal;
                }

                // staticinvoke args: [receiver, originalArg0, originalArg1, ...]
                List<Value> allArgs = new ArrayList<>();
                allArgs.add(receiverValue);
                allArgs.addAll(iie.getArgs());

                soot.jimple.StaticInvokeExpr staticExpr =
                    Jimple.v().newStaticInvokeExpr(staticClone.makeRef(), allArgs);

                if (stmt instanceof AssignStmt) {
                    ((AssignStmt) stmt).setRightOp(staticExpr);
                } else if (stmt instanceof InvokeStmt) {
                    ((InvokeStmt) stmt).setInvokeExpr(staticExpr);
                }

                int line = stmt.getJavaSourceStartLineNumber();
                if (line > 0) {
                    System.out.printf("%03d -> %s%n", line, staticClone.getSignature());
                }
                count++;
            }
        }
        return count;
    }

    private SootMethod getOrCreateStaticClone(SootMethod concreteMethod, SootClass concreteType) {
        return monoClones
            .computeIfAbsent(concreteMethod, k -> new HashMap<>())
            .computeIfAbsent(concreteType, cls -> createStaticClone(concreteMethod, cls));
    }

    private SootMethod createStaticClone(SootMethod concreteMethod, SootClass concreteType) {
        // Parameter list: concreteType (the receiver) followed by the original parameters
        List<Type> paramTypes = new ArrayList<>();
        paramTypes.add(concreteType.getType());
        paramTypes.addAll(concreteMethod.getParameterTypes());

        String cloneName = concreteMethod.getName() + "$mono$" + concreteType.getShortJavaStyleName();
        int modifiers = (concreteMethod.getModifiers() | Modifier.STATIC) & ~Modifier.ABSTRACT & ~Modifier.NATIVE;

        SootMethod staticMethod = new SootMethod(
            cloneName, paramTypes, concreteMethod.getReturnType(), modifiers
        );
        concreteMethod.getDeclaringClass().addMethod(staticMethod);

        // Clone the body, then fix up identity statements
        Body originalBody = concreteMethod.retrieveActiveBody();
        Body newBody = Jimple.v().newBody(staticMethod);
        newBody.importBodyContentsFrom(originalBody);

        // @this: T         → @parameter0: concreteType
        // @parameterN: T   → @parameter(N+1): T
        for (Unit unit : newBody.getUnits()) {
            if (!(unit instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) unit;
            Value rhs = id.getRightOp();
            if (rhs instanceof ThisRef) {
                id.setRightOp(Jimple.v().newParameterRef(concreteType.getType(), 0));
            } else if (rhs instanceof ParameterRef) {
                ParameterRef pr = (ParameterRef) rhs;
                id.setRightOp(Jimple.v().newParameterRef(pr.getType(), pr.getIndex() + 1));
            }
        }

        staticMethod.setActiveBody(newBody);
        return staticMethod;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isJdkClass(SootClass sc) {
        String n = sc.getName();
        return n.startsWith("java.") || n.startsWith("javax.")
            || n.startsWith("sun.")  || n.startsWith("jdk.")
            || n.startsWith("com.sun.");
    }

    private SootMethod resolveDispatch(SootClass receiverType, SootMethod virtualMethod) {
        try {
            return Scene.v().getOrMakeFastHierarchy()
                       .resolveConcreteDispatch(receiverType, virtualMethod);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAnalyzable(SootMethod m) {
        return m.isConcrete()
            && !m.isAbstract()
            && m.getDeclaringClass().isApplicationClass();
    }

    private List<SootMethod> collectReachableMethods(CallGraph callGraph) {
        List<SootMethod> reachable = new ArrayList<>();
        Set<SootMethod> visited = new HashSet<>();
        Deque<SootMethod> worklist = new ArrayDeque<>(Scene.v().getEntryPoints());

        while (!worklist.isEmpty()) {
            SootMethod current = worklist.removeFirst();
            if (!visited.add(current)) continue;
            if (!current.getDeclaringClass().isApplicationClass()) continue;

            reachable.add(current);

            Body body;
            try {
                body = current.retrieveActiveBody();
            } catch (RuntimeException e) {
                continue;
            }

            for (Unit unit : body.getUnits()) {
                if (!(unit instanceof Stmt)) continue;
                Stmt stmt = (Stmt) unit;
                if (!stmt.containsInvokeExpr()) continue;

                Iterator<Edge> outEdges = callGraph.edgesOutOf(stmt);
                while (outEdges.hasNext()) {
                    SootMethod target = outEdges.next().tgt();
                    if (target.getDeclaringClass().isApplicationClass()) {
                        worklist.addLast(target);
                    }
                }
            }
        }

        return reachable;
    }

    private static Map<Integer, Set<SootClass>> deepCopyContext(Map<Integer, Set<SootClass>> ctx) {
        Map<Integer, Set<SootClass>> copy = new HashMap<>();
        ctx.forEach((k, v) -> copy.put(k, new HashSet<>(v)));
        return copy;
    }

    // -------------------------------------------------------------------------
    // AnalysisTask: worklist item pairing a method with its calling context
    // -------------------------------------------------------------------------

    private static class AnalysisTask {
        final SootMethod method;
        final Map<Integer, Set<SootClass>> paramCtx;

        AnalysisTask(SootMethod method, Map<Integer, Set<SootClass>> paramCtx) {
            this.method = method;
            this.paramCtx = paramCtx;
        }
    }
}
