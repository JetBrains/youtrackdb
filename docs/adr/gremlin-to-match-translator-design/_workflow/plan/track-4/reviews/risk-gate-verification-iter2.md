<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 4 risk review — gate verification, iteration 2 (reviewer-risk, Phase 3A)

All six iteration-1 risk findings (R1–R6) are VERIFIED against the fixed `track-4.md`. The two watch-items from the spawn — a lingering "NOT throw is a hard failure" claim, and any spot still binding RIDs as params — are both absent: "hard failure" now appears only in the corrected line-51 defense-in-depth framing, and every RID mention across the Purpose, C&O, Plan of Work, Decision Log, and acceptance sections consistently says inline/structural. The load-bearing code claims underpinning R2 and R4 re-check clean against source. No new findings. Overall PASS.

#### Verify R1: polymorphic `hasLabel` narrowing
- **Original issue**: The track pinned non-poly `hasLabel(L)` to `classEquals` (exact `@class =`) but left the polymorphic branch as an unresolved Phase-4 fork, asserting an unvalidated "still matches subclasses" claim that leans on a stale design section (deleted `MatchClassFilters`) and may over-match if native `HasStep(~label)` is leaf-exact.
- **Fix applied**: Decision Log entry (track-4.md:25) pins mode-gated behavior — non-poly → `classEquals`; poly → subclass-inclusive via the polymorphic MATCH `class:` node *if the builder supports it, else decline to native (the safe fallback)*, with the subclass-inclusive correctness explicitly flagged unvalidated and overlapping the open Phase-4 BC2 item. Surprises entry (track-4.md:20) flags `design.md` §"Schema polymorphism" as stale for Phase 4. Plan of Work item 2 (:48), C&O (:40), and the Validation gate (:63) all gate on `ctx.polymorphic()` and require a polymorphic-vs-non-polymorphic equivalence test.
- **Re-check**:
  - Track-file location: Decision Log :25, Surprises :20, C&O :40, Plan of Work item 2 :48, Validation :63.
  - Current state: all five sites carry the same mode-gated rule; the polymorphic branch now has a concrete safe fallback (decline) and a Track-4-level equivalence-test gate, replacing the bare unvalidated assertion.
  - Criteria met: don't-implement-against-stale-design (flagged), equivalence test as a Track-4 gate (Validation :63), concrete branch pinned with a correctness-safe fallback.
- **Regression check**: Checked the plan file (implementation-plan.md:400–401, :407–419) — its Track 3 "ADJUST" note and Track 4 scope describe `classEquals` narrowing without contradicting the added polymorphic gate. Clean.
- **Verdict**: VERIFIED — the empirical determination of native leaf-exact vs hierarchy-aware membership is deferred to decomposition via the equivalence test, which is the appropriate place; decline is a correctness-safe fallback under either native behavior, so no wrong-result can ship un-caught.

#### Verify R2: AST nodes survive `copy()` / `toGenericStatement()`
- **Original issue**: A new `findMode` field on `SQLMatchesCondition` and the whole new `SQLEndsWithCondition` node must round-trip through the hand-written `copy()` / `splitForAggregation()` / `toGenericStatement()`; a missed field silently reverts regex to whole-string on every clone/cache-get.
- **Fix applied**: Plan of Work item 4 (track-4.md:50) now requires both nodes to round-trip `copy()` and `toGenericStatement()`, citing the per-clone/per-cache-get deep copy as the silent-wrong-result vector (R2). Acceptance bullet added (:73): "The new Text / TextP AST nodes survive `copy()` / `toGenericStatement()` round-trips (plan-clone equivalence)".
- **Re-check**:
  - Codebase location: `SQLMatchesCondition.java` — `copy()` @166 and `splitForAggregation()` @276 rebuild the node field-by-field; `toGenericStatement()` @113 renders the fingerprint (confirmed present via grep).
  - Current state: the track now treats copy-round-trip completeness as an acceptance gate for the AST work.
  - Criteria met: field-completeness of `copy()`/`toGenericStatement()` is now a written acceptance criterion, matching the real hand-copy surface.
- **Regression check**: Checked that the acceptance addition does not conflict with the existing collate-transform acceptance on the same line — the two clauses (`ci` regression + round-trip) are distinct and both present. Clean.
- **Verdict**: VERIFIED

#### Verify R3: RID inline vs positional-parameter contradiction
- **Original issue**: The track inlined RIDs while `design.md` §"Parameter binding" listed RID arguments as "Bound, out of the key" — a direct contradiction; parameterizing would defeat `promoteStaticRidsFromFilters` and demote `g.V(id)` to a class scan.
- **Fix applied**: Decision Log (track-4.md:24) pins inline/structural RIDs (preserve `promoteStaticRidsFromFilters`) and adds a cache-bypass for RID-bearing traversals to avoid single-use-entry thrash. Surprises (:19) flags `design.md` §"Parameter binding" for Phase-4 reconciliation. Plan of Work item 7 (:53) lists RIDs among structural tokens that must not parameterize. Acceptance bullet added (:72): inlined-RID shape takes the direct-RID fetch, not a class scan; RID-bearing traversals do not populate the cache.
- **Re-check**:
  - Track-file location: :19, :24, :40, :48, :53, :72 — all say inline/structural.
  - Current state: no site binds RIDs as params; the design contradiction is now a documented, flagged Phase-4 deferral (the correct workflow for design reconciliation) rather than a live contradiction inside the track.
  - Criteria met: the track's inline choice (the performance-correct one) is now consistent everywhere, with the cache-reuse cost acknowledged and mitigated via cache bypass.
- **Regression check**: Grep of the whole track file for `rid|parameteriz|param|inline` (and the plan file for `promoteStaticRids|@rid|parameteriz|Bound, out of the key`) surfaced no residual "bind RIDs as params" statement. Clean.
- **Verdict**: VERIFIED — the only remaining divergence is the stale `design.md` text, which is intentionally deferred to Phase 4 with a Surprises flag, not a track-internal contradiction.

#### Verify R4: `manageNotPatterns` is not a hard failure
- **Original issue**: The track framed the NOT decline conditions as guarding against a *hard query failure*, but the throw is caught by `apply()`'s `RuntimeException` net (eager build) and degrades to a native decline; the inaccurate premise could mis-shape the mitigation.
- **Fix applied**: Plan of Work item 5 (track-4.md:51) and Signatures (:91) rewritten — the throw is `CommandExecutionException` caught by the eager-build `apply()` `RuntimeException` net → clean native decline; recogniser-side decline is defense-in-depth (skip wasted plan-build; stay correct if plan-build ever goes lazy). Acceptance bullet added (:71): the two disqualifying NOT shapes run on native with no exception surfaced via the eager-build safety net.
- **Re-check**:
  - Codebase location (confirmed via grep): `GremlinToMatchStrategy.java` — `catch (RuntimeException e)` @217 → `declineOnThrow` @224; `applyTranslation` @372 → `buildPlan` @376 → `createExecutionPlan(ctx, false, useCache=false)` @391 (eager, at apply() time). Class Javadoc @116/@132 corroborates the eager-build-then-mutate ordering and `useCache=false`.
  - Current state: no "hard query failure" wording remains; the single "hard failure" mention (:51) is the corrected "not the sole barrier against a hard failure" defense-in-depth framing.
  - Criteria met: premise corrected to the true degrade-to-decline behavior; recogniser decline correctly reframed as defense-in-depth + lazy-build fragility guard; negative acceptance test added.
- **Regression check**: Grep for `hard (query )?fail|hard failure` across the track file returns only the corrected line 51. Plan file has no `manageNotPatterns`/hard-failure statement to drift against. Clean.
- **Verdict**: VERIFIED

#### Verify R5: collate transform changes existing SQL `CONTAINSTEXT`
- **Original issue**: `SQLContainsTextCondition` is the shared SQL/GQL `CONTAINSTEXT` runtime; adding a field-collate transform changes existing SQL query results on `ci`-collated properties, an untested/undocumented SQL-side behavior change.
- **Fix applied**: Plan of Work item 4 caveat (track-4.md:50) — the collate transform "changes existing SQL `CONTAINSTEXT` semantics on `ci`-collated properties too, not only Gremlin, so carry a regression check on existing SQL `CONTAINSTEXT` (R5)". Acceptance bullet added (:73): "existing SQL `CONTAINSTEXT` on a `ci`-collated property still matches its pre-change multiset".
- **Re-check**:
  - Track-file location: :50 (caveat), :73 (acceptance).
  - Current state: the shared-node blast radius is now called out and pinned by an SQL-side regression check.
  - Criteria met: the core mitigation for a suggestion-severity finding (a regression test pinning no-op-on-`default` vs case-insensitive-on-`ci`) is present.
- **Regression check**: Clean. Minor note: the iter-1 proposed fix also suggested recording the change in `design.md` §"Observable behavior changes"; that design-note component was not added, but design reconciliation is a Phase-4 concern and the risk-bearing part (untested behavior change) is mitigated by the regression check, so this does not hold the finding open.
- **Verdict**: VERIFIED

#### Verify R6: D5 slot-ordering / structural-vs-value determinism
- **Original issue**: D5 cache correctness rests on two silent-failure invariants — deterministic walk-to-slot ordering and an exact structural/value split — both wrong-result-without-exception and untested by construction.
- **Fix applied**: Plan of Work item 7 (track-4.md:53) — "D5 correctness pivots on deterministic walk-to-slot parameter ordering and a stable structural-vs-value token classification — both silent wrong-plan / wrong-value on failure — so pin them with fingerprint-stability and value-independence tests (R6)"; the item also enumerates the structural tokens that must not parameterize (class names, `~label`, RIDs). Acceptance bullets: :70 (one plan per shape across distinct values; schema change invalidates) and :74 (fingerprint stable across walks of the same shape, value-independent across values — deterministic slot ordering).
- **Re-check**:
  - Track-file location: :53 (Plan of Work), :70 and :74 (acceptance).
  - Current state: both silent-failure invariants are now written requirements with dedicated determinism/value-independence acceptance gates.
  - Criteria met: fingerprint-stability + value-independence tests pinned; structural-vs-value classification made explicit with the must-not-parameterize token list.
- **Regression check**: The structural-token-never-renders-as-`PARAMETER_PLACEHOLDER` guard test from the iter-1 proposal is subsumed by the "stable structural-vs-value token classification" requirement plus the enumerated inline-only token list (:53); a decomposer implementing item 7 has enough to write it. Clean.
- **Verdict**: VERIFIED

## Findings

(No new findings surfaced by this verification pass.)
