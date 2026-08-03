<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-03 [ctx=warning]

Track 9 Phase B ran four steps in parallel worktrees and is paused at the context
warning. Read this file, then `plan/track-9.md` (`## Decision Log`, `## Surprises &
Discoveries`, `## Artifacts and Notes`, `## Episodes`), then the branch list below.

## Step state

| Step | State |
|---|---|
| 1 — repeat decline | done, merged, episode written, roster `[x]` |
| 3 — per-alias filter binding | code merged, reviewed, **fix landed** (`c4d9d67ae7`), no episode, roster `[ ]` |
| 7 — union positional suffix | code merged, reviewed, **fix landed** (`001ea7921c`), no episode, roster `[ ]` |
| 8 — veto marker carrier | done, merged (`55da40dcdd`), **no episode**, roster `[ ]` |
| 2, 4, 6 — measurements | not started, must run serially with nothing else touching `core` |
| 5 — residue triage and fixes | not started; scope expanded by DR-S2, see below |

**Both fix respawns landed and are merged.** No spawn is in flight. The design branch
tip carries every step's code; `...gremlin.translator.**` is 612 / 612 green with all
four steps combined. Nothing needs re-running — the outstanding work is episodes,
measurements and step 5.

Worktrees: `t9-step3`, `t9-step7`, `t9-step8` under `.claude/worktrees/`. Steps 3, 7
and 8 are already merged into the design branch, so those branches are only useful for
in-flight work. Rebase then `git merge --ff-only` in step order.

## Episodes owed

Steps 3, 7 and 8 all lack episodes and all three are `risk: high`. Their step-level
dimensional reviews are done — 25 findings across steps 3 and 7, 0 blockers as graded,
12 should-fix, files under `plan/track-9/reviews/`: `bugs-step3-iter1.md`,
`performance-step3-iter1.md`, `test-structure-step3-iter1.md`, `bugs-step7-iter1.md`,
`performance-step7-iter1.md`, `test-structure-step7-iter1.md`. Step 8 had no
step-level review; decide whether to run one or let Phase C cover it.

Two orchestrator severity calls, already reflected in the fix respawns: step 3's BG1
plus PF1 (same site, `MatchPatternBuilder:391`, the NOT path binding the generic `V`
class the walker site skips) were **upgraded to blocker** because the basis says
"divergent". Step 7's BG3 was **excluded** from its respawn — see below.

## The decision that reshapes the rest of the track (DR-S2)

Measured at `55da40dcdd`, one flag apart: translator **off** is 1930 / **0 failures**
/ 14; translator **on** is 1930 / **42** / 14. Every compliance failure is a
regression this branch introduces, not inherited debt, and the kill-switch defaults to
true. The user was offered a kill-switch flip to `false` (green by construction) and
chose instead to **fix the whole residue in step 5**, the twelve Track-6-surface
defects included. `## Decision Log` DR-S2 carries the full rationale and the cost:
the track grows past DR-S1's split size and Phase C's review-burden check is expected
to trip. If item 4's two-working-day ESCALATE fires, the fallback is the kill-switch
flip, not a follow-up issue.

## Two live defects with no owner yet

Both are silent wrong answers on recognised shapes, both recorded in `## Surprises &
Discoveries`, both in the mandatory-handling category.

- **A1** — `union(...).range(2,5)` and `.limit(3)` diverged on/off. **Fixed** by step 7.
- **BG3** (step 7 bugs review, `### BG3 `, cert C7) — pre-existing, single-plan path: a
  slice followed by a hop compiles to a statement-level SQL `SKIP`/`LIMIT`, so
  `g.V().limit(2).out()` slices the **hop's output** instead of its input. Not fixed,
  not assigned. The user directed A1 fixed in-track rather than dispositioned; they
  were asked whether BG3 gets the same treatment and had not answered when the session
  paused. Default is that step 5 inherits it.

## Step 3's fix landed — what the commit does not tell you

`c4d9d67ae7` ("Review fix: stop binding the generic vertex root") closed step 3's
review. **The feature suite moved from 42 failures to 27** at the same 1930 run and 14
skips, translator on. That is the drop the dropped-filter family was expected to
produce, now measured. Off-arm is still 0, so **27 regressions remain** and DR-S2 puts
all of them in step 5's scope.

Three things from that spawn that the diff does not carry:

- **BG3 did not under-emit, it declined.** With the resolver mutated back to the raw
  label, `g.V().as("a").out().where(P.neq("a"))` engages zero boundary steps: the plan
  build fails on the unknown `$matched.a` alias and the strategy's throw-safety net
  degrades to native. The reviewer's worse branch never materialised. The fix is still
  worth having — it turns an accidental decline into a correct translation and makes
  the unresolvable case an explicit decline.
- **BG2 was fixed test-side only, and the reason bounds a future step.** The
  over-emission is real and now pinned by
  `whereFragmentWithSeveralMatchingTargets_translatedPlanOverEmits`. The production
  exit was not taken because `ConnectiveStepSupport.commitEdgeBearingChild` is reached
  from **three** recognisers — `WhereTraversalStep` and `TraversalFilterStep` through
  `commitPositiveFilterChild`, and `AndStepRecogniser` directly — so a decline covers
  all three or leaves the defect live on two. The other candidate exit, `returnDistinct`,
  is wrong in general: it collapses path multiplicity native Gremlin legitimately
  produces (`g.V().out()` returns a target once per in-path). **Step 5 should size this
  as one fix across three recognisers**, and expect the suite count to move in either
  direction depending on the exit.
- `MatchPatternBuilder.buildNotExpression` is now three-argument with **no** two-argument
  overload, deliberately: a future front end has to state which of its registered
  classes is generic, which is what stops the two binding sites drifting apart again.

PF2 was skipped with a reason (per-row `SQLIdentifier` allocation in
`MatchEdgeTraverser.targetClassName` is executor work warranting its own issue). Two
ephemeral-identifier leaks were cleaned up in passing.

## Numbers

`core` feature suite, translator on, at `c4d9d67ae7`: **1930 / 27 / 14** (was 42 before step 3's review fix). Translator
off at the same commit: **1930 / 0 / 14**. The whole `...gremlin.translator.**` package
is 601 / 601 green. The track recorded that 28 of the original 42 were count
comparisons and 25 of those over-emission — the dropped-filter signature — and step 3's
review fix is where that drop actually landed, not step 4. **Step 4 has still not run**,
and it is owed: it is the step that records both directions of drift against step 2's
baseline, and step 2 has not run either.

Banked CI figures and their three qualifications are in `## Artifacts and Notes`; they
are pinned to `b35ac67d2f` and superseded by anything later.

## Step 7's fix — what the commit does not carry

`001ea7921c` re-keyed the post-union positional gate off recogniser identity onto a
property: `StepRecogniser.selectsPositionally(Step)` with a safe `false` default, each
allow-list member stating its own answer, and a unit test over `POST_UNION_RECOGNISERS`
that **fails the build** if a future member inherits the default. That is a hard gate in
front of Track 11 item 4 — a `tail` recogniser must answer `true`, since `tail(n)`
selects from the end of the concatenation, the position the two arrival orders disagree
about hardest, and will then translate only ahead of an immediate `count()`.

Two facts worth keeping. `skip(n)` and `range(n, -1)` compile to the identical
`RangeGlobalStep(n, -1)`, so TS2's supposedly unpinned shape was already covered by the
existing `skip(1)` guard; the guard was added anyway so the block reads as guarded
without that knowledge. And the decline's real cost, now recorded in
`RangeGlobalStepRecogniser`'s Javadoc rather than the track file: because the walk is
all-or-nothing, withdrawing the post-union slice **withdraws the prefix from MATCH too**.
Post-concat `order()` is the recovery path and should be sequenced ahead of other
post-concat work if union paging matters to consumers.

BG3 was left untouched as instructed and verified unaffected — the single-plan branch
and `GremlinPatternAssembler.claimFoldedHop` are byte-identical, and the
`g.V().limit(2).out()` repro behaves exactly as at `2def4d43f0`.

## Steps 2, 4 and 6 should be restructured before they run (proposed, user raised it, not yet applied)

Do not run step 2 in its written shape. Most of what it was going to produce either
already exists or lost its purpose:

- **`core`'s two-arm A/B exists and is orchestrator-verified** at `55da40dcdd`: on
  1930 / 42 / 14, off 1930 / 0 / 14. That was step 2's core deliverable.
- **The per-directory table lost its reason.** It was the baseline shape for item 1's
  escalation branch, taken only if the single fork never completed. It completes, the
  branch is not taken, and step 2's own wording defers to "whichever shape step 1
  established" — which is single-fork.
- **What genuinely remains unmeasured is `embedded`'s off arm.** CI supplies the on arm
  (1931 / 41 / 14 at `b35ac67d2f`) and nothing anywhere supplies the off arm. Under
  DR-S2 that number decides whether `embedded`'s 41 are also this branch's regressions.
- **Step 4 is now redundant.** Its job was recording drift after the filter fix; the
  drift is 42 → 27 and it is recorded. The only gap is that 27 is implementer-measured
  where 42 and 0 are orchestrator-verified — a two-minute re-check, not a step.
- **Step 6 stays and is the real measurement.** R8 invalidates any baseline on a
  qualifying commit, and step 7's fix plus all of step 5 are still to land, so the
  final two-runner A/B has to be taken at the track's last commit regardless. That is
  the number Track 11 reads.

Proposed shape: step 2 shrinks to `embedded`'s off-arm run plus writing the baseline
artifact from figures already taken; step 4 is dropped into step 6; step 6 remains the
single final A/B. The artifact still has to be written either way — those figures live
in commit messages and a session transcript today, not in a file under `plan/track-9/`.
**Amend the roster before running any of the three.**

## Rules the parallel worktrees run under

Repeat these on any re-spawn: never `mvn install`, never `-am` (shared `~/.m2` poisons
pending measurements — Track 11 R5 confirms the mechanism); never the full Cucumber
suite or a whole-module `core` run except where a spawn explicitly authorises one;
one Maven invocation at a time per worktree; commit on the worktree branch, do not
push. **Steps 2, 4 and 6 are measurements — stop every other stream before running
them.**

## Track 11

Phase A panel ran out-of-band and is committed: `technical-iter1.md` (8 findings, 1
blocker), `risk-iter1.md` (9, 2 blockers), `adversarial-iter1.md` (9, 2 blockers) under
`plan/track-11/reviews/`. **No ledger entry was appended for Track 11.** Item 7 was
revised against A2 / T5 / A7 and is committed. The union-ordering cluster (T1, T3, R2)
and the baseline-ancestry findings (R4, A6) were left for Track 11's own Phase A and
must be re-read against `2def4d43f0`, which changed the union surface underneath them.

Step 7 established two hard constraints for Track 11 item 4: **`tail(n)` cannot be a
bare post-union suffix** (it selects by position and inherits the ordering defect,
though `tail(n).count()` would be order-free), and **`fold()` cannot either**, because
the folded result is a one-element multiset whose member is a `List` and `List.equals`
is order-sensitive. Step 7's own review added BG2: the positional gate keys on
`RangeGlobalStepRecogniser.INSTANCE` rather than on a property, so a new
`POST_UNION_RECOGNISERS` member would get the membership gate and no positional gate —
the fix respawn was asked to re-key it.

## The recurring failure mode, at eight instances

Acceptance assertions on this branch keep passing without exercising the path they
name. Two in Phase A, one in step 1's review, one in Track 11's technical review (T4,
a Mockito mock inverting a `true` default), one found by step 3 itself (a boundary
counter reading `YTDBMatchPlanStep` where a union splices `MultiPlanMatchStep`), one in
step 3's review (TS1, confirmed by trace), and two in step 7's review (TS1, TS2). Step
3's review also found the counter trap still live in three more helpers (TS3), where a
DECLINED case passes inverted. The durable rule — a decline assertion needs a positive
control of the same shape, measured rather than derived — is recorded for
`design-final.md`. The user was offered promoting it to an enforced review rule and had
not answered when the session paused.

## D14 deviation

Track 11's adversarial pass ran on Opus; `design_gate` resolves to `yes`, so D14
required Fable 5. Root cause recorded as A9: `phase-ledger.md` carries no `design_gate`
field and D14 states no fallback. Worth a self-improvement issue.
