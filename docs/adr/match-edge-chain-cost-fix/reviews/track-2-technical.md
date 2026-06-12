# Track 2 Technical Review — Iteration 1

## Part 1: Evidence Certificates

#### Premise: Sort loop at `MatchExecutionPlanner.java:2108-2133` is the correct integration point
- **Track claim**: Sort loop at 2108-2133 is the integration point; `neighbor` is computed at 2113-2114 as `entry.getValue() ? entry.getKey().in : entry.getKey().out`.
- **Search performed**: Direct read of MatchExecutionPlanner.java lines 2080-2135.
- **Code location**: `MatchExecutionPlanner.java:2113-2114`.
- **Actual behavior**: Line 2113 iterates `sortedEdges`; line 2114: `var neighbor = entry.getValue() ? entry.getKey().in : entry.getKey().out;`. Line 2119 calls `estimateEdgeCost`; line 2121 guards on `cost < Double.MAX_VALUE`; lines 2122-2124 call `applyTargetSelectivity`; line 2125 calls `applyDepthMultiplier`.
- **Verdict**: CONFIRMED.
- **Detail**: The line numbers match exactly, and the call shape is as described. The `neighbor` variable is available to a chain-aware helper.

#### Premise: `estimateEdgeCost` returns `Double.MAX_VALUE` on unknown direction; chain fold must preserve this
- **Track claim**: `estimateEdgeCost` returns `Double.MAX_VALUE` on failure; the chain fold must respect the existing `cost < Double.MAX_VALUE` gate.
- **Search performed**: Read of `estimateEdgeCost` body at MatchExecutionPlanner.java:2292-2340.
- **Code location**: `MatchExecutionPlanner.java:2299-2306`.
- **Actual behavior**: Returns `Double.MAX_VALUE` when `method == null` or when `parseDirection(method.getMethodNameString()) == null`. Line 2121's `if (cost < Double.MAX_VALUE)` gate short-circuits both `applyTargetSelectivity` and `applyDepthMultiplier`.
- **Verdict**: CONFIRMED.
- **Detail**: Track 2 preserves this gate by only wiring the chain fold inside the existing guarded block — the MAX_VALUE preservation invariant is upheld naturally.

#### Premise: `resolveChainedTarget(edge, neighbor, visitedEdges, aliasClasses, session)` exists with the claimed signature
- **Track claim**: Helper exists with that exact signature around line 2613.
- **Search performed**: Read of MatchExecutionPlanner.java lines 2613-2683.
- **Code location**: `MatchExecutionPlanner.java:2613-2618`.
- **Actual behavior**:
  ```java
  static Optional<ChainedTarget> resolveChainedTarget(
      PatternEdge edge,
      PatternNode neighbor,
      Set<PatternEdge> visitedEdges,
      @Nullable Map<String, String> aliasClasses,
      @Nullable DatabaseSessionEmbedded session) { ... }
  ```
- **Verdict**: CONFIRMED.
- **Detail**: Package-private static, returns `Optional<ChainedTarget>`, accepts nullable `aliasClasses` and `session`. Matches the plan's wiring contract exactly. `ChainedTarget` record at lines 2555-2557 has `(String effectiveTargetAlias, @Nullable String effectiveTargetClass)`.

#### Premise: The new overload signature is feasible — `applyTargetSelectivity` body from line 2475 onward doesn't depend on `edge` or `isOutbound`
- **Track claim**: Body below the `resolveTargetClass` call does not depend on `edge` or `isOutbound`; extractable.
- **Search performed**: Read of `applyTargetSelectivity` at MatchExecutionPlanner.java:2461-2502.
- **Code location**: `MatchExecutionPlanner.java:2470-2502`.
- **Actual behavior**: Lines 2470-2471 call `resolveTargetClass(targetAlias, edge, isOutbound, aliasClasses, session)` — that is the ONLY use of `edge` and `isOutbound` in the body. From line 2476 onward, the code only uses `targetClass`, `targetAlias`, `schema`, `session`, `aliasFilters`, `estimatedRootEntries`. Factoring the body below line 2476 into a shared private helper is trivial.
- **Verdict**: CONFIRMED.
- **Detail**: The overload split is clean. The claim that `isOutbound` is "dead once the class is known" is accurate.

#### Premise: Intermediate alias's own `WHERE` is already factored into `baseCost` by the sort-loop logic
- **Track claim**: "The intermediate's filter is already factored into `baseCost` by the existing per-edge sort-loop logic: the cost-estimation path for each candidate already calls `applyTargetSelectivity` on the intermediate alias as part of building `baseCost`."
- **Search performed**: Read of the sort loop at MatchExecutionPlanner.java:2112-2128 and `estimateEdgeCost` at 2292-2340.
- **Code location**: `MatchExecutionPlanner.java:2119-2125`.
- **Actual behavior**: Line 2119 sets `cost = estimateEdgeCost(...)`. `estimateEdgeCost` (lines 2292-2340) does NOT call `applyTargetSelectivity`; it returns `CostModel.edgeTraversalCost(sourceRows, fanOut)` with no filter factor. The filter factor is applied in the SEPARATE call at line 2122: `cost = applyTargetSelectivity(cost, neighbor.alias, entry.getKey(), entry.getValue(), ...)`.
- **Verdict**: **WRONG**.
- **Detail**: This is a significant factual error in the plan. `baseCost` (the output of `estimateEdgeCost`) does NOT contain the intermediate's filter selectivity — it is pure `sourceRows × fanOut`. The intermediate's filter is applied by the existing `applyTargetSelectivity` call that Track 2 proposes to REPLACE with a chain-aware call using the downstream alias. If the call-site integration simply swaps the target alias to the downstream one (as the plan describes), the intermediate alias's filter (e.g., `workEdge.where: workFrom = 2015`) will never be multiplied in. See Edge case trace below.

#### Premise: Test at `MatchEdgeMethodInferenceAndAbortTest.java:183` currently asserts selectivity without ordering
- **Track claim**: `testVertexClassInferenceEnablesIndexIntersection` currently asserts `{selectiveTag}` and `{broadTag}` both appear in the plan but does NOT assert ordering.
- **Search performed**: Read of the test at MatchEdgeMethodInferenceAndAbortTest.java:183-274.
- **Code location**: `MatchEdgeMethodInferenceAndAbortTest.java:258-263` and 268-271.
- **Actual behavior**: Assertions are (a) `plan.contains("{selectiveTag}")`, (b) `plan.contains("{broadTag}")`, (c) `plan.contains("(intersection: index VITag_name)")`. No `indexOf`/ordering assertion.
- **Verdict**: CONFIRMED.
- **Detail**: The proposed update to add `selectivePos < broadPos` mirrors the pattern at `MatchStatementExecutionTest.java:4689-4696` (single `.out` variant) and 4759-4767 (edge-schema inference variant).

#### Premise: Ordering-assertion convention exists at `MatchStatementExecutionTest.java:4708`
- **Track claim**: Convention asserts `selectivePos < broadPos` using `String.indexOf`.
- **Search performed**: Read of MatchStatementExecutionTest.java:4708-4770.
- **Code location**: `MatchStatementExecutionTest.java:4759-4767`.
- **Actual behavior**: Uses `plan.indexOf("{selectiveTag}")` and `plan.indexOf("{broadTag}")`, then `assertTrue(..., selectivePos < broadPos)`. Same pattern earlier at lines 4689-4696 in `testSelectivityInferredFromEdgeSchema`.
- **Verdict**: CONFIRMED.

#### Premise: All current `applyTargetSelectivity` callers use the 8-arg signature
- **Track claim**: Only the sort-loop call site uses `applyTargetSelectivity` in production code; adding an overload will not break callers.
- **Search performed**: `grep -rn "applyTargetSelectivity" core/src/`.
- **Code location**: Production: only `MatchExecutionPlanner.java:2122`. Tests: `EstimateEdgeCostTest.java` (19 call sites, all using the 8-arg signature).
- **Actual behavior**: No other production caller; no test caller uses a shorter overload signature.
- **Verdict**: CONFIRMED.
- **Detail**: Adding a new overload is safe. No existing caller is at risk.

#### Premise: `@Nullable` import and `DatabaseSessionEmbedded` / `SQLWhereClause` types available
- **Track claim**: Overload signature uses `@Nullable`, `SQLWhereClause`, `DatabaseSessionEmbedded` — all already imported.
- **Search performed**: Read of imports at MatchExecutionPlanner.java:1-80.
- **Code location**: Lines 63, 7, 79.
- **Actual behavior**: `javax.annotation.Nullable`, `DatabaseSessionEmbedded`, `SQLWhereClause` are all imported.
- **Verdict**: CONFIRMED.

#### Premise: Grep sweep for `{selectiveTag}|{broadTag}` — identify all at-risk tests
- **Track claim**: Sweep should catch any test using `String.indexOf` on the markers; new ordering may regress.
- **Search performed**: `grep -rn '{selectiveTag}\|{broadTag}' core/src/test/`.
- **Code location**:
  - `MatchEdgeMethodInferenceAndAbortTest.java:260, 263` — `contains()` checks only, no ordering.
  - `MatchStatementExecutionTest.java:4689-4690, 4759-4760` — both use `indexOf` + `selectivePos < broadPos` ordering assertions.
- **Actual behavior**: Only two tests use ordering assertions, and both already assert **selective-before-broad** using direct `.out` patterns. Track 2's change promotes the same ordering for the edge-method pattern — consistent with existing assertions.
- **Verdict**: CONFIRMED.

#### Premise: `executionPlanAsString` sweep — identify other ordering-sensitive tests
- **Track claim**: Sweep should surface any plan-string assertions that may observe edge ordering.
- **Search performed**: `grep -rn 'executionPlanAsString' core/src/test/` (99 hits) combined with `grep -rn 'indexOf' ... sql/executor/`.
- **Code location**: Ordering-sensitive hits beyond `{selectiveTag}/{broadTag}`:
  - `MatchEdgeMethodInferenceAndAbortTest.java:142-149` — `testEdgeAliasSchedulingOrder`: `workEdgePos < tagPos` where `workEdge` is the intermediate alias of an `outE(...){where: workFrom=2015}.inV()` chain, tested against `.out('SOHasTag')`. **High risk: see Edge case below.**
  - `MatchStatementExecutionTest.java:4864-4871` — `directClassPos < matchedClassPos`: a WHILE edge test, not an edge-method chain. Not at risk.
- **Actual behavior**: The `testEdgeAliasSchedulingOrder` test is the load-bearing regression risk. With Track 2 as currently drafted, `workEdge`'s filter would no longer be applied to the first edge's cost (see Edge case), potentially reversing the order and breaking the test.
- **Verdict**: PARTIAL — sweep is correct, but Track 2's pre-merge step must also review `testEdgeAliasSchedulingOrder`.

#### Edge case: Intermediate alias has a WHERE but downstream vertex has none (e.g., `testEdgeAliasSchedulingOrder`)
- **Trigger**: Query shape `outE('X'){as: e, where: weight > 5}.inV(){as: v}` with no WHERE on `v` and no explicit class, compared against another branch with neither WHERE.
- **Code path trace (current behavior, pre-Track-2)**:
  1. Sort loop at line 2113 iterates candidate edges.
  2. For the `outE` edge: `cost = estimateEdgeCost(...)` = sourceRows × fanOut. No filter applied yet.
  3. Line 2122: `applyTargetSelectivity(cost, "workEdge", edge, true, ...)`. `resolveTargetClass` returns the edge-class name or null; `aliasFilters.get("workEdge")` returns the `workFrom = 2015` filter; **filter selectivity multiplier is applied** → cost drops.
  4. Cost is low; edge is scheduled first.
- **Code path trace (proposed Track 2 behavior)**:
  1. Sort loop iterates. For the `outE` edge: `cost = estimateEdgeCost(...)` = sourceRows × fanOut.
  2. `resolveChainedTarget(edge, neighbor, ...)` matches → returns `ChainedTarget(effectiveTargetAlias="company", effectiveTargetClass="SOCompany")`.
  3. Call-site swaps to: `applyTargetSelectivity(cost, "company", "SOCompany", aliasFilters, rootEntries, session)`.
  4. `aliasFilters.get("company")` = null (no WHERE on `company`). `estimatedRootEntries.get("company")` likely absent → overload returns `baseCost` unchanged.
  5. `workEdge`'s filter is NEVER applied. Cost is high.
  6. Competing `.out('SOHasTag')` edge (whose target `tag` has no filter either) has similar cost.
  7. Order flips or becomes tie-breaker (stable sort, original order) — `workEdgePos < tagPos` assertion may fail.
- **Outcome**: **Regression**. `testEdgeAliasSchedulingOrder` breaks. This is a concrete existing test, not a speculative one.
- **Track coverage**: **NO**. The plan asserts the intermediate's filter is "already factored into `baseCost`", which is false. The design D3 promises multiplication of intermediate × downstream, but Track 2's wiring only applies downstream.

#### Edge case: `preResolvedTargetClass == null` (e.g., `bothE→bothV` with no `class:` annotation)
- **Trigger**: `bothE('X').bothV(){where: p}` where `p`'s alias has no explicit `class:` — `resolveChainedTarget` returns `ChainedTarget(alias, null)`.
- **Code path trace**:
  1. Sort loop: chain matches. `ChainedTarget.effectiveTargetClass == null`.
  2. New overload: `preResolvedTargetClass == null` → returns `baseCost` unchanged.
  3. Cost = baseCost (no filter multiplier applied).
- **Outcome**: No-op. Correctly matches the non-chain behavior when class cannot be resolved.
- **Track coverage**: YES — plan line 108 explicitly covers this, and Track 3 test 4 tests the `class:`-annotated variant.

#### Edge case: `visitedNodes.contains(neighbor)` — already-visited neighbor gets cost 0.0
- **Trigger**: Sort loop line 2116: `if (visitedNodes.contains(neighbor)) { cost = 0.0; }`.
- **Code path trace**:
  1. The `if` branch at line 2116 produces `cost = 0.0` without calling `estimateEdgeCost` or `applyTargetSelectivity`.
  2. Track 2's chain-fold call is nested under the `else` branch, after `estimateEdgeCost`.
- **Outcome**: Chain fold does NOT execute when neighbor is visited. This is correct: a visited neighbor means the traversal is just a join (no fan-out), and cost 0.0 is already optimal.
- **Track coverage**: Implicitly yes — the chain fold is placed inside the `else` block alongside `applyTargetSelectivity`, so the 0.0 short-circuit is undisturbed. No explicit mention in Track 2 body; would be worth a comment in the code.

#### Edge case: Track 1's helper returns `null` for the class (e.g., `bothE→bothV` fallback, or `outE` with no schema)
- **Trigger**: `resolveChainedTarget` returns `Optional.of(ChainedTarget(alias, null))`.
- **Code path trace**:
  1. Chain matches; class is null.
  2. New overload is called with `preResolvedTargetClass == null`.
  3. Plan specifies overload short-circuits to `return baseCost` immediately.
- **Outcome**: Net no-op. Chain detected but no fold applied. Correct.
- **Track coverage**: YES — plan explicitly describes the short-circuit at lines 108-109.

#### Edge case: `estimateEdgeCost` returns `Double.MAX_VALUE` (recursive DFS at the intermediate edge alias node)
- **Trigger**: DFS recurses into the intermediate alias; the sole outgoing edge is the `inV()` hop, whose `parseDirection` returns null → `estimateEdgeCost` returns `Double.MAX_VALUE`.
- **Code path trace**:
  1. Line 2121's `if (cost < Double.MAX_VALUE)` gate is FALSE.
  2. Neither `applyTargetSelectivity` nor `applyDepthMultiplier` runs.
  3. Track 2 wires chain fold INSIDE this gate (matching existing behavior).
- **Outcome**: Chain fold does NOT execute. Cost stays at MAX_VALUE; TimSort preserves insertion order. Correct.
- **Track coverage**: YES — plan explicitly calls out this constraint at lines 49-52.

#### Integration: New `applyTargetSelectivity` overload — caller analysis
- **Plan claim**: Add a new overload; existing callers are unaffected.
- **Actual entry point**: `MatchExecutionPlanner.java:2461` (existing 8-arg overload).
- **Caller analysis**: Single production caller at line 2122. 19 test callers in `EstimateEdgeCostTest.java` — all use the 8-arg form.
- **Breaking change risk**: Zero. Java overload resolution disambiguates by type/arity — the new overload (`String, Map, Map, Session` after `baseCost, targetAlias`) differs from the existing (`PatternEdge, boolean, Map, Map, Map, Session`) in positions 3-4.
- **Verdict**: MATCHES — adding the overload is safe.

#### Integration: Sort-loop call site — chain fold semantics
- **Plan claim**: Chain-aware path replaces the existing `applyTargetSelectivity` call with the new overload using the downstream alias.
- **Actual entry point**: `MatchExecutionPlanner.java:2122-2124`.
- **Caller analysis**: The sort-loop's `applyTargetSelectivity` call uses `neighbor.alias` (= intermediate alias) today and applies the intermediate's filter. If replaced with the chain-aware call using the downstream alias, the intermediate's filter is silently dropped.
- **Breaking change risk**: **HIGH** for queries where the intermediate alias carries a WHERE (`testEdgeAliasSchedulingOrder`). See Edge case trace above.
- **Verdict**: **MISMATCHES** — the integration as described in Track 2 does not preserve the intermediate's filter contribution. Design D3 promises independence-multiplication; the implementation in Track 2's body does not achieve it.

## Part 2: Findings

### Finding T1 [blocker]
**Certificate**: "Premise: Intermediate alias's own `WHERE` is already factored into `baseCost`" (verdict: WRONG) + Edge case "Intermediate alias has a WHERE but downstream vertex has none".
**Location**: Plan file `implementation-plan.md:381-388` ("the intermediate's filter is already factored into `baseCost` by the existing per-edge sort-loop logic").
**Issue**: The plan's claim is factually incorrect. `baseCost` (the output of `estimateEdgeCost` at line 2119) contains only `sourceRows × fanOut`; it does NOT include any filter selectivity. The intermediate alias's WHERE filter is applied by the SEPARATE `applyTargetSelectivity` call at line 2122, which Track 2 proposes to REPLACE (not augment) with a chain-aware call that targets the downstream alias. As currently drafted, Track 2 will silently drop the intermediate's filter contribution from the first-edge cost.

Concrete regression: `testEdgeAliasSchedulingOrder` (MatchEdgeMethodInferenceAndAbortTest.java:70) exercises exactly the shape `outE('SOWorkAt'){as: workEdge, where: (workFrom = 2015)}.inV(){as: company}`. `workEdge`'s filter is today the only thing that makes this branch win against `.out('SOHasTag')`. After Track 2, `company` has no filter and no estimate, so the chain fold is a no-op; `workEdge`'s filter is no longer applied; the test's `workEdgePos < tagPos` assertion will likely fail.

**Proposed fix**: Apply the intermediate's filter BEFORE (or IN ADDITION TO) the chain fold. Three viable shapes, listed in preference order:

1. **(Preferred) Two-step multiplication at the call site.** Keep the existing `applyTargetSelectivity(cost, neighbor.alias, edge, isOutbound, ...)` call unchanged when a chain is detected, then follow it with the chain-aware overload on the downstream alias. Multiplication commutes and each call is a no-op when its alias has no filter/class.
   ```java
   cost = applyTargetSelectivity(
       cost, neighbor.alias, entry.getKey(), entry.getValue(), ...);
   var chain = resolveChainedTarget(entry.getKey(), neighbor, visitedEdges, aliasClasses, session);
   if (chain.isPresent()) {
     cost = applyTargetSelectivity(
         cost, chain.get().effectiveTargetAlias(),
         chain.get().effectiveTargetClass(), aliasFilters, estimatedRootEntries, session);
   }
   cost = applyDepthMultiplier(cost, entry.getKey());
   ```
   This is the minimal-risk change: it preserves today's behavior exactly when no chain matches, and adds a second selectivity multiplier when a chain matches.

2. **Have the new overload receive both aliases** and apply both filters internally. More coupling, less intuitive.

3. **Update the plan's D3 rationale and Track 2 body** to state explicitly that Track 2 adds a SECOND multiplicative call (not a replacement). Then Design D3 ("multiply intermediate × downstream") and the implementation align.

The plan's existing D3 ("Multiply intermediate `WHERE` selectivity by the downstream vertex selectivity — chosen") and the design doc's "Independence Multiplication Across Filters" section both describe option 1's behavior. The bug is only in Track 2's body text, which incorrectly attributes the intermediate factor to `baseCost`.

### Finding T2 [should-fix]
**Certificate**: Edge case "`visitedNodes.contains(neighbor)`".
**Location**: Track 2 body in `implementation-plan.md:367-389`.
**Issue**: Track 2 does not explicitly state that the chain fold must be placed inside the `else` branch at line 2118 (where the cost is estimated), i.e., NOT on the `cost = 0.0` path at line 2116. The natural reading is correct — Track 2 says "after `estimateEdgeCost` returns a finite base cost" — but an implementer might place the chain-detection before the `visitedNodes` check and accidentally call the fold for already-visited neighbors (where `neighbor.out` may be empty or reflect a different structural configuration).
**Proposed fix**: Add one sentence to Track 2 body: "The chain-fold call lives inside the existing `else` block of line 2116 (alongside `applyTargetSelectivity`) so that already-visited neighbors continue to get cost 0.0 unconditionally. The `visitedEdges` guard inside `resolveChainedTarget` is redundant for this path but harmless." Or simply add a code sketch showing the integration inside the existing `if/else`.

### Finding T3 [suggestion]
**Certificate**: Integration "New `applyTargetSelectivity` overload".
**Location**: Track 2 body, plan file `implementation-plan.md:391-407`.
**Issue**: Factoring the shared body into a private helper is wise (plan says so), but the plan doesn't name the helper or suggest its signature. Two inconsistent naming patterns in the planner today: `resolveTargetClass` (private + `@Nullable`) vs. `estimateFilterSelectivity` (package-private + static). Locking the shared helper's signature up front avoids a review loop.
**Proposed fix**: In Track 2 body, propose the shared helper explicitly. Example:
```java
private static double applyClassSelectivity(
    double baseCost,
    String targetAlias,
    String targetClass,
    Map<String, SQLWhereClause> aliasFilters,
    Map<String, Long> estimatedRootEntries,
    DatabaseSessionEmbedded session) {
  // body from line 2476 onward; `targetClass` already non-null.
}
```
Both overloads delegate to this after their own pre-processing (class resolution for the original, null-check for the new).

### Finding T4 [suggestion]
**Certificate**: "Premise: Grep sweep for `{selectiveTag}|{broadTag}`" + `executionPlanAsString` sweep.
**Location**: Track 2 body, pre-merge verification (plan file `implementation-plan.md:416-423`).
**Issue**: Track 2's pre-merge step mentions `{selectiveTag}|{broadTag}` and `executionPlanAsString` grep sweeps but doesn't call out the highest-risk existing test (`testEdgeAliasSchedulingOrder`) by name. Given that this test uses a user-named intermediate alias (`workEdge`) with its own WHERE — the exact case broken by Finding T1 as drafted — it should be flagged explicitly in Track 2's checklist.
**Proposed fix**: Add a bullet to the pre-merge verification: "Run `testEdgeAliasSchedulingOrder` (MatchEdgeMethodInferenceAndAbortTest.java:70) specifically. Its intermediate alias `workEdge` carries the selective filter; the chain fold must not drop its contribution. This test is the regression proof that T1's multiplication is correctly implemented."

### Finding T5 [suggestion]
**Certificate**: "Premise: `resolveChainedTarget` exists" + documentation context.
**Location**: Track 2 body in plan, and future implementation.
**Issue**: Track 1 populated `resolveChainedTarget` with detailed Javadoc covering the structural rule. But the call-site integration (in the sort loop, added by Track 2) will need its own short comment explaining WHY we're calling the chain-aware overload — otherwise future readers will wonder why two `applyTargetSelectivity` calls exist back-to-back (if Finding T1's fix is adopted).
**Proposed fix**: The implementation step should add a block comment at the chain-fold call site, e.g.:
```java
// Edge-method chain detection: when the current edge is the first hop of an
// `outE→inV` / `inE→outV` / `bothE→bothV` sequence, fold the downstream
// vertex's WHERE selectivity into the first edge's cost. This matches the
// cost of the single-step equivalent `.out('X'){where: p}` — the intermediate
// edge alias carries no vertex WHERE, so without this fold the branch sorts
// as "no selectivity" and loses to broader branches.
```

### Finding T6 [suggestion]
**Certificate**: "Premise: `testVertexClassInferenceEnablesIndexIntersection` currently asserts selectivity without ordering".
**Location**: `MatchEdgeMethodInferenceAndAbortTest.java:183-274`, Track 2's proposed update.
**Issue**: The existing test asserts `(intersection: index VITag_name)` appears in the plan. With the chain fold, the sort order changes, but the index intersection plan-fragment may also change its position. Track 2 only proposes adding `selectivePos < broadPos`; the pre-existing `contains("(intersection: index VITag_name)")` assertion must continue to hold — worth verifying explicitly during implementation.
**Proposed fix**: Verify at implementation time that the index-intersection assertion still passes after the ordering change. Add a note to Track 2's pre-merge checklist.

## Summary

- Blockers: 1
- Should-fix: 1
- Suggestions: 4
- Overall verdict: **ADJUST**

The Track 2 design is sound at the structural level (overload signature, integration point, `MAX_VALUE` preservation, backward compatibility) — but the body text contains a critical factual error about where the intermediate's filter is applied. As drafted, the integration would silently drop the intermediate alias's WHERE from the first-edge cost, breaking the existing `testEdgeAliasSchedulingOrder` test and contradicting Design D3.

The fix is small (adopt Finding T1's two-step multiplication at the call site) and aligns the implementation with the already-chosen design rationale (independence multiplication across filters). Once this is corrected in Track 2's body, the track is ready for decomposition into steps.
