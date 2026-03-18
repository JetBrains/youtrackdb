# Track 1: Migrate call sites to unified edge API

## Steps

- [x] Step: Migrate call sites from isStatefulEdge/asStatefulEdge/isStateful to isEdge/asEdge
  > **What was done:** Gave `EntityImpl.isEdge()` and `ResultInternal.isEdge()` standalone
  > implementations (no longer delegating to `isStatefulEdge()`). Migrated ~25 call sites
  > across 21 files: `isStatefulEdge()` → `isEdge()`, `asStatefulEdge()` → `asEdge()` or
  > direct casts to `StatefulEdge`/`Entity`/`Identifiable`. Updated Gremlin layer
  > (`YTDBVertexImpl`, `YTDBGraphImplAbstract`, `YTDBGraphStep`, `GremlinResultMapper`,
  > `YTDBElementImpl`, `YTDBPropertyImpl`), SQL functions (`SQLFunctionAstar`,
  > `SQLFunctionShortestPath`), SQL executors (`CreateEdgesStep`, `CastToEdgeStep`,
  > `FetchEdgesToVerticesStep`, `FetchEdgesFromToVerticesStep`, `SQLUpdateItem`),
  > `GraphRepair`, and `ResultInternal`. Old methods kept compilable.
  >
  > **Key files:** `EntityImpl.java` (modified), `ResultInternal.java` (modified),
  > `EmbeddedEntityImpl.java` (modified), `UpdatableResult.java` (modified),
  > `YTDBVertexImpl.java` (modified), `YTDBGraphImplAbstract.java` (modified),
  > `YTDBGraphStep.java` (modified), `GremlinResultMapper.java` (modified),
  > `YTDBElementImpl.java` (modified), `YTDBPropertyImpl.java` (modified),
  > `SQLFunctionAstar.java` (modified), `SQLFunctionShortestPath.java` (modified),
  > `GraphRepair.java` (modified), `CreateEdgesStep.java` (modified),
  > `CastToEdgeStep.java` (modified), `FetchEdgesToVerticesStep.java` (modified),
  > `FetchEdgesFromToVerticesStep.java` (modified), `SQLUpdateItem.java` (modified)

- [x] Step: Delete EdgeImpl lightweight edge implementation
  > **What was done:** Deleted `EdgeImpl.java`. In `EdgeIterator`, replaced the
  > `entity.isVertex()` branch (which created EdgeImpl for lightweight edges) with
  > `IllegalStateException("Legacy lightweight edge encountered")`. In
  > `DatabaseSessionEmbedded`, made `newLightweightEdgeInternal()` throw
  > `UnsupportedOperationException("Lightweight edges are no longer supported")`.
  > Deleted `CreateLightWeightEdgesSQLTest.java` entirely. Cleaned up 4 other test
  > files: removed lightweight-only tests, converted mixed tests to use stateful edges.
  >
  > **What was discovered:** EdgeImpl had more callers than the 2 direct construction
  > sites (EdgeIterator, DatabaseSessionEmbedded). The `Vertex.addLightWeightEdge()` API
  > calls through `DatabaseSessionEmbedded.newLightweightEdge()` →
  > `addEdgeInternal(from, to, type, false)` → `newLightweightEdgeInternal()`. This caused
  > 14 test failures beyond the initial source changes. All were in tests that used the
  > lightweight edge creation API. The `newLightweightEdge()` method still exists but now
  > fails at `newLightweightEdgeInternal()` — Track 3 should clean up that entire code path.
  >
  > **What changed from the plan:** Originally planned as a simple file deletion. Required
  > additional changes to EdgeIterator and DatabaseSessionEmbedded, plus deletion/modification
  > of 5 test files. Track 3's scope for `DatabaseSessionEmbedded` cleanup is slightly reduced
  > since `newLightweightEdgeInternal()` already throws.
  >
  > **Key files:** `EdgeImpl.java` (deleted), `EdgeIterator.java` (modified),
  > `DatabaseSessionEmbedded.java` (modified), `CreateLightWeightEdgesSQLTest.java` (deleted),
  > `LightWeightEdgesTest.java` (modified), `DoubleSidedEdgeLinkBagTest.java` (modified),
  > `UniqueIndexTest.java` (modified), `EntityTransactionalValidationTest.java` (modified)

- [x] Step: Remove dead isStatefulEdge/asStatefulEdge/isStateful methods and fix ResultSet typos
  > **What was done:** Removed `isStatefulEdge()`, `asStatefulEdge()`,
  > `asStatefulEdgeOrNull()` from `DBRecord`, `Entity`, `EntityImpl`,
  > `EmbeddedEntityImpl`, `RecordBytes`, `Result`, `ResultInternal`, `UpdatableResult`.
  > Removed `Edge.isStateful()`. Deleted misspelled `ResultSet` duplicates:
  > `findFirstStateFullEdge()`, `findFirstSateFullEdgeOrNull()`, `statefulEdgeStream()`,
  > `forEachStatefulEdge()`, `toStatefulEdgeList()`. Fixed `edgeStream()` to call
  > `asEdge()` instead of `asStatefulEdge()`. Added `asStatefulEdge()`/
  > `asStatefulEdgeOrNull()` implementations to `StatefullEdgeEntityImpl` (required
  > because `Edge` interface retains these methods for Track 2). Updated 6 test files.
  > Net -271 lines.
  >
  > **What was discovered:** `Edge` interface must retain `asStatefulEdge()`/
  > `asStatefulEdgeOrNull()` declarations until Track 2 collapses the type hierarchy.
  > `CreateEdgesStep` retains the `isLightweight()` branch — needed until Track 4.
  > `DbImportExportTest.java` in `tests` module still calls `Edge.asStatefulEdge()` —
  > fine since `Edge` retains it; cleaned up with Track 2's StatefulEdge deletion.
  >
  > **Key files:** `DBRecord.java` (modified), `Entity.java` (modified),
  > `Edge.java` (modified), `Result.java` (modified), `ResultSet.java` (modified),
  > `EntityImpl.java` (modified), `EmbeddedEntityImpl.java` (modified),
  > `RecordBytes.java` (modified), `ResultInternal.java` (modified),
  > `UpdatableResult.java` (modified), `StatefullEdgeEntityImpl.java` (modified)
