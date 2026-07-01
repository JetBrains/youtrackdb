<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: DEFERRED-ACCEPTED}
  - {id: R5, verdict: DEFERRED-ACCEPTED}
  - {id: R6, verdict: DEFERRED-ACCEPTED}
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: DEFERRED-ACCEPTED}
  - {id: A5, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->
<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 2 — Phase A review gate-verification (iteration 2)

Both iteration-1 blockers (T1 / A1, the same recogniser-key contradiction seen
through the technical and adversarial lenses) are resolved in the edited
decomposition, and every should-fix landed a concrete step or Decision Log
edit. The gate PASSES: no blocker is STILL OPEN. The three risk suggestions
(R4/R5/R6) and the two adversarial suggestions (A4 folded into A5) are
documented deferrals or already covered by the reconciled Context / step text.
No new finding surfaced during verification.

The T1 / A1 fix rests on a code fact re-confirmed this session: `new
YTDBGraphStep` occurs only at `YTDBGraphStepStrategy.java:114`, so under D4's
translator-first ordering the translator sees a plain `GraphStep`. Gating on
`GraphStep` (a supertype of `YTDBGraphStep`, confirmed at
`YTDBGraphStep.java:34`) is both correct and ordering-robust. `isPolymorphic`
is `@Nullable Boolean`, null on no-graph (`YTDBStrategyUtil.java:29-37`),
confirming the T3 null-decline fix is warranted; `SelectExecutionPlan.copy`
exists (`SelectExecutionPlan.java:238`), confirming R1's `plan.copy(ctx)` is
achievable.

**Tooling note.** mcp-steroid PSI was not exercised this session; the reference
questions are class-instantiation-site counts and single-signature lookups
(sole `new YTDBGraphStep`, `isPolymorphic` return type, `copy(ctx)` existence),
not polymorphic-dispatch resolution, so `grep` over `core/src/main` is
authoritative for each. Caveat carried from iteration 1.

## Verifications

#### Verify T1 / A1: recogniser key on `YTDBGraphStep` vs plain `GraphStep` (BLOCKER)
- **Original issue**: the Decision Log `simplification` entry gated the start
  step on `stepIndex == 0 && step instanceof YTDBGraphStep`, and D9 keyed the
  registry on that class. Under D4 the translator runs before
  `YTDBGraphStepStrategy` — the sole producer of `YTDBGraphStep` — so at
  translator time the start step is a plain `GraphStep`; gating on
  `YTDBGraphStep` declines every recognized shape and the track translates
  nothing.
- **Fix applied**: the `simplification` Decision Log entry now reads
  `StartStep gates on stepIndex == 0 && step instanceof GraphStep`
  (track-2.md:40) — `YTDBGraphStep` is gone from it. A dedicated
  `review-resolution (T1 / A1, blocker)` entry (track-2.md:43-50) states the
  recogniser gates and the D9 registry key on the plain TinkerPop `GraphStep`,
  explains the D4-ordering rationale, and notes gating on `GraphStep` is
  ordering-robust because a `YTDBGraphStep` is a `GraphStep`. Concrete Step 4
  (track-2.md:116) pins the gate/key on "the plain TinkerPop `GraphStep` (not
  `YTDBGraphStep`, which `YTDBGraphStepStrategy` produces only after the
  translator runs; T1, A1)".
- **Re-check**:
  - Location: track-2.md Decision Log lines 36-50; Concrete Step 4 line 116.
  - Current state: no remaining `instanceof YTDBGraphStep` in any gating clause;
    both the simplification entry and the new resolution entry, plus Step 4,
    agree on plain `GraphStep`. This is A1 proposed-fix option (a), which the
    reviews called "the ordering the design actually argues for" — consistent
    with immutable D4 (plan lines 155-165: translator first, half-measures list
    it in their `applyPrior()`), so no DR revision is forced.
  - Criteria met: the recognize branch is now reachable for `g.V()` /
    `g.V(ids)`; the track can deliver observable behavior. Step 5 adds a
    translator-on-vs-off parity smoke test so the fix cannot silently regress.
  - Code fact re-confirmed: `new YTDBGraphStep` is the sole construction site at
    `YTDBGraphStepStrategy.java:114`; `YTDBGraphStep extends GraphStep`
    (`YTDBGraphStep.java:34`).
- **Regression check**: checked D4 (plan) and the Plan of Work — translator-first
  ordering is unchanged; the fix changes the class name only, not the ordering.
  Clean.
- **Verdict**: VERIFIED

#### Verify T2: Step 5 spells out the three distinct registration / applyPrior edits
- **Original issue**: "add `GremlinToMatchStrategy` to each `applyPrior()`"
  understated the edit — `YTDBGraphStepStrategy` has no `applyPrior()` (must be
  created), while the other two must be widened; the registration list has five
  strategies, not three.
- **Fix applied**: Concrete Step 5 (track-2.md:117) now reads "wire D4 ordering
  as three distinct edits — create an `applyPrior` on `YTDBGraphStepStrategy`
  (it has none), widen `YTDBGraphCountStrategy`'s and
  `YTDBGraphMatchStepStrategy`'s (T2)" and separately "Register
  `GremlinToMatchStrategy` in `registerOptimizationStrategies`".
- **Re-check**:
  - Location: Concrete Step 5, line 117.
  - Current state: the three edits are distinguished (create one, widen two) and
    registration is called out as a separate action.
  - Code fact re-confirmed: `YTDBGraphCountStrategy` and
    `YTDBGraphMatchStepStrategy` each declare `applyPrior()`;
    `YTDBGraphStepStrategy` declares none — matching the create-vs-widen split.
- **Regression check**: the two untouched strategies (`YTDBGraphIoStepStrategy`,
  `YTDBQueryMetricsStrategy`) are not named for edits, consistent with iter-1's
  guidance not to touch them. Clean.
- **Verdict**: VERIFIED

#### Verify T3: Step 4 declines on null `isPolymorphic`
- **Original issue**: the Plan of Work pinned `polymorphic` from
  `isPolymorphic` without addressing the null (no-graph) return — an unbox NPE
  or a divergent default.
- **Fix applied**: Concrete Step 4 (track-2.md:116) adds "declining on a null
  `isPolymorphic`" to the `StartStepRecogniser` behavior.
- **Re-check**:
  - Location: Concrete Step 4, line 116.
  - Current state: the recogniser declines the traversal when `isPolymorphic`
    returns null, mirroring `YTDBGraphStepStrategy`'s early-return; no unbox on a
    null.
  - Code fact re-confirmed: `isPolymorphic` is `@Nullable Boolean`, returns null
    when the graph is inaccessible (`YTDBStrategyUtil.java:29-37`).
- **Regression check**: decline path honours the no-mutation-on-decline
  invariant (D3); no new issue. Clean.
- **Verdict**: VERIFIED

#### Verify T4 / R2: Step 1 mutable-copies alias maps + `useCache=false` null-statement path
- **Original issue**: "full defensive copies" phrasing risked passing an
  immutable / passthrough `aliasFilters` (the planner mutates it via
  `detectNotInAntiJoin`), missing the three `final` fields
  `groupBy`/`orderBy`/`unwind`, and the null inherited `statement` could NPE if
  the cache branch ran.
- **Fix applied**: Concrete Step 1 (track-2.md:113) now states "mutable
  defensive copies of `aliasFilters` (the planner mutates it via
  `detectNotInAntiJoin`), `aliasRids`, and `aliasClasses`, the final `groupBy` /
  `orderBy` / `unwind` fields assigned, and a `useCache=false` path so the null
  inherited `statement` never reaches the cache (D2; T4, R2)".
- **Re-check**:
  - Location: Concrete Step 1, line 113.
  - Current state: the three alias maps are named as mutable copies (not
    passthrough / not `Map.of()`), the three `final` fields are assigned, and
    the `useCache=false` guard for the null `statement` is explicit — covering
    both T4 (compile / immutable-map trap) and R2 (null-`statement` NPE trap).
  - Code fact (iter-1 P8 / E2): the two `statement` dereferences are guarded by
    `useCache &&`, so `useCache=false` short-circuits before the null field is
    touched. The fix pins the exact condition the deferral rests on.
- **Regression check**: the three existing ctors are still untouched (D2
  additive-only). Clean.
- **Verdict**: VERIFIED

#### Verify R1: boundary `clone()` copies the plan (`plan.copy(ctx)`)
- **Original issue**: the design justified `clone()` sharing one
  `SelectExecutionPlan` across original and clone; the plan class's own contract
  requires `copy(ctx)` before each execution for thread safety, so sharing is
  unsafe under parallel / nested reuse.
- **Fix applied**: Concrete Step 2 (track-2.md:114) now reads "`clone()` that
  copies the plan per execution (`plan.copy(ctx)`, not a shared instance —
  `SelectExecutionPlan` thread-safety contract; R1)". A `review-resolution (R1)`
  Decision Log entry (track-2.md:51-55) records the copy, cites the mirror
  (`HashJoinMatchStep`), and notes the design's "fresh stream makes sharing
  safe" claim is corrected in Phase 4.
- **Re-check**:
  - Location: Concrete Step 2, line 114; Decision Log lines 51-55.
  - Current state: the boundary copies the plan per execution instead of sharing
    it; the R1 clone-independence test is the natural coverage.
  - Code fact re-confirmed: `SelectExecutionPlan.copy(CommandContext)` exists at
    `SelectExecutionPlan.java:238` — the fix is achievable.
- **Regression check**: the residual stale "clone() shares the plan" phrasing in
  the Plan of Work (line 109) is superseded by the authoritative Concrete Step 2
  and Decision Log, matching the track's documented Plan-of-Work-keeps-original,
  Concrete-Steps-authoritative deviation policy. Not a contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R3: throw-safety net moved into Step 3 (not Step 5)
- **Original issue**: the strategy runs on every Gremlin compilation; the
  throw-safety catch-all was scheduled in Step 5, two steps after the walker
  (Step 4) runs under the strategy — leaving a window where a recogniser throw
  breaks all Gremlin.
- **Fix applied**: Concrete Step 3 (track-2.md:115) now lands the strategy "with
  its throw-safety net in place from the start (a recogniser throw must not
  break native Gremlin; R3)". A `review-sequencing (R3)` Decision Log entry
  (track-2.md:57-59) records the move.
- **Re-check**:
  - Location: Concrete Step 3, line 115; Decision Log lines 57-59.
  - Current state: the net lands with the skeleton in Step 3, before the walker
    (Step 4) runs under the strategy. The invariant "a throw in `apply()` can
    only decline, never break a query" holds from the first moment the strategy
    is live.
- **Regression check**: idempotency scan (D7) and kill-switch knob remain in
  Step 3 alongside the net. Clean.
- **Verdict**: VERIFIED

#### Verify A5: Context reconciled — Track 2 wires the boundary and returns results
- **Original issue**: the track's Context / design carried a "Track 3 wires the
  first boundary" statement while the Validation section asserted translate-and-
  return parity — a contradiction over whether the boundary emits in Track 2.
- **Fix applied**: the Context section (track-2.md:86) now states "Track 2 wires
  the boundary end to end: a recognized `g.V()` runs through the planner and the
  boundary emits vertices, so translator-on and translator-off return the same
  multiset (this is why the Validation section asserts translate-and-return
  parity)."
- **Re-check**:
  - Location: Context and Orientation, line 86.
  - Current state: the boundary is wired and emits in Track 2; the parity
    acceptance lines (Validation 123) belong here, resolving the tension A5
    flagged. No residual "Track 3 wires the boundary" sentence remains in the
    track file (the only Track-3 mentions are the anon-generator / class-keyed-
    map deferrals and the chain-target `polymorphic` use, none of which claim
    Track 3 first wires the boundary).
- **Regression check**: Validation lines still assert translate-and-return
  parity; now consistent with the Context. Clean.
- **Verdict**: VERIFIED

#### Verify A2: D9 registry key names the concrete `GraphStep` class (should-fix, coupled to A1)
- **Original issue**: D9's `getClass()`-keyed dispatch must key on the exact
  concrete class `g.V()` presents at translator time; the list-with-`instanceof`
  simplification masked the key-identity question and risked carrying
  `YTDBGraphStep.class` into Track 3.
- **Fix applied**: resolved jointly with A1 — the `review-resolution (T1 / A1)`
  Decision Log entry (track-2.md:43-50) states "the recogniser gates and the D9
  registry key on the plain TinkerPop `GraphStep`", and Step 5's smoke tests
  verify the fix empirically.
- **Re-check**: the key is now stated as `GraphStep`, and the parity smoke test
  (Step 5) exercises `map.get(startStep.getClass())` reaching a non-null
  recogniser for a bare `g.V()`. The Track-3 class-keyed-map restoration
  inherits the correct key.
- **Regression check**: consistent with A1's ordering fix. Clean.
- **Verdict**: VERIFIED

#### Verify A3 / A4 / R4 / R5 / R6: suggestions and coupled items
- **A3 (should-fix, design/track `useCache` disagreement)**: the track side is
  resolved — Step 1 pins `useCache=false` for the additive-ctor path (T4/R2
  fix). A3's option (a) also asks to correct the design §Workflow diagram's
  `useCache=true`; that is a design-file edit the track's Outcomes section
  routes to Phase 4 documentation reconciliation. The track-file obligation is
  met; the design correction is a Phase-4 deferral, not a track-file gap.
  Verdict folded as VERIFIED at the track level.
- **A4 (suggestion, `g.V(ids)` uses WHERE @rid IN, not the single-RID seek)**:
  the Context section (track-2.md:86) already spells out the split — `g.V(id)` →
  `aliasRids[boundary] = SQLRid(id)` (RID seek); `g.V(id1, id2, …)` →
  `aliasFilters[boundary] = WHERE @rid IN [...]` (filter). The distinction A4
  asked to clarify is present. DEFERRED-ACCEPTED (doc-clarity, no code change;
  the finding itself was survives-as-correct).
- **R4 (suggestion, projection double-append forward-risk)**: no Track-2 code
  path (no projection recogniser until Track 5); a documentation guard for
  future tracks. DEFERRED-ACCEPTED — no Track-2 obligation.
- **R5 (suggestion, abandoned-iteration stream leak)**: an inherited MATCH risk
  class; Concrete Step 2 already lists `AutoCloseable` close on exhaustion /
  exception / abandonment. DEFERRED-ACCEPTED as an inherited risk with the
  close-path test folded into Step 2 coverage.
- **R6 (suggestion, deferred `GremlinPlanCache` per-execution re-plan cost)**:
  the deferral is documented (Decision Log scope-down); Step 5 adds a
  translator-on-vs-off timing sanity check to catch a gross regression before
  the cache lands. DEFERRED-ACCEPTED.
- **Verdict**: A3 VERIFIED (track-level); A4 / R4 / R5 / R6 DEFERRED-ACCEPTED.

## Findings

<!-- No new findings surfaced during verification. -->

## Summary

PASS — 0 STILL OPEN. Both blockers (T1 / A1) VERIFIED; all should-fix
(T2/T3/T4, R1/R2/R3, A2/A3) VERIFIED; suggestions (R4/R5/R6, A4) DEFERRED-
ACCEPTED. No new findings.
