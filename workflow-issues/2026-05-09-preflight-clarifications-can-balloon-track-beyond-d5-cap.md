---
severity: medium
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Pre-Flight clarifications can balloon a track beyond D5's per-track step cap with no proactive warning

## Symptom

During the Track Pre-Flight gate for Track 22 (the final-sweep track),
the user picked **Clarify** twice across the loop, attaching two
substantive clarifications to the upcoming track:

1. WHEN-FIXED issue creation deferred to Track 22 itself with a
   matching `// WHEN-FIXED: YTDB-NNNN` marker rewrite in lockstep.
2. Hybrid (cluster-by-cluster) dead-code deletion policy, with PSI
   find-usages classification per cluster.

Both clarifications were captured into the buffer and (after **Proceed**)
flowed into the step file's `## Description` → `### Clarifications`
subsection. Together, they expanded Track 22's expected step count
from the plan-file's "~6 steps + ~3–4 cleanup steps" to a Phase A
realistic estimate of "~6 main coverage + ~8–10 deletion-lockstep + ~1–2
marker-rewrite ≈ 15–18 steps" — **2.5× the recent track-size precedent
(Tracks 6–21 ran 6–8 steps each)** and 2× the implicit per-track cap of
"~5–7 steps" from `conventions.md` §1.2.

The Pre-Flight gate did not surface this size impact at clarification
time. The size violation became visible only at iteration 1 of Phase A's
adversarial review (finding A3, classified `blocker`), which then forced
an ESCALATE → inline-replanning to split Track 22 into 22a/22b/22c — a
deep amendment the user could have made cheaply at Pre-Flight time
instead.

## Reproduction context

- Phase: phase-a (specifically the Track Pre-Flight gate that immediately
  precedes Phase A sub-step 1)
- Workflow doc(s) involved:
  - `.claude/workflow/track-review.md` § Track Pre-Flight (steps 3–5;
    the Clarify loop)
  - `.claude/workflow/conventions.md` §1.2 (per-track step cap)
  - `docs/adr/<dir-name>/_workflow/implementation-plan.md` Decision
    Record D5 ("one PR per track", "5–7 commits per track")
- Tool / sub-agent involved: `AskUserQuestion` (the Clarify loop)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Any Pre-Flight gate where the user picks **Clarify**
  with a clarification that adds new step categories (e.g., issue
  creation, marker rewrite, in-track deletion lockstep, production-fix
  dispositions). The friction worsens monotonically as more
  clarifications stack.

## Why it's a problem

A clarification is a low-cost, low-friction action at Pre-Flight (the
user simply picks a menu option and types guidance). The structural
consequences are invisible at that moment: nothing in the gate's
output or the captured buffer enumerates "this clarification implies N
new steps". So clarifications can compound silently.

When the size cap is breached, the cost of catching it later is high:
- Three Phase A review sub-agents must run (~25 minutes wall-clock,
  substantial sub-agent token spend) before the BLOCKER surfaces.
- The orchestrator must consume context budget reading the findings
  and consolidating them.
- ESCALATE → inline-replanning routes back to the user, who must now
  unwind the clarifications via a structural decision (split into 2-3
  sub-tracks). That decision could have been made cheaply during the
  Pre-Flight Clarify loop itself.
- Any plan / backlog / step-file edits already committed at Phase A
  sub-steps 1–2 must be undone or restructured.

This session's Track 22 burned three commits on the Pre-Flight +
description-move + iter-1 review summary path before the BLOCKER
forced ESCALATE. Most of that work would have been avoided by an
earlier size-impact check.

## Proposed fix

Add a **size-impact heuristic** to the Track Pre-Flight gate that runs
at the end of every Clarify round (before re-asking) and at the end of
every Adjust round:

1. Maintain a running step-count estimate for the upcoming track,
   seeded from the plan-file `**Scope:**` indicator.
2. After each clarification or adjustment, the gate evaluates whether
   the new content adds step categories. Heuristic categories that
   typically add steps:
   - "Track must include a step that creates / writes / generates …"
     (each is typically one new step or sub-step).
   - "Cluster-by-cluster classification" or "per-cluster decomposition"
     (typically maps to the cluster count — Track 22's hybrid deletion
     policy alone implied ~8–10 cluster steps).
   - "Apply / fix / migrate every X" where the X-set is large and
     cannot be folded into a single step.
3. When the running estimate crosses ~5–7 (the per-track cap from
   `conventions.md` §1.2 / D5), the gate emits an inline warning in
   the next round's user-facing summary:

   ```
   ⚠ Pre-Flight size-impact heuristic: the captured clarifications
     imply ~N steps, exceeding the per-track cap of ~5–7. Consider
     splitting the track during Pre-Flight (Adjust → reorder + add
     placeholder tracks) rather than deferring to inline-replanning
     after Phase A reviews surface the size as a BLOCKER.
   ```

4. The user can choose to ignore the warning (continue with **Proceed**
   and accept that Phase A reviews may BLOCKER) or **Adjust** to split
   the track. Either way, the size impact is surfaced at the cheap
   decision point, not after three reviewers run.

The heuristic does not need to be perfect — even a rough estimate
("clarifications added ~4 step categories on top of the existing ~3
scoped steps; total ~7+ steps; near or over cap") gives the user a
prompt to consider splitting before sub-agents fan out.

Alternative (smaller fix): The gate's `AskUserQuestion` re-render at
each Clarify round could include a one-line "captured clarifications
so far" summary with a step-impact estimate, even without the
heuristic running in the workflow doc — pure prompt-template
addition to the user-facing panel.

## Acceptance criteria

- `track-review.md` § Track Pre-Flight steps 3–5 include a size-impact
  heuristic (or a comparable user-facing prompt) that runs after each
  Clarify / Adjust round and surfaces a warning when the estimated
  step count exceeds the per-track cap.
- A Pre-Flight gate that captures clarifications expanding the track
  beyond ~5–7 steps surfaces the size impact in the same round, not
  later via Phase A reviewer BLOCKER.
- Regression check: a session running Track 22-style clarifications
  (issue creation + per-cluster classification) should produce a
  warning during the Pre-Flight Clarify loop, before sub-step 2c writes
  the step file. (Reproducible by replaying this session's Pre-Flight
  inputs against the new gate.)
