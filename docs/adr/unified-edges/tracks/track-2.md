# Track 2: Merge StatefulEdge into Edge

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/4 complete)
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

- [x] Step 2: Remove `isLightweight()` from `Edge` and all call sites
  > **What was done:** Made `isLightweight()` a default returning false on
  > Edge. Removed override from StatefullEdgeEntityImpl. Removed dead
  > `|| edge.isLightweight()` branch in VertexEntityImpl (also dropped
  > now-unused `edge` parameter). Simplified ResultInternal.toMapValue()
  > to use `edge.getIdentity()` directly (Edge extends Entity extends
  > Identifiable). Removed test assertions in DbImportExportTest.
  >
  > **What was discovered:** `LightweightRelationImpl.equals()` calls
  > `isLightweight()` on a `Relation<?>`, not an `Edge` — correctly left
  > untouched. Also, `VertexEntityImpl.removeLinkFromEdge()` still has
  > a dead `instanceof StatefulEdge` branch and stale "lightweight edge"
  > comment that will be cleaned up in Track 4/5.
  >
  > **Key files:** `Edge.java` (modified), `StatefullEdgeEntityImpl.java`
  > (modified), `VertexEntityImpl.java` (modified), `ResultInternal.java`
  > (modified), `DbImportExportTest.java` (modified)

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
