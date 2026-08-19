<!-- MANIFEST
findings: 15   severity: {blocker: 0, should-fix: 8, suggestion: 7}
index:
  - {id: TB1, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java:272, anchor: "### TB1 ", cert: C1, basis: "the only test naming EdgeHopRecogniser's new range guard stays green when the guard flag is flipped to false"}
  - {id: TB2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/OrderGlobalStepRecogniser.java:91, anchor: "### TB2 ", cert: C2, basis: "the new order-after-values branch is asserted ascending only; hardcoding ascending=true keeps the suite green"}
  - {id: TB3, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java:343, anchor: "### TB3 ", cert: C3, basis: "zero-boundary assertion holds by construction; the test cannot separate withoutStrategies from the veto"}
  - {id: TB4, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java:1343, anchor: "### TB4 ", cert: C4, basis: "reflection gate matches a method name only, and no member's positional answer is asserted"}
  - {id: TB5, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java:51, anchor: "### TB5 ", cert: C5, basis: "eager result.getClass() in the failure message turns a null regression into an NPE"}
  - {id: TC1, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java:119, anchor: "### TC1 ", cert: C6, basis: "no BigDecimal fixture divides non-terminatingly, so MathContext.DECIMAL128 is untested and its loss ships ArithmeticException out of a projection"}
  - {id: TC2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeHopRecogniser.java:122, anchor: "### TC2 ", cert: C7, basis: "no edge-property fixture holds mixed runtime types under one key, so the edge-hop type guard has no discriminating case"}
  - {id: TC3, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslator.java:209, anchor: "### TC3 ", cert: C8, basis: "group value-side by(values(k).mean()) became reachable when the mean SQL function landed; no equivalence case covers it"}
  - {id: TC4, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslator.java:106, anchor: "### TC4 ", cert: C9, basis: "new public keyModulatorPropertyKey has zero tests and its record-attribute arm is unobservable end to end"}
  - {id: TC5, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionForkHostImpl.java:92, anchor: "### TC5 ", cert: C10, basis: "the fold-latch child-scope boundary is only tested on a one-step prefix, so an off-by-one that over-guards a filtered prefix is invisible"}
  - {id: TC6, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:854, anchor: "### TC6 ", cert: C11, basis: "no end-to-end case binds a constraint onto an intermediate alias of a multi-hop chain"}
  - {id: TC7, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchFilter.java:122, anchor: "### TC7 ", cert: C12, basis: "setClassName's null argument and its append-onto-an-existing-WHERE path are both untested"}
  - {id: TC8, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java:97, anchor: "### TC8 ", cert: C13, basis: "empty multi-value, BigDecimal-plus-Integer promotion and NaN inputs are outside the fixture set"}
  - {id: TC9, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java:401, anchor: "### TC9 ", cert: C14, basis: "no post-union slice selects an empty window, so the early-stop claim on PostConcatOp.Range is unpinned at its boundary"}
  - {id: TC10, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/ProjectionExpressionFactories.java:161, anchor: "### TC10 ", cert: C15, basis: "propertyMethodCall's requireNonBlank contract on either argument has no test"}
evidence_base: {section: "## Evidence base", certs: 19, matches: 15}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CONFIRMED, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: CONFIRMED, anchor: "#### C14 "}
  - {id: C15, verdict: CONFIRMED, anchor: "#### C15 "}
  - {id: C16, verdict: REFUTED, anchor: "#### C16 "}
  - {id: C17, verdict: REFUTED, anchor: "#### C17 "}
  - {id: C18, verdict: REFUTED, anchor: "#### C18 "}
  - {id: C19, verdict: REFUTED, anchor: "#### C19 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [should-fix] The only test that names the edge-hop range guard cannot fail when the guard is removed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, method `undeclaredEdgePropertyRange_keepsTranslatingAndKeepsItsRows` (line 272)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeHopRecogniser.java:118-122`

**Issue**: The track changes `EdgeHopRecogniser` to pass `/* rangeTypeGuard= */ true` for every edge property container, on the argument that an edge filter is never folded into `YTDBGraphStep`. This test is the only one that exercises a range comparison on an edge property, and its fixture makes the guard a no-op: `seedMixedTypeFixture` stores `since` as `2020`, `2024`, `2030`, `2031` — Integers only — and the comparand is the Integer `2025`. The guard emits `since.type() IN ['BYTE', … 'DECIMAL']`, which is true for every row that has `since`, so the guarded and unguarded translations select the same two rows.

**Evidence** (FALSIFIABILITY CHECK, cert C1): mutate `EdgeHopRecogniser.java:122` from `true` to `false` and no test in the tree reddens. The three other edge-property `has` shapes in the suite use `eq` (`FoldedEdgeStepDispatchClassTest`, `VertexStepRecogniserTest`, `CombinatorFoldedHopRecogniserTest` all use `has("w", 1)`) or `TextP.containing` (`PredicateTraversalEquivalenceTest:844`), and `isOrderComparison` excludes both, so the flag is unread on every other path.

**Missing behavior**: that an edge property holding two runtime types under one key is partitioned per record the way TinkerPop's comparator partitions it. The test's own Javadoc frames the case as a no-regression pin ("keeps translating with the answer it already had"), which is honest, but it leaves the production change with no witness.

**Suggested fix** — extend the fixture so one `link` edge stores a String `since`, and assert the direction where SQL ordering and the comparator disagree. `gt` is the discriminating operator: unguarded SQL ranks every String above `2025` and admits the row, and the comparator excludes it.

```java
// in seedMixedTypeFixture(), after the four numeric since edges:
// A String `since` on a fifth edge is what separates the two comparators: SQL ranks it above
// every number, TinkerPop's comparator refuses to compare it with one.
var echo = graph.addVertex(T.label, "Item", "tag", "echo", "name", "echo", "num", 50);
root.addEdge("link", echo, "since", "recently");

@Test
public void crossTypeEdgePropertyRange_matchesTheComparatorNotSqlOrdering() {
  seedMixedTypeFixture();

  assertAgreesWithNative(
      "g.V().outE(link).has(since, gt(2025)).inV() — String since must not compare",
      () -> graph.traversal().V().outE("link").has("since", P.gt(2025)).inV(),
      List.of("charlie", "delta"));
}
```

### TB2 [should-fix] The order-after-values branch is asserted ascending only, so its direction flag is unfalsifiable

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `orderAfterValues_sortsByTheValueNotTheRid` (line 1306)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/OrderGlobalStepRecogniser.java:88-92`

**Issue**: `resolveSortItem` gained a branch that re-points a bare `order()` at `lastPropertyProjection`, passing `ascending` through to `ProjectionExpressionFactories.orderByProperty`. Every test of that branch sorts ascending. `order().by(Order.desc)` after a `values(k)` reaches the same branch — the value traversal is still an `IdentityTraversal`, only the comparator changes — and nothing asserts it.

**Evidence** (FALSIFIABILITY CHECK, cert C2): replace `ascending` with the literal `true` at `OrderGlobalStepRecogniser.java:91` and the suite stays green. `OrderRangeStepRecogniserTest.orderByProperty_desc` (line 60) does cover a descending property sort, but it spells `by("name", Order.desc)`, a *keyed* modulator that resolves through `ByModulatorTranslator.translateKeyModulatorOrderItem` and never enters the identity branch. `ProjectionExpressionFactoriesTest.orderByProperty_rendersLikeParser` pins the factory with `false`, which proves the factory honours the flag and says nothing about the caller passing it.

**Missing behavior**: `g.V().values("name").order().by(Order.desc)` returns the names in descending order, matching native.

**Suggested fix**:

```java
/**
 * The descending direction of the same re-point. A bare {@code order().by(Order.desc)} keeps the
 * identity value traversal and changes only the comparator, so it enters the same branch as the
 * ascending case — and a branch that dropped the direction flag would still pass that one.
 */
@Test
public void descendingOrderAfterValues_sortsByTheValueDescending() {
  graph.addVertex(T.label, "Person", "name", "Zoe");
  graph.addVertex(T.label, "Person", "name", "Alice");
  graph.addVertex(T.label, "Person", "name", "Mallory");
  graph.tx().commit();

  assertEquivalentOrdered(
      "g.V().values(name).order().by(Order.desc)",
      Recognition.RECOGNIZED,
      () -> graph.traversal().V().values("name").order().by(Order.desc));
}
```

### TB3 [should-fix] `translatorAlreadyRemovedFromTheSource_needsNoVeto` asserts an outcome that holds by construction

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RepeatDeclineStrategyTest.java`, method `translatorAlreadyRemovedFromTheSource_needsNoVeto` (line 343)

**Issue**: The test drops the translator through `withoutStrategies(GremlinToMatchStrategy.class)` on a repeat-bearing traversal and asserts zero boundary steps plus the native two-hop rows. Both assertions hold under two independent mechanisms — the missing translator and the veto — and the test cannot tell them apart. Its own Javadoc concedes the first half ("holds by construction here"). The track file already records this method as one of twelve measured vacuous-acceptance instances on the branch, and the shape is unchanged in the landed tree.

**Evidence** (FALSIFIABILITY CHECK, cert C3): delete the `withoutStrategies(...)` call and the test still passes, because `RepeatDeclineStrategy` vetoes the traversal on its own. Delete `RepeatDeclineStrategy.instance()` from `YTDBGraphImplAbstract`'s strategy list instead and the test still passes, because the source has no translator to engage. The one claim the method name makes — that the two mechanisms compose rather than interfere — is the claim nothing checks.

**Missing behavior**: that the veto marker is present *and* the traversal's own strategy list genuinely no longer carries the translator, so a `VetoedStrategies` wrapper cannot mask a failed removal.

**Suggested fix**:

```java
    admin.applyStrategies();

    // The two mechanisms have to be separated, or a zero-boundary count says nothing about either.
    assertThat(admin.getStrategies().getStrategy(GremlinToMatchStrategy.class))
        .as("withoutStrategies must really have removed the translator from this traversal's list")
        .isEmpty();
    assertThat(RepeatDeclineStrategy.isVetoed(admin))
        .as("and the veto still fires on top of that removal, so the two compose")
        .isTrue();
    assertThat(countBoundarySteps(admin))
        .as("a source without the translator must produce no boundary step")
        .isZero();
```

### TB4 [suggestion] The post-union positional-answer gate matches a method name rather than the override

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, method `everyPostUnionRecogniserStatesItsOwnPositionalAnswer` (line 1343)

**Issue**: The gate streams `getClass().getDeclaredMethods()` and matches `m.getName().equals("selectsPositionally")`. A private helper of that name, or a method with a different signature, satisfies it while the interface default stays in force. The test also asserts only that an override exists, never what any member answers, so `CountGlobalStepRecogniser` or `DedupGlobalStepRecogniser` flipping to `true` is caught only through the union equivalence cases.

**Evidence** (ASSERTION PRECISION CHECK, cert C4): the assertion is `PRECISE` about existence and silent about the contract. `StepRecogniser.selectsPositionally(Step)` takes one parameter, so `getDeclaredMethod(String, Class)` is available and exact; the looser form is what makes a renamed-parameter or narrowed-visibility override pass.

**Suggested fix**:

```java
    for (var recogniser : GremlinStepWalker.POST_UNION_RECOGNISERS) {
      // Resolve the exact override rather than any method of that name: a private helper or a
      // differently-signed method would satisfy a name match while the interface default stays live.
      var declaresOwn =
          Arrays.stream(recogniser.getClass().getDeclaredMethods())
              .anyMatch(m -> m.getName().equals("selectsPositionally")
                  && Arrays.equals(m.getParameterTypes(), new Class<?>[] {Step.class})
                  && Modifier.isPublic(m.getModifiers()));
      assertThat(declaresOwn).as(/* … */).isTrue();
    }

    // And pin the two answers the shipped allow-list depends on, so a flipped constant fails here
    // rather than only through a union equivalence case.
    var count = graph.traversal().V().count().asAdmin().getSteps().get(1);
    assertThat(CountGlobalStepRecogniser.INSTANCE.selectsPositionally(count)).isFalse();
    var dedup = graph.traversal().V().dedup().asAdmin().getSteps().get(1);
    assertThat(DedupGlobalStepRecogniser.INSTANCE.selectsPositionally(dedup)).isFalse();
```

### TB5 [suggestion] Three `SQLFunctionMeanTest` assertions build their failure message eagerly and NPE on the regression they exist to report

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java`, lines 51, 62, 92

**Issue**: `assertTrue("Expected Double, got " + result.getClass(), result instanceof Double)` evaluates `result.getClass()` before `assertTrue` runs. A regression that made `getResult()` return `null` — the exact failure the surrounding cases guard, since `computeMean` returns `null` on an empty aggregate — surfaces as a `NullPointerException` from the message expression instead of the intended type assertion. The same shape repeats at line 62 (`longInputAlsoDividesInFloatingPoint`) and line 92 (`bigDecimalInputKeepsExactArithmetic`).

**Evidence** (ASSERTION PRECISION CHECK, cert C5): the assertion is correct on the happy path and `WEAK` on the failure path — it reports a diagnostic-free NPE where a one-line AssertJ form reports the actual and expected types.

**Suggested fix** — the file already imports JUnit assertions only; switching these three to AssertJ (used throughout the rest of this track's tests) removes the eager evaluation and improves the message:

```java
    assertThat(mean.getResult()).isInstanceOf(Double.class);
    assertThat((Double) mean.getResult()).isCloseTo(30.75, within(1.0e-15));
```

### TC1 [should-fix] No BigDecimal fixture divides non-terminatingly, so `MathContext.DECIMAL128` is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java`, method `bigDecimalInputKeepsExactArithmetic` (line 85)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java:114-121`

**Missing scenario**: a `BigDecimal` aggregate whose sum divided by its contributor count has no exact decimal representation — for example `10` over three contributors.

**Why it matters**: `computeMean` divides with `bd.divide(new BigDecimal(iTotal), MathContext.DECIMAL128)`. The `MathContext` is the only thing standing between a non-terminating quotient and `ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result`, thrown out of the projection while a `SELECT mean(price) FROM …` is executing. The sibling `SQLFunctionAverage.computeAverage` reaches the same safety a different way (`bd.divide(new BigDecimal(iTotal), RoundingMode.HALF_UP)`), so the two functions differ precisely at this argument and an edit that aligned `mean`'s signature with `avg`'s — dropping the second argument rather than swapping it — would compile and ship the exception.

**Evidence** (INPUT DOMAIN TABLE entry, cert C6):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| `iSum` / `iTotal` (BigDecimal branch) | BigDecimal, int | exact quotient; **non-terminating quotient** | YES at `SQLFunctionMeanTest:85` (`1.00 + 2.00` over 2 → `1.50`, exact) / **NO** | the only BigDecimal fixture divides exactly, so both the `MathContext` and a bare `divide` produce `1.50` |

**Refutation considered**: the branch is not unreachable — `mean` is registered in `DefaultSQLFunctionFactory` (line 86 of the diff), so any user query over a `DECIMAL` property reaches it, and `PropertyTypeInternal.increment` keeps a BigDecimal sum a BigDecimal. Nor is the exception cosmetic: it escapes the projection rather than yielding `null`, so the query fails. No other test in the tree passes a BigDecimal to `mean` (searched `core/src/test/.../functions/math/`).

**Suggested test**:

```java
@Test
public void nonTerminatingBigDecimalQuotientDividesUnderDecimal128() {
  // 10 over three contributors has no exact decimal expansion. Without MathContext.DECIMAL128 the
  // divide throws ArithmeticException out of the projection instead of answering, which is why the
  // exact-dividing fixture above cannot stand in for this one.
  mean.execute(null, null, null, new Object[] {new BigDecimal("10")}, null);
  mean.execute(null, null, null, new Object[] {BigDecimal.ZERO}, null);
  mean.execute(null, null, null, new Object[] {BigDecimal.ZERO}, null);

  var result = (BigDecimal) mean.getResult();
  assertEquals(34, result.precision());
  assertEquals(0, new BigDecimal("3.333333333333333333333333333333333").compareTo(result));
}
```

### TC2 [should-fix] No edge-property fixture holds two runtime types under one key

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java` (the `seedMixedTypeFixture` edge block, lines 610-615)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeHopRecogniser.java:118-122`

**Missing scenario**: an `outE(L).has(p, gt(v))` where `p` holds a String on one edge and numbers on the others — the vertex-side equivalent of the `Loose` and `Anyp` classes the same fixture already builds for `HasStepRecogniser`.

**Why it matters**: the vertex side gets nine vertices across three classes with deliberately mixed types, and the whole comparability partition is asserted through `comparabilityPartition_matchesNativeForEveryLiteralType`. The edge side gets four same-typed Integers. The two code paths reach the same `GremlinPredicateAdapter.toFilter(container, typeGate, paramSink, rangeTypeGuard)` overload but decide the flag independently, so the vertex-side coverage transfers nothing: `HasStepRecogniser` computes `!ctx.atTraversalStart()` while `EdgeHopRecogniser` hardcodes `true`. An edge filter reverting to the unguarded form returns rows native excludes, silently, under a kill switch that defaults on.

**Evidence** (INPUT DOMAIN TABLE entry, cert C7):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| edge property under an order comparison | any stored type | same-type comparand; **cross-type comparand** | YES at `RangeTypeGuardEquivalenceTest:272` (`since` all Integer vs `lt(2025)`) / **NO** | grep over `core/src/test/.../gremlin/` finds no `addEdge` with two runtime types under one key |
| edge property under `eq` / `TextP` | any | — | YES (`has("w", 1)` ×3 classes, `containing(1)` ×1) | `isOrderComparison` excludes both, so the guard flag is unread |

**Refutation considered**: the shape is caller-writable (`g.V().outE("link").has("since", P.gt(2025)).inV()` is ordinary Gremlin), the property is undeclared so no static gate substitutes for the per-record one, and the divergence is a row-set change rather than an error. `PredicateTraversalEquivalenceTest:844` covers an edge filter end to end but with `TextP.containing`, which routes through `translateContains` and never reaches `translateCompare`.

**Suggested test**: see the fixture extension and test body in `TB1`'s suggested fix — one change closes both findings.

### TC3 [should-fix] The group value-side `mean` accumulator became reachable and has no equivalence case

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java` (the aggregate block, around line 1450)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslator.java:209` → `GremlinAggregateAssembler.resolveGroupValue` → `MatchProjectionBuilder.propertyAggregate("MEAN", …)`

**Missing scenario**: `g.V().group().by(k).by(__.values(v).mean())` over buckets whose values do not divide evenly.

**Why it matters**: `ValueAccumulator.AggregateFunction` carries a `MEAN` arm and `ByModulatorTranslator` maps `MeanGlobalStep` to it, so `resolveGroupValue` has always emitted `mean(alias.key)` into a grouped RETURN. Until this track registered `SQLFunctionMean` there was no `mean` SQL function to resolve, so the shape translated and then failed at execution. Registering the function fixed it silently. CLAUDE.md requires a regression test for a bug fix, and the closest cases cover a different path: `meanOverIntegerProperty_dividesInFloatingPoint` exercises the single-plan `PropertyAggregateStepRecogniser` route (`values(age).mean()`), and `MatchProjectionBuilderTest.propertyAggregate_mean_matchesParserShape` pins the AST without executing it. The group-side route reaches the same SQL function through a different builder call and a grouped projection.

**Evidence** (CLAIM G3, cert C8): `ByModulatorTranslator.java:209` maps `MeanGlobalStep` to `AggregateFunction.MEAN`; `GremlinAggregateAssembler.resolveGroupValue`'s `PropertyAggregate` arm passes `prop.function().name()` through `MatchProjectionBuilder.propertyAggregate`, which lowercases it to `mean`. No test in the tree drives a `by(...)` value modulator ending in `mean()` (grep over the strategy test package finds `values("age").count()` and `values("age").sum()` value-side bodies in `ByModulatorTranslatorTest` and no `mean` one).

**Refutation considered**: cross-group state bleed was the first worry and it does not hold — `AggregateProjectionCalculationStep.aggregate` stores one `AggregationContext` per group row and `SQLFunctionMean` is registered by class rather than as a shared instance, so each group accumulates independently (cert C19). That downgrades the finding from a live defect to a missing regression net, which is why it is `should-fix` rather than `blocker`.

**Suggested test**:

```java
/**
 * The group value side reaches the same {@code mean} SQL function through a different builder call
 * than {@code values(k).mean()} does, and it only started resolving when the function was
 * registered. The ages are chosen not to divide evenly per bucket, so an {@code avg} regression
 * shows up as an integer payload rather than as an equal one.
 */
@Test
public void groupValueSideMean_dividesInFloatingPointPerBucket() {
  graph.addVertex(T.label, "Person", "name", "Alice", "city", "NYC", "age", 30);
  graph.addVertex(T.label, "Person", "name", "Bob", "city", "NYC", "age", 25);
  graph.addVertex(T.label, "Person", "name", "Carol", "city", "LON", "age", 41);
  graph.tx().commit();

  assertEquivalent(
      "g.V().group().by(city).by(values(age).mean())",
      Recognition.RECOGNIZED,
      () -> graph.traversal().V().group().by("city").by(__.values("age").mean()));
}
```

### TC4 [should-fix] `keyModulatorPropertyKey` is new public API with no test, and its record-attribute arm is unobservable end to end

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslatorTest.java`

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslator.java:96-108`

**Missing scenario**: `keyModulatorPropertyKey` over each of its three documented outcomes — a property key, a record attribute (`by(T.id)` / `by(T.label)`), and an unrecognised body.

**Why it matters**: the method's whole job is the `.filter(ref -> !ref.recordAttr())` guard, which keeps `ByModulatorPresence` from writing `@rid IS DEFINED` or `@class IS DEFINED` onto an alias. Dropping that filter changes no row on any fixture in the tree — every record has a RID and a class — so the mistake is invisible to every equivalence case, including `terminatorsAfterGroup_decline`, which drives `group().by(T.label)` and would keep passing. It is not free, though: `ByModulatorPresence`'s own `@implNote` records that `MatchExecutionPlanner.estimateRootEntries` scores a filtered alias at `classCount / 2` against an unfiltered alias's `classCount + 1`, so a spurious presence conjunct can capture the plan root and reschedule the chain.

**Evidence** (INPUT DOMAIN TABLE entry, cert C9):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| `modulator` | `Traversal.Admin` | `by("age")` (property); `by(T.id)` / `by(T.label)` (record attr); `by(__.out())` (unrecognised); `null` | **NO** for all four | grep for `keyModulatorPropertyKey` finds one production caller (`ByModulatorPresence.java:41`) and zero test references |

**Refutation considered**: indirect coverage exists for the property arm — `groupByMissingKey_hasNoNullBucket` and `productiveByStrategy_configuredKeysInvertPerKey` both depend on the conjunct landing — but no path makes the record-attribute arm observable, and the unrecognised arm is shadowed by `resolveGroupKey` declining first, so the method is the only place where either can be checked.

**Suggested test** (three assertions, one per arm, in the class that already owns `classifyKey`'s siblings):

```java
/**
 * The presence conjunct's gate, read directly: a property key is returned, a record attribute is
 * not (every record has a RID and a class, so a conjunct on one filters nothing and only distorts
 * root selection), and an unrecognised body is not.
 */
@Test
public void keyModulatorPropertyKey_returnsPropertyKeysOnly() {
  assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.values("age").asAdmin()))
      .contains("age");
  assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.id().asAdmin())).isEmpty();
  assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.label().asAdmin())).isEmpty();
  assertThat(ByModulatorTranslator.keyModulatorPropertyKey(__.out("knows").asAdmin())).isEmpty();
  assertThat(ByModulatorTranslator.keyModulatorPropertyKey(null)).isEmpty();
}
```

### TC5 [should-fix] The union fold-latch boundary is only tested on a one-step prefix

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionArmCrossTypeRange_isGuardedAndAgreesWithNative` (line 729)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionForkHostImpl.java:92` (`walk(forked, prefix.size())`) and `GremlinStepWalker.dispatchAll`'s latch update (`cursor.position() < childScopeBoundary`)

**Missing scenario**: a union whose recognised prefix carries its own leading `has` — `g.V().has("name", P.gt(27)).union(__.out("knows"), __.in("knows"))` on a fixture where `name` holds mixed runtime types.

**Why it matters**: the only test of the child-scope boundary uses a bare `g.V().union(...)`, whose prefix is a single `GraphStep`. On that shape the latch closes after index 0, so both plausible off-by-ones in the *permissive* direction (`<=` instead of `<`, or `prefix.size() + 1`) leave the arm's `has` reading as folded and the existing test catches them. The *restrictive* direction is invisible: with a two-step prefix `[GraphStep, HasStep]`, a boundary of `prefix.size() - 1` closes the latch before the prefix's own `HasStep` is classified, so the prefix filter is treated as unfolded and takes the per-record guard. Natively `YTDBGraphStepStrategy.rebuildTraversal` folds it, so the translated arm would then answer with the comparator's partition where native answers with SQL ordering — an under-emission on a shape a caller writes routinely.

**Evidence** (CLAIM G5, cert C10): the latch term is `cursor.position() < childScopeBoundary`; for `prefix = [GraphStep]` the only decision point is `1 < 1`. Every value of the boundary that differs from `prefix.size()` by one in the permissive direction flips that comparison and is caught; a boundary of `0` or `prefix.size() - 1` also flips it and is caught on this shape by accident, but not the case where the prefix has an interior `HasStep` whose classification the shorter boundary changes. No test in the tree spells `has(...).union(...)` or `hasLabel(...).union(...)` (grep over the strategy test package returns nothing).

**Refutation considered**: the shape is translatable — `UnionStepRecogniser` forks on the recognised prefix, and a leading `has` plus two hop arms leaves an empty post-union suffix, which `postUnionSuffixTranslatable` accepts vacuously. `RangeTypeGuardEquivalenceTest.foldedPositions_keepTranslatingWithTheGraphStepComparatorsAnswer` covers a filtered prefix in the *single-plan* walk, which never passes a boundary, so it exercises a different code path.

**Suggested test** (in `UnionTraversalEquivalenceTest`, beside the existing union-guard case):

```java
/**
 * A filtered prefix before the union. The prefix's own {@code has} is folded natively, so it must
 * NOT take the per-record guard, while the arms' containers must. The existing bare-{@code g.V()}
 * case cannot separate the two, because its prefix is one step and the latch has a single decision
 * point; here the boundary has to fall after the prefix's {@code HasStep} rather than before it.
 */
@Test
public void unionWithFilteredPrefix_keepsThePrefixFoldedAndAgreesWithNative() {
  // name holds a String on two vertices and the Integer 99 on a third: SQL ordering ranks every
  // String above 27, the comparator refuses the comparison, so the two readings differ.
  var zed = graph.addVertex(T.label, "Person", "name", "Zed");
  var abe = graph.addVertex(T.label, "Person", "name", "Abe");
  graph.addVertex(T.label, "Person", "name", 99);
  zed.addEdge("knows", abe);
  graph.tx().commit();

  assertEquivalent(
      "g.V().has(name, gt(27)).union(out(knows), in(knows)) — folded prefix, unfolded arms",
      Recognition.RECOGNIZED_MULTI_PLAN,
      () -> graph.traversal().V().has("name", P.gt(27))
          .union(__.out("knows"), __.in("knows")));
}
```

### TC6 [suggestion] No end-to-end case binds a constraint onto an intermediate alias of a multi-hop chain

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java` and `PredicateTraversalEquivalenceTest.java` (the post-hop-constraint blocks)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:854-895`

**Missing scenario**: `g.V(marko).out("knows").has("age", 32).out("created")` — a filter on an alias that is neither the plan root nor the terminal projection.

**Why it matters**: `bindPathItemConstraints` walks `pattern.aliasToNode.values()` and then each node's `out` edges, so it visits every path item rather than only the ones hanging off the root. All five unit tests build a single-edge pattern, and all four equivalence cases (`postHopHasId_onNonRootTarget_…`, `postHopHasLabel_onNonRootTarget_…`, `edgePathItemFilter_survivesTargetConstraintBinding`, `postHopHas_pinnedOrigin_matchesNative`) put the constraint on the last hop's target, which is also the returned alias. Restricting the pass to the root node's out-edges — a plausible simplification given the Javadoc's emphasis on the root — would keep all nine green and silently drop the middle filter, returning an over-large multiset with no error.

**Evidence** (INPUT DOMAIN TABLE entry, cert C11):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| pattern depth × constrained alias | pattern | 1 hop, target constrained; **2 hops, interior alias constrained** | YES ×4 equivalence + ×5 unit / **NO** | grep for `.out(…).has(…).out(` over the gremlin test tree returns nothing |

**Refutation considered**: the loop shape makes the behaviour correct today, so this is a net rather than a live defect — hence `suggestion`. It is not covered indirectly: the modern-graph fixture makes the shape discriminating (marko knows vadas and josh; only josh created anything, so dropping josh's `age` filter adds vadas's zero `created` edges and changes nothing — the fixture needs the filter to *exclude* a productive neighbour, which `age` on vadas versus josh supplies).

**Suggested test**:

```java
@Test
public void interiorAliasFilter_onATwoHopChain_returnsSameMultisetAsNative() {
  var modern = ModernGraphFixture.seed(graph, session);
  var markoId = modern.marko().id();
  Supplier<GraphTraversal<?, ?>> traversal =
      () -> graph.traversal().V(markoId).out("knows").has("age", 32).out("created");

  // Discriminating premise: without the interior filter both of marko's knows-neighbours are
  // traversed, and vadas contributes nothing — so widen it to a filter vadas would pass.
  assertThat(nativeSortedIds(() -> graph.traversal().V(markoId).out("knows").out("created")))
      .as("the unfiltered two-hop reaches josh's two creations only, so the interior filter has to "
          + "be the discriminating one — assert the filtered count differs")
      .hasSize(2);
  assertEquivalent(
      "g.V(marko).out(knows).has(age, 32).out(created)", Recognition.RECOGNIZED, traversal);
}
```

### TC7 [suggestion] `SQLMatchFilter.setClassName`'s null argument and its append-beside-an-existing-WHERE path are untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchFilterTest.java` (lines 24, 41)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchFilter.java:122-136`

**Missing scenarios**: `setClassName(null)`, and `setClassName` on a filter that already carries a `WHERE` but no class.

**Why it matters**: the test class states its own purpose as making the contract real "for the next caller, which may not carry the guard", then tests only the two arms the current callers take. `setClassName(null)` wraps a null into `new SQLExpression(new SQLIdentifier(null))` and appends it, so `getClassName(null)` afterwards returns whatever an `SQLIdentifier` with a null value renders as — a shape neither the append test nor the rewrite test can see. The second gap is the exact combination `GremlinStepWalker.bindPathItemConstraints` produces: it calls `setClassName` before `setFilter`, so on a path item that already carries an edge predicate the append arm runs on a filter with a populated item list, and nothing checks that the existing `WHERE` survives the append.

**Evidence** (INPUT DOMAIN TABLE entry, cert C12):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| `className` | String | name; **null**; blank | YES / **NO** / **NO** | both cases pass a real class name |
| receiver `items` | list | alias-only; class-carrying; **where-carrying, class-free** | YES at line 24 / YES at line 41 / **NO** | `bindPathItemConstraints_andComposesWithExistingItemWhere` passes `Map.of()` for classes, so it never reaches `setClassName` |

**Refutation considered**: the null argument is not reachable from either in-tree caller (`bindPathItemConstraints` guards on `className != null`, `mergedTargetFilter` on the same), so the value of the null case is contract documentation rather than defect prevention. The where-plus-append combination *is* reachable end to end, which is why it carries the weight here.

**Suggested test**:

```java
/**
 * The combination {@code GremlinStepWalker.bindPathItemConstraints} produces: it sets the class
 * before the WHERE, so on an item that already carries a predicate the append arm runs against a
 * populated item list. The existing WHERE has to survive the append, or a bound class silently
 * drops the item's own filter.
 */
@Test
public void setClassName_onWhereCarryingFilter_appendsWithoutLosingTheWhere() {
  var wb = new MatchWhereBuilder();
  var filter = SQLMatchFilter.fromAliasAndClass("t", null);
  filter.setFilter(wb.wrap(wb.eq("since", MatchLiteralBuilder.toLiteral(2020L))));

  filter.setClassName("Person");

  assertThat(filter.getClassName(null)).isEqualTo("Person");
  var sb = new StringBuilder();
  filter.getFilter().getBaseExpression().toGenericStatement(sb);
  assertThat(sb.toString()).contains("since");
}
```

### TC8 [suggestion] `SQLFunctionMean`'s input domain leaves three boundaries uncovered

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java` (line 97 onwards)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java:76-121`

**Missing scenarios**, in descending order of value:

1. **A promoted BigDecimal sum.** `PropertyTypeInternal.increment(BigDecimal, Integer)` decides whether `computeMean` takes the exact branch or the `doubleValue()` one, and the fixture set holds only pure-BigDecimal and pure-primitive rows. A `mean(price)` over a `DECIMAL` column with one Integer row in it goes down a branch nothing pins.
2. **An empty multi-value.** `MultiValue.getMultiValueIterable(List.of())` iterates nothing, so `sum` stays null and `getResult()` returns null. Distinct from `nonNumericSingleArgumentLeavesTheAggregateEmpty`, which never enters the multi-value branch.
3. **`Double.NaN` and the infinities.** `sum` accumulates them and `doubleValue() / total` propagates, so `mean` returns NaN rather than null. `RangeTypeGuardEquivalenceTest.nanValue_isExcludedByBothArmsEvenThoughTheGuardAdmitsIt` shows the tree already cares about NaN on the comparison side; the aggregate side is unstated.

**Why it matters**: item 1 is a branch-selection boundary in a brand-new function on a code path (`SELECT mean(decimalField)`) reachable from any user query. Items 2 and 3 fix the documented contract ("a mean over no contributor at all is `null` rather than zero") at its edges.

**Evidence** (INPUT DOMAIN TABLE entry, cert C13):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| accumulated `sum` runtime type | Number | Integer, Long, Double, BigDecimal, **BigDecimal+Integer mix** | YES ×4 (lines 42, 56, 67, 85) / **NO** | line 67 mixes Integer and Long only |
| `iParams[0]` as MultiValue | Collection | 3 elements with a null, 3 with a String, **empty** | YES ×2 (lines 97, 115) / **NO** | no empty-collection case |
| numeric value | double | finite, **NaN**, **±Infinity** | YES / **NO** / **NO** | no non-finite fixture |

**Refutation considered**: none of the three is correct by construction. The BigDecimal-mix branch selection depends on `increment`'s promotion rule, which lives outside this class; the empty-collection path and the NaN path both run real code and produce observable results.

**Suggested test**:

```java
@Test
public void bigDecimalMixedWithIntegerStillDividesUnderTheExactBranch() {
  // increment() decides which branch computeMean takes, and that decision lives outside this class.
  mean.execute(null, null, null, new Object[] {new BigDecimal("1.00")}, null);
  mean.execute(null, null, null, new Object[] {2}, null);

  var result = mean.getResult();
  assertTrue(result instanceof BigDecimal);
  assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) result));
}

@Test
public void emptyCollectionArgumentLeavesTheAggregateEmpty() {
  mean.execute(null, null, null, new Object[] {List.of()}, null);
  assertNull(mean.getResult());
}

@Test
public void nanContributorPropagatesRatherThanEmptyingTheAggregate() {
  mean.execute(null, null, null, new Object[] {1.0}, null);
  mean.execute(null, null, null, new Object[] {Double.NaN}, null);
  assertTrue(Double.isNaN((double) mean.getResult()));
}
```

### TC9 [suggestion] No post-union slice selects an empty window

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionThenSliceThenCount_sliceTheConcatenation` (line 401)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java:269-290` (`normalize`) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/PostConcatOp.java:32-38`

**Missing scenario**: `union(...).limit(0).count()` and `union(...).range(3, 2).count()` — the two spellings that normalise to a zero-width window.

**Why it matters**: `normalize` maps `limit(0)` to `NormalizedRange(0, 0, noop = false)` and `range(high < low)` to `NormalizedRange(low, 0, false)`, so both build a real `PostConcatOp.Range` with `limit == 0`. `PostConcatOp.Range`'s Javadoc states the operational contract that motivates the type — "Early-stops the concatenator so unopened children never start" — and a zero-width window is where that claim is strongest and cheapest to check: no child should be opened at all, and the count must be `0`. The four bounds the existing case covers (`limit(2)`, `skip(1)`, `range(1, 3)`, `range(1, -1)`) all keep at least one row, so the zero branch of the early stop is unexercised. The compact constructor's `skip < 0` rejection is likewise unpinned.

**Evidence** (INPUT DOMAIN TABLE entry, cert C14):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| post-union `Range(skip, limit)` | long, long | `(0, 2)`, `(1, -1)`, `(1, 2)`; **`(0, 0)`**; **`(3, 0)`** | YES ×4 at line 401 / **NO** / **NO** | every asserted count is ≥ 2 |
| `Range` compact constructor | long | `skip >= 0`; **`skip < 0`** | YES implicitly / **NO** | `normalize` returns null for `low < 0`, so the throw has no test |

**Refutation considered**: `limit(0)` is caller-writable and survives strategy application (the DSL rejects only `limit(-n)`), and `range(3, 2)` constructs because `RangeGlobalStep`'s constructor rejects `low > high` only when both bounds are set — which they are here, so this spelling in fact throws; `range(3, 3)` is the constructible zero-width form and is the one to use. That correction is why the suggested test below uses `range(3, 3)` rather than `range(3, 2)`.

**Suggested test**:

```java
/**
 * A zero-width post-union window. Both spellings normalise to a real {@link PostConcatOp.Range}
 * with limit 0, which is where the early-stop contract on that record is strongest: no arm should
 * be opened and the count must be zero. Every bound the sibling case asserts keeps rows, so the
 * zero branch is otherwise unexercised.
 */
@Test
public void unionThenEmptyWindowThenCount_countsZero() {
  var aliceId = seedWideFanOut();
  assertMultiPlanEngaged(
      () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(0).count());
  assertMultiPlanEngaged(
      () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(3, 3).count());

  setTranslatorEnabled(true);
  assertThat(graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(0).count().next())
      .as("limit(0) selects nothing, so no arm need be opened")
      .isEqualTo(0L);
  assertThat(
      graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(3, 3).count().next())
      .as("a zero-width range selects nothing either")
      .isEqualTo(0L);
}
```

### TC10 [suggestion] `propertyMethodCall`'s blank-argument contract has no test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/ProjectionExpressionFactoriesTest.java` (the two new cases at the end of the file)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/ProjectionExpressionFactories.java:161-170`

**Missing scenario**: `propertyMethodCall("", "type")` and `propertyMethodCall("age", "")` — the two `requireNonBlank` calls the factory opens with.

**Why it matters**: the new factory is the only route by which a caller-supplied property key reaches an SQL method call, and its two new tests cover rendering parity and the injection surface while leaving both validation calls unexercised. `MatchWhereBuilder.typeIn` supplies the key from `HasContainer.getKey()`, which TinkerPop permits to be empty for some container shapes, and the sibling `typeIn_rejectsAnEmptyTypeList` shows the class already treats an argument-validation case as worth pinning on the other parameter.

**Evidence** (INPUT DOMAIN TABLE entry, cert C15):

| Parameter/State | Type | Boundary values | Currently tested? | Evidence |
|---|---|---|---|---|
| `propertyKey` | String | ordinary; metacharacter-bearing; **empty**; **blank**; **null** | YES / YES (line 11782 of the diff) / **NO** ×3 | the two new cases pass `"age"` and an injection payload |
| `methodName` | String | `"type"`; **empty**; **null** | YES / **NO** ×2 | no case varies the method name |

**Refutation considered**: `MatchWhereBuilder.typeIn` is the only production caller and it always passes `"type"`, so the method-name arm is documentation-only. The key arm is caller-supplied, which is where the value sits.

**Suggested test**:

```java
/** Both arguments are rejected blank rather than rendered into a malformed accessor. */
@Test
public void propertyMethodCall_rejectsBlankArguments() {
  assertThatThrownBy(() -> ProjectionExpressionFactories.propertyMethodCall("", "type"))
      .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> ProjectionExpressionFactories.propertyMethodCall("  ", "type"))
      .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> ProjectionExpressionFactories.propertyMethodCall("age", " "))
      .isInstanceOf(IllegalArgumentException.class);
}
```

## Evidence base

#### C1 FALSIFIABILITY CHECK — `undeclaredEdgePropertyRange_keepsTranslatingAndKeepsItsRows` under the guard's removal — CONFIRMED

Mutating `EdgeHopRecogniser.java:122` from `/* rangeTypeGuard= */ true` to `false` reddens nothing: the fixture's four `since` values are Integers and the comparand is Integer `2025`, so `since.type() IN [numeric block]` is true for every candidate row and both translations return `[alpha, bravo]`. Every other edge-property `has` in the tree uses `eq` or `TextP.containing`, neither of which `isOrderComparison` admits.

#### C2 FALSIFIABILITY CHECK — the direction flag on `resolveSortItem`'s new property branch — CONFIRMED

Replacing `ascending` with `true` at `OrderGlobalStepRecogniser.java:91` keeps the suite green. `ProjectionEquivalenceTest.orderAfterValues_sortsByTheValueNotTheRid` sorts ascending; `OrderRangeStepRecogniserTest.orderByProperty_desc` spells `by("name", Order.desc)`, a keyed modulator that resolves through `ByModulatorTranslator.translateKeyModulatorOrderItem` and never reaches the identity branch.

#### C3 FALSIFIABILITY CHECK — `translatorAlreadyRemovedFromTheSource_needsNoVeto` under either mechanism's removal — CONFIRMED

Removing the `withoutStrategies(...)` call leaves the test green (the veto declines); removing `RepeatDeclineStrategy.instance()` from the graph's strategy list also leaves it green (the source has no translator). Neither assertion distinguishes the two, and the test's own Javadoc concedes the first half.

#### C4 ASSERTION PRECISION CHECK — `everyPostUnionRecogniserStatesItsOwnPositionalAnswer` — CONFIRMED

The reflection predicate is a name equality over `getDeclaredMethods()`, so any method or private helper named `selectsPositionally` satisfies it regardless of signature or visibility. `StepRecogniser.selectsPositionally(Step)` has one parameter, so an exact resolution is available. No member's answer is asserted anywhere in the class.

#### C5 ASSERTION PRECISION CHECK — eager failure messages at `SQLFunctionMeanTest` 51, 62, 92 — CONFIRMED

`"Expected Double, got " + result.getClass()` is evaluated before `assertTrue` runs, so a `null` result — the value `computeMean` returns for an empty aggregate — throws `NullPointerException` from the message expression rather than failing with the intended type comparison.

#### C6 CLAIM G1 + REFUTATION CHECK — `MathContext.DECIMAL128` in `computeMean` — CONFIRMED

`SQLFunctionMean.java:119` divides with `MathContext.DECIMAL128`; the only BigDecimal fixture (`1.00 + 2.00` over 2) yields `1.50`, which a bare `divide` produces identically. Reachable: `mean` is registered in `DefaultSQLFunctionFactory`, so `SELECT mean(decimalField)` reaches it, and `PropertyTypeInternal.increment` keeps a BigDecimal sum a BigDecimal. Not trivially correct: without the `MathContext` a non-terminating quotient throws `ArithmeticException` out of the projection. Not covered indirectly: no other test in `core/src/test/.../functions/math/` passes a BigDecimal to `mean`, and `SQLFunctionAverage` reaches its own safety through `RoundingMode.HALF_UP`, so the two functions diverge exactly here.

#### C7 CLAIM G2 + REFUTATION CHECK — cross-type edge-property range — CONFIRMED

No `addEdge` call anywhere under `core/src/test/.../gremlin/` stores two runtime types under one property key. The shape is caller-writable, the property is undeclared so no static type gate substitutes, and the divergence is a silent row-set change. `PredicateTraversalEquivalenceTest:844` covers an edge filter end to end but through `TextP.containing`, which never reaches `translateCompare`.

#### C8 CLAIM G3 + REFUTATION CHECK — group value-side `mean` — CONFIRMED

`ByModulatorTranslator.java:209` maps `MeanGlobalStep` to `AggregateFunction.MEAN`; `resolveGroupValue` passes `prop.function().name()` to `MatchProjectionBuilder.propertyAggregate`, which lowercases it to `mean`. Before this track no `mean` SQL function existed, so the emitted projection had no resolver. No test drives a value modulator ending in `mean()`.

#### C9 CLAIM G4 + REFUTATION CHECK — `keyModulatorPropertyKey` — CONFIRMED

Grep finds one production caller (`ByModulatorPresence.java:41`) and zero test references. The record-attribute arm is unobservable at the row level (`@rid`/`@class` are present on every record), so `terminatorsAfterGroup_decline`'s `group().by(T.label)` case would keep passing with the `.filter(ref -> !ref.recordAttr())` guard deleted, while `ByModulatorPresence`'s own `@implNote` documents the root-selection distortion a spurious presence conjunct causes.

#### C10 CLAIM G5 + REFUTATION CHECK — union fold-latch boundary — CONFIRMED

The latch term is `cursor.position() < childScopeBoundary`, and the sole test shape (`unionArmCrossTypeRange_isGuardedAndAgreesWithNative`) has a one-step prefix, so `1 < 1` is the only decision. A restrictive off-by-one changes the classification of a prefix's *interior* `HasStep`, which no test shape contains: grep over the strategy test package finds no `has(...).union(...)` or `hasLabel(...).union(...)` spelling. `RangeTypeGuardEquivalenceTest.foldedPositions_…` covers a filtered prefix in the single-plan walk, which passes `NO_CHILD_SCOPE` and so exercises a different path.

#### C11 CLAIM G6 + REFUTATION CHECK — interior-alias constraint binding — CONFIRMED

All five `bindPathItemConstraints` unit tests build a single-edge pattern, and all four equivalence cases put the constraint on the terminal hop target. Grep for `.out(…).has(…).out(` over the gremlin test tree returns nothing. The behaviour is correct today (the pass iterates every node's out-edges), so this is a missing net rather than a live defect — hence the `suggestion` grading.

#### C12 CLAIM G7 + REFUTATION CHECK — `SQLMatchFilter.setClassName` arms — CONFIRMED (partially refuted on the null arm)

The where-carrying-append combination is reachable end to end: `bindPathItemConstraints` calls `setClassName` before `setFilter`, so a target item already holding an edge predicate takes the append arm against a populated item list, and `bindPathItemConstraints_andComposesWithExistingItemWhere` passes `Map.of()` for classes and never reaches `setClassName`. The null-argument arm is **refuted as reachable** — both in-tree callers guard on `className != null` — so its value is contract documentation only, which is why the finding is a `suggestion` and leads with the reachable half.

#### C13 CLAIM G8 + REFUTATION CHECK — `SQLFunctionMean` input domain — CONFIRMED

Three boundaries are outside the fixture set: a BigDecimal sum promoted from a mixed row set (line 67 mixes Integer and Long only, so the branch selection in `computeMean` is decided by `PropertyTypeInternal.increment` and unpinned), an empty multi-value (lines 97 and 115 both pass three-element collections), and non-finite doubles. None is correct by construction; all three run real code and produce observable results.

#### C14 CLAIM G9 + REFUTATION CHECK — post-union zero-width window — CONFIRMED, with one spelling corrected

`normalize` maps `limit(0)` to `NormalizedRange(0, 0, noop = false)` and a `high < low` range to `NormalizedRange(low, 0, false)`, so both build a real `PostConcatOp.Range` with `limit == 0`. Every bound the existing case asserts (`limit(2)`, `skip(1)`, `range(1, 3)`, `range(1, -1)`) keeps rows, so the zero branch of the early-stop contract is unexercised, as is the compact constructor's `skip < 0` throw. **Correction inside the claim:** `range(3, 2)` is not constructible — `RangeGlobalStep`'s constructor rejects `low > high` when both bounds are set — so the suggested test uses `range(3, 3)`, which does construct and normalises to the same zero-width window.

#### C15 CLAIM G10 + REFUTATION CHECK — `propertyMethodCall` argument validation — CONFIRMED on the key, refuted on the method name

Neither new case in `ProjectionExpressionFactoriesTest` varies either argument towards blank or null. The key arm carries the value: `MatchWhereBuilder.typeIn` forwards `HasContainer.getKey()`, which is caller-supplied. The method-name arm is **refuted as reachable** — `typeIn` is the only production caller and always passes `"type"` — so it is documentation only.

#### C16 CLAIM — `assertNativeFanOut`'s fan-out check compares two call-site literals (track-file residual TS13) — REFUTED

The track file records TS13 as an unfixed observation: the helper's fan-out check "compares two literals passed at the call site rather than anything derived from the fixture, so the discrimination is really done by the `hasSize` pins beside it". That is not true of the landed code. `EdgeTraversalEquivalenceTest.assertNativeFanOut` drains both shapes first and then asserts `assertThat(joinRows.size()).isGreaterThan(filterRows.size())` — both operands come from the drained lists, not from `expectedFilterRows` / `expectedJoinRows`. The helper's own Javadoc states the same ("measured too, over the same two drained lists rather than over the caller's expectations"). The guard is fixture-derived, so it does survive a fixture edit that flattens the fan-out, and the residual is closed rather than open. No finding.

Two secondary checks on the same helper also pass. The two `hasSize` pins remain as independent witnesses, so the helper is strictly stronger than the pins alone rather than a substitute for them. And the helper runs with the translator forced off for both drains, so it measures the fixture's native fan-out rather than comparing a translated arm against itself.

#### C17 CLAIM — the fold latch's "any `GraphStep`, not only the first" branch needs a mid-traversal `V()` test — REFUTED as unreachable

`GremlinStepWalker.dispatchAll`'s latch update opens a fold on `head instanceof GraphStep<?, ?>`, and `RecognitionContext.atTraversalStart()`'s Javadoc emphasises that this mirrors `rebuildTraversal`'s restart on any `GraphStep` rather than only the first. That suggested a missing test for `g.V().out().V().has(k, gt(v))`. The shape cannot reach the branch: `StartStepRecogniser.recognize` opens with `if (ctx.boundaryAlias() != null) return Outcome.DECLINE;`, and the boundary is pinned by the first `GraphStep`, so a second one declines the whole walk before the latch is updated. The branch exists to keep the latch's *rule* aligned with `rebuildTraversal` rather than to serve a translatable shape, and no test can exercise it. No finding.

#### C18 CLAIM — `ShapeClassifier.aggregateShapeForCall` is case-sensitive, so `SELECT MEAN(age)` still falls through to `RECORD` — REFUTED

The new `case "mean", "median", "mode", "variance", "stddev", "percentile" -> K0_NONE` arm is matched against lowercase literals, and `ShapeClassifierTest`'s two new cases both use lowercase SQL. If the switch read the raw name, an upper-case spelling would reach `RECORD` and hand a RID-less scalar row to the per-record delta builder — the exact defect the arm closes. It does not: line 975 normalises with `name.getStringValue().toLowerCase(Locale.ROOT)` before the switch, so every spelling routes identically. All six names are also real registered functions (`SQLFunctionMean`, `SQLFunctionMedian`, `SQLFunctionMode`, `SQLFunctionVariance`, `SQLFunctionStandardDeviation` → `"stddev"`, `SQLFunctionPercentile`), so no arm of the new case list is dead. No finding.

#### C19 CLAIM — a grouped `mean` bleeds accumulator state across buckets — REFUTED

`SQLFunctionMean` keeps `sum` and `total` as instance fields, so a single function instance shared across `GROUP BY` buckets would carry the first bucket's contributors into every later one and answer wrongly on all but the first — a silent wrong answer on the newly-reachable group-side path in `TC3`. Two mechanisms rule it out. `AggregateProjectionCalculationStep.aggregate` stores one `AggregationContext` per group row (`preAggr.setTemporaryProperty(alias, aggrCtx)`, created only when the lookup misses), so each bucket accumulates through its own context. And `DefaultSQLFunctionFactory` registers `mean` by class (`register(SQLFunctionMean.NAME, SQLFunctionMean.class)`) rather than as a shared instance, matching how the other stateful aggregates including `avg` are registered, so `SQLEngine.getFunction` hands out a fresh instance. `TC3` is therefore a missing regression net on a fixed path, not a live defect, which is what holds it at `should-fix`.
