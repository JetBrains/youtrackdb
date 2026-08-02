<!-- MANIFEST
findings: 7   severity: {blocker: 2, should-fix: 4, suggestion: 1}
index:
  - {id: R1, sev: blocker,    loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:101", anchor: "### R1 ", cert: A1, basis: "run as one suite with the strategy on, the core Cucumber execution never completes and reports zero scenarios; the same command with the kill-switch off runs 1930 green in 17 s"}
  - {id: R2, sev: blocker,    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:172", anchor: "### R2 ", cert: A5, basis: "registering the four terminators exposes them to and/or/not/where sub-walks, where the specified void append seam has no channel to decline and both in-repo precedents are wrong"}
  - {id: R3, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:2064", anchor: "### R3 ", cert: X2, basis: "item 1a's named preferred fix site is on the planner path SQL MATCH shares, and the blast-radius analysis stops at GQL"}
  - {id: R4, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:63", anchor: "### R4 ", cert: A2, basis: "item 6 reads a baseline item 1 takes before item 1a moves it; measured, 25 of the 28 count-comparison Cucumber failures carry 1a's over-emission signature"}
  - {id: R5, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md:640", anchor: "### R5 ", cert: A8, basis: "third consecutive over-threshold track, now with an undiagnosed suite-level hang added; the 1/1a boundary is a clean split with no shared file"}
  - {id: R6, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:59", anchor: "### R6 ", cert: X1, basis: "a plain ./mvnw -pl core test aborts at gremlin-process-compliance-tests and never reaches the Cucumber execution the acceptance criterion is written against"}
  - {id: R7, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:84", anchor: "### R7 ", cert: A7, basis: "the JMH harness is compiled but never executed in track, and the no-fixture-graph premise does not hold for jmh-ldbc/src/test"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 3}
cert_index:
  - {id: X1, verdict: HIGH, anchor: "#### X1 "}
  - {id: X2, verdict: HIGH, anchor: "#### X2 "}
  - {id: X3, verdict: HIGH, anchor: "#### X3 "}
  - {id: X4, verdict: LOW, anchor: "#### X4 "}
  - {id: A1, verdict: CONTRADICTED, anchor: "#### A1 "}
  - {id: A2, verdict: CONTRADICTED, anchor: "#### A2 "}
  - {id: A3, verdict: CONTRADICTED, anchor: "#### A3 "}
  - {id: A4, verdict: UNVALIDATED, anchor: "#### A4 "}
  - {id: A5, verdict: CONTRADICTED, anchor: "#### A5 "}
  - {id: A7, verdict: CONTRADICTED, anchor: "#### A7 "}
  - {id: A8, verdict: VALIDATED, anchor: "#### A8 "}
  - {id: TS1, verdict: ACHIEVABLE, anchor: "#### TS1 "}
flags: [CONTRACT_OK]
-->

# Track 9 — risk review, iteration 1

The Cucumber gate is not unsized. It is a hang, and the strategy this track is validating is what causes it. Run as one suite at branch HEAD with the translator on its default, `gremlin-feature-compliance-tests` reports `Tests run: 0` and the fork dies; the identical command with `-Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false` runs 1930 scenarios green in 16.9 s. That A/B is one commit and one flag, so the attribution is not inferential. Track 10 measured the same zero three times and recorded it in the log the dispositions file cites as its own measurement log; the dispositions table has no row for that execution, so nobody read it, and this track's Pre-Flight then retired Track 10's "the runner is inert" discovery on the strength of the surefire wiring rather than the result.

The second blocker is smaller but the same family as item 1a. Registering `FoldStep` / `UnfoldStep` / `ReverseStep` / `TailGlobalStep` in `PRODUCTION_RECOGNISERS` also registers them for the `and` / `or` / `not` / `where` / `filter` sub-walk, which shares the map and which `dispatchAll`'s own javadoc says the terminator invariant does not cover. Item 2a saw the hazard and answered "declining is the safer answer", but the seam it specifies is `void appendListShapingOp(ListShapingOp)` and a void mutator cannot decline. Both in-repo precedents give the wrong answer: `setResultShaping` swallows, which turns `and(__.out().fold())` from always-true into an existence filter, and `appendPostConcatOp` throws, which crashes the query. These shapes are correct today only because `FoldStep` has no registry entry.

The rest is smaller. Item 1a's named preferred fix site is on the planner path SQL MATCH shares, and the blast-radius paragraph stops at GQL. Item 6 reads a baseline item 1 takes before item 1a moves it. The track is the third consecutive one past the review-burden threshold and now inherits an undiagnosed hang on top. Not everything I probed came back bad: the union suffix path is genuinely safeguarded by the fail-closed `POST_UNION_RECOGNISERS` gate, the terminators and their `ListShapingOp`s are ordinary unit-testable code with existing fixtures, and the per-directory run I did in the course of this review hands the track most of the Cucumber baseline it thinks it has to derive.

**Tooling caveat.** `steroid_execute_code` times out on this repository, so every symbol result below is `find` / `grep` / `unzip` plus a full read of the declaring file. The negatives that carry weight — "the sub-walk uses the same registry", "`SubTraversalPredicateAdapter` does not override `appendPostConcatOp`", "`SQLMatchStatement` is not a `MatchPatternBuilder` consumer" — were each cross-checked by reading the declaring file end to end, which bounds but does not eliminate the usual grep risk. The measurements are not symbol searches: they are recorded test runs, listed in X1.

## Findings

### R1 [blocker]
**Certificate**: A1 (CONTRADICTED), X1

**Location**: Track 9 `## Validation and Acceptance` line 101 and `## Plan of Work` item 1 (line 63); `core/pom.xml:276-287`; runs recorded in X1

**Issue**: The track's headline gate — "The full TinkerPop Cucumber suite is green with the strategy registered" — cannot be evaluated today, and the obstacle is not a failure count.

Measured at HEAD (`3148ac14e1`), same command both ways:

| Run | Result |
|---|---|
| `surefire:test@gremlin-feature-compliance-tests`, branch default (translator on) | no result; killed at 15 min and again at 7 min, zero scenarios reported |
| the same plus `-Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false` | `Tests run: 1930, Failures: 0, Errors: 0, Skipped: 14` in 16.9 s, BUILD SUCCESS |

Track 10's three full-suite runs reached the execution (they carried `-Dmaven.test.failure.ignore=true`) and each recorded `Tests run: 0` followed by `The forked VM terminated without properly saying goodbye`, at Track 10's base `f007749249` and at its tip. The feature files, the runner, and the glue are byte-identical to `develop` — no branch commit touches them — so the kill-switch A/B is the whole explanation.

Three consequences the track's current shape does not survive.

**Item 1 cannot produce what it promises.** A hang yields no failure list, so "establish the pre-terminator green baseline and size the cross-track triage bucket" has no output. The Cucumber half of the bucket stays unsized not because nobody has run it but because the run does not terminate.

**Item 6 has nothing to compare against**, for the same reason, and its acceptance clause "no previously-passing scenario regresses" is unfalsifiable while the suite does not complete.

**The Pre-Flight's third Clarification is wrong.** It retires Track 10's "`core`'s Cucumber runner is inert" discovery on the grounds that develop's `9b9dfa20fd` gave the runner its own unfiltered surefire execution. The wiring claim is right and the conclusion drawn from it is not: the runner executes zero scenarios for a different reason than the one Track 10 diagnosed. The refuting evidence was already on disk at `/tmp/track10-final-verify.log:4713` when the Clarification was written.

The good news is that the suite is measurable in pieces. Running each upstream feature directory separately with the translator **on**, every one completes in seconds (X1): 1888 upstream scenarios, 42 failures, no directory hanging on its own. Summed wall time across the seven directory runs is under 75 s including seven Maven starts. So the whole-suite non-completion is a cumulative or cross-scenario interaction, not one pathological scenario — which is the diagnosis this track has to own, because it is the thing standing between the branch and its own acceptance criterion.

**Proposed fix**: Insert an item 0 ahead of everything, and make it the ESCALATE gate.

1. Record the per-directory baseline (the seven runs in X1 reproduce it in about five minutes) as the artifact items 1 and 6 read, so the track has a real Cucumber baseline on the first day rather than a promised one.
2. Make whole-suite completion its own deliverable with a stated threshold: if the cumulative-run hang is not localized to a fixable cause within the first step, ESCALATE rather than absorbing an undiagnosed engine-level defect into a terminator track. Track 10's R1 produced exactly this shape and it is the finding that let that track close honestly.
3. Restate the acceptance criterion against the recorded baseline — "no regression against the item-0 per-directory baseline, and the suite completes in a single fork" — rather than against unqualified green. A criterion the track cannot measure is a criterion it will be argued out of at completion, which is the failure Track 10's retitle already paid for once.

### R2 [blocker]
**Certificate**: A5 (CONTRADICTED), X3

**Location**: Track 9 `## Plan of Work` item 2a (line 67) and item 4a (line 77); `GremlinStepWalker.java:143-172` (registry), `:399-411` (`subWalk`), `:305-308` (the top-level-only note); `RecognitionContext.java:286`; `SubTraversalPredicateAdapter.java:89, 397`

**Issue**: Item 4a closes the union-child hole and leaves the combinator-child hole open, and item 2a's seam cannot close it as specified.

`subWalk` builds a `SubTraversalPredicateAdapter` over **the same `recognisers` map** the top-level walk uses (`GremlinStepWalker.java:399-403`, and the adapter's own field comment at `:89`: "the same registry the top-level walker uses"). `AndStep`, `OrStep`, `NotStep`, `TraversalFilterStep`, and `WhereTraversalStep` are all registered and all drive children through it. `dispatchAll`'s javadoc states that the terminator invariant is top-level-only, so nothing in a sub-walk stops a child's trailing `fold()` from being claimed — and from the child's point of view the `fold()` *is* the last step, exactly the reasoning item 4a already applied to union children.

Item 4a does not cover this path. It gates `UnionStepRecogniser`, which forks through `walkFork`; combinator children go through `walkChild`, a different method returning a different type, with no `listShapingOps` inspection anywhere.

Item 2a saw the adapter and reached the right conclusion — "a swallowed *append* inside a combinator child means the child silently loses a list-shaper it appeared to accept, so declining is the safer answer" — but specified `void appendListShapingOp(@Nonnull ListShapingOp op)`. A void mutator has no return channel, so a recogniser calling it cannot learn that the context refused, and the two shapes already in the file both give the wrong behaviour:

- Copy `setResultShaping`'s swallow (`SubTraversalPredicateAdapter:397`) and `g.V().and(__.out().fold())` translates as `and(__.out())`. Native is always true — the track's own item 2b records that a dry upstream still emits one empty list — while the translated form is an existence filter. Rows silently disappear. Same failure class as item 1a, introduced by this track.
- Copy `appendPostConcatOp` (`RecognitionContext.java:286`), the only existing append seam and therefore the natural template, and the default throws `UnsupportedOperationException`. `SubTraversalPredicateAdapter` does not override it. The same traversal then throws out of `TraversalStrategy.apply()` instead of declining, breaking the branch's all-or-nothing contract in the loudest possible way. The throw is safe for post-concat ops only because a sub-walk can never carry a union carrier; nothing makes it safe for a list-shaper.

Today every one of these shapes is correct, because `FoldStep` has no registry entry and `dispatchAll` declines the whole traversal at the missing key. The track's own registration is what opens the path.

**Proposed fix**: Give the seam a query the recogniser reads before it mutates — `default boolean supportsListShaping() { return true; }` on `RecognitionContext`, overridden to `false` on `SubTraversalPredicateAdapter` — and have each of the four recognisers return `Outcome.DECLINE` when it is false. State in 2a that the adapter's answer is *decline*, and specifically neither swallow nor throw, with the reason for each rejection. Add the combinator decline cases to item 5's decline group and to `## Validation and Acceptance`: at minimum `g.V().and(__.out().fold())` and `g.V().where(__.out().tail(1))` must return native's multiset. Without a witness the gate is inferred from a code reading, which is the objection item 4a itself raises about op reference identity.

### R3 [should-fix]
**Certificate**: X2 (HIGH), A4 (UNVALIDATED)

**Location**: Track 9 `## Plan of Work` item 1a (line 64) and `## Interfaces and Dependencies` (line 118); `MatchExecutionPlanner.java:2064`, `:5677`, `:6012`; `SQLMatchStatement.java:191, 201`

**Issue**: Item 1a's blast-radius paragraph reaches GQL and stops. The fix site it names as preferred reaches SQL MATCH.

Three entry points construct the planner: `SQLMatchStatement:191,201` (SQL `MATCH`), `GremlinToMatchStrategy:486` (the translator), `GqlMatchStatement:88` (GQL). Only the last two use `MatchPatternBuilder` — a repository-wide grep over `core/src/main/java` returns eleven consumers and `SQLMatchStatement` is not among them. So a fix confined to `MatchPatternBuilder.mergedTargetFilter` moves Gremlin and GQL only, which is what the track's blast-radius paragraph analyses.

But item 1a then names the other option as the one to prefer: "rebinding over the `Pattern`'s edge items rather than `matchExpressions` is the option that keeps `rebindFilters`' purpose intact." `rebindFilters` is a private method of `MatchExecutionPlanner` (`:6012`) called from `:2064` and `:5677`, both on the common path all three entry points share. Changing it changes SQL `MATCH`.

`## Interfaces and Dependencies` lists `rebindFilters` in scope, so the file boundary is honest. What is missing is the consequence: `## Validation and Acceptance` has a GQL witness bullet and a `NOT IN` strip bullet and no SQL `MATCH` bullet, and the only broad regression net for SQL `MATCH` — `MatchStatementExecutionTest`, 159 `@Test` methods, which the track books at about 32 minutes — appears once, in a Clarification, as a cost the track pays rather than as a gate it clears. The two candidate sites therefore differ in blast radius by an entire query language, and the track presents the choice as being about `rebindFilters`' internal purpose.

Rollback is also unaddressed. A `MatchPatternBuilder`-side fix is revertible in one file with the GQL prettyPrint tests as the signal. A `rebindFilters`-side fix reverting after the terminators have landed on top of it is a multi-step unwind, and the track has no note on which way it would go.

**Proposed fix**: In item 1a, state the two sites' blast radii side by side — builder-side is Gremlin plus GQL, planner-side adds SQL `MATCH` — and make the choice on that basis rather than only on `rebindFilters`' purpose. If the planner-side option is chosen, add an acceptance bullet requiring a full `MatchStatementExecutionTest` pass for the step that lands it, and name the 32 minutes as gate cost rather than incidental cost. Add one sentence on rollback: which files revert, and what signal says the revert is complete.

### R4 [should-fix]
**Certificate**: A2 (CONTRADICTED)

**Location**: Track 9 `## Plan of Work` item 1 (line 63) and item 6 (line 81); `## Decision Log` third bullet (line 25)

**Issue**: Item 1 takes the Cucumber baseline, item 1a then changes the result of every translated multi-hop traversal, and item 6 compares the final run against item 1's number. The track's own Decision Log records the rule that forbids this — "Recompute the failure inventory whenever the base SHA is recomputed" — but states the trigger as a rebase, and here the invalidator is the track's own second sub-item.

Measured, the drift is not marginal. Twenty-eight of the 42 upstream Cucumber failures at HEAD report a count comparison (X1), and 25 of those 28 are over-emission — `expected:<2> but was:<3>`, `expected:<2> but was:<10>`, `expected:<1> but was:<6>` — the signature item 1a documents from the four-vertex modern graph and the one the dispositions file attributes to 1a in `AndTest` and `WhereTest`. So item 1a should move a large minority of the Cucumber baseline, in both directions: scenarios that fail today should pass, and any scenario passing today only because a filter was dropped will start failing and be read as a terminator regression by item 6's comparison.

**Proposed fix**: Re-measure immediately after item 1a lands and record that as the baseline items 2 through 6 are validated against. Keep item 1's pre-1a run for sizing the triage bucket only, and say in item 6 which of the two numbers it reads. Generalize the Decision Log bullet from "whenever the base SHA is recomputed" to "whenever anything moves the measured behaviour", naming item 1a as this track's instance.

### R5 [should-fix]
**Certificate**: A8 (VALIDATED)

**Location**: `implementation-plan.md:640` (the ESCALATE trigger) and `:662-681` (Scope); Track 9 `## Interfaces and Dependencies`

**Issue**: The sizing gate is reading the right number and the wrong scope.

Measured: Track 10's full range `f007749249..7c77a4544f` is 39 files / 5,331 insertions, of which 24 files / 2,718 insertions are code (`*.java`, `*.xml`); the remainder is `_workflow/` markdown. The plan records Track 8 at 38 files / 5,814 insertions. Both crossed the `~4,000` review-burden threshold, which `track-code-review.md:335` defines over all non-generated changed lines and marks explicitly flag-only, never a gate. Track 9's planned footprint is `~22–30` files, above the `~20-25` split-candidate bound, before the Cucumber bucket. The plan's ESCALATE instruction is therefore stricter than the workflow rule it cites, which is a deliberate local choice and worth keeping — but it should escalate on the code figure, because a track whose overage is `_workflow/` prose is not the same review burden as one whose overage is planner code.

R1 changes the answer regardless. The track now also carries an undiagnosed suite-level hang whose fix is unsized, sitting in front of a shared-planner correctness fix with cross-language blast radius (R3) and a four-recogniser feature.

The split is clean and the boundary is already drawn in the file. Item 0 (R1's diagnosis) plus item 1 and item 1a share **no file** with items 2 through 5: 1a touches `MatchPatternBuilder` / `MatchExecutionPlanner` / `GqlMatchPatternAssembler` and the equivalence suites, while the terminators touch `RecognitionContext` / `WalkerContext` / `UnionStepRecogniser` / `ListShapingOp` / `GremlinStepWalker`'s registry. The only coupling runs one way — the terminator slice wants a base where 1a has landed — which is what a stacked-diff series is for. What a split costs is one extra PR boundary and a second Cucumber run; what it buys is that the correctness fix reviewed on its own merits does not arrive in the same diff as four new recognisers.

**Proposed fix**: Split at the item 1/1a boundary into 9a (hang diagnosis, Cucumber baseline, item 1a plus its triage) and 9b (terminators, seam, union gate, tests, final Cucumber re-run, JMH harness). If the split is declined, record the ESCALATE decision and the reason in the Decision Log, and change `:640` to measure the code-only figure so the third data point calibrates the threshold against something comparable.

### R6 [should-fix]
**Certificate**: X1 (HIGH)

**Location**: Track 9 `## Context and Orientation` § Clarifications, third and fourth bullets (lines 58-59); `core/pom.xml:236-293`, `:394-450`

**Issue**: The command the track's every-gate-verified-locally clarification implies cannot reach the gate it is written against.

`core/pom.xml` binds five surefire executions to `test`, in order: `default-test`, `sequential-tests`, `gremlin-process-compliance-tests`, `gremlin-structure-compliance-tests`, `gremlin-feature-compliance-tests`. Twenty-one `gremlin-process-compliance-tests` failures survive Track 10 and are deferred to this track. Without `-Dmaven.test.failure.ignore=true`, Maven stops at that third execution: `/tmp/core-final2-track10.log:4624` is exactly that, `BUILD FAILURE` at process-compliance with the two later executions never run. Track 10's three ignore-flagged runs are the only ones on record that reached the Cucumber execution at all.

So a plain `./mvnw -pl core test` on this branch cannot evaluate the criterion at line 101 for as long as any of the 21 deferred failures stands — which is the whole of the track until item 1's triage closes them. The track names the runner, names the profile activation, and never names the flag.

Two cost multipliers ride on the same point. Each such run costs about 31 minutes at HEAD, of which `MatchStatementExecutionTest` is roughly 32 minutes of the branch's own accounting for its slice; and with no CI signal (PR #1038 draft, `[run-ci]` reverted with Track 10's withdrawn item 4) there is no second opinion on any of it. Running the Cucumber execution alone through `surefire:test@gremlin-feature-compliance-tests` costs 20 seconds and is what X1 used throughout.

**Proposed fix**: Pin the verification commands in `## Validation and Acceptance` rather than leaving them implied: the full-suite command with `-Dmaven.test.failure.ignore=true`, and the targeted `./mvnw -pl core -o surefire:test@gremlin-feature-compliance-tests -Dmaven.main.skip=true` for the Cucumber gate's own iteration loop, with `-Dcucumber.features=` for per-directory runs. State that a bare `./mvnw -pl core test` does not reach the Cucumber execution while any deferred process-compliance failure stands, so a future reader does not mistake an early abort for a pass.

### R7 [suggestion]
**Certificate**: A7 (CONTRADICTED), TS1

**Location**: Track 9 `## Plan of Work` item 7a (line 84) and `## Validation and Acceptance` line 102; `jmh-ldbc/pom.xml:148-155`; `jmh-ldbc/src/test/java/.../LdbcQueryCorrectnessTest.java:28-41`

**Issue**: 7a's premise is right about the benchmark `@State` and wrong about the module, and the deliverable it defines is never executed.

`LdbcBenchmarkState` does require the LDBC dataset, so the benchmark half of the claim holds. But "the module has no self-contained fixture graph" is not true of `jmh-ldbc/src/test`: `LdbcQueryCorrectnessTest` "Builds a small, deterministic in-memory social network graph" and asserts all 20 LDBC read queries against it. Those tests run in an ordinary build — the `<skip>true</skip>` at `jmh-ldbc/pom.xml:155` belongs to the deploy plugin, not surefire, and `jmh-ldbc` is a root module.

That matters because of what 7a settles for. In-track the harness "lands and compiles" with a JUnit installation check; the numbers go to Hetzner, out of track, with no owner. A harness that has never run end to end is a harness whose first execution is on a rented machine after the track closes — the same shape as the `assert` that T9 rejected for being disabled in measured forks, one level up.

**Proposed fix**: Point the in-track JUnit check at `LdbcQueryCorrectnessTest`'s fixture pattern and have it drive one recognised shape through the harness's own entry point with the kill-switch on and again with it off, asserting the boundary step is installed in the first and absent in the second. That exercises the harness rather than only compiling it, costs one fixture the module already knows how to build, and leaves the Hetzner scope for the numbers alone. Correct 7a's "no self-contained fixture graph" to scope the claim to `LdbcBenchmarkState`.

## Evidence base

#### X1 Exposure: the `core` Cucumber gate — surefire chain and measured behaviour
- **Track claim**: `## Validation and Acceptance` line 101 — "The full TinkerPop Cucumber suite is green with the strategy registered — no previously-passing scenario regresses"; Clarification line 58 — "The core Cucumber runner executes under `./mvnw -pl core test`."
- **Critical path trace**:
  1. `core/pom.xml:236-293` — profile `gremlin-compliance-suites`, activated on `!test`, holds three executions bound to phase `test`, the last being `gremlin-feature-compliance-tests` over `**/YTDBGraphFeatureTest.java` with `failIfNoTests=true` and no group filter.
  2. Executions run in declaration order after `default-test` (`:394`) and `sequential-tests` (`:419`). A test failure in any of them stops the build unless `-Dmaven.test.failure.ignore=true`.
  3. `YTDBGraphFeatureTest` is `@RunWith(Cucumber.class)` over two feature roots — the upstream `gremlin-test` jar tree and `core/src/test/resources/.../gremlintest/features` — with eleven `not @…` tag exclusions.
- **Measurements** (all at HEAD `3148ac14e1`, main checkout, `-o`):

  | # | Command delta | Result | Log |
  |---|---|---|---|
  | 1 | default (translator on) | killed at 15 min, zero scenarios | `/tmp/t9risk-cucumber-head.log` |
  | 2 | default (translator on) | killed at 7 min, zero scenarios | `/tmp/t9risk-cucumber-on2.log` |
  | 3 | `+ -DargLine=…toMatchTranslator.enabled=false` | 1930 / 0F / 0E / 14S in 15.91 s | `/tmp/t9risk-cucumber-off.log` |
  | 4 | `+ -Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false` (plain `-D`, original argLine intact) | 1930 / 0F / 0E / 14S in 16.91 s, BUILD SUCCESS | `/tmp/t9risk-cucumber-off2.log` |
  | 5 | `+ -Dcucumber.features=…/gremlintest/features` (local only, translator on) | 42 / 0F in 5.40 s | `/tmp/t9risk-cuc-localonly-on.log` |
  | 6 | seven runs, one per upstream directory, translator on | see below, all complete | `/tmp/t9risk-bisect.txt` |

  Run 4 is the controlled one: identical command to runs 1-2 plus one property, so no argLine confound.

  Run 6, per upstream feature directory: `branch` 134/0F, `data` 98/0F, `filter` 369/10F, `integrated` 175/7F, `map` 811/22F/6S, `semantics` 97/0F, `sideEffect` 204/3F/8S. Total 1888 scenarios, 42 failures; 1888 + 42 local = 1930, matching run 4 exactly. Summed Maven wall time 74.9 s across seven starts. Failure signatures are dominated by over-emission: 28 of the 42 report a count comparison and 25 of those are `expected:<N> but was:<M>` with `M > N`, including `expected:<2> but was:<10>` and `expected:<1> but was:<6>`, the same shapes the dispositions file records for `WhereTest` and `AndTest`. Per directory the count comparisons split 9 over / 1 under (`filter`), 7 over / 0 under (`integrated`), 9 over / 2 under (`map`); `sideEffect`'s three failures report no count.
- **Prior-run corroboration**: Track 10's three full-suite runs reached the execution and each recorded `Tests run: 0` plus a fork that terminated without saying goodbye — `/tmp/track10-base-control.log:33870` (base `f007749249`, in the `track10-base` worktree), `/tmp/track10-core-reproduce.log:4772`, `/tmp/track10-final-verify.log:4713` (tip). `grep -c OutOfMemoryError` over all three returns 0, so the mechanism is not a reported heap exhaustion.
- **Blast radius**: the track's headline acceptance criterion, item 1's baseline, item 1's triage bucket, and item 6's regression comparison all rest on this one execution.
- **Existing safeguards**: none in this direction. `failIfNoTests=true` (`core/pom.xml:285`) is the guard that makes the zero visible at all, and it fired correctly three times; nothing read it.
- **Residual risk**: HIGH — produces R1 and R6.

#### X2 Exposure: item 1a's fix site on the shared `MatchExecutionPlanner` path
- **Track claim**: item 1a — the fix lands in `MatchPatternBuilder`'s positive-path-item construction or on `rebindFilters`; "rebinding over the `Pattern`'s edge items rather than `matchExpressions` is the option that keeps `rebindFilters`' purpose intact." Blast radius stated as reaching GQL.
- **Critical path trace**:
  1. `grep -rn "new MatchExecutionPlanner(" --include=*.java core/src/main/java` returns three entries: `SQLMatchStatement.java:191` and `:201`, `GremlinToMatchStrategy.java:486`, `GqlMatchStatement.java:88`.
  2. `grep -rln "MatchPatternBuilder" --include=*.java core/src/main/java` returns eleven files: the builder, `MatchEdgePathItems`, eight translator-package files, `GqlMatchPatternAssembler`. `SQLMatchStatement` is absent — SQL `MATCH` builds its pattern from the parsed AST, not the shared builder.
  3. `rebindFilters` is declared `private` at `MatchExecutionPlanner.java:6012` and called at `:2064` (post-optimization, carrying the `detectNotInAntiJoin` comment the technical review cites) and `:5677` (after `promoteStaticRidsFromFilters`). Neither call site is gated on the entry point, so both run for SQL, GQL, and the translator.
  4. `SQLMatchStatement` carries its own separate `rebindFilters` at `:232`, called at `:226` — a distinct method, not the one item 1a would edit.
- **Blast radius**: builder-side fix → Gremlin + GQL (`GqlMatchPatternAssembler`, the Track 1 prettyPrint regression tests). Planner-side fix → all three, adding every SQL `MATCH` query in the product.
- **Existing safeguards**: `MatchStatementExecutionTest`, 159 `@Test` methods, ~215 KB — the broadest SQL `MATCH` net in the tree, and the track books it as a cost rather than a gate. The GQL side is covered by Track 1's prettyPrint regression tests, which the track does list.
- **Residual risk**: HIGH for the planner-side option, MEDIUM for the builder-side one — produces R3.

#### X3 Exposure: registering four terminators into the shared recogniser registry
- **Track claim**: item 3 and `## Interfaces and Dependencies` — the four recognisers are added to `GremlinStepWalker`'s registry; item 4 relaxes `POST_UNION_RECOGNISERS`; item 4a gates union children; item 2a decides `SubTraversalPredicateAdapter`.
- **Critical path trace**:
  1. `GremlinStepWalker.PRODUCTION_RECOGNISERS` (`:143-172`) is one `Map<Class<?>, StepRecogniser>` keyed on concrete runtime class. It already registers `AndStep`, `OrStep`, `NotStep`, `TraversalFilterStep`, `WhereTraversalStep`, `WherePredicateStep`.
  2. `dispatchAll` (`:310-342`) looks the head step's exact class up in whatever map it is handed and declines the whole walk on a miss.
  3. `GremlinStepWalker.subWalk` (`:399-403`) constructs `new SubTraversalPredicateAdapter(parent, recognisers)` — the same map — and the adapter's field comment at `:89` says so. `dispatchAll`'s javadoc (`:305-308`) states that the reserved-prefix scan, the flag resolution, **the terminator invariant**, and `buildResult` are top-level-only.
  4. `ConnectiveStepSupport:41`, `TraversalFilterStepRecogniser:75`, and `NotStepRecogniser:63` each call `ctx.walkChild(child)`. `UnionStepRecogniser` uses a different path, `host.walkFork`, which is the one item 4a gates.
- **Blast radius**: every `and` / `or` / `not` / `where` / `filter` sub-traversal ending in one of the four terminators. These translate correctly today because the registry has no entry for their step classes.
- **Existing safeguards**: the missing registry key, which this track removes. Nothing else — the top-level terminator invariant does not run in a sub-walk, and 4a inspects only union children.
- **Residual risk**: HIGH — produces R2.

#### X4 Exposure: relaxing the `POST_UNION_RECOGNISERS` allow-list
- **Track claim**: item 4 — admit the four terminator recognisers to the post-union suffix allow-list (DR-U4).
- **Critical path trace**: `GremlinStepWalker.java:193-197` declares the `private static final Set<StepRecogniser>`; `dispatchAll:322` is the fail-closed per-step gate and `postUnionSuffixTranslatable:380` the look-ahead. The field's javadoc states the invariant the gate protects: `buildResult`'s multi-plan branch "reads only the boundary metadata, the shaping, and the post-concat ops", so a recogniser that writes anywhere else has its contribution silently discarded after a union.
- **Blast radius**: bounded by that invariant. The four terminators write only into `ResultShaping.listShapingOps()`, which is inside the set `buildResult` reads, so admitting them is consistent with the gate's stated rationale.
- **Existing safeguards**: the gate is fail-closed by construction (a recogniser not in the set is declined), both readers are in the same private field's file, and the javadoc records the invariant an implementer must preserve.
- **Residual risk**: LOW — no finding. Recorded because the opposite result would have been a blocker for item 4, and because the invariant is the thing R2's fix must not disturb: a `supportsListShaping()` query is a read, so it adds nothing to the write set the gate polices.

#### A1 Assumption: the Cucumber suite is green with the strategy registered, modulo an unsized bucket
- **Track claim**: line 101 and item 1 — the suite is green, or is a set of enumerable failures to be triaged.
- **Evidence search**: six recorded test runs at HEAD plus three of Track 10's, listed in X1. Tool: Maven/surefire, not a symbol search.
- **Code evidence**: `/tmp/t9risk-cucumber-off2.log` (1930 green, 16.9 s, translator off) against `/tmp/t9risk-cucumber-on2.log` (no result at 7 min, translator on), same command otherwise; `/tmp/track10-final-verify.log:4713` and `:4734` (`Tests run: 0` at Track 10's tip).
- **Verdict**: CONTRADICTED
- **Detail**: the suite does not fail, it does not finish. `git log develop..HEAD` over the feature directory, `YTDBGraphFeatureTest.java`, and `GraphFeatureWorld.java` returns no commits, so the runner and its inputs are develop's; the branch's contribution is the strategy. Produces R1.

#### A2 Assumption: item 1's run derives the first Cucumber baseline and item 6 can read it
- **Track claim**: item 6 — "item 1's run derives the first Cucumber baseline this branch has; there is nothing to read for that half."
- **Evidence search**: the seven per-directory runs in X1; failure-signature extraction by `grep -E "^\[ERROR\]   expected"` over each directory log.
- **Code evidence**: `/tmp/t9risk-bisect.txt`; `/tmp/t9risk-bisect-filter.log`, `-map.log`, `-integrated.log`, `-sideEffect.log`.
- **Verdict**: CONTRADICTED, in both directions
- **Detail**: the baseline is derivable now and cheaply — 1888 upstream scenarios, 42 failures, five minutes — so item 1 has less to discover than it thinks. And it is not stable across the track: 33 of the 42 carry item 1a's over-emission signature, so the number item 6 compares against will have moved by the time item 6 runs. Produces R4, and the measured directory table is R1's proposed item-0 artifact.

#### A3 Assumption: Track 10's "`core`'s Cucumber runner is inert" discovery no longer holds
- **Track claim**: `## Context and Orientation` § Clarifications, third bullet — "Read at HEAD instead: develop's `9b9dfa20fd` gives `YTDBGraphFeatureTest` its own `gremlin-feature-compliance-tests` surefire execution … The runner list above is correct as written."
- **Evidence search**: read of `core/pom.xml:196-292`; `grep -n "gremlin-feature-compliance-tests"` over the three Track 10 logs.
- **Code evidence**: `core/pom.xml:276-287` (the execution exists, unfiltered, `failIfNoTests=true`); `/tmp/track10-final-verify.log:4713-4734` (`Tests run: 0`).
- **Verdict**: CONTRADICTED on the conclusion, VALIDATED on the wiring
- **Detail**: the Clarification corrects Track 10's *mechanism* — the group filter is no longer the reason — and then infers that the runner executes, which the same session's own log refutes. Track 10's finding was right in effect and wrong in cause, and the Pre-Flight kept the cause and discarded the effect. Feeds R1.

#### A4 Assumption: item 1a's blast radius is Gremlin plus GQL
- **Track claim**: item 1a — "**The blast radius reaches GQL.** … any fix inside `MatchPatternBuilder` moves GQL's IR with it."
- **Evidence search**: `grep -rn "new MatchExecutionPlanner(" --include=*.java core/src/main/java`; `grep -rln "MatchPatternBuilder" --include=*.java core/src/main/java`; `grep -rn "rebindFilters" --include=*.java core/src`; reads of `MatchExecutionPlanner:2050-2070` and `:5665-5680`.
- **Code evidence**: `SQLMatchStatement.java:191, 201`; `MatchExecutionPlanner.java:2064, 5677, 6012`.
- **Verdict**: UNVALIDATED — true of the builder-side option, false of the planner-side option the item names as preferred
- **Detail**: see X2. Produces R3.

#### A5 Assumption: `SubTraversalPredicateAdapter` can decline a list-shaping append
- **Track claim**: item 2a — "Decide `SubTraversalPredicateAdapter` explicitly … a swallowed *append* inside a combinator child means the child silently loses a list-shaper it appeared to accept, so declining is the safer answer", with the seam specified as `void appendListShapingOp(@Nonnull ListShapingOp op)`.
- **Evidence search**: full read of `RecognitionContext`'s mutator block (`:250-295`); `grep -rn "appendPostConcatOp\|postConcatOps()" --include=*.java core/src/main/java`; read of `SubTraversalPredicateAdapter`'s override block (`:385-405`) and class javadoc; read of `GremlinStepWalker.subWalk`.
- **Code evidence**: `RecognitionContext.java:286-288` — `default void appendPostConcatOp(@Nonnull PostConcatOp op) { throw new UnsupportedOperationException("post-concat ops are top-level only"); }`; `SubTraversalPredicateAdapter.java:397` — `setResultShaping` is a documented no-op; the adapter overrides `setLimit`, `setSkip`, `setResultShaping`, `setLastPropertyProjection` and does **not** override `appendPostConcatOp`.
- **Verdict**: CONTRADICTED
- **Detail**: a `void` mutator has no decline channel, and the two behaviours the file already implements for adjacent seams are swallow (silent wrong answer) and throw (crash). The throw is safe for `appendPostConcatOp` only because `hasUnionCarrier()` is false in every sub-walk, so no recogniser reaches it; that argument does not transfer to a terminator the sub-walk dispatches directly. Produces R2. Reference-accuracy caveat: the "does not override" negative rests on a full read of the adapter's override block rather than on PSI.

#### A7 Assumption: `jmh-ldbc` has no self-contained fixture graph
- **Track claim**: item 7a and acceptance line 102 — "`jmh-ldbc` has no self-contained fixture graph, so no baseline is capturable locally."
- **Evidence search**: `grep -n "skipTests\|surefire\|<skip>\|<profile>\|<id>" jmh-ldbc/pom.xml`; read of `jmh-ldbc/pom.xml:138-165`; read of `LdbcQueryCorrectnessTest`'s header; `grep -n "<module>" pom.xml`.
- **Code evidence**: `LdbcQueryCorrectnessTest.java:28-41` — "Builds a small, deterministic in-memory social network graph and verifies that each query returns the expected results", with the topology in the javadoc; `jmh-ldbc/pom.xml:148-155` — the `<skip>true</skip>` belongs to `maven-deploy-plugin`; `pom.xml:54` — `jmh-ldbc` is a root module, so its tests run in an ordinary build.
- **Verdict**: CONTRADICTED as stated, VALIDATED when scoped to `LdbcBenchmarkState`
- **Detail**: the benchmark `@State` does need the dataset; the module's test tree does not. Produces R7 at suggestion severity — the split deliverable stands, only its premise and the in-track check need adjusting.

#### A8 Assumption: Track 9 is the third consecutive over-threshold track
- **Track claim**: `implementation-plan.md:640` — Track 9 at ~22–30 files could be the third track past the review-burden threshold; ESCALATE if decomposition confirms it.
- **Evidence search**: `git diff --shortstat f007749249..7c77a4544f`, then the same restricted to `*.java` and `*.xml`; read of `.claude/workflow/track-code-review.md:325-350` and `step-implementation.md:849-875`.
- **Code evidence**: Track 10 = 39 files / 5,331 insertions total, 24 files / 2,718 insertions of code. Track 8 = 38 files / 5,814 insertions (plan record, not re-measured). Threshold defined at `track-code-review.md:335`, over all non-generated changed lines, flag-only.
- **Verdict**: VALIDATED
- **Detail**: the trend is real on the workflow rule's own scope. The nuance worth recording is that roughly half of Track 10's overage is `_workflow/` markdown, and the rule itself says to read a markdown-dominated count as an order-of-magnitude signal. Produces R5.

#### TS1 Testability: the four terminators, their `ListShapingOp`s, and the seam
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: low for the recognisers and the ops. Each recogniser is a pure function of a step plus a context and follows an established shape (`RangeGlobalStepRecogniser` is the direct template, including the two-keys-one-recogniser placeholder registration and the Contract-interface cast). Each `ListShapingOp` is a `@FunctionalInterface Iterator<Object> apply(Iterator<Object>)`, testable in isolation without a graph. The re-arm and clone properties item 5 adds after T7 are the hard part, and they have an in-tree idiom.
- **Existing test infrastructure**: `BoundaryStepTestSupport` (drives a boundary step) and `ReplayablePlanFixture` (builds the real `SelectExecutionPlan` under one), both established by Track 10; `MultiPlanMatchStepTest`'s clone-isolation idiom, which item 5 already names.
- **Feasibility**: ACHIEVABLE
- **Detail**: no finding. The exceptions are elsewhere and are covered above: the Cucumber gate (R1) is not testable as written, the combinator-child declines (R2) have no test in the current item 5, item 1a's SQL `MATCH` regression net (R3) is a 32-minute class the track does not gate on, and the JMH harness (R7) is compiled rather than run.
