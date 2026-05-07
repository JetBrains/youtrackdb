---
name: execute-tracks
description: "Execute the implementation plan: autonomous plan review (Phase 2) on first invocation, then track-by-track execution (Phase A review + decomposition, Phase B implementation, Phase C code review). Use after /create-plan to implement the planned work."
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
- State 0 (autonomous plan review): `.claude/workflow/implementation-review.md`
- Phase A: `.claude/workflow/track-review.md`
- Phase B: `.claude/workflow/step-implementation.md`
- Phase C: `.claude/workflow/track-code-review.md`
- Phase 4 (State D): `.claude/workflow/prompts/create-final-design.md`
  (also load `design-document-rules.md` — Phase 4 writes `design-final.md`)

Do NOT load phase documents you won't use this session. Prompt files
(in `.claude/workflow/prompts/`) are read only when spawning the specific
sub-agent that needs them — `create-final-design.md` is the one exception
because Phase 4 is main-agent work rather than a sub-agent spawn.
`implementation-review.md` is loaded only when State 0 fires; non-
State-0 sessions never read it.

On-demand reference documents (load only when the situation arises):
- `strategy-refresh.md` — load when entering State A (strategy refresh)
- `inline-replanning.md` — load when ESCALATE triggers
- `review-iteration.md` — load when running any review loop (Phase A reviews or Phase C code review)
- `code-review-protocol.md` — load at the start of Phase B sub-step 4 or Phase C code review
- `plan-slim-rendering.md` — load when assembling any step-level or track-level review sub-agent prompt
- `episode-format-reference.md` — load when writing your first episode
- `design-document-rules.md` — load when entering State D (Phase 4); not needed for Phase A/B/C
- `risk-tagging.md` — load during Phase A decomposition (to assign per-step risk tags) and on the rare Phase B upgrade path (when implementation reveals a step is more invasive than tagged); **not** loaded by Phase B normal execution or Phase C — those phases read the per-step `**Risk:**` tag from the step file directly
- `self-improvement-reflection.md` — load at the **end** of every session (State 0, Phase A, Phase B, Phase C, Phase 4) before "End the session". Mandatory final step that captures workflow-process friction as durable issue files under `workflow-issues/` for future agents to fix. Each phase doc invokes it explicitly.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The implementation plan is at:
`docs/adr/<dir-name>/_workflow/implementation-plan.md`
(every workflow file lives under `_workflow/`; the directory is
removed in the Phase 4 cleanup commit before merge — see
`conventions.md` §1.2 and `workflow.md` § Final Artifacts).

Follow the startup protocol in `workflow.md`:

1. Read the plan file at
   `docs/adr/<dir-name>/_workflow/implementation-plan.md`.
2. Identify all tracks and their status (`[ ]` not started, `[x]` completed,
   `[~]` skipped).
3. Determine session state and auto-resume:
   - **State 0** (`## Plan Review` is `[ ]` or section missing): load
     `implementation-review.md` on-demand and run the autonomous plan
     review (consistency + structural). End session after the audit
     summary lands.
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

   State 0 is checked **before** State A/B/C — plan review must
   complete before any track-level work begins.
4. Inform the user of the auto-resume decision. The user can override, but
   by default proceed without waiting for confirmation.
5. Load the phase-specific workflow document and execute that phase only.
6. After the phase completes, end the session. Instruct the user to clear
   context and re-run `/execute-tracks` for the next phase.

Each session handles exactly ONE PHASE of one track (or Phase 4 / State 0):
- State 0 (autonomous plan review) → end session after `## Plan Review` is `[x]`
- Phase A → end session
- Phase B → end session (or mid-phase checkpoint if 5+ steps done)
- Phase C → end session
- Track completion → end session after user approval
- Phase 4 (State D) → commit `design-final.md` + `adr.md`, then end session

User interaction happens at specific points:
- Session start: auto-resume decision (confirm or override)
- State 0 design-decision findings: resolve each escalated finding (only design-decision; mechanical fixes apply autonomously)
- Strategy refresh: accept recommendation or override
- Track pre-flight (start of fresh Phase A): proceed, amend the plan/backlog (light edits only), or capture clarifications for inclusion in the step file's Description (written at Phase A sub-step 2c); deep amendments ESCALATE to inline replanning. Skipped on State C resume.
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
