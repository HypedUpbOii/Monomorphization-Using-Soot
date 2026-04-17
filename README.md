# Monomorphization Optimizer

A Soot-based Java bytecode optimizer that devirtualizes virtual method calls using interprocedural, context-sensitive type-flow analysis. When a virtual call site can be proven to always dispatch to exactly one concrete method, it is replaced with a static call to a cloned version of that method.

## Overview

Java virtual dispatch (`invokevirtual`) requires a vtable lookup at runtime. If static analysis can prove that the receiver of a virtual call always has exactly one concrete type, the dispatch can be replaced with a direct static call — eliminating the lookup and enabling further optimizations (e.g. inlining).

This optimizer implements that transformation in three phases:

1. **Type-flow analysis** — forward dataflow that tracks, at each program point, the set of concrete classes each local variable may hold.
2. **Interprocedural context-sensitive propagation** — runs the analysis across the full call graph, threading type information from call sites into callees as calling contexts.
3. **Rewriting** — virtual call sites with a singleton receiver type are replaced with a `staticinvoke` on a freshly cloned static method.

## Files

| File | Role |
|---|---|
| `Optimizer.java` | Entry point. Configures Soot (SPARK call graph, Jimple output) and registers the transformer. |
| `MonomorphizationTransformer.java` | `SceneTransformer` that orchestrates the full pipeline: reachability, analysis worklist, rewriting, and static clone creation. |
| `TypeFlowAnalysis.java` | `ForwardFlowAnalysis<Unit, TypeState>` that computes concrete types for locals within one method body under a given calling context. |
| `TypeState.java` | Dataflow lattice element: `Map<Local, Set<SootClass>>`. Absence means unknown. Merge is union. |

## Design

### TypeState

Maps each `Local` to the set of concrete `SootClass` instances it may point to at a given program point. An absent entry means no type information is known (conservative). The join/merge operation is set union.

### TypeFlowAnalysis

A `ForwardFlowAnalysis` run on a single method body. Constructor sequence (mirrors `ScalarReplacementAnalysis`): `initializeParams()` → `doAnalysis()` → `finalizeResults()`.

`flowThrough` handles:
- `IdentityStmt` (`@this`, `@parameterN`) — seeds parameter locals from the calling context (`paramCtx`)
- `NewExpr` — singleton set containing the allocated class
- `Local = Local` — propagates type set; clears lhs if source is unknown
- `CastExpr` — narrows to types that pass `FastHierarchy.canStoreClass`
- `InvokeExpr` on lhs — looks up the callee's return-type summary for the current arg context
- Any other rhs (field reads, array reads, etc.) — clears the lhs to avoid carrying stale types from a prior assignment

After `doAnalysis()`, `finalizeResults()` reads `getFlowBefore(unit)` at each virtual call site to extract receiver types, and at each return statement to collect return types for the method summary.

Outputs:
- `receiverTypesAtSite` — virtual call site → concrete receiver types at that point
- `callSiteArgCtx` — call site → argument types (used to build callee contexts)
- `returnTypes` — concrete types this method may return under the current context

### MonomorphizationTransformer

**Analysis phase** (`runContextSensitiveAnalysis`):

Uses a worklist of `(method, paramCtx)` tasks. Each method is seeded with an empty context as a conservative fallback (covers methods reachable from library code). As call sites are analyzed, callees are enqueued with the concrete argument types discovered at that site. For virtual calls, each possible concrete dispatch target is enqueued separately with `this` refined to the specific receiver type.

Context sensitivity is realized in `TypeFlowAnalysis.handleIdentity`: when `paramCtx` contains types for a parameter index, those types are injected into the corresponding local at method entry, propagating caller knowledge into the callee body.

Receiver types are accumulated per call site across all contexts (union). A call site is only devirtualized if **all observed contexts agree on a single concrete type** — if different callers pass different receiver types, the union exceeds size 1 and the site is conservatively left alone.

**Limitation of current rewriting:** context sensitivity improves precision (types flow through parameter chains transitively) but the benefit is limited to cases where a method is always called with the same concrete types. When a method is called from multiple contexts with different types, unioning loses per-context precision. Full exploitation would require cloning the analyzed method itself per context (not just the target of the virtual call) — this is the natural next step toward full monomorphization.

**Rewriting phase** (`rewriteCallSites`):

For each virtual call site where the receiver type union is a singleton `{T}`:

1. `resolveConcreteDispatch(T, virtualMethod)` finds the exact method that would be invoked.
2. `getOrCreateStaticClone(concreteMethod, T)` returns a cached or freshly created static copy of that method (see below).
3. The `virtualinvoke` is replaced with `staticinvoke` passing the receiver as the first argument.

**Static clone creation** (`createStaticClone`):

For a virtual method `T foo(A a, B b)` on class `C`, creates a new static method `T foo$mono$T(T this$0, A a, B b)` on `C`'s declaring class. The body is deep-copied via `Body.importBodyContentsFrom`. Identity statements are then fixed up: `@this: C` → `@parameter0: T`, and each `@parameterN` shifts to `@parameter(N+1)`. The clone is cached in `monoClones` so the same `(concreteMethod, concreteType)` pair is only created once even if multiple call sites dispatch to it.

The original method is left untouched.

## Known Limitations

- **Field and array reads** do not propagate type info (conservative: receiver types from fields are unknown).
- **Context union before rewriting** means a method called with two different receiver types cannot be devirtualized at its internal virtual call sites without cloning the method per context.
- Methods only reachable from library code (not application code) are analyzed with an empty context, which may miss devirtualization opportunities.

## Usage

```
javac -cp soot-4.6.0-jar-with-dependencies.jar *.java
java -cp .:soot-4.6.0-jar-with-dependencies.jar Optimizer <testcase> --mono
```
