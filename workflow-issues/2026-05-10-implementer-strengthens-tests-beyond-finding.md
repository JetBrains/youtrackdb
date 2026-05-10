---
severity: medium
phase: phase-c
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# Implementer "strengthens" tests beyond finding spec when applying FIX_REVIEW_FINDINGS

## Symptom

In Phase C iter-1 of Track 22a's track-level review-fix loop, finding F3
(CQ3) instructed the implementer to delete a tautological
`assertNotNull(pageFrameTest)` line from
`EntityImplTest.testStorageCoupledLazyPathsAreOutOfScopeForwardedToPageFrameSuite`.
The implementer deleted the line as instructed but autonomously *added*
a new `assertEquals(<FQN>, pageFrameTest.getName())` "to keep the
rename-pin falsifiable" (per the implementer's own FIX_NOTES). The
addition is itself tautological — `Class.forName(X).getName()` is `X`
by JLS for non-array, non-primitive classes — so the iter-2 gate check
flagged it as TB-8 ("the iter-1 added assertEquals is unfalsifiable")
and iter-2 had to revert the addition as F18.

This consumed gate-check iteration budget on a defect the implementer
introduced rather than a defect that pre-existed.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` (the rulebook the
    implementer reads)
  - `.claude/workflow/step-implementation.md` § Implementer Prompt
    Template (specifies `mode=FIX_REVIEW_FINDINGS` and `findings:`
    payload but not the scope-of-application discipline)
- Tool / sub-agent involved (if any): the per-iteration implementer
  spawn (`level=track`, `mode=FIX_REVIEW_FINDINGS`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Any FIX_REVIEW_FINDINGS spawn where a finding's
  proposed-fix is "delete X" or "modify X to Y" — the implementer is
  free to add new code beyond X if it judges the result needs
  strengthening.

## Why it's a problem

When the implementer freelances additions beyond the finding's
specification, three things go wrong:

1. The orchestrator's synthesis treats the iteration as fully-applied,
   but the gate-check sub-agents see a new addition that was never in
   the original synthesis. They flag it as a new finding, consuming
   iteration counter budget that should have gone to real defects.
2. The user / reviewer cannot audit "what changed and why" by reading
   the finding list alone — the implementer's autonomous additions are
   invisible until they appear in a diff.
3. The implementer's confidence in its own additions is sometimes
   misplaced (TB-8 was a tautology the implementer believed was
   strengthening; in fact it was unfalsifiable).

Track 22a Phase C had 17 should-fix findings split across 2 fix
iterations (8 + 11) of the 3-iteration cap, leaving exactly 1
iteration of buffer. F18's revert ate part of that buffer; if a true
regression had also surfaced, the loop would have escalated.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` to add a "Scope of
fixes" rule for `mode=FIX_REVIEW_FINDINGS`:

> When `mode=FIX_REVIEW_FINDINGS`, apply *only* what each finding's
> "Proposed fix" instructs. Do not add adjacent assertions, do not
> "strengthen" the test beyond the finding's specification, do not
> introduce new test methods unless the finding instructs the
> creation of one. If the implementer judges that a finding's
> proposed fix would weaken falsifiability without a compensating
> assertion, emit `RESULT: DESIGN_DECISION_NEEDED` instead of
> autonomously adding the compensation — the orchestrator routes
> that to the user via §design-decision-escalation, who decides
> whether to instruct an addition.

Alternative (if the rule is judged too strict): require the
implementer to enumerate any additions in `FIX_NOTES.what_was_fixed`
under a labelled subsection like "Beyond-finding additions" so the
orchestrator's synthesis can flag them for the gate check explicitly
(rather than the gate check having to discover them blind).

## Acceptance criteria

- `.claude/workflow/implementer-rules.md` carries a "Scope of fixes"
  subsection (or equivalent) defining the boundary between
  finding-instructed edits and autonomous additions.
- A future Phase C session whose iter-1 implementer adds a
  beyond-finding assertion either (a) routes through
  `RESULT: DESIGN_DECISION_NEEDED` per the new rule, or (b) labels
  the addition explicitly in `FIX_NOTES`.
- Spot-check: grep `implementer-rules.md` for "scope of fixes" or
  "beyond the finding" returns the new rule's anchor.
