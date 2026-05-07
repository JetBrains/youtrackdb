---
severity: medium
phase: state-0
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# Legacy plan missing `## Plan Review` marker forces user gate at every resume

## Symptom

When `/execute-tracks` started this session against a plan with 18
already-completed tracks, the startup-protocol read of
`docs/adr/unit-test-coverage/_workflow/implementation-plan.md`
found no `## Plan Review` section at all. The literal rule in
`workflow.md` § Startup Protocol's resume table — "`## Plan Review`
checklist entry is `[ ]` (or section missing entirely)" → State 0 —
would route to `implementation-review.md` and audit a plan whose
structure is already proven by 18 successful Phase C completions.
The agent had to pause and ask the user how to proceed (State 0
audit vs. State A strategy refresh) because the table has no row
for "section missing AND tracks already `[x]`".

## Reproduction context

- Phase: state-0 / startup
- Workflow doc(s) involved: `.claude/workflow/workflow.md` § Startup
  Protocol (resume table); `.claude/workflow/conventions.md` §1.2
  (the "Plan Review" subsection that defines the marker).
- Tool / sub-agent involved: orchestrator startup-protocol logic
  (no sub-agent).
- ADR directory at the time: `docs/adr/unit-test-coverage/`.
- Trigger condition: any `/execute-tracks` resume against a plan
  that was created before the State 0 marker was added to the
  workflow templates. Affects every legacy plan in the repo
  (search: plans whose `implementation-plan.md` has neither a
  `## Plan Review` heading nor any `Plan review` checklist entry).

## Why it's a problem

Every resume against a legacy plan stalls on a user-interaction
gate the workflow could resolve autonomously — either by treating
"section missing AND tracks already `[x]`" as an implicit `[x]`
(the plan is already validated by execution), or by surfacing a
clear "legacy plan, please add `## Plan Review` `[x]` and re-run"
hint instead of routing to the State 0 audit. The current rule's
literal interpretation (re-audit) is also wrong on the face: a
plan with 18 successful track completions doesn't need a fresh
plan-review pass; the cost is several minutes of churn per resume,
multiplied by every remaining session on this branch (Tracks 19–22
plus Phase 4 = 12+ sessions).

## Proposed fix

Edit `.claude/workflow/workflow.md` § Startup Protocol → step 3
resume table to add a row, and update `conventions.md` §1.2
correspondingly:

```
| `## Plan Review` section is missing AND at least one track is
  `[x]` (legacy plan, predates the State 0 marker) | — | **State A**:
  treat the plan as implicitly validated. Auto-add `## Plan Review`
  with `[x]` and a one-line note ("legacy plan; validated through N
  completed tracks") in the same Workflow update commit that writes
  the strategy refresh line, then proceed normally. |
```

Alternative (more conservative): explicit `[ ] State A: legacy plan
amnesty` row that prints a one-shot informational message but does
not pause for confirmation.

## Acceptance criteria

- `workflow.md` § Startup Protocol resume table has a row covering
  legacy plans (section missing AND ≥1 track `[x]`).
- `conventions.md` §1.2 is updated to mirror the legacy-plan rule
  in its description of the `## Plan Review` marker lifecycle.
- A fresh `/execute-tracks` resume against a plan with no
  `## Plan Review` section but with completed tracks proceeds to
  State A without asking the user; the auto-mark commit is part of
  the strategy-refresh Workflow update.
- Regression check: `grep -rn 'Plan Review' .claude/workflow/` only
  matches entries consistent with the new rule (no stale references
  to the old "section missing → always State 0" reading).
