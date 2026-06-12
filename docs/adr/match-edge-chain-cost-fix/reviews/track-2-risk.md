# Track 2 Risk Review — Iteration 1

## Part 1: Evidence Certificates

#### Exposure: sort loop in `updateScheduleStartingAt`
- **Track claim**: Modify the per-edge cost-computation block at
  `MatchExecutionPlanner.java:2108-2133` so each candidate edge is passed
  through `resolveChainedTarget`; on a chain hit, call a new
  class-forced `applyTargetSelectivity` overload instead of the
  single-edge one.
- **Critical path trace**:
  1. Entry: `MatchExecutionPlanner.calculateSchedule()` (via the SQL
     planner for every MATCH query) →
     `updateScheduleStartingAt(PatternNode, …)` @
     `MatchExecutionPlanner.java:2033`.
  2. Sort-loop body builds `edgeCosts` by iterating every `sortedEdges`
     entry @ `:2113-2129`. For each edge it calls
     `estimateEdgeCost` @ `:2119-2120` and, when the base cost is
     finite, `applyTargetSelectivity` @ `:2122-2124` +
     `applyDepthMultiplier` @ `:2125`. Track 2 inserts the chain-fold
     branch inside this conditional.
  3. `sortedEdges.sort(…)` @ `:2133-2134` uses TimSort ordering with
     `edgeCosts.getOrDefault(key, Double.MAX_VALUE)` as the key.
  4. `updateScheduleStartingAt` is invoked recursively for every
     unvisited neighbor @ `:2233-2236`, producing DFS-wide cost
     evaluation per source node per query.
- **Blast radius**: Every MATCH query plan is routed through this loop
  (confirmed by `SelectExecutionPlanner` paths that delegate to
  `MatchExecutionPlanner`). A bug that (a) drops a finite cost to
  `MAX_VALUE`, (b) produces `NaN`, or (c) throws from
  `resolveChainedTarget` would affect scheduling for all MATCH queries,
  not only edge-method chains.
- **Existing safeguards**:
  - Track 1 already added 44 unit tests for `resolveChainedTarget`
    covering null-method / wrong-method / size-mismatch / visited-edge
    / reverse-traversal cases (see `MatchExecutionPlannerMutationTest`
    lines 1618-2500).
  - TimSort-stable ordering @ `:2130-2134` preserves insertion order on
    equal cost, including the `MAX_VALUE` fallback, so an edge whose
    cost mysteriously "does not change" still sorts as it does today.
  - `cost < Double.MAX_VALUE` gate @ `:2121` protects
    `applyTargetSelectivity` from running on unestimated edges.
  - `cost < Double.MAX_VALUE` gate must also protect the new chain
    fold: the plan explicitly commits to this ("Fallback on unknown
    cost" constraint, plan lines 49-52).
  - Integration tests:
    `MatchEdgeMethodInferenceAndAbortTest.testVertexClassInferenceEnablesIndexIntersection`
    and `MatchStatementExecutionTest.testSelectivityInferred*` both
    exercise MATCH planning end-to-end on real schemas.
- **Residual risk**: MEDIUM. The critical-path change is small and
  well-bounded (a single `if` inside an existing conditional), but any
  regression blast-radiuses across every MATCH query.

---

#### Exposure: `applyTargetSelectivity` behavioural contract
- **Track claim**: Factor the shared body (lines 2475-2501) into a
  private helper; expose a new overload that skips
  `resolveTargetClass` and takes the pre-resolved class directly; the
  existing overload continues to call `resolveTargetClass`.
- **Critical path trace**:
  1. `applyTargetSelectivity` @ `:2461-2502` is called from exactly
     one site today: the sort loop @ `:2122-2124`.
  2. The first guarded branches (`targetClass == null` @ `:2472`,
     `schema == null || !existsClass` @ `:2477`, `classCount <= 0` @
     `:2482`, `filter != null` → heuristic @ `:2487-2494`,
     `targetEstimate == null` @ `:2497`) each short-circuit to
     `baseCost` unchanged.
  3. Only the final line @ `:2501` multiplies `baseCost * selectivity`.
- **Blast radius**: Refactor risk. If the extraction drops a short-
  circuit, `baseCost` is silently transformed in the common case.
- **Existing safeguards**: No unit tests currently target
  `applyTargetSelectivity` directly (grep in
  `MatchExecutionPlannerMutationTest` returned no matches). End-to-end
  coverage comes from MATCH integration tests.
- **Residual risk**: MEDIUM. The refactor is mechanical but exposed —
  Track 2 should add direct unit tests for the new overload (both
  short-circuit paths and the numeric multiplication path).

---

#### Assumption: `cost < Double.MAX_VALUE` gate is the right trigger
- **Track claim**: "If `estimateEdgeCost` returns `Double.MAX_VALUE`
  for the first edge, the chain fold must not upgrade it to a finite
  value" (plan lines 49-52).
- **Evidence search**: Read `estimateEdgeCost` @ `:2292-2340`.
- **Code evidence**:
  - Returns `MAX_VALUE` when `method == null` @ `:2299-2300`.
  - Returns `MAX_VALUE` when `parseDirection(methodName) == null` @
    `:2305-2306`. `parseDirection` @ `:3117-3127` accepts `out`,
    `oute`, `in`, `ine`, `both`, `bothe`; rejects `inv`/`outv`/`bothv`.
  - Otherwise returns a finite `CostModel.edgeTraversalCost(sourceRows,
    fanOut)` @ `:2339`.
- **Verdict**: VALIDATED. The first edge of a chain (`oute`/`ine`/
  `bothe`) always produces a finite cost; the gate fires. For an edge
  where cost is genuinely unknown (e.g. `method == null`), the gate
  short-circuits and the chain fold is skipped — matching the plan's
  fallback-on-unknown constraint.
- **Detail**: The gate also protects against `NaN`/`Infinity` from a
  pathological `estimateFanOut`, because
  `CostModel.edgeTraversalCost` is the only finite-producing path and
  it saturates at `Long.MAX_VALUE` internally; any future drift that
  produces `NaN` would sort-order unchanged under
  `Comparator.comparingDouble` (which sends NaNs to the end).

---

#### Assumption: independence multiplication commutes across the existing `applyTargetSelectivity` body
- **Track claim**: "When the intermediate alias has its own `WHERE`
  (…), the intermediate's filter is already factored into `baseCost`
  by the existing per-edge sort-loop logic (…) The chain fold
  multiplies that product by the downstream vertex selectivity.
  Multiplication commutes." (plan lines 382-388)
- **Evidence search**: Trace `applyTargetSelectivity` body @
  `:2461-2502`.
- **Code evidence**:
  - Today the sort loop always calls `applyTargetSelectivity` on
    `neighbor.alias` (the intermediate edge alias) when base cost is
    finite @ `:2122-2124`.
  - In `.outE('X'){as: e, where: w}.inV()`, the intermediate alias `e`
    has a `WHERE` populated in `aliasFilters.get("e")`. The existing
    path enters the `filter != null` branch @ `:2487-2494` and returns
    `baseCost * heuristic` — provided `resolveTargetClass` returned a
    non-null class for `e`. With `outE('X')` targeting edge-alias `e`,
    the `resolveTargetClass` explicit branch @ `:2517-2520` is likely
    to hit because `addAliases` assigns `e → X` (the edge class)
    through `inferClassFromEdgeSchema` @ `:4753-4754`. So the
    intermediate-filter selectivity is already multiplied into
    `baseCost` today.
  - However, in the common `.outE('X').inV(){where: q}` case with no
    intermediate filter, `aliasFilters.get(intermediate) == null` →
    the `filter != null` branch skips. Then
    `estimatedRootEntries.get(intermediate)` is queried @ `:2496`.
    Edge aliases normally do **not** have a `estimatedRootEntries`
    entry (the map is built for root-candidate aliases in
    `estimateRootEntries` @ `:499`, which focuses on vertex classes).
    So the function returns `baseCost` unchanged @ `:2498`, and the
    chain fold's contribution is purely additive in the multiplicative
    sense — no double counting.
- **Verdict**: VALIDATED with a caveat. Multiplication commutes, and
  the plan's intuition is correct for the no-intermediate-filter case.
  When the intermediate has both its own filter AND a
  `targetEstimate` row via `estimatedRootEntries`, the multiplication
  still composes because `applyTargetSelectivity` returns `baseCost *
  heuristic` (single factor, not double). **Caveat**: if for some
  reason `estimatedRootEntries` starts including edge aliases in
  future work, the existing path would start multiplying by
  `targetEstimate / classCount` on top of the heuristic — but that's
  already the status quo, not something the chain fold introduces.

---

#### Assumption: updating only `testVertexClassInferenceEnablesIndexIntersection` is sufficient
- **Track claim**: "Update `testVertexClassInferenceEnablesIndexIntersection`
  (MatchEdgeMethodInferenceAndAbortTest.java:183) to assert that
  `{selectiveTag}` appears **before** `{broadTag}` in the `EXPLAIN`
  plan string" (plan lines 409-414).
- **Evidence search**: Ran both grep sweeps from the plan:
  - `grep -rn '{selectiveTag}\|{broadTag}' core/src/test` — 4 files:
    `MatchEdgeMethodInferenceAndAbortTest` (target),
    `MatchStatementExecutionTest:4689,4690,4759,4760`.
  - `grep -rn 'executionPlanAsString' core/src/test` — ~20 files;
    alias-ordering indexOf/`Pos` assertions appear in
    `MatchEdgeMethodInferenceAndAbortTest` (2 sites, lines 142-149 and
    the updated 254 site), `MatchStatementExecutionTest` (2 sites at
    4689-4696 and 4759-4767), `MatchPreFilterSchemaVariationsTest`
    (grep showed contains-only, no indexOf ordering),
    `HashJoinPlannerIntegrationTest` (contains-only), and the
    `CostModelIntegrationTest` (contains-only).
- **Code evidence**:
  - `MatchStatementExecutionTest:4689` and `:4759` — both use the
    single-step `.out('X')` pattern, NOT `.outE('X').inV()`. The
    structural rule would not match because `out` is not in
    `oute`/`ine`/`bothe`. They are unaffected.
  - `MatchEdgeMethodInferenceAndAbortTest.testEdgeAliasSchedulingOrder`
    @ `:70-168` — uses `.outE('SOWorkAt').inV()` + `.out('SOHasTag')`
    pair. Asserts `{workEdge}` (the selective **intermediate** edge
    alias) is before `{tag}` (the broad single-step vertex).
    Post-fix, the `outE('SOWorkAt')` side gets its downstream WHERE
    (`workFrom = 2015`, already applied to the intermediate alias
    `workEdge`) × **no** downstream-vertex WHERE (the `company` alias
    has no filter). The chain fold with `workEdge`'s own filter
    already in place, multiplied by the selectivity of `company`
    (which has no filter, so `classCount/classCount = 1.0` or no
    factor depending on branch), should preserve current ordering.
    **Risk**: if the chain-folded cost produces a tiebreak shift vs.
    `.out('SOHasTag')`, this test could flip.
  - `MatchEdgeMethodInferenceAndAbortTest.testVertexClassInferenceEnablesIndexIntersection`
    @ `:183-274` — uses two `.outE('VIHasTag').inV()` branches. This
    is the target test. Today the two branches sort as equal (both
    have intermediate edges with no filter, and the downstream vertex
    filters are invisible to the cost model). After the fix, the
    selective branch gets a lower cost and scheduling flips.
  - `MatchEdgeMethodLdbcPatternTest` — several `.outE('X'){where:
    …}.inV(){as: …}` patterns but **no** plan-ordering assertions
    (the grep returned only `plan.contains`).
- **Verdict**: UNVALIDATED (partially). The plan's single test-update
  target covers the load-bearing case. **However**,
  `testEdgeAliasSchedulingOrder` has a realistic chance of ordering
  drift. Track 2 should explicitly verify that test in the pre-merge
  sweep — adding the grep result to the review loop would also catch
  any follow-up tests added between Track 1 and Track 2.
- **Detail**: The plan's grep sweep is a correct process control, but
  it is not testable — "did the author run grep?" cannot be asserted
  by CI. Recommend making the sweep the first step of the track's
  post-implementation review and recording the findings in the track
  episode.

---

#### Assumption: Track 1 deviations do not affect Track 2
- **Track claim**: Strategy refresh says "CONTINUE … no downstream
  impact detected".
- **Evidence search**: Reviewed `resolveChainedTarget` @
  `:2613-2683` and its dependency
  `inferDownstreamVertexClassFromEdge` @ `:2702-2717` +
  `lookupLinkedVertexClass` @ `:4794-4816`.
- **Code evidence**:
  - `resolveChainedTarget`'s signature matches what Track 2 wires in:
    `(edge, neighbor, visitedEdges, aliasClasses, session)` →
    `Optional<ChainedTarget>` with `(effectiveTargetAlias,
    effectiveTargetClass)`.
  - The helper returns a `ChainedTarget` even when
    `effectiveTargetClass == null` (the `bothE→bothV` + no-class-
    annotation case). Track 2's new overload must handle
    `preResolvedTargetClass == null` as "short-circuit to baseCost" —
    which matches the plan at lines 403-404.
  - `lookupLinkedVertexClass` now accepts `@Nullable
    DatabaseSessionEmbedded`. The Track 2 overload will pass the
    session through from the sort loop, so this widening is a no-op
    for Track 2 (sessions are always non-null in the sort loop path).
- **Verdict**: VALIDATED. Track 1 deviations are internal and do not
  change the Track 2 contract. The new `ChainedTarget` + helper
  signature is the exact shape Track 2 needs.

---

#### Testability: call-site integration and overload behaviour
- **Coverage target**: 85% line / 70% branch on changed code.
- **Difficulty assessment**:
  - **Call-site integration** (sort-loop diff): This change is a
    single new `if`-branch inside the finite-cost block @ `:2121-2126`.
    Covering it directly requires building a `Pattern` graph with at
    least two `PatternEdge`s matching the structural rule AND a
    `PatternNode` whose `out`/`in` sets are wired correctly. The
    existing `MatchExecutionPlannerMutationTest` pattern
    (`mockEdgeWithMethod`, `mockEdgeWithMethodAndParam`, direct
    `PatternEdge`/`PatternNode` field assignment — used by Track 1)
    can synthesise this in-memory without SQL parsing.
  - **`updateScheduleStartingAt` itself** is package-private and has
    never been unit-tested in `MatchExecutionPlannerMutationTest`
    (the class doc @ lines 49-52 explicitly calls out the
    "scheduling/traversal mutations" as "deeply coupled to the pattern
    graph infrastructure … would require full integration test
    setup"). Track 2 will need to either (a) add the first such unit
    tests — doable using Track 1's synthesised
    `PatternEdge`/`PatternNode` helpers — or (b) rely on integration
    tests (`testVertexClassInferenceEnablesIndexIntersection` +
    Track 3 regression tests).
  - **New `applyTargetSelectivity` overload**: two public branches
    (`preResolvedTargetClass == null` → short-circuit;
    `preResolvedTargetClass != null` → delegate to shared private
    body). Both trivially unit-testable in Mockito style using the
    existing mocks.
  - **Shared private body**: covered by today's existing integration
    tests that go through `applyTargetSelectivity`; Track 2's refactor
    should not lose coverage.
- **Existing test infrastructure**: `MatchExecutionPlannerMutationTest`
  @ `core/src/test/.../match/MatchExecutionPlannerMutationTest.java`
  with Mockito mocks for `SQLMethodCall`, `PatternEdge`, `PatternNode`,
  `DatabaseSessionEmbedded`, `ImmutableSchema`, `MetadataDefault`.
  `@Before setUp()` pre-wires db → metadata → schema (lines 62-68).
- **Feasibility**: ACHIEVABLE. The 85% line / 70% branch target can be
  hit by (i) 2-3 unit tests on the new overload (both branches + the
  schema-null path); (ii) 1-2 tests on a synthesised pattern graph
  that exercise the sort-loop call site (chain hit + chain miss); and
  (iii) the existing integration test flip (`testVertexClassInference
  EnablesIndexIntersection`) + Track 3 regression tests for
  end-to-end coverage.

---

#### Testability: pre-merge grep sweep
- **Coverage target**: N/A — this is process, not code coverage.
- **Difficulty assessment**: The plan asks for two greps and a
  `./mvnw -pl core clean test` run. This is not directly verifiable in
  CI: nothing in the test corpus fails if the grep was not performed.
  The only safety net is the test suite itself — if any grep hit is a
  genuinely affected test, `mvn test` will fail.
- **Existing test infrastructure**: The full MATCH test suite (~828
  tests per Track 1 report) runs under `./mvnw -pl core clean test`.
- **Feasibility**: ACHIEVABLE as a process step. Recommend the track
  episode record the grep results explicitly (which files / lines
  were reviewed and why they were determined unaffected) so a
  reviewer can audit the sweep. This converts "was the sweep done?"
  into an evidence check.

---

#### Testability: runtime result-set invariant
- **Coverage target**: "A regression test verifies that the observed
  result set is identical to the pre-fix run" (plan line 53-55).
- **Difficulty assessment**: Both
  `testVertexClassInferenceEnablesIndexIntersection` (10-post × 5
  broad tags = 50 rows assertion @ `:236-237`) and the six Track 3
  regression tests assert row counts and content, which defends the
  invariant. Runtime semantics are untouched by planner changes
  anyway.
- **Existing test infrastructure**: `DbTestBase` + JUnit 4; trivial.
- **Feasibility**: ACHIEVABLE.

---

## Part 2: Findings

### Finding R1 [should-fix]
**Certificate**: "updating only `testVertexClassInferenceEnablesIndexIntersection`
is sufficient" — UNVALIDATED.
**Location**: `MatchEdgeMethodInferenceAndAbortTest.testEdgeAliasSchedulingOrder`
@ `core/src/test/.../MatchEdgeMethodInferenceAndAbortTest.java:70-168`.
**Issue**: This test today asserts `{workEdge}` (selective outE chain)
sorts before `{tag}` (broad single-step). Post-fix, the `outE.inV`
chain's cost gets **reduced** by the new chain fold (the `company`
side gets a finite `applyTargetSelectivity` via the new overload
instead of returning `baseCost` unchanged). If the chain-fold reduction
flips the ordering against `.out('SOHasTag')`, the test fails.
Likelihood: MEDIUM (the `{workEdge}` branch is already selective
because `workFrom = 2015` is already applied to the intermediate alias,
so the fold's additional factor for the no-filter `company` alias is
either 1.0 or small — but the exact magnitude depends on how
`applyTargetSelectivity` handles the no-filter-no-row-estimate case).
Impact: test flake during Track 2; not a correctness issue.
**Proposed fix**: Add this test to the Track 2 pre-merge sweep
explicitly (by name). If the ordering flips, update the assertion and
document why in the commit message (the post-fix ordering is the
correct one per the invariants in the plan). Alternatively, run the
test against HEAD+Track 2 early in the decomposition and pin the
expected post-fix ordering in the test update commit.

### Finding R2 [should-fix]
**Certificate**: `applyTargetSelectivity` refactor exposure — MEDIUM
residual risk.
**Location**: New overload + extracted private helper in
`MatchExecutionPlanner.java` around `:2461-2502`.
**Issue**: No unit test today covers `applyTargetSelectivity` directly.
The refactor (factoring lines 2475-2501 into a shared helper) is
mechanical but changes could silently drop a short-circuit or flip a
conditional, with no unit test to catch it. The blast radius is every
MATCH query that applies target selectivity.
Likelihood: LOW (mechanical refactor, Spotless will catch whitespace,
reviewer will catch logic), but the consequence is broad.
**Proposed fix**: Add unit tests in `MatchExecutionPlannerMutationTest`
for both overloads (new and existing) covering at minimum: (a) null
target class → baseCost; (b) non-existent class → baseCost; (c)
`classCount <= 0` → baseCost; (d) filter-heuristic path; (e)
`targetEstimate == null` fallback; (f) cardinality-ratio path.
The plan already commits to a ~3-step track; spend one step on this
test wave in parallel with the overload extraction.

### Finding R3 [should-fix]
**Certificate**: Pre-merge grep sweep — ACHIEVABLE but unverifiable
via CI.
**Location**: Track 2 process step.
**Issue**: The plan asks for two greps but provides no mechanism to
audit them. If the author skips the sweep, a downstream test regression
only surfaces during `./mvnw -pl core clean test`.
Likelihood: LOW (the author will run the full test suite anyway), but
the sweep's analytical value (deciding which tests are affected vs.
merely mentioning the alias) is lost if undocumented.
**Proposed fix**: The track episode (Phase B artifact) should record
the grep output and a one-line verdict per hit: "{selectiveTag}/
{broadTag} appears in X; uses `.out` not `.outE.inV`; unaffected." or
"… uses `.outE.inV`; expected to flip; updated assertion." This makes
the sweep auditable in Phase C review.

### Finding R4 [suggestion]
**Certificate**: Sort-loop call-site unit testing — no prior coverage.
**Location**: `MatchExecutionPlannerMutationTest` @
`core/src/test/.../match/MatchExecutionPlannerMutationTest.java`
(class doc @ `:49-52` explicitly notes sort-loop is not covered).
**Issue**: Track 2 adds a new branch to the sort loop, but the loop
itself has never been unit-tested in the mutation-test suite. All
existing coverage is via integration tests that build real patterns.
Track 2 **could** add the first unit tests for the sort-loop call site
using Track 1's `PatternEdge`/`PatternNode` synthesis primitives. This
would catch regressions faster than the integration tests and close
the coverage target more directly.
Likelihood: LOW risk that coverage falls below thresholds without this,
because Track 3 adds six regression integration tests. But unit tests
are cheaper to debug.
**Proposed fix**: Optional — add 2 unit tests exercising the sort-loop
with a synthesised chain pattern (chain hit + chain miss) to
`MatchExecutionPlannerMutationTest`. Leave as a suggestion since
Track 3's integration tests will cover behaviour.

### Finding R5 [suggestion]
**Certificate**: Rollback story — planner-only change.
**Location**: Track 2 overall.
**Issue**: The plan does not explicitly address rollback if a
particular schema shape produces a worse cost estimate after the fix.
Since the change is planner-only (no persisted state, no WAL, no
schema migration), rollback is trivial — revert the commit. But the
plan could note the escape hatch: the entire chain-fold path is gated
on `resolveChainedTarget` returning non-empty, so rolling back is as
simple as making the helper always return `Optional.empty()` or
commenting out the call site.
Likelihood: LOW — no irreversible state.
**Proposed fix**: Add a one-line rollback note to the track episode
during Phase B: "Rollback: revert `updateScheduleStartingAt` call-site
changes; `resolveChainedTarget` helper is unused but dormant."

### Finding R6 [suggestion]
**Certificate**: Performance — schema lookups on the hot path.
**Location**: `resolveChainedTarget` @ `:2613-2683` is invoked once
per candidate edge in the sort loop.
**Issue**: The helper is mostly structural (cheap set-size checks,
identity compare) but the class-inference precedence falls through to
`inferDownstreamVertexClassFromEdge` → `lookupLinkedVertexClass` →
`schema.getClassInternal()` → `edgeClass.getPropertyInternal()` in the
cold path (when `aliasClasses.get(effectiveTargetAlias) == null`).
Schema snapshot access is already a per-sort-loop operation via
`estimateEdgeCost` and `applyTargetSelectivity`, so the marginal cost
is small — but the lookup is now duplicated in the chain path (once
in `estimateEdgeCost` for the first edge, again in
`resolveChainedTarget`'s fallback).
Likelihood: LOW — `getImmutableSchemaSnapshot()` is O(1) and
`getClassInternal`/`getPropertyInternal` are hash-map reads. No new
allocations or locks.
**Proposed fix**: Leave as-is. If micro-benchmarks (JMH LDBC) show a
regression on MATCH planning time, consider caching the result of
`getImmutableSchemaSnapshot()` once per `updateScheduleStartingAt`
call.

---

## Summary
- Blockers: 0
- Should-fix: 3 (R1, R2, R3)
- Suggestions: 3 (R4, R5, R6)
- Overall verdict: **PROCEED** with the three should-fix items
  folded into Track 2's decomposition:
  - **R1**: add `testEdgeAliasSchedulingOrder` to the pre-merge sweep,
    verify early, update assertion if it flips.
  - **R2**: add direct unit tests for the new `applyTargetSelectivity`
    overload and the refactored shared body (1 step in the track).
  - **R3**: record grep sweep results in the track episode so Phase C
    review can audit them.

Track 1's deviations do not affect Track 2. The plan's risk analysis
is sound; the main residual risks are test-side (ordering flips, new
overload coverage) rather than correctness-side. The
`cost < Double.MAX_VALUE` gate is validated and correctly protects the
fallback-on-unknown-cost constraint. Independence multiplication is
validated through the existing `applyTargetSelectivity` body's
short-circuits.
