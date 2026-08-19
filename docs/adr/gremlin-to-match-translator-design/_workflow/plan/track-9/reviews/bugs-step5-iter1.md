<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 5, suggestion: 3}
index:
  - {id: BG1, sev: should-fix, loc: WalkerContext.java:332, anchor: "### BG1 ", cert: C1, basis: "byModulatorIsProductive answers true for a key IN productiveKeys; TinkerPop wraps (makes productive) exactly the keys NOT in a non-empty set, so both arms mistranslate — measured on the pinned fork"}
  - {id: BG2, sev: should-fix, loc: ByModulatorPresence.java:56, anchor: "### BG2 ", cert: C2, basis: "requireProperty applies the by-modulator productivity gate to two values(key)-derived callers; ProductiveByStrategy only wraps ByModulating steps, so values() always drops and the conjunct is always needed"}
  - {id: BG3, sev: should-fix, loc: GremlinAggregateAssembler.java:82, anchor: "### BG3 ", cert: C3, basis: "configureCount clears the values(key) row projection without restating its drop, the defect the same commit fixes one method below; g.V().values(age).count() measures 4 against native's 2"}
  - {id: BG4, sev: should-fix, loc: GremlinAggregateAssembler.java:159, anchor: "### BG4 ", cert: C4, basis: "the groupBy() != null gate the step adds to configureCount is absent from configureGroup and configureGroupCount, which clobber the earlier GROUP BY the same way"}
  - {id: BG5, sev: should-fix, loc: GremlinAggregateAssembler.java:261, anchor: "### BG5 ", cert: C5, basis: "resolveGroupKey gained a lastPropertyProjection branch and resolveGroupValue did not, so values(k).group() emits vertices as bucket values where native emits the projected values"}
  - {id: BG6, sev: suggestion, loc: ProjectionEquivalenceTest.java:669, anchor: "### BG6 ", cert: C11, basis: "countAfterGroup_declines pins a decline with no assertion that its prefix g.V().group().by(T.label) translates; the prefix does translate today, so the gate is exercised, but nothing holds that"}
  - {id: BG7, sev: suggestion, loc: ShapeClassifier.java:967, anchor: "### BG7 ", cert: C12, basis: "mean is absent from aggregateShapeForCall, so SELECT mean(x) FROM C classifies RECORD rather than K0_NONE; joins the existing median / variance / stddev / mode / percentile gap"}
  - {id: BG8, sev: suggestion, loc: SQLFunctionMean.java:73, anchor: "### BG8 ", cert: C13, basis: "the multi-argument arm casts every element to Number unguarded while the single-argument arm tests instanceof; the new test class covers only the guarded arm"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 8}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CONFIRMED, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: REFUTED,   anchor: "#### C14 "}
  - {id: C15, verdict: REFUTED,   anchor: "#### C15 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] The productive-by gate reads `productiveKeys` backwards, so a configured `ProductiveByStrategy` mistranslates in both directions

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContext.java` (lines 332-336); resolution site `GremlinStepWalker.java` (lines 302-309)

**Issue**: `byModulatorIsProductive` answers "productive, skip the `IS DEFINED` conjunct" when the key **is** in `productiveKeys`. TinkerPop's rule is the opposite. `ProductiveByStrategy.hasKeyNotKnownAsProductive` returns true — meaning "wrap this modulator in `coalesce(…, null)` and make it productive" — when the set is empty, or when the key is **absent** from a non-empty set. A key listed in `productiveKeys` is one the caller asserts is already present on every element, so the strategy leaves it alone and the `by(key)` keeps its filtering behaviour.

Empty-set is the only case the predicate gets right, and it is the only case the new positive control exercises (`productiveByStrategy_keepsTheNullBucket` uses `ProductiveByStrategy.instance()`, whose `productiveKeys` is `Collections.emptySet()`). Once a caller configures keys, both arms diverge:

```
productiveKeys("age"):
  g.V().groupCount().by("age")   ON [{25=1, 30=1, 99=1, null=1}]   OFF [{25=1, 30=1, 99=1}]
  g.V().groupCount().by("name")  ON [{Alice=1, Bob=1, Nameless2=1}]
                                 OFF [{Alice=1, Bob=1, Nameless2=1, null=1}]
  g.V().order().by("name")       ON 3 vertices                     OFF 4 vertices
```

The listed key keeps a `null` bucket native drops; the unlisted key drops a `null` bucket native keeps.

**Evidence** (`#### C1`): measured against the project's own `gremlin-core-3.8.1-67860f6-SNAPSHOT` on a four-vertex fixture (two with `name`+`age`, one `name`-only, one `age`-only), translator on against translator off, through `ProjectionEquivalenceTest`'s own harness shape. The bytecode of `hasKeyNotKnownAsProductive` in that jar is `productiveKeys.isEmpty() || (getBypassTraversal() == null && !productiveKeys.contains(getPropertyKey()))`, and `apply` calls `wrapValueTraversalInCoalesce` only when it returns true.

**Refutation considered**: I checked whether the empty-set default makes the configured case unreachable in practice — it does not; `ProductiveByStrategy.build().productiveKeys(…).create()` is public API and `PRODUCTIVE_KEYS` is a documented configuration key, so a Gremlin Server or `withStrategies` caller can reach it. I also checked whether the strategy runs before the translator and pre-rewrites the modulator into a shape the translator would decline: it does not change the classification either way, because `wrapValueTraversalInCoalesce` sets a bypass traversal on the same `ValueTraversal`, which `ByModulatorTranslator.classifyKey` still matches on its `getPropertyKey()`.

**Suggestion**: negate the membership test.

```java
@Override
public boolean byModulatorIsProductive(String propertyKey) {
  // ProductiveByStrategy wraps (and so makes productive) every by(key) when its key set is empty,
  // and otherwise only the keys the caller did NOT list — a listed key is asserted already
  // productive, so the strategy leaves its filtering behaviour intact.
  return productiveByKeys != null
      && (productiveByKeys.isEmpty() || !productiveByKeys.contains(propertyKey));
}
```

Extend `productiveByStrategy_keepsTheNullBucket` with a `productiveKeys("age")` case on both arms — a listed key that must keep the conjunct, and an unlisted key that must drop it. The current single-strategy control cannot tell the two spellings apart.

### BG2 [should-fix] The productive-by gate is applied to `values(key)` drops, which the strategy never touches

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ByModulatorPresence.java` (lines 51-61); callers `GremlinAggregateAssembler.java:143` (`configurePropertyAggregate`) and `GremlinAggregateAssembler.java:238` (`requireGroupKeyPresent`'s no-`by` branch)

**Issue**: `requireProperty` short-circuits on `ctx.byModulatorIsProductive(key)`, and two of its three callers are not modulator sites at all. `configurePropertyAggregate` restates the drop that a preceding `values(key)` pinned in the row projection, and `requireGroupKeyPresent`'s fallback restates the same drop for a bare `group()` / `groupCount()`. A `PropertiesStep` is not a `ByModulating` step, so `ProductiveByStrategy` never wraps it and `values(key)` drops whatever the strategy says. Suppressing the conjunct there removes a filter native still applies:

```
g.withStrategies(ProductiveByStrategy).V().values("age").groupCount()
   ON  [{null=2, 30=1, 25=1}]      OFF [{25=1, 30=1}]
g.withStrategies(ProductiveByStrategy).V().values("foo").sum()
   ON  [0]                         OFF []
```

The second is the exact scenario the new comment above the `requireProperty` call describes ("`sum()` over six null-valued rows emits a zero") reappearing whenever the strategy is present. `SQLFunctionSum.getResult()` returns `0` for a null running sum, so `dropNullRows` never fires.

**Evidence** (`#### C2`): measured, both shapes, same harness as BG1. The strategy's scope is a decompiled fact rather than an inference: `ProductiveByStrategy.apply` is `TraversalHelper.getStepsOfAssignableClass(ByModulating.class, traversal).stream().filter(bm -> bm instanceof TraversalParent).forEach(…)`, and `PropertiesStep` implements neither.

**Refutation considered**: I checked whether `configurePropertyAggregate`'s `dropNullRows` covers the gap on its own. It does not — the SQL aggregate cell is `0`, not null, so the row survives. I also checked whether the `values(key)` shaping (`dropOnAbsent` plus presence key) survives into the aggregate plan; both assemblers call `setResultShaping` with a fresh `ResultShaping`, so it does not.

**Suggestion**: split the two obligations. Keep the gate on the modulator entry point and give the projection entry point an ungated body:

```java
/** Contributes {@code key IS DEFINED} for a drop that no by-modulator strategy can invert — the
 *  filtering a {@code values(key)} step performs in its own right. */
static void requireProjectedProperty(RecognitionContext ctx, String alias, String key) {
  ctx.putAliasFilter(alias, WHERE.wrap(WHERE.isDefined(key)));
}
```

`requireModulatedProperty` keeps calling the gated `requireProperty`; the two `values(key)` callers move to the new one. Add the two measured shapes above to `ProjectionEquivalenceTest` beside `productiveByStrategy_keepsTheNullBucket`, since that test is what currently makes the gate look correct.

### BG3 [should-fix] `count()` after `values(key)` counts every element, not the ones carrying the key

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (lines 64-89, contribution block at 82-87)

**Issue**: This is the defect the step fixes in `configurePropertyAggregate`, unfixed one method above it. `configureCount` calls `clearReturnProjection()`, nulls `lastPropertyProjection`, and replaces the shaping with `ResultShaping.NONE` — the same three moves whose consequence the new comment at line 138 spells out ("that drop lives in the boundary's row projection — which this method is about to replace"). It never restates the drop, so the `count(*)` runs over the unfiltered pattern:

```
fixture: 2 vertices with age, 2 without
  g.V().values("age").count()   ON [4]   OFF [2]

fixture: 3 vertices with age, 1 without
  g.V().values("age").count()   ON [4]   OFF [3]
```

`g.V().order().by("age").count()` is right (measured `[2]`/`[2]`) because the order path contributes its conjunct to the pattern, where `configureCount` cannot discard it. The gap is specific to the drop that lives in the projection.

**Evidence** (`#### C3`): measured on both fixtures. The shape predates the commit — `configureCount`'s only change here is the `groupBy()` gate — but it sits inside the result-shaping family the step scoped, and no test in `core/src/test/java/…/translator/strategy` puts a `count()` after a `values(key)`.

**Refutation considered**: I checked whether `PropertiesStepRecogniser` declines when a `count()` follows, which would keep the shape off the translated path. It does not; the walker's dispatch loop has no terminator gate, and the measured run splices one `AbstractMatchPlanStep`. I checked whether `dropNullRows` or `dropOnAbsent` survives `configureCount` — neither does; the method installs `ResultShaping.NONE`. I also checked `min` / `max` / `mean` for the same hole, and they are covered by the step's own fix (`g.V().values("age").max()` measured `[30]`/`[30]`).

**Suggestion**: make the two aggregate entry points restate the drop the same way. `configureCount` should read `ctx.lastPropertyProjection()` before nulling it and, when non-null, call the same presence helper `configurePropertyAggregate` calls (the ungated one from BG2):

```java
var projection = ctx.lastPropertyProjection();
if (projection != null) {
  // count() after values(key) counts the values the step emitted, and values(key) drops an element
  // without the property. That drop lives in the row projection this method is about to discard.
  ByModulatorPresence.requireProjectedProperty(ctx, projection.alias(), projection.propertyKey());
}
```

Add `g.V().values("age").count()` to `ProjectionEquivalenceTest` on a fixture where some elements lack the key — on an all-bearing fixture the assertion passes against either implementation.

### BG4 [should-fix] The new `groupBy()` gate covers `count()` and not the two sibling terminators that clobber a GROUP BY the same way

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (`configureGroup` lines 159-190, `configureGroupCount` lines 195-219; the gate the step added is at lines 71-77)

**Issue**: The step's reasoning for the `count()` gate is that a terminator following a grouping terminator consumes the maps the grouping emits, and `configureCount` would instead overwrite the grouped plan. `configureGroup` and `configureGroupCount` do exactly what that comment forbids — `clearReturnProjection()`, then `setGroupBy(groupBy)` over the top of the existing one — and neither consults `ctx.groupBy()`:

```
g.V().group().by("name").groupCount()
   ON  [{v[#19:0]=1, v[#19:1]=1, v[#25:0]=1, v[#25:1]=1}]
   OFF [{{Bob=[v[#25:1]], Alice=[v[#19:1]], Nobody=[v[#19:0]], Nemo=[v[#25:0]]}=1}]

g.V().group().by("name").group()
   ON  [{v[#18:0]=[v[#18:0]], v[#20:0]=[v[#20:0]], v[#24:1]=[v[#24:1]]}]
   OFF [{{Nameless2=[…], Bob=[…], Alice=[…]}=[{Alice=[…], Bob=[…], Nameless2=[…]}]}]
```

Native wraps the emitted map in a second grouping; the translated plan re-keys the underlying rows by RID and never sees the map at all.

**Evidence** (`#### C4`): measured, both shapes, one `AbstractMatchPlanStep` spliced in each. `g.V().groupCount().by("age").count()` declines (measured, zero boundary steps), which confirms the new gate works on the arm it covers. `configurePropertyAggregate` needs no gate because both grouping methods null `lastPropertyProjection`, so a following `sum`/`min`/`max`/`mean` declines on its own (`#### C10`), and `order()` after a grouping terminator measured identical on both arms (`#### C15`).

**Refutation considered**: I checked whether the walker refuses to dispatch past a `MAP`-pinned boundary, which would make the shape unreachable. `dispatchAll` (`GremlinStepWalker.java:348-370`) keys only on the step class and the union-carrier allow-list, so a second grouping terminator is dispatched normally. I checked whether `GroupStepRecogniser` / `GroupCountStepRecogniser` add a guard of their own — both are thin delegations with no context read.

**Suggestion**: lift the gate into the shared pre-check the three terminators already run. `hasPreAggregateCardinalityClause` is the natural home, but it is named for a different reason, so a sibling reads better:

```java
/** A grouping terminator has already fixed the row set, and a second one consumes the maps the
 *  first emits rather than the rows that fed it. Neither assembler can express that, so decline. */
private static boolean hasGrouping(RecognitionContext ctx) {
  return ctx.groupBy() != null;
}
```

Call it from `configureCount`, `configureGroup`, and `configureGroupCount`, replacing the inline test the step added. Cover `group().by(k).group()` and `group().by(k).groupCount()` beside `countAfterGroup_declines`.

### BG5 [should-fix] `values(k).group()` keys on the projected value and buckets the vertices

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (`resolveGroupValue` lines 261-285; the new key branch at 249-259)

**Issue**: `resolveGroupKey` gained a `lastPropertyProjection` branch so a bare `group()` / `groupCount()` after `values(k)` keys on the projected value. `resolveGroupValue` did not gain the matching branch, and its no-`by` default is `MatchProjectionBuilder.listAlias(alias)` — the boundary element. `groupCount()` is unaffected (its value column is `count(*)`), which is why the new `groupCountAfterValues_keysOnTheProjectedValue` test is green. `group()` is not:

```
g.V().values("name").group()
   ON  [{Alice=[v[#24:1]], Bob=[v[#18:0]], Nameless2=[v[#20:0]]}]
   OFF [{Alice=[Alice], Bob=[Bob], Nameless2=[Nameless2]}]
```

Native groups a stream of strings, so each bucket holds the strings. The verdict is unchanged from before the commit — the old code keyed on `@rid` and bucketed vertices, so the whole map was wrong — but the answer now looks right on the key axis, which is the half a reader checks first.

**Evidence** (`#### C5`): measured, one boundary step spliced. `resolveGroupValue`'s signature still takes only `(String alias, Traversal.Admin<?,?> valueTraversal)`, so it has no access to the projection the key branch now reads; the omission is structural rather than a missed condition.

**Refutation considered**: I checked whether the shaping layer re-derives the bucket values from the key column — `AbstractMatchPlanStep.projectMap` with `accumulateMap` reads the `value` RETURN column verbatim, converting RIDs to vertices, so it cannot. I checked whether `group()` after `values(k)` is instead declined somewhere upstream; the measured run translates it.

**Suggestion**: thread the context into `resolveGroupValue` the way the step threaded it into `resolveGroupKey`, and give the no-`by` branch the same projection-first rule:

```java
private static SQLExpression resolveGroupValue(
    RecognitionContext ctx, String alias, Traversal.Admin<?, ?> valueTraversal) {
  if (valueTraversal == null || valueTraversal instanceof IdentityTraversal) {
    // Bare group() folds whatever the walk is emitting. After values(k) that is the property, not
    // the element: g.V().values("name").group() buckets the names, matching resolveGroupKey.
    var projection = ctx.lastPropertyProjection();
    if (projection != null) {
      return MatchProjectionBuilder.listExpression(projection.expression());
    }
    return MatchProjectionBuilder.listAlias(alias);
  }
  …
```

If `MatchProjectionBuilder` has no `list(<expression>)` factory, declining the `values(k)` + bare-`group()` combination is the cheaper correct move; it is one `ctx.lastPropertyProjection() != null && valueTraversal == null` test in `configureGroup`. Either way the shape needs a `ProjectionEquivalenceTest` case, since `groupCountAfterValues_keysOnTheProjectedValue` does not reach the value column.

### BG6 [suggestion] `countAfterGroup_declines` pins a decline with nothing pinning that its prefix translates

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java` (lines 1035-1048)

**Issue**: The test asserts that `g.V().group().by(T.label).count()` declines and that both payloads match. A decline is also what a `by(T.label)` that stopped classifying would produce, or a `group()` that stopped translating for any other reason, and the payload comparison is `[1]` against `[1]` either way — both arms run natively once the translator declines. Nothing in the file asserts that the prefix `g.V().group().by(T.label)` is recognised, so the test cannot distinguish "the new `groupBy()` gate fired" from "the shape never reached the gate".

The prefix does translate today, so the assertion is live as written. The nearest control, `group_byName_matchNative`, uses a `ValueTraversal` modulator (`by("name")`) rather than the `TokenTraversal` this scenario carries, so it does not cover the same classification path.

**Evidence** (`#### C11`): measured — `g.V().group().by(T.label)` splices one `AbstractMatchPlanStep` and returns `[{Person=[v[#19:0], v[#19:1], v[#25:0], v[#25:1]]}]` on both arms; `g.V().group().by(T.label).count()` splices zero. The sibling decline test in the same block, `keylessValueMapAndElementMap_decline`, does have same-shape positive controls (`valueMapWithTokens_stillWrapsValuesInLists` and `elementMap_matchNative` both pin the keyed spelling as `RECOGNIZED`), which is the pattern this one is missing.

**Suggestion**: add the prefix assertion to the same test method, above the decline.

```java
// Positive control: the prefix must translate, or the decline below would be produced by the
// prefix rather than by the count gate this case exists to pin.
assertEquivalent(
    "g.V().group().by(label)",
    Recognition.RECOGNIZED,
    () -> graph.traversal().V().group().by(T.label));
```

### BG7 [suggestion] Registering `mean` adds one more aggregate the query-result cache classifies as a record shape

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (lines 967-980); registration at `DefaultSQLFunctionFactory.java:86`

**Issue**: `aggregateShapeForCall` recognises `count` / `sum` / `avg` / `min` / `max` and returns `null` for everything else. `projectionContainsAggregate` is built on the same switch, so for `SELECT mean(age) FROM Person` both the single-aggregate branch and the contains-aggregate branch miss, and `classifySelect` falls through to `return CacheableShape.RECORD` — the per-record delta path, applied to a projection whose one row is a scalar with no RID.

`mean` joins `median`, `variance`, `stddev`, `mode`, and `percentile`, which the classifier's own Javadoc names as unmodelled aggregates. The gap is not new; what is new is that the Gremlin work put a sixth name into it, and `mean` is the one a user reaching for a floating-point average will now type.

**Evidence** (`#### C12`): read, not measured. The switch at lines 972-979 has no `mean` case, `subtreeContainsAggregate` (line 1010) tests `aggregateShapeForCall(call) != null`, and `classifySelect`'s last statement is the `RECORD` fallback. The translator's own plans do not reach this code — they execute `MatchPlanInputs` directly rather than through `DatabaseSessionEmbedded.query(String)`, which is the only `ShapeClassifier.classify` call site — so the exposure is hand-written SQL.

**Refutation considered**: I checked whether the `RECORD` populate path rejects a non-identifiable row downstream, which would make the misclassification harmless. I did not resolve it; `DeltaBuilder` and `CachedResultSetView` are outside this diff and the chase is longer than the finding is worth. That is why this is a suggestion with a verification step rather than a defect claim.

**Suggestion**: verify first — run `SELECT mean(age) FROM Person` twice inside one transaction with an intervening insert, on `avg` and on `mean`, and compare. If `avg` re-reconciles and `mean` replays a stale scalar, the switch needs a branch that routes a recognised-but-unmodelled aggregate to `K0_NONE` rather than letting it fall through to `RECORD`; the same branch closes the five pre-existing names. If the populate path already refuses the row, record that in `aggregateShapeForCall`'s Javadoc, which currently says only that an unmodelled aggregate "returns `null`" without saying where `null` lands.

### BG8 [suggestion] `SQLFunctionMean`'s multi-argument arm casts unguarded where the single-argument arm tests

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java` (lines 64-82)

**Issue**: The single-argument arm tests `iParams[0] instanceof Number` and falls through to `MultiValue`, leaving the aggregate untouched for anything else — the behaviour `nonNumericArgumentLeavesTheAggregateEmpty` pins. The multi-argument arm casts every element straight to `Number`, so `mean(name, age)` over a string column throws `ClassCastException` out of the projection rather than skipping the value. The `MultiValue` branch has the same unguarded cast on its elements, so a mixed-type collection throws too.

The behaviour is inherited from `SQLFunctionAverage`, which the class is deliberately modelled on, so this is consistency rather than a regression. It is worth a line because the class is new: the private `sum(@Nullable Number)` helper already exists as the single place a value is filtered, and widening its parameter is a two-line change.

**Evidence** (`#### C13`): read. Line 69 is `sum((Number) n)` inside the `MultiValue` loop and line 79 is `sum((Number) param)` in the multi-argument loop; `sum` (line 85) tests only for null. `SQLFunctionMeanTest` exercises the multi-argument arm twice (`multiArgumentFormIsRowWiseAndResetsBothSumAndCount`, `multiArgumentFormSkipsNullEntries`) with numeric and null entries only.

**Suggestion**: widen the helper and let it do the filtering both arms need.

```java
/** Adds one contributor. Null and non-numeric values are skipped rather than counted, so the
 *  divisor stays the number of values that actually contributed. */
private void sum(@Nullable Object value) {
  if (!(value instanceof Number number)) {
    return;
  }
  total++;
  sum = sum == null ? number : PropertyTypeInternal.increment(sum, number);
}
```

Both call sites then drop their casts. Add a multi-argument non-numeric case to `SQLFunctionMeanTest` beside the single-argument one it already has.

## Evidence base

METHOD CAVEAT: reference questions in this review were answered by grep over `core/src/{main,test}/java` and by disassembling the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT` jar, not by PSI — `steroid_execute_code` times out on this repository (cold kotlinc exceeds the MCP limit). Five of the eight findings rest on executed translator-on / translator-off probes rather than on a symbol search, so a missed reference would not flip them. The two grep-only claims whose accuracy depends on the search being complete are `#### C12`'s "the only `ShapeClassifier.classify` call site" and `#### C3`'s "no test puts a `count()` after a `values(key)`".

MEASUREMENT METHOD: probes ran as a temporary `GraphBaseTest` subclass in `core`, deleted after the run. Each probe built the traversal twice — once with `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` true, once false — counted `AbstractMatchPlanStep` instances after `applyStrategies()` to confirm the shape actually translated, and printed both payloads with each map key's runtime class so a `T.id` token could not be mistaken for the string `"id"`. Two fixtures were used: the four-vertex `seedAgedAndAgeless` shape the new tests use (two with `age`, two without), and a mixed shape (two with `name`+`age`, one `name`-only, one `age`-only) needed to exercise both arms of BG1.

#### C1 `byModulatorIsProductive` inverts `ProductiveByStrategy`'s configured-key rule — CONFIRMED

The predicate at `WalkerContext.java:333-335` treats membership in `productiveKeys` as "productive". `ProductiveByStrategy.hasKeyNotKnownAsProductive` in the pinned jar is `productiveKeys.isEmpty() || (getBypassTraversal() == null && !productiveKeys.contains(getPropertyKey()))`, and `apply` wraps only when it returns true, so membership means "left alone, still filtering". Measured on the mixed fixture under `productiveKeys("age")`: `groupCount().by("age")` gains a `null` bucket the native run does not have, `groupCount().by("name")` loses the one the native run does have, and `order().by("name")` returns three vertices against native's four. Raised as BG1.

#### C2 `ProductiveByStrategy` never makes a `values(key)` step productive — CONFIRMED

`ProductiveByStrategy.apply` iterates `TraversalHelper.getStepsOfAssignableClass(ByModulating.class, traversal)` filtered to `TraversalParent`, and `PropertiesStep` implements neither interface, so the strategy cannot reach the drop a `values(key)` performs. `ByModulatorPresence.requireProperty` gates on it anyway, and two of its three callers are `values(key)` sites. Measured under `ProductiveByStrategy.instance()`: `values("age").groupCount()` gains a `null=2` bucket, and `values("foo").sum()` emits `0` where native emits nothing. Raised as BG2.

#### C3 `configureCount` discards the `values(key)` drop — CONFIRMED

`configureCount` (`GremlinAggregateAssembler.java:64-89`) calls `clearReturnProjection()`, `setLastPropertyProjection(null)`, and `setResultShaping(ResultShaping.NONE)` without reading the projection first, so the presence key and `dropOnAbsent` the `values(key)` step pinned are both gone by the time `count(*)` is appended. Measured `[4]` against `[2]` and `[4]` against `[3]` on the two fixtures, with one boundary step spliced in each. Grep over the translator strategy test tree finds no traversal placing a `count()` after a `values(key)`. Raised as BG3.

#### C4 `configureGroup` and `configureGroupCount` have no grouping gate — CONFIRMED

Both methods reach `setGroupBy(groupBy)` with no read of `ctx.groupBy()`; the gate the step added lives only in `configureCount` at lines 71-77. `dispatchAll` (`GremlinStepWalker.java:348-370`) gates on the step class and the union allow-list only, so a second grouping terminator is dispatched. Measured: `group().by("name").groupCount()` and `group().by("name").group()` both translate and both return a different multiset than native. `groupCount().by("age").count()` declines, confirming the covered arm works. Raised as BG4.

#### C5 `resolveGroupValue` has no value-projection branch — CONFIRMED

`resolveGroupKey` (lines 249-259) reads `ctx.lastPropertyProjection()`; `resolveGroupValue` (lines 261-285) takes no context parameter and defaults to `MatchProjectionBuilder.listAlias(alias)`. Measured: `g.V().values("name").group()` returns `{Alice=[v[#24:1]], …}` where native returns `{Alice=[Alice], …}`. `groupCount()` is unaffected because its value column is `count(*)`, which is why the step's own `groupCountAfterValues_keysOnTheProjectedValue` test does not reach the gap. Raised as BG5.

#### C6 `ProductiveByStrategy` sits in the default strategy set, so the new negative assertions are vacuous — REFUTED

CLAIM: if the strategy applies by default, then `orderByMissingKey_dropsElementLikeNative`, `groupByMissingKey_hasNoNullBucket`, and the two `select` cases would be green with or without the presence conjunct, because both arms would keep the `null` values.

REFUTATION: it is not in the default set. `TraversalStrategies$GlobalCache`'s static initialiser registers `IdentityRemoval`, `Connective`, `EarlyLimit`, `InlineFilter`, `IncidentToAdjacent`, `AdjacentToIncident`, `ByModulatorOptimization`, `FilterRanking`, `MatchPredicate`, `RepeatUnroll`, `Count`, `PathRetraction`, `LazyBarrier`, `Profile`, `StandardVerification`, and `GValueReduction` for the standard traversal source; `ProductiveByStrategy` appears nowhere in it. `GremlinStepWalker`'s `getStrategies().getStrategy(ProductiveByStrategy.class)` therefore returns empty on an ordinary traversal, `productiveByKeys` stays null, and `byModulatorIsProductive` answers false for every key. The four negative cases do exercise the conjunct.

RESIDUE: the reverse also holds — the empty-set arm of `byModulatorIsProductive` is only reachable through an explicit `withStrategies`, which is what `productiveByStrategy_keepsTheNullBucket` supplies. That control is sound for the default-instance case and covers nothing else (`#### C1`).

#### C7 The new `valueMap(true, keys…)` list wrapping also wraps the id / label token columns — REFUTED

CLAIM: `configurePropertyMap` now sets `withWrapMapValuesInLists(!isElementMap)` for `valueMap(true, "name")`, where the previous code derived the flag from the token bits and left it false. If the wrapping applies to every map column, the token columns become `T.id=[#19:0]` where native emits `T.id=#19:0`.

REFUTATION: the wrapping is scoped to presence-checked columns. `AbstractMatchPlanStep.projectMap` (lines 706-727) applies `Collections.singletonList` only inside the `presenceKeySet.contains(name)` branch, and `presenceKeys` is built from `propertyKeys` alone — the token columns are appended before that list is populated and are routed through `convertMapColumn` instead. Measured: `g.V().valueMap(true, "name")` returns `{T.id=#19:0, T.label=Person, name=[Alice]}` on both arms, and rendering each key's runtime class confirms the token keys are `T` enum constants on both sides rather than the strings `"id"` / `"label"`.

RESIDUE: `valueMap(true, "id")` would collide the token column's `"id"` alias with a property column of the same name, since both are appended under that alias. The collision predates the commit (the old `isElementMap = tokens != 0` derivation took the same branch) and the shape is not in the diff, so it is not raised.

#### C8 `putAliasFilter` overwrites, so a presence conjunct clobbers an earlier `has()` predicate — REFUTED

CLAIM: the new conjuncts arrive through `ctx.putAliasFilter(alias, …)` on an alias that a `has(k, v)` recogniser has usually already written, and a `put` that replaces would silently widen the result — `g.V().has("name","Alice").order().by("age")` would lose the name filter.

REFUTATION: it AND-composes. `WalkerContext.putAliasFilter` (lines 457-470) reads the existing clause and, when one is present, builds `WHERE.and(existing.getBaseExpression(), where.getBaseExpression())` before re-putting the wrapped conjunction; the method's own comment names the `g.V(ids)` plus `has(...)` case as the reason. `SubTraversalPredicateAdapter.putAliasFilter` (lines 275-288) does the same into a local `capturedAliasFilters` map. Nothing overwrites.

#### C9 `SelectStepRecogniser`'s in-loop contribution leaks on a later-iteration decline — REFUTED

CLAIM: `SelectStepRecogniser` calls `requireModulatedProperty` inside its label loop (line 61), so a later iteration returning `DECLINE` leaves alias filters on the context. `OrderGlobalStepRecogniser` deliberately defers its contribution past every resolution ("reached only after every comparator resolved, so a declining modulator leaves the context unmutated"), which suggests the ordering matters.

REFUTATION: it cannot leak in either position. On the top-level path a `DECLINE` makes `dispatchAll` return false and the walker discard the whole `WalkerContext`, which `WalkerContext`'s own class Javadoc states as the no-mutation-discipline invariant. On the sub-walk path `SubTraversalPredicateAdapter` writes filters to a private `capturedAliasFilters` map that is committed to the parent only on accept. The order-recogniser's deferral is stylistic here rather than load-bearing.

#### C10 `configurePropertyAggregate` needs the same grouping gate `configureCount` gained — REFUTED

CLAIM: if `count()` after a grouping terminator must decline, so must `sum` / `min` / `max` / `mean`, and `configurePropertyAggregate` has no `ctx.groupBy()` test.

REFUTATION: it declines already, through a different field. Both `configureGroup` (line 185) and `configureGroupCount` (line 214) call `setLastPropertyProjection(null)`, and `configurePropertyAggregate`'s second guard (lines 133-137) declines on a null projection. A property aggregate after a grouping terminator therefore cannot translate. The gate would be redundant; only the two grouping terminators need one (`#### C4`).

#### C11 `countAfterGroup_declines` has no same-shape positive control — CONFIRMED

The test asserts `Recognition.DECLINED` for `g.V().group().by(T.label).count()` and compares payloads, both of which a prefix-side decline would also satisfy. `ProjectionEquivalenceTest` contains no assertion that `g.V().group().by(T.label)` is recognised; the closest, `group_byName_matchNative`, uses a `ValueTraversal` modulator rather than the `TokenTraversal` this scenario carries. Measured, the prefix does translate (one boundary step, `[{Person=[…4 vertices…]}]` on both arms) and the full shape declines (zero boundary steps), so the assertion is live today and unprotected against a prefix regression. Raised as BG6.

#### C12 `mean` is absent from `ShapeClassifier.aggregateShapeForCall` — CONFIRMED

The switch at `ShapeClassifier.java:972-979` covers `count`, `sum`, `avg`, `min`, `max`. `subtreeContainsAggregate` (line 1010) is built on the same call, so `projectionContainsAggregate` answers false for a `mean` projection and `classifySelect` reaches its `return CacheableShape.RECORD` fallback. `DatabaseSessionEmbedded.java:977` is the only production `ShapeClassifier.classify` call site found by grep, and it sits on the `query(String)` path, which the translator's `MatchPlanInputs` execution does not take. Raised as BG7, at suggestion severity and with a verification step rather than a defect claim, because the downstream `RECORD` populate path was not traced.

#### C13 `SQLFunctionMean`'s multi-argument arm casts unguarded — CONFIRMED

Line 79 is `sum((Number) param)` with no type test, and line 69 is `sum((Number) n)` on `MultiValue` elements. The single-argument arm at line 66 tests `instanceof Number` first. `SQLFunctionMeanTest` covers the guarded arm (`nonNumericArgumentLeavesTheAggregateEmpty`) and exercises the unguarded arms with numeric and null entries only. Inherited verbatim from `SQLFunctionAverage`. Raised as BG8.

#### C14 `mean` is misclassified for determinism or missing from a registry the new function must join — REFUTED

CLAIM: a newly registered SQL function has to be wired into more than the factory — a determinism denylist, an aggregate-name registry, or the parser's aggregate detection — and one of those is missed.

REFUTATION: the three that exist are correct. Determinism: `mean` is a pure function of its input and belongs in `KNOWN_DETERMINISTIC`, which the diff updates; `FunctionDeterminismEnumerationTest`'s completeness walk would have failed the build otherwise, and `knownNonDeterministicMirrorsProductionDenylist` confirms the denylist itself is unchanged. Aggregate detection: `SQLFunctionCall.isAggregate` (line 414) calls `config(params)` then `aggregateResults()`, and `SQLFunctionMathAbstract.aggregateResults` returns `configuredParameters.length == 1`, so `mean(v.age)` is an aggregate without any per-name registry. Factory surface: `DefaultSQLFunctionFactoryTest.ALL_NAMES` is updated in the same commit. The one registry `mean` is absent from is `ShapeClassifier`, which is `#### C12` and a different question.

RESIDUE: `aggregateResults()` dereferences `configuredParameters` before `config()` has run, so a direct `new SQLFunctionMean().aggregateResults()` throws. `SQLFunctionAverage` and `SQLFunctionSum` override the method with the same body and the same exposure, and every production route calls `config` first. Not raised.

#### C15 `order()` after a grouping terminator mistranslates the way `group()` does — REFUTED

CLAIM: `OrderGlobalStepRecogniser` has no `ctx.groupBy()` test either, so `g.V().group().by(k).order()` would attach an `ORDER BY` over the pre-grouping rows or fail plan build.

REFUTATION: measured identical on both arms — `g.V().group().by("name").order()` returns `[{Alice=[v[#24:1]], Bob=[v[#18:0]], Nameless2=[v[#20:0]]}]` with the translator on and off. The grouping terminator emits a single accumulated map, and sorting a one-element stream is a no-op on the native side while the `ORDER BY` the recogniser adds reorders GROUP BY rows that are re-accumulated into the same map. No divergence to report, so `#### C4`'s scope stays at the two grouping terminators.
