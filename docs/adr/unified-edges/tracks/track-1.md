# Track 1: Migrate call sites to unified edge API

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/4 complete)
- [ ] Track-level code review

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

- [ ] Step 3: Delete `EdgeImpl` and bridge lightweight edge creation/iteration paths
  > Delete `EdgeImpl.java` (lightweight edge implementation).
  >
  > Bridge `EdgeIterator.createBidirectionalLink()` vertex branch (lines 71-87):
  > replace `new EdgeImpl(...)` with `throw new IllegalStateException("Legacy
  > lightweight edges are no longer supported")`.
  >
  > Bridge `DatabaseSessionEmbedded.newLightweightEdgeInternal()` (line 3408):
  > replace body with throw. This covers all transitive callers:
  > `newLightweightEdge()`, `addEdgeInternal()` lightweight path,
  > `VertexEntityImpl.addLightWeightEdge()`, `SQLUpdateItem`, and
  > `FrontendTransactionImpl`/`FrontendTransactionNoTx` delegates.
  >
  > **Files:** `EdgeImpl.java` (deleted), `EdgeIterator.java` (modified),
  > `DatabaseSessionEmbedded.java` (modified)

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
