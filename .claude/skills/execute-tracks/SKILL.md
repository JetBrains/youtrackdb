---
name: execute-tracks
description: "Execute implementation plan tracks phase by phase (review, implement, code review). Use after /create-plan and /review-plan to implement the planned work."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

Read and follow the workflow for Phase 3 (Execution).

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/conventions-execution.md` — execution-specific:
   episode formats, commit format, code review, complexity tiers,
   checklist decomposition rules, step file content
3. `.claude/workflow/workflow.md` — session lifecycle,
   startup protocol (auto-resume), strategy refresh, cross-track impact
   monitoring, session boundary rules, failure handling, inline replanning
   (ESCALATE), track completion protocol

After determining which phase to execute, load the phase-specific document:
- Phase A: `.claude/workflow/track-review.md`
- Phase B: `.claude/workflow/step-implementation.md`
- Phase C: `.claude/workflow/track-code-review.md`
- Phase 4 (State D): `.claude/workflow/prompts/create-final-design.md`
  (also load `design-document-rules.md` — Phase 4 writes `design-final.md`)

Do NOT load phase documents you won't use this session. Prompt files
(in `.claude/workflow/prompts/`) are read only when spawning the specific
sub-agent that needs them — `create-final-design.md` is the one exception
because Phase 4 is main-agent work rather than a sub-agent spawn.

On-demand reference documents (load only when the situation arises):
- `strategy-refresh.md` — load when entering State A (strategy refresh)
- `inline-replanning.md` — load when ESCALATE triggers
- `review-iteration.md` — load when running any review loop (Phase A reviews or Phase C code review)
- `code-review-protocol.md` — load at the start of Phase B sub-step 4 or Phase C code review
- `plan-slim-rendering.md` — load when assembling any step-level or track-level review sub-agent prompt
- `episode-format-reference.md` — load when writing your first episode
- `design-document-rules.md` — load when entering State D (Phase 4); not needed for Phase A/B/C
- `risk-tagging.md` — load during Phase A decomposition (to assign per-step risk tags) and on the rare Phase B upgrade path (when implementation reveals a step is more invasive than tagged); **not** loaded by Phase B normal execution or Phase C — those phases read the per-step `**Risk:**` tag from the step file directly

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The implementation plan is at: docs/adr/<dir-name>/implementation-plan.md

Follow the startup protocol in `workflow.md`:

1. Read the plan file at `docs/adr/<dir-name>/implementation-plan.md`.
2. Identify all tracks and their status (`[ ]` not started, `[x]` completed,
   `[~]` skipped).
3. Determine session state and auto-resume:
   - **State A** (track just completed, needs strategy refresh): perform
     strategy refresh, then proceed to Phase A of the next track.
   - **State B** (fresh start): identify first uncompleted track, begin
     Phase A (review + decomposition).
   - **State C** (mid-track resume): read step file Progress section,
     resume the next incomplete phase:
     - `Review + decomposition` incomplete → resume Phase A
     - Steps incomplete → run Phase B (check for orphan commits from
       interrupted steps — see step-implementation.md §Phase B Resume)
     - Steps done, code review incomplete → run Phase C
     - All phases done → compile track episode, present to user, write
       to plan file only after user approval
   - **State D** (all tracks complete, Phase 4 pending): follow
     `prompts/create-final-design.md` to produce `design-final.md` and
     `adr.md`. Mark the Phase 4 checklist entry `[>]` when starting and
     `[x]` when the commit lands. See workflow.md §Startup Protocol for
     the `[ ]` / `[>]` resume-action table.
4. Inform the user of the auto-resume decision. The user can override, but
   by default proceed without waiting for confirmation.
5. Load the phase-specific workflow document and execute that phase only.
6. After the phase completes, end the session. Instruct the user to clear
   context and re-run `/execute-tracks` for the next phase.

Each session handles exactly ONE PHASE of one track (or Phase 4):
- Phase A → end session
- Phase B → end session (or mid-phase checkpoint if 5+ steps done)
- Phase C → end session
- Track completion → end session after user approval
- Phase 4 (State D) → commit `design-final.md` + `adr.md`, then end session

User interaction happens at specific points:
- Session start: auto-resume decision (confirm or override)
- Strategy refresh: accept recommendation or override
- Phase complete: user clears session, re-runs `/execute-tracks`
- Cross-track impact detected: continue, pause, or escalate
- Track complete: approve, request fixes, or request rework
- Step failure (2nd attempt): retry, adjust, or escalate

Everything within a phase executes autonomously: Phase A runs reviews
(as sub-agents), decomposes steps, and assigns each step a risk tag
(`low` / `medium` / `high`) per `risk-tagging.md`. Phase B implements
steps and produces episodes; the step-level dimensional review loop
(4 baseline + up to 6 conditional sub-agents in parallel, selected per
`review-agent-selection.md`) fires only on steps tagged `risk: high` —
`medium` and `low` steps proceed directly from commit to episode,
relying on tests plus the always-on track-level review. Phase C runs
track-level dimensional review (same selection rules) against the
cumulative track diff and treats `medium` and `high` step ranges as
focal points.
