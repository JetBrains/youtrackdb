# Track 2: Delete Relation hierarchy and merge StatefulEdge into Edge

## Steps

- [x] Step 1: Move StatefulEdge methods to Edge and replace StatefulEdge type references in production code
  > **Goal:** Make `Edge` absorb `StatefulEdge`'s role so that `StatefulEdge` becomes an empty
  > marker interface (like `Element`), ready for deletion in step 3.
  >
  > **Commit:** `YTDB-605: Move StatefulEdge methods to Edge and replace StatefulEdge type references`
  >
  > **Episode:**
  > - Made `Edge` extend `Entity, Relation<Vertex>` (was `Element, Relation<Vertex>`), moved
  >   `default asEntity()` from `StatefulEdge` to `Edge`.
  > - Changed return types from `StatefulEdge` to `Edge` across `Transaction` (7 methods),
  >   `FrontendTransactionImpl`, `FrontendTransactionNoTx`, `DatabaseSessionEmbedded`,
  >   `Vertex` (3 methods), `VertexEntityImpl`.
  > - Removed `StatefulEdge` casts in Gremlin layer: `YTDBStatefulEdgeImpl`, `YTDBEdgeInternal`,
  >   `GremlinResultMapper`, `YTDBGraphImplAbstract`, `YTDBGraphStep`, `YTDBVertexImpl`,
  >   `YTDBPropertyImpl`.
  > - Fixed `ResultInternal` constructor ambiguity: when `Edge` extends both `Entity`
  >   (→`Identifiable`) and `Relation<Vertex>`, calls became ambiguous. Added explicit
  >   `(Relation<?>)` casts in `CreateEdgesStep`, `FetchEdgesFromToVerticesStep`,
  >   `FetchEdgesToVerticesStep`, `ScriptTransformerImpl`.
  > - Updated 2 test files (`TransactionTest`, `SelectStatementExecutionTest`) that had
  >   `StatefulEdge` variable types — changed to `Edge`. Remaining test files deferred to Step 2.
  >
  > **Discoveries:**
  > - `ScriptDatabaseWrapper.java`, `SQLUpdateItem.java`, `JSONSerializerJackson.java` required
  >   no changes — they use `var` or call methods returning concrete types.
  > - `VertexEntityImpl.removeLinkFromEdge()` used `instanceof StatefulEdge` to check for
  >   non-lightweight edges; replaced with `!edge.isLightweight()` + `edge.asEntity().getIdentity()`.
  > - `Element` interface was an empty marker; removing it from Edge's extends list had no impact.
  >
  > **Files changed (20):** 18 production, 2 test. See commit for full diff.

- [x] Step 2: Replace StatefulEdge type references in test code
  > **Goal:** Remove all `StatefulEdge` usage from test files so step 3 can delete the interface.
  >
  > **Commit:** `YTDB-605: Remove StatefulEdge type references from test code`
  >
  > **Episode:**
  > - Searched all 17 test files listed in the plan. Only 3 files had actual `StatefulEdge`
  >   *type* references (imports, casts, type parameters, `.asStatefulEdge()` calls). The
  >   remaining 14 files only contained `newStatefulEdge()` / `addStateFulEdge()` method calls
  >   using `var` — no type change needed since Step 1 already changed return types to `Edge`.
  > - `SQLUpdateEdgeTest.java`: Replaced `import StatefulEdge` with `import Edge`, changed
  >   2 `(StatefulEdge)` casts to `(Edge)`.
  > - `TestGraphElementDelete.java`: Replaced `import StatefulEdge` with `import Edge`, changed
  >   `tx.<StatefulEdge>load(edge)` to `tx.<Edge>load(edge)`.
  > - `DbImportExportTest.java`: Removed 2 `.asStatefulEdge()` calls — `getEdges()` already
  >   returns `Iterable<Edge>`, and `Edge` now extends `Entity` so `getString()`, `getFrom()`,
  >   `getTo()` are all accessible directly.
  > - `GremlinResultMapperTest.java`: The grep match was for `YTDBStatefulEdgeImpl` (a Gremlin
  >   implementation class), not the `StatefulEdge` interface — no change needed.
  >
  > **Discoveries:**
  > - The plan estimated ~90 occurrences across 17 files, but most were `newStatefulEdge()`
  >   method calls assigned to `var` — Step 1 already changed return types to `Edge`, so
  >   `var` infers the correct type. Only 6 actual type references needed changing.
  > - `tests` module required `core` to be installed first (`mvn install`) for compilation
  >   to succeed, due to inter-module dependency resolution.
  >
  > **Compilation check:** All code compiles. No `StatefulEdge` type references remain in
  > test code. Remaining `StatefulEdge` references are only in: `StatefulEdge.java` itself,
  > `Edge.java` (asStatefulEdge declarations), `StatefullEdgeEntityImpl.java` (implements
  > clause + asStatefulEdge methods), and `newStatefulEdge()`/`addStateFulEdge()` method
  > names (renamed in Track 3).
  >
  > **Files changed (3):** `SQLUpdateEdgeTest.java`, `TestGraphElementDelete.java`,
  > `DbImportExportTest.java`.

- [x] Step 3: Delete StatefulEdge interface and remove asStatefulEdge methods from Edge
  > **Goal:** Delete `StatefulEdge.java` and clean up the last references.
  >
  > **Commit:** `YTDB-605: Delete StatefulEdge interface and remove asStatefulEdge methods`
  >
  > **Episode:**
  > - Deleted `StatefulEdge.java` (`core/.../db/record/record/StatefulEdge.java`).
  > - `Edge.java`: Removed `asStatefulEdge()` and `asStatefulEdgeOrNull()` method declarations
  >   (12 lines of Javadoc + signatures).
  > - `StatefullEdgeEntityImpl.java`: Removed `implements StatefulEdge` from class declaration
  >   (now `implements EdgeInternal` only). Removed `StatefulEdge` import. Removed
  >   `asStatefulEdge()` and `asStatefulEdgeOrNull()` method implementations.
  > - `YTDBStatefulEdge.java` (API interface in `api/gremlin/embedded/`): Kept alive — it
  >   extends `YTDBEdge` and does not reference the deleted `StatefulEdge` interface. Track 3
  >   will handle its rename/deletion.
  > - `YTDBGremlinPlugin.java`: No change needed — it imports `YTDBStatefulEdge` (the Gremlin
  >   API interface), not the deleted `StatefulEdge`.
  > - `YTDBStatefulEdgeImpl.java`: No change needed — it already uses `Edge` (from Step 1)
  >   and does not import `StatefulEdge`.
  >
  > **Discoveries:**
  > - After Steps 1 and 2, only 3 files still referenced `StatefulEdge`: the interface itself,
  >   `Edge.java` (method declarations), and `StatefullEdgeEntityImpl.java` (implements clause
  >   + method implementations). No other files needed changes.
  > - Grep confirmed zero remaining `import.*StatefulEdge` or `asStatefulEdge` references
  >   after the changes.
  >
  > **Compilation check:** Core module compiles. Tests module compiles. No `StatefulEdge`
  > type references remain anywhere. `YTDBStatefulEdge` (Gremlin API) is untouched.
  >
  > **Files changed (3):** `StatefulEdge.java` (deleted), `Edge.java`, `StatefullEdgeEntityImpl.java`.

- [x] Step 4: Remove isLightweight() from Edge and Relation interfaces and all call sites
  > **Goal:** Remove the `isLightweight()` method from `Edge` and `Relation` interfaces and
  > all call sites. This compiles independently because `Relation` still exists at this point.
  >
  > **Commit:** `YTDB-605: Remove isLightweight() from Edge and Relation interfaces and all call sites`
  >
  > **Episode:**
  > - Removed `isLightweight()` from `Edge.java` (8 lines: Javadoc + `@Override` + signature),
  >   `Relation.java` (1 line), `StatefullEdgeEntityImpl.java` (4 lines),
  >   `LightweightRelationImpl.java` (4 lines + `isLightweight()` check in `equals()`).
  > - Removed `isLightweight()` from `RidPair.java` record.
  > - `ResultInternal.java`: Removed `isLightweight()` from `toMapValue()` (Edge branch now
  >   always yields identity). In `Relation<?>` constructor and `convertPropertyValue()`, replaced
  >   `isLightweight()` with `instanceof Entity` check — `LightweightRelationImpl` does not
  >   implement `Entity`, so this correctly distinguishes record-based edges from lightweight
  >   relations without calling the removed method.
  > - `VertexEntityImpl.java`: Simplified `removeLinkFromEdge()` — always uses
  >   `edge.asEntity().getIdentity()` for edge ID. Removed dead `edgeId == null` / lightweight
  >   fallback branch. Removed `edge.isLightweight()` from `removeEdgeLinkFromProperty()`.
  >   Cleaned up unused `edge` and `direction` parameters from internal helper methods.
  > - `YTDBVertexImpl.java`: Removed `!e.isLightweight()` from edge filter.
  > - `SQLUpdateItem.java`: Removed `isLightweight()` check and dead
  >   `newLightweightEdge()`/`setRelation()` code path.
  > - `SQLFunctionShortestPath.java`: Both `walkLeft()` and `walkRight()` — replaced
  >   `if (!edge.isLightweight()) ... else null` with direct `((Identifiable) edge).getIdentity()`.
  > - `SQLFunctionAstar.java`: Both `getDistance()` overloads — simplified `e != null && !e.isLightweight()`
  >   to just `e != null`.
  > - `CreateEdgesStep.java`: Always returns `UpdatableResult`. Removed unused `Relation` import.
  > - `FetchEdgesFromToVerticesStep.java`, `FetchEdgesToVerticesStep.java`: `matchesCollection()`
  >   now always gets collection ID from the edge identity (no lightweight fallback).
  > - Test files: Replaced `isLightweight()` assertions with equivalent checks
  >   (`assertNotEquals(primaryRid, secondaryRid)` for `RidPair`, removed `.isLightweight()` calls
  >   from `DbImportExportTest`).
  >
  > **Discoveries:**
  > - `LightweightRelationImpl` is still instantiated at runtime (e.g., by MATCH traversers).
  >   The plan assumed all edges are record-based so `asEntity()` would always work, but
  >   `LightweightRelationImpl.asEntity()` throws `IllegalStateException`. Used `instanceof Entity`
  >   instead of the removed `isLightweight()` to maintain correct behavior until Step 5 deletes
  >   the Relation hierarchy entirely.
  > - Error-prone's `UnusedVariable` check caught cascading unused parameters in
  >   `VertexEntityImpl` after the lightweight code paths were removed — cleaned up `edge` and
  >   `direction` parameters from two internal helper methods.
  > - `YTDBQueryMetricsStep.java` has its own `isLightweight` field about query monitoring mode
  >   (`QueryMonitoringMode.LIGHTWEIGHT`) — completely unrelated to edge lightweight, not touched.
  >
  > **Files changed (17):** 14 production, 3 test.
  > Production: `Edge.java`, `Relation.java`, `StatefullEdgeEntityImpl.java`,
  > `LightweightRelationImpl.java`, `RidPair.java`, `ResultInternal.java`, `VertexEntityImpl.java`,
  > `YTDBVertexImpl.java`, `SQLUpdateItem.java`, `SQLFunctionShortestPath.java`,
  > `SQLFunctionAstar.java`, `CreateEdgesStep.java`, `FetchEdgesFromToVerticesStep.java`,
  > `FetchEdgesToVerticesStep.java`.
  > Test: `DoubleSidedEdgeLinkBagTest.java`, `VertexFromLinkBagIteratorTest.java`,
  > `DbImportExportTest.java`.

- [x] Step 5: Atomic deletion of Relation hierarchy, Element, EdgeInternal, and dependent types
  > **Goal:** Delete the entire `Relation` type hierarchy and all dependent types in a single
  > atomic commit.
  >
  > **Commit:** `YTDB-605: Delete Relation type hierarchy and all dependent code`
  >
  > **Episode:**
  > - Deleted 8 source files: `Element.java`, `Relation.java`, `EdgeInternal.java`,
  >   `LightweightRelationImpl.java`, `RelationsIteratorAbstract.java`, `RelationsIterable.java`,
  >   `EntityRelationsIterator.java`, `EntityRelationsIterable.java`.
  > - `Edge.java`: Removed `extends Element, Relation<Vertex>` (now `extends Entity` only).
  >   Removed `@Override` from `getFrom()`, `getTo()`, `isLabeled()`. Added `label()` and
  >   `getEntity(Direction)` default methods (from `Relation`). Moved static helpers from
  >   `EdgeInternal`: `checkPropertyName()`, `isEdgeConnectionProperty()`,
  >   `isInEdgeConnectionProperty()`, `isOutEdgeConnectionProperty()`, `filterPropertyNames()`.
  > - `DBRecord.java`: Removed `extends Element`. Now `DBRecord extends Identifiable` only.
  > - `Result.java`, `Entity.java`: Removed `isRelation()`, `asRelation()`, `asRelationOrNull()`.
  > - `ResultInternal.java`: Removed `relation` field, `Relation<?>` constructor, `setRelation()`,
  >   `isRelation()`, `asRelation()`, `asRelationOrNull()`. Cleaned up `toMap()`, `toJSON()`,
  >   `toString()`, `equals()`, `hashCode()`, `toResultInternal()`, `convertPropertyValue()`.
  > - `UpdatableResult.java`: Removed `isRelation()`, `asRelation()`, `asRelationOrNull()`.
  > - `StatefullEdgeEntityImpl.java`: Changed `implements EdgeInternal` to `implements Edge`.
  > - `BidirectionalLinksIterable`: Reparameterized from `Relation<T>` to `Edge` (non-generic).
  > - `BidirectionalLinkToEntityIterator`: Reparameterized from `Relation<T>` to `Edge`.
  > - `EdgeIterator`: Inlined from `RelationsIteratorAbstract` — standalone `Iterator<Edge>`
  >   with `Resettable`, `Sizeable`. Added `matchesLabels()` helper for null-safe label check.
  >   Removed unused `connection` field.
  > - `EdgeIterable`: Inlined from `RelationsIterable` — standalone `Iterable<Edge>` with `Sizeable`.
  > - `MultiCollectionIterator`: Replaced `instanceof RelationsIteratorAbstract` with
  >   `instanceof EdgeIterator`.
  > - `VertexEntityImpl`: Changed `Iterable<EdgeInternal>` to `Iterable<Edge>` in
  >   `getEdgesInternal()`. Removed `getBidirectionalLinksInternal()` override. Removed diamond
  >   operator from `BidirectionalLinksIterable` calls.
  > - `DatabaseSessionEmbedded`: Changed `EdgeInternal` return types to `Edge` in
  >   `addEdgeInternal()` and `newLightweightEdgeInternal()`.
  > - `EntityImpl`: Deleted `getEntities()`, `getBidirectionalLinks()`,
  >   `getBidirectionalLinksInternal()` methods.
  > - `SQLFunctionMove`: Removed `instanceof Relation<?>` from `execute()`. `v2v()` now returns
  >   `null` for non-vertex/non-edge entities (link-based traversal no longer supported).
  > - `SQLFunctionMoveFiltered`: Removed `instanceof Relation<?>` check.
  > - `SQLEngine.foreachRecord()`: Removed `isRelation()` / `asRelation()` branch.
  > - All 12 graph SQL functions (`Out`, `In`, `Both`, `OutE`, `InE`, `BothE`, `OutV`, `InV`,
  >   `BothV`, `ShortestPath`): Removed Relation-specific overloads and imports.
  > - MATCH traversers: Removed `Relation<?>` branches from `MatchEdgeTraverser`,
  >   `MatchMultiEdgeTraverser`, `OptionalMatchEdgeTraverser`.
  > - `MatchStepUnitTest`: Removed `isRelation()`, `asRelation()`, `asRelationOrNull()` stubs.
  >   Updated Javadoc "Relation" → "Edge".
  > - Deleted `LinkBasedMatchStatementExecutionTest.java` and
  >   `LinkBasedMatchStatementExecutionNewTest.java` — these tested MATCH traversal over
  >   plain entities via the `Relation` abstraction, which is no longer supported.
  >
  > **Discoveries:**
  > - `EdgeIterator.createEdge()` called `edge.isLabeled(labels)` where labels could be `null`
  >   (passed from `SQLFunctionMove` when no labels specified). Added `matchesLabels()` helper
  >   that returns `true` when labels is null or empty.
  > - Error-prone flagged unused `connection` field in `EdgeIterator` after inlining — removed it.
  > - `BidirectionalLinksIterable` callers in `VertexEntityImpl` and `SQLFunctionShortestPath`
  >   used `<>` diamond operator from when the class was generic — removed after making it
  >   non-generic.
  > - Plain-entity link traversal via `out()`/`in()`/`both()` in MATCH (e.g., `.out('triangle')`
  >   on a non-vertex class with link properties) is no longer supported. The original code used
  >   `getEntities()` which returned `BidirectionalLinksIterable` wrapping `LightweightRelationImpl`
  >   objects. With the Relation hierarchy deleted, `v2v()` returns `null` for non-vertex entities.
  >   All 98 `LinkBasedMatchStatementExecution*` test failures were expected and the test classes
  >   were deleted.
  >
  > **Files changed (48):** 8 deleted source files, 2 deleted test files, 36 modified production
  > files, 1 modified test file, 1 new file (track-2-technical.md review).
  > **Test results:** 7619 tests run, 0 failures, 491 skipped.
