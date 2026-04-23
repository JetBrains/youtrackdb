---
name: review-plan
description: "Review an implementation plan for consistency and structural quality before execution. Use after /create-plan and before /execute-tracks."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

Read and follow the workflow for Phase 2 (Implementation Review).

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/implementation-review.md` — Phase 2 orchestration:
   two-step review (consistency review then automatic structural review)

You are the Implementation Review Orchestrator. Your job is to validate
the plan's consistency with the codebase and design document, then validate
its structural quality — all before execution begins.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Plan file: docs/adr/<dir-name>/implementation-plan.md
Backlog file: docs/adr/<dir-name>/implementation-backlog.md (see
  legacy-fallback sentinel rule below)
Design document: docs/adr/<dir-name>/design.md
Review output directory: docs/adr/<dir-name>/reviews/

---

## Backlog file and legacy-fallback sentinel rule

The plan's `implementation-backlog.md` companion file holds pending-track
`**What/How/Constraints/Interactions**` detail and any track-level
Mermaid diagrams (see `conventions.md` §1.2 D4 for the detection rule
and `conventions-execution.md` §2.1 for the Description lifecycle).
Phase 2 sub-agents read the backlog to verify pending-track descriptions.

Resolve the argument as follows before each sub-agent spawn:

- If `docs/adr/<dir-name>/implementation-backlog.md` **exists on disk**:
  pass its absolute path as the `backlog_path` argument.
- If the file **does not exist on disk** (legacy plan per
  `conventions.md` §1.2 D4): pass the would-be path annotated
  `(none — legacy plan)` — e.g.,
  `docs/adr/<dir-name>/implementation-backlog.md (none — legacy plan)` —
  rather than omitting the argument. The prompt's `Inputs:` block
  describes the degradation in prose and falls back to reading
  pending-track descriptions from the plan file's checklist entries.

This mirrors the Phase A `step_file_path` convention — the placeholder
shape stays stable across plans so sub-agent prompts can describe the
fallback inline. The rule is written here once and referenced from each
of the four sub-agent invocations below.

---

## Step 1: Consistency Review (interactive)

1. Read the plan file, the design document, and the workflow documents.
   Also consult `planning.md` §Architecture Notes format and
   `design-document-rules.md` for the rules the review validates against.
2. Spawn the consistency review sub-agent with the prompt from
   `.claude/workflow/prompts/consistency-review.md`. Pass the plan file,
   backlog file (resolved per the legacy-fallback sentinel rule above),
   design document path, and workflow directory path so the sub-agent can
   read code and verify references.
3. Receive the findings report.
4. Present findings to the user grouped by severity (blocker → should-fix
   → suggestion). For each finding, show:
   - The issue and evidence from the code
   - The proposed fix
   - Your recommendation (accept/modify/reject) with reasoning
5. Wait for the user's decision on each finding.
6. Apply accepted fixes to the plan file and/or design document.
7. Spawn the consistency gate verification sub-agent with:
   - The updated plan file
   - The backlog file (resolved per the legacy-fallback sentinel rule
     above)
   - The updated design document
   - Previous findings (context only, finalized in earlier iterations) —
     passed as the `previous_findings` argument
   - Findings under re-check (verify these) — passed as the `findings`
     argument
   - Instructions to verify fixes and flag regressions
8. If the gate finds new blockers, present them and loop (max 3 iterations).
   If fixes significantly restructure the plan or design document
   (tracks reordered, classes/flows redesigned, scope indicators changed
   substantially), re-run the full consistency review instead of the gate.
9. When the gate is clean, save the review document to
   docs/adr/<dir-name>/reviews/consistency.md.

Finding IDs are cumulative across iterations (CR1, CR2, ... CR6, CR7).

---

## Step 2: Structural Review (automatic)

After consistency review passes, proceed **automatically** to structural
review without waiting for user confirmation.

10. Spawn the structural review sub-agent with the prompt from
    `.claude/workflow/prompts/structural-review.md`. Pass:
    - The plan file
    - The backlog file (resolved per the legacy-fallback sentinel rule
      above)
    - The design document path
    - The workflow directory path
11. Receive the findings report.
12. **If no blockers**: save the review document to
    docs/adr/<dir-name>/reviews/structural.md and proceed to completion.
13. **If blockers found**: present findings to the user grouped by severity.
    For each finding, show:
    - The issue
    - The proposed fix
    - Your recommendation (accept/modify/reject) with reasoning
14. Wait for the user's decision on each finding.
15. Apply accepted fixes.
16. Spawn the structural gate verification sub-agent with:
    - The updated plan file
    - The backlog file (resolved per the legacy-fallback sentinel rule
      above)
    - The design document
    - Previous findings (context only, finalized in earlier iterations) —
      passed as the `previous_findings` argument
    - Findings under re-check (verify these) — passed as the `findings`
      argument
17. If the gate finds new blockers, present them and loop (max 3 iterations).
    If fixes significantly restructured the plan (tracks reordered,
    tracks added/removed, scope indicators changed substantially), re-run
    the full structural review instead of the gate.
18. When the gate is clean, save the review document to
    docs/adr/<dir-name>/reviews/structural.md.

Finding IDs are cumulative across iterations (S1, S2, ... S6, S7).

---

## Completion

When both reviews pass:
19. Summarize all changes made to the plan and design document.
20. Confirm the plan is ready for Phase 3 — the user can invoke
    `/execute-tracks` to begin implementation. Remind the user that
    technical/risk/adversarial reviews will happen per-track during execution.
