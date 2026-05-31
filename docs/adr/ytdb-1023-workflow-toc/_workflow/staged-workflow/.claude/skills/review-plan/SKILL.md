---
name: review-plan
description: "Manually re-run the autonomous plan review (Phase 2 — consistency + structural). The same review runs automatically as the first phase of /execute-tracks; this command is for re-runs after inline replanning or whenever the plan needs explicit re-validation. TRIGGER when: user asks to re-validate the plan after inline replanning; user requests explicit plan review outside /execute-tracks. SKIP: /execute-tracks already running State 0 in this session (autonomous Phase 2 path will fire) — do not double-run."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (files with no `## ` headings carry none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections.

Your role: orchestrator or reviewer-plan (whichever invoked this skill).
Your phase: 2 (Plan Review).

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §What this skill does | orchestrator,reviewer-plan | 2 | Manual entry-point steps: clean-tree check, run both reviews, apply or escalate fixes, write the audit trail, commit. |

<!--Document index end-->

Read and follow the workflow for Phase 2 (Implementation Review).

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:orchestrator,reviewer-plan:2 `§1.5` for the workflow-level anchor and tier mapping.

> **Manual override.** Phase 2 normally runs autonomously as the first
> phase of `/execute-tracks` when the startup protocol detects State 0
> (`## Plan Review` is `[ ]`). This skill is a manual entry point for
> re-running the same review — useful after inline replanning has
> produced a revised plan, or when you want to explicitly re-validate
> the plan against current code without going through `/execute-tracks`.

Read these workflow documents in order before starting:
1. conventions.md:orchestrator,reviewer-plan:2 — shared formats, glossary, plan
   file structure, scope indicators, review iteration protocol
2. implementation-review.md:orchestrator,reviewer-plan:2 — Phase 2 orchestration:
   autonomous classifier flow (mechanical findings auto-fixed,
   design-decision findings escalated to user), audit trail, mutation
   discipline for `design.md` fixes

You are the Implementation Review Orchestrator. Your job is to validate
the plan's consistency with the codebase and design document, then
validate its structural quality — applying mechanical fixes
autonomously and escalating only design-level decisions to the user.
The full orchestration loop, classifier rules, and audit-trail format
all live in implementation-review.md:orchestrator,reviewer-plan:2; this skill exists only to
provide a manual entry point.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the
directory name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Plan file: `docs/adr/<dir-name>/_workflow/implementation-plan.md`
Track files directory: `docs/adr/<dir-name>/_workflow/plan/`
Design document: `docs/adr/<dir-name>/_workflow/design.md`

Each `plan/track-N.md` track file carries the pending track's
detail across four plan-at-start sections: `## Purpose / Big Picture`
(BLUF + intro paragraph), `## Context and Orientation` (codebase
state at the start of the track plus any track-level Mermaid
diagram), `## Plan of Work` (prose sequence of edits, ordering
constraints, invariants), and `## Interfaces and Dependencies`
(in-scope/out-of-scope files, inter-track dependencies, signatures).
See conventions-execution.md:orchestrator:3A,3B,3C `§2.1` *Section lifecycle* for the
per-section writer/reader matrix. Phase 2 sub-agents read every
pending track's track file alongside the plan to verify
pending-track descriptions; pass the absolute track-files directory
path as the `plan_dir` argument on each sub-agent spawn.

---

## What this skill does
<!-- roles=orchestrator,reviewer-plan phases=2 summary="Manual entry-point steps: clean-tree check, run both reviews, apply or escalate fixes, write the audit trail, commit." -->

1. Run the clean-tree precondition from
   implementation-review.md:orchestrator,reviewer-plan:2
   § How to run > Precondition — path-scoped to the workflow files
   the audit-trail commit will touch (plan, every track file under
   `plan/`, design, design-mechanics, design-mutations). Halt and ask
   the user to commit or stash if any of those are dirty. Other dirty
   paths in the working tree are safe to ignore.
2. Load implementation-review.md:orchestrator,reviewer-plan:2 and follow its
   §"Step 1: Consistency Review" → §"Step 2: Structural Review" → §
   "Completion" sections in order. The orchestration is identical to
   the autonomous State 0 path inside `/execute-tracks`.
3. Apply mechanical fixes via `Edit` (plan / track files) or the
   `edit-design` skill (`design.md` — mutation discipline).
4. Batch-escalate any `design-decision` findings to the user once per
   step. Apply user-resolved fixes the same way.
5. After both reviews pass, overwrite the plan file's `## Plan Review`
   section with the audit summary (`[x]` + auto-fixed/escalated
   listings) per implementation-review.md:orchestrator,reviewer-plan:2 § Audit trail.
6. Commit the plan / track-file / design updates with the message
   `Plan review autonomous fixes for <plan-name>` and push.
7. End the session.

The behavior is identical to the autonomous State 0 path — both share
the same orchestration in implementation-review.md:orchestrator,reviewer-plan:2. The only thing
this skill adds is the entry point.
