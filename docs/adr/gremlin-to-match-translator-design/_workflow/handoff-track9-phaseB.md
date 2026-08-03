<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-04T02:10Z [ctx=warning]

Paused at the context warning. **Nothing is in flight**: all five agents returned, all four
working branches are integrated and pushed, and the tree is green. Branch
`gremlin-to-match-translator-design`, HEAD `de638ca85f`, working tree clean, everything on
`origin`. Read this, then `plan/track-9.md` (`## Decision Log` DR-S9 through DR-S15,
`## Surprises & Discoveries`, and step 9's episode).

## Where the track stands

Steps 9, 10, 11 and 13 all have their code landed on the branch, verified together:
`./mvnw -pl core -o test-compile surefire:test -Dtest='…gremlin.translator.**'` gives
**683 / 683**. Roster: 1, 2, 3, 5, 7, 8, 9 are `[x]`; 4, 6, 12 are `[~]`; **10, 11 and 13 are
still `[ ]` — their code is in, their review loops are not closed.** 14 is not started.

```
db3fe3b422  Decline edge-bearing positive filters                  (step 11)
f96bcfc6ee  Decline range comparisons inside not()                 (step 13)
12b1359ce3  End the translator walk at a captured slice            (step 10, BG3)
2c61514ca5  Close both orderings of the clause-position collision  (step 10, BG7 + returnDistinct)
1ee9a270df  Stop treating where() scope bindings as transparent    (step 10, BG8)
```

**The Cucumber arithmetic is unresolved and must be measured, not inferred.** Step 11 alone took
1930 / 5 / 14 to 1930 / 1 / 14. Step 13 alone took it to 1930 / 4 / 14. Step 10's BG8 also took
it to 1930 / 4 / 14 — and step 10 flagged that its moving scenario is a labelled `where`, most
likely the *same* one step 11 cleared, so the three **overlap rather than add**. The merged tree
should still reach 1930 / 0 / 14, which is DR-S8's absolute criterion, but by a different route
than 5 − 4 − 1 − 1. **Nobody has measured the merged tree.**

## Do this first

1. **Measure the merged tree**, both runners, both arms. Scope the extraction to `TEST-_.xml`
   or `rm -rf core/target/surefire-reports` first — `surefire:test@gremlin-feature-compliance-tests`
   does not clean the directory, and a glob over `TEST-*.xml` reads stale unit-test reports and
   invents failures. This bit two agents already.
2. **Close step 11's review loop — it carries the only blocker.** `BG9` (CONFIRMED, cert C1)
   refutes the `not`-exemption this session recorded as an invariant:
   `g.V().or(not(out(a)).has(name, x), has(age, 30))` returns `[x]` against native's `[x, y]` at
   one boundary step, because an edge-bearing `not()` inside an OR arm leaks its anti-join past
   the capture boundary. Also `BG11` (a path-scoped `where` mis-keyed onto the current boundary:
   `g.V().as(a).out(knows).where(as(a).has(age, 30))` returns `[]` against `[Bob]`) and `BG10`
   (`RecognitionContext.appendPattern` now has zero production callers, so the regression guard
   two test Javadocs claim does not exist). Six test-structure findings, 0 blockers.
3. **Close step 13's loop, folding in DR-S15's narrowing** — one respawn, not two. Its review
   returned 0 blockers. `BG22` independently confirms DR-S15's premise by measurement: the gate is
   type-unaware and withdraws negated range comparisons whose arms already agree, with same-type
   control `g.V().out().has(age, gt(30))` agreeing 1/1. `BG21` is a real completeness gap — the
   detector inspects `HasContainerHolder` steps and nested child traversals only, so a
   `WherePredicateStep`'s range comparison passes the gate; benign today because both arms order
   by RID, but the Javadoc's "however deeply nested" claim overstates the search.
   **Condition on the narrowing, learned from BG9:** measure the narrowed shapes **under
   composition** — inside OR, inside AND, nested — not standalone. BG9 is a fresh demonstration
   that a `not()` argument sound in isolation can fail under composition, and DR-S15's argument
   has the same shape.
4. **Run step 10's dimensional review — it has had none at all.** Its four defects landed across
   three resumptions with no step-level review at any point. It is the largest single contributor
   to the track diff and the only `risk: high` step with no review of its own.

## Episodes

**Step 9's episode is written** (`d6e0920e5c`, roster `[x]`, `## Progress` entry appended). Steps
10, 11 and 13 have **no episode and must not get one yet** — an episode is written after the
review loop closes, and all three loops are open. Their `EPISODE_DRAFT` material is rich; recover
it from the review files and the decision log rather than re-deriving it.

## Phase C, and the obligation nothing has discharged

**The coverage gate has still not run on any step of this track.** Steps 5, 9, 10, 11 and 13 all
skipped it by orchestrator instruction, because it is the full `clean package -P coverage` build
and implementers on this branch have repeatedly burned hours in it. **Phase C owns it and must not
skip it.**

Phase C also inherits a deliberate deferral: `review-performance` was **not** run at step level for
steps 10, 11 or 13. That was a design choice. All three widen the decline surface, so the question
— how much translation surface did this track give up — is identical for all three and is better
answered once against the cumulative diff than three times against fragments. Between them the
track now declines: any step after a captured `SKIP` / `LIMIT` / `RETURN DISTINCT` except three
pure projections; a slice over a `dropOnAbsent` projection; any edge-bearing child of `where` /
`filter` / `and`; any range comparison inside `not()`; and any `where` child carrying a scope
binding. That is a lot of surface, and nobody has priced the sum.

## Confirmed and unowned

**BG20 (should-fix, cert C1) is the significant one.** `g.V().out().has("name", gt(27))` answers
**6 translated against 0 native** at one boundary step. The graph-step fold that reconciles
TinkerPop's ternary comparison with YouTrackDB's comparator applies only at the **root**, so every
post-hop range comparison carries the divergence with no negation involved. Pre-existing, outside
step 13's scope, and with **no Cucumber witness** — so the absolute green criterion will not catch
it, and the track can close at 1930 / 0 / 14 with this inside. Under item 4 a silent wrong answer
on a recognised shape needs a disposition and this one has none. Declining is probably unavailable:
withdrawing every post-hop range comparison would take a large fraction of recognised traversals.
DR-S15's `declaredPropertyType` accessor is the only tool that separates the safe same-type case
from the unsafe one. **Size this before Phase C** — it may be a step.

Smaller: `SubTraversalPredicateAdapter.appendPattern` carries a comment naming the removed
`commitEdgeBearingChild` (one-line sweep). Track 11 gained item 8 (`as()` labels never bind on edge
steps) and item 9 (18 hand-built MATCH AST nodes that should route through the shared
`match/builder/` package). Step 10 left a label-resolution follow-up: two spellings that agree
today are declined by BG8's fix, and both would return if the walker resolved a `where` scope label
to a pattern alias — bounded work on machinery that exists, since `bindStepLabels` already mints
the alias. Whoever takes it must read `commitPositiveFilterChild` in its post-step-11 shape first,
because that is where the two changes meet.

## Track completion

Under DR-S7 the four-arm baseline is track-completion work, taken after Phase C's fixes land.
Under DR-S8 the criterion is absolute: **0 failures and 14 skips on both runners**, plus a frozen
exclusion list for anything reproducing on `develop`. The baseline names the SHA it was taken at
and comes from an invocation that compiled in the same run.

## Branch hygiene

`ytdb-558-t9-step10`, `ytdb-558-t9-step11` and `ytdb-558-t9-step13` are merged and can be deleted:
`git push --delete origin <name>` plus `git worktree remove`. `t11-item7-jmh` is **not** merged —
it carries Track 11 item 7's JMH harness at `b1fc04a030` and stays.

## Rules any respawn runs under

Never `mvn install`, never `-am` — the shared `~/.m2` poisons pending measurements. Never the full
coverage gate outside Phase C. One Maven invocation at a time per worktree. **The `test-compile`
prefix is not optional**: `surefire:test` alone silently tests the previous compilation, which cost
this session one misleading green run. Every new or changed test gets a mutation proof — this
branch has twelve recorded instances of a test that could not fail. Step 11 added an
`assertNativeFanOut` helper aimed at that, but its own review (TS13) found the check compares two
call-site literals rather than anything derived from the fixture, so **do not copy it as-is**; the
idea is right and the implementation is still assertion-shaped.

**One methodological note worth carrying.** Four times this session a claim derived from reading
the code was overturned by measurement: the allow-list inference from BG7, the pricing of the
fail-closed option for BG2, the `not`-exemption invariant, and the belief that BG8 had no Cucumber
witness. Structural argument is good for deciding **where to measure**; it has not been reliable
here for deciding **what is true**.
