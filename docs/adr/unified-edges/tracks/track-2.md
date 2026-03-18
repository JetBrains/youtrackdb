# Track 2: Merge StatefulEdge into Edge

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
- [ ] Track-level code review

## Base commit
`f7f835659a`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Make `Edge` extend `Entity` and resolve method conflicts
  > **What was done:** Changed `Edge` from `extends Element, Relation<Vertex>`
  > to `extends Entity, Relation<Vertex>`. Added `default asEntity()` on Edge
  > (resolves Entity/Relation conflict). Removed `asStatefulEdge()`/
  > `asStatefulEdgeOrNull()` from Edge (inherited from Entity). Added
  > `@Override` to `getSchemaClass()`, `getSchemaClassName()`, `delete()`.
  > Added `isRelation()`/`asRelation()`/`asRelationOrNull()` overrides
  > returning true/this (code review fix). Removed `asEntity()` default from
  > StatefulEdge (now on Edge). Disambiguated 3 ResultInternal constructor
  > calls with `(Identifiable)` cast. Removed dead EdgeInternal branch in
  > ScriptTransformerImpl (code review fix).
  >
  > **What was discovered:** Edge extending both Identifiable (via Entity)
  > and Relation causes constructor ambiguity in `ResultInternal` which has
  > `(session, Identifiable)` and `(session, Relation<?>)` overloads.
  > 3 call sites needed explicit `(Identifiable)` casts. Also, Entity's
  > `isRelation()` default returns false — edges need explicit override to
  > return true now that Edge IS-A Entity.
  >
  > **Key files:** `Edge.java` (modified), `StatefulEdge.java` (modified),
  > `ScriptTransformerImpl.java` (modified), `FetchEdgesFromToVerticesStep.java`
  > (modified), `FetchEdgesToVerticesStep.java` (modified)

- [ ] Step 2: Remove `isLightweight()` from `Edge` and all call sites
  > Make `isLightweight()` a default method returning `false` on `Edge`
  > (overrides `Relation.isLightweight()`). Remove the `isLightweight()`
  > override in `StatefullEdgeEntityImpl`. Remove all call sites that
  > branch on `isLightweight()`:
  > - `VertexEntityImpl.removeEdgeLinkFromProperty()` — dead branch
  >   (always false after unification)
  > - `LightweightRelationImpl.equals()` — checks `isLightweight()` on
  >   the other relation
  > - Test assertions: `DbImportExportTest`, any other tests asserting
  >   `isLightweight() == false`
  >
  > Remove the `isLightweight()` declaration from `Edge` interface (keep
  > the default returning false so `Relation.isLightweight()` is satisfied
  > — `Relation` deletion is Track 3).
  >
  > This must precede `StatefulEdge` deletion per plan constraint.

- [ ] Step 3: Replace all `StatefulEdge` type references with `Edge`
  > Mechanical replacement of `StatefulEdge` with `Edge` across the
  > codebase. Now that `Edge extends Entity`, all `Entity` methods are
  > available on `Edge`, so the replacement is safe.
  >
  > Key areas:
  > - **Transaction interface** (`Transaction.java`): 7 methods change
  >   return type from `StatefulEdge` to `Edge`
  > - **Transaction implementations** (`FrontendTransactionImpl.java`,
  >   `FrontendTransactionNoTx.java`): return types + internal casts
  > - **Vertex interface** (`Vertex.java`): `addStateFulEdge()` overloads
  >   return `Edge` instead of `StatefulEdge`
  > - **VertexEntityImpl**: `addStateFulEdge()` implementations
  > - **EntityImpl**: `asStatefulEdge()`/`asStatefulEdgeOrNull()` use
  >   `instanceof Edge` + cast to `Edge` (since Edge IS-A StatefulEdge's
  >   contract now)
  > - **DatabaseSessionEmbedded**: method signatures
  > - **Gremlin layer**: `YTDBEdgeInternal.getRawEntity()` returns `Edge`;
  >   `YTDBStatefulEdgeImpl` constructor takes `Edge`;
  >   `GremlinResultMapper`/`YTDBGraphImplAbstract` casts updated
  > - **ResultInternal**: any remaining `StatefulEdge` references
  > - **Test files**: `instanceof StatefulEdge` → `instanceof Edge`, etc.

- [ ] Step 4: Delete `StatefulEdge.java` and remove `asStatefulEdge()`/`asStatefulEdgeOrNull()`
  > Delete `StatefulEdge.java` — it is now an empty bridge interface
  > (all its contract is on `Edge`).
  >
  > Remove `asStatefulEdge()`/`asStatefulEdgeOrNull()` from all
  > interfaces and implementations:
  > - `Edge.java` (declaration)
  > - `Entity.java` (declaration)
  > - `Result.java` / `DBRecord.java` (if declared)
  > - `EntityImpl.java` (implementation)
  > - `ResultInternal.java` (implementation)
  > - `UpdatableResult.java` (implementation)
  > - `RecordBytes.java`, `EmbeddedEntityImpl.java` (implementations)
  >
  > Replace any remaining callers of `asStatefulEdge()` with `asEdge()`.
  > Remove `StatefullEdgeEntityImpl`'s `implements StatefulEdge` (replace
  > with `implements Edge` — already an Edge via EntityImpl, but verify).
