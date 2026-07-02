<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: TB1, sev: suggestion, loc: YTDBQueryMetricsStrategyTest.java:239, anchor: "### TB1 ", cert: C1, basis: "scan test asserts only non-null + no-index-step; passes for an empty/unrelated captured plan"}
  - {id: TC1, sev: suggestion, loc: YTDBQueryMetricsStrategyTest.java:206, anchor: "### TC1 ", cert: C2, basis: "D3 non-graph-rooted / child-traversal null-plan locality contract has no regression test"}
  - {id: TC2, sev: suggestion, loc: YTDBQueryMetricsStrategyTest.java:206, anchor: "### TC2 ", cert: C3, basis: "D2 downstream short-circuit (limit(0)) -> null-plan claim untested"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [suggestion] Scan test asserts only the absence of an index step, so a wrong captured plan passes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`, method `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep` (lines 239-246)

**Issue** — This is the core positive test for the scan-detection use case (D-purpose: DNQ walks the plan to flag an unindexed full scan). It makes two assertions: the plan is non-null, and its steps contain no `FetchFromIndexStep`. Both are satisfied by a plan that has nothing to do with the `person` scan — an empty step list, or a plan captured from a different query, passes unchanged. The negative assertion carries no evidence that the captured plan is the plan that actually ran.

**Evidence** — FALSIFIABILITY CHECK: mutate the capture at `YTDBGraphStep.java:157` to `this.lastExecutionPlan = <any non-null plan with no index step>` (or a plan whose `getSteps()` is empty). `assertThat(listener.executionPlan).isNotNull()` passes; `containsFetchFromIndexStep(listener.planStepsInCallback)` returns `false` and `.isFalse()` passes. The test gives false confidence for the exact mutation the feature must guard against. Cross-test mitigation exists — `executionPlanReadableInsideCallbackAfterResultSetClosed` (lines 328-335) drains the same `g().V().hasLabel("person")` and asserts `planStepsInCallback` is non-empty — but that confidence lives in a different test, not this one.

**Missing behavior** — The scan test should positively assert the captured plan is a full-class scan: its steps should contain a `FetchFromClassExecutionStep` (the scan step, sibling of `FetchFromIndexStep` in `internal.core.sql.executor`), or at minimum that the step list is non-empty, so the "no index step" claim is anchored to a real scan plan rather than to nothing.

**Suggested fix**:
```java
assertThat(listener.executionPlan)
    .as("a plan-backed scan surfaces a non-null plan")
    .isNotNull();
org.assertj.core.api.Assertions.assertThat(listener.planStepsInCallback)
    .as("the captured plan is the scan plan, not an empty/unrelated plan")
    .isNotEmpty();
org.assertj.core.api.Assertions.assertThat(
        containsStepOfType(listener.planStepsInCallback, FetchFromClassExecutionStep.class))
    .as("an unindexed scan fetches from the class, not an index")
    .isTrue();
org.assertj.core.api.Assertions.assertThat(
        containsFetchFromIndexStep(listener.planStepsInCallback))
    .as("an unindexed scan uses no index step")
    .isFalse();
```
(Generalize the existing `containsFetchFromIndexStep` helper into a `containsStepOfType(List<ExecutionStep>, Class<?>)` recursive scan, or add a sibling helper.)

### TC1 [suggestion] The D3 locality contract (non-graph-rooted / child-traversal → null plan) has no regression test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java` (new-test block, lines 206-419)

**Production code** — `YTDBQueryMetricsStep.capturedExecutionPlan()` (`YTDBQueryMetricsStep.java:73-77`) resolves the plan via `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)`, which scans the **root** traversal's direct steps only.

**Missing scenario** — A root traversal that is not rooted at a `YTDBGraphStep` (for example `g.inject(...)`, per D3's stated risk) surfaces a `null` plan even though `queryFinished` fires; likewise a plan-backed scan buried in a child traversal (`where`/`union`/`local`) is not captured. D3 documents this as an accepted locality contract, but no test pins it.

**Why it matters** — The consumer (DNQ full-scan detection) keys off plan presence. A silent `null` for a query shape the consumer did believe was plan-backed is exactly the kind of contract that drifts on a future refactor of `capturedExecutionPlan()` or of the strategy's step-placement. D5's null-on-replay limitation was pinned with a test on the same reasoning; the D3 limitation deserves the same guard. The input-domain axis "root traversal shape" (graph-rooted vs `inject`-rooted) is currently untested — every new test uses a `g().V()...` root.

**Refutation considered** — The track lists "Child-traversal / non-graph-rooted plan capture (D3)" as *out of scope*. That scopes out *implementing broader capture*; it does not scope out a test that pins the *current* (null) behavior, which is what makes an accepted limitation safe to accept. Treat this as a suggestion rather than should-fix precisely because of that scope tension.

**Suggested test**:
```java
// A non-graph-rooted root traversal (g.inject) has no YTDBGraphStep at its root, so
// capturedExecutionPlan() finds no source step and the listener sees a null plan even
// though the query-finished callback still fires. Pins the D3 locality contract.
@Test
@LoadGraphWith(MODERN)
public void nonGraphRootedTraversalSurfacesNullPlan() throws Exception {
  final var listener = new RememberingListener();
  ((YTDBTransaction) g.tx())
      .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
      .withQueryListener(listener);

  g.tx().open();
  try (var q = g().inject(1, 2, 3)) {
    q.toList();
  }
  g.tx().commit();

  org.assertj.core.api.Assertions.assertThat(listener.notified).isTrue();
  assertThat(listener.executionPlan)
      .as("a non-graph-rooted root traversal captures no source-step plan (D3)")
      .isNull();
}
```

### TC2 [suggestion] The D2 downstream-short-circuit (`limit(0)` → null) claim is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java` (new-test block, lines 206-419)

**Production code** — Plan capture happens in `YTDBGraphStep.elements()` at `YTDBGraphStep.java:157`, inside the source step's iterator supplier. The field stays `null` until the supplier runs.

**Missing scenario** — D2's Risks/Caveats states: "A downstream short-circuit that never pulls from the source (e.g. `limit(0)`) leaves the plan `null`, which is correct (the source query did not execute)." No test exercises a traversal whose downstream step short-circuits before the source is pulled.

**Why it matters** — This is a documented correctness claim about the boundary between "callback fired" and "source executed." If a future TinkerPop-fork change caused the source supplier to be invoked eagerly (or `limit(0)` to still pull one element), the plan would flip from `null` to non-null and the documented D2 contract would break silently. A test converts the prose claim into a guard.

**Refutation considered** — Whether `g.V().limit(0)` actually leaves the source unpulled depends on the fork's `RangeGlobalStep` short-circuit behavior, which I could not execute here (tests run only through the `YTDBProcessTest` suite; mcp-steroid unreachable, so no PSI confirmation of the step interaction). The suggested test therefore asserts the D2-documented outcome; if the fork pulls the source anyway, the test surfaces that the D2 claim is wrong — either outcome is useful. Lowest-value of the three; a suggestion only.

**Suggested test**:
```java
// D2: a downstream limit(0) short-circuits before the source step is pulled, so no query
// runs and the listener sees a null plan even though the callback fires. Pins the claim.
@Test
@LoadGraphWith(MODERN)
public void downstreamShortCircuitSurfacesNullPlan() throws Exception {
  final var listener = new RememberingListener();
  ((YTDBTransaction) g.tx())
      .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
      .withQueryListener(listener);

  g.tx().open();
  try (var q = g().V().hasLabel("person").limit(0)) {
    q.toList();
  }
  g.tx().commit();

  org.assertj.core.api.Assertions.assertThat(listener.notified).isTrue();
  assertThat(listener.executionPlan)
      .as("a limit(0) short-circuit never pulls the source, so no plan is captured (D2)")
      .isNull();
}
```

## Evidence base

#### C1 [TB1] CONFIRMED — mutating the capture to any non-null empty/unrelated plan passes `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`; its only positive claim is non-null, its shape claim is negative-only. Meaningful precision gap on the core scan-detection test.

#### C2 [TC1] CONFIRMED — no new test uses a non-`V()/E()`-rooted root or a child-traversal scan; `capturedExecutionPlan()` scans root direct steps only, so the D3 locality contract is unpinned. Meaningful (documented-limitation guard) gap.

#### C3 [TC2] CONFIRMED — no test exercises a downstream short-circuit; D2's `limit(0)`→null claim is prose-only. Meaningful-but-low-value gap (mechanism unverifiable here).

#### C4 REFUTED — "the new tests never run in CI (gremlintest scenarios need suite wiring)." Checked: `grep` shows `YTDBQueryMetricsStrategyTest` is referenced in `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/suites/YTDBGremlinProcessTests.java`, the `@SelectClasses` suite the episode names as the runnable entry point. The class predates this diff and its existing `testQueryMonitoringExact`/`Lightweight` tests already run through that suite, so the seven added `@Test` methods run with it. Not a finding. (Caveat: verified by grep, not PSI — mcp-steroid unreachable; but a suite `@SelectClasses` reference is a literal-name match grep resolves reliably.)

#### C5 REFUTED — "`cacheHitReplaySurfacesNullPlan` leaks the global `QUERY_TX_RESULT_CACHE_ENABLED` mutation into other suite tests." Checked: the test wraps the mutation in `try { ... } finally { GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheWasEnabled); }` (lines 346-379), reading and restoring the prior value. The gremlin scenarios run sequentially (`@Category(SequentialTest.class)`, suite-ordered), so no concurrent reader observes the toggled window. Matches the established `AggregateCacheEquivalenceTest` pattern. Not a finding.

#### C6 REFUTED — "the cache-replay test's first (populating) run may itself be a cache hit served from the `@Before warmup()` query, so it would not capture a plan." Checked: `warmup()` runs `g().executeInTx(s -> s.V().hasLabel("person").toList())` in a *separate* transaction, and the result cache is per-transaction (D5); the test also enables the cache flag only inside its own `try` block, after warmup. So `q1` inside the monitored tx is a genuine cache-miss populating run, and its non-null-plan assertion (line 360-362) is sound. Not a finding.
