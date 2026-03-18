# Track 1: Migrate call sites to unified edge API

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Give `EntityImpl` and `ResultInternal` standalone `isEdge()`/`asEdge()`/`asEdgeOrNull()` implementations
  > Make `EntityImpl.isEdge()` stop delegating to `isStatefulEdge()` — copy the
  > body (instanceof Edge check + schema check). Make `EntityImpl.asEdge()` and
  > `asEdgeOrNull()` stop delegating to `asStatefulEdgeOrNull()` — use
  > `instanceof Edge` directly, returning `Edge` type.
  >
  > Make `ResultInternal.isEdge()` standalone by merging the current `isEdge()`
  > body (lines 992-1003: `relation instanceof Edge` check) with the
  > `isStatefulEdge()` body (lines 1006-1025: identifiable switch with
  > `!cls.isAbstract()` check). Similarly update `asEdge()` and `asEdgeOrNull()`
  > to stop delegating through `isStatefulEdge()`/`asStatefulEdge()`.
  >
  > Update `UpdatableResult.isStatefulEdge()` to also delegate to `isEdge()`
  > instead of `isStatefulEdge()` (preparing for Step 4 removal).
  >
  > **Files:** `EntityImpl.java`, `ResultInternal.java`, `UpdatableResult.java`

- [ ] Step 2: Migrate all call sites from stateful edge API to unified edge API
  > Mechanical migration across the codebase. All changes follow the same
  > patterns:
  > - `entity.isStatefulEdge()` → `entity.isEdge()`
  > - `entity.asStatefulEdge()` → `entity.asEdge()` (cast to Edge)
  > - `edge.isStateful()` → remove guard (always true after unification) or
  >   replace with `true`
  > - `edge.asStatefulEdge()` → remove cast (edge is already Edge)
  > - `result.isStatefulEdge()` → `result.isEdge()`
  > - `result.asStatefulEdge()` → `result.asEdge()`
  >
  > Call sites grouped by area:
  >
  > **Core:** `EntityImpl.java` (lines 572, 2006, 4084),
  > `EdgeIterator.java` (lines 56-57, 88-90), `GraphRepair.java` (lines 136,
  > 175, 224, 586-587)
  >
  > **Gremlin:** `YTDBStatefulEdgeImpl.java` (line 63),
  > `YTDBVertexImpl.java` (lines 102-103), `YTDBGraphImplAbstract.java`
  > (line 170), `YTDBGraphStep.java` (line 80), `YTDBPropertyImpl.java`
  > (lines 60-61), `YTDBElementImpl.java` (line 220),
  > `GremlinResultMapper.java` (lines 37, 64-65)
  >
  > **SQL:** `SQLUpdateItem.java` (lines 265, 270),
  > `FetchEdgesToVerticesStep.java` (lines 111-112),
  > `FetchEdgesFromToVerticesStep.java` (lines 164-165),
  > `CreateEdgesStep.java` (lines 204-205), `CastToEdgeStep.java`
  > (lines 28, 30), `SQLFunctionShortestPath.java` (lines 382-383, 456-457),
  > `SQLFunctionAstar.java` (lines 327-328, 342-343)
  >
  > **Result:** `ResultInternal.java` (lines 129, 297-302, 1052-1109 — collapse
  > lightweight/stateful branches into unified edge paths),
  > `ResultSet.java` (lines 171, 406 — fix `edgeStream()` internal call)
  >
  > **Tests:** `DbImportExportTest.java` (lines 360, 366),
  > `MatchStepUnitTest.java` (line 3336 — mock override)
  >
  > **Files:** ~20 files (mechanical, same pattern throughout)

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
