# Track 3: Update writer SKILLs (`/create-plan`, `/review-plan`, inline-replanning, track-skip)

## Purpose / Big Picture

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Update every writer SKILL that authors or amends a per-track file or the root index: `/create-plan` SKILL gets the new track-file template; `/review-plan` is a thin wrapper to verify; `inline-replanning.md` cases 2–6 get section-name updates against the new shape (case 1 was already rewired in Track 2 step 6's atomic switch + Phase C iter-1 review-fix WI3); `track-skip.md` picks up section-name updates. **The episode-writer rewire that originally lived here as step 5 (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) has moved into Track 2's atomic shape switch (D13)** so the writer logic, the on-disk shape, and this branch's own track files all change in one commit. Track 3 is now strictly the writer-SKILL section-name + template update — the orchestrator-driven episode-write logic is already on the new shape by the time Track 3 starts, and `inline-replanning.md` case 1 is already on the new shape.

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

The writer SKILLs today still emit the legacy five-section shape (`## Description` / `## Progress` / `## Reviews completed` / `## Base commit` / `## Steps`) and reference legacy section names in their step-by-step prose. Track 2 has already landed the new spec (`conventions.md`, `conventions-execution.md` §2.1, `planning.md`, `design-document-rules.md`) describing the 14-section ExecPlan template, has renamed the on-disk directory and prose terminology, and via its atomic shape switch (D13) has migrated this branch's own track files plus rewired the episode-writer (`step-implementation.md` sub-step 7 + `episode-format-reference.md` + the D12 canonical write order across every Progress writer). Track 2 step 6 atomic switch + Phase C iter-1 review-fix WI3 also rewired `inline-replanning.md` **case 1** (new-track inline-replan) to author the 14-section shape. The remaining gap: `create-plan/SKILL.md` still embeds the legacy template; `review-plan/SKILL.md`, `track-skip.md`, and `inline-replanning.md` cases 2–6 still reference legacy section names (`## Description`, `## Reviews completed`, `## Steps`); and two line-broken "step file" residuals escaped Track 2 step 2's terminology rename in `inline-replanning.md` (cases 3 and 6). Track 3 closes those gaps.

## Plan of Work

Update each writer SKILL in turn. `/create-plan` SKILL is the largest edit — Step 4's embedded track-file template block rewrites to the 14-section shape. (Step 1b's `mkdir` line was already updated to `_workflow/plan/` in Track 2 Phase C iter-1 review-fix WC1.) `/review-plan` SKILL is a thin verification pass. `inline-replanning.md` § Updating plan and track files updates cases 2–6 for the new section names (case 1 was already rewired in Track 2 step 6 + Phase C iter-1 WI3); cases 3 and 6 also receive a small fix for two line-broken "step file" residuals that escaped Track 2 step 2's terminology rename. `track-skip.md` updates the remaining section-name references (`## Description` mentions on lines 60, 67, 89). The template body lives in `conventions-execution.md` §2.1 (Track 2's responsibility); writer SKILLs point at it rather than duplicating.

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

**In-scope files**: `.claude/skills/create-plan/SKILL.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/workflow/inline-replanning.md`, `.claude/workflow/track-skip.md`.

**Out-of-scope**: reader workflow docs and section-name references (Track 4), sub-agent prompts (Track 4), `workflow.md` startup (Track 4). The episode-writer rewire (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) is handled by Track 2 step 6's atomic shape switch (D13), not by this track.

**Template consistency**: the track-file template in `create-plan/SKILL.md` Step 4 and the per-track shape `inline-replanning.md` case 1 produces must be byte-identical to the template in `conventions-execution.md` §2.1 (Track 2's responsibility). The two writers point at that canonical template; they do not duplicate the body.

**Markdown-only changes**: no Java, no Maven, no tests.

**Inter-track dependencies**:
- **Depends on Track 1** (workflow-review triage) for correct Phase C dispatch on this track's diff, and **Track 2** (spec + atomic shape switch per D13 — the episode-writer rewire that originally lived here as step 5 already landed in Track 2 step 6, and `inline-replanning.md` case 1 already landed in Track 2 Phase C iter-1 review-fix WI3). By the time Track 3 starts, the episode-writer in `step-implementation.md` sub-step 7 already follows the multi-section convention, this branch's own track files are already on the new shape, and `inline-replanning.md` case 1 already authors the 14-section shape; this track only updates the writer SKILLs that PRODUCE per-track files (vs. the orchestrator logic that WRITES into them, which is Track 2's territory now).
- Does not share `step-implementation.md` with Track 4 — Track 2 step 6 handled the writer half; Track 4 owns the reader half. Track 3 does not touch `step-implementation.md` at all.
- `inline-replanning.md` is a writer (it authors per-track files during replans), so cases 2–6 stay in Track 3 alongside the other writer SKILLs. Track 4's reader-pass does not include `inline-replanning.md` per Track 4's In-scope list (track file `## Interfaces and Dependencies`).

## Base commit
<!-- Phase B writes this once at session start. Empty at Phase 1. -->
