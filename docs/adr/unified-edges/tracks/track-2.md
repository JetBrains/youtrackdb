# Track 2: Merge StatefulEdge into Edge

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
`f7f835659a`

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Make `Edge` extend `Entity` and resolve method conflicts
  > Make `Edge` extend `Entity` in addition to `Element` and
  > `Relation<Vertex>`. This is the core merge — after this commit,
  > every `Edge` IS-A `Entity`.
  >
  > Resolve method conflicts between `Edge` and `Entity`:
  > - `getSchemaClass()`: `Edge` declares `@Nonnull`, `Entity` declares
  >   `@Nullable`. Keep `@Nonnull` on `Edge` (edges always have a schema
  >   class). Remove the declaration from `Edge` if it's inherited
  >   identically, or keep the narrowed version.
  > - `getSchemaClassName()`: same pattern as `getSchemaClass()`.
  > - `toMap()`, `toJSON()`, `delete()`: both declare — remove duplicates
  >   from `Edge` if inherited identically from `Entity`/`Relation`.
  > - `asStatefulEdge()`/`asStatefulEdgeOrNull()`: both `Edge` and `Entity`
  >   declare these — remove the duplicate from `Edge`.
  > - Add `default Entity asEntity() { return this; }` on `Edge` (moved
  >   from `StatefulEdge`).
  >
  > Remove the `asEntity()` default from `StatefulEdge` (now redundant —
  > inherited from `Edge`).
  >
  > Files: `Edge.java`, `StatefulEdge.java`, possibly `Entity.java`.
  > Verify: compile + full core test suite.

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
