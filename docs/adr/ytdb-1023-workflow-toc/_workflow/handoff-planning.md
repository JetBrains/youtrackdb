# Handoff: Phase 1 — design refinement, mid-mutation-sequence

**Paused:** 2026-05-27
**Phase:** 1 (planning, mid-design-refinement)
**Context level at pause:** warning
**Branch:** ytdb-1023-workflow-toc
**HEAD:** ccb1882f1d "Disable publish-action prior-commit lookup to fix flaky 403 (#1094)"
**Unpushed:** <no upstream — first push of branch will set it; see workflow.md §What to do before ending a session>

## What I was investigating

Refining the per-document TOC + per-section role/phase annotation design (YTDB-1023). The user identified several internal contradictions and fragility risks in the existing Phase 1 design; we worked through alternatives and landed on the **X3 model** as the locked direction. Mutation 2 (drop `requires` marker = D3) landed; 2 more mutations queued before this phase closes.

## Already ruled out

- **`requires` marker (D3)** — dropped as fragile. Author classification is subjective; reflexive `requires=none` is a known failure; body-vs-marker drift caught only as a warning; TOC summary cell clutters with inline `requires §X.Y`. Cost-benefit: marker eliminates one extra round-trip Read per inter-section dep, which is cheap under lazy loading. Verdict in `design-mutations.md` Mutation 2.
- **D1 (lightweight `### ` suffix only, no TOC entry)** — loses `### `-level Read savings since the reader must load the parent `## ` body to see `### ` annotations. Filter applies after load, not before. Cognitive aid only.
- **Z (auto-stamped `:roles:phases` on in-text refs, `## `-only TOC)** — adds inline suffix tokens without unlocking `### `-level Read calls. Same load-then-filter property as D1. Costs more tokens than X3 per session under lazy loading.
- **Y (per-`## ` sub-TOCs + global `## `-only TOC)** — TOC tokens amortize per-section but each `## ` access costs one extra Read tool call for the sub-TOC. Tool-call overhead exceeds the per-section token saving for typical 2-3-section loads. Y wins only on sparse single-section sessions.
- **Refs-only direction (drop TOC entirely)** — creates an offset-computation problem (no implicit line-range lookup) and breaks cold-entry discovery for agents without precise pre-stamped refs. Either Grep-first per Read or script-stamped line ranges (brittle on edits). Cost > saving.
- **Role compression (`O` / `OR` instead of `orchestrator`)** — token saving is real (~700-2000/session under lazy load) but adds bootstrap-table overhead (~150 tokens) plus ongoing author friction. Marginal win; the 7 reviewer roles all start with R so single-letter codes need 2-3 chars anyway. Skipped.

## Most promising lead

**X3 model** — the locked direction. Detailed schema:

- Global TOC region per file under H1, between `<!--Document index start-->` and `<!--Document index end-->`. Four columns: `Section | Roles | Phases | Summary`.
- TOC carries one row per **every `## `** AND **every `### `** heading. No author-judged granularity. CI rule rebuilds the TOC mechanically from heading annotations.
- Heading annotation comments on every `## ` and every `### `: `<!-- roles=... phases=... summary="..." -->`. Fields uniform across both heading levels.
- File-level cross-file refs: `name.md:roles:phases` suffix in SKILL.md startup read-lists and `.claude/agents/*.md` outgoing workflow-doc refs. Sub-section precision allowed: `conventions.md§1.6(c):migrator:1`.
- In-file `§X.Y` and `§X.Y(z)` references auto-stamped by the reindex script (`workflow-reindex.py --write`) with the matching `:roles:phases` suffix derived from the target heading. Author writes plain refs; script stamps; drift mechanically detectable.
- `CLAUDE.md`, Phase 4 final artifacts, ephemeral `_workflow/**` artifacts, and non-workflow skills all out of scope.

Token math on `conventions.md` (~10K-token file under typical 3-section load): X3 lands ~2K-3K tokens of metadata + content per session, vs ~10K full-file today. ~70-80% Read-share reduction on this file.

## Open questions

- **Task 2**: Lock `### ` granularity to "every `## ` and every `### `" in design.md. Resolve four contradiction sites: Overview line 8 (says "every"), Core Concepts → TOC region line 20 (says "selected `### ` where the author granularizes"), TL;DR line 38 (says "every"), Reindex CI rule 4 (currently allows optional `### ` annotations). After this: every `### ` carries an annotation comment AND appears in the TOC mechanically.
- **Task 3**: Add the in-file ref auto-stamping convention to design.md. Update Cross-reference convention section (or add subsection) describing the script's auto-stamp of in-text `§X.Y(z)?` references. Add a new validation rule to the Reindex script's rule list. Update the Read-decision flow Mermaid if needed.
- **Bootstrap heading `## Reading workflow files (TOC protocol)` interaction**: this `## ` heading appears in the bootstrap block which sits between frontmatter and H1 on the 7 SKILL.md + 11 prompts. The reindex script's rule 3 (every `^## ` heading has a TOC row) would either (a) trip on this heading, demanding a TOC row, or (b) need an explicit exception. Design doesn't state which. Unresolved corner.
- **Out-of-scope clarification section**: add an explicit "Files / surfaces out of scope" section listing every excluded category (agents w/o TOC, CLAUDE.md, Phase 4 artifacts, ephemeral `_workflow/**`, non-workflow skills, files with no `## `).

## Raw notes / partial findings

- Mutation 2 (drop `requires` marker) landed cleanly. `design.md` carries 8 Core Concepts (was 9), Read-decision flow Mermaid simplified, `## requires marker discipline` section removed entirely. Cross-ref cleanup propagated to `implementation-plan.md` (D3 block removed, I4 invariant deleted, I5/I6 renumbered to I4/I5, non-goal removed, Component Map REQ node dropped, Track 1 scope quote rewritten) and `plan/track-{1,2,4}.md` (Purpose, Plan of Work, Validation/Acceptance, audit-agent extensions). Two remaining `requires` tokens are pure English-verb usage in unrelated prose (verified by cold-read iteration 2).
- Mechanical checks: PASS, 0 blockers. 8 pre-existing fragmented-header should-fix findings at lines 36, 81, 233, 394, 444, 455, 461, 486 — carried forward as known debt, unrelated to this mutation sequence.
- Cold-read iteration 1 returned 3 blockers (residues in `implementation-plan.md` lines 69, 135, 149, 159-160 and `plan/track-1.md` lines 6, 10, 61, 62, 84 plus the dangling I4). All fixed in iteration 2. Cold-read iteration 2: PASS.
- Mutation 2 log entry appended to `design-mutations.md`.
- Tasks #1 (drop `requires`) and #4 (implementation-plan.md cleanup) completed; the cleanup got pulled forward into Mutation 2's same-mutation cross-ref discipline rather than being a separate edit pass.

## Resume notes

- **Do NOT re-explore**: `requires`-vs-no-`requires` (decided — dropped); TOC structure choice (X3 picked vs A / B / C / D / X3 / Y / Z / refs-only); role compression (skipped); cross-file `§X.Y` link suffix question (resolved — in-file refs stay plain and are auto-stamped, cross-file refs carry the suffix).
- **Do NOT redo**: Mutation 2's edits to `design.md`, `implementation-plan.md`, or `plan/track-{1,2,4}.md`. All landed and verified.
- **Next action on resume**: Run Task 2 via `/edit-design content-edit` to lock `### ` granularity to "every" in `design.md`. Fix Overview line 8 (already says "every" — no edit), Core Concepts TOC region blurb at line 20 (says "selected `### ` where the author granularizes" — change to "every `### `"), TL;DR line 38 (already says "every" — no edit), and Reindex CI rule 4 (rewrite to require `### ` annotation density mechanically). Confirm `### ` rows are part of the global TOC rebuild semantics. Update the example TOC table in `### Idiom shape` to show at least one `### ` row alongside the `## ` rows.
- **After Task 2**: run Task 3 (add in-file ref auto-stamping) and Task 5 (final verify) per the original task list.
- The user will resume by running `/create-plan` (Phase 1 = `/create-plan`-owned). The startup will detect this handoff via `ls handoff-*.md` in Step 1a, and per the resume protocol present the body above and ask the user to choose `proceed with Next action on resume` / `redirect` / `pause again`.
