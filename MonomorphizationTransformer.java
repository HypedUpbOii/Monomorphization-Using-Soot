import java.util.*;

import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.BriefUnitGraph;

// Pair of (method, context) used as a worklist item.
class Task {
    SootMethod method;
    Map<Integer, Set<SootClass>> ctx;

    Task(SootMethod m, Map<Integer, Set<SootClass>> c) {
        this.method = m;
        this.ctx = c;
    }
};

public class MonomorphizationTransformer extends SceneTransformer {

    // per-method context-sensitive return-type summaries
    Map<SootMethod, Map<Map<Integer, Set<SootClass>>, Set<SootClass>>> summaries = new HashMap<>();
    // union of the receiver types ever observed at each dispatch site
    Map<SootMethod, Map<Stmt, Set<SootClass>>> receiversAt = new HashMap<>();
    // cache of already-created static clones
    Map<SootMethod, Map<SootClass, SootMethod>> clones = new HashMap<>();

    Map<SootField, Set<SootClass>> fieldTypes = new HashMap<>();
    Map<Type, Set<SootClass>> arrayElemTypes = new HashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();
        List<SootMethod> reachable = collectReachable(cg);

        // outer fixed-point: redo the context-sensitive pass until the global field / array maps stop changing
        int prevF = -1, prevA = -1;
        for (int it = 0; it < 10; it++) {
            runPass(reachable);
            int f = sizeOf(fieldTypes), a = sizeOf(arrayElemTypes);
            if (f == prevF && a == prevA) break;
            prevF = f; prevA = a;
        }

        int n = rewrite(reachable);
        System.out.println("Monomorphized call sites: " + n);
    }

    void runPass(List<SootMethod> reachable) {
        Deque<Task> wl = new ArrayDeque<>();
        Map<SootMethod, Set<Map<Integer, Set<SootClass>>>> seen = new HashMap<>();

        for (SootMethod m : reachable) wl.add(new Task(m, new HashMap<>()));

        while (!wl.isEmpty()) {
            Task t = wl.removeFirst();
            SootMethod m = t.method;
            var ctx = t.ctx;

            if (!isApp(m)) continue;
            if (!seen.computeIfAbsent(m, k -> new HashSet<>()).add(deepCopy(ctx))) continue;

            Body body = m.retrieveActiveBody();
            
            TypeFlowAnalysis tfa = new TypeFlowAnalysis(
                new BriefUnitGraph(body), ctx, summaries, fieldTypes, arrayElemTypes);

            summaries.computeIfAbsent(m, k -> new HashMap<>())
                     .put(deepCopy(ctx), new HashSet<>(tfa.returnTypes));

            var sites = receiversAt.computeIfAbsent(m, k -> new HashMap<>());
            for (var e : tfa.receiverTypesAtSite.entrySet()) {
                sites.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
            }

            // enqueue callees under the contexts discovered here
            for (var e : tfa.callSiteArgCtx.entrySet()) {
                Stmt s = e.getKey();
                var argCtx = e.getValue();
                InvokeExpr ie = s.getInvokeExpr();
                if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                    Set<SootClass> recv = tfa.receiverTypesAtSite.get(s);
                    if (recv == null) continue;
                    for (SootClass r : recv) {
                        SootMethod tgt = resolveConcrete(r, ie.getMethod());
                        if (tgt == null || !isApp(tgt)) continue;
                        var nctx = deepCopy(argCtx);
                        nctx.put(-1, Collections.singleton(r));
                        wl.add(new Task(tgt, nctx));
                    }
                } else {
                    SootMethod callee = ie.getMethod();
                    if (isApp(callee)) wl.add(new Task(callee, deepCopy(argCtx)));
                }
            }
        }
    }

    int rewrite(List<SootMethod> reachable) {
        int n = 0;
        for (SootMethod m : reachable) {
            Map<Stmt, Set<SootClass>> sites = receiversAt.get(m);
            if (sites == null) continue;

            Body body = m.retrieveActiveBody();
    
            // snapshot the unit chain -- we insert casts while iterating
            for (Unit u : new ArrayList<>(body.getUnits())) {
                if (!(u instanceof Stmt)) continue;
                Stmt s = (Stmt) u;
                if (!s.containsInvokeExpr()) continue;
                InvokeExpr ie = s.getInvokeExpr();
                if (!(ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr)) continue;

                Set<SootClass> recv = sites.get(s);
                if (recv == null || recv.size() != 1) continue;

                SootClass concrete = recv.iterator().next();
                InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
                SootMethod target = resolveConcrete(concrete, iie.getMethod());
                if (target == null || !target.isConcrete()) continue;

                // loaded JDK classes can't be overridden, so don't create $mono on them
                if (isJdk(target.getDeclaringClass())) continue;

                SootMethod clone = getClone(target, concrete);

                // the clone's first param is typed as the concrete class; if the
                // receiver local is declared as a wider type, cast it first
                Value base = iie.getBase();
                if (base instanceof Local && !concrete.getType().equals(((Local) base).getType())) {
                    Local tmp = Jimple.v().newLocal("$mono_cast_" + n, concrete.getType());
                    body.getLocals().add(tmp);
                    AssignStmt cast = Jimple.v().newAssignStmt(
                        tmp, Jimple.v().newCastExpr(base, concrete.getType()));
                    body.getUnits().insertBefore(cast, s);
                    base = tmp;
                }

                List<Value> args = new ArrayList<>();
                args.add(base);
                args.addAll(iie.getArgs());
                StaticInvokeExpr sie = Jimple.v().newStaticInvokeExpr(clone.makeRef(), args);

                if (s instanceof JAssignStmt) ((JAssignStmt) s).setRightOp(sie);
                else if (s instanceof JInvokeStmt) ((JInvokeStmt) s).setInvokeExpr(sie);

                int line = s.getJavaSourceStartLineNumber();
                if (line > 0) System.out.printf("%03d -> %s%n", line, clone.getSignature());
                n++;
            }
        }
        return n;
    }

    SootMethod getClone(SootMethod tgt, SootClass concrete) {
        return clones.computeIfAbsent(tgt, k -> new HashMap<>()).computeIfAbsent(concrete, k -> makeClone(tgt, concrete));
    }

    SootMethod makeClone(SootMethod tgt, SootClass concrete) {
        List<Type> params = new ArrayList<>();
        params.add(concrete.getType());
        params.addAll(tgt.getParameterTypes());

        String name = tgt.getName() + "$mono$" + concrete.getShortJavaStyleName();
        int mods = (tgt.getModifiers() | Modifier.STATIC) & ~Modifier.ABSTRACT & ~Modifier.NATIVE;

        SootMethod out = new SootMethod(name, params, tgt.getReturnType(), mods);
        tgt.getDeclaringClass().addMethod(out);

        Body src = tgt.retrieveActiveBody();
        Body dst = Jimple.v().newBody(out);
        dst.importBodyContentsFrom(src);

        // rewire @this -> @parameter0, and push every @parameterN down by one
        for (Unit u : dst.getUnits()) {
            if (!(u instanceof IdentityStmt)) continue;
            IdentityStmt id = (IdentityStmt) u;
            Value r = id.getRightOp();
            if (r instanceof ThisRef) {
                id.setRightOp(Jimple.v().newParameterRef(concrete.getType(), 0));
            } else if (r instanceof ParameterRef) {
                ParameterRef pr = (ParameterRef) r;
                id.setRightOp(Jimple.v().newParameterRef(pr.getType(), pr.getIndex() + 1));
            }
        }
        out.setActiveBody(dst);
        return out;
    }

    static boolean isJdk(SootClass c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.")
            || n.startsWith("sun.")  || n.startsWith("jdk.")
            || n.startsWith("com.sun.");
    }

    SootMethod resolveConcrete(SootClass recv, SootMethod m) {    
        return Scene.v().getOrMakeFastHierarchy().resolveConcreteDispatch(recv, m);
    }

    boolean isApp(SootMethod m) {
        return m.isConcrete() && !m.isAbstract() && m.getDeclaringClass().isApplicationClass();
    }

    List<SootMethod> collectReachable(CallGraph cg) {
        List<SootMethod> out = new ArrayList<>();
        Set<SootMethod> seen = new HashSet<>();
        Deque<SootMethod> wl = new ArrayDeque<>(Scene.v().getEntryPoints());
        while (!wl.isEmpty()) {
            SootMethod cur = wl.removeFirst();
            if (!seen.add(cur)) continue;
            if (!cur.getDeclaringClass().isApplicationClass()) continue;
            out.add(cur);

            Body body = cur.retrieveActiveBody(); 
            for (Unit u : body.getUnits()) {
                if (!(u instanceof Stmt)) continue;
                Stmt s = (Stmt) u;
                if (!s.containsInvokeExpr()) continue;
                Iterator<Edge> it = cg.edgesOutOf(s);
                while (it.hasNext()) {
                    SootMethod t = it.next().tgt();
                    if (t.getDeclaringClass().isApplicationClass()) wl.addLast(t);
                }
            }
        }
        return out;
    }

    static Map<Integer, Set<SootClass>> deepCopy(Map<Integer, Set<SootClass>> ctx) {
        Map<Integer, Set<SootClass>> out = new HashMap<>();
        for (var e : ctx.entrySet()) out.put(e.getKey(), new HashSet<>(e.getValue()));
        return out;
    }

    static <K> int sizeOf(Map<K, Set<SootClass>> m) {
        int n = 0;
        for (Set<SootClass> v : m.values()) n += v.size();
        return n;
    }
}
