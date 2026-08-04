<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff: Phase B — Track 11 step 1, review iteration 1 synthesized

**Paused:** 2026-08-04
**Phase:** B
**Context level at pause:** safe (14%) — user-requested pause, not context pressure
**Branch:** gremlin-to-match-translator-design
**HEAD:** 0eaf97ad07 "[YTDB-558] Add the list-shaping recognition seam"
**Unpushed:** 0 commits

Resume by spawning one `FIX_REVIEW_FINDINGS` implementer for step 1 with the eight
findings below. Step 1's code is committed and pushed; its episode is not written and
its roster line is still `[ ]`, so the resume lands in `steps-partial` with one orphan
implementer commit — the expected shape that `step-implementation-recovery.md`
§Phase B Resume reconciles.

## Durable artifacts on disk

- `plan/track-11.md` — twelve-step roster from Phase A; `## Base commit` holds
  `f5dbfd55711bfc5ab5a838519c3fd55f35f71ca6`; `## Episodes` still empty.
- `plan/track-11/reviews/bugs-step1-iter1.md` — `review-bugs` iteration 1, four
  findings (BG1–BG4), nine evidence certificates, four CONFIRMED and five REFUTED.
  Flags: `CONTRACT_OK`, `GREP_NOT_PSI`, `NO_TEST_RUN`.
- `plan/track-11/reviews/test-structure-step1-iter1.md` — `review-test-structure`
  iteration 1, four findings (TS1–TS4), no certificates.
- Commit `f4d54aff2f` — base-commit record. Commit `0eaf97ad07` — step 1 itself.

## Pending decision

None waiting on the user. The pause is user-requested ("zapisz i dalej pojdziemy w
kolejnej sesji"), so the next action is mechanical: one fix respawn, then a
gate-check fan-out over whichever dimensions still carry open findings.

## Verbatim re-present text

Step 1 landed as `0eaf97ad07` with 728/728 `core` tests green, 86.9% line and 72.8%
branch coverage on the cumulative branch diff, and Spotless applied. The step-level
review loop then ran iteration 1 with two agents — `review-bugs` (step-level
baseline) and `review-test-structure` (the changed tests lean on Mockito
parent-context fixtures). Eight findings, no blockers: three should-fix, five
suggestions. Every one of them is documentation accuracy or test-structure work on
the five files step 1 already touches, so all eight fit one fix respawn well under
the ~15-finding / ~10-file pre-spawn ceiling.

The synthesis clusters them into four concerns:

1. **The rationale javadoc's worked example does not reach its own failure**
   (BG1, should-fix). `g.V().and(__.out().fold())` declines at the pre-existing
   edge-bearing-child gate under the swallow alternative too, so the example
   passes under the bug it is meant to demonstrate.
2. **Two of the four rationale copies state the failure direction backwards**
   (BG2, should-fix; TS4, suggestion). The interface javadoc, the commit message
   and DR-T2 all say rows disappear; two copies say rows survive. The direction
   also flips per combinator, so no single sentence covers the family — and the
   same rationale is restated in four places that must be edited together.
3. **The test-file navigation aids misdescribe the file** (TS1, should-fix; TS2,
   TS3, suggestions). The new tests sit under a section header and class javadoc
   that describe neither layer they belong to; `WalkerContext`'s `true` answer is
   pinned only as a control inside the adapter's test class; the ordering
   assertion rests on lambda-instance identity a reader cannot see, because the
   two ops are textually identical.
4. **Two javadoc claims outrun the code at this commit** (BG3, BG4,
   suggestions). `setResultShaping`'s last-step rule is stated in the present
   tense although step 2 is what enforces it, and the two-context taxonomy omits
   the union fork — a third context that gets its own `WalkerContext` and answers
   `true`, which is exactly the path DR-T3 wants a separate gate for.

## Findings handoff for the fix respawn

Review files (read bodies by anchor; the orchestrator routed on the manifest index
alone and never read a body):

- `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-11/reviews/bugs-step1-iter1.md`
- `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-11/reviews/test-structure-step1-iter1.md`

All eight are in scope for this iteration:

| id | sev | anchor | loc |
|---|---|---|---|
| BG1 | should-fix | `### BG1 ` | `RecognitionContext.java:404`; `SubTraversalPredicateAdapter.java:489`; `SubTraversalPredicateAdapterTest.java:508` |
| BG2 | should-fix | `### BG2 ` | `SubTraversalPredicateAdapter.java:489-490`; `SubTraversalPredicateAdapterTest.java:508-509` |
| TS1 | should-fix | `### TS1 ` | `SubTraversalPredicateAdapterTest.java:481` |
| BG3 | suggestion | `### BG3 ` | `RecognitionContext.java:358-361` |
| BG4 | suggestion | `### BG4 ` | `RecognitionContext.java:395-400`; `WalkerContext.java:654-663` |
| TS2 | suggestion | `### TS2 ` | `WalkerContextResultShapingTest.java:34` |
| TS3 | suggestion | `### TS3 ` | `WalkerContextResultShapingTest.java:54` |
| TS4 | suggestion | `### TS4 ` | `SubTraversalPredicateAdapterTest.java:504` |

`loc`-collapse groups BG1 + BG2 + TS4 (the four rationale copies around
`SubTraversalPredicateAdapter.java:489` and its test at 504–509) into one
implementer concern; the three ids stay separately addressable. The upgrade-only
severity backstop found nothing to raise: no finding names a correctness, crash,
CI-hang or data-loss impact.

Per-dimension high-water marks for the next fan-out: `BG` = 4, `TS` = 4.

## Resume notes

- Do NOT redo: the step 1 implementer spawn (committed as `0eaf97ad07` and pushed),
  the iteration-1 `review-bugs` and `review-test-structure` fan-out (both review
  files are on disk), or Phase B startup (base commit recorded and committed as
  `f4d54aff2f`; slim plan snapshot regenerates cheaply).
- Regenerate before the next fan-out: the slim plan snapshot at
  `/tmp/claude-code-plan-slim-<PPID>.md` (PID-scoped, so the old one is dead) and
  the step diff / changed-files temp files, whose `{commit}` advances to the
  `Review fix:` commit.
- Next action: spawn the step 1 implementer with `mode=FIX_REVIEW_FINDINGS` and the
  table above, then gate-check with the compact template from
  `prompts/dimensional-review-gate-check.md` for `bugs` and `test-structure`. The
  loop is at iteration 1 of 3.
- After the loop closes: sub-steps 5–8 for step 1 (cross-track check, context
  check, episode, episode commit), then steps 2–12. Step 2 (the walker last-step
  gate) and step 5 (union and combinator paths) are the other two `risk: high`
  steps and get their own review loops.
- Two reviewer caveats worth carrying: both agents fell back to grep because
  `steroid_execute_code` times out in this repo, so any reference-accuracy claim in
  their findings is grep-backed; and `review-bugs` ran no tests
  (`NO_TEST_RUN`), so its verdicts rest on reading, not execution.
