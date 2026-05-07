---
severity: low
phase: phase-c
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# Implementer commits but does not push, leaving draft PR out of sync

## Symptom

The Phase C iter-2 implementer for Track 18 successfully landed
`Review fix:` commit `84e117de31` on top of HEAD and emitted a
`RESULT: SUCCESS` block. Its `TEST_SUMMARY` reported `passed: 55 / 55,
spotless_applied: yes`, and its `TOOLING_NOTES` recorded
`maven_cycles: 3, ide_refactor_used: no`. The orchestrator's
post-spawn verification (`git log @{u}..HEAD`) found one unpushed
commit — the implementer had committed but not pushed. The
orchestrator pushed manually before continuing.

The implementer-rules document (`.claude/workflow/implementer-rules.md`)
references `commit-conventions.md` § Push every commit only at line
396, inside a paragraph describing the **orchestrator's** commit
cadence for workflow-file changes. The implementer's own sub-step 3
(commit) and §Return contract (`SUCCESS` requires `COMMIT: <sha>`)
contain no explicit instruction to push. Because the rulebook does
not surface a push obligation in the implementer's contract, the
behaviour is implicit and easy for an implementer to skip.

## Reproduction context

- Phase: phase-c (also reproducible in phase-b)
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` §"What the implementer
    does (sub-steps 1–3, expanded)" → sub-step 3 (commit)
  - `.claude/workflow/implementer-rules.md` §"Return contract"
  - `.claude/workflow/commit-conventions.md` §"Push every commit"
  - `.claude/workflow/track-code-review.md` §"Implementer Spawns"
    (already says "Each implementer's `Review fix:` commit is pushed
    by the implementer itself" — but the rulebook does not echo
    this back to the implementer's contract)
- Tool / sub-agent involved: any Phase B / Phase C implementer spawn
  (`level=step` and `level=track`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any `RESULT: SUCCESS` return that produces a
  commit. The implementer is free to omit the push because nothing
  in its own rulebook gates SUCCESS on a successful push.

## Why it's a problem

The friction is small per occurrence (one extra orchestrator turn
to verify and push) but recurs on every implementer spawn that
lands a commit — Phase B per-step spawns, Phase B `Review fix:`
respawns, Phase C iter-N spawns. It is also **silent**: a
less-careful orchestrator could mark the iteration complete and
end the session without pushing. The draft PR then carries an
out-of-sync state until the next push. This contradicts the two
stated motivations of the "Push every commit" rule
(`commit-conventions.md` lines 21–27): team visibility on the draft
PR and disk-loss backup. Both are silently weakened any time an
implementer commit is left unpushed.

Track 17's Phase C iter-1 (2026-04-29) hit a related issue tracked
in `WORKFLOW_ISSUE_implementer_silent_exit.md` — the implementer
exited silently before pushing. PR #1043 (commit `c49a897a53`)
hardened the orchestrator's recovery path for budget-pressure
silent exits, but did not address the routine-success case where
an implementer simply forgets to push.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` to elevate the push
obligation from an indirect cross-reference to an explicit
sub-step. Two surgical changes:

1. In §"What the implementer does (sub-steps 1–3, expanded)",
   rename sub-step 3 from "commit" to "**commit and push**" and
   add a single sentence: "*After the commit lands, run
   `git push` immediately, per `commit-conventions.md` § Push
   every commit. The implementer is responsible for the push;
   the orchestrator does not push on the implementer's behalf.*"

2. In §"Return contract", under the `SUCCESS` field rules
   (`.claude/workflow/implementer-rules.md` ~line 763), add: "*A
   `RESULT: SUCCESS` return implies the commit at `COMMIT` has
   been pushed to `origin`. If the push failed (e.g., `non-fast-
   forward` from concurrent activity, network blip), do not emit
   `SUCCESS` — surface the situation via `RESULT: FAILED` with
   `FAILURE.recommended_action: retry` so the orchestrator can
   reconcile.*"

Optionally also add a one-line check in
`.claude/workflow/track-code-review.md` §"on_iteration_success" /
`.claude/workflow/step-implementation.md` §"on_success" so the
orchestrator's post-success path does a defensive
`git log @{u}..HEAD` check and surfaces a clear error if anything
is unpushed — defense in depth in case an implementer slips.

## Acceptance criteria

- `.claude/workflow/implementer-rules.md` sub-step 3 explicitly
  names `git push` as part of the implementer's contract.
- `.claude/workflow/implementer-rules.md` §Return contract
  documents that `SUCCESS` implies the commit is pushed.
- A grep for `git push` against `implementer-rules.md` returns
  the new explicit instructions, not just the indirect §Push
  every commit cross-reference at line 396.
- (Optional defense-in-depth) After
  `track-code-review.md` §"on_iteration_success" or
  `step-implementation.md` §"on_success" runs, the orchestrator
  no longer needs to manually invoke `git push` — verified by a
  Phase B / Phase C dry run that the implementer's commit is
  already on `origin` when control returns.
