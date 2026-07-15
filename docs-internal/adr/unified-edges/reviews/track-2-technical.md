# Track 2 Technical Review

## Review Summary

Track 2 merges `StatefulEdge` into `Edge` by making `Edge extend Entity`,
replacing all `StatefulEdge` type references with `Edge`, and deleting
`StatefulEdge.java`.

**Assessment: PASS with findings incorporated into step decomposition.**

## Findings

### Finding T1 [should-fix] (originally reported as blocker — downgraded)
**Location**: Edge.java, Entity.java — type hierarchy merge
**Issue**: The review flagged a diamond inheritance conflict when making
`Edge extend Entity` (`@Nonnull` vs `@Nullable` on `getSchemaClass()`).
However, `StatefulEdge extends Edge, Entity` already compiles — the same
diamond resolution applies when `Entity` moves to `Edge`'s extends list.
Java allows narrowing `@Nullable` to `@Nonnull` in a subinterface. Not a
compilation issue, but the step must verify all method declarations resolve
cleanly when `Entity` is added to `Edge`'s extends clause.
**Proposed fix**: Step 1 explicitly resolves method conflicts and removes
duplicate declarations from `Edge` that are now inherited from `Entity`.

### Finding T2 [should-fix]
**Location**: Transaction.java, FrontendTransactionImpl.java,
FrontendTransactionNoTx.java
**Issue**: Transaction interface has 7 methods returning `StatefulEdge`.
These must be changed to return `Edge` after `Edge extends Entity`.
**Proposed fix**: Included in Step 3 (replace StatefulEdge type refs).

### Finding T3 [should-fix]
**Location**: YTDBEdgeInternal.java, YTDBStatefulEdgeImpl.java,
GremlinResultMapper.java, YTDBGraphImplAbstract.java
**Issue**: Gremlin layer uses `StatefulEdge` in constructors, return types,
and casts. Must be updated before `StatefulEdge` deletion.
**Proposed fix**: Included in Step 3 (replace StatefulEdge type refs).

### Finding T4 [should-fix]
**Location**: Edge.java (declaration), Relation.java (declaration),
VertexEntityImpl.java (production call site), test files
**Issue**: `isLightweight()` is declared on both `Edge` and `Relation`.
Track 2 can only remove it from `Edge` and its call sites; `Relation`
declaration persists until Track 3. Production call site in
`VertexEntityImpl.removeEdgeLinkFromProperty()` is dead code (always false).
**Proposed fix**: Step 2 makes `isLightweight()` a default returning `false`
on `Edge`, removes the override in `StatefullEdgeEntityImpl`, removes all
call sites. `Relation.isLightweight()` declaration stays until Track 3.

### Finding T5 [should-fix]
**Location**: Vertex.java — `addStateFulEdge()` methods
**Issue**: Three overloads return `StatefulEdge`. Must change to `Edge`
before `StatefulEdge` deletion. Method rename to `addEdge()` is Track 4.
**Proposed fix**: Included in Step 3 (return type change only, not rename).

### Finding T6 [should-fix]
**Location**: YTDBStatefulEdge.java (public API), YTDBGremlinPlugin.java
**Issue**: Public API type `YTDBStatefulEdge` depends on `StatefulEdge`
indirectly through `YTDBEdgeInternal`. Must be updated.
**Proposed fix**: Step 3 changes `YTDBEdgeInternal.getRawEntity()` to return
`Edge`. `YTDBStatefulEdge` rename is deferred to Track 4.

### Finding T7 [suggestion]
**Location**: Track approach ordering
**Issue**: Plan describes isLightweight removal as step 4 (after StatefulEdge
deletion), but constraint says it must be a "preceding commit."
**Proposed fix**: Reordered — Step 2 removes `isLightweight()` before
Step 3-4 replace/delete `StatefulEdge`.

### Finding T8 [suggestion]
**Location**: Track scope estimate
**Issue**: Original estimate of ~3-4 steps is feasible. The diamond
inheritance concern (T1) was overstated. 4 steps is appropriate.
**Proposed fix**: Decomposed into 4 steps.
