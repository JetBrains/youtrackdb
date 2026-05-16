# Track 4: Update readers (workflow docs, sub-agent prompts, remaining references)

## Purpose / Big Picture

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Update every code path that reads a per-track file by section heading: Phase A (`track-review.md`), Phase B readers (`step-implementation.md`, `implementer-rules.md`, `step-implementation-recovery.md`), Phase C (`track-code-review.md`), all sub-agent prompts, plus `workflow.md` startup and the remaining workflow docs. Readers that today grep a step's inline blockquote (for risk tag, for episode fields like What-was-done / Key files) now perform a **section-join**: read `## Concrete Steps` for the plan fields (risk tag, description, status); read `## Episodes ### Step N` for the episode fields per D11 (`## Artifacts and Notes` is reserved for cross-step content only). Join key is step number; commit SHA is the secondary key. Ends with a manual `/create-plan` smoke test against a synthetic task and a final grep verification across the entire `.claude/` tree.

## Progress
<!-- Phase A has not yet decomposed this track. Phase A writes the first Progress entry on decomposition complete. -->

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1; populated by Phase C review iterations + completion. -->

## Context and Orientation

Reader code paths across `.claude/workflow/` and `.claude/workflow/prompts/` still name the legacy section headings (`## Description`, `## Steps`, `## Reviews completed`) and grep per-step blockquotes for episode content. Track 2 (atomic shape switch per D13) has migrated this branch's own track files to the new 14-section shape and rewired the episode-writer; Track 3 has updated the writer SKILLs. The remaining gap is the reader half — every reader that today expects `## Description` finds nothing under the new shape and either falls back to a whole-file read or returns vacuous output. The transient-reader-staleness window from Track 2 step 6's HEAD to Track 4's HEAD is documented in Track 2's Constraints; Track 4 closes that window.

## Plan of Work

Update each reader in turn. Phase A reader (`track-review.md`) is the largest single file — every `## Description` reference splits across the four new Phase 1 track-level sections (`## Purpose / Big Picture` + `## Context and Orientation` + `## Plan of Work` + `## Interfaces and Dependencies`). Phase B readers (`step-implementation.md` reader half, `implementer-rules.md`, `step-implementation-recovery.md`) batch update via `steroid_apply_patch`. Phase C reader (`track-code-review.md`) gets the section-join pattern for per-step episode reads. Sub-agent prompts (`.claude/workflow/prompts/*.md`) update for the new section names. `consistency-review.md` and `structural-review.md` carry the largest content edits. `workflow.md` startup updates path references. End with a manual `/create-plan` synthetic smoke test against a sentinel directory and the final grep verification across the entire `.claude/` tree.

## Concrete Steps

<!-- Phase A decomposition has not yet run. The Concrete Steps roster is populated by Phase A from the Plan of Work above. -->

## Episodes
<!-- Continuous-log. Empty until Phase B writes the first step block. -->

## Validation and Acceptance
<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — Phase A names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Cross-step artifacts only. Empty until cross-step content surfaces. -->

## Interfaces and Dependencies

**In-scope files**: every workflow doc and prompt not already updated by Tracks 1, 2, or 3. Specifically: `track-review.md`, `step-implementation.md` (reader half), `implementer-rules.md`, `step-implementation-recovery.md`, `track-code-review.md`, every `prompts/*.md`, `workflow.md`, `episode-format-reference.md`, `risk-tagging.md`, `mid-phase-handoff.md`, `plan-slim-rendering.md`, `review-mode.md`, `ephemeral-identifier-rule.md` (verification only), `design-document-rules.md` (verification only).

**Out-of-scope**: workflow-review triage update (Track 1), spec files (Track 2), writer SKILLs (Track 3), `step-implementation.md` sub-step 7 writer half (Track 2 step 6 per D13).

**Coordinated with Track 2 step 6 on `step-implementation.md`**: Track 2 step 6 (atomic shape switch per D13) lands sub-step 7's writer-half edit and the on-disk shape migration; Track 4 picks up Track 2's HEAD as base. Verify Track 2 step 6's writer-half edit is intact after Track 4's reader-half edits land.

**No section-content semantics changes**: only section names change; the logic each reader implements stays identical. This is a renaming sweep, not a behavioral rewrite. Resist scope-creep into rewriting the readers' semantics.

**Smoke test discipline**: the `/create-plan` synthetic run must NOT pollute `docs/adr/`. Use a sentinel directory name (e.g., `_smoke-test-ytdb-817`) and delete it after the smoke test passes. The smoke-test invocation is part of Track 4 step 5; the deletion is the final commit in Track 4 (or a fold into step 5's commit if no other content remains).

**Markdown-only changes**: no Java, no Maven, no tests.

**Inter-track dependencies**:
- **Depends on Track 1** (workflow-review triage) for correct Phase C dispatch on this track's diff, **Track 2** (spec + atomic shape switch per D13 — by Track 4 start, this branch's track files are already on the new shape and the episode writer already follows the multi-section convention), and **Track 3** (writer-SKILL changes).
- Shares `step-implementation.md` with Track 2 step 6 (writer half) — Track 4 owns the reader half; see In-scope files above.
- The final grep verification gates Phase C of this track. A non-zero match count is a blocker — implies a missed reference somewhere across the four tracks.
- The smoke test exercises the entire writer + reader chain — it's the closest thing this plan has to an integration test. If it fails, the failure is almost certainly a missed section-name reference; rerun the grep verification narrowed to the surfaced section name.

## Base commit
<!-- Phase B writes this once at session start. Empty at Phase 1. -->
