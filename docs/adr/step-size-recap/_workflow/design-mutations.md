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
