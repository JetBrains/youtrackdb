---
name: execute-tracks
description: "Execute the implementation plan: autonomous plan review (Phase 2) on first invocation, then track-by-track execution (Phase A review + decomposition, Phase B implementation, Phase C code review). Use after /create-plan to implement the planned work."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: orchestrator.
Your phase: determined by the auto-resume State in `workflow.md` § Startup Protocol.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

Read and follow the workflow for Phase 3 (Execution).

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:orchestrator:2,3A,3B,3C,4 `§1.5` for the workflow-level anchor and tier mapping.

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

Run the startup protocol in workflow.md:orchestrator:2,3A,3B,3C,4
`§ Startup Protocol (Auto-Resume)`. That section is the single
detection-and-routing home: it runs
`.claude/scripts/workflow-startup-precheck.sh --mode full` once at
turn 1 and routes on its JSON over branch divergence, workflow-SHA
drift (and the one autonomous normalization commit), pending
handoffs, and the resume state (the `state.phase` / `state.substate`
routing for State 0 / A / C / D / Done). Follow that dispatch
verbatim — do not re-derive the gates or the state routing here.

The phase-doc-loading map above and the self-improvement-reflection
step below are this SKILL's own additions on top of that protocol.
Once the protocol resolves a resume state, inform the user of the
decision (override allowed; by default proceed without confirmation),
then load the phase-specific workflow document from the map above and
execute that one phase only.

Each session handles exactly ONE PHASE of one track (or Phase 4 / State 0):
- State 0 (autonomous plan review) → end session after the audit summary lands in `plan-review.md` and the phase ledger records the `phase=A` boundary (D3/D7 — there is no plan `## Plan Review` checkbox under the thinned plan)
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
(the step-level baseline `review-bugs-concurrency`, subject to the
baseline-skip override, plus conditional agents and the step-level
workflow reviewers `hook-safety`/`prompt-design`; full rules in
`review-agent-selection.md` §Step-level vs track-level routing) fires
only on steps tagged
`risk: high` —
`medium` and `low` steps proceed directly from commit to episode,
relying on tests plus the always-on track-level review. Phase C runs
track-level dimensional review (same selection rules) against the
cumulative track diff and treats `medium` and `high` step ranges as
focal points.
