---
severity: high
phase: phase-c
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase C iter-1 finding spec must sanity-check reviewer-proposed bound tightenings against step-file design rationale

## Symptom

During Track 21 Phase C iter-1 the orchestrator forwarded a TB-dimension
sub-agent's suggestion ("tighten `<= racerCount` to `<= 2`") verbatim to
the iter-1 implementer. The change landed in commit `19857464e5`. The
gate-check fan-out then reproduced 6 of 8 failures of
`StaleTransactionMonitorTest.testStartIdempotentUnderConcurrentRace` with
`actual: 8` against the `<= 2` bound — a real regression on multi-core
hardware. Step 4's own "Critical context" block in
`docs/adr/unit-test-coverage/_workflow/tracks/track-21.md` (line ~477)
had **explicitly** warned: *"tightening to at-most-1 would falsely fail
until the production code is hardened to `AtomicReference` /
`compareAndSet` — the test comment documents this."* The orchestrator
did not surface that conflict before spawning the implementer. An entire
iter-2 of the 3-iteration budget must now be spent reverting the change
and applying the rest of the deferred findings.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved: `.claude/workflow/track-code-review.md`
  §"Synthesis" + §"Review loop" (Classify findings, Pre-spawn budget
  check, Spawn the per-iteration implementer)
- Tool / sub-agent involved: dimensional review sub-agent
  (`review-test-behavior` in this case) + the orchestrator's iter-1
  finding-spec composition
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C iteration where a sub-agent flags an
  assertion-precision finding on code whose existing step file (or
  episode) carries rationale for the deliberately-loose shape.

## Why it's a problem

A reviewer sub-agent does not know what already shipped: it sees only
the diff and the slim plan. The orchestrator is the only place where
the intersection of "reviewer suggestion" + "existing step-file design
context" can be checked. When the orchestrator forwards a finding spec
verbatim, the implementer applies the suggested fix and the regression
lands. Discovery happens post-hoc in gate-check, costing one full
iteration of the 3-iteration Phase C budget. In this case the implementer
correctly executed the spec; the regression is a workflow gap, not an
implementation gap.

This is structurally distinct from "blocker findings get applied". The
issue is specifically *bound-tightening* / *assertion-tightening*
findings whose looser form was a deliberate design choice the step
episode documents.

## Proposed fix

Edit `.claude/workflow/track-code-review.md` §"Synthesis" item 1
("Deduplicate") and item 2 ("Prioritize") to add a rule: **before any
finding that proposes tightening an existing assertion bound, the
orchestrator must read the step-file episode for the affected
test/method and surface a conflict line if the episode contains
"deliberately loose", "intentionally relaxed", "WHEN-FIXED", "until
production is hardened", or similar rationale.** If a conflict is
detected, the finding is downgraded to a *suggestion* deferred to a
follow-up production-hardening track, NOT applied this iteration.

A heuristic that catches the common case:

```
For each finding F whose proposed_fix mentions "tighten", "bound",
"assertion", "narrow", "stricter":
  episode_text = read step file episode for the test method F.location
  if episode_text contains any of:
      ["deliberately", "intentionally", "loose", "relax", "until",
       "WHEN-FIXED", "is hardened", "safety", "atomic"]
  then mark F as DEFER and emit a one-line conflict note in the
  synthesis output.
```

The orchestrator's synthesis output gains a brief "Findings deferred
due to step-file rationale conflict" subsection, which feeds back into
the deferred-cleanup-track absorption block via the existing plan-
correction handoff.

Alternative (lighter): add a one-line reminder to the §"Spawn the
per-iteration implementer" instructions: *"Before forwarding a
proposed assertion tightening, confirm the step-file episode for the
affected test does not document a deliberate loose-bound rationale."*
This relies on the orchestrator's discretion rather than a structural
check.

## Acceptance criteria

- [ ] `.claude/workflow/track-code-review.md` adds the conflict-check
  rule (either structural or discretion-level) in §Synthesis or
  §Review loop step 1.
- [ ] A worked example (Track 21 iter-1 TB1 case) documents the
  expected output: the synthesis pre-implementer output should have
  flagged the `<= 2` proposal as conflicting with Step 4 rationale,
  and the implementer should have either received a less-tight bound
  or skipped the tightening entirely.
- [ ] Reproduction: in a future track where a step file episode
  contains "deliberately loose" + the next Phase C surfaces a tightening
  proposal, the synthesis output names the conflict before the
  implementer spawn.
