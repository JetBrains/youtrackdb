<!-- MANIFEST
findings: 10   severity: {blocker: 0, should-fix: 5, suggestion: 5}
index:
  - {id: TS39, sev: should-fix, loc: RangeTypeGuardEquivalenceTest.java:592, anchor: "### TS39 ", cert: n/a, basis: "assertAgreesWithNative never pins the off arm at zero boundary steps; both sibling helpers in the package do, and iter1 TS8 already recorded this exact drift"}
  - {id: TS40, sev: should-fix, loc: RangeTypeGuardEquivalenceTest.java:618, anchor: "### TS40 ", cert: n/a, basis: "assertDeclinesAndMatchesNative compares empty to empty for its only caller — the vacuity its twin helper's javadoc says pinned tags exist to prevent"}
  - {id: TS41, sev: should-fix, loc: NotStepRecogniserTest.java:440, anchor: "### TS41 ", cert: n/a, basis: "rewritten javadoc claims a leaf-only guard would answer differently here; age is Integer everywhere on the modern graph, so guarded and unguarded give identical rows"}
  - {id: TS42, sev: should-fix, loc: GremlinStepWalkerTest.java:1615, anchor: "### TS42 ", cert: n/a, basis: "new seed method inserted between an existing javadoc and its method — seedAgedKnowsEdge now carries a description of a different fixture and seedOneKnowsEdgeNoSelfLoop has none"}
  - {id: TS43, sev: should-fix, loc: RangeTypeGuardEquivalenceTest.java:449, anchor: "### TS43 ", cert: n/a, basis: "plan-root and FETCH FROM INDEX assertions cannot witness the IN-vs-equality choice they name; the equality path is gated on isBaseIdentifier() and the pattern has one edge"}
  - {id: TS44, sev: suggestion,  loc: WherePredicateStepRecogniserTest.java:39, anchor: "### TS44 ", cert: n/a, basis: "hand-copied mirror of GremlinStepWalker.TRANSPARENT_STEPS with a javadoc asserting the two stay equal; the production constant is private, so nothing can enforce it"}
  - {id: TS45, sev: suggestion,  loc: WhereTraversalStepRecogniserTest.java:145, anchor: "### TS45 ", cert: n/a, basis: "whereTraversalStep_declinedChild_declines now observes the scope binding, not a declined child; name promises coverage the suite no longer has anywhere"}
  - {id: TS46, sev: suggestion,  loc: ProjectionExpressionFactoriesTest.java:138, anchor: "### TS46 ", cert: n/a, basis: "injection case asserts only rendered text; SQLIdentifier(String) leaves quoted=false, so a string-concatenating implementation renders the same and passes"}
  - {id: TS47, sev: suggestion,  loc: RangeTypeGuardEquivalenceTest.java:516, anchor: "### TS47 ", cert: n/a, basis: "the DATE/DATETIME dual-name block is the riskiest line in comparabilityBlock and no end-to-end case ever compares against a Date literal"}
  - {id: TS48, sev: suggestion,  loc: RangeTypeGuardEquivalenceTest.java:733, anchor: "### TS48 ", cert: n/a, basis: "seventh hand-rolled copy of the translator-toggle harness in this package, and tagsOf's javadoc describes an edge-target branch the code does not have"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS39 [should-fix] The new suite's main helper never checks that the native arm is native

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, method `assertAgreesWithNative` (line 592)

**Issue**: The off arm is one line — `var offTags = tagsOf(shape.get().toList());`. It never materialises an admin, so it never asserts that the translator-off traversal engaged zero boundary steps. Twelve of the file's fifteen tests route every assertion through this helper, thirty-seven call sites in total.

Both sibling helpers in the same package carry the check the new one dropped. `NotStepRecogniserTest.assertEquivalent` (line 497, off-arm check at line 529) asserts `countBoundarySteps(offAdmin.getSteps())` is zero. `GremlinStepWalkerTest.assertDeclinesAndMatchesNative` (line 1643) does the same and its javadoc gives the reason in as many words: "the off arm's count is pinned at zero so a kill-switch flip that never reached the traversal cannot go unnoticed — the flag defaults on."

The hazard is specific to how the toggle is written. `setTranslatorEnabled` resolves its target through `graphSession()`, which calls `tx.readWrite()` on each access; if a write ever landed on a handle the traversal does not read, both arms would run translated and every case in the file would be comparing the guarded engine against itself. The hand-written `expectedTags` lists are a partial net — they were derived from a run, so if that run had both arms translated, the tags encode translated behaviour and the net is gone with it. `assertDeclinesAndMatchesNative` (line 618) has the same omission.

This is the second time on this track that a new equivalence class forked the harness and lost the off-arm check on the way. Iter1 TS8 recorded it for `RepeatDeclineStrategyTest`, which "improved on the parent by pinning an explicit expected multiset, and regressed on it by dropping the off-arm boundary assertion." The new file repeats both halves exactly.

**Suggestion**: Materialise the off arm and pin it, matching the sibling helpers:

```java
setTranslatorEnabled(false);
var offAdmin = shape.get().asAdmin();
offAdmin.applyStrategies();
assertThat(countBoundarySteps(offAdmin.getSteps()))
    .as(scenario + " (translator off) must never engage a boundary step")
    .isZero();
var offTags = tagsOf(offAdmin.toList());
```

Apply the same three lines to `assertDeclinesAndMatchesNative`.

### TS40 [should-fix] The decline helper compares an empty result against an empty result

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, method `assertDeclinesAndMatchesNative` (line 618), sole caller `literalWithNoComparabilityBlock_declinesToNative` (line 424)

**Issue**: The helper takes no expected row list. It runs the shape twice and asserts the two arms match, which for a declined shape means native against native — an identity the assertion cannot fail on. Its only caller runs `typesRange(P.gt(Instant.ofEpochMilli(1000)))`; TinkerPop's comparator rejects an `Instant` against every stored value in the `Types` class, so the native answer is empty and both sides of `isEqualTo` are `List.of()`.

The twin helper twelve lines above states the rule this one breaks. `assertAgreesWithNative`'s javadoc: "Pinning the expected tags rather than only comparing the two arms is what stops an empty-on-both-sides regression from passing." `NotStepRecogniserTest.assertEquivalent` enforces the same rule with an explicit `isNotEmpty()` on the arm that matters, and says so in its assertion description.

The decline assertion itself is sound — `countBoundarySteps(...)` is zero, and restoring `Instant` to `comparabilityBlock` would translate the shape and redden it. What cannot fail is the row comparison, which is the half that would catch the fixture drifting out from under the case.

**Suggestion**: Add a control to the same test rather than trying to pin a non-empty expectation on a shape whose answer is legitimately empty. One extra line makes the emptiness meaningful:

```java
assertAgreesWithNative(
    "control: the same shape with a comparable literal still returns rows",
    () -> typesRange(P.gt(27)),
    List.of("t_decimal", "t_double", "t_float", "t_long"));
assertDeclinesAndMatchesNative(
    "v > Instant — no block can be named",
    () -> typesRange(P.gt(java.time.Instant.ofEpochMilli(1000))));
```

Alternatively give the helper an `expectedTags` parameter and require callers to state the answer, which also documents what "declines to native" produces here.

### TS41 [should-fix] The between case's rewritten javadoc claims a falsification its fixture cannot deliver

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/NotStepRecogniserTest.java`, method `notWithBetweenPredicate_translatesToTheSameRows` (line 440)

**Issue**: The rewrite ends "A guard applied only to leaf predicates at the top would leave both arms unguarded and this case would answer differently from native." It would not. The case runs `g.V().not(has(age, between(28, 33)))` on `ModernGraphFixture`, where `age` is an Integer on every vertex that carries it and absent on the two software vertices. With the guard, each arm becomes `age.type() IN [numerics] AND age >= 28` and the same for `age < 33`; the type conjunct is true wherever `age` is present and false wherever it is absent, which is precisely where the bare comparison is already false. Guarded and unguarded produce the same rows, so removing the connective recursion leaves this test green.

The claim is not idle. This is the only end-to-end case anywhere in the diff that exercises `between` / `AndP`, and the flip from `DECLINED` to `RECOGNIZED` removed the one assertion it did carry. What remains that can fail is `Recognition.RECOGNIZED` — real, but a statement about the deleted gate, not about the guard reaching both arms. The connective recursion is pinned only at render level, in `GremlinPredicateAdapterTest.guardedRange_reachesUnderTheConnectives` (line 824), which asserts the emitted text contains `age.type() IN ["BYTE"` and never runs a row.

Of the three flipped tests, this is the one that changed subject while asserting less. `notWithCrossTypeRangeComparison_translatesAndAgreesWithNative` still discriminates — `name` is a String against an Integer literal, so an unguarded translation returns nothing where native returns six. `notWithRangeComparisonBehindHop_translatesToTheSameRows` cannot discriminate and its javadoc says so outright. Only the between case asserts a discriminating property in prose that its fixture contradicts.

**Suggestion**: Move the case to a fixture that carries mixed runtime types under one key. `RangeTypeGuardEquivalenceTest` already seeds exactly that — `Loose` holds the String `"zulu"` and the Integer `99` under `name`:

```java
assertAgreesWithNative(
    "g.V().hasLabel(Loose).not(has(name, between(28, 33)))",
    () -> graph.traversal().V().hasLabel("Loose").not(__.has("name", P.between(28, 33))),
    List.of("loose_num", "loose_zulu"));
```

An unguarded leaf-only translation drops `loose_zulu` there, so the case witnesses what the javadoc promises. Keep the `NotStepRecogniserTest` case if the modern-graph shape is wanted for its own sake, and correct its javadoc to say the boundary-step assertion is the discriminating half, matching how the hop case next to it is worded.

### TS42 [should-fix] The new fixture method took over the javadoc of the one below it

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, method `seedAgedKnowsEdge` (line 1615)

**Issue**: `seedAgedKnowsEdge` was inserted between `seedOneKnowsEdgeNoSelfLoop` and that method's javadoc, leaving two stacked comment blocks on the new method and none on the old one:

```java
  /** Alice knows Bob, plus an isolated vertex. No self-loops, so a child asking for one matches
   *  nothing natively. */
  /** One {@code knows} edge whose two endpoints have different ages, so a filter on {@code age}
   *  separates "run it against the start label" from "run it against the hop target". */
  private void seedAgedKnowsEdge() {
```

The first block is wrong about the method it now sits on: `seedAgedKnowsEdge` creates two vertices and no isolated one, and self-loops have nothing to do with what it is for. `seedOneKnowsEdgeNoSelfLoop` (line 1622), which the block does describe, is now undocumented — and the property it documents is load-bearing for the cases that use it, since those cases turn on a child asking for a self-loop matching nothing.

**Suggestion**: Move the first block back down onto `seedOneKnowsEdgeNoSelfLoop`.

### TS43 [should-fix] The plan-shape assertions cannot observe the node-type choice they exist for

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, method `guardedAliasAboveTheEstimatorThreshold_doesNotCaptureThePlanRoot` (line 449)

**Issue**: The row assertion in this test is sound — the hop targets hold `mixed` as `30`, `"zz"` and `10`, so unguarded SQL ranks the String above 27 and returns `b602` alongside `b601`, and the pinned `List.of("b601")` catches it. The 604-row class is also correctly sized: `SQLWhereClause.estimate` (line 95) returns early when `classCount / 2 < 100`, so a smaller class would skip condition inspection entirely, and 604 clears it. Both of those hold up.

What does not hold up is the pair of plan assertions the test is named for. They observe the plan root and the presence of `FETCH FROM INDEX`, and the stated hazard is that an equality-shaped guard would be scored at the estimator's tier-3 `1.0 / classCount` default and "make the alias look like a one-row alias." Neither assertion can see that choice on this shape, for two independent reasons.

Root cardinalities come from `MatchExecutionPlanner.estimateRootEntries` (line 6375), which calls `SQLWhereClause.estimate`. That method's equality path runs through `getEqualityOperations` (`SQLWhereClause.java:205`), which requires `b.left.isBaseIdentifier()`. A left side carrying a `.type()` modifier fails that gate whatever the operator is, so `IN` and `=` produce the same estimate. The hop target is registered under the generic vertex class `V` with no index (`VertexHopRecogniser`'s "Bare hop targets root at `V` polymorphically"), so the estimate for that alias is `classCount / 2` either way.

The `1.0 / classCount` default the javadoc names lives on a different path — `applyClassSelectivity` calling `estimateFilterSelectivity(filter, classCount, schemaClass, session)` (`MatchExecutionPlanner.java:3789`), which does accept a modifier-bearing `SQLBinaryCondition` and does feed edge cost and the hash-join forecast. The pattern here has one edge, so there is no ordering to perturb and no join to forecast.

So the two assertions pass for the same reason a well-behaved guard passes: the shape gives the estimator nothing to get wrong. `MatchWhereBuilderTest.typeIn_buildsAnInConditionOverTheMethodCall` (line 860) pins the node type directly and is the assertion actually carrying the decision; this case does not add a second witness for it.

**Suggestion**: Verify the assertions can fail before trusting them — change `MatchWhereBuilder.typeIn` to emit an `SQLBinaryCondition` with `=` for the single-name case and re-run this class. If the test stays green, either move the assertion onto a two-edge pattern where edge ordering is observable (add a second hop off the guarded alias so the schedule has a choice to make), or drop the plan-root claim and keep the case as what it demonstrably is — a large-class agreement case with an index-fetch assertion — and point the estimator rationale at `MatchWhereBuilderTest` where it is pinned.

### TS44 [suggestion] A hand-copied transparency set with a documented invariant nothing can enforce

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WherePredicateStepRecogniserTest.java`, line 39

**Issue**: The constant now reads `Set.of(NoOpBarrierStep.class)` with a javadoc saying it "Mirrors `GremlinStepWalker`'s production transparency set" and that "Keeping the two sets equal is what makes a decline observed here mean the same thing as a decline in production." The two sets are equal today. Nothing keeps them so: `GremlinStepWalker.TRANSPARENT_STEPS` (line 163) is `private static final`, so the test cannot read it even though it sits in the same package, and the next change to the production set will silently make this class's declines mean something different from production's while the javadoc goes on asserting they agree.

The step just demonstrated the drift is live — this constant lost two entries in this diff because the production set did.

**Suggestion**: Drop `private` from `GremlinStepWalker.TRANSPARENT_STEPS` and have the test reference it directly, which deletes the invariant instead of documenting it. If the visibility change is unwanted, add a one-line assertion in the test that the two sets are equal, so a drift fails here rather than downstream.

### TS45 [suggestion] A case renamed nowhere still promises coverage the suite no longer has

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WhereTraversalStepRecogniserTest.java`, method `whereTraversalStep_declinedChild_declines` (line 145)

**Issue**: The added javadoc is honest — the `count()` is not what stops this case, the `WhereStartStep` is, and the mechanism is "the same as its two neighbours above." That leaves a case whose name says it covers "a `where` child that declines on its own content" sitting on a shape that never reaches the child's content, and it is the weakest of the three: its neighbours also assert `getNumOfEdges()` is zero and `aliasFilters` is empty, while this one asserts only the outcome.

The declined-child path is now uncovered anywhere in this class. Every `WhereTraversalStep` child begins with a `WhereStartStep`, so `WhereTraversalStepRecogniser` declines at index 0 before `walkChild` can return anything — which means the recogniser's own declined-child branch has no test reaching it.

**Suggestion**: Rename to something the case can support, `whereTraversalStep_scopeBoundCountChild_declines`, and either add the two no-mutation assertions its neighbours carry or fold it into `whereTraversalStep_pathScopedPureFilter_declines`. If the declined-child branch still matters, cover it through a filter shape that reaches the child — `TraversalFilterStepRecogniser` with a `filter(__.count())` child is the nearest equivalent.

### TS46 [suggestion] The injection case asserts a rendering an unsafe implementation would also produce

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/ProjectionExpressionFactoriesTest.java`, method `propertyMethodCall_injectionKeyStaysLiteral` (line 138)

**Issue**: The case asserts `render(built)` contains `k FROM V WHERE 1=1--` and ends with `.type()`. `SQLIdentifier(String)` leaves `quoted` at its default `false` (`SQLIdentifier.java:47`, `toString` at line 132), so the render is the raw metacharacter text followed by `.type()` — exactly what a string-concatenating implementation would produce. Both assertions hold either way, so the case cannot distinguish the AST construction from the thing the javadoc says it rules out ("stays one literal identifier under a method call").

The safety claim is about AST shape, and the factory does deliver it. Rendered text is the wrong surface to read it off. The sibling `aliasProperty_injectionKeyStaysLiteral` (line 112) has the same weakness plus a comment ("Renders the whole thing as one quoted identifier segment") that is not true for an unquoted identifier — pre-existing, worth correcting in the same pass.

**Suggestion**: Assert the AST instead, which is what the claim is about:

```java
var built = ProjectionExpressionFactories.propertyMethodCall("k FROM V WHERE 1=1--", "type");
assertThat(built.getModifier()).isNotNull();
assertThat(built.getModifier().getNext()).isNull();
assertThat(built.getDefaultAlias().getStringValue()).isEqualTo("k FROM V WHERE 1=1--");
```

Adjust the accessors to whatever `SQLExpression` exposes; the point is one identifier node and one modifier, not a substring match.

### TS47 [suggestion] The date block is the riskiest line in `comparabilityBlock` and no case runs it

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, fixture `seedOneValueOfEachType` (line 516), test `comparabilityPartition_matchesNativeForEveryLiteralType` (line 378)

**Issue**: `comparabilityBlock` returns `List.of("DATE", "DATETIME")` for a `java.util.Date`, and its javadoc explains the pair as a hedge: the accessor "may report under either name depending on the stored value." Every other block names one certainty; this one names an uncertainty, which makes it the line most likely to be wrong.

The fixture seeds `t_date_early` and `t_date_late` and the partition test runs ten literal/direction combinations, none of them a Date. The two date rows appear only as exclusions, never in an expected list. The end-to-end evidence for the block is therefore that a numeric literal does not reach a Date — which is true whether the block lists `DATE`, `DATETIME`, both, or neither. The only assertion touching the pair is `GremlinPredicateAdapterTest.guardedRange_namesTheBlockOfTheLiteralsOwnClass` (line 773), which checks the emitted text and never asks what a stored `Date` reports.

**Suggestion**: Add two combinations to `comparabilityPartition_matchesNativeForEveryLiteralType`, which needs no fixture change:

```java
assertAgreesWithNative("v > Date(5s)", () -> typesRange(P.gt(new Date(5_000L))),
    List.of("t_date_late"));
assertAgreesWithNative("v < Date(5s)", () -> typesRange(P.lt(new Date(5_000L))),
    List.of("t_date_early"));
```

If only one of the two names is ever reported, that shows up as a mismatch here rather than as a divergence later.

### TS48 [suggestion] A seventh copy of the translator harness, and one helper javadoc describing a branch that is not there

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeTypeGuardEquivalenceTest.java`, lines 677, 684, 723, 733, 739

**Issue**: `countBoundarySteps`, `withTranslator`, `translatorEnabled` and `setTranslatorEnabled` are near-verbatim from `NotStepRecogniserTest` (lines 541, 551, 579). Six classes in this package already carry their own copy of the pair — `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `ProjectionEquivalenceTest`, `UnionTraversalEquivalenceTest`, `NotStepRecogniserTest`, `RepeatDeclineStrategyTest` — and this is the seventh. TS39 and TS40 above are what the drift bought this time.

The three assertion helpers also each re-roll the save/restore that `withTranslator` already provides, so the file carries four independent copies of the same try/finally.

Separately, `tagsOf`'s javadoc (line 677) reads "Sorted `tag` values of the returned vertices (or of the returned edges' target, for a shape that ends on a vertex step)". The parenthetical describes a branch the method does not have — it casts every element to `Vertex` unconditionally. The one edge-flavoured shape in the file, `undeclaredEdgePropertyRange_keepsTranslatingAndKeepsItsRows`, ends on `.inV()` and returns vertices, so the cast is safe and the sentence is simply describing something else.

Reference-accuracy caveat: the count of six prior copies is grep over `core/src/test`, not PSI. `mcp-steroid` PSI has timed out on this repository for every agent on this branch, so the figure is a floor for literal matches; a copy reached through a base class or an alias would not appear.

**Suggestion**: Delete the parenthetical from `tagsOf`. Route the three assertion helpers through `withTranslator` so the save/restore exists once in the file. The seven-way extraction is not this step's work — record it in the track file's `## Surprises & Discoveries` so the follow-up has an owner, and make the new class the first caller when a shared support type lands.

## Evidence base
