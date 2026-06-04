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
