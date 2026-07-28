<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 8 — Technical gate verification (iteration 2)

All five technical findings verify clean against the amended track file. T1's missing N-plan advance seam is resolved by re-pointing the realization: DR-U1 realizes the base's `startPlanStream()` hook as a `MultipleExecutionStream`, so no base advance seam is needed and any residual base edit is scoped conditionally. T2's understated strategy scope now names `applyTranslation` / `buildPlan` / `replaceAllStepsWithBoundary` as gaining a multi-plan branch plus the multi-plan carrier. T3's per-child parameter collision is pinned (base takes an empty map, per-child install into each child's own context, plus a `?`-slot test). T4's no-op scan sub-item is dropped ("no edit needed"). T5's cache policy is pinned to `cacheEligible=false`. No new findings.

Method: track-file text read in full; the two load-bearing code facts the amendments rest on were re-confirmed by reading YTDB source — `MultipleExecutionStream` (`sql/executor/resultset/MultipleExecutionStream.java`) is a lazy one-live-stream concatenator (one `currentStream`, closes the drained child before pulling the next from the `ExecutionStreamProducer`, an exception in `next()` never opens the next stream, `close()` closes current + producer), and the D7 scan is `step instanceof AbstractMatchPlanStep<?, ?>` (`GremlinToMatchStrategy.java:350`); `startPlanStream()` is the `protected abstract` hook at `AbstractMatchPlanStep.java:805`, called once per arming in `openArming` (`:432`). `steroid_execute_code` PSI times out in this repo (cold kotlinc > ~60s MCP limit), confirmed this session, so symbol facts rest on grep + declaration reads; the two re-check facts above are direct source reads, not reference-accuracy inferences.

#### Verify T1 (should-fix): N-plan advance seam has no base hook, base lifecycle is private
- **Original issue**: The base's advance-on-drain decision is unconditional (`processNextStart` sets `DRAINED`, releases the stream, throws) and every lifecycle primitive is `private`, so a subclass adding N-plan advance could not reuse the base machinery without a Track-7 base edit that Track 8's Interfaces listed under Out of scope. Decide the advance mechanism explicitly and reconcile scope.
- **Fix applied**: The realization was re-pointed off the Track 7 episode hand-off (the per-plan NEW/REARMED reopen) to a `MultipleExecutionStream` supplied through the existing `startPlanStream()` hook (DR-U1). One base arming spans all N plans, so no base advance seam is needed at all; row projection + list-shaping apply once over the concatenation. Landed in DR-U1, Plan of Work item 1, Context and Orientation (the `MultiPlanMatchStep` realization paragraph + mermaid), Surprises (2026-07-28 iter1 entry), the reference-accuracy note, and Interfaces (base scoped conditionally).
- **Re-check**:
  - Track-file location: `## Decision Log` DR-U1 (line 30); `## Plan of Work` item 1 (line 61); `## Context and Orientation` (lines 46, 48, mermaid line 56); `## Surprises & Discoveries` (line 20); `## Interfaces and Dependencies` (line 92).
  - Current state: the advance mechanism is now explicitly named — `startPlanStream()` returns a `MultipleExecutionStream` over an `ExecutionStreamProducer` that lazily opens each child against its own isolated context (DR-U1). Because the base drives exactly one live stream and that stream now internally concatenates N children (verified: `MultipleExecutionStream` closes the drained child before pulling the next, so the base drains once over the whole union), the "no advance seam exists" obstacle the finding raised is dissolved rather than patched. The base edit is scoped conditionally: Interfaces reads "`AbstractMatchPlanStep` **only if** decomposition finds the composite realization needs a per-child-context-threading seam," and item 1 says decomposition "names the base in scope accordingly." The reference-accuracy note (line 48) names the residual concretely (`openArming` rebinds the session once, `rowProjectionSource` captures the ctx once — technical T1 / risk R1).
  - Criteria met: advance mechanism decided explicitly (DR-U1); scope reconciled (base conditional, no longer flatly Out of scope). Phase A's obligation is to name and scope, both done. The chosen mechanism differs from the finding's proposed fix (add an advance seam / reset()-based path) but is a superior third option that needs no base advance machinery.
- **Regression check**: The re-point deliberately diverges from the Track 7 episode hand-off ("route each child-plan reopen through the NEW/REARMED branch"). This is not a regression — the divergence is documented as adversarial A3 and is a correctness fix (per-plan reopen rebuilds `shapedPayloads` per child, folding Track 9's `union().fold()` per child, a multiset violation); the single-base-arming realization pins the whole-union fold. The residual per-child-context-threading question is named and conditionally scoped, not left silent. `startPlanStream()` confirmed as the one hook the base calls per arming; the `MultipleExecutionStream` exception/close-all semantics DR-U1 relies on read exactly as the source implements. Clean.
- **Verdict**: VERIFIED

#### Verify T2 (should-fix): single-plan build/splice pipeline + TranslationResult understated in strategy scope
- **Original issue**: `applyTranslation` → `buildPlan` (one `InternalExecutionPlan`) → `replaceAllStepsWithBoundary` (`new YTDBMatchPlanStep`) and `TranslationResult` (one `MatchPlanInputs`) are single-plan end to end; the strategy's modified-scope named only recogniser registration and the (already-done) scan, so the substantive build/splice edits were unlisted.
- **Fix applied**: DR-U2 + Plan of Work item 2 + Interfaces name the multi-plan branch across `buildPlan` / `replaceAllStepsWithBoundary` / `applyTranslation` and the multi-plan carrier (`TranslationResult` field or sibling `UnionTranslationResult`, decomposition pins which).
- **Re-check**:
  - Track-file location: `## Decision Log` DR-U2 (line 31); `## Plan of Work` item 2 (line 62); `## Interfaces and Dependencies` In-scope-modified (line 92); `## Surprises` (line 24).
  - Current state: Interfaces now reads "`GremlinToMatchStrategy` (`applyTranslation` / `buildPlan` / `replaceAllStepsWithBoundary` gain a multi-plan branch that builds N guarded child plans, installs per-child params, and splices a `MultiPlanMatchStep`)" and "`GremlinToMatchTranslator` / `TranslationResult` (multi-plan carrier — field or sibling `UnionTranslationResult`)". All three methods and the carrier are named; the carrier shape is pinned as a decomposition choice between two concrete options.
  - Criteria met: the substantive build/splice edits are enumerated in the strategy modified-scope; the carrier shape decision is recorded in the Decision Log.
- **Regression check**: item 3 correctly relocates recogniser registration to `GremlinStepWalker.PRODUCTION_RECOGNISERS` (adversarial A4), consistent with the strategy scope no longer overclaiming registration. The child→plan seam in DR-U2 (fork prefix, strip `EndStep`, recursive `walk`) matches C6's confirmed "no existing child→full-plan path" finding. Clean.
- **Verdict**: VERIFIED

#### Verify T3 (should-fix): per-child positional parameters collide with the base's single-map install
- **Original issue**: `openArming` installs one `inputParameters` map on the current plan context; each union child mints its own `0,1,…` slots, so a single shared map would collide and bind wrong values (wrong-multiset correctness hazard). The track named "reconciling per-child positional parameters" abstractly without confronting the single-map install.
- **Fix applied**: DR-U2 and Plan of Work item 2 pin the mechanism — the base super-constructor takes an empty `inputParameters` map, and each child's parameters install into its own child context; Validation adds the per-child `?`-slot correctness test.
- **Re-check**:
  - Track-file location: `## Decision Log` DR-U2 (line 31, "base takes an empty `inputParameters` map"); `## Plan of Work` item 2 (line 62, "installs each child's positional parameters into its own context (base takes an empty map — technical T3)"); `## Validation and Acceptance` (line 76, "Two union children with different `?`-slot values return the correct per-child multiset (technical T3)").
  - Current state: the base-single-map obstacle is named as the thing to change (empty map to base), the per-child install is the mechanism, and a falsifiable per-child multiset test pins it.
  - Criteria met: mechanism pinned in the Decision Log; test added.
- **Regression check**: coherent with DR-U1 — the lazily-opened per-child contexts are exactly where per-child params install, so the empty-base-map + per-child-install mechanism composes with the `MultipleExecutionStream` realization rather than conflicting with it. Clean.
- **Verdict**: VERIFIED

#### Verify T4 (suggestion): D7 idempotency scan already base-keyed — broaden-the-scan sub-item is a no-op
- **Original issue**: `containsBoundaryStep` already tests `instanceof AbstractMatchPlanStep`, which detects a `MultiPlanMatchStep`; the track's "broaden the scan" sub-item was already done and risked a redundant re-edit.
- **Fix applied**: Interfaces drops the sub-item and states "no edit needed"; Surprises + Plan of Work item 2 record the scan is already base-keyed.
- **Re-check**:
  - Track-file location: `## Interfaces and Dependencies` (line 92, "The D7 idempotency scan already keys on `AbstractMatchPlanStep` — no edit needed (technical T4; drops the pre-review 'broaden the scan' sub-item)"); `## Plan of Work` item 2 (line 62, "no change (technical T4)"); `## Surprises` (line 24, "the D7 idempotency scan is already base-keyed (a no-op, dropped)").
  - Current state: the broaden-scan clause is removed and annotated "already satisfied by Track 7; no change," aligned with the plan's Track 7 refresh note.
  - Criteria met: the stale sub-item is gone; the implementer will not re-edit an already-correct scan.
- **Regression check**: confirmed against source — `GremlinToMatchStrategy.java:350` reads `step instanceof AbstractMatchPlanStep<?, ?>`, so a `MultiPlanMatchStep extends AbstractMatchPlanStep` is detected with no change. Clean.
- **Verdict**: VERIFIED

#### Verify T5 (suggestion): union cache policy unpinned against the single-plan cache value
- **Original issue**: The cache is single-plan (one `InternalExecutionPlan` value, one `GremlinPlanFingerprint` over one `MatchPlanInputs`, boolean RID bypass); "cache the union plan" had no direct home and the track flagged the policy open without picking one.
- **Fix applied**: DR-U5 pins the policy — union sets `cacheEligible = false` (mirroring the RID-bypass path); per-child caching or a multi-input fingerprint is deferred. Plan of Work item 2 sets `cacheEligible = false`; Validation adds a RID-bearing-child test.
- **Re-check**:
  - Track-file location: `## Decision Log` DR-U5 (line 34); `## Plan of Work` item 2 (line 62, "sets `cacheEligible = false` for union (DR-U5)"); `## Validation and Acceptance` (line 78, "Union sets `cacheEligible = false`; a union with one RID-bearing child still returns the correct multiset (DR-U5)").
  - Current state: the policy is a concrete decision record (non-caching), with the deferral of a multi-plan cache value explicitly out of scope in Interfaces (line 93). The choice fits the confirmed single-plan cache shape (I2) — bypass avoids the missing multi-plan value slot.
  - Criteria met: policy pinned in the Decision Log; a RID-bearing-child acceptance case pins correctness.
- **Regression check**: consistent with Interfaces Out-of-scope ("a multi-plan cache value / union fingerprint — deferred under DR-U5") and the RID-bypass precedent (`cacheEligible = !ctx.ridBearing()`). Bypassing the whole union is the lowest-risk fit and introduces no cross-shape fingerprint collision. Clean.
- **Verdict**: VERIFIED

## Findings

_No new findings. The five prior findings all verify clean (see the verdicts block and the T1–T5 certificates)._

## Summary

**PASS.** T1–T5 verify clean. The amendments accurately reflect the source facts they rest on: `MultipleExecutionStream` is a lazy one-live-stream concatenator, so DR-U1's `startPlanStream()` realization dissolves T1's missing-advance-seam obstacle without a base advance edit, scoping any residual per-child-context-threading edit conditionally; the D7 scan already keys on `AbstractMatchPlanStep` (T4 no-op confirmed at `GremlinToMatchStrategy.java:350`). T2's build/splice edits and multi-plan carrier, T3's empty-base-map + per-child-install mechanism, and T5's `cacheEligible=false` policy are all named in the Decision Log, Plan of Work, and Interfaces with matching Validation tests. No regression introduced; no new findings.
