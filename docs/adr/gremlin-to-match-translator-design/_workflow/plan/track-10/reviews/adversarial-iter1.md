<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:116", anchor: "### A1 ", cert: AT3, basis: "Nothing binds this track's commits to rostered steps; the plan carried the unrostered-follow-up review gap forward for Track 9 only, and Track 10's decide-during-Phase-B shape is the highest-exposure track on the branch"}
  - {id: A2, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:72", anchor: "### A2 ", cert: CH2, basis: "Item 2's suppress-the-capture option has no realization inside the track's own fences: the natural one violates the do-not-change-what-translates invariant, the other threads a new flag through out-of-scope translator files"}
  - {id: A3, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md:606", anchor: "### A3 ", cert: AT6, basis: "Accepted amendments naming artifacts outside track-10.md were never applied there: plan Scope line still ~5 files, checklist still carries the disproved MatchFirstStep-only wording, track-9.md has no g.V(rid) JMH hint"}
  - {id: A4, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:95", anchor: "### A4 ", cert: AT5, basis: "Acceptance is satisfiable by single-plan plus mocked tests alone; no criterion exercises the MultiPlanMatchStep re-arm or any real-plan re-arm with assertions enabled, the two surfaces the applied R3/E3 hazards name as riskiest"}
  - {id: A5, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:28", anchor: "### A5 ", cert: CH1, basis: "DR-M1 argues only against folding into Track 8 Phase C; the stronger unlisted alternative is merging into Track 9, and the step-0 enumeration overlaps Track 9's up-front Cucumber run with no hand-off"}
  - {id: A6, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:107", anchor: "### A6 ", cert: SC1, basis: "Realistic footprint is 11-13 files once the applied amendments' test homes and doc edits are counted; refresh the figure at decomposition so Phase C compares against a live number"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 7}
cert_index:
  - {id: CH1, verdict: "SURVIVES", anchor: "#### CH1 "}
  - {id: CH2, verdict: "NO", anchor: "#### CH2 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2, verdict: INFEASIBLE, anchor: "#### V2 "}
  - {id: V3, verdict: INFEASIBLE, anchor: "#### V3 "}
  - {id: AT1, verdict: HOLDS, anchor: "#### AT1 "}
  - {id: AT2, verdict: HOLDS, anchor: "#### AT2 "}
  - {id: AT3, verdict: BREAKS, anchor: "#### AT3 "}
  - {id: AT4, verdict: HOLDS, anchor: "#### AT4 "}
  - {id: AT5, verdict: BREAKS, anchor: "#### AT5 "}
  - {id: AT6, verdict: BREAKS, anchor: "#### AT6 "}
  - {id: SC1, verdict: "SURVIVES", anchor: "#### SC1 "}
  - {id: SM1, verdict: "SURVIVES", anchor: "#### SM1 "}
flags: [CONTRACT_OK]
-->

# Track 10 — adversarial review, iteration 1

The track's shape survives the three narrowed challenges: the five-item sequence is the right decomposition, the realized Track 7/8 outputs it leans on all exist as assumed, and no planned item can violate the engine-surface constraint beyond the two overrides the third exception already names. Track 10 is not on Track 8's retroactive-split trajectory — the realistic footprint is 11-13 files and roughly 1-2k insertions, with escalation fences on both variance sources (step 0's enumeration, item 4's gate cascade). What does not survive: item 2 still carries a "suppress the capture" option whose only natural realization changes what translates — the track's own first invariant, verbatim — while the invariant-clean realization needs a flag threaded through translator files the track scopes out; three accepted amendments that named artifacts outside track-10.md were never applied to those artifacts (the durable plan still says `~5 files`); the acceptance criteria are satisfiable without ever exercising the multi-plan re-arm the applied hazard paragraphs call the riskiest path; and nothing binds this decision-heavy Phase B to its step roster, on the branch where unrostered follow-up commits produced both of Track 8's silent-wrong-results blockers.

**Reference-accuracy caveat.** PSI was not attempted — `steroid_execute_code` hits the known cold-kotlinc timeout in this repo (both iteration-1 reviewers recorded it). Every symbol result below is grep plus a direct read of the declaring file. The negative results that matter — no `ridBearing` signal reaches the boundary constructors, no acceptance criterion names `MultiPlanMatchStepTest`, no `g.V(rid)` hint in track-9.md — were each cross-checked by reading the relevant file region end to end, which bounds the miss risk for those files without eliminating it repo-wide.

## Findings

### A1 [should-fix]
**Certificate**: AT3 (BREAKS)
**Target**: The remediation-track shape — the implicit assumption that Phase B work stays on the step roster
**Challenge**: Track 10 reproduces the exact preconditions of Track 8's worst failure mode and carries no guard against it. Track 8's episode records that both Phase C blockers were silent-wrong-results defects originating in unrostered follow-up commits that never received a step-level review, and the plan's Track 8 strategy refresh carries that gap forward as a risk "for Track 9" — Track 10, inserted afterwards, is not named. Yet Track 10's Phase B is the most decision-heavy on the branch: items 1-3 each mint a Decision Record mid-implementation, step 0 can resize the track, and item 4's pipeline dispatch surfaces arbitrary branch debt. Every one of those is a fork where new work materializes mid-step — the exact spot where Track 8's unrostered commits appeared. Validation bullet 2 disposes of out-of-scope *failures* (deferred / escalated / split), but no text binds *commits* to rostered steps.
**Evidence**: plan `## Checklist` Track 8 strategy refresh ("the unrostered-follow-up-commit review gap [is] carried forward as forward risks for Track 9"); `plan/track-8.md` episode and ledger entry of 2026-08-01 (iteration 3's misattributed follow-up); `track-10.md` `## Invariants & Constraints` (:115-118) — three bullets, none about commit discipline.
**Proposed fix**: Add one `## Invariants & Constraints` bullet: every commit in this track maps to a rostered step; work discovered by step 0, item 4's dispatch, or an item-1/2/3 Decision Record enters the roster via a recorded amendment with its own review scope — never as a follow-up commit. One sentence closes the gap the branch has now paid for twice.

### A2 [should-fix]
**Certificate**: CH2 (survival NO), supported by V1
**Target**: `## Plan of Work` item 2 — the "suppress the capture for that shape" option
**Challenge**: The option is still on the table (the applied R2 paragraph says "weigh the two options", not "strike one"), but it has no realization inside the track's own fences, so a Phase B that picks it discovers the collision mid-step. Realization (a) — decline RID-bearing start steps so `g.V(rid)` goes native again — makes the test pass by changing what translates: `## Invariants & Constraints` bullet 1 forbids exactly that, and `## Interfaces and Dependencies` scopes out "any change to what translates". It is also the *attractive* implementation, because it simultaneously removes the per-call planner cost the R2 paragraph documents. Realization (b) — keep translation, suppress at the capture layer — has no signal to key on: `ridBearing` is private `WalkerContext` state consumed only into `cacheEligible`, and neither boundary-step constructor receives it, so (b) means threading a new flag through `TranslationResult`, both boundary constructors, and the strategy splice — files absent from the In-scope list. And "that shape" is undefined: `HasStepRecogniser` also marks RID-bearing for `hasId(...)` predicates, so a flag-scoped suppression would null the capture for filter shapes far beyond the by-id lookup the test names.
**Evidence**: `WalkerContext.java:73`, `:474-480` (`ridBearing` private, package-read); `GremlinToMatchTranslator.java:145` (consumed into `cacheEligible` only); `GremlinToMatchStrategy.java:525`, `:548` (boundary constructors, no such flag); `HasStepRecogniser.java:142` (`hasId` also marks RID-bearing); `track-10.md:109`, `:116` (the two fences).
**Proposed fix**: Rewrite item 2's option set: (i) update the contract, with the kill-switch-pinned pair split the applied T3 text already requires; or (ii) if the item-2 Decision Record concludes by-id lookups should not translate, ESCALATE under invariant 1 — do not implement the decline in this track. State that suppress-at-capture is admissible only with an explicit shape definition plus the flag-threading files added to scope, which today it is not.

### A3 [should-fix]
**Certificate**: AT6 (BREAKS)
**Target**: The premise that iteration-1 findings are applied — their cross-artifact halves are not
**Challenge**: Three accepted amendments named artifacts outside `track-10.md`, and none of those artifacts was edited. The durable plan's Track 10 Scope line still reads `~5 files` (T6's own location field named "plan `## Checklist` Track 10 Scope line"; the track file's item 0 now cites "planned at ~7–10 files", and the Phase-A slim render says `~7–10` — so the corrected figure exists only in artifacts that die with the session or contradict the durable one). The same checklist entry still carries the pre-T1 diagnosis wording ("the `MatchFirstStep` introspection question"), contradicting the corrected item 3 (`MatchPrefetchStep` primary). And R2's accepted cross-track hint — put `g.V(rid)` / `g.V(ids)` into Track 9's on-vs-off JMH shape list — is nowhere in `track-9.md`. The plan file is what a decomposer, Phase C's review-burden check, and any post-`/clear` resume actually read.
**Evidence**: `implementation-plan.md:606` (`**Scope:** ~5 files`, at HEAD `84395668b7`, clean tree); `:609-611` (pre-T1 wording); `track-10.md:65` ("planned at ~7–10 files"); grep for `g.V(rid)` / `byId` over `plan/track-9.md` — zero hits.
**Proposed fix**: Apply the three cross-artifact edits in this Phase A commit: refresh the plan checklist entry's Scope figure and item-3 wording, and add the one-line JMH-shape hint to `track-9.md` `## Interfaces and Dependencies`. Alternatively give each an owner acceptance criterion, the pattern the applied T10 fix already established for the Constraints amendment.

### A4 [should-fix]
**Certificate**: AT5 (BREAKS)
**Target**: `## Validation and Acceptance` — re-arm test coverage
**Challenge**: The acceptance criteria are satisfiable by single-plan and mocked tests alone, leaving unexercised precisely the two surfaces the track's own applied hazard paragraphs call out. Criterion :95 requires "a close-then-reset case … at unit level" (singular), :96 requires "the test" to assert `copy(...)`-not-`start()` — both satisfiable in `YTDBMatchPlanStepTest` against a Mockito plan. In-scope (:107) names `YTDBMatchPlanStepTest` only, although the applied T2 text (:71) pulls `MultiPlanMatchStep` into scope and its re-arm is the harder case: the leak multiplies by child count, and the copy path must derive N fresh isolated contexts under the clone-isolation assert (:69, the applied R3 hazard: "the copy path needs its own context derivation"). No criterion requires any re-arm case against a real, prefetched plan with assertions enabled — the only configuration in which the `MultiPlanMatchStep` assert and the prefetch-sub-plan re-run can fire at all. A Phase B that meets every written criterion can ship the multi-plan re-arm untested.
**Evidence**: `track-10.md:95-96` (singular criteria), `:107` (In-scope test list), `:69`, `:71` (the applied hazard text naming both subclasses and the context derivation); `MultiPlanMatchStep.java:180` (the `-ea` clone-isolation assert); `core/pom.xml:36` (`-ea` in surefire argLine).
**Proposed fix**: Extend :95 to name both boundary unit-test classes, and add one criterion: at least one close-then-reset case runs a real prefetched plan (not a mock) with assertions enabled, so the context-derivation and prefetch-sub-plan behavior are exercised where the assert can fire.

### A5 [suggestion]
**Certificate**: CH1 (survival YES)
**Target**: DR-M1 — remediation is its own track
**Challenge**: DR-M1 argues only against folding the fix into Track 8's Phase C (attribution honesty). The stronger alternative is not listed: merge into Track 9, which already owns the branch's first whole-feature Cucumber gate and an unsized triage bucket — and after the applied R1 fix, the two tracks share the same enumeration event, since step 0's full `-pl core test` drives `YTDBGraphFeatureTest`, one of Track 9's two Cucumber homes. Merging would mean one enumeration and one triage rule instead of two. The decision survives: a merged track lands at ~26-34 files, past the ~20-25 split ceiling; Track 9's JMH baseline and Cucumber gate need a green start, which is the ordering premise of the whole insertion; and the attribution argument still favors separation. But the rationale as written does not meet its strongest opponent, and the enumeration overlap has no hand-off.
**Evidence**: `track-10.md:28` (DR-M1, Track-8-folding argument only); `plan/track-9.md:23`, `:36` (up-front Cucumber run, triage bucket unsized); Track 9 Scope `~15–21 files` + Track 10 realistic 11-13 (SC1).
**Proposed fix**: One sentence in DR-M1 naming the Track 9 merge alternative and why it loses (split ceiling, green-start premise). One cross-track line: step 0's recorded failure list is handed to Track 9's triage bucket, so the first Cucumber triage is not paid twice.

### A6 [suggestion]
**Certificate**: SC1 (survives; figure stale)
**Target**: The `~7–10 files` footprint (track-10.md:65; plan Scope line per A3)
**Challenge**: Counting the surfaces the applied amendments themselves name, the realistic set is 11-13: six product files (`YTDBQueryMetricsStep`, `AbstractMatchPlanStep`, `YTDBMatchPlanStep`, `MultiPlanMatchStep`, `MatchPrefetchStep`, `MatchFirstStep`), four test files (`YTDBQueryMetricsStrategyTest`, `YTDBMatchPlanStepTest`, `MultiPlanMatchStepTest` per A4, the R6 `EXPLAIN`/`getSubSteps` home), at least one CI file, plus the plan and `track-9.md` edits (A3). Insertions stay bounded — two one-line overrides, a fifth plan-seam hook with two implementations, and tests — roughly 1-2k, nowhere near Track 8's 5,814; the two unbounded sources (step 0's set, item 4's cascade) are already escalation-fenced. So the track is neither a split candidate nor meaningfully under-sized, and the answer to "is Track 10 heading the same way as Track 8" is no.
**Evidence**: `track-10.md:107-113` (In-scope enumeration); A3/A4 additions; plan §Track descriptions two-sided bound.
**Proposed fix**: When decomposition fixes the step list, refresh the Scope figure once (to ~9-13) in both the track file reference and the plan checklist line, so Phase C's review-burden comparison starts from a number that was true at decomposition time.

## Evidence base

#### CH1 Challenge: DR-M1 — remediation is its own track, not a Track 8 iteration
- **Chosen approach**: a dedicated Track 10, running before Track 9, holding the metrics remediation.
- **Best rejected alternative**: not listed in DR-M1 — merge the remediation into Track 9, which already owns the first whole-feature Cucumber gate and the cross-track mistranslation triage bucket.
- **Counterargument trace**:
  1. Step 0 runs full `./mvnw -pl core test`, which drives `YTDBGraphFeatureTest` (`core/pom.xml:323-331`, no `gremlintest` exclusion on the `sequential-tests` execution) — the same suite `track-9.md:23` schedules as an up-front run.
  2. A merged track would enumerate once and triage once, where the current split enumerates twice with no recorded hand-off between the two buckets.
  3. The merged footprint is Track 9's ~15-21 plus Track 10's realistic 11-13 → ~26-34 files, past the ~20-25 split ceiling; and Track 9's JMH baseline against a red suite would blur regressions into noise — the track's own stated ordering premise.
- **Codebase evidence**: `track-9.md:23`, `:36`; `track-10.md:7`, `:65`.
- **Survival test**: YES — the split survives on the split-ceiling arithmetic and the green-start premise; the rationale gap (unaddressed alternative, unhandled enumeration overlap) is worth one sentence each. → A5.

#### CH2 Challenge: item 2's option set — "update the test … or suppress the capture for that shape"
- **Chosen approach**: keep both options open for the item-2 Decision Record, weighed against the R2 performance question.
- **Best rejected alternative**: strike the suppress option (or fence its realization explicitly), leaving update-the-contract vs ESCALATE.
- **Counterargument trace**:
  1. Realization (a) of suppress: decline the RID-bearing start shape in `StartStepRecogniser`, so `g.V(rid)` goes native, `YTDBGraphStep`'s by-id branch runs no query, `getLastExecutionPlan()` is null, `byIdLookupSurfacesNullPlan` passes. One decline edit — and it also deletes the R2 per-call planner cost, making it the implementation a Phase B under time pressure reaches for.
  2. That is "change what translates in order to make a metrics test pass", the track's Invariant 1 verbatim, and "any change to what translates" is Out-of-scope (`track-10.md:109`, `:116`).
  3. Realization (b): suppress at the capture layer. The capture reads only the boundary step (`YTDBQueryMetricsStep.java:91-107`); no by-id signal exists there (V1 trace), so (b) requires new plumbing through files outside In-scope, plus a definition of "that shape" that excludes `hasId(...)` filter walks.
- **Codebase evidence**: `WalkerContext.java:73`, `:474-480`; `GremlinToMatchTranslator.java:145`; `GremlinToMatchStrategy.java:525`, `:548`; `HasStepRecogniser.java:142`.
- **Survival test**: NO — the option as listed should be struck or explicitly fenced; both realizations exit the track's own boundaries. → A2.

#### V1 Violation scenario: "Do not change what translates in order to make a metrics test pass"
- **Invariant claim**: `track-10.md:116` — the translator's recognized set is untouchable from inside this track; narrowing coverage to fix a metrics test is an ESCALATE.
- **Violation construction**:
  1. Start state: `byIdLookupSurfacesNullPlan` red because `g.V(rid)` translates (`StartStepRecogniser.normaliseIds`, `:116`) and the boundary surfaces a plan.
  2. Action sequence: Phase B picks item 2's suppress option and implements it as a decline — `StartStepRecogniser` returns its no-claim outcome when `normaliseIds` yields a non-empty id set (one guard, one file).
  3. Intermediate state: under D3, the whole traversal declines; the native pipeline installs `YTDBGraphStep` with pinned ids.
  4. Violation point: the recognized set just narrowed — `g.V(rid)` and every `g.V(ids)` prefix shape stop translating — solely so the capture returns null.
  5. Observable consequence: the test goes green; every by-id-rooted traversal silently loses MATCH planning, plan-cache behavior, and the Track 2-8 equivalence guarantees; nothing in the metrics suite distinguishes this from a genuine capture fix.
- **Feasibility**: CONSTRUCTIBLE — it is the shortest path to green among item 2's listed options. → A2.

#### V2 Violation scenario: plan `### Constraints` "Engine surface is preserved" vs item 1's copy-on-re-arm
- **Invariant claim**: the MATCH engine's IR classes, execution steps, grammar, and evaluators are unmodified except the two named exceptions (plus item 3's pending third).
- **Violation construction attempt**:
  1. Item 1's product side touches `AbstractMatchPlanStep`, `YTDBMatchPlanStep`, `MultiPlanMatchStep` — all in `gremlin/translator/step/`, the translator's boundary package, not the engine.
  2. The copy mechanism it consumes pre-exists: `SelectExecutionPlan.copy` (`SelectExecutionPlan.java:238-276`) and the abstract `ExecutionStepInternal.copy(CommandContext)` (`ExecutionStepInternal.java:250`), implemented by every step including the uncacheable ones (`CountFromClassStep.java:106` — `canBeCached()==false` at `:101` does not remove `copy`).
  3. No engine file needs an edit; the re-arm calls existing public plan API.
- **Feasibility**: INFEASIBLE — item 1 cannot violate the engine-surface constraint as designed; the third exception's scope (two introspection overrides) is exactly the track's full engine touch. Strengthens the track.

#### V3 Violation scenario: multiset equality vs copy-on-re-arm re-iteration
- **Invariant claim**: translator-on and translator-off return equal multisets for recognized shapes, including after `reset()`.
- **Violation construction attempt**:
  1. Native path after close-then-reset: `YTDBGraphStep` re-issues its query per arming (`YTDBGraphStep.java:234-240`) — fresh execution against current tx state.
  2. Translated path under copy-on-re-arm: a fresh plan copy executes against current tx state — also a fresh execution.
  3. Both re-execute; neither replays a stale snapshot, so the multisets diverge only if the underlying data changed between runs, which diverges identically on both paths.
- **Feasibility**: INFEASIBLE for the copy shape (an in-place-restart shape would instead throw or leak, per the applied T2 analysis — which is why copy-on-re-arm is the chosen realization). Strengthens item 1's chosen shape.

#### AT1 Assumption test: Tracks 7/8's realized outputs exist as this track assumes
- **Claim**: `AbstractMatchPlanStep` (Track 7's boundary base with plan-seam hooks), `MultiPlanMatchStep` (Track 8), and the `YTDBQueryMetricsStep` capture path that reads them all exist in the shapes the track's items 1-3 lean on.
- **Stress scenario**: an episode-vs-code drift (the failure mode the prior-episodes input exists to catch) — e.g. the base lacking the hook seam item 1's "fifth hook" framing assumes, or the capture reading a class that no longer exists.
- **Code evidence**: `AbstractMatchPlanStep.java:791-811` — exactly the four abstract plan-seam hooks the Track 7 episode records (`planContext()`, `rewindPlan(ctx)`, `startPlanStream()`, `closePlan()`), plus `resetLifecycleForClone()` at `:552`; `MultiPlanMatchStep` extends the base (sibling of final `YTDBMatchPlanStep`, per D8 revised) with `getPlans()` at `:147`; `YTDBMatchPlanStep.getPlan()` at `:89`; `YTDBQueryMetricsStep.capturedExecutionPlan()` at `:91-107` reads `YTDBMatchPlanStep` first, then `MultiPlanMatchStep.getPlans().getFirst()`, then the `YTDBGraphStep.getLastExecutionPlan()` fallback — exactly the Integration Points description.
- **Verdict**: HOLDS — all three realized outputs match the track's assumptions; item 1's fifth-hook framing fits the existing four-hook seam.

#### AT2 Assumption test: the two-commit attribution (6e657ce2b1 + 3d476357cc) is right
- **Claim**: the Track 4-era commit broke the capture by removing `YTDBGraphStep` from translated traversals; Track 8's commit repaired three scenarios and broke `byIdLookupSurfacesNullPlan`.
- **Stress scenario**: the byId flip caused by a translator change in the #286→tip window rather than the capture change — which would move the defect into "what translates" territory and reopen the track boundary.
- **Code evidence**: `g.V(ids)` translation landed in Track 2 (plan `## Checklist`), long before the #286 bracket — so at #286 the traversal already translated and the test passed *because* the capture still read only `YTDBGraphStep` (absent → null → expected-null green). The failure therefore requires the capture-side change, and `3d476357cc` is the sole intervening edit to `YTDBQueryMetricsStep.java` (track's own reference-accuracy note, `:59`). The mechanism is forced, not just bracketed.
- **Verdict**: HOLDS — with the track's already-recorded caveat that the commit was not run in isolation.

#### AT3 Assumption test: Phase B work stays on the step roster without a written guard
- **Claim**: implicit — the track assumes workflow-level step discipline suffices.
- **Stress scenario**: the twice-observed branch pattern. Items 1-3 each require a mid-implementation Decision Record whose outcome can expand file scope (T2 already grew item 1 from one class to three); step 0 can surface one or two extra failures small enough to tempt an in-track drive-by fix; item 4's dispatch surfaces branch-wide debt. Each is a mid-step fork identical in shape to the ones that produced Track 8's unrostered follow-up commits — where both of its silent-wrong-results blockers originated.
- **Code evidence**: plan Track 8 strategy refresh routes the "unrostered-follow-up-commit review gap" forward "for Track 9" only; `track-10.md` `## Invariants & Constraints` (:115-118) carries no commit-to-roster rule; Validation bullet 2 disposes of failures, not commits.
- **Verdict**: BREAKS — the guard must be written, not assumed. → A1.

#### AT4 Assumption test: `YTDBQueryMetricsStrategyTest` has 20 scenarios
- **Claim**: `## Validation and Acceptance` — "All 20 `YTDBQueryMetricsStrategyTest` scenarios pass".
- **Stress scenario**: a stale count would make the criterion unverifiable as written.
- **Code evidence**: `grep -c "@Test"` over the class returns 20.
- **Verdict**: HOLDS.

#### AT5 Assumption test: the acceptance criteria force the risky re-arm surfaces to be exercised
- **Claim**: implicit in `## Validation and Acceptance` :95-96 — the close-then-reset and no-restart criteria cover item 1's product side.
- **Stress scenario**: a Phase B that adds one `YTDBMatchPlanStepTest` close-then-reset case against a Mockito plan (satisfies :95, singular) and the `verify(copy)`/`never(start)` assertion in the same harness (satisfies :96, "the test"). Every criterion passes; the `MultiPlanMatchStep` re-arm (N-child copy, per-child context derivation under the `:180` assert) and the real-plan prefetch-sub-plan re-run never execute in any test.
- **Code evidence**: `track-10.md:95-96` singular phrasing; `:107` In-scope names `YTDBMatchPlanStepTest` only; `:69`, `:71` — the applied hazard text itself names both subclasses and the context-derivation problem; `MultiPlanMatchStep.java:180` (`-ea` assert reachable only with a real context); `core/pom.xml:36` (`-ea` active in surefire).
- **Verdict**: BREAKS — the criteria admit a green run that skips both named hazards. → A4.

#### AT6 Assumption test: iteration-1 findings are durably applied across all named artifacts
- **Claim**: the review inputs state all prior findings "are already applied to the track file" — and the applied fixes for T6, T1 (checklist half), and R2 named artifacts beyond the track file.
- **Stress scenario**: cross-artifact halves applied only to ephemeral or wrong surfaces die at session end or mislead durable-artifact readers (decomposer, Phase C review-burden check, post-`/clear` resume, `design-final.md` derivation).
- **Code evidence**: `implementation-plan.md:606` at HEAD (`84395668b7`, clean tree) — `**Scope:** ~5 files`; `:609-611` — "the `MatchFirstStep` introspection question", the pre-T1 diagnosis; the Phase-A slim render and `track-10.md:65` both cite `~7–10`; grep for `g.V(rid)`/`byId`/`by-id` over `plan/track-9.md` — zero hits, so R2's accepted JMH-shape hint never landed.
- **Verdict**: BREAKS on the plan file and track-9.md; the track-file halves are applied. → A3.

#### SC1 Scope certificate: footprint, insertions, and the two-sided bound
- **Question**: is `~7–10 files` with the five-item sequence right-sized — under-sized (merge candidate) or heading toward Track 8's retroactive split?
- **Recount**: product 6 (`YTDBQueryMetricsStep`, `AbstractMatchPlanStep`, `YTDBMatchPlanStep`, `MultiPlanMatchStep`, `MatchPrefetchStep`, `MatchFirstStep`) + tests 4 (`YTDBQueryMetricsStrategyTest`, `YTDBMatchPlanStepTest`, `MultiPlanMatchStepTest`, the R6 `getSubSteps`/`EXPLAIN` home) + CI ≥1 + doc edits (plan checklist, `track-9.md`) → 11-13.
- **Insertion estimate**: two one-line accessor overrides, one plan-seam hook plus two implementations, contract-pinned test additions, one CI change — roughly 1-2k insertions, versus Track 8's 5,814 over 38 files. The two unbounded contributors are step 0's failure set and item 4's gate cascade, and both already carry ESCALATE fences (items 0 and 4; Validation bullets 1-2).
- **Two-sided bound**: 11-13 sits above the ~12 merge-candidate line and far under the ~20-25 split ceiling; merging into Track 9 breaches the ceiling (CH1).
- **Verdict**: SURVIVES — right-sized decomposition, not a Track 8 trajectory; only the recorded figure is stale (→ A6), and its durable copy is doubly stale (→ A3).

#### SM1 Simplification challenge: fold step 0 into item 4's `workflow_dispatch`
- **Proposed simplification**: one pipeline dispatch both enumerates the failure set (all modules, both storage envs) and verifies the CI lever — deleting step 0 as a separate local run.
- **Counterargument trace**:
  1. Step 0's purpose is sizing the track *before any fix lands*; a dispatch delivers results at CI latency across three OS legs plus two branch-wide gates — the R5 cascade — so the enumeration arrives entangled with exactly the debt the triage rule is supposed to be decided *before* seeing.
  2. A local `-pl core test` answers the sizing question in minutes against the one suite the acceptance criterion names.
  3. Item 4 runs last by design, after items 1-3 have made the core answer green-or-known.
- **Survival test**: YES — the separation stands; the sequence is the right decomposition. No finding.
