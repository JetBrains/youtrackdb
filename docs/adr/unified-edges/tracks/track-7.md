# Track 7: Verification, API cleanup, and documentation

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/3 complete)
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

- [ ] Step: Full verification run
  Run `./mvnw -pl core clean test` (includes ~1900 Cucumber scenarios via
  `YTDBGraphFeatureTest`), `./mvnw -pl embedded clean test` (Cucumber via
  `EmbeddedGraphFeatureTest`), and `./mvnw -pl tests clean test` (integration
  test suite). Fix any failures found. This is the final verification that
  unified edges work end-to-end across all test suites.

## Notes
- `LightWeightEdgesTest` — already deleted in prior tracks (T1)
- `DoubleSidedEdgeLinkBagTest` — already fully updated, no changes needed (T4)
- DSL regeneration — happens automatically at compile time, not a separate step (T2)
