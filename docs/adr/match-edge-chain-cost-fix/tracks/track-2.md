# Track 2: Wire chain-aware cost into the sort loop

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (2 iterations; iteration 2 PASS)

## Base commit
`e44213a6ae`

## Reviews completed
- [x] Technical (1 blocker T1 fixed in plan; 1 should-fix T2 folded into Step 2; suggestions T3/T4/T5/T6 folded into Step 1/Step 2 as noted below)
- [x] Risk (0 blockers; 3 should-fix R1/R2/R3 folded into Step 1/Step 2; suggestions R4/R5/R6 noted but not adopted)

## Review fixes applied (Phase A)
- **T1 (blocker)** — Plan Track 2 body text rewritten to correctly state
  that the existing `applyTargetSelectivity` call on the intermediate
  alias is **preserved unchanged** and the chain fold is an **additional**
  second call, not a replacement. Now aligns with Design Record D3
  (independence multiplication across filters).
- **R1 (should-fix)** — Added explicit pre-merge verification of
  `testEdgeAliasSchedulingOrder` to the plan's Pre-merge verification
  block.
- **R3 (should-fix)** — Added "record grep output in step episode with
  per-hit verdict" to the plan's Pre-merge verification block.
- **T6 (suggestion)** — Added `(intersection: index VITag_name)`
  re-verification requirement to the plan's Pre-merge verification.

## Steps

- [x] Step 1: Extract `applyClassSelectivity` shared helper + add
  class-forced `applyTargetSelectivity` overload with unit tests
  - [x] Context: unavailable
  > **What was done:** Extracted the body of the 8-arg
  > `applyTargetSelectivity` (from the schema-snapshot lookup through the
  > cardinality-ratio return) into a `private static applyClassSelectivity`
  > helper that assumes a non-null target class. Added the 6-arg
  > class-forced overload `applyTargetSelectivity(double, String,
  > @Nullable String, Map, Map, DatabaseSessionEmbedded)` that
  > short-circuits to `baseCost` when `preResolvedTargetClass` is null
  > and otherwise delegates to the shared helper. Both overloads now
  > route through the same arithmetic, so the production call site at
  > MatchExecutionPlanner.java:2122 produces byte-identical results to
  > the pre-refactor implementation. Added 12 unit tests in
  > `MatchExecutionPlannerMutationTest` (124 tests total): 7 covering
  > the new overload's short-circuit branches (null class, null schema
  > snapshot, class not in schema, classCount == 0, filter-heuristic
  > equality/inequality, cardinality-ratio fallback, filter-present
  > heuristic-returns-minus-one fallback, no-filter-no-estimate), plus
  > 3 parity tests asserting the 8-arg and 6-arg overloads return
  > identical doubles on matching inputs.
  >
  > **What was discovered:** Errorprone's `InvalidParam` bug pattern
  > flags `{@code resolveTargetClass}` in the new overload's Javadoc as
  > "similar to parameter `preResolvedTargetClass`" and fails the build.
  > Switching to `{@link #resolveTargetClass}` avoids the false positive
  > — the lint disambiguates parameter references from method references
  > via Javadoc tag, so `@link` is safe.
  >
  > **What changed from the plan:** None material. Step 2 can wire the
  > 6-arg overload exactly as the plan specifies.
  >
  > **Review fixes applied in `78d56c7562`:** Step-level review returned
  > 0 blockers, 3 should-fix, and several suggestions across 4
  > dimensions (code quality, bugs & concurrency, test behavior, test
  > completeness). All 3 should-fix items fixed plus 1 related
  > suggestion:
  > - TC-1: added `applyTargetSelectivity_classForced_schemaSnapshotNull_returnsBaseCost`
  >   to pin the `schema == null` half of the
  >   `schema == null || !schema.existsClass(...)` guard, which the
  >   existing `classNotInSchema` test did not exercise. Narrowed that
  >   test's doc comment to claim only the `!existsClass` half it
  >   actually pins.
  > - TC-2: added `applyTargetSelectivity_classForced_heuristicUnestimable_fallsBackToCardinalityRatio`
  >   to pin the fall-through path when `estimateFilterSelectivity`
  >   returns -1.0 for an unestimable WHERE (empty AND block); prevents
  >   a mutation of the `heuristic >= 0.0` threshold from surviving.
  > - TB-1: tightened the inequality test's delta from 1.0 to `DELTA`
  >   (1e-9); the arithmetic `500 × 999/1000 = 499.5` is exact in
  >   IEEE-754 at this magnitude, so the wider tolerance was admitting
  >   off-by-one mutations in the `(n-1)/n` fraction.
  > - TB-2 (suggestion, same theme): tightened the equality and
  >   cardinality-ratio test deltas from 0.01 to `DELTA` for the same
  >   reason — both expected values (0.5) are exact.
  >
  > **Suggestions deferred (noted, not fixed):**
  > - CQ-1: `makeWhereWithOperator` is duplicated between
  >   `MatchExecutionPlannerMutationTest` and `EstimateEdgeCostTest`;
  >   extracting a shared helper is a cross-test-class refactor out of
  >   scope for this step.
  > - CQ-2: the shared helper's `targetClass` parameter is unannotated
  >   while the overload's `preResolvedTargetClass` is `@Nullable`;
  >   asymmetric but acceptable given the Javadoc's explicit
  >   non-null contract.
  > - CQ-3: Javadoc asymmetry between `aliasFilters` null-guard
  >   (tolerated) and `estimatedRootEntries` null-guard (not
  >   tolerated) — a pre-existing behaviour preserved by the refactor.
  > - CQ-4/5/6: style micro-opts (named local for
  >   `(String) null`, javadoc alignment — handled by Spotless).
  > - BC-1: asymmetric null-tolerance for `estimatedRootEntries` —
  >   pre-existing, caller at line 2122 always passes non-null.
  > - BC-2: negative `classCount` not tested; `approximateCount` never
  >   returns negatives in practice, so the `<= 0` vs `==` mutation is
  >   defensive-only.
  > - TB-3: parity tests could also pin absolute values — redundant with
  >   the already-present non-parity branch tests.
  > - TB-4: subsumed by TC-1 fix above.
  > - TC-3/4/5/6: mutation-level boundary tests below the should-fix
  >   threshold given the narrow production caller surface.
  >
  > **Cross-track impact:** None. The new 6-arg overload matches Step 2's
  > wiring contract exactly (signature, null-class semantics, arithmetic
  > delegation). Track 3's test scenarios depend on Step 2, not this
  > refactor.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java`
  >   (modified) — +61 lines: extracted `applyClassSelectivity`, added
  >   6-arg `applyTargetSelectivity` overload with Javadoc.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java`
  >   (modified) — +287 lines: 12 new tests covering both overloads'
  >   short-circuit branches + parity + helper
  >   `makeWhereWithOperator`.
  > **Goal:** Refactor the shared body of `applyTargetSelectivity` into
  > a private helper so both overloads can reuse it, then add the new
  > class-forced overload that bypasses `resolveTargetClass`.
  > Refactor-only behavior change at the existing single call site at
  > `MatchExecutionPlanner.java:2122-2124` (it continues to call the
  > existing 8-arg overload, whose body now delegates to the shared
  > helper — result is identical).
  >
  > **Files (expected ~2):**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java`
  >   — extract shared helper, add new overload.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java`
  >   — new unit tests covering both overloads' branches.
  >
  > **Changes:**
  > 1. Extract the body of the existing `applyTargetSelectivity` from
  >    line 2475 downward (the `var schema = session.getMetadata()
  >    .getImmutableSchemaSnapshot();` block) into a new
  >    `private static double applyClassSelectivity(
  >        double baseCost,
  >        String targetAlias,
  >        String targetClass,
  >        Map<String, SQLWhereClause> aliasFilters,
  >        Map<String, Long> estimatedRootEntries,
  >        DatabaseSessionEmbedded session)`.
  >    The helper assumes a non-null `targetClass`; all null-guards are
  >    done by the callers. Name chosen per Finding T3 — mirrors the
  >    existing `estimateFilterSelectivity` / `estimateCompoundAnd-
  >    Selectivity` package-private-static style but is `private static`
  >    because only the two overloads call it.
  > 2. Re-wire the existing 8-arg `applyTargetSelectivity(double, String,
  >    PatternEdge, boolean, …)`: after `resolveTargetClass`, on null →
  >    return `baseCost`; else delegate to `applyClassSelectivity`.
  > 3. Add the new overload:
  >    ```java
  >    static double applyTargetSelectivity(
  >        double baseCost,
  >        String targetAlias,
  >        @Nullable String preResolvedTargetClass,
  >        Map<String, SQLWhereClause> aliasFilters,
  >        Map<String, Long> estimatedRootEntries,
  >        DatabaseSessionEmbedded session)
  >    ```
  >    Body: `preResolvedTargetClass == null` → return `baseCost`
  >    unchanged; else delegate to `applyClassSelectivity(baseCost,
  >    targetAlias, preResolvedTargetClass, …)`. Javadoc explains this
  >    is the chain-aware call path used by the sort-loop's chain fold.
  > 4. Add unit tests to `MatchExecutionPlannerMutationTest` covering
  >    all short-circuit branches of the shared body **via both
  >    overloads** (Finding R2):
  >    - existing overload (class resolved via `resolveTargetClass`):
  >      - `resolveTargetClass` returns null → `baseCost`;
  >      - class not in schema → `baseCost`;
  >      - `classCount <= 0` → `baseCost`;
  >      - filter present, heuristic path → `baseCost × heuristic`;
  >      - filter absent, `targetEstimate == null` → `baseCost`;
  >      - filter absent, `targetEstimate` present → cardinality-ratio
  >        path.
  >    - new class-forced overload:
  >      - `preResolvedTargetClass == null` → `baseCost`;
  >      - class not in schema → `baseCost`;
  >      - `classCount <= 0` → `baseCost`;
  >      - filter-heuristic path;
  >      - cardinality-ratio path.
  >   Reuse the existing Mockito scaffolding at
  >   `MatchExecutionPlannerMutationTest.java:60-100` (`@Before setUp()`
  >   wires db → metadata → schema) and
  >   `mockEdgeClassWithVertexLinks(...)` at line 491.
  >
  > **Verification:**
  > - `./mvnw -pl core spotless:apply` clean.
  > - `./mvnw -pl core test -Dtest=MatchExecutionPlannerMutationTest`
  >   green (existing 112 tests plus new ones).
  > - `./mvnw -pl core test -Dtest=MatchStatementExecutionTest,
  >   MatchEdgeMethodInferenceAndAbortTest,
  >   EstimateEdgeCostTest` green (regression guard for the refactor).
  > - Coverage of changed lines via `coverage-gate.py`
  >   (85% line / 70% branch).
  >
  > **Acceptance (from plan):**
  > - The existing 8-arg `applyTargetSelectivity` call at line 2122-2124
  >   continues to produce the same numeric result as before (pure
  >   refactor for this caller).
  > - The new overload exists and correctly short-circuits on null
  >   class.
  >
  > **Notes:**
  > - No change yet at the sort-loop call site — that is Step 2.
  > - `applyClassSelectivity` stays `private static`; the chain-path
  >   integration lives in the sort-loop in Step 2.
  > - All three types (`@Nullable`, `SQLWhereClause`,
  >   `DatabaseSessionEmbedded`) already imported.

- [x] Step 2: Wire `resolveChainedTarget` + new overload into the sort
  loop; update existing ordering-sensitive test; grep sweep with
  episode audit trail
  - [x] Context: unavailable
  > **What was done:** Added the chain-aware second
  > `applyTargetSelectivity` call inside the finite-cost branch of
  > `updateScheduleStartingAt`'s pre-compute sort loop
  > (`MatchExecutionPlanner.java:2125-2146`), placed immediately after the
  > preserved intermediate-alias call and before `applyDepthMultiplier`.
  > `resolveChainedTarget` is called with the same `neighbor` already
  > computed by the loop; on non-empty it yields the downstream vertex
  > alias + class which are passed to the 6-arg overload. No-chain and
  > MAX_VALUE cases short-circuit via the existing guards; double-counting
  > is avoided because the two calls target distinct aliases (intermediate
  > edge alias vs. downstream vertex alias) that, in practice, never share
  > filters. Updated `testVertexClassInferenceEnablesIndexIntersection` in
  > `MatchEdgeMethodInferenceAndAbortTest` to assert
  > `selectivePos < broadPos` on top of the preserved
  > `(intersection: index VITag_name)` assertion, proving the chain fold
  > schedules the selective branch first. Grep sweep complete — see below.
  >
  > **Grep sweep results (per Finding R3):**
  >
  > `grep -rn '{selectiveTag}\|{broadTag}' core/src/test`:
  > 1. `MatchEdgeMethodInferenceAndAbortTest.java:258-259` — uses
  >    `.outE.inV` with ordering assertion — **updated** to assert
  >    `selectivePos < broadPos`.
  > 2. `MatchStatementExecutionTest.java:4689-4690` — uses `.out`, not
  >    `.outE.inV` — **unaffected**.
  > 3. `MatchStatementExecutionTest.java:4759-4767`
  >    (`testSelectivityInferredFromEdgeSchemaWithoutExplicitClass`) —
  >    uses `.out`, not `.outE.inV` — **unaffected**; this is the
  >    reference test whose assertion pattern the update mirrors.
  >
  > `grep -rn 'executionPlanAsString' core/src/test`:
  > - `CostModelIntegrationTest.java`, `IndexHistogramIntegrationTest.java`,
  >   `SelectStatementExecutionTest.java`, `MatchPreFilterTestBase.java`,
  >   `ExplainStatementExecutionTest.java`,
  >   `CorrelatedOptionalHashJoinTest.java`,
  >   `InvertedWhileHashJoinTest.java` — all assert presence of specific
  >   plan elements (fetch from index, intersection, filter step) not
  >   alias ordering; **unaffected**.
  > - `MatchEdgeMethodPreFilterTest.java` — uses `.outE.inV` /
  >   `.inE.outV` but assertions check result counts and intersection
  >   presence, not alias ordering; **unaffected** (verified by running
  >   the test: 13/13 green).
  > - `MatchEdgeMethodInferenceAndAbortTest.java:137, 342, 421, 492` —
  >   assert `(intersection:` presence, not ordering; **unaffected**.
  >
  > `testEdgeAliasSchedulingOrder` (`MatchEdgeMethodInferenceAndAbortTest.java:70`) —
  > explicitly re-verified per plan: uses
  > `.outE('SOWorkAt'){as: workEdge, where: workFrom=2015}.inV(){as: company}` +
  > `.out('SOHasTag'){as: tag}`. After the fold: branch A's first
  > `applyTargetSelectivity` call still applies the `workFrom=2015`
  > selectivity on the intermediate alias `workEdge`; the chain fold's
  > second call targets `company` which has no `class:`, no WHERE, and
  > no `estimatedRootEntries` entry — so the fold's call short-circuits
  > to baseCost. Net: branch A cost unchanged, branch B (broad
  > `.out('SOHasTag')`) cost unchanged. `workEdge < tag` ordering
  > preserved. **Test green** (5/5 green in
  > `MatchEdgeMethodInferenceAndAbortTest`).
  >
  > **Verification summary:**
  > - `MatchExecutionPlannerMutationTest`: 126 tests green (124 from
  >   Step 1 + 2 new MAX_VALUE-preservation tests added in the review
  >   fix).
  > - `MatchEdgeMethodInferenceAndAbortTest`: 5/5 green.
  > - `MatchEdgeMethodPreFilterTest`: 13/13 green.
  > - `EstimateEdgeCostTest`: 157/157 green (regression guard for the
  >   extracted applyClassSelectivity body).
  > - `MatchStatementExecutionTest`: 146/146 green (4 skipped as
  >   baseline — same as pre-change).
  >
  > **Review fixes applied in `4f44518f7f`:** Step-level review returned
  > 0 blockers, 2 should-fix (CQ-1 stale Javadoc, TC-1 chain-shape
  > anchor), 1 promoted-from-suggestion (TC-2 MAX_VALUE preservation),
  > and 1 trivial CQ-2 readability cleanup.
  > - CQ-1: Javadoc of `testVertexClassInferenceEnablesIndexIntersection`
  >   now names the ordering assertion as part of the contract.
  > - TC-1: added `assertTrue(query.contains(".outE('VIHasTag').inV()"))`
  >   pre-check so a copy-paste regression of the query to `.out(X)`
  >   fails the test instead of silently bypassing the fold.
  > - TC-2: added two unit tests in `MatchExecutionPlannerMutationTest`
  >   (`applyTargetSelectivity_classForced_maxValueInputPreservedOnNullClass`
  >   and `applyTargetSelectivity_classForced_maxValueInputPreservedOnNoFilterNoEstimate`)
  >   that pin MAX_VALUE → MAX_VALUE through the 6-arg overload's
  >   short-circuit paths — guards the sort-loop's stable-sort tiebreaker
  >   against an accidental weakening of the null-class guard.
  > - CQ-2: extracted `var target = chain.get();` to avoid the repeated
  >   `Optional.get()` after `isPresent()` pattern inside the sort loop.
  >
  > **Suggestions deferred (noted, not fixed):**
  > - CQ-3/4: comment tightening — subjective, out of scope.
  > - BC-1: explicit self-loop rejection in `resolveChainedTarget`
  >   (Track 1 file, defensive). The self-loop case is already
  >   incidentally blocked by the `inv/outv/bothv` method-name whitelist
  >   (a self-loop's single out-edge is the original edge whose method
  >   is `outE/inE/bothE`, which rejects). Noting as a future defense-
  >   in-depth improvement.
  > - BC-2: doc tightening — purely clarification, deferred.
  > - TB-1/2/3: test-message phrasing — below should-fix threshold.
  > - TC-3: `cost == 0.0` visited-neighbor preservation through the
  >   fold site — Track 3's fragment-join test is the natural home for
  >   this invariant, not Step 2.
  >
  > **Cross-track impact:** None. Track 3's six regression scenarios
  > (direction-matrix: outE/inE/bothE variants, mixed-style, intermediate
  > filter, fragment-join negative case) are designed precisely to
  > validate the behavioural change Step 2 introduces. The chain-fold
  > call-site implementation matches the plan's acceptance criteria
  > exactly — Track 3 can execute against it as specified.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java`
  >   (modified) — +22 lines: chain-detection call after existing
  >   applyTargetSelectivity, inside the `cost < MAX_VALUE` gate, before
  >   applyDepthMultiplier.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchEdgeMethodInferenceAndAbortTest.java`
  >   (modified) — +24 lines: selective-before-broad ordering assertion,
  >   query-shape anchor assertion, Javadoc update.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlannerMutationTest.java`
  >   (modified) — +37 lines: two MAX_VALUE-preservation tests for the
  >   6-arg overload.
  > **Goal:** Add the second, chain-aware `applyTargetSelectivity` call
  > immediately after the existing one at
  > `MatchExecutionPlanner.java:2122-2124`, gated on
  > `resolveChainedTarget` returning non-empty. Update the existing
  > `testVertexClassInferenceEnablesIndexIntersection` to assert
  > selective-before-broad ordering. Document the grep sweep in the
  > step episode.
  >
  > **Files (expected 2-4):**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java`
  >   — add chain-aware call at the sort-loop integration point.
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchEdgeMethodInferenceAndAbortTest.java`
  >   — update `testVertexClassInferenceEnablesIndexIntersection`
  >   (:183-274) to assert `selectivePos < broadPos`.
  > - Any additional tests flagged by the grep sweep (expected: none
  >   beyond the target test — sweep is defensive).
  >
  > **Changes:**
  > 1. At the sort-loop finite-cost `else` block (currently at
  >    `MatchExecutionPlanner.java:2118-2126`), keep the existing
  >    `applyTargetSelectivity(cost, neighbor.alias, entry.getKey(),
  >    entry.getValue(), aliasClasses, aliasFilters, estimatedRootEntries,
  >    session)` call unchanged. Immediately AFTER it (and BEFORE
  >    `applyDepthMultiplier`), add:
  >    ```java
  >    // Edge-method chain detection: when the current edge is the first
  >    // hop of an `outE→inV` / `inE→outV` / `bothE→bothV` sequence,
  >    // fold the downstream vertex's WHERE selectivity into the first
  >    // edge's cost. The intermediate edge alias carries no vertex
  >    // WHERE, so without this fold the branch sorts as "no selectivity"
  >    // and loses to broader branches. This is the second factor of
  >    // Design Record D3's independence multiplication; the first
  >    // factor (the intermediate alias's own filter) was applied by
  >    // the preceding applyTargetSelectivity call. Multiplication
  >    // commutes and each call short-circuits to baseCost when its
  >    // alias has no filter/class/row estimate.
  >    var chain = resolveChainedTarget(
  >        entry.getKey(), neighbor, visitedEdges, aliasClasses, session);
  >    if (chain.isPresent()) {
  >      cost = applyTargetSelectivity(
  >          cost,
  >          chain.get().effectiveTargetAlias(),
  >          chain.get().effectiveTargetClass(),
  >          aliasFilters,
  >          estimatedRootEntries,
  >          session);
  >    }
  >    ```
  >    Placement is INSIDE the existing `if (cost < Double.MAX_VALUE)`
  >    block at line 2121, AFTER the existing `applyTargetSelectivity`
  >    call and BEFORE `applyDepthMultiplier` (Findings T2, T5). This
  >    ensures: (a) the `cost = 0.0` path at line 2116 for already-
  >    visited neighbors is unaffected; (b) the `MAX_VALUE` preservation
  >    invariant holds (chain fold is skipped on unestimated edges);
  >    (c) `applyDepthMultiplier` sees the fully folded cost.
  > 2. Update `testVertexClassInferenceEnablesIndexIntersection`
  >    (`MatchEdgeMethodInferenceAndAbortTest.java:183`) to add a
  >    `selectivePos < broadPos` assertion, mirroring
  >    `testSelectivityInferredFromEdgeSchemaWithoutExplicitClass`
  >    (`MatchStatementExecutionTest.java:4759-4767`). Keep the
  >    pre-existing `contains("(intersection: index VITag_name)")`
  >    assertion — it must continue to hold (Finding T6).
  > 3. Run the two grep sweeps from the plan's Pre-merge verification:
  >    - `grep -rn '{selectiveTag}\|{broadTag}' core/src/test`
  >    - `grep -rn 'executionPlanAsString' core/src/test`
  >    For each hit, record a one-line verdict in the step episode
  >    ("uses `.out` — unaffected" / "uses `.outE.inV` — unaffected
  >    because X" / "uses `.outE.inV` with ordering assertion —
  >    updated") per Finding R3.
  > 4. Explicitly run and verify `testEdgeAliasSchedulingOrder`
  >    (`MatchEdgeMethodInferenceAndAbortTest.java:70`): its
  >    `workEdgePos < tagPos` assertion must still pass because the
  >    intermediate alias `workEdge`'s `workFrom = 2015` filter is still
  >    applied by the preserved first call (Finding R1). If it flips,
  >    that signals a bug in the fold implementation — stop and
  >    investigate, do not weaken the assertion.
  >
  > **Verification:**
  > - `./mvnw -pl core spotless:apply` clean.
  > - `./mvnw -pl core test -Dtest=MatchEdgeMethodInferenceAndAbortTest`
  >   green — `testVertexClassInferenceEnablesIndexIntersection` now
  >   asserts the flipped-to-correct ordering; `testEdgeAliasSchedulingOrder`
  >   still green with unchanged assertion.
  > - `./mvnw -pl core test -Dtest=MatchStatementExecutionTest` green
  >   (no unexpected regressions from broader MATCH tests).
  > - `./mvnw -pl core clean test` green (full unit test suite; catches
  >   any test regression the grep sweep missed).
  > - Coverage of changed lines via `coverage-gate.py`.
  >
  > **Acceptance (from plan):**
  > - `testVertexClassInferenceEnablesIndexIntersection` asserts
  >   selective-before-broad ordering after the fix.
  > - `testEdgeAliasSchedulingOrder` passes unchanged (proves the
  >   intermediate alias filter contribution is preserved).
  > - Pre-merge grep sweep documented in the step episode with per-hit
  >   verdicts.
  > - No MATCH-related test regressions in the full `./mvnw -pl core
  >   clean test` run.
  >
  > **Notes:**
  > - Track 3's new regression tests depend on this step landing. The
  >   scope of this step is strictly the call-site wiring + the single
  >   existing-test update + grep-sweep documentation; Track 3 will
  >   add the six regression tests covering the direction-variant
  >   matrix.
  > - Rollback (Finding R5): this step is planner-only. Reverting the
  >   commit is sufficient; `resolveChainedTarget` helper becomes
  >   dormant but unused.
