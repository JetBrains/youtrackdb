---
name: execute-tracks
description: "Execute the implementation plan: autonomous plan review (Phase 2) on first invocation, then track-by-track execution (Phase A review + decomposition, Phase B implementation, Phase C code review). Use after /create-plan to implement the planned work."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (files with no `## ` headings carry none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections.

Your role: orchestrator.
Your phase: determined by the auto-resume State in `workflow.md` § Startup Protocol.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

Read and follow the workflow for Phase 3 (Execution).

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:orchestrator:2,3A,3B,3C,4 `§1.5` for the workflow-level anchor and tier mapping.

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/conventions-execution.md` — execution-specific:
   episode formats, commit format, code review, complexity tiers,
   checklist decomposition rules, track file content
3. `.claude/workflow/workflow.md` — session lifecycle,
   startup protocol (auto-resume), Track Pre-Flight gate, cross-track
   impact monitoring, session boundary rules, failure handling, inline
   replanning (ESCALATE), track completion protocol

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
- `inline-replanning.md` — load when ESCALATE triggers
- `review-iteration.md` — load when running any review loop (Phase A reviews or Phase C code review)
- `code-review-protocol.md` — load at the start of Phase B sub-step 4 or Phase C code review
- `plan-slim-rendering.md` — load when assembling any step-level or track-level review sub-agent prompt
- `episode-format-reference.md` — load when writing your first episode
- `design-document-rules.md` — load when entering State D (Phase 4); not needed for Phase A/B/C
- `risk-tagging.md` — load during Phase A decomposition (to assign per-step risk tags) and on the rare Phase B upgrade path (when implementation reveals a step is more invasive than tagged); **not** loaded by Phase B normal execution or Phase C — those phases read the per-step inline `risk:` token from the `## Concrete Steps` roster line directly
- `self-improvement-reflection.md` — load at the **end** of every `/execute-tracks` session (the State 0, Phase A, Phase B, Phase C, and Phase 4 phases of this skill) before "End the session". Mandatory final step that captures workflow-process friction and creates approved proposals as YouTrack issues under `YTDB` with the `dev-workflow` tag (or skips with a notice when the YouTrack MCP server is unreachable). Each phase doc invokes it explicitly. (The same doc serves `/migrate-workflow` under its own contract.)

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The implementation plan is at:
`docs/adr/<dir-name>/_workflow/implementation-plan.md`
(every workflow file lives under `_workflow/`; the directory is
removed in the Phase 4 cleanup commit before merge — see
`conventions.md` `§1.2` and `workflow.md` § Final Artifacts).

Follow the startup protocol in `workflow.md`:

1. Read the plan file at
   `docs/adr/<dir-name>/_workflow/implementation-plan.md`.
2. Identify all tracks and their status (`[ ]` not started, `[x]` completed,
   `[~]` skipped).
3. **Run the Branch Divergence Check** per
   `.claude/workflow/branch-divergence-check.md`. This must complete
   before any commit / push work in this session — including a
   handoff resolution commit in step 4.
4. **Check for active handoffs.** Run
   `ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null`. If any
   files exist, load `.claude/workflow/mid-phase-handoff.md` and follow
   its §Resume protocol before any state evaluation below. Do NOT spawn
   sub-agents, recompile episodes, or re-run gate-checks while a handoff
   is unresolved.
5. Determine session state and auto-resume:
   - **State 0** (`## Plan Review` is `[ ]` or section missing): load
     `implementation-review.md` on-demand and run the autonomous plan
     review (consistency + structural). End session after the audit
     summary lands.
   - **State A** (pre-Phase-A — next track is `[ ]`, no track file
     exists): run the Track Pre-Flight gate per `track-review.md`
     § Track Pre-Flight (Panel 1 strategy assessment when an earlier
     track has just completed/skipped, plus Panel 2 upcoming-track
     summary), then proceed to Phase A of the next track in the same
     session.
   - **State C** (mid-track resume): read track file Progress section,
     resume the next incomplete phase:
     - `Review + decomposition` incomplete → resume Phase A
     - Steps incomplete → run Phase B (check for orphan commits from
       interrupted steps — see step-implementation.md:orchestrator:3B `§Phase B Resume`)
     - Steps done, code review incomplete → run Phase C
     - All phases done → compile track episode, present to user, write
       to plan file only after user approval
   - **State D** (all tracks complete, Phase 4 pending): follow
     `prompts/create-final-design.md` to produce `design-final.md` and
     `adr.md`. Mark the Phase 4 checklist entry `[>]` when starting and
     `[x]` when the commit lands. See workflow.md:orchestrator:3A,3B,3C `§Startup Protocol` for
     the `[ ]` / `[>]` resume-action table.

   State 0 is checked **before** State A/C/D — plan review must
   complete before any track-level work begins.
6. Inform the user of the auto-resume decision. The user can override, but
   by default proceed without waiting for confirmation.
7. Load the phase-specific workflow document and execute that phase only.
8. After the phase completes, end the session. Instruct the user to clear
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
- Track Pre-Flight (State A — pre-Phase-A): two-panel gate. Panel 1 (strategy assessment, look-back) is shown when an earlier track has just completed/skipped — accept the CONTINUE/ADJUST recommendation or override; an ESCALATE recommendation routes to inline replanning. Panel 2 (upcoming track summary, look-forward) always renders. Three one-step options per `.claude/workflow/review-mode.md` § Approval-panel contract: **Approve** (accept and start Phase A with whatever review-mode-accumulated amendments and clarifications have landed); **Review mode** (conversational refinement loop per `.claude/workflow/review-mode.md` § Flow — user drops observations across as many chat turns as they want; on a completion signal one approval panel surfaces the accumulated set; Apply executes `EDIT_PLAN` / `EDIT_STEP_DESC` / `SKIP_TRACK`, buffers `CLARIFY`, answers `QUESTION` inline; panels re-render); **ESCALATE** → inline replanning. Skipped on State C resume.
- Phase complete: user clears session, re-runs `/execute-tracks`
- Cross-track impact detected: continue, pause, or escalate
- Track complete: three one-step options per `.claude/workflow/review-mode.md` § Approval-panel contract — **Approve** (write track episode + collapse + `[x]`); **Review mode** (conversational refinement loop per `.claude/workflow/review-mode.md` § Flow; on Apply, `FIX_FINDING` items spawn a fresh implementer with `mode=FIX_REVIEW_FINDINGS`; `QUESTION` items are answered inline); **ESCALATE** → inline replanning
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
