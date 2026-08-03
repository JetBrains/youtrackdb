<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-04T01:20Z [ctx=warning]

Paused at the context warning with **five agents in flight and four unmerged branches**.
Branch `gremlin-to-match-translator-design`, everything pushed to `origin` including the
four worktree branches; working tree clean. Read this, then `plan/track-9.md`
(`## Decision Log` DR-S9 through DR-S15, `## Surprises & Discoveries`), then collect the
in-flight agents.

**Track 9's headline: the Cucumber residue is one scenario away from zero.** Step 11 alone
takes 1930 / 5 / 14 to **1930 / 1 / 14**; step 13 alone takes it to **1930 / 4 / 14**. They
fix disjoint witness sets, so the merged tree should reach **1930 / 0 / 14**, which is
DR-S8's absolute criterion. That number is an inference from two separate branches and is
**not yet measured on the merged tree** — measuring it is the first thing worth doing after
integration.

## Do this first

**Collect the five in-flight agents before starting anything new.** Each reviewer writes its
own findings file as it finishes, so **check `plan/track-9/reviews/` rather than assuming all
five are still pending** — `test-structure-step13-iter1.md` had already landed when this
handoff was written (7 findings, 0 blockers: TS30–TS32 should-fix, TS33–TS36 suggestions).
Step 10's implementer is the only one whose work is not self-persisting: it commits to its
branch, and anything unfinished stays as uncommitted changes in its worktree. **All four
reviews have now landed**: step 13 test-structure (7 findings, 0 blockers), step 11 bugs
(5 findings, **1 blocker BG9**), step 11 test-structure (6 findings, 0 blockers: TS10–TS12
should-fix, TS13–TS15 suggestions), step 13 bugs. Only step 10 is still running.

| Agent | What it is doing | Where its output goes |
|---|---|---|
| step-10 implementer | BG8: drop `WhereStartStep` / `WhereEndStep` from `TRANSPARENT_STEPS`, decline any child carrying one | commits on `ytdb-558-t9-step10` |
| step-11 `review-bugs` | step-level dimensional review | `plan/track-9/reviews/bugs-step11-iter1.md`, prefix from BG9 |
| step-11 `review-test-structure` | same | `test-structure-step11-iter1.md`, prefix from TS10 |
| step-13 `review-bugs` | same | `bugs-step13-iter1.md`, prefix from BG20 |
| step-13 `review-test-structure` | same | `test-structure-step13-iter1.md`, prefix from TS30 |

The prefixes are deliberately disjoint so two concurrent reviewers cannot collide on IDs.

**Step 11's bugs review has since landed and it carries a blocker — start there.** `BG9` (CONFIRMED, cert C1) refutes the `not`-exemption invariant this session recorded: `g.V().or(not(out(a)).has(name, x), has(age, 30))` returns `[x]` against native's `[x, y]` at one boundary step, because an edge-bearing `not()` inside an OR arm leaks its anti-join past the capture boundary. `BG11` is a second confirmed wrong answer — a path-scoped `where` mis-keyed onto the current boundary, `g.V().as(a).out(knows).where(as(a).has(age, 30))` returning `[]` against `[Bob]` — and `BG10` reports that the regression guard two test Javadocs claim does not exist, since `RecognitionContext.appendPattern` now has zero production callers. Step 11 is **not** done; the track-file entry recording its invariant has been corrected in the same commit as this note.

## The four branches, and the order to integrate them

All three are **pushed to `origin`** (as is `t11-item7-jmh`, carrying Track 11 item 7's JMH
harness at `b1fc04a030`) and based on `d6e0920e5c` unless noted. They are temporary
integration branches: delete each with `git push --delete origin <name>` once merged.

| Branch | Commits | State |
|---|---|---|
| `ytdb-558-t9-step10` | `2da1333841`, `59049882de` + BG8 work uncommitted | rebased onto `d6e0920e5c`; BG8 in progress |
| `ytdb-558-t9-step11` | `7c8f694cde` | complete, review in flight |
| `ytdb-558-t9-step13` | `b1c8fd9fed` | complete, review in flight, **narrowing owed — see DR-S15** |

**One known collision.** Step 10 has `WhereTraversalStepRecogniserTest.java` modified, and
step 11's commit also changed it. This is the orchestrator's omission, not an agent's — the
claimed-file list sent to step 10 predated step 11's return. Step 10 has been told to keep
its additions to that file append-only and to leave the production
`WhereTraversalStepRecogniser.java` alone. Expect a textual merge in that one test class.
Integrate **step 11 before step 10** so the semantic rewrites land first and step 10's
additions apply on top.

Steps 11 and 13 are mutually clean: step 11 never touched `NotStepRecogniser` or
`GremlinPredicateAdapter`, and step 13's `NotStepRecogniser` change is three additive things
(a Javadoc paragraph, a four-line gate, one local extraction) with no existing line's
behaviour changed.

## Pending decisions already taken — do not re-litigate

- **DR-S15, owed work:** step 13's decline is broader than necessary. Fold the narrowing
  into **one** respawn together with its review findings rather than issuing it separately;
  the reviews were already running against its committed diff when the decision landed.
  The narrowing adds a `declaredPropertyType(className, key)` accessor beside the existing
  `isDeclaredStringProperty`, which already performs the whole lookup and narrows only at
  its last line.
- **DR-S14:** BG8 stays in step 10.
- **DR-S12:** step 9's BG2 is closed fail-closed; do not reopen the successor question.
- **DR-S10 / DR-S13:** `returnDistinct` and BG7 are contained inside step 10, both measured.

## Phase C, and the one obligation nothing has discharged

**The coverage gate has still not run on any step of this track.** Steps 5, 9, 10, 11 and 13
all skipped it by orchestrator instruction, because it is the full `clean package -P coverage`
build and implementers on this branch have repeatedly burned hours in it. **Phase C owns it
and must not skip it.**

Phase C also inherits a deliberate deferral: `review-performance` was **not** run at
step level for steps 10, 11 or 13. That was a design choice, not an omission. All three widen
the decline surface, so the question — how much translation surface did this track give up —
is identical for all three and is better answered once against the cumulative diff than three
times against fragments. Phase C should treat it as a first-class question, not a formality.

## Track completion

Under DR-S7 the four-arm baseline is **track-completion work**, taken after Phase C's fixes
land, and under DR-S8 the criterion is absolute: **0 failures and 14 skips on both runners**,
plus a frozen exclusion list for anything reproducing on `develop`.

**Measurement hygiene, non-negotiable.** `surefire:test@gremlin-feature-compliance-tests`
does not clean `core/target/surefire-reports`, so a glob over `TEST-*.xml` reads stale
unit-test reports and invents failures. The suite writes **one** file, `TEST-_.xml`, holding
all 1930 cases. Scope the extraction to it or `rm -rf core/target/surefire-reports` first,
and say which in the artifact.

## Episodes owed

Steps 9, 10, 11 and 13 all have complete work and **no episode written**. Write them after
integration, so each header carries its post-rebase SHA. Step 9's is fully determined: four
iterations, two dimensions, BG2 VERIFIED at iteration 5, BG8 routed out.

## Open leads with no owner

**The unfolded-`has` cross-type divergence is no longer a lead — it is CONFIRMED and unowned.**
Step 13's bugs review measured it as **BG20** (should-fix, cert C1, `HasStepRecogniser.java:150`):
`g.V().out().has("name", gt(27))` answers **6 translated against 0 native** at one boundary step.
The graph-step fold that reconciles TinkerPop's ternary comparison with YouTrackDB's comparator
applies only at the **root**, so every post-hop range comparison carries the divergence with no
negation involved. It is pre-existing, outside step 13's scope, and has **no Cucumber witness**, so
the absolute green criterion will not catch it. Under the track's item-4 rule a silent wrong answer
on a recognised shape needs a disposition — fix, decline, or a recorded user waiver — and this one
has none. **Declining is probably not available here:** withdrawing every post-hop range comparison
would take a large fraction of recognised traversals, which is why DR-S15's `declaredPropertyType`
accessor matters beyond step 13 — it is the only tool that separates the safe same-type case from
the unsafe cross-type one. Size this before Phase C, because it may be a step rather than a fix.

**Two further step-13 findings, both confirmed.** **BG22** (suggestion, cert C4) independently
confirms DR-S15's premise by measurement: the gate is type-unaware and withdraws negated range
comparisons whose arms already agree, with the same-type control `g.V().out().has(age, gt(30))`
agreeing 1/1. **BG21** (suggestion, cert C2) is a real completeness gap in step 13's detector — it
inspects `HasContainerHolder` steps and nested child traversals only, so a `WherePredicateStep`'s
range comparison passes the gate. Benign today because both arms order by RID, but the Javadoc's
"however deeply nested" claim overstates the search and will mislead the next reader.

Two smaller ones: `SubTraversalPredicateAdapter.appendPattern` carries a comment naming the
removed `commitEdgeBearingChild` (step 10's file, one-line sweep after integration); and
Track 11 gained items 8 (`as()` labels never bind on edge steps) and 9 (18 hand-built MATCH
AST nodes that should route through the shared `match/builder/` package).

## Rules any respawn runs under

Never `mvn install`, never `-am` — the shared `~/.m2` poisons pending measurements. Never the
full coverage gate outside Phase C. One Maven invocation at a time per worktree. Iterate with
`./mvnw -pl core -o test-compile surefire:test@gremlin-feature-compliance-tests
-Dmaven.test.failure.ignore=true`; the `test-compile` prefix is not optional. Every new or
changed test gets a mutation proof — this branch has twelve recorded instances of a test that
could not fail. Step 11 added an `assertNativeFanOut` helper aimed at that, but its own
test-structure review (TS13) found the fan-out check compares two call-site literals rather
than anything derived from the fixture — so **do not copy it as-is**; the idea is right and
the implementation is still assertion-shaped. Strengthening it into a real structural guard
would be worth more than any single finding it currently carries.
