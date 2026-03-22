# Track 5: Unify schema and edge lifecycle

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [x] Track-level code review

## Base commit
`cb87cf28c1`

## Reviews completed
- [x] Technical
- [x] Risk

## Review decisions

The technical and risk reviews identified that the plan's two primary targets
(EdgeIterator iteration branching, edge deletion simplification) were already
handled by Tracks 1-3. The track scope has been redirected to the actual
remaining cleanup targets identified by the reviews:

- **T5-2/R7 (blocker)**: `VertexEntityImpl.EdgeType` enum and
  `SQLGraphNavigationFunction` — the `LIGHTWEIGHT` filter in
  `propertiesForV2VNavigation()` will return empty results once abstract
  edge classes are gone, breaking V2V SQL traversal.
- **T5-1/R2**: No `setAbstract()` enforcement exists — removed from scope.
- **T5-3/R1**: No lightweight-specific deletion paths exist — removed from scope.
- **T5-4/R4**: `OPTIMIZE DATABASE LWEDGES` creates forbidden state — must disable.
- **T5-5**: `VertexEntityImpl.createLink()` single-arg `bag.add()` creates
  invalid `primaryRid == secondaryRid` pairs in edge LinkBags.
- **T5-6**: `GraphRepair.java` lightweight edge repair path is stale.

## Steps

- [x] Step 1: Remove `createLightweightEdgeClass()` from Schema API
  > **What was done:** Deleted `createLightweightEdgeClass()` from `Schema.java`
  > (default method) and `DatabaseSessionEmbedded.java`. Updated `createEdgeClass()`
  > Javadoc. Renamed `LightWeightEdgesTest` → `EdgeTest` and
  > `CreateLightWeightEdgesSQLTest` → `CreateEdgesSQLTest` (class names updated).
  > Code review renamed `testSimpleLightWeight()` → `testSimpleEdge()`.
  >
  > **Key files:** `Schema.java` (modified), `DatabaseSessionEmbedded.java`
  > (modified), `EdgeTest.java` (renamed), `CreateEdgesSQLTest.java` (renamed)

- [x] Step 2: Delete `EdgeType` enum, simplify `getAllPossibleEdgePropertyNames()` and SQL navigation
  > **What was done:** Deleted `VertexEntityImpl.EdgeType` enum and removed
  > `EdgeType` parameter from `getAllPossibleEdgePropertyNames()`. Removed
  > `isAbstract()`-based filtering — all edge classes included unconditionally.
  > Updated both `propertiesForV2ENavigation()` and `propertiesForV2VNavigation()`
  > in `SQLGraphNavigationFunction`. V2V for vertex entities now returns edge
  > property names (same as V2E) since `RidPair.secondaryRid` provides the
  > opposite vertex. Updated stale Javadoc on `propertyNamesForIndexCandidates`.
  >
  > **Key files:** `VertexEntityImpl.java` (modified),
  > `SQLGraphNavigationFunction.java` (modified)

- [x] Step 3: Disable `OPTIMIZE DATABASE LWEDGES` command
  > **What was done:** Replaced `optimizeEdges()` body with
  > `UnsupportedOperationException` throw. Removed unused imports and dead
  > `verbose()` method. Fixed pre-existing `"optimize databae"` typo. Updated
  > test to verify the exception is thrown.
  >
  > **Key files:** `SQLOptimizeDatabaseStatement.java` (modified),
  > `OptimizeDatabaseExecutionTest.java` (modified)

- [x] Step 4: Fix `createLink()` fallback path and clean up `GraphRepair` lightweight references
  > **What was done:** Fixed `VertexEntityImpl.createLink()` single-Identifiable
  > to LinkBag conversion: added `resolveSecondaryRid()` helper that loads the
  > pre-existing edge record and reads the opposite vertex from the `in`/`out`
  > property based on the field name prefix. Simplified `GraphRepair.isEdgeBroken()`
  > vertex branch to mark vertex RIDs in edge LinkBags as corruption. Updated
  > stale lightweight comments in `PropertyLinkBagIndexDefinition` and
  > `VertexFromLinkBagIterator`. Code review replaced assert with explicit
  > `IllegalStateException` null check in `resolveSecondaryRid()`.
  >
  > **Key files:** `VertexEntityImpl.java` (modified), `GraphRepair.java`
  > (modified), `PropertyLinkBagIndexDefinition.java` (modified),
  > `VertexFromLinkBagIterator.java` (modified)
