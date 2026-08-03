<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Handoff — Track 9 Phase B, 2026-08-04T01:20Z [ctx=warning]

Paused at the context warning with **five agents in flight and four unmerged branches**.
Branch `gremlin-to-match-translator-design`, HEAD `d3ce3b98bd`, everything on `origin`
except the four worktree branches, working tree clean. Read this, then `plan/track-9.md`
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
branch, and anything unfinished stays as uncommitted changes in its worktree.

| Agent | What it is doing | Where its output goes |
|---|---|---|
| step-10 implementer | BG8: drop `WhereStartStep` / `WhereEndStep` from `TRANSPARENT_STEPS`, decline any child carrying one | commits on `ytdb-558-t9-step10` |
| step-11 `review-bugs` | step-level dimensional review | `plan/track-9/reviews/bugs-step11-iter1.md`, prefix from BG9 |
| step-11 `review-test-structure` | same | `test-structure-step11-iter1.md`, prefix from TS10 |
| step-13 `review-bugs` | same | `bugs-step13-iter1.md`, prefix from BG20 |
| step-13 `review-test-structure` | same | `test-structure-step13-iter1.md`, prefix from TS30 |

The prefixes are deliberately disjoint so two concurrent reviewers cannot collide on IDs.

## The four branches, and the order to integrate them

All four are **unpushed** and based on `d6e0920e5c` unless noted.

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

**The unfolded-`has` cross-type divergence is the significant one.** The graph-step fold that
reconciles TinkerPop's ternary comparison with YouTrackDB's comparator applies **only at the
root**, so `g.V().out().has("name", gt(27))` should carry the identical divergence with no
negation at all. Unmeasured, and it has no Cucumber witness, so only a directed measurement
will find it. If it reproduces, the surface is far wider than `not()` and step 13's decline
shape will not do — declining every post-hop range comparison would withdraw a large fraction
of recognised traversals. Step 13's own note is that schema-typed properties are safe and
untyped ones are not, which points at DR-S15's accessor as the tool.

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
could not fail, and step 11's `assertNativeFanOut` helper is the first mechanical defence
against recurrence, so copy that pattern rather than relying on assertions alone.
