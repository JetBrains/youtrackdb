---
name: review-plan
description: "Manually re-run the autonomous plan review (Phase 2 — consistency + structural). The same review runs automatically as the first phase of /execute-tracks; this command is for re-runs after inline replanning or whenever the plan needs explicit re-validation."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

Read and follow the workflow for Phase 2 (Implementation Review).

> **Manual override.** Phase 2 normally runs autonomously as the first
> phase of `/execute-tracks` when the startup protocol detects State 0
> (`## Plan Review` is `[ ]`). This skill is a manual entry point for
> re-running the same review — useful after inline replanning has
> produced a revised plan, or when you want to explicitly re-validate
> the plan against current code without going through `/execute-tracks`.

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats, glossary, plan
   file structure, scope indicators, review iteration protocol
2. `.claude/workflow/implementation-review.md` — Phase 2 orchestration:
   autonomous classifier flow (mechanical findings auto-fixed,
   design-decision findings escalated to user), audit trail, mutation
   discipline for `design.md` fixes

You are the Implementation Review Orchestrator. Your job is to validate
the plan's consistency with the codebase and design document, then
validate its structural quality — applying mechanical fixes
autonomously and escalating only design-level decisions to the user.
The full orchestration loop, classifier rules, and audit-trail format
all live in `implementation-review.md`; this skill exists only to
provide a manual entry point.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the
directory name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Plan file: `docs/adr/<dir-name>/_workflow/implementation-plan.md`
Backlog file: `docs/adr/<dir-name>/_workflow/implementation-backlog.md`
Design document: `docs/adr/<dir-name>/_workflow/design.md`

The backlog file holds pending-track
`**What/How/Constraints/Interactions**` detail and any track-level
Mermaid diagrams (see `conventions-execution.md` §2.1 for the
Description lifecycle). Phase 2 sub-agents read it alongside the plan
to verify pending-track descriptions; pass its absolute path as the
`backlog_path` argument on each sub-agent spawn.

---

## What this skill does

1. Confirm via `git status --porcelain` that the working tree is clean
   (the autonomous flow commits the resulting plan/backlog/design
   updates as a single workflow-update commit; a dirty tree confuses
   the audit-trail commit).
2. Load `.claude/workflow/implementation-review.md` and follow its
   §"Step 1: Consistency Review" → §"Step 2: Structural Review" → §
   "Completion" sections in order. The orchestration is identical to
   the autonomous State 0 path inside `/execute-tracks`.
3. Apply mechanical fixes via `Edit` (plan/backlog) or the
   `edit-design` skill (design.md — mutation discipline).
4. Batch-escalate any `design-decision` findings to the user once per
   step. Apply user-resolved fixes the same way.
5. After both reviews pass, overwrite the plan file's `## Plan Review`
   section with the audit summary (`[x]` + auto-fixed/escalated
   listings) per `implementation-review.md` § Audit trail.
6. Commit the plan/backlog/design updates with the message
   `Plan review autonomous fixes for <plan-name>` and push.
7. End the session.

The behavior is identical to the autonomous State 0 path — both share
the same orchestration in `implementation-review.md`. The only thing
this skill adds is the entry point.
