<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-03T11:5xZ [ctx=warning]

Track 9 Phase B is mid-flight with parallel worktrees. Steps 1 and 7 are done and on
the branch; steps 3 and 8 are committed on their own branches or still running. Resume
by reading this file, then the track file, then the branch list below.

## Where the work is

| Step | State | Branch / commit |
|---|---|---|
| 1 — repeat decline | **done**, episode written, roster `[x]` | `107de3ef34` on the design branch |
| 7 — union positional suffix | **done**, code merged, **episode not written**, roster still `[ ]` | `2def4d43f0` on the design branch |
| 3 — per-alias filter binding | implementer was running in a worktree | `ytdb-558-t9-step3` at `.claude/worktrees/t9-step3` |
| 8 — move the veto marker off the strategy list | implementer was running in a worktree | `ytdb-558-t9-step8` at `.claude/worktrees/t9-step8` |
| 2, 4, 6 — measurements | not started, deliberately serialized | — |
| 5 — residue triage | not started, depends on 3 and 4 | — |

**If a worktree branch has commits the design branch lacks**, its implementer finished
after this file was written and its structured return was lost with the session. The
commit message and the diff are the record; reconstruct the episode from them rather
than re-running the step. Rebase each onto the design branch tip and fast-forward, in
step order: `git -C .claude/worktrees/t9-stepN rebase <design-branch-tip>` then
`git merge --ff-only ytdb-558-t9-stepN`.

**If a worktree branch has no commits**, its implementer did not finish. Re-spawn it
from the roster line, which carries the full contract for both steps 7 and 8.

## What still owes work on steps already merged

- **Step 7 has no episode and no step-level dimensional review.** It is `risk: high`,
  so the review is owed before Phase C. Its `EPISODE_DRAFT` was lost with the session;
  the commit message and `## Surprises & Discoveries` carry the substance. What the
  step established, so it is not re-derived: `MultiPlanMatchStep` concatenates child
  plans branch-major through a `MultipleExecutionStream`, while TinkerPop's `union` is
  a `BranchStep` that interleaves arms per incoming traverser. Same rows, different
  order, so any positional slice after a union diverges. The fix accepts a post-union
  `range` / `limit` / `skip` only when a `count()` follows immediately, because
  `min(n, total)` and `max(0, total - n)` are order-free; everything else declines.
  `POST_UNION_RECOGNISERS` membership is now necessary but not sufficient —
  `RangeGlobalStepRecogniser` stays on the list and gates inside itself.
- **Step 1's dimensional review closed at iteration 1 with two recorded skips**: TS8
  (a fifth hand-rolled copy of the translator test harness across five classes) and
  PF1's code half (a deep hand-written chain still translates and still pays the path
  enumeration; bounding it by depth or fan-out is a design decision, not a review fix).
  Both belong on the residual list.

## Numbers that are load-bearing

`core` feature suite at `107de3ef34`: **1930 scenarios / 42 failures / 14 skipped**,
single fork, translator on, orchestrator-verified. **42 is the pre-triage number**, not
41 — see `## Artifacts and Notes` for why the intermediate 41 was an artifact of an
accidental narrowing rather than an improvement. Step 7 changed `core` behaviour after
that verification, so the figure must be re-taken before step 2 publishes anything.

CI covers both Cucumber runners on the **on-arm only** from `b35ac67d2f` onward and
never sets the kill-switch. Banked figures and their three qualifications are in
`## Artifacts and Notes`.

## Rules the parallel worktrees run under

Every worktree implementer was given these and they must be repeated on any re-spawn:
never `mvn install`, never `-am` (the shared `~/.m2` would poison a pending
measurement — Track 11 finding R5 confirms the mechanism); never the full Cucumber
suite or a whole-module `core` test run, targeted `-Dtest=` only; one Maven invocation
at a time per worktree; commit on the worktree branch and do not push.

**Steps 2, 4 and 6 are measurements and must not run concurrently with anything that
touches `core` or `embedded`.** Rule R8 in `## Decision Log` invalidates a baseline on
any qualifying commit, and the suite is additionally perturbable by machine load. Stop
the other streams before measuring.

## Track 11, out-of-band

Its Phase A panel ran early and in parallel: `plan/track-11/reviews/technical-iter1.md`
(8 findings, 1 blocker), `risk-iter1.md` (9, 2 blockers), `adversarial-iter1.md` (9, 2
blockers). **No ledger entry was appended for Track 11** — the ledger stays Track 9's,
so its formal Phase A will find the reviews on disk and iterate from there rather than
starting cold. Item 7 was already revised against A2 / T5 / A7 and is committed. The
union-ordering cluster (T1, T3, R2) and the baseline-ancestry findings (R4, A6) were
deliberately left for Track 11's own Phase A, because they depend on what Track 9 does
to the union surface — which step 7 has now changed, so re-read them against
`2def4d43f0` rather than against the state they were written on.

Step 7's cross-track finding bears directly on Track 11 item 4: **`tail(n)` inherits
the same defect and cannot be added as a bare post-union suffix**, though
`tail(n).count()` would be order-free; and `fold()` cannot be added either, because
the folded result is a one-element multiset whose member is a `List` and `List.equals`
is order-sensitive, so a multiset comparison of `union(...).fold()` is an ordered
comparison in disguise.

## The recurring failure mode, now at five instances

Acceptance assertions on this branch keep passing without exercising the path they
name. Phase A caught two, step 1's review caught a third (TS1 / TS2 / TS3), Track 11's
technical review caught a fourth (T4 — `supportsListShaping()` defaults to `true`,
which inverts under a Mockito mock), and step 7 found the fifth: every existing
post-union slice test used a fixture whose arms were too short to separate the two
orders, or asserted cardinality plus membership instead of the multiset. The defect
was reachable the whole time and the fixtures could not see it. Any new decline
assertion needs a positive control of the same shape, measured rather than derived.
This belongs in `design-final.md` at Phase 4.

## D14 deviation to record

Track 11's adversarial pass ran on Opus. `design_gate` resolves to `yes` (ledger
`tier=full`, `design.md` exists), so D14 required a Fable 5 pin. Root cause, recorded
as finding A9: `phase-ledger.md` carries no `design_gate` field and D14 states no
fallback. Worth a self-improvement issue.
