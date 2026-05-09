---
severity: medium
phase: phase-c
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase C iter-1 + 5-agent gate-check fan-out routinely pushes context to warning before iter-2 can start

## Symptom

Track 21 Phase C session ran:

1. The dimensional review fan-out — 8 sub-agents (CQ/BC/TB/TC/CS/TY/TX/TS),
   each producing dense markdown reports averaging ~100-300 lines.
2. Synthesis + iter-1 implementer spawn returning ~80 lines of structured
   handoff.
3. Gate-check fan-out — 5 sub-agents (CQ/BC/TB/TS/TX), each reading the
   diff + producing another dense report.

By the end of the gate-check fan-out, the orchestrator's context
consumption read **34% (warning)** per
`/tmp/claude-code-context-usage-$PPID.txt`. The mandatory protocol gate
in `track-code-review.md` § "Context consumption check" then forced the
orchestrator to save state and end the session before iter-2 could
begin. iter-1 found a real regression (TB1, the `<= 2` bound), so iter-2
is not optional — it must run, but in a fresh session.

This pattern is observable across multiple tracks (Track 14, Track 15,
Track 18, Track 19, Track 20 all reached iter-2 or iter-3, and the
context-warning gate fires reliably whenever ≥10 distinct findings need
fixing).

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` §"Multi-Step Tracks", §"Sub-agents",
    §"Review loop" (steps 3 — gate-check fan-out, 4 — context consumption check)
  - `.claude/workflow/review-agent-selection.md` (8 baseline+conditional agents)
- Tool / sub-agent involved: 8 review sub-agents + 5 gate-check sub-agents
  + 1 implementer per iteration
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C track whose initial dimensional review
  surfaces ≥ ~10 fixable findings, forcing iter-1 + a non-trivial iter-2
  (gate-check fan-out re-runs the open-finding dimensions).

## Why it's a problem

The context-budget gate fires after iter-1, halfway through Phase C.
The user sees Track 21 enter Phase C, reaches the gate-check, and is
told "session ends, please re-run /execute-tracks." Then the next
session resumes from `(1/3 iterations)`, runs iter-2, hits the context
gate again at the iter-2 gate-check. So a Phase C with 2 iterations
plus track completion takes 3 full sessions instead of 1.

Concretely: this session burned ~50% of context on review/gate-check
sub-agent outputs alone, even though most findings (~25 of ~30) were
deferred to the absorption queue or to iter-2 carry-forward. The
orchestrator never needed the full text of every sub-agent's report
— it needed a summary table per dimension.

Side-effect: the iter-2 implementer sees a step-file `## Phase C iter-1
audit` section with ~180 lines of synthesis the orchestrator wrote into
the step file before ending the session, just to communicate state to
the next session. That's 180 lines of step-file overhead that exists
solely because the orchestrator could not carry the synthesis forward
in-context.

## Proposed fix

Three options, ordered by complexity:

(a) **Cheap — declare 2-session minimum for non-trivial Phase C**: edit
`track-code-review.md` § "Track Completion" or add a new section at the
top of the file documenting that any Phase C track whose review surfaces
≥10 fixable findings (or where the per-iteration budget split per
§Review loop step 2 fires) is **expected** to take 2 sessions. Update
`workflow.md` § "Startup Protocol" so State C resume after iter-1 in
Phase C does not surprise the user.

(b) **Medium — gate-check fan-out via narrative summary**: instead of
re-running each open dimension as a full sub-agent, have the orchestrator
read the iter-1 fix commit's diff inline and ask each open dimension
sub-agent only the question *"VERIFIED / STILL OPEN / REGRESSION per
finding ID, plus any new findings limited to ≤ 5 lines per dimension"*.
The current gate-check prompts already ask for this format, but the
sub-agents still produce 100-300-line responses with lengthy "Notes",
"Files of interest", and "Reference-Accuracy Audit" sections. A length
budget enforced in the prompt (e.g., *"≤ 60 lines total"*) would cut
gate-check token usage by roughly 70%.

(c) **Heavy — eager iteration split**: when synthesis classifies findings,
if the in-scope subset > 6 findings AND > 4 distinct test files, force a
session-end after iter-1 (before gate-check) and let the next session do
gate-check + iter-2 + completion. This trades one extra session boundary
for predictable per-session context use.

Recommended: (a) + (b) together. (a) sets correct user expectations; (b)
gives back enough budget to keep simple Phase C in one session.

## Acceptance criteria

- [ ] `.claude/workflow/track-code-review.md` documents an explicit
  "non-trivial Phase C is a 2-session phase" expectation (or fixes the
  budget so it isn't).
- [ ] Gate-check sub-agent prompts in `track-code-review.md` (or in
  `review-iteration.md`) carry an explicit length budget (e.g., *"≤ 60
  lines, no Notes / Files-of-interest / Reference-Accuracy-Audit sections"*).
- [ ] Reproduction: a Phase C session with the same shape as Track 21
  iter-1 (8 dimensions, ~25 should-fix, 5-dimension gate-check) finishes
  iter-1 and gate-check below 30% (info), allowing iter-2 to start in
  the same session OR the user sees a clear note that 2 sessions are
  expected.
- [ ] No reduction in gate-check effectiveness: the test that this is
  done correctly is that a regression like TB15 (Track 21 iter-1) is
  still caught by the gate-check sub-agents.
