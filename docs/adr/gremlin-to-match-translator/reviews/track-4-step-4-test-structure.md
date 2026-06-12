# Track 4, Step 4 — Test Structure Review

Commit: `3c363fe6f0` ("Translate folded HasContainers on the start step")

## Summary

The test changes are well-structured overall: each test class extends `GraphBaseTest`,
which gives every method a fresh per-test database (the `databaseName` field in
`DbTestBase` is regenerated and the database dropped in `@After`), so the test classes
are isolated by construction. The assertions flipped in the strategy and translator
unit tests are correctly load-bearing for the lifted gate, and the equivalence harness
extension reuses the existing `shape(...)` factory and `seedFixture` rather than
introducing a new harness. The primary concerns are (a) one new equivalence case is
*vacuous* against the seeded fixture, (b) one smoke-test assertion is weaker than the
convention used by every other smoke test in the same file, and (c) inline FQN usage
where existing imports already cover the type.

## Findings

### Recommended

#### TS-1 — `V_has_age_gt_30` is vacuously equivalent against the seeded fixture
- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, case at lines 174-177; fixture at `seedFixture` (lines 226-268).
- **Issue**: The seed fixture creates Person/Place vertices with only a `name` property — no vertex carries an `age` property. The native pipeline returns the empty list; the translated pipeline (if it correctly translates `P.gt(30)`) also returns the empty list. The case's load-bearing assertion (`resultSet_translatedMatchesNative`) compares `[] == []` and passes regardless of whether the predicate-adapter wiring is actually correct. A regression that, e.g., translated `P.gt(30)` as `P.lt(30)` or dropped the predicate entirely would still pass this case because there is nothing in the fixture to discriminate against. The boundary-step engagement assertion still proves the case was *recognized*, but the result-equivalence comparison is vacuous.
- **Suggestion**: Either (a) seed at least two Person vertices with `age` property, one above and one below 30, so the multiset comparison actually exercises the predicate translation; or (b) explicitly note in a comment that this case only pins recognition (not predicate semantics) and that semantics are covered elsewhere — and add a non-vacuous case (e.g. `V_has_name_Alice` already does the discriminating work for eq, but no case yet does for `gt`/`lt`/range predicates). Option (a) is preferable because the comment-only path means the harness silently loses signal as the recogniser's predicate coverage grows.

#### TS-2 — Smoke-test boundary-step assertion uses `anyMatch` instead of `getStartStep()`
- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`, method `startWithFoldedHasContainer_translatesToBoundaryStepWithMatchingResult` (line 343).
- **Issue**: The assertion uses `admin.getSteps().stream().anyMatch(s -> s instanceof YTDBMatchPlanStep<?, ?>)`. Every other test in this file (lines 68, 95, 117, 149, 172, 202, 231, 277, 288, 393) asserts `admin.getStartStep() instanceof YTDBMatchPlanStep<?, ?>`. The strategy splices the boundary step at index 0 (verified by the unit test `apply_translationProduced_replacesPrefixWithBoundaryStep` which asserts "boundary lands at index 0"), so `getStartStep()` is the correct, stronger probe — `anyMatch` would also pass for a buggy splice that left the original `YTDBGraphStep` in place at index 0 and inserted the boundary step somewhere later.
- **Suggestion**: Replace with `assertTrue("expected start step to be YTDBMatchPlanStep but was " + admin.getStartStep().getClass(), admin.getStartStep() instanceof YTDBMatchPlanStep<?, ?>)` to match the convention used by every other smoke test in the class.

#### TS-3 — `EdgeTraversalEquivalenceTest` is becoming a kitchen sink (20 cases)
- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`.
- **Issue**: The class now has 20 parameterised cases mixing edge traversals (`out`/`in`/`bothE`/`outE_inV`), folded HasContainers (`has(...)`/`hasLabel(...)`), and aggregation (`out_Knows_count`). The class name still says "EdgeTraversal" but six of the cases are not edge-traversal cases at all. Tracks 5-10 in the plan add HasStep, HasNotStep, HasLabel-mid-chain, HasId, range/order, projection, aggregation, and union recognisers — each will add several cases. The seed fixture is already accreting concerns (Person hierarchy, Place class, Knows/Likes/Follows edges, and an unused-by-this-step `Berlin` vertex used by `V_out_Knows_out_Likes`).
- **Suggestion**: Consider partitioning the harness by recogniser theme as the case count grows (e.g. `EdgeTraversalEquivalenceTest`, `FoldedHasContainerEquivalenceTest`, `AggregationEquivalenceTest`). A shared abstract `EquivalenceHarnessTest` base would keep the `runCollecting`/`withStrategyEnabled`/`stableIdentifier` plumbing in one place, while letting each subclass define a focused fixture and case list. This is not load-bearing for Step 4 — flag for Track 5 onwards as the cardinality grows.

### Minor

#### TS-4 — Inline FQN where the type is already imported
- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`, line 349.
- **Issue**: The new smoke test uses `assertEquals(java.util.List.of("Alice"), names)` while `java.util.List` is imported at line 11 and used unqualified at lines 76, 234, and 400. The inline FQN is inconsistent with the file's own style and serves no disambiguation purpose.
- **Suggestion**: Replace `java.util.List.of("Alice")` with `List.of("Alice")`.

#### TS-5 — Inline FQN for `org.apache.tinkerpop.gremlin.process.traversal.P`
- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/EdgeTraversalEquivalenceTest.java`, line 176.
- **Issue**: The case uses `org.apache.tinkerpop.gremlin.process.traversal.P.gt(30)` inline. There is no name collision (no `P` is imported in the file) and `P` is the canonical TinkerPop predicate factory used throughout the test plan; subsequent steps will add many more `P.eq`/`P.neq`/`P.within` cases.
- **Suggestion**: Add `import org.apache.tinkerpop.gremlin.process.traversal.P;` and use `P.gt(30)` so this and the future predicate cases stay readable. A static import of common factories (`import static org.apache.tinkerpop.gremlin.process.traversal.P.gt;`) is also acceptable but a regular `P` import is the lighter touch.

#### TS-6 — `WalkerContextFixtures` not applicable here, no action required
- **File**: N/A — review point clarification only.
- **Issue**: The diff prompt asked whether `WalkerContextFixtures` (extracted in Step 2) should be used by the new/modified tests. Confirmed: it should not. `WalkerContextFixtures` is a package-private helper for *unit-level* recogniser tests (`StartStepRecogniserTest`, `VertexStepRecogniserTest`, `GremlinStepWalkerTest`) that pre-populate a `WalkerContext` to mimic post-start-step state. None of the tests touched in this commit (`EdgeTraversalEquivalenceTest`, `GremlinToMatchSmokeTest`, `GremlinToMatchStrategyTest`, `GremlinToMatchTranslatorTest`) operate at the recogniser-context level — they all run a real traversal through `applyStrategies` or `translatePrefix`. No change required.
- **Suggestion**: None.

## Notes (no action required)

- **Test isolation:** All four test classes extend `GraphBaseTest`, which extends `DbTestBase`. `DbTestBase` regenerates `databaseName` per test in `@Before` and drops the database in `@After` (line 188 of `DbTestBase.java`); `GraphBaseTest.openGraph()` opens a fresh graph against that name and `closeGraphDB()` closes it. There is no shared mutable state across tests in any of these classes. The kill-switch round-trip in `GremlinToMatchSmokeTest.killSwitch_roundTripOffThenOn` defensively restores the original config in a `finally` block even though the per-test database lifecycle would garbage-collect it anyway.
- **Equivalence harness `restoreKillSwitch`:** The `@After`-style restore of `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` (lines 362-370) is documented as defensive given the per-test database lifecycle. Confirmed safe — there is no path by which a kill-switch flip in one parameterised case bleeds into another.
- **Smoke-test fixture discriminates Alice from Bob:** The Bob vertex (line 331) is intentional and necessary. Without Bob, `g.V().has("name", "Alice")` would return the only existing vertex regardless of whether the predicate filter is correctly translated. Bob is what makes the `assertEquals(List.of("Alice"), names)` assertion load-bearing for the predicate translation. Good test design.
- **Translator unit test naming:** `translateGV_startStepWithFoldedHasLabel_narrowsAliasClass` is descriptive (subject_scenario_outcome) and matches the file's existing convention. The Javadoc explains the load-bearing nature of the `Optional.of(...)` versus prior `Optional.empty()` flip. No issue.
- **Strategy unit test naming:** `apply_proceedsWhenHasContainersPresent` is clearer than the prior `apply_declinesOnNonEmptyHasContainers` — accurately reflects the lifted-gate semantics. Comment at lines 430-432 explains why the fixture-translator's `prefixStepCount=1` makes the splice fire and what regression a re-introduced gate would surface. Good.
- **Equivalence harness comment for `V_hasLabel_Person_Place`:** The DECLINED case at lines 191-194 is correctly accompanied by a comment explaining why multi-class hasLabel is not handled at the start-step recogniser (lines 187-190). Pinning the contract surface explicitly is the right pattern for a parameterised harness.
