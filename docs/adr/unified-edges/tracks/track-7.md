# Track 7: Verification, API cleanup, and documentation

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/3 complete)
- [ ] Track-level code review

## Base commit
`f5040b2930`

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Delete YTDBStatefulEdge and unify Gremlin edge API
  Move `RID id()` covariant return type from `YTDBStatefulEdge` to `YTDBEdge`.
  Remove `implements YTDBStatefulEdge` from `YTDBEdgeImpl`. Remove
  `YTDBStatefulEdge.class` from `YTDBGremlinPlugin` class imports. Delete
  `YTDBStatefulEdge.java`. Run core tests to verify Gremlin integration.
  Files: `YTDBEdge.java`, `YTDBEdgeImpl.java`, `YTDBGremlinPlugin.java`,
  `YTDBStatefulEdge.java` (deleted).

- [ ] Step: Clean up stale edge naming in tests
  Rename 7 test methods in `SelectStatementExecutionTest` that contain
  "StateFull" (e.g., `testOutEStateFullEdgesIndexUsageInGraph` →
  `testOutEEdgesIndexUsageInGraph`). Update ~11 "lightweight entry" comments
  in `LinkBagIndexTest` to use "single-RID entry" terminology. These are
  cosmetic changes — no logic changes.
  Files: `SelectStatementExecutionTest.java`, `LinkBagIndexTest.java`.

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
