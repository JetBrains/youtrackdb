<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TS10, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLSuffixIdentifierTest.java:148, anchor: "### TS10 ", cert: n/a, basis: "test name and Javadoc promise the isProjection() gate stops a record dispatch, but the Entity-backed fixture leaves the test green when the gate is deleted"}
  - {id: TS11, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:571, anchor: "### TS11 ", cert: n/a, basis: "scenario comment describes YTDBGraphStep/RangeGlobalStep mechanics that the translator folds away, and the kill-switch is unpinned while three siblings in the same file were pinned"}
  - {id: TS12, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java:2453, anchor: "### TS12 ", cert: n/a, basis: "the track lands five copies of the getSubSteps() plan walk across four test classes, two pairs byte-identical, all encoding the DR-M3 single-accessor decision"}
  - {id: TS13, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:394, anchor: "### TS13 ", cert: n/a, basis: "one 66-line method runs the translated and native by-id contracts in sequence over a shared listener, against the one-path-per-test shape the same class states for the cache-replay pair"}
  - {id: TS14, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java:1553, anchor: "### TS14 ", cert: n/a, basis: "the raw-entity reader and freshTraversal stay duplicated across the two boundary test classes in the track that created BoundaryStepTestSupport as their shared home"}
  - {id: TS15, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java:76, anchor: "### TS15 ", cert: n/a, basis: "re-raise of TS9: initEdgeIndexTest opens 1000 transactions per method for 157 methods, and DR-M5 withdrew the item-4 CI gate the deferral was parked against"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS10 [should-fix] `dollarNameOnEntityBackedResultResolvesToNullWithoutTouchingTheRecord` cannot witness the property it is named for

`SQLSuffixIdentifierTest.java:148`. The method name and its Javadoc both promise that the
`isProjection()` gate keeps the `$`-branch from dispatching to the record. The body asserts three
things: `record.isProjection()` is false, `suffix.execute(...)` does not throw, and it returns null.
Deleting the gate changes none of them.

Trace the ungated path against this fixture. `ResultInternal.hasProperty` checks `content` first,
then `identifiable instanceof Entity entity` and forwards to `entity.hasProperty(propName)`. The test
builds `new ResultInternal(session, entity)` over a freshly created `SuffixDollarEntity`, so an
ungated probe lands on that Entity branch. `hasProperty` never validates the name, so it returns
false silently, the `$` branch falls through to metadata and temporary properties exactly as before,
and the resolver still returns null.

The branch the Javadoc names as the real cost is the third one, `loadLazyAndHasProperty` — the
lazily loaded RID-only `Result` whose `identifiable` is not an `Entity`. That is the fixture the
guard exists for, and the test does not build it.

**Failure scenario.** Someone simplifies the `$` branch in `SQLSuffixIdentifier` by dropping
`iCurrentRecord.isProjection() &&` from the condition, on the reasoning that `hasProperty` is
already safe on any result shape. Every test in `SQLSuffixIdentifierTest` stays green, this one
included. The regression surfaces only as a storage read per row on the MATCH path — the cost step 6
added the gate to avoid — and nothing in the tree names it.

**Suggestion.** Build the shape the gate protects: a `ResultInternal` over a bare `RID` or
`Identifiable` rather than a loaded `Entity`, so an ungated probe reaches `loadLazyAndHasProperty`.
A spy with `verify(record, never()).hasProperty(any())` also works and is cheaper to write. If
neither fits, rename to `dollarNameOnEntityBackedResultResolvesToNullWithoutThrowing` and drop the
no-dispatch claim from the Javadoc, so the file stops recording a guarantee no test holds.

### TS11 [should-fix] `downstreamLimitZeroStillCapturesSourcePlan` documents a path it no longer runs

`YTDBQueryMetricsStrategyTest.java:571`, with its explanatory comment at `:564`:

> A downstream limit(0) does NOT prevent the source step from running: the YTDBGraphStep source
> supplier executes its query as soon as the traversal is iterated, before RangeGlobalStep can
> short-circuit, so a non-null scan plan is still captured.

Neither step named there survives translation. `RangeGlobalStepRecogniser` accepts `limit(n)` and
sets `SQLLimit` on the walk, and `HasStepRecogniser` folds `hasLabel`, so
`g.V().hasLabel("person").limit(0)` compiles to a single MATCH plan behind one boundary step. There
is no `YTDBGraphStep` source supplier and no `RangeGlobalStep` left to race it. The assertion still
passes, because `capturedExecutionPlan()` reads the boundary step's plan whether or not the plan
yields rows — so the test holds while the ordering property it was written to pin goes unexercised.

This is the hazard the track's own T3 clause identified in this file: "none of them does today, so a
flip of the default silently re-points the assertion." Steps 4 and 5 pinned three plan-capture
scenarios (`:276`, `:329`, `:394`) and the cache-replay pair pins itself through
`withResultCacheAndTranslator`. Two were left out. `executionPlanReadableInsideCallbackAfterResultSetClosed`
(`:596`) is the milder case — its prose claims nothing path-specific — but this one carries a written
mechanism for the path it does not take.

**Failure scenario.** A reader triaging a red suite reaches this test, reads the comment, and
concludes the failure is in `YTDBGraphStep`'s source supplier or in step ordering against
`RangeGlobalStep`. Both are absent from the plan actually under test. The same comment also makes the
scenario read as native-path coverage, so a future change that stops capturing plans on the native
by-id/scan path looks covered here and is not.

**Suggestion.** Pin the switch the way the three siblings do (`final var restoreTranslator =
setTranslatorEnabled(true);` around the traversal), and rewrite the comment to name the boundary step
as the source. If the native ordering property is worth keeping, add the `setTranslatorEnabled(false)`
sibling that actually exercises `YTDBGraphStep` and `RangeGlobalStep`.

### TS12 [should-fix] The `getSubSteps()` plan walk landed five times across four test classes

This track introduced the sub-step introspection the walks read, and shipped a private copy of the
walk in every class that needed one:

- `MatchStatementExecutionTest.java:2453` `containsStepOfType(ExecutionStep, Class)` and `:2470`
  `countStepsOfType(List<ExecutionStep>, Class)`
- `HashJoinPlannerIntegrationTest.java:377` and `:393` — byte-identical to the pair above, verified
  by diff
- `GremlinToMatchSmokeTest.java:736` `containsStepOfType(List<ExecutionStep>, Class)`
- `YTDBQueryMetricsStrategyTest.java:1733` `findStepOfType(List<ExecutionStep>, Class)`, beside the
  pre-existing `containsStepOfType` at `:1718`

All five encode the same DR-M3 decision: recurse `getSubSteps()` and never `getSubExecutionPlans()`,
because publishing through both would double-count. Nothing in any of the five files records that
they are one contract in five places. Two more pre-existing walks in the tree —
`CommandExecutorSQLSelectTest.indexUsages` and `BaseDBJUnit5Test.indexesUsed` — recurse both
accessors, so the tree already holds two incompatible conventions for the same traversal.

**Failure scenario.** Later work makes `YTDBGraphQuery.usedIndexes` MATCH-aware, which the track
records under `## Surprises & Discoveries` as separate unclaimed work. The natural implementation
publishes the nested plan through `getSubExecutionPlans()` as well, reversing the half of DR-M3 that
kept it empty. The tally assertions break as arithmetic: `countStepsOfType(steps,
FetchFromClassExecutionStep.class)` returns 4 where `HashJoinPlannerIntegrationTest:2112` expects 2,
and 6 where `:2168` expects 3, with `MatchStatementExecutionTest`'s `prefetchCount` comparison
failing the same way. Whoever fixes the first file has no signal that three others carry the same
walk, and the two copies that are byte-identical today drift apart silently.

**Suggestion.** Give the walk one home and have the four classes call it — a small package-private
helper under `core/src/test/java/com/jetbrains/youtrackdb/internal/` next to `DbTestBase`, carrying
`containsStepOfType`, `countStepsOfType`, and `findStepOfType` over `List<ExecutionStep>`, with the
"`getSubSteps()` only, per DR-M3" reasoning stated once in its Javadoc. The single-step
`containsStepOfType(ExecutionStep, …)` overload the two integration classes use is a one-line
adapter over the list form.

### TS13 [suggestion] The by-id scenario runs two source-path contracts in one method

`YTDBQueryMetricsStrategyTest.java:394`,
`byIdLookupSurfacesRidFetchPlanWhenTranslatedAndNoPlanWhenNative`, is 66 lines carrying two complete
arrange-act-assert cycles: a translated pass asserting a RID-fetch plan inside the prefetch sub-plan
(`:404`–`:438`), then `listener.reset()`, a re-registration on the transaction, and a native pass
asserting a null plan (`:440`–`:459`). It is the only test in the class that covers two source paths
in one method; the neighbouring cache-replay pair splits them, and the comment at `:618` states the
class's reasoning for doing so.

The two arms are coupled through one mutable `RememberingListener`. `queryFinished` (`:1694`) only
overwrites `planStepsInCallback` and `planPrettyInCallback` when the captured plan is non-null, so
the native arm — whose plan is null by contract — inherits whatever the translated arm left there
unless the explicit `reset()` at `:440` runs first. Today it does, and the native arm asserts nothing
on those fields, so the coupling is latent rather than live.

**Failure scenario.** The native arm grows an assertion on `planStepsInCallback` (say, that the
native path leaves no steps behind), and the `reset()` call is moved or dropped during an unrelated
edit. The new assertion then reads the translated arm's captured steps and reports the native path as
carrying a MATCH plan. Separately, any failure in the translated arm ends the method, so the native
contract is never checked on that run, and the failure report names a method whose title covers both
contracts.

**Suggestion.** Split at `:440` into `byIdLookupTranslated_surfacesRidFetchPlan` and
`byIdLookupNative_surfacesNoPlan`, each with its own listener. The shared preamble is three lines
(`personId` lookup plus listener registration) and the shared prose comment can stay above the pair.

### TS14 [suggestion] The raw-entity reader is still two names and two bodies

The track file assigns this to the track-level pass under `## Surprises & Discoveries`: "The
raw-entity reader is still duplicated across the two boundary test classes under two names with two
bodies; the track-level pass owns folding it in." It is unchanged at HEAD —
`YTDBMatchPlanStepTest.java:1553` `assertRawEntityOf` and `MultiPlanMatchStepTest.java:1224`
`rawEntityOf`, identical apart from the name and a reworded Javadoc.

`freshTraversal` is in the same state: `YTDBMatchPlanStepTest.java:1567` and
`MultiPlanMatchStepTest.java:1238` are identical once comments are stripped, verified by diff. Under
the division the track states — `BoundaryStepTestSupport` drives a step, `ReplayablePlanFixture`
builds the plan under one — `freshTraversal` builds the traversal a step is constructed against and
belongs with the fixture, and the raw-entity reader interprets what a driven step emitted and belongs
with the drive helpers. A third case sits just inside the line: `MultiPlanMatchStepTest.java:1070`
`nextPayload` is the single-pull sibling of `drainPayloads` and repeats its cast rationale
("reading a SCALAR count cell through the typed `get()` would insert a cast the aggregate payload
cannot satisfy") word for word in a second place.

**Failure scenario.** `YTDBElementImpl.fastPathEntity` is renamed or moved during a Gremlin-wrapper
change. Both readers fail at runtime with `AssertionError: Failed to read fastPathEntity via
reflection`, and both need the same edit. Whoever greps for `fastPathEntity` finds them; whoever
greps for `assertRawEntityOf` — the name in the failing stack trace from the class they were working
in — finds one. The pair already demonstrates the drift: the two Javadocs describe the same helper in
different words, and only one explains its `assert` prefix.

**Suggestion.** Move both into `BoundaryStepTestSupport` under one name (`rawEntityOf` reads better
against the `assertThat(rawEntityOf(x)).isSameAs(y)` call sites, and the type check is a precondition
rather than the assertion) and move `freshTraversal` there too. `nextPayload` can stay where it is if
it drops the duplicated rationale in favour of a pointer to `drainPayloads`.

### TS15 [suggestion] Re-raise of TS9: the 1000-transaction fixture, with its deferral target withdrawn

Re-raised from the step 3 pass, where it was accepted as a suggestion and deliberately not fixed.
`MatchStatementExecutionTest.initEdgeIndexTest` (`:76`) still opens and commits one transaction per
vertex:

```java
var nodes = 1000;
for (var i = 0; i < nodes; i++) {
  session.begin();
  var doc = session.newVertex("IndexedVertex");
  doc.setProperty("uid", i);
  session.commit();
}
```

Two facts moved since step 3. The class is now at 157 `@Test` methods, four added by this track, and
`beforeTest` at `:32` overrides `DbTestBase`'s `@Before`, so the loop runs per method — 157,000
commits per class run. And the disposition recorded for TS9 ("It lands on the item-4 CI-gate work,
where the class runtime becomes recurrent cost") now points at nothing: DR-M5 records that the
`maven-pipeline.yml` change was reverted, Plan of Work item 4 is unrealized, and the CI gate the
deferral was parked against does not exist.

One argument for the fix got stronger. The same method already batches its 200 `IndexedEdge` creates
inside a single `session.begin()`/`session.commit()` pair immediately below the vertex loop, so the
one-transaction shape is proven inside the method itself, not only in
`MatchStatementExecutionHeavyTest:36`.

**Failure scenario.** The class keeps accruing tests — this track added four, and it is the natural
home for MATCH planner coverage — and each one costs another 1000 commits of setup. Nothing in the
file marks the fixture as the expensive part, so the next author adding a MATCH test pays the toll
without knowing why the class is slow, and the wall-clock cost lands wherever the branch's suite
eventually runs.

**Placement.** This belongs to a follow-up, not to Track 10. The fix changes setup commit
granularity for all 157 methods, so it needs a full-class run to confirm nothing depends on the
per-vertex boundaries, and this track's remaining budget is the track review. It also no longer has a
gating dependency now that item 4 is withdrawn, which makes it freestanding rather than blocked.

**Suggestion.** Roster it against Track 9 or a follow-up track: fold the vertex loop into one
`session.executeInTx`, mirroring `MatchStatementExecutionHeavyTest:36` and the edge loop directly
below it, then run the full class.

## Evidence base
