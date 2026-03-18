# Track 1: Migrate call sites to unified edge API

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
- [ ] Track-level code review

## Base commit
`5e64e89da9`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Give `EntityImpl` and `ResultInternal` standalone `isEdge()`/`asEdge()`/`asEdgeOrNull()` implementations
  > **What was done:** Made `EntityImpl.isEdge()` standalone with its own
  > `instanceof Edge` + schema check instead of delegating to `isStatefulEdge()`.
  > Made `EntityImpl.asEdge()`/`asEdgeOrNull()` use `instanceof Edge` directly
  > instead of delegating to `asStatefulEdgeOrNull()`. Made
  > `ResultInternal.isEdge()` standalone by merging the `relation instanceof Edge`
  > check with the identifiable switch (preserving `!cls.isAbstract()` check).
  > Updated `ResultInternal.asEdge()`/`asEdgeOrNull()`/`asRelation()`/
  > `asRelationOrNull()` to route through `isEdge()` + `asEntity().asEdge()`.
  > Updated `UpdatableResult.isStatefulEdge()` to delegate to `isEdge()`.
  >
  > **Key files:** `EntityImpl.java` (modified), `ResultInternal.java` (modified),
  > `UpdatableResult.java` (modified)

- [x] Step 2: Migrate all call sites from stateful edge API to unified edge API
  > **What was done:** Migrated all `isStatefulEdge()`/`isStateful()` call sites
  > to `isEdge()` across 19 files. Removed `isStateful()` guards where they
  > were always true (SQLFunctionShortestPath, SQLFunctionAstar,
  > FetchEdgesToVerticesStep, FetchEdgesFromToVerticesStep). Simplified
  > CastToEdgeStep (removed dead double-check). Fixed ResultSet.edgeStream()
  > to use `asEdge()` instead of `asStatefulEdge()`. Collapsed
  > ResultInternal.convertPropertyValue() lightweight/stateful branch into
  > single edge path.
  >
  > **What was discovered:** The `Edge` interface does not extend `Entity` or
  > `Identifiable`, so `asEdge()` returning `Edge` cannot be used where
  > `getIdentity()`, `getProperty()`, or `asEntity()` is needed. This means
  > ~9 call sites must retain `asStatefulEdge()` until Track 2 merges
  > `StatefulEdge` into `Edge`. Affected: CastToEdgeStep, CreateEdgesStep,
  > FetchEdgesToVerticesStep, FetchEdgesFromToVerticesStep, SQLUpdateItem,
  > SQLFunctionShortestPath, SQLFunctionAstar, ResultInternal.toMapValue().
  > Gremlin layer uses `(StatefulEdge)` casts from `asEdge()` results.
  >
  > **What changed from the plan:** DbImportExportTest was NOT migrated — it
  > uses `getString()` (Entity method) on the result, requiring
  > `asStatefulEdge()`. MatchStepUnitTest mock was NOT changed here — it
  > implements the `Result` interface's `isStatefulEdge()` method which is
  > removed in Step 4. CreateEdgesStep keeps a fallback for lightweight edges
  > (EdgeImpl still exists until Step 3).
  >
  > **Key files:** `EntityImpl.java` (modified), `EdgeIterator.java` (modified),
  > `GraphRepair.java` (modified), `YTDBStatefulEdgeImpl.java` (modified),
  > `YTDBVertexImpl.java` (modified), `YTDBGraphImplAbstract.java` (modified),
  > `YTDBGraphStep.java` (modified), `YTDBPropertyImpl.java` (modified),
  > `YTDBElementImpl.java` (modified), `GremlinResultMapper.java` (modified),
  > `SQLUpdateItem.java` (modified), `FetchEdgesToVerticesStep.java` (modified),
  > `FetchEdgesFromToVerticesStep.java` (modified), `CreateEdgesStep.java`
  > (modified), `CastToEdgeStep.java` (modified),
  > `SQLFunctionShortestPath.java` (modified), `SQLFunctionAstar.java`
  > (modified), `ResultInternal.java` (modified), `ResultSet.java` (modified)
  >
  > **Critical context:** Future tracks should be aware that any call site
  > needing Entity/Identifiable methods on an Edge MUST use `asStatefulEdge()`
  > until Track 2 merges StatefulEdge into Edge. After Track 2, all these casts
  > can be removed.

- [x] Step 3: Delete `EdgeImpl` and unify edge creation to always be record-based
  > **What was done:** Deleted `EdgeImpl.java`. Unified `addEdgeInternal()` to
  > always create record-based edges via `newStatefulEdgeInternal()`, removing
  > the `createLightweightEdge` branching and `isRegular` parameter. Bridged
  > `newLightweightEdgeInternal()` with throw (dead code). Added abstract class
  > rejection in `newLightweightEdge()`. Bridged `EdgeIterator` vertex branch
  > with `IllegalStateException` guard for legacy LinkBag entries. Simplified
  > `CreateEdgesStep` to always return `UpdatableResult`. Fixed
  > `VertexEntityImpl.addEdge()` to route all edges through `newStatefulEdge()`
  > instead of routing abstract classes to `newLightweightEdge()`.
  >
  > **What was discovered:** Abstract edge classes (created via
  > `createLightweightEdgeClass()`) cannot have storage collections, so
  > redirecting lightweight creation through the stateful path requires
  > concrete edge classes. Tests using `createLightweightEdgeClass()` had to
  > be migrated to `createEdgeClass()`. The composite unique index behavior
  > changed: with record-based edges, each edge has a unique RID, so
  > `(type, out_Link)` composite indexes no longer conflict when two vertices
  > link to the same target.
  >
  > **What changed from the plan:** Step scope expanded beyond the planned 3
  > files. Test migrations were necessary to keep the build green:
  > `DoubleSidedEdgeLinkBagTest` (assertions for unified RidPair pattern),
  > `LightWeightEdgesTest`, `UniqueIndexTest`, `EntityTransactionalValidationTest`,
  > `CreateLightWeightEdgesSQLTest`, and `DbImportExportTest` (in tests module).
  > LINKBAG property types in validation tests changed from vertex class to
  > edge class to match the new storage model.
  >
  > **Key files:** `EdgeImpl.java` (deleted), `DatabaseSessionEmbedded.java`
  > (modified), `EdgeIterator.java` (modified), `CreateEdgesStep.java` (modified),
  > `VertexEntityImpl.java` (modified), `DoubleSidedEdgeLinkBagTest.java`
  > (modified), `LightWeightEdgesTest.java` (modified), `UniqueIndexTest.java`
  > (modified), `EntityTransactionalValidationTest.java` (modified),
  > `CreateLightWeightEdgesSQLTest.java` (modified), `DbImportExportTest.java`
  > (modified)
  >
  > **Critical context:** `createLightweightEdgeClass()` still exists and creates
  > abstract edge classes, but these can no longer be used for edge creation.
  > Track 5 will remove it. `newLightweightEdge()` now rejects abstract classes
  > but accepts concrete classes (delegates to addEdgeInternal). It is
  > effectively equivalent to `newStatefulEdge()` for non-abstract classes.

- [ ] Step 4: Remove dead `isStatefulEdge()`/`isStateful()` definitions and rename typo methods
  > Remove `isStatefulEdge()` from interfaces: `DBRecord`, `Entity`, `Result`.
  > Remove `isStatefulEdge()` implementations: `EntityImpl`, `ResultInternal`,
  > `UpdatableResult`, `RecordBytes`, `EmbeddedEntityImpl`.
  > Remove `isStateful()` default method from `Edge`.
  > Remove `isStatefulEdge()` from test mock: `MatchStepUnitTest`.
  >
  > **NOT removed** (retained until Track 2): `asStatefulEdge()`,
  > `asStatefulEdgeOrNull()` on all interfaces and implementations. These are
  > needed because `Result.asStatefulEdge()` default calls
  > `asEntity().asStatefulEdge()`.
  >
  > Rename typo methods:
  > - `ResultSet.findFirstStateFullEdge()` → `findFirstEdge()` (fix body:
  >   `asStatefulEdge()` → `asEdge()`)
  > - `ResultSet.findFirstSateFullEdgeOrNull()` → `findFirstEdgeOrNull()`
  >   (fix body: `asStatefulEdgeOrNull()` → `asEdgeOrNull()`)
  > - `YTDBGraphTraversalDSL.addStateFullEdgeClass()` → `addEdgeClass()`
  >   (update all callers and generated DSL)
  >
  > Update tests referencing renamed methods: `ResultSetTest.java`,
  > `SelectStatementExecutionTest.java` (method name strings with "StateFull").
  >
  > **Files:** ~12 files (interface/implementation cleanup + renames)
