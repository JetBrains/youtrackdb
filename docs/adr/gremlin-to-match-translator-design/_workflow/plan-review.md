# Plan Review — Gremlin-to-MATCH Translator

Phase-2 audit summary (consistency + structural). Relocated from
`implementation-plan.md` § Plan Review during workflow migration (#1145,
D7): the Phase-2 audit lives here in every tier, and the plan no longer
carries the State-0 checkbox.

- [x] Plan review (consistency + structural) — passed at iteration 1

**Auto-fixed (mechanical):** S2 — trimmed Track 4 checklist intro to 3 sentences (detail already in plan/track-4.md); S3 — trimmed Track 6 checklist intro to 3 sentences (detail already in plan/track-6.md).

**Escalated (design decisions):** S1 — missing Decision Record for the class-`count(*)` fast-path unification (the one engine-surface change beyond the additive ctor, reversing the pre-YTDB-609 decline rationale). User approved adding D11, rationale distilled from the frozen design.md §"Aggregation barrier semantics".

Consistency review: no findings (28 verification certificates, all current-state symbols confirmed). Review files: `plan/reviews/consistency-iter1.md`, `plan/reviews/structural-iter1.md`.

## Re-validation 2026-07-15 (7-track plan, after the Track 4 split inline replan)

- [x] Plan review (consistency + structural) — passed at iteration 1

**Auto-fixed (mechanical):** S1 — trimmed the Track 6 checklist intro from ~6 sentences to 2 (over the 1–3-sentence cap after the renumber; the class/flag inventory is preserved in the entry's `**Scope:**` line and in `plan/track-6.md`).

**Escalated (design decisions):** S2 — Tracks 2 & 3 (completed) carry Strategy-refresh notes naming pre-split downstream ranges (`Tracks 3–6`, `Tracks 4–6`) that the A1 split superseded. User chose **leave-as-is**: they are as-of-completion historical assessments, editing completed-track content is user-pause-gated, and widening the ranges would assert a broader "unchanged" claim the split made false. The split is documented in live prose (Track 3 ADJUST note, Track 4 Outcomes, the Implementation-state paragraph, D5→Track 5).

**Deferred to Phase 4 (design.md frozen):** CR1 — the frozen `design.md` class diagram lags the as-built Track 2/3 surface (7-param `addEdge` + separate `addEdgeAsNode` vs the diagram's 8-param `addEdge`; the current `WalkerContext` field set; `NotFilterStepRecogniser`). Recorded only, no plan/track impact; reconciled in the Phase-4 `design-final.md`.

Consistency review: PASS (gate-verified; CR1 deferred). Structural review: PASS (gate-verified; the 1→(2,3)→4→5→6→7 chain is acyclic, cross-track references and the D5→5 / D11→6 / D8→7 reassignments consistent). PSI was non-responsive this session (IDE stuck indexing) — references verified via grep + direct source Reads with the reference-accuracy caveat recorded in the review evidence bases. The four `plan/reviews/*-iter1.md` files were regenerated for this 7-track re-validation, superseding the Jul-14 6-track evidence (preserved in git history).

## Re-validation 2026-07-27 (9-track plan, after the Track 6 split inline replan)

- [x] Plan review (consistency + structural) — passed at iteration 1

**Auto-fixed (mechanical):** S2 — the `### Invariants` idempotency bullet named only `YTDBMatchPlanStep`; broadened it to name both boundary steps (`YTDBMatchPlanStep` or `MultiPlanMatchStep`), matching the sibling boundary-step invariant above it and D7 (the scan detects both after Track 8).

**Escalated (design decisions):** CR1 (consistency) — Track 7 named `GremlinToMatchTranslator`, `GremlinStepWalker`, and `GremlinToMatchStrategy` all "construction sites" of `YTDBMatchPlanStep`, but only `GremlinToMatchStrategy.replaceAllStepsWithBoundary` constructs it; the other two produce the upstream `TranslationResult`. User chose **correct current-state wording**: the plan Checklist scope line and `track-7.md` Plan of Work item 2 / Interfaces / Context reference-accuracy note now name the sole construction site and describe the two upstream producers, leaving the rewire footprint deferred to the base-shape choice at decomposition. S1 (structural) — Track 6's `**Strategy refresh:** ADJUST` line asserted two premises the post-Track-6 split (D8) overturned (`MultiPlanMatchStep` inherits the `YTDBMatchPlanStep` constructor; three order-less post-process flags). User chose **append superseding note**: the historical line is kept and a correcting parenthetical points to D8 + Tracks 7/8/9 (`MultiPlanMatchStep` extends the shared boundary base; the post-process is an ordered `List`), mirroring the Track 2 refresh convention.

Consistency review: PASS (gate-verified; CR1 fixed and re-confirmed). Structural review: PASS (gate-verified; the 7→8→9 chain rooting on completed Track 6 is acyclic, sizing within the soft two-sided bound, the supply/consume contract holds). The frozen `design.md` `MultiPlanMatchStep : "Track 6"` / `extends YTDBMatchPlanStep` provenance is expected pre-split freeze state, reconciled in the Phase-4 `design-final.md` — recorded, not a finding. PSI `steroid_execute_code` timed out on the cold kotlinc index this session; symbol facts (the sole `new YTDBMatchPlanStep` site, the walker/translator non-references) were verified via grep + Read + fork-jar inspection with the reference-accuracy caveat recorded in the evidence bases. The four Jul-27 12:21 pre-split `plan/reviews/*-iter1.md` files were archived under `plan/reviews/pre-split/`; this re-validation's fresh files are `plan/reviews/consistency-iter1.md`, `consistency-gate-verification-iter1.md`, `structural-iter1.md`, and `structural-gate-verification-iter1.md`.
