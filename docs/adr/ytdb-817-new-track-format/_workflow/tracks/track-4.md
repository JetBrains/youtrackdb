# Track 4: Update readers (workflow docs, sub-agent prompts, remaining references)

## Description
Update every code path that reads a per-track file by section heading: Phase A (`track-review.md`), Phase B readers (`step-implementation.md`, `implementer-rules.md`, `step-implementation-recovery.md`), Phase C (`track-code-review.md`), all sub-agent prompts, plus `workflow.md` startup and the remaining workflow docs. Readers that today grep a step's inline blockquote (for risk tag, for episode fields like What-was-done / Key files) now perform a **section-join**: read `## Concrete Steps` for the plan fields (risk tag, description, status); read `## Artifacts and Notes ### Step N` for the episode fields. Join key is step number; commit SHA is the secondary key. Ends with a manual `/create-plan` smoke test against a synthetic task and a final grep verification across the entire `.claude/` tree.

> **What**:
> - Update Phase A reader (`.claude/workflow/track-review.md`): every `## Description` reference splits across the new sections (`## Purpose / Big Picture` for intro, `## Context and Orientation` + `## Plan of Work` for the W/H content, `## Interfaces and Dependencies` for C/I); `## Reviews completed` reads become `## Outcomes & Retrospective` reads; `## Steps` becomes `## Concrete Steps`; the Pre-write rule's "track file's `## Description`" reference (already swept to "track file's" by Track 2's terminology pass) updates to the new sections list. The Track Pre-Flight Panel 1 strategy assessment (which today reads each prior track's step blockquotes for cross-cutting discoveries) now reads `## Surprises & Discoveries` and `## Decision Log` directly, with per-step detail available in `## Artifacts and Notes ### Step N` blocks via the section-join pattern.
> - Update Phase B readers:
>   - `.claude/workflow/step-implementation.md` (reader half only — sub-steps 1–6 and 8+; Track 2 step 6 owns sub-step 7's writer half per D13): section-name references in Phase B Startup (`## Base commit` stays the same name), Per-Step Orchestration Loop, and the resume-detection logic. **Sub-step 4 (dimensional-review gate) reads risk tag from `## Concrete Steps` roster line** (not from inside an episode blockquote — risk tag never lived in episode anyway, but the roster is the canonical home now). **Resume detection** for "next step" reads `## Concrete Steps` for the next `[ ]` checkbox; "what was done in prior steps of this track" reads `## Artifacts and Notes` for the blocks that already exist.
>   - `.claude/workflow/implementer-rules.md`: track-file section references — the implementer NO LONGER writes the episode itself directly; it returns the `EPISODE_DRAFT` to the orchestrator at sub-step 3 (handoff), and the orchestrator's sub-step 7 places it in the four sections per D9. Update the implementer-rules to make this explicit: the implementer drafts the fields in the handoff, never edits the track file itself for episode content.
>   - `.claude/workflow/step-implementation-recovery.md`: Phase B Resume references — `## Base commit` parsing unchanged; "find next pending step" reads `## Concrete Steps` for the next `[ ]`; "what happened in prior steps" reads `## Artifacts and Notes` (orphan-commit recovery cross-references the most recent Progress entry against the most recent Artifacts block to detect a commit-without-episode-write).
> - Update Phase C reader (`.claude/workflow/track-code-review.md`): `## Base commit` reads (unchanged name); `## Steps` → `## Concrete Steps` for the step roster, `## Artifacts and Notes` for episode content; the track-completion collapse rules (Always keep / Always drop list) — the collapse now aggregates Artifacts entries into the Track episode rather than aggregating step blockquotes; the Implementer-Spawn passed-context section names; the Track Completion compile-episode source references (compile from Artifacts entries, Surprises, Decision Log, and Outcomes — not from step blockquotes).
> - Update sub-agent prompts (`.claude/workflow/prompts/*.md`):
>   - `consistency-review.md`: the per-track-description code-reference sources (`## Description` W/H/C/I → `## Purpose / Big Picture` + `## Context and Orientation` + `## Plan of Work` + `## Interfaces and Dependencies`); the Inputs: block (`tracks_dir` path).
>   - `structural-review.md`: add a check that every track file has all 12 ExecPlan sections in OpenAI's order; add the reserved-slot exemption (a heading followed only by an HTML-comment placeholder is not a defect); update TRACK DESCRIPTIONS criterion to read the new sections.
>   - `design-review.md`: update path references.
>   - `technical-review.md`, `risk-review.md`, `adversarial-review.md`: prompt-template section-name references (`## Steps` → `## Concrete Steps`); path references.
>   - `create-final-design.md` (Phase 4): aggregates content from `plan/track-N.md`; aggregation now pulls from multiple sections (Purpose / Concrete Steps / Outcomes) instead of one `## Description`.
>   - Gate verifications (`consistency-gate-verification.md`, `structural-gate-verification.md`, `review-gate-verification.md`, `dimensional-review-gate-check.md`): path + section-name references.
> - Update `.claude/workflow/workflow.md`: §Startup Protocol path references (`tracks/track-N.md` → `plan/track-N.md`); State C sub-state table's `## Progress` parsing (no logic change, only the heading name and the new continuous-log entry shape — most-recent timestamped entry, not the 3-line fixed list).
> - Update remaining workflow docs:
>   - `episode-format-reference.md`: step episode is written under `## Concrete Steps`; failed-step episode same.
>   - `risk-tagging.md`: risk tag lives on the `## Concrete Steps` roster line (description + `risk: <level>`), NOT inside an episode blockquote. Update §Lifecycle table to reflect this: Phase A writes the tag onto the roster line at decomposition; Phase B may upgrade in place (but never downgrade) by editing the roster line. The tag is locked after the step is committed (the Artifacts block is written; the roster checkbox flips to `[x]`).
>   - `mid-phase-handoff.md`: path references; resume-protocol section-name references.
>   - `plan-slim-rendering.md`: renders the new shape (intro paragraph + Scope + Depends-on; after collapse, intro + Track episode + Track file pointer).
>   - `design-document-rules.md`: confirm Track 2's boundary-table update is intact and no other references missed.
>   - `ephemeral-identifier-rule.md`: verify the path-exclude glob doesn't need updating (`':(exclude)docs/adr/*/_workflow/**'` covers both old and new shape).
>   - `review-mode.md`: `EDIT_STEP_DESC` action type — confirm the action now writes to whichever new sections cover the old `## Description` content; update §Action types prose.
>   - `track-review.md` is in Phase A scope above; confirm no double-edit.
> - Manual smoke test: invoke `/create-plan` on a synthetic single-track task (e.g., "rename method `foo()` to `bar()` in a one-file fictional module"); verify the per-track file lands at `_workflow/plan/track-1.md`, all 12 ExecPlan sections plus `## Base commit` are present in the correct order, reserved-slot placeholders render correctly; spawn `/execute-tracks` State 0 (autonomous plan review) and verify it processes the new shape without crashing.
> - Final grep verification — all three sweeps must return zero matches (or only documented historical references in fenced Markdown that intentionally quote the legacy vocabulary):
>   - `grep -rn '_workflow/tracks/' .claude/workflow/ .claude/skills/` (directory rename)
>   - `grep -rni 'step.file' .claude/workflow/ .claude/skills/` (terminology rename — covers `step file`, `step-file`, `Step File`, etc.)
>   - `grep -rn '## Description' .claude/workflow/ .claude/skills/` (section-name sweep)
>
>   A non-zero match in any sweep is a blocker — implies a missed reference somewhere across the four tracks.
>
> **How**:
> - Step 1 (Phase A reader — `track-review.md`): largest single file in this track. Rewrite section references methodically; multiple subsections affected (Track Pre-Flight, Pre-write rule, Phase A Resume, the table at line ~716). Commit.
> - Step 2 (Phase B readers): batch update `step-implementation.md` (reader half), `implementer-rules.md`, `step-implementation-recovery.md` via `steroid_apply_patch` where text is uniform. Commit.
> - Step 3 (Phase C reader — `track-code-review.md`): update section references, track-completion collapse rules, Implementer-Spawn context, Track Completion compile-episode references. Commit.
> - Step 4 (sub-agent prompts): update all `.claude/workflow/prompts/*.md`. Several prompts have a large "Inputs:" block naming the track-file directory — confirm path change. Largest content edits are in `consistency-review.md` (per-track-description code-ref sources) and `structural-review.md` (12-section completeness check + reserved-slot exemption). Commit.
> - Step 5 (`workflow.md` + remaining docs + smoke test + grep verification): batch update remaining docs via `steroid_apply_patch` where text is uniform; run `/create-plan` synthetic smoke test; run final grep sweep `grep -rn '_workflow/tracks/' .claude/` and confirm zero matches. Commit.
>
> **Constraints**:
> - **In-scope files**: every workflow doc and prompt not already updated by Tracks 1, 2, or 3. Specifically: `track-review.md`, `step-implementation.md` (reader half), `implementer-rules.md`, `step-implementation-recovery.md`, `track-code-review.md`, every `prompts/*.md`, `workflow.md`, `episode-format-reference.md`, `risk-tagging.md`, `mid-phase-handoff.md`, `plan-slim-rendering.md`, `review-mode.md`, `ephemeral-identifier-rule.md` (verification only), `design-document-rules.md` (verification only).
> - **Out-of-scope**: workflow-review triage update (Track 1), spec files (Track 2), writer SKILLs (Track 3), `step-implementation.md` sub-step 7 writer half (Track 2 step 6 per D13).
> - **Coordinated with Track 2 step 6 on `step-implementation.md`**: Track 2 step 6 (atomic shape switch per D13) lands sub-step 7's writer-half edit and the on-disk shape migration; Track 4 picks up Track 2's HEAD as base. Verify Track 2 step 6's writer-half edit is intact after Track 4's reader-half edits land.
> - **No section-content semantics changes**: only section names change; the logic each reader implements stays identical. This is a renaming sweep, not a behavioral rewrite. Resist scope-creep into rewriting the readers' semantics.
> - **Smoke test discipline**: the `/create-plan` synthetic run must NOT pollute `docs/adr/`. Use a sentinel directory name (e.g., `_smoke-test-ytdb-817`) and delete it after the smoke test passes. The smoke-test invocation is part of Track 4 step 5; the deletion is the final commit in Track 4 (or a fold into step 5's commit if no other content remains).
> - **Markdown-only changes**: no Java, no Maven, no tests.
>
> **Interactions**:
> - **Depends on Track 1** (workflow-review triage) for correct Phase C dispatch on this track's diff, **Track 2** (spec + atomic shape switch per D13 — by Track 4 start, this branch's track files are already on the new shape and the episode writer already follows the multi-section convention), and **Track 3** (writer-SKILL changes).
> - Shares `step-implementation.md` with Track 2 step 6 (writer half) — Track 4 owns the reader half; see Constraints.
> - The final grep verification gates Phase C of this track. A non-zero match count is a blocker — implies a missed reference somewhere across the four tracks.
> - The smoke test exercises the entire writer + reader chain — it's the closest thing this plan has to an integration test. If it fails, the failure is almost certainly a missed section-name reference; rerun the grep verification narrowed to the surfaced section name.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
