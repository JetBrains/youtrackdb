# Track 4: Unify edge implementations and creation API

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (2/3 iterations)

## Base commit
`d163c394c7`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Rename `StatefullEdgeEntityImpl` to `EdgeEntityImpl` and `YTDBStatefulEdgeImpl` to `YTDBEdgeImpl`
  > **What was done:** Renamed `StatefullEdgeEntityImpl` → `EdgeEntityImpl` (file +
  > class + all 6 referencing files), `YTDBStatefulEdgeImpl` → `YTDBEdgeImpl`
  > (file + class + all 7 referencing files), and `newStatefulEdgeInternal()` →
  > `newEdgeInternal()` in `DatabaseSessionEmbedded` + `JSONSerializerJackson`.
  > Updated error message and local variable name (`statefulEdge` → `edgeEntity`)
  > in `addEdgeInternal()`. Review fix: renamed record type label from
  > `"statefulEdge"` to `"edge"` in `RecordFactoryManager`.
  >
  > **Key files:** `EdgeEntityImpl.java` (renamed), `YTDBEdgeImpl.java` (renamed),
  > `DatabaseSessionEmbedded.java` (modified), `RecordFactoryManager.java` (modified),
  > `EntityHelper.java` (modified), `JSONSerializerJackson.java` (modified),
  > `SQLRecordAttribute.java` (modified), `YTDBGraphStep.java` (modified),
  > `GremlinResultMapper.java` (modified), `YTDBVertexImpl.java` (modified),
  > `YTDBPropertyImpl.java` (modified), `YTDBGraphImplAbstract.java` (modified),
  > `GremlinResultMapperTest.java` (modified), `YTDBGraphProvider.java` (modified)

- [x] Step 2: Unify Vertex edge creation API — collapse `addStateFulEdge()` and `addLightWeightEdge()` into `addEdge()`
  > **What was done:** Collapsed 5 methods (`addStateFulEdge` x3, `addLightWeightEdge` x2)
  > into 3 `addEdge()` overloads on the `Vertex` interface. Deleted all implementations
  > in `VertexEntityImpl`. Deleted 4-param `createLink()` overload (lightweight-only,
  > zero callers). Updated Gremlin layer caller in `YTDBVertexImpl` and all test callers
  > across 10 test files. Updated 5-param `createLink()` Javadoc to remove lightweight
  > references.
  >
  > **Key files:** `Vertex.java` (modified), `VertexEntityImpl.java` (modified),
  > `YTDBVertexImpl.java` (modified), `DoubleSidedEdgeLinkBagTest.java` (modified),
  > `TestGraphElementDelete.java` (modified), `TestGraphOperations.java` (modified),
  > `SelectStatementExecutionTest.java` (modified), `MatchStatementExecutionNewTest.java`
  > (modified), `TransactionRidAllocationTest.java` (modified),
  > `GraphRecoveringTest.java` (modified), `MatchPlanCacheBenchmark.java` (modified),
  > `VertexTraversalBenchmark.java` (modified), `DbImportExportTest.java` (modified)

- [x] Step 3: Unify DatabaseSession edge creation — remove `newLightweightEdge()`, rename `newStatefulEdge()` to `newEdge()`
  > **What was done:** Renamed `newStatefulEdge()` → `newEdge()` (3 overloads) across
  > `Transaction`, `DatabaseSessionEmbedded`, `FrontendTransactionImpl`,
  > `FrontendTransactionNoTx`. Deleted `newLightweightEdge()` (2 overloads) from all
  > layers. Deleted dead `newLightweightEdgeInternal()` tombstone method (review fix).
  > Updated `ScriptDatabaseWrapper`, `SQLUpdateItem`, `VertexEntityImpl`, and all
  > callers across 13 test files. Updated error messages to reference `newEdge()`.
  >
  > **Key files:** `Transaction.java` (modified), `DatabaseSessionEmbedded.java` (modified),
  > `FrontendTransactionImpl.java` (modified), `FrontendTransactionNoTx.java` (modified),
  > `ScriptDatabaseWrapper.java` (modified), `SQLUpdateItem.java` (modified),
  > `VertexEntityImpl.java` (modified), plus 13 test files across core and tests modules

- [x] Step 4: RidPair legacy guard — add `validateEdgePair()`, delete `ofSingle()` and `isLightweight()`
  > **What was done:** Deleted `RidPair.ofSingle()` and `RidPair.isLightweight()`.
  > Added `validateEdgePair()` method that throws `IllegalStateException` when
  > `primaryRid == secondaryRid`. Replaced `testOfSingleCreatesLightweightPair`
  > with `testValidateEdgePairRejectsEqualRids`. Replaced all 10
  > `isLightweight()` assertions in `DoubleSidedEdgeLinkBagTest` with
  > `assertNotEquals(pair.primaryRid(), pair.secondaryRid())`.
  >
  > **What changed from the plan:** The plan specified a compact constructor
  > guard, but `RidPair` is used for both edge and non-edge LinkBag entries —
  > non-edge entries legitimately have `primaryRid == secondaryRid`. Moved the
  > guard to an explicit `validateEdgePair()` method instead. `EdgeIterator`
  > already has an equivalent runtime guard (checks if loaded entity is a
  > vertex instead of an edge record).
  >
  > **Key files:** `RidPair.java` (modified), `VertexFromLinkBagIteratorTest.java`
  > (modified), `DoubleSidedEdgeLinkBagTest.java` (modified)
