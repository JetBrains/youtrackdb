<!-- MANIFEST
findings: 9   severity: {blocker: 2, should-fix: 5, suggestion: 2}
index:
  - {id: R1, sev: blocker,    loc: "plan/track-11.md:69,87", anchor: "### R1 ", cert: C14, basis: "item 6's no-regression gate has no both-directions rule, no fix-vs-defer bound, no ESCALATE valve, and no successor track to inherit a deferral"}
  - {id: R2, sev: blocker,    loc: "plan/track-11.md:33,65,67", anchor: "### R2 ", cert: C1, basis: "row-level suffix steps are applied before payload-level list-shaping ops regardless of declared order; the last-step rule that is the only guard is stated three inconsistent ways"}
  - {id: R3, sev: should-fix, loc: "plan/track-11.md:68,69,70", anchor: "### R3 ", cert: C5, basis: "translate/decline was observed fork-position-dependent in Track 9 step 1; items 5, 6 and 7 all assert it is a function of the traversal"}
  - {id: R4, sev: should-fix, loc: "plan/track-11.md:26,69", anchor: "### R4 ", cert: C4, basis: "Track 9's R8 reciprocal ancestor check is absent from this track file, and item 6 pins the item-4 artifact rather than Track 9's final HEAD"}
  - {id: R5, sev: should-fix, loc: "plan/track-11.md:70; jmh-ldbc/pom.xml:20-23", anchor: "### R5 ", cert: C9, basis: "jmh-ldbc resolves youtrackdb-core from the local repository under -pl; the installed-step assertion then passes vacuously against a stale jar"}
  - {id: R6, sev: should-fix, loc: "plan/track-11.md:70", anchor: "### R6 ", cert: C10, basis: "item 7 measures a live translator-on slowdown on g.V(rid) and names no destination for it; no track follows"}
  - {id: R7, sev: should-fix, loc: "plan/track-11.md:68,85", anchor: "### R7 ", cert: C13, basis: "clone-isolation coverage is specified for fold only; tail is the other buffered op and shares the by-reference shaping copy"}
  - {id: R8, sev: suggestion, loc: "jmh-ldbc/pom.xml:184-186", anchor: "### R8 ", cert: C12, basis: "the JaCoCo benchmark exclusion is a single-level glob; a subpackage placement puts untestable JMH bodies into the coverage gate"}
  - {id: R9, sev: suggestion, loc: "implementation-plan.md:710-718", anchor: "### R9 ", cert: C14, basis: "last track on the branch, so the Phase C review-burden threshold has no split target if the code plus _workflow sum trips it"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 9}
cert_index:
  - {id: C1, verdict: HIGH,         anchor: "#### C1 "}
  - {id: C2, verdict: MEDIUM,       anchor: "#### C2 "}
  - {id: C3, verdict: LOW,          anchor: "#### C3 "}
  - {id: C4, verdict: UNVALIDATED,  anchor: "#### C4 "}
  - {id: C5, verdict: CONTRADICTED, anchor: "#### C5 "}
  - {id: C6, verdict: VALIDATED,    anchor: "#### C6 "}
  - {id: C7, verdict: VALIDATED,    anchor: "#### C7 "}
  - {id: C8, verdict: VALIDATED,    anchor: "#### C8 "}
  - {id: C9, verdict: VALIDATED,    anchor: "#### C9 "}
  - {id: C10, verdict: VALIDATED,   anchor: "#### C10 "}
  - {id: C11, verdict: ACHIEVABLE,  anchor: "#### C11 "}
  - {id: C12, verdict: ACHIEVABLE,  anchor: "#### C12 "}
  - {id: C13, verdict: DIFFICULT,   anchor: "#### C13 "}
  - {id: C14, verdict: DIFFICULT,   anchor: "#### C14 "}
flags: [CONTRACT_OK]
-->

# Track 11 risk review — iteration 1

Two blockers, five should-fix, two suggestions. The blockers are both about the last track having no successor: item 6's headline gate has no rule for what to do with what it finds, and the composition rule that keeps the four terminators from mistranslating is stated three incompatible ways in one file while item 4 simultaneously removes the walker gate that was covering for it.

**Reference-accuracy caveat, applies to every finding below.** mcp-steroid is reachable and `steroid_list_projects` reports the IDE open on `/home/sandra-adamiec/IdeaProjects/youtrackdb`, matching this working tree. `steroid_execute_code` timed out on a single `ReferencesSearch` over `ResultShaping.withListShapingOps` (cold kotlinc exceeds the MCP call limit), consistent with the same failure recorded in this track file's `## Clarifications` and in Track 9's three Phase A panels. Every symbol result below is grep plus an end-to-end read of each returned site. Declaration-level reads and control-flow traces are reliable; "no other caller" negatives are bounded, not established.

**Working-tree state this review read.** HEAD is `54cc0a708f`. Track 9's step 1 landed at `b35ac67d2f`, and the working tree carries uncommitted follow-up edits to `GremlinStepWalker.java`, `GremlinToMatchStrategy.java`, `RepeatDeclineStrategy.java` and `RepeatDeclineStrategyTest.java` — the fix for the root-only guard, now a `RepeatDeclineStrategy.Veto` marker read at `GremlinToMatchStrategy:266` instead of the absence check `b35ac67d2f` shipped. Findings that depend on that code say which version they read.

## Findings

### R1 [blocker]
**Certificate**: C14 (Testability: item 6's regression gate), with C5 supplying the noise term
**Location**: `plan/track-11.md` item 6 (line 69) and its acceptance criterion (line 87); contrast `plan/track-9.md` item 4's fix-vs-defer rule and `plan/track-9.md` `## Validation and Acceptance` line 163

**Issue**: Item 6 says "show no regression against Track 9's post-fix baseline" and stops there. It carries no rule for what the run is allowed to find, and this track has no successor to inherit whatever it finds.

Registering four terminators newly translates shapes that decline today, so the item-6 run moves the measured set in both directions exactly as Track 9's item 2 does. Track 9 wrote that consequence down explicitly and sized it — 28 of 42 upstream failures report a count comparison, 25 of them over-emission, "scenarios failing today should pass; any scenario passing today only because a filter was dropped will start failing", both directions recorded (`plan/track-9.md` item 3). Item 6 has no equivalent sentence, and the direction that matters more here is the one Track 9 did not face: a scenario that passes today *because the shape declines to the native pipeline* and fails tomorrow because the translator now claims it. This track's own `## Context and Orientation` names `groupCount().unfold()` and `valueMap().unfold()` as "ordinary idioms present in the Cucumber suite", so the newly-claimed population is not a handful of scenarios.

Three things are missing that Track 9's item 4 has, and Track 9 has them because its Phase A adversarial pass made A1 a blocker for exactly this shape:

- **A fix-vs-defer rule.** Track 9 bounds in-track fixes to "the dropped-filter family plus defects whose diagnosis lands on files already in this track's scope" and dispositions the rest. Item 6 says nothing, which is the open bucket A1 identified as how Track 10's identical bucket became 483 repairs.
- **A destination for anything not fixed.** Track 9's A2 rule is that every disposition names one of fixed here / shape declined here / a named follow-up, and that "deferred with no owner is not a disposition". Track 9 could write that because Track 11 followed it. Nothing follows Track 11.
- **An ESCALATE trigger.** Items 1 and 4 of Track 9 both carry one. Item 6 carries none, so an item-6 run that surfaces thirty new failures has no exit except growing the track.

The severity is a blocker rather than a should-fix because the kill-switch defaults on (`GlobalConfiguration:1019`), so anything item 6 finds and leaves alone ships live at merge, and because the missing rule is discovered at the very last gate of the last track — the point where a plan amendment is most expensive.

**Proposed fix**: Give item 6 the three clauses Track 9's item 4 already has, adapted to a track with no successor:

1. State both directions explicitly, as item 3 of Track 9 does: scenarios that start passing because a terminator now translates, and scenarios that start failing because a terminator now translates. Both are recorded; only the second is a defect.
2. State the fix-vs-defer rule. The natural bound here is narrower than Track 9's and cheaper: **the decline exit is the default.** A newly-claimed shape that disagrees with native gets its recogniser's decline branch widened until it agrees, which restores the translator-on-equals-translator-off invariant by construction and costs one condition. Fixing the projection is the exception and needs a written reason.
3. Add the ESCALATE trigger with a named follow-up destination (a YouTrack issue, or a `### Non-Goals` amendment in the plan), and state that a translator-caused silent wrong answer on a shape this track newly claims takes the fix or decline exit before track completion rather than a deferral — the same clause Track 9's A2 wrote, minus the "later track inherits it" escape.

### R2 [blocker]
**Certificate**: C1 (Exposure: `POST_UNION_RECOGNISERS` relaxation and the two shaping carriers)
**Location**: `plan/track-11.md` `## Context and Orientation` line 33, item 2 (line 65), item 3 (line 66), item 4 (line 67), acceptance lines 80 and 86; `AbstractMatchPlanStep.java:370-398`; `MultiPlanMatchStep.java:308-341`; `UnionStepRecogniser.java:124`

**Issue**: The engine applies row-level suffix steps *before* payload-level list-shaping ops, whatever order the Gremlin traversal declared. The only thing standing between that and a silent wrong answer is the last-step rule, and this track file states that rule three ways that cannot all be true.

The two carriers and their fixed order, traced:

- `MultiPlanMatchStep.startPlanStream()` wraps the concatenator in each `PostConcatOp` at `:338-340`, so `count` / `range` / `dedup` act on **rows**. On the single-plan path the same suffix steps land in the SQL statement through `setReturnDistinct` / `setLimit` / `setSkip` / `setOrderBy`, also on rows.
- `AbstractMatchPlanStep.openShapedPayloads()` at `:374-376` builds the projection source and only then calls `applyListShaping`, so a `ListShapingOp` acts on **projected payloads**.

`openStream` is what `startPlanStream()` returned, and `openShapedPayloads()` reads it. Row-level always precedes payload-level. `ListShapingOp` has no way to interleave with a `PostConcatOp`, and `ResultShaping` has no slot expressing "this op runs before that row filter".

That fixed order is correct for one declared order and wrong for the other:

| Traversal | Native | Engine | |
|---|---|---|---|
| `union(a,b).count().fold()` | `[N]` | count rows → N → fold → `[N]` | agrees by luck |
| `union(a,b).fold().count()` | `1` | count rows → N → fold → `[N]` | wrong, silently |
| `g.V().valueMap().unfold().dedup()` | dedup over unfolded entries | DISTINCT rows → project → unfold | wrong, silently |
| `g.V().values("name").fold().order()` | one list, unordered | ORDER BY rows → fold → sorted list | wrong, silently |

Now the rule that is supposed to stop those. This file says all of:

- line 33: the four terminators "are accepted only as the **last** step (D3)";
- line 33, two sentences later: `reverse().unfold()` and `unfold().reverse()` "are both accepted with declared order preserved" — which requires `reverse` and `unfold` to be accepted **non-last**;
- item 3 (line 66): "Mid-traversal use declines (D3)";
- acceptance line 80: "any mid-traversal list-shaper decline".

Under the literal reading, `reverse().unfold()` must decline, contradicting line 80's first clause and line 33's own claim. Under the reading that makes `reverse().unfold()` work, a non-last list-shaper is legal and nothing in the file says what may follow it. The rule that is both consistent and safe — *a list-shaping op may be followed only by another list-shaping op* — appears nowhere.

Item 4 removes the gate that was covering for the ambiguity. `GremlinStepWalker:335` declines any post-union step whose recogniser is not in `POST_UNION_RECOGNISERS`, and the field's javadoc at `:193-200` states the admission criterion: a recogniser that writes into "the DISTINCT / GROUP BY / ORDER BY / LIMIT / SKIP fields" post-union has its contribution silently discarded. The four terminators satisfy that criterion — they write only shaping — so admitting them is right. But admitting them also makes `postUnionSuffixTranslatable` (`:383-399`) accept **every** permutation of `{count, range, dedup, fold, unfold, reverse, tail}` in the suffix, including `union(...).unfold().limit(2)` and `union(...).fold().count()`. Before item 4 those declined at the walker; after it they reach the recognisers, and only a last-step gate declines them.

Item 2 is where this bites hardest. Its whole specification of the `FoldStep` recogniser's decline branches is "Declines when `!step.isListFold()`, and declines when `supportsListShaping()` is false" — no last-step gate at all. Item 3 mentions one in passing for the other three. There is no in-repo idiom to copy: a grep across `translator/strategy/` finds `peek(1)` used only in `VertexStepRecogniser`, and no terminator recogniser today checks that it is last.

One more instance of the same hole, on the union path rather than the suffix path: `UnionStepRecogniser:124` calls `ctx.setResultShaping(agreedShaping)`, a full replace. A list-shaping op appended to the parent context *before* the union is silently erased — `g.V().fold().union(...)` loses its fold with no decline. Item 1 asserts the two cannot collide because of "D3's last-step rule plus `UnionStepRecogniser` calling `setResultShaping(agreedShaping)` before any suffix op appends". The second half is true; the first half is the rule this finding says is not pinned down.

**Proposed fix**:

1. Replace the three variants with one rule, stated once and cited from items 2, 3 and 4: **a recognised list-shaping op may be followed only by another list-shaping op; any other following step declines the whole traversal.** That admits `reverse().unfold()` and `unfold().reverse()`, declines `fold().count()`, `unfold().dedup()`, `tail(2).limit(1)`, `fold().order()`, and `fold().union(...)`, and keeps line 80's "any mid-traversal list-shaper declines" true under the only reading that survives.
2. Put the gate in item 2 as well as item 3, since item 2 is the recogniser whose decline branches are enumerated and `fold` is the op whose misordering is most visibly wrong.
3. Record the cross-carrier ordering fact in `## Context and Orientation`. It is the reason the gate is a correctness requirement rather than a coverage choice, and it is also the reason `union(...).count().fold()` is *accidentally* right — an implementer who does not know the engine order cannot tell those two apart.
4. Add to item 5's decline set: `union(__.out(), __.in()).fold().count()`, `g.V().valueMap().unfold().dedup()`, and `g.V().values("name").fold().order()`. Each is a silent wrong answer if the gate is missing and each is cheap to assert.

### R3 [should-fix]
**Certificate**: C5 (Assumption: translate/decline is a function of the written traversal) — CONTRADICTED
**Location**: `plan/track-11.md` items 5, 6 and 7 (lines 68-70) and acceptance lines 85-88; `RepeatDeclineStrategy.java:97-105`

**Issue**: Every gate in this track asserts that a given traversal either translates or declines. Track 9's step 1 measured that this is not a function of the traversal alone: one traversal's translation flipped on how many scenarios had already run in the same fork, at a boundary near 1200, cause unexplained, `AdjacentToIncidentStrategy` the first suspect.

There is code evidence for a mechanism, in the strategy Track 9 step 1 added. `RepeatDeclineStrategy`'s javadoc at `:97-105` records that its `addStrategies` call "re-runs `TraversalStrategies.sortStrategies` over the whole list", that TinkerPop "resolves everything else by the iteration order of the maps it builds", and — naming the same suspect — that "whether `AdjacentToIncidentStrategy` rewrites the last unrolled hop into an edge hop turns on exactly that unconstrained position". A rewrite from a vertex hop to an edge hop changes which recogniser the walker dispatches, so it changes whether the shape translates. Sort order resolved through hash-map iteration over `Class` keys is allocation-history dependent, which is the shape of a defect that flips at a scenario-count threshold rather than at a code change. I did not verify the mechanism end to end and do not claim it is the cause; what is established is that the decision has been observed to move without the traversal moving.

Three of this track's gates rest on the assumption:

- **Item 5's decline tests** assert a shape declines. A unit test runs early in its fork; the Cucumber suite runs the same shape after up to 1930 scenarios. A green unit test is then not evidence about the suite.
- **Item 6's regression diff** attributes every delta against Track 9's baseline to this track's four recognisers. If two runs at one SHA can differ, part of the delta is noise, and the gate as written cannot distinguish noise from a defect — which makes "no regression" unfalsifiable rather than merely hard.
- **Item 7's harness** asserts the boundary step is installed on the on-arm. If installation is order-dependent for some shapes, the assertion is a flake source in a benchmark harness, where a flake shows up as a missing measurement rather than a red test.

**Proposed fix**: Two cheap additions, no new mechanism.

1. **Measure the noise before reading the signal.** Item 6 runs the baseline command twice at the *same* SHA — Track 9's published SHA — before running it at this track's tip. The delta between the two same-SHA runs is the run-to-run variance; only a delta larger than that is attributable to this track. This costs one extra ~20 s `core` run and one `embedded` run, and it also re-validates the inherited artifact (see R4).
2. **Make item 5's decline assertions read the walker's decision, not the row count.** Assert that the compiled traversal carries no `AbstractMatchPlanStep` (the idempotency scan at `GremlinToMatchStrategy:363-371` is the existing predicate), rather than asserting that the rows match native. A shape that declines and a shape that translates correctly both match native, so a row-count assertion passes whichever way the decision went — the same vacuous-pass failure mode Track 9's Phase A hit twice (R16, A6/A7).

Also worth one line in `## Clarifications`: if Track 9's step-1 investigation lands a cause or a pin for the nondeterminism, this track's gates read it rather than re-deriving it.

### R4 [should-fix]
**Certificate**: C4 (Assumption: Track 9 publishes a baseline this track can read) — UNVALIDATED
**Location**: `plan/track-11.md` `## Decision Log` fourth bullet (line 26) and item 6 (line 69); `plan/track-9.md` `## Decision Log` R8 bullet (line 30) and `## Validation and Acceptance` line 163

**Issue**: Track 9 assigned this track a reciprocal obligation that this track file does not carry, and item 6 pins the wrong version of the artifact.

Track 9's R8 rule ends: "Track 11 inherits the reciprocal obligation: before reading the baseline it confirms the recorded SHA is an ancestor of its own base with no intervening `core` commit, and re-takes the measurement otherwise." A grep for `SHA`, `ancestor`, `merge-base` and `stale` across `plan/track-11.md` returns lines 60, 61, 102 and 113 — the iteration-loop clarification, the `embedded` install clarification, an interfaces line, and the Base commit template comment. The obligation appears nowhere.

Item 6 also names the artifact one step too early. It says the baseline is "the artifact Track 9 publishes after its **last** fix — its triage item re-measures on top of the filter fix". Track 9's own acceptance criterion at line 163 says the opposite about which run counts: the published SHA must be Track 9's *final HEAD*, with Phase C review fixes included in the re-trigger list, and it names Track 10's `a0b3e96e15` → `5db5b41a3d` → `7c77a4544f` sequence as the worked example of an artifact that was stale at close precisely because the trigger list stopped at the Plan of Work. Track 9's item 4 re-measure is therefore a candidate handoff, not the handoff. Reading item 4's artifact when a Phase C fix landed after it reproduces Track 10's incident one track later.

The working tree makes this concrete rather than hypothetical. HEAD is `54cc0a708f`, and the CI figures banked at `b35ac67d2f` (`core` 1930/41/14, `embedded` 1931/41/14) predate uncommitted edits to three `core` production files. By Track 9's own rule those figures are already invalidated. That is Track 9's problem to re-take, but it is this track's problem to notice.

**Proposed fix**: Two sentences in item 6.

1. Carry the reciprocal check verbatim: before reading the baseline, confirm `git merge-base --is-ancestor <baseline-sha> <this track's base>` and that `git log <baseline-sha>..<base> -- core embedded` is empty. If either fails, re-take the measurement at this track's base and record that number as the baseline, noting the divergence.
2. Re-point the artifact identifier from "after its last fix / its triage item re-measures" to "the artifact stamped at Track 9's final HEAD, the one satisfying Track 9's acceptance criterion at `plan/track-9.md:163`". Drop the item-4 framing, which contradicts the criterion it cites.

The re-take path in (1) composes with R3's same-SHA double run: one command shape covers both the staleness check and the variance measurement.

### R5 [should-fix]
**Certificate**: C9 (Assumption: `jmh-ldbc/src/test` runs in an ordinary build) — VALIDATED, with a resolution caveat that inverts the gate
**Location**: `plan/track-11.md` item 7 (line 70) and acceptance line 88; `jmh-ldbc/pom.xml:20-23`; `plan/track-9.md` `## Clarifications` (the `embedded` stale-jar entry)

**Issue**: Item 7's in-track execution has the same stale-jar hazard the `embedded` runner has, and here the failure mode is a green vacuous pass rather than a visible miss. This track file records the hazard for `embedded` (line 61) and not for `jmh-ldbc`.

`jmh-ldbc/pom.xml:20-23` declares `youtrackdb-core` at `${project.version}`. Under `./mvnw -pl jmh-ldbc test` the reactor holds one module, so core resolves from the local repository — the same mechanism Track 9's `## Clarifications` documents for `-pl embedded`, and on this machine the installed jar is dated 2026-07-02 by Track 9's account, predating Tracks 7, 8 and 10.

The consequence is worse than for `embedded` because of what item 7 asserts. Its check is that "the boundary step is installed in the first and absent in the second". A boundary step has existed since Track 2, so against a stale core jar the on-arm assertion passes, the off-arm assertion passes, the test is green, and none of this track's four recognisers was loaded. Track 9's identical clause spells the trap out — "a run against a stale jar exercises none of it and still reports no regression" — but this track applies it only to `embedded`.

Two supporting facts, both verified: `jmh-ldbc` is a default reactor module (`pom.xml:54`), and its POM skips only `maven-deploy-plugin` (`:152-157`), so surefire runs under the parent's configuration and `LdbcQueryCorrectnessTest` executes in an ordinary build. The module builds correctly under the full reactor; only the `-pl` form is unsafe.

**Proposed fix**: Pin item 7's invocation the way this file already pins the Cucumber loop and the `embedded` run. Either `./mvnw -pl core,jmh-ldbc test -Dtest=<harness test>` (both modules in one reactor, no install step) or `./mvnw -pl core -am install -DskipTests` followed by `./mvnw -pl jmh-ldbc test`, repeated after every code change the assertion is meant to cover. Add one sentence saying why: against a stale core jar both arms of the assertion pass and the test proves nothing.

Second, strengthen the assertion so a stale classpath cannot pass it. Assert on the shape whose translation this track introduces — a `fold()` or `unfold()` traversal — rather than any recognised shape. A stale core jar has no terminator recogniser, so the on-arm assertion then fails loudly instead of passing on a Track 2 boundary step.

### R6 [should-fix]
**Certificate**: C10 (Assumption: `g.V(rid)` translator-on can be strictly slower) — VALIDATED
**Location**: `plan/track-11.md` item 7 (line 70); `GremlinToMatchTranslator.java:145`; `GremlinToMatchStrategy.java:432`

**Issue**: Item 7 deliberately includes a shape it already knows is a live translator-on regression, and names no destination for it. No track follows this one, so the regression ships at merge with the kill-switch defaulting on.

The mechanism checks out. `GremlinToMatchTranslator:145` sets `cacheEligible = false` on a RID-bearing walk, and `GremlinToMatchStrategy:432` gates the plan-cache publication on that flag, so a `g.V(rid)` walk recompiles a MATCH plan on every execution where the native path ran no query at all. Item 7 states the consequence itself: "it remains the one shape where translator-on can be strictly slower than translator-off — Track 10's promotion fix landed but the per-call recompile did not."

Track 9's Phase A settled the general form of this: A2's rule is that every disposition names a destination, one of fixed here / shape declined here / a named follow-up, and that "deferred with no owner is not a disposition". Item 7 measures the regression and disposes of it in neither of the three ways. It is not in the plan's `### Non-Goals` list either — a grep of `implementation-plan.md:390-399` returns the Phase 2 decline set, and per-call recompile on RID-bearing walks is not in it.

Likelihood is certain rather than probable: the code path is in `develop`'s future as soon as this branch merges, and the benchmark exists precisely to show it. Impact is bounded — a by-id lookup, not a hot storage path — which is why this is should-fix rather than blocker.

**Proposed fix**: Item 7 records a destination for the shape it measures. The cheapest honest option is a named follow-up: a YouTrack issue for RID-bearing plan-cache eligibility, referenced from item 7 and from the plan's `### Non-Goals`, carrying the harness's measured on-vs-off figure as its evidence. If the number turns out large enough that shipping it is not acceptable, the alternative exit is the one Track 9's A2 also allows — decline RID-bearing walks for now — but that is a scope decision the orchestrator makes, not something item 7 should decide silently by measuring and moving on.

### R7 [should-fix]
**Certificate**: C13 (Testability: item 5's re-arm and clone coverage) — DIFFICULT
**Location**: `plan/track-11.md` item 5 (line 68) and acceptance line 85; `AbstractMatchPlanStep.java:167-176`; `ListShapingOp.java:31-38`

**Issue**: `fold` and `tail` are both buffered ops sharing one hazard, and item 5 specifies re-arm coverage for both but clone coverage for `fold` only.

The hazard is recorded in this file's `## Context and Orientation` (line 41) and corroborated in code: `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` leaves it alone, so two clones share the same `ListShapingOp` instances. `ListShapingOp`'s javadoc at `:31-38` states the discipline that makes that safe — each `apply` returns an independent iterator and the op holds no state across calls — and names the two ops that need buffers as the ones most likely to break it. `AbstractMatchPlanStep:167-176` confirms `shapedPayloads` is rebuilt on every open, so `apply` really is called afresh per arming.

Item 5's wording splits the two ops unevenly: "Re-arm: `fold()` and `tail(n)` return identical results across `toList(); reset(); toList()` from both the `DRAINED` and `CLOSED` (`REARMED_AFTER_CLOSE`) routes. Clone: two concurrently-iterated clones of a **`fold()`** boundary each see their own full result." `tail`'s ring buffer is the harder of the two to get right — it is a bounded window whose contents depend on arrival order, so a shared `ArrayDeque` across two clones yields a plausible-looking wrong window rather than an obviously empty result, which is the failure a row-count assertion is least likely to catch.

The infrastructure exists, so this is a one-test gap rather than a design problem: item 5 already points at `MultiPlanMatchStepTest`'s clone-isolation idiom, and `YTDBMatchPlanStepTest:1178-1430` already drives `withListShapingOps` through stateful fixture ops (`TagRepeatOp`, `DrainToSizeOp`) across re-arm routes.

**Proposed fix**: Extend item 5's clone clause to `tail(n)` alongside `fold()`, and make the `tail` assertion element-for-element on an ordered input rather than on size — acceptance line 86 already establishes that positional assertions are valid on ordered inputs, so the fixture shape is settled. One sentence in item 5 and one more test method.

### R8 [suggestion]
**Certificate**: C12 (Testability: coverage of the new JMH classes) — ACHIEVABLE with a placement constraint
**Location**: `jmh-ldbc/pom.xml:161-189`; `plan/track-11.md` item 7 (line 70)

**Issue**: The JaCoCo exclusion that keeps untestable JMH bodies out of the coverage gate is a single-level glob, and item 7 does not say where the new classes go.

`jmh-ldbc/pom.xml`'s `coverage` profile excludes `**/benchmarks/ldbc/*.class` from the report. `*.class` matches one directory level, so classes placed in `benchmarks/ldbc/gremlin/` are *not* excluded — they appear in the report at roughly zero coverage, and `coverage-gate.py`'s `collect_coverage_data` counts their changed lines against the 85% line / 70% branch thresholds. JMH benchmark method bodies are not unit-testable by construction, which is why the exclusion exists (the POM comment says so outright).

The benign direction holds for same-package placement: a file absent from every report contributes no coverable lines, so the gate neither credits nor penalises it. Mirroring the existing naming — `LdbcSingleThreadICBenchmark` → a Gremlin sibling in the same package — lands in the exempt set. This is a suggestion rather than a should-fix because the default placement is the safe one; the risk is an implementer tidying the new classes into a subpackage.

**Proposed fix**: One clause in item 7 stating that the mirrored classes go in `com.jetbrains.youtrackdb.benchmarks.ldbc` directly, because the coverage exclusion is single-level, and that the in-track execution test is the part that carries coverage weight and therefore lives where it counts.

### R9 [suggestion]
**Certificate**: C14 (Testability / feasibility: track sizing against the Phase C burden check)
**Location**: `implementation-plan.md:710-718` (the Track 11 Scope line); `plan/track-9.md` `## Decision Log` DR-S1

**Issue**: The plan sizes Track 11 at ~14–20 in-scope files, inside the soft bounds. The Phase C review-burden check reads a different quantity, and on that quantity this track has no escape hatch.

DR-S1 records the rule: `track-code-review.md` step 9's `--shortstat` excludes only generated sources and the generated parser, so `docs/adr/**` and test code both count, and the threshold is around 4,000 lines. It also records the observed magnitudes — Track 8 at 5,814 code insertions, Track 10 at 5,168 non-generated over 36 files against 2,613 of `_workflow/` prose, and Track 10's `plan/track-10/reviews/` alone at 3,154 lines. This track's Phase A panel will add its own reviews to that sum, and its code half carries four recognisers, four ops, a seam across three files, two child gates, an allow-list change, five javadoc corrections, the mirrored JMH classes, and five test families.

DR-S1's remedy for an over-threshold track was a split. Track 11 is the last track on the branch, so there is no successor to split into, and splitting backwards would reorder work Track 9 already depends on being after it.

Nothing here says the track is mis-sized. The point is that the one lever DR-S1 used is unavailable, so it is worth deciding at decomposition rather than at Phase C.

**Proposed fix**: At decomposition, order the roster so items 6 and 7 are separable — the two whose deliverables are artifacts and harness code rather than translator behaviour — and note in `## Artifacts and Notes` that if the Phase C burden check trips, the recorded response is a written justification rather than a split, with the sum's composition (code half against `_workflow/` half) stated the way DR-S1 states Track 10's.

## Evidence base

#### C1 Exposure: the `POST_UNION_RECOGNISERS` relaxation and the two independent shaping carriers — residual risk HIGH
- **Track claim**: item 4 adds the four terminator recognisers to `GremlinStepWalker.POST_UNION_RECOGNISERS`, "today `count` / `range` / `dedup`, Track 8 DR-U4"; acceptance line 84 says a union suffix "still folds once" and matches native.
- **Critical path trace**:
  1. Entry: `GremlinStepWalker.dispatchAll(cursor, ctx, recognisers)` @ `GremlinStepWalker.java:323`. Per step it looks up the recogniser by exact class and, at `:335`, declines when `ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)`.
  2. `GremlinStepWalker.postUnionSuffixTranslatable(cursor, recognisers)` @ `:383` — the pre-fork look-ahead reading the same field; returns false on the first suffix step whose recogniser is not allow-listed.
  3. `UnionStepRecogniser.recognize` @ `UnionStepRecogniser.java:60-127` — calls the look-ahead first (`:69`), walks each child through `host.walkFork` (`:95`), agrees the projection contract at `:105-112`, then pins it with `ctx.setResultShaping(agreedShaping)` at `:124`, a **full replace**.
  4. `MultiPlanMatchStep.startPlanStream()` @ `MultiPlanMatchStep.java:308` — builds one `MultipleExecutionStream` over the per-child producer (`:337`), then wraps it in each `PostConcatOp` at `:338-340` via `applyPostConcatOp` (`:434-448`: `PostConcatStreams.count`, range, `PostConcatStreams.dedup`). All three act on **rows**.
  5. `AbstractMatchPlanStep.openShapedPayloads()` @ `AbstractMatchPlanStep.java:374` — picks the projection source (`accumulatedGroupMapSource` or `rowProjectionSource`) and calls `applyListShaping(source)` @ `:386`, which threads each `ListShapingOp` left to right at `:391-393`. These act on **projected payloads**, downstream of step 4 in every case, since `openStream` is what step 4 returned.
- **Blast radius**: any traversal whose recognised suffix mixes a list-shaping op with a row-level step in the order list-shaper-first returns a wrong result set with no error and no decline. Reached from both boundary forms (`MultiPlanMatchStep` via `PostConcatOp`, `YTDBMatchPlanStep` via the SQL DISTINCT / LIMIT / SKIP / ORDER BY fields), so the exposure is not union-specific. The kill-switch defaults on (`GlobalConfiguration:1019`), so it is a live wrong answer rather than an opt-in one. A second reachable case: a list-shaping op appended before a `union` is erased by the full replace at `UnionStepRecogniser:124`.
- **Existing safeguards**: the allow-list itself (`GremlinStepWalker:206-210`), which today declines every non-`count`/`range`/`dedup` post-union suffix step and is the gate item 4 relaxes; the fail-closed default for unregistered classes at `:328-330`; the assert at `:344-352` catching an accept that consumed nothing; the empty-list structural bypass at `AbstractMatchPlanStep:387-389`, which keeps non-terminator traversals off the shaping path entirely. No safeguard orders a `ListShapingOp` against a `PostConcatOp`; `ResultShaping` (`ResultShaping.java:39-47`) has no slot for it, and `ListShapingOp` (`ListShapingOp.java:44-54`) is a single-method iterator-to-iterator interface with no position metadata.
- **Residual risk**: HIGH. After item 4 the only guard is a per-recogniser last-step check, which item 2 does not specify at all, item 3 mentions in four words, and the file's `## Context and Orientation` and acceptance criteria state three mutually incompatible ways. A grep for a last-step idiom across `translator/strategy/` finds `peek(1)` only in `VertexStepRecogniser`, so there is no existing pattern for the implementer to copy. → **R2**

#### C2 Exposure: `ListShapingOp` instances shared across armings and clones — residual risk MEDIUM
- **Track claim**: `## Context and Orientation` line 41 — `applyListShaping` calls `op.apply(...)` afresh on every open across three routes, and `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` does not touch it, so two clones share the same op instances.
- **Critical path trace**:
  1. `AbstractMatchPlanStep.processNextStart` @ `:330-340` — builds `shapedPayloads = openShapedPayloads()` on the first pull of an arming, sets `State.DRAINED` and releases the stream when it runs dry.
  2. `openShapedPayloads()` @ `:374-376` → `applyListShaping` @ `:386-395` — calls `op.apply(shaped)` once per op per open.
  3. `shapedPayloads` field comment @ `:170-176` — "rebuilt fresh on every (re)open", confirming the three open routes the track names.
  4. `ListShapingOp` javadoc @ `:31-38` — states the contract: each call returns an independent iterator, the op holds no state across calls, and an op whose buffer is allocated once outside the returned iterator "would replay stale output on the second arming".
- **Blast radius**: a stateful `fold` or `tail` op replays or corrupts results on re-arm, and on two concurrently-iterated clones produces interleaved output. Confined to traversals carrying a list-shaping terminator — the structural bypass at `:387-389` keeps everything else off the path.
- **Existing safeguards**: the documented contract at `ListShapingOp:31-38`; the structural bypass; `YTDBMatchPlanStepTest:1178-1430`, which already exercises `withListShapingOps` with stateful fixture ops (`TagRepeatOp`, `DrainToSizeOp`) across re-arm routes and so provides the assertion idiom; `MultiPlanMatchStepTest`'s clone-isolation idiom, which item 5 names.
- **Residual risk**: MEDIUM, and unevenly covered. Item 5 specifies re-arm coverage for both buffered ops and clone coverage for `fold` alone, leaving `tail`'s ring buffer — the harder of the two, because a shared deque yields a plausible wrong window rather than an empty result — without a clone test. → **R7**

#### C3 Exposure: the item-7 kill-switch A/B inside one JVM — residual risk LOW
- **Track claim**: item 7 — drive one recognised shape through the harness entry point twice, kill-switch on and off, asserting the boundary step is installed in the first and absent in the second; `GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` "is read by `GremlinToMatchStrategy:338`, so the on/off axis is real".
- **Critical path trace**:
  1. `GremlinToMatchStrategy.resolveSessionIfEnabled` @ `:335-352` — resolves the YTDB session, reads `session.getConfiguration()`, and returns the session only when `getValueAsBoolean(QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED)` is true. The read is **per-session**, which the javadoc at `:325-327` states is deliberate so operators and tests can "flip it per-session without mutating global state".
  2. `GremlinToMatchStrategy.containsBoundaryStep` @ `:363-371` — scans for `AbstractMatchPlanStep`, the predicate an installed / absent assertion keys on.
  3. Plan-cache interaction: `GremlinToMatchStrategy:432` gates cache publication on `translation.cacheEligible()`. The off-arm returns before the walker, so it never consults the cache; an on-arm following an off-arm reads a cache the off-arm never wrote.
- **Blast radius**: a mis-set switch produces an A/B one flag apart in name only — the failure Track 9's R9 recorded for `-DargLine=`. Confined to the harness's own measurement.
- **Existing safeguards**: `GremlinToMatchSmokeTest:106-145` and `:419-445` already implement the in-JVM flip through `session.getConfiguration().setValue(...)` with restore-in-finally, so the idiom is in-repo and proven; `GraphStepStrategyTest:28` is a second instance. Item 7's own requirement that the installation check throw rather than use a Java `assert` closes the JMH assertions-disabled hole.
- **Residual risk**: LOW. The axis is real and the template exists. What is not safe is the classpath the harness runs against — see C9. → feeds **R5**

#### C4 Assumption: Track 9 publishes a baseline this track can read — UNVALIDATED
- **Track claim**: `## Decision Log` line 26 — "Track 9 re-runs both runners after its final fix and publishes that artifact explicitly for this purpose"; item 6 — "the artifact Track 9 publishes after its **last** fix — its triage item re-measures on top of the filter fix".
- **Evidence search**: Read of `plan/track-9.md` (`## Decision Log` R8 bullet at line 30; `## Validation and Acceptance` line 163; items 3 and 4); `grep -n 'SHA\|ancestor\|merge-base\|stale' plan/track-11.md`; `git log --oneline ab4a118efd..HEAD`; `git status --short`. Grep and Read only — no symbol search involved.
- **Code evidence**: `plan/track-9.md:30` assigns this track the reciprocal check ("confirms the recorded SHA is an ancestor of its own base with no intervening `core` commit, and re-takes the measurement otherwise"). The grep over `plan/track-11.md` returns lines 60, 61, 102, 113 — none of them that check. `plan/track-9.md:163` requires the published SHA to be Track 9's final HEAD with Phase C fixes in the re-trigger list, contradicting item 6's item-4 framing. `git status --short` shows uncommitted edits to `GremlinStepWalker.java`, `GremlinToMatchStrategy.java` and `RepeatDeclineStrategy.java` at HEAD `54cc0a708f`, so the CI figures banked at `b35ac67d2f` are already invalidated by Track 9's own rule.
- **Verdict**: UNVALIDATED
- **Detail**: the baseline does not exist yet, which is expected for a panel running in parallel with Track 9's Phase B. What is a defect now rather than later is that this track file carries neither the staleness check Track 9 assigned it nor the correct identifier for which of Track 9's several measurements is the handoff. → **R4**

#### C5 Assumption: translate/decline is a function of the written traversal — CONTRADICTED
- **Track claim**: implicit in items 5, 6 and 7 and in every acceptance line of the form "X translates" / "Y declines" — that a shape's translation status is a property of the shape.
- **Evidence search**: Read of `RepeatDeclineStrategy.java` (whole file), `git show b35ac67d2f` (message and diff), and the out-of-band record of Track 9 step 1's discovery. Grep for the step-level finding across `plan/track-9/reviews/` — that directory holds the Phase A panel plus five step-level dimensional files; the threshold observation is not written into `plan/track-9.md`'s `## Surprises & Discoveries`, which is still empty.
- **Code evidence**: `RepeatDeclineStrategy.java:97-105` — the veto's `addStrategies` "re-runs `TraversalStrategies.sortStrategies` over the whole list"; TinkerPop "resolves everything else by the iteration order of the maps it builds"; "whether `AdjacentToIncidentStrategy` rewrites the last unrolled hop into an edge hop turns on exactly that unconstrained position". A vertex-hop-to-edge-hop rewrite changes which recogniser `GremlinStepWalker.dispatchAll` (`:323-357`) dispatches on, so it changes the translation decision. Independently, Track 9 step 1 observed one traversal's translation flipping at roughly 1200 preceding scenarios in the same fork, cause unexplained.
- **Verdict**: CONTRADICTED
- **Detail**: the assumption fails at least once, measured. I did not confirm the sort-order path is the cause and make no such claim; the established fact is that the decision moved without the traversal moving, which is enough to invalidate a gate that compares two runs and attributes the whole delta to a code change. Item 5's unit tests run early in their fork, item 6's suite runs up to 1930 scenarios in one, and item 7's harness asserts installation — all three read the same assumption. → **R3**

#### C6 Assumption: `ResultShaping.withListShapingOps` has no production caller — VALIDATED
- **Track claim**: item 1 — "`ResultShaping.withListShapingOps(@Nonnull List<ListShapingOp>)` exists at `ResultShaping.java:106`, replaces the list wholesale, and has no production caller yet".
- **Evidence search**: `grep -rn "withListShapingOps\|listShapingOps\|applyListShaping" --include=*.java core/src`. PSI attempted and failed — one `ReferencesSearch` over the method through `steroid_execute_code` timed out.
- **Code evidence**: every `withListShapingOps` call site is in `core/src/test/.../YTDBMatchPlanStepTest.java` (`:1212`, `:1265`, `:1337`, `:1374`, `:1393`, `:1429`). Production hits are the declaration itself, `AbstractMatchPlanStep:158` (javadoc), `:375`, `:386-387`, `ListShapingOp:8` (javadoc), `PostConcatOp:10` (javadoc). The method body at `ResultShaping.java:103-108` confirms wholesale replacement.
- **Verdict**: VALIDATED
- **Detail**: grep-based, so the negative is bounded rather than established — a reflective or generated caller would not appear. Given the method is package-visible on an internal record introduced by Track 7, the residual doubt is small. Item 1's append implementation on top of it is sound.

#### C7 Assumption: a union suffix folds once over the concatenation, not once per child — VALIDATED
- **Track claim**: `## Context and Orientation` line 43 — `MultiPlanMatchStep.startPlanStream()` returns one `MultipleExecutionStream`, `openShapedPayloads()` runs once per arming over that single stream, and `ListShapingOp`'s "once per child plan for a multi-plan boundary" javadoc is false.
- **Evidence search**: Read of `MultiPlanMatchStep.java:300-341` and `:32-53`; `AbstractMatchPlanStep.java:167-176` and `:330-395`; `ListShapingOp.java:31-38`. Grep for `startPlanStream|MultipleExecutionStream|openShapedPayloads` within `MultiPlanMatchStep.java`.
- **Code evidence**: `MultiPlanMatchStep:337` constructs one `MultipleExecutionStream(producer)` for the whole arming; `AbstractMatchPlanStep:330-336` builds `shapedPayloads` once per arming from that single `openStream`. `MultiPlanMatchStep`'s class javadoc at `:47` says the intended behaviour outright — "`union().fold()` fold the whole union into one list rather than one list per child". `ListShapingOp:33-34` says the opposite.
- **Verdict**: VALIDATED
- **Detail**: the track's reading of the code is right and the javadoc it flags is wrong, in the direction that would mislead an implementer into building one list per child. Item 4 already schedules the correction. No finding — recorded because acceptance line 84 rests on it.

#### C8 Assumption: `SubTraversalPredicateAdapter` swallows `setResultShaping`, so a `void` append cannot signal refusal — VALIDATED
- **Track claim**: DR-T2 — copying the adapter's swallow turns `g.V().and(__.out().fold())` into an existence filter and rows silently disappear, so the seam needs a query (`supportsListShaping()`) beside the mutator.
- **Evidence search**: Read of `SubTraversalPredicateAdapter.java:380-425`; `RecognitionContext.java` (grep for `setResultShaping|appendPostConcatOp|hasUnionCarrier|walkChild|shaping()`); `GremlinStepWalker.java:402-435` (`subWalk`).
- **Code evidence**: `SubTraversalPredicateAdapter:420-424` — `setResultShaping` is a comment-only no-op ("boundary row-projection shaping is pinned by terminal recognisers on the outer context only"), sitting alongside the same treatment for `setOrderBy` / `setLimit` / `setSkip`. `RecognitionContext:270` declares `setResultShaping`, `:286` the throwing `appendPostConcatOp` default, `:333` `walkChild`. `walkChild` on the adapter (`:413-416`) returns `GremlinStepWalker.subWalk(child, this, recognisers)`, wrapping this adapter — so a `false` override reaches grandchildren without extra work. `subWalk` (`:412-431`) drives the child through the same `dispatchAll` loop against the adapter as context.
- **Verdict**: VALIDATED
- **Detail**: both of DR-T2's rejected templates are where it says they are, and the recursion means one override covers nested combinators. The seam design is sound as specified. No finding.

#### C9 Assumption: `jmh-ldbc/src/test` runs in an ordinary build, so item 7's harness can execute in-track — VALIDATED, with a resolution caveat
- **Track claim**: item 7 — "`jmh-ldbc/src/test` is not fixture-less — `LdbcQueryCorrectnessTest` builds a small deterministic in-memory social graph and asserts all 20 LDBC read queries against it, and those tests run in an ordinary build".
- **Evidence search**: Read of `jmh-ldbc/pom.xml` (whole), `pom.xml:44-54` (module list) and `:405-421` (parent surefire pluginManagement), `jmh-ldbc/src/test/.../LdbcQueryCorrectnessTest.java:1-90`; grep for `surefire|skipTests|<skip>|profile|<id>` in `jmh-ldbc/pom.xml`.
- **Code evidence**: `pom.xml:54` lists `jmh-ldbc` as a default module. `jmh-ldbc/pom.xml`'s only `<skip>true</skip>` is on `maven-deploy-plugin` (`:152-157`); surefire is unconfigured locally and inherits the parent, and `junit:junit` is a test dependency (`:50-54`). `LdbcQueryCorrectnessTest:1-50` is JUnit 4, builds an in-memory DB through `YourTracks`, and imports `YTDBGraphTraversalSource`, so a Gremlin-driven fixture test in this module has precedent. **The caveat**: `jmh-ldbc/pom.xml:20-23` declares `youtrackdb-core` at `${project.version}`, so under `-pl jmh-ldbc` core resolves from the local repository — the mechanism Track 9's `## Clarifications` documents for `-pl embedded`.
- **Verdict**: VALIDATED
- **Detail**: the claim holds under the full reactor and fails under the `-pl` form item 7 will reach for. Against a stale core jar both arms of item 7's assertion pass — a boundary step has existed since Track 2 — so the test is green and proves nothing. → **R5**

#### C10 Assumption: `g.V(rid)` translator-on can be strictly slower than translator-off — VALIDATED
- **Track claim**: item 7 — "RID-bearing walks set `cacheEligible=false`, so a by-id lookup compiles an uncached MATCH plan where the native path ran no query, and it remains the one shape where translator-on can be strictly slower than translator-off — Track 10's promotion fix landed but the per-call recompile did not".
- **Evidence search**: `grep -rn "cacheEligible" core/src/main/java --include=*.java`; Read of `GremlinToMatchTranslator.java:74-195` and `GremlinToMatchStrategy.java:425-440`; grep of `implementation-plan.md:390-399` for the `### Non-Goals` set.
- **Code evidence**: `GremlinToMatchTranslator:145` sets `cacheEligible = false` and `:87` documents it as "`false` when the walk is RID-bearing and must bypass the plan cache"; `GremlinToMatchStrategy:432` gates publication on `!translation.cacheEligible()`. The plan's `### Non-Goals` list (Phase 2 declines) does not contain per-call recompile on RID-bearing walks.
- **Verdict**: VALIDATED
- **Detail**: the regression is real, in scope for measurement, out of scope for repair everywhere on the branch, and unowned. Track 9's A2 rule ("every disposition names a destination") has no counterpart in this track file, and no track follows this one. → **R6**

#### C11 Testability: the seam, the four recognisers, and the four ops — ACHIEVABLE
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: the pieces are small and pure. `appendListShapingOp` is one expression over `withListShapingOps`; `supportsListShaping()` is a two-valued default plus one override; each op is an iterator-to-iterator function. The widest branch surface is `unfold`'s five `flatMap` arms — `Iterator`, `Iterable`, `Map` via `entrySet()`, array via `handleArrays` (both `Object[]` and primitive arrays by reflection), and the one-element fallback — each of which item 5 already names a case for.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest:1178-1430` drives `withListShapingOps` end to end with stateful fixture ops across re-arm routes and pins the `withX` threading invariant; `MultiPlanMatchStepTest` carries the clone-isolation idiom; recogniser-level tests exist per recogniser under `translator/strategy/` (`NotStepRecogniserTest` is the one Track 9 cites); `EdgeTraversalEquivalenceTest` / `PredicateTraversalEquivalenceTest` are the native-comparison harnesses.
- **Feasibility**: ACHIEVABLE
- **Detail**: no coverage gap expected for the `core` half. The infrastructure for every case item 5 names is already in the repo, and the branch-heavy piece (`unfold`) is enumerable by construction.

#### C12 Testability: coverage of the mirrored JMH classes — ACHIEVABLE with a placement constraint
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: JMH benchmark bodies are not unit-testable — they are `@Benchmark` methods over a dataset-bound `@State`. The project already resolves this by excluding them from the report rather than by testing them.
- **Existing test infrastructure**: `jmh-ldbc/pom.xml:161-189` — the `coverage` profile's JaCoCo `<excludes>` entry `**/benchmarks/ldbc/*.class`, whose comment states the intent ("all classes here are JMH benchmark infrastructure or CLI tools, not library code"). `.github/scripts/coverage-gate.py`'s `collect_coverage_data` / `compute_results` (invoked at `:438-440`) count only changed lines that appear in a JaCoCo report, so a file absent from every report neither credits nor penalises the gate.
- **Feasibility**: ACHIEVABLE
- **Detail**: `*.class` matches a single directory level. Same-package placement is exempt; a `benchmarks/ldbc/gremlin/` subpackage is not, and would enter the gate at roughly zero coverage. The default placement is the safe one, so the risk is a tidying decision rather than a design problem. → **R8**

#### C13 Testability: item 5's re-arm and clone coverage of the buffered ops — DIFFICULT
- **Coverage target**: 85% line / 70% branch, plus the behavioural assertions acceptance line 85 names
- **Difficulty assessment**: the defect class needs two armings or two concurrent clones to surface, and its symptom is a plausible-looking wrong result rather than an exception. For `tail(n)` specifically, a buffer shared across clones yields a wrong *window* — right size, wrong contents — which a size or row-count assertion cannot see. Acceptance line 86 additionally restricts element-for-element comparison to ordered inputs, so the fixture needs an `order().by(...)` prefix for the assertion to be legal.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest:1178-1430` (stateful fixture ops across re-arm routes, `TagRepeatOp` / `DrainToSizeOp`); `MultiPlanMatchStepTest`'s clone-isolation idiom, which item 5 names; `OrderGlobalStepRecogniser`, which acceptance line 86 confirms already translates `order().by(...)` into a MATCH `ORDER BY`, so the ordered fixture is available.
- **Feasibility**: DIFFICULT — reachable with the infrastructure listed, but not by the tests item 5 currently specifies.
- **Detail**: item 5 covers re-arm for `fold` and `tail` and clone for `fold` only. `tail` is the op where the shared-buffer symptom is hardest to detect and the assertion needs the ordered fixture to be valid at all. → **R7**

#### C14 Testability: item 6's no-regression gate and the track's sizing — DIFFICULT
- **Coverage target**: not a line-coverage question — the gate is "the full TinkerPop Cucumber suite completes and shows no regression against the artifact Track 9 publishes after its last fix" (acceptance line 87)
- **Difficulty assessment**: three separate problems compound. (a) The comparison has an unmeasured noise term, because translation has been observed to move without the code moving (C5). (b) The baseline may be the wrong version of Track 9's artifact and its staleness is unchecked (C4). (c) The gate has no rule for its own output: no both-directions clause, no fix-vs-defer bound, no destination for a deferral, no ESCALATE trigger — while registering four terminators newly translates a population this file itself calls "ordinary idioms present in the Cucumber suite" (`groupCount().unfold()`, `valueMap().unfold()`).
- **Existing test infrastructure**: `YTDBGraphFeatureTest` under `core`'s `gremlin-feature-compliance-tests` execution and `EmbeddedGraphFeatureTest` in `embedded` — both runners named in `## Interfaces and Dependencies`; the pinned iteration invocation in `## Clarifications` line 60, which now carries `test-compile` in the same run; `plan/track-9.md` item 4's disposition format and its A1 / A2 rules, which are the templates the missing clauses would copy.
- **Feasibility**: DIFFICULT
- **Detail**: the mechanics are in place and the discipline around them is not. Track 9's Phase A adversarial pass made the identical shape a blocker on its item 4 (A1: "'fix what belongs' with no rule is how Track 10's identical bucket became 483 repairs"), and the asymmetry here is worse — Track 9 could route a deferral to Track 11, while nothing follows Track 11. The same certificate covers the sizing question: DR-S1's remedy for an over-threshold track was a split, and the last track on a branch has no split target. → **R1**, **R9**
