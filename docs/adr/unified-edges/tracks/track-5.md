# Track 5: Unify schema and edge lifecycle

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
*(set at Phase B start)*

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

- [ ] Step 1: Remove `createLightweightEdgeClass()` from Schema API
  > Delete `createLightweightEdgeClass()` from `Schema.java` interface (default
  > method). Search for and update/remove all callers across the codebase.
  > Update `createEdgeClass()` Javadoc to remove the "non-abstract" qualifier
  > (only meaningful in contrast with the removed method). Rename test files
  > `LightWeightEdgesTest` -> `EdgeTest` and `CreateLightWeightEdgesSQLTest`
  > -> `CreateEdgesSQLTest` since they already use `createEdgeClass()` and
  > their names are misleading.
  >
  > **Target files:** `Schema.java`, test callers (if any),
  > `LightWeightEdgesTest.java` (rename), `CreateLightWeightEdgesSQLTest.java`
  > (rename)

- [ ] Step 2: Delete `EdgeType` enum, simplify `getAllPossibleEdgePropertyNames()` and SQL navigation
  > Delete `VertexEntityImpl.EdgeType` enum (LIGHTWEIGHT, STATEFUL, BOTH).
  > Simplify `getAllPossibleEdgePropertyNames()` to remove the
  > `isAbstract()`-based filtering — after unification, all edge classes
  > (abstract or concrete) should be included. Update
  > `SQLGraphNavigationFunction.propertiesForV2ENavigation()` and
  > `propertiesForV2VNavigation()` to call the simplified method without
  > `EdgeType`. Remove stale lightweight comments from
  > `SQLGraphNavigationFunction`. This addresses the blocker (T5-2/R7):
  > the `LIGHTWEIGHT` filter would return empty results after
  > `createLightweightEdgeClass()` removal.
  >
  > **Target files:** `VertexEntityImpl.java` (modified),
  > `SQLGraphNavigationFunction.java` (modified)

- [ ] Step 3: Disable `OPTIMIZE DATABASE LWEDGES` command
  > Replace the body of `SQLOptimizeDatabaseStatement.optimizeEdges()` with
  > a throw of `UnsupportedOperationException` explaining that lightweight
  > edge conversion is no longer supported after edge unification. Keep the
  > method and SQL parsing path intact (no grammar changes) — only the runtime
  > behavior changes. Update or add a test verifying the exception is thrown.
  >
  > **Target files:** `SQLOptimizeDatabaseStatement.java` (modified),
  > test file for the exception (new or modified)

- [ ] Step 4: Fix `createLink()` fallback path and clean up `GraphRepair` lightweight references
  > Fix `VertexEntityImpl.createLink()` at line 682: the single-arg
  > `bag.add(foundId.getIdentity())` creates a `RidPair(rid, rid)` —
  > invalid for edge LinkBags. Determine the correct secondary RID for
  > the pre-existing entry (load the edge record to find the opposite
  > vertex, or if the entry is a vertex RID, treat it as corruption).
  > Clean up `GraphRepair.repairEdge()` lightweight repair path (line
  > 545-546) — replace with corruption detection since lightweight edges
  > no longer exist. Update stale "lightweight" comments in
  > `PropertyLinkBagIndexDefinition`, `VertexFromLinkBagIterator`, and
  > `VertexEntityImpl`.
  >
  > **Target files:** `VertexEntityImpl.java` (modified),
  > `GraphRepair.java` (modified), `PropertyLinkBagIndexDefinition.java`
  > (comments), `VertexFromLinkBagIterator.java` (comments)
