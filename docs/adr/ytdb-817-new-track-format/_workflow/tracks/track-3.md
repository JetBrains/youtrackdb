# Track 3: Update writer SKILLs (`/create-plan`, `/review-plan`, inline-replanning, track-skip)

## Description
Update every writer SKILL that authors or amends a per-track file or the root index: `/create-plan` SKILL gets the new track-file template; `/review-plan` is a thin wrapper to verify; `inline-replanning.md` case 1 ("new track") writes the new template; `track-skip.md` picks up the new path. **The episode-writer rewire that originally lived here as step 5 (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) has moved into Track 2's atomic shape switch (D13)** so the writer logic, the on-disk shape, and this branch's own track files all change in one commit. Track 3 is now strictly the writer-SKILL update — the orchestrator-driven episode-write logic is already on the new shape by the time Track 3 starts.

> **What**:
> - Update `.claude/skills/create-plan/SKILL.md`: rewrite Step 4's embedded track-file template block (the `# Track N: <title>` skeleton) to the new 12-section shape plus the two workflow-specific siblings (`## Episodes`, `## Base commit`) exactly as in `design.md` §"New per-track file shape" — Concrete Steps as a thin numbered roster (description + `risk:` tag + `[ ]` checkbox), Episodes with the per-step block template (D11), Artifacts and Notes as cross-step-only (D11) — including HTML-comment placeholders for the three reserved slots; change Step 1b's `mkdir -p ... tracks` to `mkdir -p ... plan`; update every path reference in Step 4 (the trailing example block under "Each track file (`tracks/track-N.md`) is created..." — already swept to `plan/track-N.md` by Track 2's mechanical pass, but verify and refresh the prose around it). Note: Track 2's mechanical "step file" → "track file" sweep has already touched this file; Track 3's work is the semantic rewrite of the template block, not the prose-term replace.
> - Update `.claude/skills/review-plan/SKILL.md`: inspect for path references and section-name references; update to `plan/` + new section names. The skill mostly delegates to the consistency + structural review prompts (Track 4's scope); Track 3's work here is verifying the skill's own text after Track 2's mechanical sweep.
> - Update `.claude/workflow/inline-replanning.md` § Updating plan and track files (heading renamed by Track 2 from "§ Updating plan and step files"):
>   - Case 1 ("New track"): rewrite the track-file shape this case creates — must produce the same 12-section template `/create-plan` writes at Phase 1 (point to `conventions-execution.md` §2.1 as the canonical template; do not duplicate the template body here).
>   - Cases 2–6: update `tracks/track-N.md` path references to `plan/track-N.md`; update any `## Description` section-name references to the new section name(s) — case 2 (revising a not-yet-started track) typically rewrites Purpose / Context / Plan of Work / Interfaces, not a single `## Description`.
>   - Lifecycle-pointer paragraph: re-cite `conventions-execution.md` §2.1 with the updated section names.
> - Update `.claude/workflow/track-skip.md`:
>   - Step 3 (terminal track-file delete): update the `_workflow/tracks/` path to `_workflow/plan/`.
>   - Step 5 (strategy-refresh): update the strategy-refresh-line example if it references `tracks/`.
>   - "Track-file deletion is terminal" warning (heading renamed by Track 2 from "Step-file deletion is terminal"): update any path references.
>
> **How**:
> - Step 1 (`create-plan/SKILL.md`): rewrite the Step 4 track-file template block as a single largest edit; update the `mkdir` line in Step 1b; sweep remaining path references in the file. Preview the resulting Step 4 block in the user-facing summary before committing — this is the most-cited template in the workflow.
> - Step 2 (`review-plan/SKILL.md`): inspect, update; usually a small commit.
> - Step 3 (`inline-replanning.md`): rewrite § Updating plan and track files in place; cases 1–6 sequentially.
> - Step 4 (`track-skip.md`): update step 3 + step 5 + any other path mentions.
>
> **Constraints**:
> - **In-scope files**: `.claude/skills/create-plan/SKILL.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/workflow/inline-replanning.md`, `.claude/workflow/track-skip.md`.
> - **Out-of-scope**: reader workflow docs and section-name references (Track 4), sub-agent prompts (Track 4), `workflow.md` startup (Track 4). The episode-writer rewire (`step-implementation.md` sub-step 7, `episode-format-reference.md`, and the D12 canonical write order across every Progress writer) is handled by Track 2 step 6's atomic shape switch (D13), not by this track.
> - **Template consistency**: the track-file template in `create-plan/SKILL.md` Step 4 and the per-track shape `inline-replanning.md` case 1 produces must be byte-identical to the template in `conventions-execution.md` §2.1 (Track 2's responsibility). The two writers point at that canonical template; they do not duplicate the body.
> - **Markdown-only changes**: no Java, no Maven, no tests.
>
> **Interactions**:
> - **Depends on Track 1** (workflow-review triage) for correct Phase C dispatch on this track's diff, and **Track 2** (spec + atomic shape switch per D13 — the episode-writer rewire that originally lived here as step 5 already landed in Track 2 step 6). By the time Track 3 starts, the episode-writer in `step-implementation.md` sub-step 7 already follows the multi-section convention, and this branch's own track files are already on the new shape; this track only updates the writer SKILLs that PRODUCE per-track files (vs. the orchestrator logic that WRITES into them, which is Track 2's territory now).
> - Does not share `step-implementation.md` with Track 4 — Track 2 step 6 handled the writer half; Track 4 owns the reader half. Track 3 does not touch `step-implementation.md` at all.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
