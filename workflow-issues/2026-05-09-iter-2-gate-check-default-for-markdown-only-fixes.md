---
severity: low
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Iter-2 gate-check has no skip rule for markdown-only Phase A fixes

## Symptom

`.claude/workflow/review-iteration.md` § "Iteration flow" prescribes:

```
Iteration 1: Full review -> findings -> decisions -> apply fixes
Iteration 2: Gate check -> verify fixes + catch regressions -> if blockers, loop
Iteration 3: Gate check -> if still blockers, escalate
```

The protocol treats iter-2 as mandatory whenever iter-1 produces
findings that get applied. For Phase A reviews where every accepted
finding is a markdown edit to the step file's `## Description`
section (no production code, no test code, no step decomposition
change) — i.e., factual corrections to a Constraints subsection,
reframing legend additions, How-block tightening — the iter-2 gate
check spawns three additional sub-agents to re-read the corrected
step file. Each of those agents costs ~20–40K tokens of context and
returns "findings VERIFIED" with no behavioral change observable.

Track 22a Phase A iter-1 surfaced 5 blockers + ~10 should-fix items;
all blocker fixes were Description-section edits. Running iter-2
would have spawned three more sub-agents at a session that was
already at context-warning level (≥30%). The orchestrator chose to
skip iter-2 and document the deferral in the step file's `## Reviews
completed` section ("Iter-2 gate-check deferred — fixes are
markdown-only edits to the Description; verification rides on Phase
B/C reading the corrected step file"), but this was a one-off
decision with no rule backing it.

## Reproduction context

- Phase: phase-a
- Workflow doc(s) involved: `.claude/workflow/review-iteration.md` § "Iteration flow"; `.claude/workflow/track-review.md` § "Review iteration protocol"
- Tool / sub-agent involved: review-gate-verification.md sub-agent prompts
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Phase A run where every accepted iter-1 finding is a markdown edit to the step file's `## Description` section AND the orchestrator is at context-warning level (≥30%)

## Why it's a problem

The protocol does not distinguish between fix categories that
benefit from gate verification (code edits where regressions are
real) and fix categories that don't (markdown edits where
"regression" reduces to a textual diff the orchestrator already
saw). Defaulting to "always loop" forces the orchestrator into one
of two suboptimal outcomes:

1. Spawn three iter-2 sub-agents anyway, costing ~60–120K tokens,
   often pushing the session over the context-warning threshold and
   forcing an early exit before commit + reflection.
2. Skip iter-2 ad-hoc with a Reviews-completed note (current Track
   22a behavior), which works but leaves the audit trail
   inconsistent across sessions.

Future Phase A runs will hit the same trade-off. A documented rule
prevents drift.

## Proposed fix

Add a new subsection to `.claude/workflow/review-iteration.md`
after § "Iteration flow":

> ### When iter-2 may be skipped
>
> Phase A iter-2 (and only Phase A) may be skipped when **all**
> accepted iter-1 findings are markdown-only edits to the step
> file's `## Description` section — no step decomposition changes,
> no Decision Record edits, no plan-file edits, no production or
> test code touched. The skip is recorded in `## Reviews completed`
> with the form: `iter-1 findings applied; iter-2 gate-check
> skipped — all fixes are Description-only markdown edits`.
>
> The skip is **not allowed** when:
> - any iter-1 finding modified the `## Steps` section (risk tags,
>   step boundaries, or step content)
> - any iter-1 finding modified the plan file or backlog
> - any iter-1 finding had a `STILL OPEN` precedent in a prior
>   review iteration
>
> Phase B and Phase C iter-2 gate checks remain mandatory — code
> edits get gate-checked because regressions there are not
> trivially observable from the diff.

The rule is intentionally conservative: only the Description block,
only Phase A. This covers the dominant low-ROI case (factual
constraint corrections, reframing legends) without weakening
verification on actual code or step-decomposition changes.

## Acceptance criteria

- `.claude/workflow/review-iteration.md` adds a § "When iter-2 may
  be skipped" subsection with the rule above.
- The Reviews-completed format string matches the rule's
  prescribed text.
- A Phase A run where the rule applies records the skip with the
  prescribed text and does not spawn iter-2 sub-agents.
- Regression check: `grep -n "iter-2 gate-check skipped" docs/adr/*/_workflow/tracks/*.md` returns matches only on Reviews-completed lines for Phase A markdown-only sessions.
