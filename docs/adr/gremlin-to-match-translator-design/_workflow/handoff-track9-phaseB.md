<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-04T04:20Z [ctx=warning]

Paused at the context warning. **One agent is still in flight**: the `review-bugs` full dimensional
review of the latch-and-guard commit. Everything else has returned, every commit is integrated and
pushed, and the working tree is clean. Branch `gremlin-to-match-translator-design`, HEAD
`c1415a70c5`, all on `origin`.

Read this, then `plan/track-9.md` — `## Decision Log` DR-S16 through DR-S20, `## Surprises &
Discoveries` (the top five entries are all from this session), and the roster rows for steps 10 and
13.

## Where the track stands

Roster: 1, 2, 3, 5, 7, 8, 9 are `[x]`; 4, 6, 12, 14, 15 are `[~]`; **10, 11 and 13 are `[ ]`, all
three with their code landed and no episode written.** Nothing is half-committed.

```
c1415a70c5  Review fix: guard unfolded range comparisons per record   (step 10 — latch + guard + 5 held findings)
6eb525a1f7  Review fix: decline a slice behind ORDER BY               (step 10 — 8 findings, BG24)
b7cc89b371  Review fix: observe the barriers the tests rely on        (step 11 — TS38 + 2 nits)
63e3599235  Review fix: capture not() anti-joins in the child         (step 11 — BG9 blocker)
c4a512756f  Review fix: make the comparator pin prove it ran          (step 13 — TS37)
3bf563ce77  Review fix: sharpen the decline's test record             (step 13 — 9 findings)
```

Measured on the integrated tree, by the orchestrator, not inherited from an agent report:
translator package **710/710**, Cucumber **1930 / 0 / 14** translator-on (DR-S8's absolute
criterion, met), `sql.executor.match.**` + `sql.parser.**` **1673/1673**. CI run `30863465080`
completed green on all eight platforms at `965363e025`, including the Coverage Gate and Test Count
Gate.

## Do this first

1. **Collect the `review-bugs` iter2 result** for the latch-and-guard commit. It writes to
   `plan/track-9/reviews/bugs-step10-iter2.md`; if the file is absent the agent died and the review
   must be re-run. Its brief carried five specific scrutiny points, listed under
   "The three things that need a verdict" below.
2. **Compose one fix respawn covering both iter2 reviews.** test-structure returned 10 findings
   (0 blockers, 5 should-fix) in `plan/track-9/reviews/test-structure-step10-iter2.md`. The
   should-fixes are TS39, TS40, TS41, TS42, TS43. Do not spawn before the bugs review is in — a
   second respawn on the same files costs a round for nothing.
3. **Then a gate check, then three episodes at once.** Steps 10, 11 and 13 share their discoveries;
   writing them separately means writing the same material three times.

## The three things that need a verdict

**The fail-open default is the one to decide deliberately.** The guard is opt-in through a fourth
`toFilter` parameter, and the one-, two- and three-argument overloads default it **off**. A
recogniser added later that emits a range comparison in an unfolded position and forgets the flag
inherits today's divergence rather than a compile error. Every other decline mechanism on this
branch is fail-closed, so this is the exception, and it was the implementer's own critical-context
note rather than a review finding. `RangeTypeGuardEquivalenceTest`'s fixture is the intended net,
but TS39 and TS40 both say that suite's helpers are weaker than their siblings.

**Three existing tests were flipped, against an explicit instruction not to.**
`NotStepRecogniserTest.notWithCrossTypeRangeComparison_declinesAndAgreesWithNative`,
`notWithRangeComparisonBehindHop_declinesToNative` and `notWithBetweenPredicate_declinesToNative`
asserted `DECLINED` on the gate the commit deletes, so they flip tautologically; rows are unchanged
and only the boundary count moves 0 → 1. The spawn was told to stop and report; it re-pinned them at
`RECOGNIZED` instead and led its report with the deviation. **The judgement looks right and the
reasoning was stated, but TS41 already found one of the three rewritten Javadocs overstating** — it
claims a leaf-only guard would answer differently where `age` is Integer everywhere, so guarded and
unguarded agree. Check the other two against the same standard.

**TS43 says the plan-shape test cannot witness what it was required to witness.** The measurement's
condition was a plan-shape test above `estimateFilterSelectivity`'s `THRESHOLD = 100` early bail, to
prove the `IN` form does not displace an indexed alias from the root. TS43's claim is that the
plan-root and `FETCH FROM INDEX` assertions cannot distinguish `IN` from equality, because the
equality path is gated on `isBaseIdentifier()` and the pattern carries one edge. If that holds, the
`IN`-not-equality decision — the reason `MatchWhereBuilder.typeIn` exists — is unpinned.

## Episodes

**None of steps 10, 11 or 13 has an episode, and that is correct, not an omission.** An episode is
written when the review loop closes. Step 11's is closed (bugs PASS, all test-structure VERIFIED,
TS38 fixed with mutation proofs) and is writable now. Step 13's every finding is VERIFIED or routed
out, but its decline was deleted by `c1415a70c5`, so its episode must describe the post-deletion
state. Step 10's loop is open pending item 1 above.

Recover the `EPISODE_DRAFT` material from the review files and the agent returns rather than
re-deriving it. Two ID collisions to fix while writing: step 13's gate-check finding is **BG29**,
not BG23 (step 10's review file owns BG23 on disk), and step 11's gate-check finding is **TS38**,
not TS37 (step 13's closed TS37 first). Both came from handing the same high-water mark to parallel
agents — the mark is per-track and must be bumped per fan-out, not per dimension.

## Phase C, and what it owns

**The coverage gate is no longer the expensive item it was recorded as.** CI runs it and it passes;
see `## Surprises & Discoveries`. Phase C discharges it by reading a completed run on the **final**
tree, not by a local `clean package -P coverage`. A run only completes if nothing pushes over it —
twelve consecutive runs on this branch were `cancelled` by the next push, which is why the gate
looked like it had never run.

**`review-performance` is Phase C's substantive debt.** It was deliberately not run at step level
for steps 10, 11 or 13, because all three widen the decline surface and the question is better
answered once against the cumulative diff. The track now declines: any step after a captured
`SKIP` / `LIMIT` / `RETURN DISTINCT` except three pure projections; a slice over a `dropOnAbsent`
projection; **a real slice behind any `ORDER BY`** (new, BG24); any edge-bearing child of `where` /
`filter` / `and`; an edge-bearing `not()` child inside an OR or a nested NOT; and any `where` child
carrying a scope binding. Against that, the guard **gave surface back** — it retired step 13's
`not()` decline entirely. Nobody has priced the sum in either direction.

Also Phase C's: the review-burden check, which DR-S1 and DR-S2 both predicted would trip.

## Track completion

The four-arm baseline under DR-S7 and DR-S8: both runners, both arms, at the track's final SHA, from
an invocation that compiled in the same run. `embedded` has not been measured at any recent SHA and
needs `./mvnw -pl core -am install -DskipTests` first — the one thing every agent prompt on this
branch forbids, so it runs only when nothing else is live. DR-S8's criterion is absolute: **0
failures and 14 skips on both runners**, with a frozen exclusion list that is **empty**, because
`develop`'s CI is green at the merge-base and every process-compliance failure would therefore be
this branch's. Plus one completed green CI run on the final tree, which is where DR-S17 put the
process-compliance obligation when step 14 was dropped.

## Confirmed and unowned

**A pre-existing silent wrong answer on `develop`, with a deletion deadline.** The `else` branch of
`YTDBGraphStepStrategy.rebuildTraversal` inserts a `YTDBHasLabelStep` and drops the original
`HasStep`'s labels — `g.V().out("knows").hasLabel("Person").as("a").select("a")` returns `[]` on the
**native** pipeline where the oracle returns two vertices. Translator-independent, identical on
`origin/develop`. The user chose to record it rather than open an issue, so it lives in
`## Surprises & Discoveries` with a carry marker. **`_workflow/` is deleted in the Phase 4 cleanup
commit**, so if it does not reach `design-final.md` or an issue before then, it leaves the
repository.

Smaller: `MatchPatternBuilder.appendFrom` and `edgeCount()` have no production caller after step 11
retired the sub-walk merge path; both Javadocs now say so and the deletion was deliberately left as
a standalone Track 1 or Phase C item. `WherePredicateStepRecogniser.toMatchedLabelFilter` is
unguarded because it compares two aliases rather than a field against a literal — recorded as a
residual, not closed.

## Track 11 is pre-loaded

Three items were added from this track's findings: item 8 (`as()` on edge steps), item 9 (18
hand-built MATCH AST nodes — **the count is stale, the guard added one**), item 10 (equivalence-harness
consolidation plus two vacuity sweeps). Item 8's adjacent claim was **measured and refuted** — see
`plan/track-11/item8-label-probe.md`; its second fix site is one `bindStepLabels`-or-decline call in
`HasStepRecogniser.recognize`, not a start-step change. A banner above `## Concrete Steps` warns that
DR-S1's file-disjointness premise is now false; a Phase A that plans against it will size four items
wrong.

## Rules any respawn runs under

Never `mvn install`, never `-am` — the shared `~/.m2` poisons pending measurements. Never the full
coverage gate outside Phase C, and Phase C reads CI rather than running it. One Maven invocation at a
time per worktree. **The `test-compile` prefix is not optional**: `surefire:test` alone tests the
previous compilation. Cucumber figures come from `TEST-_.xml` **only**, after
`rm -rf core/target/surefire-reports` — a glob over `TEST-*.xml` reads stale unit-test reports and
invents failures, which bit two agents. The kill-switch is a plain `-D`, never inside `-DargLine=`.

**Every new or changed test gets a mutation proof.** This branch has **seventeen** recorded instances
of a test that could not fail. The two most instructive are both from this session: an assertion
written *to fix* a vacuous test was itself vacuous, because `applyStrategies()` on an anonymous `__`
traversal does not run optimisation strategies — observing inlining outside a graph test needs
`TraversalHelper.applyTraversalRecursively` with `InlineFilterStrategy.instance()` applied by hand;
and a comparator pin asserted result counts under `withTranslator(true, …)` without asserting the
shape translated, so the folded native step gave the identical answer and the pin could not tell the
engines apart.

**One methodological note, now with five instances.** A TinkerPop `OptimizationStrategy` has
invalidated a step-position assumption five times on this branch: `InlineFilterStrategy` refuted the
original fold-boundary design, re-pointed two of step 11's tests at a different code path, and made
`and(__.not(…), __.not(…))` not the shape its Javadoc claimed; `FilterRankingStrategy` relocated an
`as()` label off the step the user wrote it on; and the walker's own cursor disagreed with
`rebuildTraversal` about `NoOpBarrierStep`, which was a user-writable divergence
(`g.V().barrier().has("name", gt(27))`, native one row against six) caught only while implementing
the latch. **The traversal the translator sees is not the traversal the user wrote.** Any recogniser
keying on position, or on which step carries a label, needs a test pinning the post-strategy step
list.

Structural argument is good for deciding **where** to measure. On this branch it has not been
reliable for deciding **what is true** — seven claims overturned by measurement, three of them this
session, including one from the orchestrator.
