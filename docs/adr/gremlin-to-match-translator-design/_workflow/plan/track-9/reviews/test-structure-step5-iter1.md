<!-- MANIFEST
findings: 9   severity: {blocker: 0, should-fix: 3, suggestion: 6}
index:
  - {id: TS1, sev: should-fix, loc: MatchProjectionBuilderTest.java:29, anchor: "### TS1 ", cert: n/a, basis: "test Javadoc states three facts this step reversed; the mean(...) projection production now emits is pinned by no builder-level test"}
  - {id: TS2, sev: should-fix, loc: ProjectionEquivalenceTest.java:669, anchor: "### TS2 ", cert: n/a, basis: "new decline case has no same-shape positive control and no hand-computed native answer, against the convention its own class header states"}
  - {id: TS3, sev: should-fix, loc: ProjectionEquivalenceTest.java:609, anchor: "### TS3 ", cert: n/a, basis: "Javadoc says all four keyless spellings decline; the body asserts three, and the missing one is the closest to the deleted derivation"}
  - {id: TS4, sev: suggestion, loc: WalkerContext.java:333, anchor: "### TS4 ", cert: n/a, basis: "the configured-keys state of byModulatorIsProductive is reached by no test; ByModulatorPresence has no unit test at all"}
  - {id: TS5, sev: suggestion, loc: ProjectionEquivalenceTest.java:199, anchor: "### TS5 ", cert: n/a, basis: "ages 10/20/30 give an integral mean and the canonicaliser folds it to a long, so the pre-existing mean arm passes under avg too"}
  - {id: TS6, sev: suggestion, loc: ProjectionEquivalenceTest.java:500, anchor: "### TS6 ", cert: n/a, basis: "an order() test takes the multiset helper where the class's other three take the ordered one, and the fixture supports the stronger form"}
  - {id: TS7, sev: suggestion, loc: ProjectionEquivalenceTest.java:536, anchor: "### TS7 ", cert: n/a, basis: "as(a, n) labels one element twice, so both labels resolve to the same internal alias and per-label conjunct targeting is unobservable"}
  - {id: TS8, sev: suggestion, loc: SQLFunctionMeanTest.java:107, anchor: "### TS8 ", cert: n/a, basis: "the non-numeric test covers one of three entry shapes; the two it skips cast unchecked and throw instead of leaving the aggregate empty"}
  - {id: TS9, sev: suggestion, loc: GremlinProjectionRecogniserTest.java:37, anchor: "### TS9 ", cert: n/a, basis: "the SQLExpression-to-record type change left an existing toString() assertion matching a record dump rather than the expression"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] A builder test documents the opposite of what the translator now emits, and the emitted shape is pinned nowhere

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchProjectionBuilderTest.java`, method `propertyAggregate_avg_matchesParserShape` (Javadoc line 29, method line 37)

**Issue**: Three claims in that Javadoc were true before this step and are false after it.

- "The Gremlin translator maps `mean()` to the SQL aggregate `avg`" — `PropertyAggregateStepRecogniser:28-31` now emits `"mean"`.
- "There is no `mean` SQL function" — `SQLFunctionMean` arrives in this diff and is registered at `DefaultSQLFunctionFactory:86`.
- "A raw `mean(...)` projection is never produced by the pipeline and would fail plan-build, so it is not worth pinning" — two production paths produce it: `GremlinAggregateAssembler:146` for the single-plan property aggregate and `GremlinAggregateAssembler:283` for the group value-side accumulator.

The class exists to pin builder output against the parser's own AST for the same text. `mean` is a brand-new function name, so whether `MatchProjectionBuilder.propertyAggregate("mean", …)` and the parser agree on `mean(age)` is exactly the question this class answers for every other shape, and it is the one shape the class declines to answer — on grounds the step removed. The step's own comment at `GremlinAggregateAssembler:280-283` points a reader at `PropertyAggregateStepRecogniser` "for why avg() is the wrong target", so the cross-reference chain now runs into a test that says the opposite.

**Suggestion**: Rewrite the Javadoc against current behaviour and add `propertyAggregate_mean_matchesParserShape` asserting `MatchProjectionBuilder.propertyAggregate("mean", "age")` against the parsed `mean(age)`. Keep the `avg` case — hand-written SQL still reaches it — so the pair states the distinction the new function was created for.

### TS2 [should-fix] The new group-then-count decline has no positive control and no measured native answer

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `countAfterGroup_declines` (line 669)

**Issue**: One assertion in that test can fail: `boundaryOn == 0`. The payload equality is tautological for a `DECLINED` case, because both runs take the native pipeline. So the test holds whenever `g.V().group().by(T.label).count()` fails to translate, for any reason at all, and it names one of them.

Nothing in the suite pins that the prefix translates. `group_bare_matchNative` (line 162) covers `group()` and `group_byName_matchNative` (line 174) covers `group().by("name")`; `by(T.label)` appears in no group test in either the equivalence class or `GremlinAggregateRecogniserTest`. Today the prefix does translate — `ByModulatorTranslator.classifyKey:118-120` maps a `TokenTraversal(T.label)` to `FieldRef(true, "@class")`, `resolveGroupKey` returns a non-null expression, and the default value traversal resolves the same way `group_bare_matchNative` already proves — which leaves `ctx.groupBy() != null` at `GremlinAggregateAssembler:75` as the only live decline reason. That reasoning is read out of the code, which is what the class's own B1 header (lines 353-359) says a decline case must not depend on: "Each case asserts the decline (no boundary step, on/off parity) and the hand-computed native answer, so the parity is not vacuous." Five sibling decline tests follow that rule (lines 369, 380, 391, 413, 431). This one does not.

**Suggestion**: Two lines inside the existing test. Add the same-shape positive control, `assertEquivalent("g.V().group().by(label)", Recognition.RECOGNIZED, () -> graph.traversal().V().group().by(T.label))`, so the prefix's recognisability is measured rather than assumed; and add `assertThat(graph.traversal().V().group().by(T.label).count().next()).isEqualTo(1L)` to pin the native answer the Javadoc already states.

### TS3 [should-fix] The keyless-decline test counts four spellings in its Javadoc and asserts three

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `keylessValueMapAndElementMap_decline` (Javadoc line 609, method line 616)

**Issue**: The Javadoc says "so all four spellings decline". The body covers `valueMap()`, `valueMap(true)` and `elementMap()`. The fourth is enumerated in the gate's own comment at `GremlinProjectionAssembler:110-111`: "This covers valueMap(), elementMap(), valueMap(true) and valueMap().with(WithOptions.tokens) alike."

The omitted spelling is the one nearest the defect. `with(WithOptions.tokens)` is the second route to a non-zero token bit set on a step with no key list, and the derivation the step deleted — `isElementMap = elementMapTokens != 0` — keyed off exactly that bit set. Of the three spellings that are asserted, `valueMap()` and `elementMap()` reach the gate through `propertyKeys.length == 0` and would have declined under the old code too, so `valueMap(true)` carries the whole discrimination on its own.

**Suggestion**: Add the fourth assertion — `graph.traversal().V().valueMap().with(WithOptions.tokens)` — or drop "four" from the Javadoc and name which of the listed spellings the old code accepted, so a reader can see which assertion is load-bearing.

### TS4 [suggestion] One of `byModulatorIsProductive`'s three states is reached by no test

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContext.java`, method `byModulatorIsProductive` (line 333); the only covering test is `ProjectionEquivalenceTest.productiveByStrategy_keepsTheNullBucket` (line 571)

**Issue**: `setProductiveByKeys`' Javadoc (`WalkerContext:322-327`) names three states: null for an absent strategy, an empty set for "every key productive", and a configured key set. Tests reach two. `productiveByStrategy_keepsTheNullBucket` uses `ProductiveByStrategy.instance()`, whose productive-key set is empty, so `productiveByKeys.isEmpty()` short-circuits and `contains(propertyKey)` never executes; every other test in the suite covers the null state by omission. A strategy built with `ProductiveByStrategy.build().addKeys(...)`, where one key is productive and another is not, is the state that decides per key, and it runs nowhere.

`ByModulatorPresence` has no unit test either. Its productive-key early return is reachable only through the case above, and the conjunct it contributes is asserted at no unit-level site: `GremlinAggregateRecogniserTest.mean_afterValues_repointsAndDropsNullRows` (line 68) now runs `requireProperty` through `configurePropertyAggregate` and checks the RETURN column, not the alias filter the call adds.

**Suggestion**: Add a `byModulatorIsProductive` case near `WalkerContextResultShapingTest` covering the configured-keys state in both directions (a productive key that suppresses the conjunct, a non-productive one that does not), and one alias-filter assertion in `mean_afterValues_repointsAndDropsNullRows` so the conjunct has a unit-level pin that does not require a live graph.

### TS5 [suggestion] The pre-existing mean arm cannot tell `mean` from `avg`

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `numericAggregates_seeded_matchNative` (line 199), mean arm at line 211

**Issue**: The fixture seeds ages 10, 20 and 30. `mean` answers 20.0, `avg` answers 20, and `canonicalizeOne` (lines 844-850) folds an integral double to `N:20`, so both aggregates produce the identical canonical payload and the arm passes under either. The step recognised the hazard and wrote a discriminating case beside it — `meanOverIntegerProperty_dividesInFloatingPoint` (line 702), whose Javadoc says the ages "are chosen not to divide evenly" for this reason — and left the older arm as it was. It now reads as coverage of the mean path while pinning nothing about it.

**Suggestion**: Change one age. With 10, 20 and 31 the mean is 20.333…, which survives the canonicaliser, and sum, min and max stay deterministic. Alternatively add a clause to the Javadoc pointing at line 702 as the arm that carries the mean discrimination, so nobody reads line 211 as covering it.

### TS6 [suggestion] An `order()` test takes the unordered helper where the ordered one applies

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `orderByMissingKey_dropsElementLikeNative` (line 500)

**Issue**: The class offers `assertEquivalent` and `assertEquivalentOrdered`, and its other three `order()` tests take the ordered one (lines 260, 274, 637). This one takes `assertEquivalent`, which sorts both payloads before comparing, so the ordering the traversal exists to produce is thrown away before the assertion. The fixture supports the stronger form: after the drop only Bob (25) and Alice (30) survive, the ages differ, and the ordered payload is deterministic on both paths.

The `order()` arm of `productiveByStrategy_keepsTheNullBucket` (line 583) has to stay unordered, because null-valued rows survive there and null placement in `ORDER BY` is a known divergence between MATCH and the native pipeline. Nothing in the test records that, so the next reader is as likely to "fix" that arm as this one.

**Suggestion**: Switch line 500 to `assertEquivalentOrdered`, and add one clause to the ProductiveBy Javadoc recording why its order arm stays on the multiset comparison.

### TS7 [suggestion] The multi-label select fixture cannot tell the two aliases apart

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java`, method `multiLabelSelectByMissingKey_dropsElementLikeNative` (line 536)

**Issue**: `V().as("a", "n")` labels one element twice, so `ctx.resolveUserLabel("a")` and `ctx.resolveUserLabel("n")` return the same internal alias at `SelectStepRecogniser:50`. The `age IS DEFINED` conjunct therefore lands on the same node whichever label the loop is processing, and the test passes whether `requireModulatedProperty` is called with `modulators.get(i)` or with the first modulator every time, and whether it targets alias `a` or alias `n`. The Javadoc claims the narrower thing the fixture cannot show: "drops on the modulated alias too."

**Suggestion**: Separate the labels so the pairing has an observable consequence — an aged vertex with a `knows` edge to an ageless one, then `V().as("a").out("knows").as("n").select("a", "n").by("age").by("name")`. Confirm the shape still translates before relying on it; if it declines, say in the Javadoc that the single-element fixture pins the drop only, not the alias the conjunct targets.

### TS8 [suggestion] `nonNumericArgumentLeavesTheAggregateEmpty` covers one of three entry shapes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMeanTest.java`, method `nonNumericArgumentLeavesTheAggregateEmpty` (line 107)

**Issue**: `SQLFunctionMean.execute` has three ways in, and the two the test skips both cast without checking: the multi-value branch does `sum((Number) n)` per element, and the multi-argument branch does `sum((Number) param)`. A non-numeric element in either throws `ClassCastException` rather than leaving the aggregate empty, which is what the method name states as the function's behaviour. This step registers `mean` as a SQL function, so `SELECT mean(name) FROM Person` over a string column reaches the second of those paths from user input. `SQLFunctionAverage` has the same hole and the same single-shape test, so the gap is inherited rather than introduced — the name is broader than the body in both.

The class also has no single-contributor case (a lone `Integer` yields a `Double` where `avg` yields an `Integer`) and no `Float`, `Short` or `BigInteger` case. Those three are where `computeMean` and `computeAverage` differ in result *type* rather than in rounding: `computeAverage` falls through to `null` for `Short` and `BigInteger`, `computeMean` returns a `Double` for both.

**Suggestion**: Rename the test to name the shape it covers, and add the two cast paths. The type-coverage half overlaps the test-quality dimension; noted here for that reviewer rather than pressed as a structural finding.

### TS9 [suggestion] An existing assertion now reads a record dump instead of the expression it was written for

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinProjectionRecogniserTest.java`, method `valuesSingleKey_pinsSingleValueAndDropOnAbsent` (lines 36-37)

**Issue**: `setLastPropertyProjection` changed from `SQLExpression` to the new `RecognitionContext.PropertyProjection` record, and `assertThat(ctx.lastPropertyProjection.toString()).contains("name")` was left as it was. It compiles and passes, but it now matches against `PropertyProjection[alias=…, propertyKey=name, expression=….name]`, where the substring appears in three fields, one of which is the alias. The record was introduced precisely so callers could read the halves separately — the `RecognitionContext:465` Javadoc says the terminators "need different halves" — and the one existing assertion over it still reads the whole dump.

**Suggestion**: `assertThat(ctx.lastPropertyProjection.propertyKey()).isEqualTo("name")`, plus an assertion on `expression().toString()` if the field-access shape matters at that site.

## Evidence base
