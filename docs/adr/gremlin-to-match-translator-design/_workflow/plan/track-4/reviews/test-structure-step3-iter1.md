<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: TS1, sev: suggestion, loc: HasStepRecogniserTest.java:293, anchor: "### TS1 ", cert: n/a, basis: "recogniser-test harness copied verbatim across three-plus test files; each later track re-copies it"}
  - {id: TS2, sev: suggestion, loc: GraphStepStrategyTest.java:22, anchor: "### TS2 ", cert: n/a, basis: "@Before translator toggle is leak-safe but the safety must be inferred across two classes, not read"}
  - {id: TS3, sev: suggestion, loc: PredicateTraversalEquivalenceTest.java:369, anchor: "### TS3 ", cert: n/a, basis: "three near-identical translator-flag save/restore blocks; one Supplier overload collapses two of them"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [suggestion] The recogniser-test harness is copied across the new test files

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/HasStepRecogniserTest.java` (helpers, line 293); `TraversalFilterStepRecogniserTest.java` (helpers, line 130)

**Issue**: Both recogniser unit tests declare the same harness: the `BOUNDARY_ALIAS = "$g2m_v0"` and `TRANSPARENT` constants, `cursorAfterStart(Traversal.Admin)`, `renderBoundaryFilter(WalkerContext)`, and a `contextWithStartBoundary(...)` that seeds the start step's context. `countBoundarySteps` is a fourth duplicated helper — `PredicateTraversalEquivalenceTest` (line 288 of its new content) and the pre-existing `GremlinToMatchSmokeTest` each carry their own copy. The plan sequences one recogniser per remaining track (Tracks 5–7), so each will copy this block again.

The duplication does not threaten isolation — every copy is correct — but it spreads the "drive a real step list over a hand-seeded WalkerContext" seam across files, so a change to the context-seeding contract has to be chased through each.

**Suggestion**: Extract a small `RecogniserTestSupport` base class (or static test util) holding the shared constants, `cursorAfterStart`, `renderBoundaryFilter`, and `countBoundarySteps`. Keep the `contextWithStartBoundary` variants local, since the `HasStep` version varies polymorphism and schema while the `TraversalFilter` version is fixed — the difference is meaningful and worth keeping visible per class. This puts the harness contract in one documented place for the tracks that follow.

### TS2 [suggestion] Document why the `@Before` translator toggle needs no restore

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/GraphStepStrategyTest.java`, method `disableGremlinToMatchTranslator` (line 22)

**Issue**: The new `@Before` disables the Gremlin-to-MATCH translator so the native `YTDBGraphStepStrategy` has-container fold stays observable, and its Javadoc explains that reason well. It does not record why leaving the flag unrestored is safe. It is safe: `session.getConfiguration()` returns the storage-scoped `ContextConfiguration` (`DatabaseSessionEmbedded.getConfiguration` → `storage.getContextConfiguration()`), whose `setValue(GlobalConfiguration, value)` writes to a per-instance `ConcurrentHashMap`, not the JVM-global `GlobalConfiguration`. `DbTestBase` creates a fresh database — hence fresh storage and fresh context config — per test method and drops it in `@After`, so the toggle cannot leak into or out of any other test. A reviewer confirming isolation has to trace two base classes to reach that conclusion.

**Suggestion**: Add one sentence to the Javadoc — the flag is storage-scoped and the per-method DB drop discards it, so no `@After` restore is needed. It turns an isolation guarantee that currently must be inferred into documentation the next reader can trust on sight.

### TS3 [suggestion] Consolidate the three translator-flag save/restore blocks

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java`, `assertEquivalent` (line 369), `boundaryPlanText` (line 407), `withTranslator` (line 426)

**Issue**: Three helpers open with the same idiom — read `translatorEnabled()` into `original`, set the flag, run inside a `try`, restore in `finally`. Each block is correct and restores on every path. `assertEquivalent` genuinely needs a hand-rolled guard because it toggles the flag twice (on, then off) inside one method. `boundaryPlanText` duplicates `withTranslator`'s body only because `withTranslator` takes a `Runnable` and cannot return the plan text.

**Suggestion**: Add a `<T> T withTranslator(boolean, Supplier<T>)` overload so `boundaryPlanText` reuses the guard, leaving one save/restore idiom outside `assertEquivalent`. Optional cleanup — the current code is correct, and the repeated `finally` restores mean no interruption leaves the flag dirty.

## Evidence base
