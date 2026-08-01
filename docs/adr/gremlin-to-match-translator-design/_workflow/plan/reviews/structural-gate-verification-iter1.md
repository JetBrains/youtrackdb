<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: S7, sev: should-fix, loc: "implementation-plan.md § Architecture Notes → #### Component Map (the YTDBQueryMetricsStep intent bullet, lines 120-125)", anchor: "### S7 ", cert: "", basis: "the bullet the S5 fix added runs 6 lines against the ~5-line component-intent cap and drops an article in 'the three contracts that shift exposed'"}
verdicts:
  - {id: S1, verdict: VERIFIED}
  - {id: S2, verdict: VERIFIED}
  - {id: S3, verdict: VERIFIED}
  - {id: S4, verdict: VERIFIED}
  - {id: S5, verdict: VERIFIED}
  - {id: S6, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Verification certificates

All six iteration-1 findings are resolved as the user directed. Five fixes landed
in the plan and track files; S6 correctly left `design.md` untouched. One new
should-fix falls out of the S5 fix itself — the Component Map bullet it added
runs one line over the component-intent cap and drops an article. Overall PASS:
no new blocker, and the S7 fix is a single mechanical re-wrap.

One carry-forward the orchestrator still owes: the S6 Phase-4 record is **not yet
on disk**. `plan-review.md` was last written 2026-07-30 and carries no 2026-08-01
re-validation section, so neither frozen-design gap is queued anywhere durable
yet. By convention that section is written when the gate closes, so this is
sequencing rather than a missed fix — but it is the whole point of S6, so it is
called out here and in the summary rather than left to memory.

#### Verify S1: Track 9 checklist intro over the 1-3-sentence cap
- **Original issue**: The Track 9 entry's intro paragraph ran five sentences against the 1-3-sentence cap, having grown to four before this session's consistency pass added the POST_UNION relaxation sentence as a fifth.
- **Fix applied**: Trimmed to three sentences. Sentence 1 kept verbatim; sentence 2 compressed to the terminator list plus the mid-traversal decline rule, dropping the `projectOrSkip` / `TailGlobalStepContract` / `n=0` / `n<0` parentheticals; the standalone POST_UNION sentence deleted; the Cucumber/JMH sentence merged with the `plan/track-9.md` pointer via an em-dash.
- **Re-check**:
  - Plan location: `implementation-plan.md` § Checklist, Track 9 entry, lines 608-614 (the block between the `- [ ] Track 9:` line and `**Scope:**`).
  - Current state: three sentences. (1) "Completes and validates the feature." (2) "Adds the four list-shaping terminators (`fold` / `unfold` / `reverse` / `tail`) as last-step recognisers driving the Track 7 ordered post-process, with mid-traversal use declining under D3." (3) "Then the full TinkerPop Cucumber suite green with the strategy registered (the first whole-feature gate over every prior track's recognisers) and a Gremlin-on-vs-off JMH baseline pinned to verified-recognised shapes — detail in plan/track-9.md."
  - Criteria met: TRACK DESCRIPTIONS — intro within the 1-3-sentence cap, followed by `**Scope:**` then `**Depends on:**`, matching the shape enforced against Tracks 4 and 6 in earlier passes.
- **Regression check**: Every displaced fact still has a live home. The POST_UNION relaxation is on the `**Scope:**` line ("the post-union suffix allow-list relaxation") and in `plan/track-9.md` Plan-of-Work item 4, its acceptance bullet, and its in-scope / out-of-scope / signatures lines. `BoundaryOutputType.LIST` and drain / ring-buffer stay on the `**Scope:**` line. `TailGlobalStepContract`, `n=0`, and `n<0` are in `plan/track-9.md` Plan-of-Work item 3 and § Validation and Acceptance. The `projectOrSkip` switch is in item 2. Nothing became unreachable. The reworded "over every prior track's recognisers" (was "over all six tracks'") stays true with Track 10 inserted, since Track 10 adds no recogniser. Checked § Checklist, `plan/track-9.md`, and § Implementation state — clean.
- **Verdict**: VERIFIED

#### Verify S2: Track 10 `**Scope:**` line carried no file footprint
- **Original issue**: Track 10's `**Scope:**` line restated its Plan-of-Work items with no `~N files` figure, so the two-sided sizing bound could not be applied and Phase A had no plan-time effort signal. Every other Scope line in the plan carries one.
- **Fix applied**: The line now opens `**Scope:** ~5 files covering …` and lists the five in-scope artefacts.
- **Re-check**:
  - Plan location: `implementation-plan.md` § Checklist, Track 10 entry, lines 594-599.
  - Current state: "**Scope:** ~5 files covering the metrics plan capture, the `AbstractMatchPlanStep.reset()`-from-`CLOSED` contract (the one genuine product defect — re-iteration yields `[]` where native re-executes), the `g.V(rid)` plan-capture contract, the `MatchFirstStep` introspection question (decided **after** establishing whether the index is still used at all), and the CI detection hole."
  - Criteria met: SCOPE INDICATORS — an approximate planned file footprint plus a brief list of what those files cover, in the same `~N files covering …` shape as Tracks 1, 2, 6, and 9.
- **Regression check**: The figure reconciles with `plan/track-10.md` § Interfaces and Dependencies **In scope**, which lists exactly five artefacts: `YTDBQueryMetricsStep`, `AbstractMatchPlanStep` (conditional on step 1), `MatchFirstStep` (conditional on step 3), `YTDBQueryMetricsStrategyTest`, and the CI/draft-PR detection gap. No sizing finding follows, per the finding's own instruction and the state of the document: the under-floor footprint is refused in both fold directions in writing — the `**Why a separate track:**` block and `plan/track-10.md` DR-M1 against folding back into Track 8, the Purpose paragraph and the inter-track dependency line against folding forward into Track 9. The two "decided after" and "(conditional)" hedges mean the real footprint could land at 3 or at 5; `~5` is the honest upper reading and matches how Track 9's `~15-21` handles the same uncertainty. Checked § Checklist, `plan/track-10.md` § Interfaces and Dependencies, § Implementation state — clean.
- **Verdict**: VERIFIED

#### Verify S3: Track 10's work items sat in the Phase-A-owned `## Concrete Steps` slot
- **Original issue**: `plan/track-10.md` inverted the template — `## Plan of Work` held a two-sentence strategy note while the four numbered work items sat under `## Concrete Steps`, a section `conventions-execution.md` §2.1 declares Phase-A-written and immutable after Phase A.
- **Fix applied**: The four items moved verbatim into `## Plan of Work` under the existing lead; `## Concrete Steps` restored to `<!-- Phase A placeholder. -->`.
- **Re-check**:
  - Track-file location: `plan/track-10.md` § Plan of Work (lines 56-65) and § Concrete Steps (lines 67-68).
  - Current state: `## Plan of Work` opens with the unchanged two-sentence lead, then items 1-4 in their original order and wording (item 3 additionally carries the S4 paragraph). `## Concrete Steps` holds only `<!-- Phase A placeholder. -->`, byte-identical to `plan/track-9.md` line 58.
  - Criteria met: REQUIRED SECTIONS / template conformance — both pending track files now carry all 15 sections in the canonical order (verified by heading grep: Purpose, Progress, Surprises, Decision Log, Outcomes, Context, Plan of Work, Concrete Steps, Episodes, Validation, Idempotence, Artifacts, Interfaces, Invariants, Base commit), and the Phase-A-owned slot is empty in both.
  - The renumbering was identity, so no cross-reference moved.
- **Regression check**: Grepped every `step N` reference in the file. § Validation and Acceptance "The index-usage question from step 3" (line 77), § Interfaces and Dependencies "if step 1 lands on the product side" and "if step 3 lands on the product side" (line 86), and the new § Invariants & Constraints bullet "bears on step 3" (line 97) all still resolve to the same items. The § Surprises reference to "Track 8 Step 2" is a cross-track pointer, unaffected. `plan/track-9.md` has no numeric step cross-references at all, so its own 4→5, 5→6, 6→7 renumbering (from the CR pass inserting the POST_UNION item at position 4) broke nothing. No `- [ ] Step:` roster glyphs appear in either file; the only `- [ ]` entries are the four `## Progress` checkboxes, so `workflow-startup-precheck.sh` still short-circuits both tracks to `decomposition-pending`. Clean.
- **Verdict**: VERIFIED

#### Verify S4: engine-surface constraint vs Track 10's `MatchFirstStep` introspection option
- **Original issue**: The plan's `### Constraints` "Engine surface is preserved" bullet freezes the MATCH execution steps with two named exceptions; Track 10 item 3 contemplates adding `getSubSteps()` / `getSubExecutionPlans()` overrides to `MatchFirstStep`, a MATCH execution step covered by neither exception. The failure mode was silent — a decomposer reading the constraint as binding closes the two scan tests test-side and the branch permanently loses its only Gremlin-level index-usage assertion.
- **Fix applied**: User resolved to leave the call to Track 10 Phase A. Item 3 gained a paragraph making the tension explicit, and `## Invariants & Constraints` gained a cross-referencing bullet. The plan's Constraints bullet was deliberately left unamended.
- **Re-check**:
  - Track-file location: `plan/track-10.md` § Plan of Work item 3, the sub-paragraph at line 64; § Invariants & Constraints, third bullet at line 97.
  - Current state: the paragraph names the `### Constraints` "Engine surface is preserved" bullet, quotes its scope ("the execution steps") and both named exceptions (D-TEXT-OPS AST nodes, the count short-circuit refactor), states that `MatchFirstStep` is a MATCH execution step, spells out the silent-default failure mode, gives resolution (a) amend the Constraints bullet with a third exception on behavior-neutrality grounds and (b) close the two scan tests test-side and record why the freeze outweighs the assertion, and declares that taking (b) without recording the trade is an ESCALATE. The Invariants bullet points at the same step for the two resolutions and the ESCALATE condition.
  - Criteria met: the contradiction is no longer silent. A Phase A decomposer reading either `## Plan of Work` or `## Invariants & Constraints` — the two sections the Phase A reviewers read — hits the collision and cannot default past it without a recorded decision.
  - The plan's Constraints bullet (lines 40-44) is byte-unchanged, as intended: amending it now would pre-decide resolution (a).
- **Regression check**: The paragraph's quotations match the source. Plan lines 42-44 read "Existing constructors, the IR classes, the execution steps, the grammar, and the evaluators are not modified — except the two new string-predicate AST nodes in D-TEXT-OPS and the count short-circuit refactor" — the scope phrase and both exceptions are reproduced accurately. The new Invariants bullet sits on a different axis from the two existing ones (translator coverage; multiset equality), so the section carries three non-overlapping rules and no self-contradiction; both ESCALATE conditions are distinct and can fire independently. The § Validation and Acceptance line "The index-usage question from step 3 is answered explicitly, not left open" is reinforced, not duplicated — it demands the index-usage answer, the new paragraph demands the constraint-resolution answer. The § Signatures line already flags that `containsStepOfType` recurses through `getSubSteps()` only, so a decomposer choosing (a) knows overriding `getSubExecutionPlans()` alone would not satisfy the test. Checked § Constraints, § Integration Points, the Component Map, `plan/track-10.md` in full — clean.
- **Verdict**: VERIFIED

#### Verify S5: Component Map carried no node for the components Track 10 modifies
- **Original issue**: The plan's one cross-track impact-assessment artefact showed a pending track with an empty blast radius — no node or bullet for the query-metrics capture layer.
- **Fix applied**: User resolved to add the node and bullet. A `Metrics["YTDBQueryMetricsStep\n(plan capture, T10)"]` node, a `Boundary -. read by .-> Metrics` edge, and a seventh annotated bullet.
- **Re-check**:
  - Plan location: `implementation-plan.md` § Architecture Notes → `#### Component Map` — node at line 85, edge at line 95, bullet at lines 120-125.
  - Current state: the node is declared at flowchart top level beside `Half` and `GQL`; the edge crosses out of the `TPside` subgraph using the same `-. label .->` dotted form as the existing `Strat -. declines .-> Half`; the bullet describes the capture-source shift and names Track 10's three contracts.
  - **Mermaid parse confirmed by rendering, not by inspection.** The block was extracted and rendered with `@mermaid-js/mermaid-cli@11` (`mmdc -i … -o …`); it produced a 33 KB SVG with no parse error, and both `YTDBQueryMetricsStep` and the `read by` edge label appear in the output. The cross-subgraph edge is legal and the `Metrics` identifier collides with no Mermaid keyword.
  - Criteria met on the completeness half of the criterion ("only touched components plus immediate neighbors"; "every component annotated with what changes and why") — the map now covers every component a pending track modifies. The length half is not met; see S7.
- **Regression check**: The bullet's "three contracts" matches Track 10's three contract-settling items (`reset()`, `g.V(rid)` capture, introspection); the fourth Plan-of-Work item closes the CI detection hole, which is not a contract, so the count is right and does not contradict the `~5 files` Scope line that includes it. The bullet's framing of the capture as a read-only observer agrees with § Integration Points lines 369-373 (reads off the boundary step, falls back to `YTDBGraphStep.getLastExecutionPlan()`) and with `plan/track-10.md` § Context and Orientation. Node/bullet correspondence did not regress: `Cache`, `Half`, and `GQL` still carry no dedicated bullet, which is the pre-existing state, not something this edit introduced. Checked the flowchart, all seven bullets, § Integration Points, and § Constraints — clean apart from S7.
- **Verdict**: VERIFIED

#### Verify S6: frozen-design gaps recorded, not patched
- **Original issue**: `design.md` § "Boundary-step lifecycle" states no `reset()` / re-iteration contract, and § "Observable behavior changes" omits the metrics-capture change. Both are Phase-4 reconciliation items against a frozen document.
- **Fix applied**: None, by design. The resolution was record-only.
- **Re-check**:
  - Design location: `design.md` § "Boundary-step lifecycle" (lines 1858-1941) and § "Observable behavior changes" (lines 1942-1986).
  - Current state: `design.md` is byte-identical to `HEAD` — `git diff HEAD -- …/design.md` is empty, the file is absent from `git status --porcelain`, and its mtime is 2026-07-30, before this session. The freeze held.
  - Criteria met: the finding demanded no mutation and got none.
- **Regression check**: No plan or track text was added that presumes either gap is filled. `plan/track-10.md` still frames the `reset()` question as open and needing a Decision Record either way, which is the correct posture against a design that says nothing. **One carry-forward is outstanding**: `plan-review.md` has no 2026-08-01 re-validation section, so neither gap is queued for Phase 4 on disk yet. The file's own convention writes that section when the gate closes — the 2026-07-15 CR1 deferral and the 2026-07-27 provenance note both appear in sections written at gate close — so this is sequencing, not a dropped fix. It is flagged here and in § Summary because the record *is* the fix for S6; if the closing write omits it, S6 silently reverts to unrecorded.
- **Verdict**: VERIFIED

## Findings

### S7 [should-fix]
**Location**: `implementation-plan.md` § Architecture Notes → `#### Component Map`, the `**YTDBQueryMetricsStep**` intent bullet (lines 120-125).
**Issue**: Two defects in the bullet the S5 fix added.

1. It runs six source lines against the `~5`-line component-intent cap. The criterion caps a component's intent bullet at ~5 lines counted from the `-` through the final continuation line; this one spans 120-125. The five pre-existing bullets other than the boundary bullet run 2-4 lines. The 7-line boundary bullet is over too, but the previous pass cleared it on the recorded ground that commit `3c4f17d752` already trimmed it from 9 lines as a prior structural fix — an excuse specific to that bullet, not a general tolerance, and the S5 fix instruction itself asked for the new bullet to land inside the budget.
2. "Track 10 settles the three contracts that shift exposed" drops an article. The intended reading is "the three contracts that **the** shift exposed" — the shift being the capture-source move the preceding sentence describes. As written the clause parses with "shift" as a verb and no object, which is not the meaning.

**Proposed fix**: One re-wrap that closes both. For example:

```markdown
- **YTDBQueryMetricsStep** — a read-only observer, not a built component. Its
  `capturedExecutionPlan()` read `YTDBGraphStep` until translation removed that
  step, so it now reads the boundary step. Track 10 settles the three contracts
  the shift exposed: `reset()` from `CLOSED`, `g.V(rid)` capture, and
  nested-plan introspection.
```

Any wording works so long as it lands at five lines or fewer and the article is repaired. All three contracts and the capture-source shift must survive the trim — they are what the bullet exists to record, and § Integration Points and `plan/track-10.md` are the only other places that carry them.
**Classification**: mechanical
**Justification**: §`mechanical` — "component-intent length — `mechanical` (long-form material moves to the matching track's `## Context and Orientation`)", and the missing article is an obvious typo. The re-wrap changes no plan intent: the same three contracts and the same capture-source shift are stated in fewer words.

## Evidence base

No certificates. This is a plan-internal structural verification with no codebase read (`certs: 0`). The one tool-backed check is the Mermaid render for S5: the `flowchart TB` block was extracted from `implementation-plan.md` and rendered with `@mermaid-js/mermaid-cli@11` to `/tmp/g2m-compmap.svg`, which succeeded and contains both `YTDBQueryMetricsStep` and the `read by` edge label.

Checked and cleared, recorded so the next pass does not re-litigate:

- **The consistency pass (CR1-CR7) introduced no structural regression.** § Implementation state prose reads "Tracks 1-8 are executed and complete; Track 10 and Track 9 are not started, in that execution order" and its table lists row 10 before row 9, matching the `## Checklist` physical order; Track 8's row and the decision-conformance paragraph both drop the stale "Phase C pending". The fifth § Integration Points bullet is present and agrees with `plan/track-10.md` § Context and Orientation. Track 9's absorption of the POST_UNION relaxation is coherent across its Plan-of-Work item 4, its new acceptance bullet, its in-scope / out-of-scope / signatures lines, and the plan Scope line, with the file-count band bumped `~14-20` → `~15-21` for the walker edit. `**Depends on:** Tracks 7, 8, and 10.` is in place.
- **Dependency graph is acyclic and the execution order still resolves.** 8 → 10 → 9, with 7 → 9, rooted on completed tracks. The nine documentation positions carrying the descending 10-before-9 order (recorded in the iteration-1 evidence base) are unchanged, and the first-`[ ]`-in-document-order walk still selects Track 10.
- **Sizing unchanged and in bounds.** Track 9 at ~15-21 files sits inside the soft two-sided range (floor ~12, ceiling ~20-25). Track 10 at ~5 is under the floor with written justification on both fold directions — see the S2 certificate.
- **Track-overlap rule does not fire.** Tracks 10 and 9 both touch the boundary base (`AbstractMatchPlanStep`), and they are consecutive in execution order. Track 10's other targets (`YTDBQueryMetricsStep`, `MatchFirstStep`) are touched by no other pending track.
- **Plan-file budget.** 642 lines / ~41 KB (~10.5K tokens), well under the ~1,500-line / ~30K-token roll-up. The S5 and CR edits added 4 net lines.
- **Ragged wrap on the Track 9 Scope line.** Line 619 ends at "terminator-composition +" around column 50 after the CR insertion. Cosmetic only — no structural criterion covers source-line fill — and worth folding into the S7 re-wrap if the orchestrator is editing that region anyway.
- **Design-document diagram sizes still exceed the soft caps** (21 classes against ~12; 11 sequence participants against ~8). Frozen, unchanged, and passed four prior structural gates without a finding. Unactionable before Phase 4; not filed.

## Summary

**PASS.** S1-S5 VERIFIED as applied fixes; S6 VERIFIED as a correctly-honored freeze. No new blocker.

Two items for the orchestrator:

1. **S7 (should-fix, mechanical)** — re-wrap the Component Map's `YTDBQueryMetricsStep` bullet to five lines or fewer and repair "the three contracts that shift exposed" → "that the shift exposed". One edit.
2. **The S6 Phase-4 record is not yet on disk.** `plan-review.md` carries no 2026-08-01 section. When it is written, both frozen-design gaps must be queued alongside the existing deferrals (CR1 of 2026-07-15; the `MultiPlanMatchStep : "Track 6"` / `extends YTDBMatchPlanStep` provenance of 2026-07-27): the missing `reset()` / re-iteration contract in § "Boundary-step lifecycle", and the missing metrics-capture entry in § "Observable behavior changes". Recording them is the entirety of S6's fix.
