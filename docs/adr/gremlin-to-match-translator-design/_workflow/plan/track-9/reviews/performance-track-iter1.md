<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: PF4, sev: should-fix, loc: GremlinPredicateAdapter.java:339-357, anchor: "### PF4 ", cert: C1, basis: "per-record type guard costs ~10 allocations + 7 string-literal re-evaluations against a one-compare predicate, and is emitted twice for between/inside"}
  - {id: PF5, sev: should-fix, loc: RangeGlobalStepRecogniser.java:181, anchor: "### PF5 ", cert: C2, basis: "blanket ORDER-BY decline trades MATCH's bounded top-N heap for native's unbounded CollectingBarrierStep: O(n) resident rows becomes O(classCount)"}
  - {id: PF6, sev: should-fix, loc: SQLWhereClause.java:352-385, anchor: "### PF6 ", cert: C3, basis: "IS DEFINED has no estimator arm, so every presence conjunct this track newly emits pins its alias at classCount/2 and can steal the root slot"}
  - {id: PF7, sev: suggestion, loc: RangeGlobalStepRecogniser.java:174, anchor: "### PF7 ", cert: C4, basis: "values(k).limit(n) declines whole; the narrower pattern-conjunct fix is blocked only by PF6's estimator gap"}
  - {id: PF8, sev: should-fix, loc: ShapeClassifier.java:987, anchor: "### PF8 ", cert: C5, basis: "Gremlin mean() moves from replayable AGGREGATE_AVG to K0_NONE, losing per-record cache reconciliation on a shape that had it"}
  - {id: PF9, sev: suggestion, loc: RepeatDeclineStrategy.java:163, anchor: "### PF9 ", cert: C6, basis: "the veto reaches every repeat depth where the measured stall was times(8); shallow rooted repeats lose index-backed MATCH"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 6}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF4 [should-fix] The per-record type guard costs an order of magnitude more than the comparison it protects, and a two-bound predicate pays it twice

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 339-357, 370-393), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java` (lines 127-140)

**Issue.** `translateCompare` emits `WHERE.and(WHERE.typeIn(key, names), comparison)` for every `gt`/`gte`/`lt`/`lte` in an unfolded position. The guard's right-hand side is an `SQLCollection` of quoted string literals, and `SQLInCondition.evaluate` re-derives it from the AST on **every record**: `evaluateRight` calls `SQLCollection.execute`, which allocates a fresh `ArrayList` and evaluates each element expression, and each element expression takes `SQLBaseExpression`'s string branch — `StringSerializerHelper.decode(string.substring(1, len - 1))` — so one `String.substring` allocation per name per record. For a numeric literal that list holds seven names (`NUMERIC_TYPE_NAMES`), giving seven substrings plus the list plus its backing array on each candidate row. Because the returned collection is a `List` rather than a `Set`, `evaluateExpression` skips its `Set.contains` fast path and walks the seven entries through `QueryOperatorEquals.equals`, re-testing `MultiValue.isMultiValue(iLeft)` on each pass.

The predicate the guard protects is a single property read and one operator call. The guard sits first in the conjunction, so it runs on every candidate row and the cheap comparison runs only on survivors.

A two-bound predicate pays it twice. `P.between(a, b)` and `P.inside(a, b)` arrive as an `AndP`, `combine` recurses into each bound with `rangeTypeGuard` still set, and each bound gets its own `translateCompare` call and its own guard. `between(0, 100)` on a numeric key therefore compiles to `age.type() IN [7 names] AND age >= ? AND age.type() IN [7 names] AND age <= ?`. Two spelled-out `has("age", gt(x)).has("age", lt(y))` containers duplicate it the same way.

Breadth: `rangeTypeGuard` is `!ctx.atTraversalStart()`, so a leading filter folded into `YTDBGraphStep` carries no guard, while every range filter after a hop and every edge-property range filter (`EdgeHopRecogniser.java:122` passes `true` unconditionally) carries one. `GremlinStepWalker.bindPathItemConstraints` then copies the boundary alias's filter onto the path item that targets it, so a hop-target guard is evaluated per candidate during edge traversal.

**Evidence.** Cost trace and scale check in `#### C1`.

**Impact.** On `g.V().out("knows").has("age", gt(30))` over a 1M-vertex `Person` class with average out-degree 30, the guard runs on roughly 30M candidates. At the traced ~10 short-lived allocations per evaluation that is ~300M allocations the untranslated arm does not make, plus the seven-way `QueryOperatorEquals` walk per candidate. On `between` the figures double. The absolute per-evaluation cost is small; the multiplier is the fan-out.

**Suggestion.** Three independent levers, in descending value:

1. **Elide the guard when the schema settles the type.** The guard exists because a schema-less or undeclared key can hold mixed runtime types. When the property is declared with a concrete type on a resolvable class, the guard is statically true or statically false, so the recogniser can drop it (or fold the whole comparison to false) at compile time. The context already reaches the schema — `WalkerContext.isDeclaredStringProperty` (line 412) resolves `schema.getClass(className)` and reads the declared property — so this needs a wider accessor on `PropertyTypeGate` rather than new plumbing. Any schema-ful workload loses the per-record cost entirely.
2. **Emit one guard per container instead of one per bound.** Hoist the guard out of `translateCompare` into `combine` when `and == true`, or dedupe identical conjuncts before wrapping. Halves the cost on `between` / `inside`.
3. **Hoist the early-calculable `IN` right side out of the per-record loop.** `SQLInCondition.evaluateRight` re-executes a literal-only collection on every row. The `isEarlyCalculated` primitive the estimator already uses (`SQLWhereClause.java:397`) would let the condition cache the resolved collection — ideally as a `Set`, which also unlocks `evaluateExpression`'s `Set.contains` fast path. That is an executor-side change and it pays back on every SQL `IN [literals]` in the product, not only on this guard.

Reordering the conjunction so the cheap comparison runs first is **not** available: `## Surprises & Discoveries` records `PropertyTypeInternal.castComparableNumber` throwing `ClassCastException: Long cannot be cast to BigDecimal` out of `SQLGeOperator.execute` on the unguarded path, so guard-first is load-bearing.

### PF5 [should-fix] Declining every slice behind a captured ORDER BY trades MATCH's bounded top-N heap for native's unbounded materialisation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (line 181)

**Issue.** Step 10 declines a real slice once `ctx.orderBy() != null`, closing the measured tie-group divergence. The decline is all-or-nothing under D3, so `g.V().order().by(k).limit(n)` gives up its whole plan and runs on the native traverser pipeline. The two arms differ in memory shape, not only in where the sort runs.

The translated arm bounds the sort. Both planner paths that chain an `OrderByStep` derive `maxResults = skip + limit` and pass it in (`MatchExecutionPlanner.java:715-727`, `SelectExecutionPlanner.java:2127-2159`), and `OrderByStep` then keeps a min-heap of that size instead of collecting every row — its own Javadoc states the reduction as `O(|all results|)` to `O(maxResults)`.

The native arm does not bound it. TinkerPop's `OrderGlobalStep` extends `CollectingBarrierStep`, which drains its input into a `TraverserSet` before emitting anything, and the one strategy that would push a following limit into it, `OrderLimitStrategy`, returns immediately unless the traversal is on a `GraphComputer` — verified by disassembling the fork's `OrderLimitStrategy.apply`, whose first instructions are `TraversalHelper.onGraphComputer(traversal)` followed by `ifne 8 / return`. For an embedded OLTP traversal the step's `limit` field stays unset.

**Evidence.** `#### C2`.

**Impact.** A top-N over a sorted property changes from `n` resident rows to `classCount` resident traversers plus a full sort of them. On 1M vertices with `limit(10)` that is 1M traversers held live where the plan held ten — a GC-pressure and peak-heap regression on a common shape, not only a CPU one.

**Suggestion.** Narrow the decline to the case the divergence needs. The measured failure is a bound cutting inside a tie group; a sort key with no ties cannot produce one. When the sort key is a property carrying a unique index, and `ByModulatorPresence` has already contributed `key IS DEFINED` so absent-key rows are excluded, the surviving values are pairwise distinct, the order is total, and the translated cut equals native's. The schema is reachable from `WalkerContext` (see PF4), so the check is local. That carve-out recovers exactly the indexed top-N where the loss is largest.

The class Javadoc's alternative carve-out — bare `order()`, provably total because it keys on `@rid` — no longer holds as written: the same step taught `resolveSortItem` to key a bare `order()` on `ctx.lastPropertyProjection()` when one exists, so `g.V().values("name").order()` sorts a property that can tie. Anyone acting on that paragraph should re-derive it against the new `resolveSortItem`.

### PF6 [should-fix] `IS DEFINED` has no selectivity estimator, and this track multiplied the number of aliases that carry one

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` (lines 352-385), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ByModulatorPresence.java` (line 81)

**Issue.** `ByModulatorPresence.contribute` writes `key IS DEFINED` into an alias's filter map. `MatchExecutionPlanner.estimateRootEntries` (line 6408-6420) scores an unfiltered alias at `classCount + 1` and a filtered one at `min(filter.estimate(...), classCount)`, and `SQLWhereClause.estimate` returns `classCount / 2` for this conjunct on both of its exits: below `THRESHOLD` it short-circuits to `classCount / 2` outright, and above it `estimatePredicateSelectivity` has arms for `SQLBinaryCondition`, `SQLBetweenCondition`, `SQLIsNullCondition`, `SQLIsNotNullCondition`, `SQLInCondition` and `SQLNotBlock` but none for `SQLIsDefinedCondition`, so `anyEstimated` stays false, the histogram path returns -1, and the method falls back to the same `classCount / 2`. A presence-only alias therefore always reads as twice as selective as an unfiltered one of the same class.

`ByModulatorPresence` is new in this track and is reached from nine sites: `OrderGlobalStepRecogniser` per comparator, `SelectOneStepRecogniser` and `SelectStepRecogniser` per `by(key)`, `GremlinAggregateAssembler.configureCount` / `configurePropertyAggregate` / `requireGroupKeyPresent`, and `GremlinProjectionAssembler.configureSingleKeyValues` on the captured-child path. So `g.V().order().by("age")`, `g.V().groupCount().by("age")` and `g.V().values("age").sum()` all now attach the conjunct where none existed before. The `@implNote` on `ByModulatorPresence` records the distortion and assigns it to the executor; the decision to emit the conjunct across this surface is what makes the assignment load-bearing.

**Evidence.** `#### C3`.

**Impact.** On a single-alias pattern the distortion is inert — one root candidate. On a multi-alias pattern the presence-filtered alias wins the root slot against an unfiltered sibling of the same class, so `g.V().out("knows").order().by("age")` roots at the hop target and schedules the edge backwards. The same map feeds the edge-cost model's `sourceRows` (`MatchExecutionPlanner.java:2414`) and the hash-join forecast, so the plan shape moves rather than only the root. YTDB's O(1) link traversal bounds the damage on the direction flip itself; the forecast and ordering effects are the part that scales.

**Suggestion.** Add an `SQLIsDefinedCondition` arm to `estimatePredicateSelectivity`, mirroring the `SQLIsNotNullCondition` arm three lines above it and delegating to the existing `SelectivityEstimator.estimateIsNotNull(stats, histogram)` (line 452). `IS DEFINED` is slightly weaker than `IS NOT NULL` — a stored literal null is defined — so the borrowed estimate is conservative in the safe direction. This is one `case`-shaped addition against a primitive that already exists, and it also unblocks PF7.

### PF7 [suggestion] The drop-on-absent slice decline is held open only by PF6

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java` (line 174)

**Issue.** `values(k).limit(n)` declines whole once `ctx.dropsRowsOnAbsentProperty()` holds, because the drop rides post-plan shaping and a statement-level `LIMIT` counts rows the drop has not removed yet. The recogniser's Javadoc names the narrower repair and rejects it: "making the projection contribute a pattern conjunct instead would change every `values(k)` plan's root-selection estimate."

That rejection rests entirely on PF6. The aggregate path already takes the conjunct route (`GremlinAggregateAssembler.configureCount` calls `requireProjectedProperty`), and its own Javadoc says so — "The aggregate path needs no guard at all, writing its presence conjunct into the pattern." So the mechanism works; only the estimate distortion argues against extending it to the projection path.

**Evidence.** `#### C4`.

**Impact.** `values(k).limit(n)` is a common spelling. The decline withdraws the prefix's hops and non-leading filters from the plan along with the slice, and gives up the pushed-down `LIMIT` that stops the scan inside the engine. The same shape behind an `ORDER BY` compounds with PF5.

**Suggestion.** Sequence this behind PF6 rather than in front of it. Once `IS DEFINED` estimates from index statistics, moving the main-line `values(k)` drop from shaping to a pattern conjunct costs no plan distortion, and the slice becomes translatable in both orderings. Re-measure the root-selection estimate on a `values(k)` plan before and after; the recogniser's rejection sentence should be updated or retired with the change.

### PF8 [should-fix] Gremlin `mean()` loses incremental cache reconciliation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (line 987), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PropertyAggregateStepRecogniser.java` (line 31)

**Issue.** Two changes compose into a caching regression. `PropertyAggregateStepRecogniser` re-points `MeanGlobalStep` from the SQL `avg` aggregate to the new SQL `mean`, correcting the integer-division answer (30 against 30.75). `ShapeClassifier.aggregateShapeForCall` then maps `mean` to `K0_NONE` alongside `median`, `mode`, `variance`, `stddev` and `percentile`, on the stated ground that `AggregateState` cannot replay it.

`AggregateState` can replay it. Its `AGGREGATE_AVG` arms are identical to `AGGREGATE_SUM`'s in `addContributor`, `removeContributor` and `updateContributor` (lines 302, 324, 346) — all three keep the per-RID value and mark the sum dirty — and the two kinds diverge only in `scalar()`, where `AGGREGATE_AVG` yields `computeAverage(sumAccumulator, count)`. `SQLFunctionMean.computeMean(sum, total)`, added by this same track, is the drop-in replacement for that one call.

**Evidence.** `#### C5`.

**Impact.** Per `CacheableShape`'s own Javadoc, a `K0_NONE` entry "serves cached reads only while no mutation has happened since it was populated, and re-executes after any tx-write." Before this track `g.V().values("age").mean()` emitted `avg(...)`, classified `AGGREGATE_AVG`, and reconciled record by record across writes. It now re-executes the full aggregate scan after any transaction commits anywhere in the database. On a read-heavy dashboard workload with a steady trickle of writes that is the difference between a delta apply and a full class scan per query.

**Suggestion.** Add an `AGGREGATE_MEAN` constant to `CacheableShape`, list it in `isAggregate()` and `emitsNoRow()` beside `AGGREGATE_AVG`, add it to the three `AGGREGATE_SUM, AGGREGATE_AVG` switch arms in `AggregateState`, and give it a `scalar()` arm that folds the sum and yields `SQLFunctionMean.computeMean(sumAccumulator, count)`. Leave `median` / `mode` / `variance` / `stddev` / `percentile` at `K0_NONE` — those genuinely have no running-scalar formulation, and the diff's comment is right about them. Keeping `mean` in their bucket is what costs a shape that had reconciliation.

### PF9 [suggestion] The repeat veto reaches every depth, where the measured stall was `times(8)`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategy.java` (line 163)

**Issue.** `apply` vetoes any traversal whose subtree carries a `RepeatStep` at all. The defect it closes is depth-driven: `repeat(__.out()).times(8)` on the grateful-dead fixture enumerates 2,505,037,961,767,380 paths. A `times(2)` or `times(3)` repeat unrolls to two or three hops and compiles to a two- or three-node MATCH pattern that the cost-based planner serves well, and it is now declined along with the deep case.

The information needed to narrow it is available where the decision is taken. `RepeatDeclineStrategy` is a `DecorationStrategy`, so it runs before `RepeatUnrollStrategy` flattens anything and can still read the `RepeatStep`'s loop bound. The class Javadoc already identifies the missing primitive in its transparency discussion — "Bounding that shape needs a depth or fan-out gate the translator does not have yet" — and the same gate narrows this veto.

**Evidence.** `#### C6`.

**Impact.** Friends-of-friends is the shape at stake: `g.V().has("id", x).repeat(__.out("knows")).times(2)` currently runs the whole traversal on the native left-to-right pipeline, giving up index-backed root selection and per-plan hop execution on a query the planner handles in two path items.

**Suggestion.** Treat this as a measurement task rather than a patch. The trade is genuinely shape-dependent: a *rooted* shallow repeat favours the plan (index root, small fan-out product), while a *bare* `g.V().repeat(__.out()).times(2)` may favour native, because the injected barriers bulk-merge identical traversers and MATCH enumerates one row per distinct path. Track 11 owns a translator-on-vs-off JMH harness; adding a rooted and a bare `times(2)`/`times(3)` pair to it would settle the bound empirically. Guessing a depth threshold without that measurement risks re-admitting the enumeration blow-up one level up.

## Evidence base

#### C1 CONFIRMED — type-guard per-record cost

Cost trace read end to end: `SQLInCondition.evaluate(Result, ctx)` → `evaluateRight` → `SQLCollection.execute` (`new ArrayList` + per-element `SQLExpression.execute`) → `SQLBaseExpression`'s `string` branch → `StringSerializerHelper.decode(string.substring(1, len - 1))`; `stringExpression` confirmed to store `"\"NAME\""` via `SQLBaseExpression(String)`; right side is a `List`, so `evaluateExpression` walks it through `QueryOperatorEquals.equals` rather than `Set.contains`; `NUMERIC_TYPE_NAMES` holds 7 entries; `AndP` decomposition confirmed to re-enter `translateCompare` per bound with `rangeTypeGuard` intact. Scale check: negligible at 100 records, noticeable at 100K, MATTERS AT SCALE at 1M+ with fan-out. Refutation attempted on the left-hand side and it partly landed — see the correction recorded under C8 — and the right-hand side survived it.

#### C2 CONFIRMED — ORDER-BY decline gives up the bounded heap

`MatchExecutionPlanner.java:715-727` and `SelectExecutionPlanner.java:2127-2159` both compute `maxResults = skip + limit` and pass it to `OrderByStep`, whose Javadoc pins the `O(|all results|)` to `O(maxResults)` reduction; `OrderGlobalStep` confirmed via `javap` to extend `CollectingBarrierStep`; `OrderLimitStrategy.apply` disassembled and confirmed to return unless `TraversalHelper.onGraphComputer`. MATTERS AT SCALE.

#### C3 CONFIRMED — `IS DEFINED` estimate distortion

Both exits of `SQLWhereClause.estimate` traced to `classCount / 2` for `SQLIsDefinedCondition`; `estimatePredicateSelectivity`'s arm list read and confirmed to have no `SQLIsDefinedCondition` case; `estimateRootEntries`'s `classCount + 1` versus `min(estimate, classCount)` asymmetry read at lines 6408-6420; nine `ByModulatorPresence` call sites enumerated across the diff. MATTERS AT SCALE on multi-alias patterns.

#### C4 CONFIRMED — the `values(k).limit(n)` decline is estimator-blocked

The recogniser's own rejection sentence names the root-selection estimate as the sole obstacle, and `GremlinAggregateAssembler.configureCount` demonstrates the conjunct mechanism working on a sibling path. Dependency on PF6 is stated rather than assumed. MATTERS AT SCALE.

#### C5 CONFIRMED — `mean` drops out of reconciliation

`CacheableShape.K0_NONE`'s Javadoc read for the invalidation semantics; `AggregateState` lines 302/324/346 confirmed to treat `AGGREGATE_SUM` and `AGGREGATE_AVG` identically; `scalar()` confirmed to diverge only at `computeAverage(sumAccumulator, count)`; `SQLFunctionMean.computeMean` confirmed to take the same `(Number, int)` shape. MATTERS NOW for any cached-aggregate workload with writes.

#### C6 CONFIRMED — veto reach exceeds the defect

`TraversalHelper.hasStepOfAssignableClassRecursively(RepeatStep.class, traversal)` is depth-blind by construction; the strategy's `DecorationStrategy` category places it before `RepeatUnrollStrategy`, so the loop bound is still readable. Severity held at suggestion because the narrowing direction is shape-dependent and unmeasured. MATTERS AT SCALE, conditionally.

#### C7 REFUTED — the plan-cache key does not fragment on values

The track file's argument for BG29's `toString(NO_PARAMS, …)` switch was checked rather than taken, and it holds.

`SQLPositionalParameter.toString(params, builder)` renders value-independently under the empty map: `bindFromInputParams(Collections.emptyMap())` looks up a missing slot, `toParsedTree(null)` yields null, and the final `else` appends the four characters `null`. Two traversals differing only in a bound value produce byte-identical `;F:` sections.

Every production path into an alias filter was enumerated to check for an inline *value*. `toFilter(container, typeGate, paramSink, rangeTypeGuard)` is the only overload carrying a sink and the only one production calls — `HasStepRecogniser.java:156` and `EdgeHopRecogniser.java:121`, both passing `ctx::bindParam`; the two shorter overloads have no production caller. Inside the adapter, every value reaches `valueExpression(value, paramSink)`, including the per-element binding in `translateContains`, the derived prefix successor in `startsWithRange`, the `Text` operands, and the regex pattern. `putAliasFilter` was then swept for other writers: `StartStepRecogniser` writes a RID list, but a RID-bearing walk bypasses the cache under R3; `OrStepRecogniser`, `NotStepRecogniser`, `WherePredicateStepRecogniser`, `TraversalFilterStepRecogniser` and `ConnectiveStepSupport` all forward expressions the adapter or the builder produced.

Two inline-literal producers remain and both are structural. `WHERE.classEquals` inlines a class name. `WHERE.typeIn` inlines comparability-block names drawn from a closed set, so the guard has at most four distinct renderings (seven numeric names, `STRING`, `BOOLEAN`, `DATE`+`DATETIME`) and splits the key exactly where BG29 requires it to. Cardinality of `within(...)` sets already split the key under `toGenericStatement` too, so nothing changed there.

Verdict: no cache-key fragmentation, no plan-reuse loss. The `GremlinPlanFingerprint` Javadoc's claim is accurate as written.

#### C8 REFUTED — the type guard does not defeat index selection, and the left-hand cost is smaller than first traced

Two sub-claims were tested; both failed, and one produced a correction to C1.

Index selection first. `ProjectionExpressionFactories.propertyMethodCall` builds the guard's left side as `SQLExpression(SQLIdentifier, SQLModifier)`, and `SQLBaseExpression.isBaseIdentifier()` returns `identifier != null && modifier == null && …`, so a modifier-bearing left answers false. That propagates three ways, all benign. `SQLInCondition.getRelatedIndexPropertyName()` returns null, and `SelectExecutionPlanner.buildIndexSearchDescriptor` explicitly skips a null-property expression with the comment "we will apply them later on post filtering" — so the guard becomes a post-filter and the range comparison beside it still drives the index descriptor. `matchesField` requires `isBaseIdentifier()`, so `estimateInConditionSelectivity` returns -1 and the histogram estimator ignores the guard; `detectTwoSidedRange` still pairs the surviving `gte`/`lte` bounds after flattening. `MatchExecutionPlanner.estimateViaHistogram` and `resolveDistinctCount` both gate on `getRelatedIndexPropertyName()` and bail. The `MatchWhereBuilder.typeIn` Javadoc's claim is verified at every one of those sites.

A secondary observation reinforces it: the guard's hot surface excludes the index-backed position by construction, since `rangeTypeGuard` is `!ctx.atTraversalStart()` and a leading `has(key, range)` folded into `YTDBGraphStep` carries no guard at all.

Second sub-claim, and the correction. The first cost trace routed the guard's left side through `SQLMethodCall.execute`, which would add a `getSystemVariable` map lookup, a `resolveParams` `ArrayList`, and a `paramValues.toArray()` per record. `SQLBaseExpression.execute(Result, ctx)` (line 172) short-circuits `field.type()` through `isEntityPropertyType()` to `EntityImpl.getPropertyTypeInternal` instead, so none of that is paid. PF4's trace was corrected to drop those three items before the finding was written; the right-hand-side cost, which is the larger half, is unaffected.

#### C9 REFUTED — nothing in this range changed step 1's PF1 exposure

PF1's exposure is that the repeat decline keys on `RepeatStep` rather than on chain depth, so a hand-written n-hop chain still reaches the same MATCH path enumeration. This range leaves it exactly where step 1 left it.

`TRANSPARENT_STEPS` narrowed from three classes to one, and the class that matters is the one that stayed: `NoOpBarrierStep` is still transparent, so a hand-written `g.V().out().out().out()…` still folds into one MATCH pattern and still enumerates one row per distinct path. The two removals were `WhereStartStep` and `WhereEndStep`, which are scope bindings rather than barriers and are unrelated to chain depth. `RepeatDeclineStrategy` bounds the `repeat(...)` spelling only, and its own transparency-set Javadoc now says so in terms: "a deep hand-written chain is still translated and still pays the enumeration."

One change in this range moves the enumeration in the *favourable* direction. `GremlinStepWalker.bindPathItemConstraints` pushes each alias's `WHERE` and class onto the path item that targets it, where `MatchEdgeTraverser` reads them during traversal. Before the pass, a predicate on any non-root alias had no consumer, so a filtered chain enumerated unfiltered and discarded later; now it prunes at each hop. That reduces the enumerated set on filtered chains without touching the unfiltered worst case.

Verdict: exposure unchanged, with an incidental pruning improvement. PF1 remains open as executor work and this range neither widens nor closes it.

#### C10 REFUTED — the strategy-pass overhead was removed and no per-compile substitute was introduced

Step 1's `PF3` measured roughly 19 µs per declining compile for a strategy-list clone plus a topological `TraversalStrategies.sortStrategies` re-sort. The final channel in this range is neither the clone nor `getSideEffects()`: `RepeatDeclineStrategy` swaps the traversal's `TraversalStrategies` *reference* for a `VetoedStrategies` view of the same list, and `isVetoed` answers from the reference's type. Nothing is added to, removed from, or reordered within the list, so no re-sort runs and the process-wide `GlobalCache` singleton is left untouched. `getSideEffects()` is documented in the class as measured and rejected, for two reasons that are correctness-shaped rather than cost-shaped (the `SIDE_EFFECTS` traverser-requirement flip on the translator-off arm, and the shared side-effects instance vetoing siblings).

What replaces the 19 µs was checked for a hidden substitute. A repeat-free traversal pays one `TraversalHelper.hasStepOfAssignableClassRecursively` scan and returns; a repeat-bearing one additionally pays one `VetoedStrategies` allocation and a field write. Two amplifiers were considered and neither reaches a reportable scale. `Traversal.Admin.applyStrategies` applies each strategy to the root and recursively to every descendant, so the scan runs once per traversal in the tree and the total is the sum of subtree sizes rather than the tree size; traversals here are single- to double-digit step counts, so the quadratic term is bounded by a small constant. And the scan runs unconditionally, including with `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` off, because consulting the kill switch would need session resolution the scan deliberately avoids — reading the flag is the more expensive branch, so the design is right and the cost stays on the translator-off arm as a sub-microsecond addition.

One forwarded call was added on the compile path for a different reason: `GremlinStepWalker.walk` now resolves `ProductiveByStrategy` once per walk through `getStrategies().getStrategy(...)`, one linear pass over the strategy list plus an `Optional`. That is resolved once per walk alongside the polymorphism and edge-label-verification flags, in the same place and for the same reason.

Scale check on the whole surface: negligible at every scale. No finding.
