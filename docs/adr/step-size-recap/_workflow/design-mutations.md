# Design Mutation Log

Append-only record of every `design.md` mutation and its review. Not
workflow-sha stamped (the log is append-only by contract; see
`conventions.md §1.6(f)`).

## Mutation 1 — 2026-06-04 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` for the step-sizing and reviewer-routing change (YTDB-1062 plus the workflow-reviewer-triage and workflow-machinery-risk-taxonomy extensions agreed during Phase 0 research). Ten top-level sections: Overview, Core Concepts (eight concepts), Class Design, Workflow, and six content sections covering step sizing (coherence + isolation + fill-toward-~12), the two file-count numbers (~5 medium vs ~12 split), the workflow-machinery risk taxonomy, step-vs-track reviewer routing, `review-bugs-concurrency` across the three review paths, and the mirror / staging / self-application constraints. Decision Records D1–D7 are forward-referenced; the separate plan-derivation session formalizes them (design-first split). Single file, no mechanics companion.

**Mechanical checks** (target=design): PASS. First run surfaced 5 should-fix (1 `dsc-ai-tell` em-dash density at the evidence paragraph, 4 `dsc-ai-tell` fragmented-header where TL;DRs echoed their heading nouns); all fixed; re-run clean (0 findings).

**Cold-read** (scope: whole-doc): PASS. Mental-model verdict YES — a cold reader of `.claude/workflow/**` can build a working model from the document alone. No blockers. 4 should-fix, all applied:

**Findings**:
- [should-fix, applied] References footer shape: footers used the bare `- D-records: D1, D4, …` comma-list; rewritten to per-record glossed form `- D1: <label>` at all eight footers (matches the ytdb-1039 precedent).
- [should-fix, applied] Em-dash discipline, Overview 2nd paragraph: parenthetical em-dash pair around the three-rule list replaced with a colon + sentence split.
- [should-fix, applied] Em-dash discipline, Core Concepts "Prose-only cap": em-dash parenthetical pair replaced with parentheses.
- [should-fix, applied] Em-dash discipline, Workflow TL;DR: em-dash parenthetical pair ("— only if `high` —") recast as a clause with a semicolon.

**Notes**: plan_path / plan_dir intentionally omitted (no plan exists yet — design-first split), so the cross-file `**Full design**` / D-code resolution check is skipped; D1–D7 forward references are validated when the plan session creates the Architecture Notes.

## Mutation 2 — 2026-06-04 — content-edit (design.md)

**Diff summary**: Phase 2 consistency-review finding CR2 (mechanical). In §"Step-vs-track reviewer routing", the sentence naming the `§Maintenance`-mirrored sections of `review-agent-selection.md` listed three sections and omitted `§Workflow-machinery file set`. The live §Maintenance block (`review-agent-selection.md:289-291`) names four, and the derived plan §Constraints (`implementation-plan.md:23`) and `track-2.md:36` both list four. Inserted `§Workflow-machinery file set` as the second element so the parenthetical reads "(`§Workflow-review agents`, `§Workflow-machinery file set`, `§Per-agent file-pattern triggers`, `§Workflow-machinery override`)", matching the live source and the derived plan/track. The design's argument (the triage note stays out of all mirrored sections) is unchanged.

**Mechanical checks** (target=design, scope=bounded): PASS (0 findings).
**Cold-read** (scope: bounded — changed section + the two surrounding sections + Overview + Core Concepts): PASS. Mental-model verdict YES; 0 structural findings. The four-item list parses cleanly and stays consistent with the parallel "mirror set" claims in §"Workflow-machinery risk taxonomy" and §"review-bugs-concurrency across the three review paths".

**Findings**: none.

**Iterations**: 1 of 3 (PASS).

## Mutation 3 — 2026-06-04 — section-add (design.md)

**Diff summary**: Inline-replanning revision after the Track Pre-Flight escalation; introduces decision D8 and a new Track 3. Added the section "Scope indicators measure file footprint, not steps" after "The two file-count numbers", documenting the rewrite of the plan-checklist `**Scope:**` line from `~N steps covering X, Y, Z` to `~N files covering X, Y, Z`: a step count pre-judges Phase A decomposition (steps do not exist until execution), while a planned file count is a plan-time-knowable fact riding the same ~12 / ~5 thresholds. The plan-file-only sizing check in structural and consistency review rekeys from claimed-versus-described to size-versus-norm. Rejected alternatives (line counts as fabricated precision; full removal) are recorded. Coherence edits: §Overview structure roadmap (three → four rule changes, naming the scope-indicator unit) and §Core Concepts (eight → nine ideas, added the "File-footprint scope indicator" gloss). Single file, no mechanics companion.

**Mechanical checks** (target=design, scope=bounded): PASS. First run surfaced 1 should-fix (`dsc-ai-tell` fragmented-header — the TL;DR shared 71% of content words with the heading because the section's subject inherently reuses scope/file/footprint/steps); fixed by rephrasing the TL;DR to avoid four of the six heading words while keeping the concrete before/after; re-run clean (0 findings). A pre-mechanical house-style pass also recast the TL;DR's paired em-dash to a semicolon clause (em-dash cap).

**Cold-read** (scope: bounded — changed section + the two surrounding sections + Overview + Core Concepts): PASS. Mental-model verdict YES; 0 structural findings. Confirmed the "nine load-bearing ideas" count and the "four rule changes" roadmap stay coherent with the added section, and the Core Concepts gloss pointer matches the section heading verbatim.

**Findings**: none surviving.

**Iterations**: 2 of 3 (PASS).

## Mutation 4 — 2026-06-04 — content-edit (design.md)

**Diff summary**: Calibrated D8's track-footprint threshold in §"Scope indicators measure file footprint, not steps". Three coordinated edits (TL;DR, the body sizing-check paragraph, the Edge cases / Gotchas bullet) replace the claim that the footprint "rides the same ~12 / ~5 thresholds the sizing rules use" with a track-level ceiling of ~20-25 in-scope files, distinct from the per-step ~12 / ~5. Rationale: ~12 is the per-step split cap and ~5 the per-step MEDIUM trigger, so a legitimate 5-7-step track aggregates many steps and routinely sits past 12 files; reusing the per-step numbers as the track ceiling would mis-flag normal-sized tracks. D8's core decision (the scope indicator measures planned file footprint, not step count) is unchanged; the per-step ~12 / ~5 (Track 1's domain) are untouched. User-approved mid-Track-3 execution, recorded as DL6 in track-3.md.

**Mechanical checks** (target=design, scope=bounded): PASS (0 findings).
**Cold-read** (scope: bounded — changed section + surrounding sections + Overview): PASS. Mental-model verdict YES; 1 suggestion.

**Findings**:
- suggestion: the TL;DR names only the ~20-25-file footprint ceiling, while the body says the check compares the footprint *and* the coverage-list cardinality against the track-level norm. The TL;DR under-describes the two-dimensional check. Not retried per the suggestion-handling rule; the footprint number is the load-bearing one and reads cleanly. Carried as known debt.

**Iterations**: 1 of 3 (PASS).

## Mutation 5 — 2026-06-05 — content-edit (design.md)

**Diff summary**: Inline-replan for Track 4 (YTDB-1068). Appended a third Edge-cases bullet to §"Constraints: mirror, staging, and self-application" documenting the collision between §1.7 staging and `workflow-reindex.py` rule_1: the validator demands a line-1 `workflow-sha` stamp on every in-scope `docs/adr/`-rooted path, but its `IN_SCOPE_GLOBS` are entirely the staged-workflow mirror, which §1.7(e) mandates be byte-verbatim copies of the unstamped live files (excluded from the stamped set by §1.6(f)). Rule_1 therefore false-positives on every staged copy and `workflow-toc-check.yml --check` fails the gate on a non-draft PR. The fix (D9) exempts the staged subtree via the existing `_STAGED_SUBTREE_PREFIX_RE` — a live `.claude/scripts/` edit outside §1.7 staging scope, so the staged-set invariant I6 is unaffected. Added a matching `- D9: ...` line to the section's References footer, giving DR D9 a resolvable `**Full design**` target. Single file, no mechanics companion.

**Mechanical checks** (target=design, scope=whole-doc): PASS. Mutation 5 trips the periodic whole-doc counter (M1-M4 are all design-touching, non-mechanics-edit), so the cold-read and the per-section shape check ran whole-doc. The first run surfaced one blocker unrelated to this edit — a truncated `**Full design**`-shaped citation in `plan/track-3.md:49` (the DL6 prose read `design.md §"Scope indicators measure file footprint"`, missing `, not steps`) that the whole-doc ref scan exposed; fixed in place to match the canonical heading. Re-run clean (0 findings).

**Cold-read** (scope: whole-doc): PASS. Mental-model verdict YES; 1 suggestion.

**Findings**:
- suggestion: the new bullet names the reuse regex `_STAGED_SUBTREE_PREFIX_RE`, verifiable only against `workflow-reindex.py` (outside cold-read scope). Already satisfied — the orchestrator grounded the symbol against the live script before the edit; the handoff's `_STAGED_PREFIX_RE` was a stale name, and the actual symbol is `_STAGED_SUBTREE_PREFIX_RE` at `workflow-reindex.py:166`.

**Iterations**: 1 of 3 (PASS).

## Mutation 6 — 2026-06-05 — phase4-creation (design-final.md)

**Diff summary**: Phase 4 created the durable `design-final.md` reflecting the as-built state across all four tracks. The structure mirrors the frozen `design.md` (Overview, nine Core Concepts, Class Design, Workflow, and seven content sections), with three reconciliations against execution outcomes that the frozen draft lagged. (1) §"Scope indicators measure file footprint, not steps" now describes the rekeyed structural-review sizing check as plan-file-only by *dropping* its former cross-file track-file read (not "stays plan-file-only"), and states the track-level `~20-25`-file ceiling as distinct from both the per-step `~12`/`~5` and the unrelated `~5-7 steps` track-sizing rule. (2) Its blast-radius list reflects the as-built edit set — convention spec, writers, checkers (including one inline-replan straggler in `track-code-review.md`), renderer — and names the three verify-only files (`implementation-review.md`, `inline-replanning.md`, the `review-workflow-consistency` agent) that carry no format literal and were left unedited. (3) §"Constraints: mirror, staging, and self-application" records the as-built D9: rule_1 kept as a harmless guard with a truthfully rewritten docstring (the §1.6(f) stamped set is enforced by the disjoint startup-precheck drift gate, not by rule_1), plus the orphaned-branch direct-call regression test. Class and workflow diagrams carried over from `design.md` unchanged after verification against the staged workflow files and the live `workflow-reindex.py`. Single file, no mechanics companion. Not stamped (Phase 4 final artifacts are excluded from the stamped set per `conventions.md §1.6(f)`).

**Mechanical checks** (target=design, scope=whole-doc): PASS. First run surfaced 1 should-fix (`dsc-ai-tell` fragmented-header — the §Scope-indicators TL;DR shared 4/7 content words with its heading); rephrased the TL;DR to drop "footprint" and reduce the bare file/steps echo while keeping the concrete `~N files`/`~N steps` before-after; re-run clean (0 findings).

**Cold-read** (scope: whole-doc): PASS. Mental-model verdict YES. All seven comprehension questions answerable from the document alone; all phase4-creation checks pass (plan deviations surfaced in the Overview and §Constraints; diagrams implementation-grounded and consistent with the routing table; zero leaked Track/Step/finding identifiers — only D1–D9 and I6, both restated in `adr.md`); all four Human-reader rules pass. 1 suggestion.

**Findings**:
- suggestion: revert-consequences are recoverable only by synthesizing each section's "Replaces…" clause; a single sentence in the §Constraints TL;DR could centralize the revert blast-radius. Not retried per the suggestion-handling rule; the information is already present section by section. Carried as known debt.

**Iterations**: 1 of 3 (PASS).
