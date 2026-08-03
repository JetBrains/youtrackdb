<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: S1, sev: should-fix, loc: "implementation-plan.md § Checklist (Track 9 entry :671-681 and Track 11 entry :699-706 — intro paragraphs)", anchor: "### S1 ", cert: "", basis: "both pending checklist intros run four sentences against the 1-3 cap; the 2026-08-03 split re-expanded the Track 9 intro the 2026-08-01 pass had trimmed to three and wrote the new Track 11 intro at four"}
  - {id: S2, sev: should-fix, loc: "plan/track-9.md § Plan of Work items 3 and 4 vs § Decision Log bullet 2, against plan/track-11.md § Plan of Work item 6", anchor: "### S2 ", cert: "", basis: "Track 9 publishes its handoff baseline after item 2 but item 4 keeps fixing behaviour, so Track 11's no-regression gate reads a number that predates Track 9's own last fixes — contradicts Track 9's recompute-whenever-behaviour-moves rule"}
  - {id: S3, sev: suggestion, loc: "implementation-plan.md #### D7 and #### D9 — the **Implemented in** lines (:232-233, :289-290)", anchor: "### S3 ", cert: "", basis: "D7 credits Track 8 with a broadening Track 7 delivered and contradicts its own body; D9 stops the per-class registry range at Track 7 although Track 8 added one entry and Track 11 adds four"}
  - {id: S4, sev: suggestion, loc: "plan/track-9.md § Validation and Acceptance (:81) and plan/track-11.md § Clarifications (:61)", anchor: "### S4 ", cert: "", basis: "the embedded install rule is stated twice at length, once inside an acceptance criterion and once as a clarification that restates it and then cites it"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### S1 [should-fix]
**Location**: `implementation-plan.md` § Checklist — the Track 9 intro paragraph (lines 671–681) and the Track 11 intro paragraph (lines 699–706), each being the prose that precedes `**Scope:**`.

**Issue**: Both pending entries run four sentences against the 1–3-sentence cap.

Track 9: (1) "Delivers the measured baseline the branch does not currently have." (2) "The `core` TinkerPop feature suite does not complete with the translator on — at `3148ac14e1` it reports zero scenarios and stalls at the `GQL Match Support` feature … returns 1930 scenarios green in 17 s." (3) "This track localizes that, records the failure set as a committed artifact, and fixes the dropped per-alias filter: … returns 3 rows where native returns 1 and translates rather than declining." (4) "Detail in plan/track-9.md."

Track 11: (1) "**Runs after Track 9** (inline replan, 2026-08-03 — the two halves of the original final track)." (2) "Completes the Phase 1 recognised set: the four list-shaping terminators … declining under D3." (3) "Then the full Cucumber suite with no regression against Track 9's post-fix baseline, and a Gremlin-on-vs-off JMH harness exercised in-track." (4) "Detail in plan/track-11.md, whose Decision Log carries the mechanism rationale (DR-T1 through DR-T3)."

The 2026-08-01 pass trimmed the then-Track-9 intro from five sentences to three under this same rule (recorded as S1 in `plan-review.md`); the 2026-08-03 split re-expanded it and wrote the new Track 11 entry at four. The plan checklist loads at every `/execute-tracks` session startup, so each extra sentence is re-read by every remaining Phase A/B/C session on this branch. Every displaced fact already has a home: the A/B measurement in Track 9's sentence 2 is the first table in `plan/track-9.md` § Context and Orientation, and the terminator mechanics in Track 11's sentence 2 are that file's § Context and Orientation plus DR-T1.

**Proposed fix**: Trim each to three sentences by folding the trailing pointer into the preceding sentence and dropping measurement detail the track file already carries.

Track 9 target: "Delivers the measured baseline the branch does not currently have: the `core` TinkerPop feature suite does not complete with the translator on, while the same command with the kill-switch off returns 1930 scenarios green. This track localizes that and records each runner's failure set as a committed artifact. It also fixes the dropped per-alias filter, which answers `g.V(marko).out().has(name, vadas)` with 3 rows where native returns 1 and translates rather than declining — detail in plan/track-9.md."

Track 11 target: keep sentences 1 and 2 verbatim, then merge 3 and 4 into "Then the full Cucumber suite with no regression against Track 9's post-fix baseline and a Gremlin-on-vs-off JMH harness exercised in-track — detail in plan/track-11.md, whose Decision Log carries the mechanism rationale (DR-T1 through DR-T3)."

**Classification**: mechanical
**Justification**: § `mechanical` — "All BLOAT findings are `mechanical` by construction"; the edit deletes checklist text already carried on the `**Scope:**` line and in the track files, changing no plan intent.

### S2 [should-fix]
**Location**: `plan/track-9.md` § Plan of Work items 3 and 4 (lines 70–71), its § Decision Log second bullet (line 24), its § Validation and Acceptance lines 87–88, and its flowchart (lines 53–59) — read against `plan/track-11.md` § Plan of Work item 6 (line 69) and § Decision Log fourth bullet (line 26).

**Issue**: Track 9 publishes the handoff baseline before it has finished changing the behaviour that baseline measures, so Track 11's no-regression gate reads a stale number.

Item 3 re-measures "immediately after item 2", and the Decision Log states the handoff explicitly: "item 3 re-measures immediately after item 2 lands and hands *that* number to Track 11." Item 4 then runs after item 3 — the flowchart draws `Rebase → Triage` — and it changes behaviour: "Read the 21 deferred process-compliance failures and the post-item-2 Cucumber residue against the item-3 baseline. **Fix what belongs to this track**." Track 11 confirms it reads the item-3 number: "The baseline is the artifact Track 9 publishes after its filter fix, not Track 9's first measurement."

Track 9's own decision rule forbids this. Its second Decision Log bullet generalizes Track 10's discovery to "**Recompute the measured baseline whenever anything moves the measured behaviour**", then applies the rule to item 2 alone. Item 4 moves the measured behaviour by the same argument and gets no re-measurement.

Two consequences follow, both silent. A Cucumber scenario that item 4 repairs is recorded as failing in the published baseline, so Track 11 can regress it back to failing and still report no regression. A scenario that item 4's fix breaks is recorded as passing, so Track 11 inherits a regression it did not cause and spends triage budget on it.

Track 9's two internal sources already disagree about the publication point. § Plan of Work and the flowchart put the publish at item 3, before triage. § Validation and Acceptance lists them the other way — the triage criterion at line 87 ("The nine dropped-filter-signature compliance failures … pass; the remaining twelve are each diagnosed and dispositioned") precedes the handoff criterion at line 88 ("The post-fix baseline is recorded and named as the number Track 11 reads"), which reads as a publish after triage.

**Proposed fix**: The user picks the publication point, and the plan states it once in all four places.

(a) Publish last. Item 3 keeps its immediate re-measurement as the item-2 gate, and a new clause on item 4 re-runs both runners after the last fix lands and names *that* artifact as Track 11's baseline. Update the flowchart (the handoff hangs off `Triage`), Track 9's Decision Log bullet 2, Track 9 acceptance line 88, and Track 11's item 6 plus its fourth Decision Log bullet.

(b) Freeze item 4. Constrain item 4 to diagnosis and disposition with no production-code change, so the item-3 number stays valid. This requires the nine dropped-filter compliance failures to be satisfied entirely by item 2, and it requires "Fix what belongs to this track" to leave item 4.

(c) Amend on change. Item 4 re-runs and amends the published artifact whenever it lands a fix, and the artifact records which measurement it is.

**Classification**: design-decision
**Justification**: § `design-decision` — "Track contradictions — Track 1 assumes X, Track 3 assumes not-X. Which is right is a design call"; whether the correctness triage sits before or after the handoff measurement is a planner judgment about Track 9's scope, not a text edit.

### S3 [suggestion]
**Location**: `implementation-plan.md` `#### D7: Strategy idempotency`, the `**Implemented in**` line (lines 232–233), and `#### D9: Type-keyed recognizer dispatch`, the `**Implemented in**` line (lines 289–290).

**Issue**: Both Decision Records name a stale set of implementing tracks, and D7 contradicts its own body.

D7's `**Implemented in**` reads "Track 2 (against `YTDBMatchPlanStep`); broadened to the boundary base in Track 8 when `MultiPlanMatchStep` lands." Track 7 did the broadening. Its Strategy refresh in the Track 7 checklist entry (lines 568–574) says so outright: Track 7 "already retargeted the D7 idempotency `instanceof` scan to the base. Track 8's `## Interfaces and Dependencies` 'broaden the scan' sub-item is therefore a no-op the decomposer drops." D7's own rationale paragraph agrees ("The scan keys on the Track 7 boundary base (D8 revised)"), so the record disagrees with itself two lines apart.

D9's `**Implemented in**` reads "Track 2 (walker + registry); per-class entries added by Tracks 2–7." Track 8 added `UnionStepRecogniser`, and Track 11 adds four — `FoldStep`, `UnfoldStep`, `ReverseStep`, `TailGlobalStep`. The Track 11 entry matters most of the three, because DR-T2 makes the registration itself load-bearing: "These shapes are only safe today because `FoldStep` is unregistered; this track's own registration is what opens the path." A reader tracing D9's safe-failure-on-unknown-subclass argument forward stops at Track 7 and never reaches the track that stresses it hardest.

**Proposed fix**: Two line edits. D7 → "**Implemented in**: Track 2 (against `YTDBMatchPlanStep`); broadened to the boundary base in Track 7 with the base extraction." D9 → "**Implemented in**: Track 2 (walker + registry); per-class entries added by Tracks 2–8 and Track 11."

**Classification**: mechanical
**Justification**: § `mechanical` — "an obvious typo in a track number reference"; both corrections are read off text already in the plan (the Track 7 Strategy refresh) and the pending track files, and neither changes what any track does.

### S4 [suggestion]
**Location**: `plan/track-9.md` § Validation and Acceptance, the `embedded` criterion (line 81), and `plan/track-11.md` § Clarifications, the `embedded` bullet (line 61).

**Issue**: The `embedded` install rule is written out twice at full length, and the second copy also points at the first.

Track 9's copy is a 162-word acceptance criterion. Its first two sentences state the criterion; the remaining four explain the mechanism — the reactor exclusion, the local-repository resolution of `youtrackdb-core:0.5.0-SNAPSHOT`, the 2026-07-02 jar on the branch machine, the test-jar supplying `EmbeddedGraphFeatureTest`'s feature files and `GraphFeatureWorld`, and the re-install rule. Track 11's copy is 105 words in § Clarifications and repeats the reactor exclusion, the local-repository resolution, and the test-jar clause almost word for word, then closes with "Same rule as `plan/track-9.md` § Validation and Acceptance."

The two files also disagree on where the material belongs. Track 11 files it as a clarification; Track 9 files the same material inside an acceptance criterion, which is what Phase C reads to decide whether the track is done. A criterion whose evaluable claim is two sentences and whose mechanism is four is harder to check off than one that states the claim and points at the mechanism.

Each track file must stand alone for its own Phase A/B/C sessions, so some restatement is correct here. The redundancy is the mechanism explanation, not the commands.

**Proposed fix**: Two moves. In `plan/track-9.md`, keep the criterion at its first two sentences plus the re-install requirement, and move the four mechanism sentences into that file's § Clarifications as a bullet, matching Track 11's placement. In `plan/track-11.md`, trim the bullet to the two commands, the track-specific reason for repeating the install ("this track's whole deliverable is new `core` code, so a run against a stale jar exercises none of it and still reports no regression"), and the existing pointer — dropping the reactor and test-jar clauses the pointer's target then carries.

**Classification**: mechanical
**Justification**: § `mechanical` — "All BLOAT findings are `mechanical` by construction"; the fix relocates prose within one file and deletes a restatement that its own cross-reference already resolves.

## Evidence base

No certificates — plan-internal structural review, no codebase read. Every claim above
is a direct read of `implementation-plan.md`, `plan/track-9.md`, `plan/track-11.md`, a
completed track's checklist entry, or `plan-review.md` § Re-validation 2026-08-01, cited
inline by section and line. `certs: 0`.

Checked and cleared, recorded so the next pass does not re-litigate:

- **The post-split dependency chain is acyclic and the annotations match.** Execution order is 7 → 8 → 10 → 9 → 11. Track 9's `**Depends on:** Track 10` matches its `**Inter-track dependencies:**` line, and Track 11's `**Depends on:** Tracks 7, 8, and 9` matches its own. No earlier-executing track depends on a later-executing one. The descending `## Checklist` order (… 8, 10, 9, 11) is deliberate and was verified mechanically sound in the 2026-08-01 pass: `workflow-startup-precheck.sh` walks `## Checklist` for the first `[ ]` in document order and reads the number off `Track <N>:` without sorting, so Track 9's entry position makes it the resume target.
- **Both halves of the split are in bounds and the split is justified in writing.** Track 11 at ~14–20 in-scope files sits inside the soft range (floor ~12, ceiling ~20–25). Track 9 at ~8–14 straddles the floor, and the only fold available to it is forward into Track 11 — refused in writing by DR-S1 in its own track file, which records the no-shared-file boundary, the one-way coupling, and the review-burden argument. A documented out-of-bounds track passes. The two halves sum to ~22–34 against the pre-split ~22–30, so the split lost nothing at the lower bound.
- **Track 9's figure survives absorbing the second runner.** The `embedded` measurement adds runs, not files: `## Interfaces and Dependencies` puts both runners' results in one artifact file ("one file carrying a labelled half per runner"), and the Scope line discloses the `embedded` A/B as unsized until first run alongside the already-disclosed unsized diagnosis. The `~8–14 files` figure needs no change.
- **The pre-split Phase A findings are fully accounted for across the two files.** Track 9 § Artifacts and Notes keeps R1, R3–R6, and T8; Track 11 keeps T1–T7, T9–T16, T18, R2, and R7; T17 is retired by the rewritten Scope lines. T1–T18 and R1–R7 are each claimed exactly once, and both files record that the PASS verdict does not carry over.
- **`## Implementation state` agrees with the Checklist on all three surfaces.** The narrative ("Tracks 1–8 and Track 10 are executed and complete; Tracks 9 and 11 remain, in that order"), the eleven table rows in Checklist order with 9 and 11 marked `not started`, and the decision-conformance sentence all match the `[x]`/`[ ]` marks. The conformance sentence's closing clause correctly reassigns D3's enforcement surface to Track 11's four recognisers.
- **The out-of-scope lines are mirror-symmetric.** Track 9 excludes the terminators, the `RecognitionContext` seam, the decline gates, and the JMH harness as Track 11's; Track 11 excludes the suite diagnosis, the dropped filter, and the baseline artifact as Track 9's. DR-S1's no-shared-file claim holds against both `## Interfaces and Dependencies` in-scope lists, with the item-1 diagnosis the one acknowledged open set — and the two tracks are consecutive, so the non-consecutive-overlap rule would not fire even if it did overlap.
- **No superseded Decision Records are retained** in either pending track file, and no `- [ ] Step:` items or *(provisional)* markers appear in the plan file or either track file.
- **Component-intent budget.** The "Boundary base / YTDBMatchPlanStep / MultiPlanMatchStep" bullet is still 7 lines against the ~5-line cap, unchanged by the split beyond "Track 9's" → "Track 11's". The 2026-08-01 pass cleared it on the record that commit `3c4f17d752` already trimmed it from 9 lines as a prior structural fix. Not re-raised. The other six bullets are 2–5 lines.
- **Plan-file budget.** 740 lines / ~48 KB, well under the ~1,500-line / ~30K-token roll-up. No section in either pending track file breaches the house-style 200-word soft cap at the smallest-labeled-block unit; the longest single blocks are Track 9's 162-word acceptance criterion (S4) and Track 11's 207-word Plan-of-Work item 7.
- **Standing Phase-4 deferrals, unchanged.** The frozen `design.md` class-diagram and sequence-diagram size overruns, the `MultiPlanMatchStep : "Track 6"` provenance, and the boundary-step `reset()` / metrics-capture gaps recorded as S6 in the 2026-08-01 pass. None is actionable before Phase 4.
