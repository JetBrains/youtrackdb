---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Track-level diff exceeds the protocol's "inline diff" assumption at coverage-track scale

## Symptom

Track 19's cumulative diff measured 249 KB / 5,459 lines (28 files,
5,169 insertions). The Phase C protocol's sub-agent context block
documents the diff as inline content:

> ## Diff
> {output of git diff {base_commit}..HEAD — passed inline since it
> is the review target}

But inlining 249 KB into 6 review sub-agents × 3 iterations × the
gate-check fan-out would have embedded ~4 MB of diff data in the
orchestrator's tool-call history alone (each prompt is part of the
orchestrator's own context, separately from the sub-agent's
context). The orchestrator was already at 24% context after the
review-fan-out spawn; a literal-protocol implementation would have
pushed it past `warning` (30%) before iter-1 even began.

The orchestrator adapted by writing the diff to
`/tmp/claude-code-track-19-diff-$PPID.txt` and pointing each
sub-agent at the file path. This worked, but it was an ad-hoc
adaptation with no protocol guidance — the prompt block I sent diverged
from the canonical context block in `track-code-review.md` §
Sub-agents → "Context passed to all sub-agents".

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § Sub-agents — Context
    passed to all sub-agents (the canonical context block)
  - `.claude/workflow/code-review-protocol.md` (referenced for
    iteration protocol)
  - `.claude/workflow/step-implementation.md` § Per-Step Orchestration
    Loop sub-step 4 (step-level review has the same inline-diff
    assumption)
- Tool / sub-agent involved: 6 dimensional review sub-agents, plus
  the gate-check spawns
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any track whose cumulative `git diff
  base..HEAD` exceeds ~30 KB. Coverage tracks (Tracks 14/15/16/17/18
  in this same plan also produced large diffs) are particularly
  affected because they're test-additive in bulk. Step-level review
  on `risk: high` steps with large changes (rare, but possible —
  e.g., schema migrations) faces the same issue.

## Why it's a problem

The current protocol is silent on diff size; agents either follow
the literal text and blow context, or invent ad-hoc adaptations
that diverge from the canonical context block (and may not be
forwarded consistently — a future Phase C sub-agent that doesn't
get the path indirection might Read the wrong file or fail to find
the diff). On this session, the adaptation cost ~1 turn (writing
the file + customising the prompt block); without the adaptation,
the session would have crossed the warning threshold before iter-1
applied any fixes.

The asymmetry between "inline plan/track-file = bad (paths only)"
and "inline diff = good (always inline)" is also unmotivated for
large diffs — both are review targets, both grow linearly with
track size, both bloat the orchestrator's context per spawn × per
iteration.

## Proposed fix

Add a rule to `track-code-review.md` § Sub-agents — Context passed
to all sub-agents:

```
## Diff
- For diffs ≤ ~30 KB: pass inline as today.
- For diffs > ~30 KB:
    1. Write the diff once: `git diff {base_commit}..HEAD >
       /tmp/claude-code-track-{N}-diff-$PPID.txt`
    2. Substitute the inline content with:
       """
       The cumulative track diff is large (NN KB / NN lines). Read
       it from disk:
         /tmp/claude-code-track-{N}-diff-$PPID.txt
       This is `git diff {base_commit}..HEAD` written to a file. You
       can also re-run that git command yourself.
       """
    3. Regenerate the file after every iteration's `Review fix:`
       commit lands, so gate-check sub-agents see the post-fix
       diff.
- The orchestrator decides per-spawn (a single `wc -c` is enough);
  do not over-engineer a threshold negotiation.
```

Apply the same rule to `step-implementation.md` § Per-Step
Orchestration Loop sub-step 4 for `risk: high` steps with large
diffs.

Alternative: keep the canonical context block path-only for ALL
diffs, regardless of size. Eliminates the threshold check and the
asymmetry, at the cost of one extra `git diff > /tmp/...` write
per iteration even on small tracks.

## Acceptance criteria

- `track-code-review.md` § Sub-agents documents the size-aware diff
  handling, with the literal substitution string the orchestrator
  uses when the diff is large.
- `step-implementation.md` § Per-Step Orchestration Loop sub-step 4
  picks up the same rule for `risk: high` step-level reviews.
- The temp-file naming follows the project's concurrent-agent
  isolation convention (`$PPID` in the filename, per the user-
  global `Concurrent Agent File Isolation` rule).
- A note covers the "regenerate after every iteration" requirement
  so gate-check spawns see the post-fix diff, not the original.
