<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: BG35, sev: should-fix, loc: RangeGlobalStepRecogniser.java:145-190, anchor: "### BG35 ", cert: C1, basis: "a slice after group()/groupCount() slices GROUP BY rows, not the single emitted map; wrong map contents, and non-empty where native is empty"}
  - {id: BG36, sev: should-fix, loc: OrderGlobalStepRecogniser.java:27-75, anchor: "### BG36 ", cert: C2, basis: "order().by(k) after a grouping terminator commits a pre-group IS DEFINED conjunct and an ORDER BY, where native drops the map and returns nothing"}
  - {id: BG37, sev: suggestion, loc: GremlinAggregateAssembler.java:147-149, anchor: "### BG37 ", cert: C3, basis: "configurePropertyAggregate is the one assembler without the new hasGrouping gate; protected only by lastPropertyProjection being nulled elsewhere"}
  - {id: BG38, sev: suggestion, loc: GremlinStepWalker.java:873-875, anchor: "### BG38 ", cert: C4, basis: "assert-then-dereference on a path item's filter; with -ea off a filter-less item costs the translation silently instead of failing"}
evidence_base: {section: "## Evidence base", certs: 22, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED, anchor: "#### C12 "}
  - {id: C13, verdict: REFUTED, anchor: "#### C13 "}
  - {id: C14, verdict: REFUTED, anchor: "#### C14 "}
  - {id: C15, verdict: REFUTED, anchor: "#### C15 "}
  - {id: C16, verdict: REFUTED, anchor: "#### C16 "}
  - {id: C17, verdict: REFUTED, anchor: "#### C17 "}
  - {id: C18, verdict: REFUTED, anchor: "#### C18 "}
  - {id: C19, verdict: REFUTED, anchor: "#### C19 "}
  - {id: C20, verdict: REFUTED, anchor: "#### C20 "}
  - {id: C21, verdict: REFUTED, anchor: "#### C21 "}
  - {id: C22, verdict: NOTE, anchor: "#### C22 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG35 [should-fix] A slice captured after a grouping terminator slices the GROUP BY rows

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (lines 145-190)

**Issue**: `g.V().groupCount().by("name").limit(1)` returns a map with one entry where
native returns a map with every entry, and `g.V().group().by("name").skip(1)` returns a
non-empty map where native returns nothing. The grouping assemblers pin
`ResultShaping.NONE.withAccumulateMap(true)`, so the boundary drains every row the
statement returns into one map and emits one traverser. A statement-level `SKIP` / `LIMIT`
lands on those rows — the groups — while Gremlin applies the slice to the single map the
grouping terminator already emitted.

**Evidence**: `configureGroupCount` (`GremlinAggregateAssembler.java:214-237`) sets
`groupBy`, pins `BoundaryOutputType.MAP` and sets the accumulating shaping. The
`RangeGlobalStep` that follows reaches `recognize` and clears every guard: `hasUnionCarrier`
is false, `ctxHasSkipOrLimit` is false, `normalize(limit(1))` is not a no-op,
`dropsRowsOnAbsentProperty()` is false because the accumulating shaping leaves
`dropOnAbsent` unset (line 174), and `orderBy()` is null (line 181). The recogniser then
sets `SQLLimit`. With three distinct names the statement is `GROUP BY name RETURN key,
count(*) LIMIT 1`, which yields one group row, which `accumulateMap` folds into a
one-entry map. The `skip(1)` spelling diverges in the other direction: native skips the
one emitted traverser and returns nothing, while the statement's `SKIP 1` returns the
remaining group rows.

**Refutation considered**: three neighbouring gates cover the adjacent orderings and none
covers this one. `GremlinStepWalker.capturedCardinalityClause` refuses a step *after* a
captured clause. `GremlinAggregateAssembler.hasPreAggregateCardinalityClause` refuses a
clause *before* an aggregate. `hasGrouping` — added this track — refuses a *terminator*
after a grouping one. A slice after a grouping terminator falls between all three.
`DedupGlobalStepRecogniser` is the sibling that does hold the line, declining on
`boundaryOutputType() != BoundaryOutputType.ELEMENT` (lines 46 and 58), which is why
`g.V().group().by(k).dedup()` is safe and the slice is not. `ctx.groupBy()` has exactly one
reader in production, `hasGrouping` at `GremlinAggregateAssembler.java:56`.

**Suggestion**: decline on `ctx.groupBy() != null` in the single-plan branch, placed beside
the two existing guards at lines 174 and 181 so a normalised-away slice still rides
through. Pair it with an equivalence case over `groupCount().by(k)` plus `limit(1)` and
`skip(1)` on a three-name fixture; both spellings are discriminating there.

**Not executed.** The claim is read off the code path. The measurement that settles it is one
`assertEquivalent` pair in `ProjectionEquivalenceTest` beside `groupByMissingKey_hasNoNullBucket`.

### BG36 [should-fix] `order().by(key)` after a grouping terminator filters the rows that feed the grouping

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/OrderGlobalStepRecogniser.java` (lines 27-75)

**Issue**: `g.V().groupCount().by("name").order().by("age")` returns a map translated where
native returns nothing, and the map it returns is narrowed by a filter the user never
wrote. Native `order().by("age")` runs a `ValueTraversal` over the single emitted `Map`,
which has no `age` key, so the modulator is non-productive and the traverser is dropped.
The translation instead commits `age IS DEFINED` on the boundary alias — a pattern
conjunct, applied to the rows that feed the `GROUP BY` — and appends `ORDER BY <alias>.age`.

**Evidence**: the recogniser reads `hasUnionCarrier`, a null boundary and a second
`orderBy` (lines 36-47) and nothing else; it has no `groupBy` or `boundaryOutputType`
gate. The contribution loop added this track calls
`ByModulatorPresence.requireModulatedProperty(ctx, boundary, pair.getValue0())` at line 71,
which routes to `ctx.putAliasFilter(alias, WHERE.wrap(WHERE.isDefined(key)))`. That write
happens whatever the boundary output type is, so on a `MAP` boundary with an
`accumulateMap` shaping it removes rows from the grouping input. The conjunct is the same
mechanism `ProjectionEquivalenceTest.groupByMissingKey_hasNoNullBucket` relies on when the
`by(key)` belongs to the grouping terminator itself; here it belongs to a step that runs
after the grouping and has a different input.

**Refutation considered**: two escape routes were checked and neither closes the shape.
`DedupGlobalStepRecogniser`'s `boundaryOutputType() != ELEMENT` guard has no counterpart
here. And a grouped statement carrying `ORDER BY` on a non-grouped field might fail plan
build, in which case the whole walk declines and the arms agree — that is the one
condition under which the divergence does not surface, and it is not established either
way, so the conjunct half of the defect is what carries the finding. The bare `order()`
spelling is weaker: native raises a `ClassCastException` comparing `Map`s, so the
divergence there is a value against a throw rather than a wrong row set.

**Suggestion**: the same one-line `ctx.groupBy() != null` decline BG35 asks for, placed
before the comparator loop so no conjunct is committed on the way out. The two findings
share a fix site in spirit but not in file, so closing one does not close the other.

**Not executed.** Verification is an `assertEquivalent` DECLINED case plus a
`withTranslatorOff` pin that native returns nothing for the `by("age")` spelling.

### BG37 [suggestion] `configurePropertyAggregate` carries no `hasGrouping` gate and relies on a distant invariant

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (lines 147-149)

**Issue**: `configureCount`, `configureGroup` and `configureGroupCount` all gate on
`hasGrouping(ctx)`. `configurePropertyAggregate` — the `sum` / `min` / `max` / `mean`
assembler in the same class — does not, and it calls `ctx.setGroupBy(null)` and
`ctx.clearReturnProjection()`, which is exactly the clobber the gate exists to prevent.

**Evidence**: the shape is unreachable today, and only because both grouping assemblers
call `ctx.setLastPropertyProjection(null)` before returning (lines 204 and 232), so a
`sum()` reaching `configurePropertyAggregate` after a group finds `projection == null` and
declines on line 153. That leaves the safety of a four-line method resting on two writes
in two other methods, with no comment linking them. `hasGrouping`'s own Javadoc says "all
three decline", which reads as exhaustive over a class that has four assemblers.

**Refutation considered**: searched for a second writer of `groupBy` that leaves the
projection set — `setGroupBy` is called from `configureCount` (null),
`configurePropertyAggregate` (null), `configureGroup` (a clause) and `configureGroupCount`
(a clause), and the two that set a clause both null the projection. No reachable path
today.

**Suggestion**: add `|| hasGrouping(ctx)` to line 148 for symmetry with the other three,
and correct the `hasGrouping` Javadoc to say which assemblers read it and why the fourth
is protected differently.

### BG38 [suggestion] `bindPathItemConstraints` asserts a non-null filter and dereferences it on the next line

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 873-875)

**Issue**: `assert existing != null` is followed immediately by `existing.copy()`. With
assertions disabled the assert is a no-op and the next line throws
`NullPointerException`, which `GremlinToMatchStrategy.apply`'s `catch (RuntimeException)`
swallows into a decline. A front end that ever produced a filter-less path item would lose
translation for that whole shape with a DEBUG log line and no test failure — the opposite
of what the assert was written to achieve.

**Evidence**: every item the Gremlin path produces carries a filter today.
`MatchPatternBuilder.addEdge` builds `toFilter` unconditionally and calls
`pathItem.setFilter(toFilter)`; `MatchEdgePathItems.edgeMethodItem` and
`vertexMethodItem` both call `item.setFilter(...)`. `bindPathItemConstraints` runs only
over `ir.pattern()`, which those two paths are the sole producers of. So the invariant
holds and the assert never fires.

**Refutation considered**: checked whether the NPE could reach the user — it cannot;
`GremlinToMatchStrategy.apply` wraps the translation in `try` / `catch (RuntimeException)`
(lines 221-230), so the failure mode is a silent decline rather than an aborted query.
That is what makes this a suggestion rather than a should-fix.

**Suggestion**: keep the assert and make the fall-through explicit — `if (existing ==
null) { continue; }` after it, or hoist the null test into the `where == null && className
== null` short-circuit above. Either way a future filter-less item is skipped rather than
throwing through a catch that reports nothing.

## Evidence base

#### C1 A slice after a grouping terminator slices GROUP BY rows — CONFIRMED
Traced through `configureGroupCount` → `accumulateMap` shaping → `RangeGlobalStepRecogniser.recognize`; every guard on the path is inapplicable and `setLimit` runs. Backs BG35.

#### C2 `order().by(key)` after a grouping terminator commits a pre-group conjunct — CONFIRMED
`OrderGlobalStepRecogniser` has no `groupBy` / `boundaryOutputType` gate and line 71 writes the `IS DEFINED` conjunct unconditionally. Backs BG36.

#### C3 `configurePropertyAggregate` lacks the `hasGrouping` gate — CONFIRMED
Read off lines 147-155 against the other three assemblers; unreachable today, protected only by `setLastPropertyProjection(null)` in the two grouping assemblers. Backs BG37.

#### C4 Assert-then-dereference in `bindPathItemConstraints` — CONFIRMED
Lines 873-875; the NPE is caught by `GremlinToMatchStrategy.apply` and degrades to a decline. Backs BG38.

#### C5 `sum()` after `group()` clobbers the grouped plan — REFUTED
**Claim**: `configurePropertyAggregate` has no `hasGrouping` gate, so
`g.V().values("age").group().sum()` clears the group columns, nulls the `GROUP BY` and
returns a scalar where native raises on summing a `Map`.
**Check**: read `configureGroup` (`GremlinAggregateAssembler.java:196-209`) and
`configureGroupCount` (214-237). Both call `ctx.setLastPropertyProjection(null)` before
returning.
**Verdict**: REFUTED as a live defect. `configurePropertyAggregate` declines on
`projection == null` at line 153 before it writes anything. The fragility survives as
BG37, graded a suggestion.

#### C6 A `barrier()` swallowed inside a multi-step `has` run defeats the fold latch — REFUTED
**Claim**: `dispatchAll`'s new transparent-step detection compares `cursor.position()`
only around the loop-head `peek()`, so a `NoOpBarrierStep` consumed inside a recogniser's
`takeWhile` run would leave the latch armed and drop the range type guard —
`g.V().has("tag", x).barrier().has("name", gt(27))` would answer SQL ordering against
native's comparability rule.
**Check**: `HasStepRecogniser.recognize` takes exactly one step (`var step =
cursor.take()`, line 80); it does not `takeWhile` over a run of `HasStep`s. Traced the
three-iteration dispatch by hand: the barrier sits at the loop head on iteration three,
`peek()`'s `skipTransparent()` advances `position` from 2 to 3, the comparison fires and
`setAtTraversalStart(false)` runs before the second `has` is dispatched.
**Verdict**: REFUTED. `EdgeHopRecogniser` does use `takeWhile` over `HasStep`, but its
containers are edge-property filters that are never folded, so it passes
`rangeTypeGuard = true` unconditionally and a swallowed barrier changes nothing.

#### C7 A `$matched` back-reference can be evaluated before the alias it names — REFUTED
**Claim**: step 10's `LabelResolver` makes `where(P.neq("a"))` emit
`$matched.<alias>.@rid`, and the planner's root selection scores a filtered alias at
`classCount / 2` against an unfiltered one at `classCount + 1`. On the modern graph the
hop target (3) beats the origin (7), so the target roots the plan, `$matched.<origin>` is
unset at scan time, `SQLNeqOperator` over a null operand answers true, and a self-loop
target the predicate should exclude is kept.
**Check**: read `MatchExecutionPlanner.getDependencies` (line 5376). It builds a
per-alias dependency set from
`filter.getBaseExpression().getMatchPatternInvolvedAliases()`, and
`getTopologicalSortedSchedule` (lines 2262-2296) will only root at an alias whose
`remainingDependencies` entry is empty. The target therefore cannot be rooted before the
origin, whatever the estimate says.
**Verdict**: REFUTED. Noted separately: `dependsOnExecutionContext` (line 879) gates
prefetching only, so the dependency map is the sole mechanism — but it is sufficient.
The test that covers the shape, `postHopBackReferenceToOriginLabel_matchesNative`, states
its vacuity argument backwards for `neq` (an accessor reading nothing keeps every row, not
none) and pins no root, which is a test-quality observation rather than a defect.

#### C8 A path-item class bound by the new pass is matched exactly, not polymorphically — REFUTED
**Claim**: `bindPathItemConstraints` binds a hop target's registered class onto the path
item, so post-hop `hasLabel(Person)` would exclude an `Employee` subclass instance that
native polymorphic mode keeps.
**Check**: `MatchEdgeTraverser.matchesClassCached` (line 511) resolves the candidate's
schema class and answers `clazz.isSubClassOf(className)`.
**Verdict**: REFUTED. Under non-polymorphic mode `HasStepRecogniser` additionally emits
`@class = 'L'` into the alias `WHERE`, which is the narrower of the two and preserves leaf
exactness.

#### C9 A bound path-item class is misread as the source constraint under reverse traversal — REFUTED
**Claim**: the planner may schedule an edge backwards, and a reverse traverser reading
`item.getFilter()` would take the target's newly-bound class and `WHERE` as constraints on
the syntactic source.
**Check**: `MatchReverseEdgeTraverser` overrides `targetClassName`, `targetRid` and
`getTargetFilter` to read `edge.getLeftClass()` / `getLeftRid()` / `getLeftFilter()`
(lines 51-68), never the item.
**Verdict**: REFUTED. The bound item filter is inert on the reverse path, and the target's
constraint still reaches the planner through `MatchPlanInputs.aliasFilters`, which
`bindPathItemConstraints` leaves populated.

#### C10 `SQLFunctionMean` never behaves as an aggregate — REFUTED
**Claim**: the class does not override `aggregateResults()`, so a single-argument
`mean(field)` would be evaluated per row instead of accumulating.
**Check**: `SQLFunctionMathAbstract.aggregateResults()` returns
`configuredParameters.length == 1`, and `SQLFunctionMean` extends it.
**Verdict**: REFUTED. The inherited implementation is the one `SQLFunctionAverage`
declares redundantly.

#### C11 The group value side still emits `avg` for `mean()` — REFUTED
**Claim**: step 10 repointed `PropertyAggregateStepRecogniser` at the new `mean` SQL
function but changed only a comment in `GremlinAggregateAssembler.resolveGroupValue`, so
`group().by(k).by(values("age").mean())` would keep integer division.
**Check**: `ByModulatorTranslator.ValueAccumulator.AggregateFunction` is
`{COUNT, SUM, MIN, MAX, MEAN}` (line 55) and `MeanGlobalStep` maps to `MEAN` (lines
209-211); `MatchProjectionBuilder.propertyAggregate` lowercases the enum name to `mean`.
**Verdict**: REFUTED. Both aggregate paths name the same SQL function.

#### C12 The guard's comparability-block names do not match what `type()` reports — REFUTED
**Claim**: `comparabilityBlock` emits `PropertyType` constant names, while
`PropertyTypeInternal`'s constructor takes a display name (`"Integer"`), so
`key.type() IN ['INTEGER', …]` could match nothing and silently exclude every row.
**Check**: `SQLMethodType.execute` returns `PropertyTypeInternal.getTypeByValue(value).toString()`, and
`PropertyTypeInternal` declares no `toString()` override, so the enum's `name()` is
returned. Confirmed every emitted name exists: `BYTE`, `SHORT`, `INTEGER`, `LONG`,
`FLOAT`, `DOUBLE`, `DECIMAL`, `STRING`, `BOOLEAN`, `DATE`, `DATETIME`.
**Verdict**: REFUTED.

#### C13 `SQLMatchFilter.setClassName` writes where `getClassName` cannot read it back — REFUTED
**Claim**: the new setter appends a class-only item at the end of `items`, while
`getClassName` might read only the first item.
**Check**: `getClassName` scans `items` for the first entry with a non-null `className`,
which is the same shape `setFilter` / `getFilter` and `setAlias` / `getAlias` use, and the
same representation `fromAliasAndClass` produces.
**Verdict**: REFUTED.

#### C14 The fingerprint's inline-literal render breaks value-independence for text predicates — REFUTED
**Claim**: step 10 switched `appendAliasFilters` from `toGenericStatement` to
`toString(NO_PARAMS, …)`, and `MatchWhereBuilder`'s prefix-range and `startsWith` /
`endsWith` / `containsText` builders take inline `stringExpression(...)` operands, so a
`TextP.startingWith("abc")` filter would put the user's string into the plan-cache key and
split the cache per value.
**Check**: every value on the Gremlin path routes through
`GremlinPredicateAdapter.valueExpression(value, paramSink)` (line 537), which binds an
`SQLPositionalParameter` when a sink is present — `translateText` (448-457),
`startsWithFilter` (473-480), `startsWithRange` (499-509), `translateRegex` (524-529) and
`translateContains` (404-422) all pass the sink through.
**Verdict**: REFUTED. The Surprises-log claim that `WHERE.classEquals` and the type guard
are the only inline-literal producers on that path holds.

#### C15 `ByModulatorPresence`'s shared static `MatchWhereBuilder` leaks state between calls — REFUTED
**Claim**: `private static final MatchWhereBuilder WHERE` is shared across every walk and
the Javadoc's "the builder is stateless" claim is unverified.
**Check**: read `MatchWhereBuilder` in full. Every method allocates its condition nodes
locally; the class declares no instance fields.
**Verdict**: REFUTED. Two other recognisers already hold the same shared instance.

#### C16 `SelectStepRecogniser`'s per-label modulator index can run off the list — REFUTED
**Claim**: the new `requireModulatedProperty` call inside the label loop indexes
`modulators.get(i)`, and Gremlin cycles a single `by(...)` across several labels.
**Check**: `ByModulatorTranslator.exactModulatorCount(labels.size(), modulators.size())`
gates the loop and declines a mismatch.
**Verdict**: REFUTED.

#### C17 A mid-loop decline in the select recognisers leaves a committed conjunct behind — REFUTED
**Claim**: `SelectStepRecogniser` clears the return projection and commits `IS DEFINED`
conjuncts inside the label loop, so a later label that fails to resolve leaves the earlier
conjuncts on the context.
**Check**: a `DECLINE` from any recogniser makes `dispatchAll` return false and `walk`
return null, so the whole `WalkerContext` is discarded; inside a captured child the
connective reads `adapter.outcome()` first and commits nothing.
**Verdict**: REFUTED. The mutation is unobservable, which is why the pattern is tolerated
elsewhere in the package.

#### C18 The new `ShapeClassifier` aggregate names do not match registered functions — REFUTED
**Claim**: `case "mean", "median", "mode", "variance", "stddev", "percentile"` could name
a function the factory registers under a different literal, leaving the arm dead and the
projection back on the per-record `RECORD` path.
**Check**: grepped the `NAME` constants — `SQLFunctionMean` `"mean"`, `SQLFunctionMedian`
`"median"`, `SQLFunctionMode` `"mode"`, `SQLFunctionVariance` `"variance"`,
`SQLFunctionStandardDeviation` `"stddev"`, `SQLFunctionPercentile` `"percentile"`.
**Verdict**: REFUTED. All six match.

#### C19 The union fork's child-scope boundary is off by one — REFUTED
**Claim**: `walkFork` passes `prefix.size()`, and the latch term is
`cursor.position() < childScopeBoundary` evaluated after the head is consumed, so either
the prefix's last `has` reads unfolded or the arm's first `has` reads folded.
**Check**: `cursor.position()` after a consume is the index of the next head. Consuming
the prefix's last step leaves `position == prefix.size()`, so the term is false exactly
for the arm's first step, and true for every prefix step after the `GraphStep`. Traced for
`prefix.size()` of 1 and 3.
**Verdict**: REFUTED. The boundary lands on the seam.

#### C20 The single-plan cardinality gate misfires inside a captured child — REFUTED
**Claim**: `capturedCardinalityClause(ctx)` reads `skip()` / `limit()` /
`returnDistinct()`, which `SubTraversalPredicateAdapter` delegates to the parent, so a
child sub-walk running after the parent captured a clause would refuse every child step.
**Check**: the clause is captured by a step earlier in the parent's list than the
connective, and the parent's own dispatch refuses the connective's recogniser first —
none of the connective recognisers is in `POST_CARDINALITY_RECOGNISERS`. The child walk is
never entered.
**Verdict**: REFUTED, unreachable. Worth keeping in mind that the adapter answers
`dropsRowsOnAbsentProperty()` with a hard false while delegating the other three reads, an
asymmetry that only stays harmless because of this unreachability.

#### C21 A filter-less path item reaches `bindPathItemConstraints` — REFUTED as a live defect
**Claim**: the pass dereferences `item.getFilter()` behind an `assert`, so some
construction path could NPE it.
**Check**: `MatchPatternBuilder.addEdge` always calls `pathItem.setFilter(toFilter)`;
`MatchEdgePathItems.edgeMethodItem` and `vertexMethodItem` always call `item.setFilter`.
Those are the only producers of items in a pattern the Gremlin walker builds.
**Verdict**: REFUTED as reachable. The robustness half survives as BG38.

#### C22 Concurrency triage gap here — NOTE
`RepeatDeclineStrategy.VetoedStrategies` wraps the process-wide
`TraversalStrategies.GlobalCache` instance and forwards `addStrategies` / `removeStrategies`
verbatim to its `LinkedHashSet`; the class Javadoc reasons explicitly about
`ConcurrentModificationException` in other threads compiling against the same graph class.
That is shared mutable state on a hot path, and `review-concurrency` was not triaged onto
this track. Flagging only — no interleaving analysis performed here.
