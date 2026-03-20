# Track 7: Verification, API cleanup, and documentation

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [ ] Track-level code review

## Base commit
`f5040b2930`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Delete YTDBStatefulEdge and unify Gremlin edge API
  > **What was done:** Moved `RID id()` covariant return type from
  > `YTDBStatefulEdge` to `YTDBEdge`. Changed `YTDBEdgeImpl` to implement
  > `YTDBEdge` directly instead of `YTDBStatefulEdge`. Removed
  > `YTDBStatefulEdge.class` from `YTDBGremlinPlugin` class imports. Deleted
  > `YTDBStatefulEdge.java`. Cucumber feature tests pass.
  >
  > **Key files:** `YTDBEdge.java` (modified), `YTDBEdgeImpl.java` (modified),
  > `YTDBGremlinPlugin.java` (modified), `YTDBStatefulEdge.java` (deleted).

- [x] Step: Clean up stale edge naming in tests
  > **What was done:** Renamed 7 test methods in `SelectStatementExecutionTest`
  > that contained "StateFull" (e.g., `testOutEStateFullEdgesIndexUsageInGraph`
  > → `testOutEEdgesIndexUsageInGraph`). Updated ~11 "lightweight" comments in
  > `LinkBagIndexTest` to use "single-RID" terminology. Purely cosmetic — no
  > logic changes.
  >
  > **Key files:** `SelectStatementExecutionTest.java` (modified),
  > `LinkBagIndexTest.java` (modified).

- [x] Step: Full verification run
  > **What was done:** Ran full test suites across all three modules:
  > `core` (BUILD SUCCESS, includes ~1900 Cucumber scenarios),
  > `embedded` (BUILD SUCCESS, 1899 tests), `tests` (1300 tests, 1 error).
  > The single error (`unionAllInlineEdges` in `SQLCombinationFunctionTests`)
  > is a pre-existing failure — reproduces on the base commit before any
  > Track 7 changes. No regressions introduced by unified edges.
  >
  > **What was discovered:** `SQLCombinationFunctionTests.unionAllInlineEdges`
  > fails with `UnsupportedOperationException: Reset is not supported` from
  > `EdgeIterator.reset()`. This is pre-existing — likely introduced during
  > earlier edge iterator refactoring (Track 3 inlined
  > `RelationsIteratorAbstract` into `EdgeIterator`). Not in scope for this
  > track.
  >
  > **Key files:** No files modified — verification-only step.

## Notes
- `LightWeightEdgesTest` — already deleted in prior tracks (T1)
- `DoubleSidedEdgeLinkBagTest` — already fully updated, no changes needed (T4)
- DSL regeneration — happens automatically at compile time, not a separate step (T2)
