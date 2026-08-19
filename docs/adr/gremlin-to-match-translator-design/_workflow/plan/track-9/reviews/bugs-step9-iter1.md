<!-- MANIFEST
findings: 6   severity: {blocker: 1, should-fix: 3, suggestion: 2}
index:
  - {id: BG1, sev: blocker, loc: PropertiesStepRecogniser.java:80-82, anchor: "### BG1 ", cert: C1, basis: "the sub-walk escape lets the element form reach a position that reads the payload: where / filter / and / not over properties(k).has(metaKey, v) translates to a top-level property filter and returns a disjoint row set from native — measured on all four spellings"}
  - {id: BG2, sev: should-fix, loc: GremlinProjectionAssembler.java:67-88, anchor: "### BG2 ", cert: C2, basis: "the escape's stated premise is false — a values/properties projection in a sub-walk contributes nothing at all, not a presence conjunct, so and(values(age), values(name)) translates to a no-op AND and returns 3 rows against native's 1"}
  - {id: BG3, sev: should-fix, loc: ByModulatorTranslator.java:246-248, anchor: "### BG3 ", cert: C3, basis: "the VALUE-only narrowing withdraws by(values(k).count()), which AdjacentToIncidentStrategy rewrites into the element form and which translated in agreement with native before the change — measured both ways"}
  - {id: BG4, sev: should-fix, loc: ProjectionEquivalenceTest.java:192-206, anchor: "### BG4 ", cert: C4, basis: "the sub-walk escape's only positive control never reaches the gate: where(values(age)) is absorbed by TraversalFilterStepRecogniser.presenceKey's has(key) desugar, so the escape ships with no exercised control"}
  - {id: BG5, sev: suggestion, loc: PropertiesStepRecogniser.java:81, anchor: "### BG5 ", cert: C5, basis: "instanceof CountGlobalStep against two in-repo exact-class precedents, one of which documents the instanceof direction as the one that is not fail-closed"}
  - {id: BG6, sev: suggestion, loc: PropertiesStepRecogniser.java:73-78, anchor: "### BG6 ", cert: C6, basis: "the third-position Javadoc names a decline site neither spelling uses; the gate itself declines the is(gt(n)) form and configureCount's cardinality gate declines the limit(n) form, so the instruction left for the next reader points at the wrong hazard"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 6}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED,   anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED,   anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [blocker] The sub-walk escape hands the element form to a step that reads the payload, and the meta-property misread survives one `where()` away

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PropertiesStepRecogniser.java` (lines 80-82); commit paths `ConnectiveStepSupport.java:55-91`

**Issue**: `elementFormIsUnobserved` accepts the element form for the whole of a sub-walk, not for the position inside it where the projection is unread. A child sub-traversal has steps after its `properties(key)`, and those steps are dispatched against the same registry and their filters are committed to the parent. Wrapping the exact shape the step exists to stop in any of four combinators restores it:

```
seed: marko{friendWeight=1.5 [acl=private]}, josh{friendWeight=2.5 [acl=public]},
      peter{acl='private' as a top-level property, no friendWeight}

g.V().where(__.properties(friendWeight).has(acl, private))    ON [peter]  OFF [marko]
g.V().filter(__.properties(friendWeight).has(acl, private))   ON [peter]  OFF [marko]
g.V().and(__.properties(friendWeight).has(acl, private))      ON [peter]  OFF [marko]
g.V().not(__.properties(friendWeight).has(acl, private))      ON [marko, josh]  OFF [josh, peter]
```

Each translates (one boundary step) and each returns a row set disjoint from native's. The commit's own `metaPropertyFilterThroughProperties_matchesNative` pins the bare main-line spelling of the first row; adding `where(...)` around it flips the assertion and nothing catches that.

Mechanism: the child's `properties(friendWeight)` contributes nothing (`#### C2`), and the trailing `has("acl", "private")` is committed by `commitPureFilterChild` as `WHERE alias.acl = 'private'` on the *vertex* alias. The meta-property key is read as a top-level property of the element. `peter` is returned because it happens to carry a vertex property called `acl`; `marko` is dropped because its `acl` lives on the `friendWeight` property.

The behaviour is unchanged from the step base — the element form was accepted unconditionally there — so this is a completeness defect rather than a regression. What the commit adds is the claim that the position is provably safe ("the element and its payload are indistinguishable to the caller"), which is what will keep the next reader from looking.

**Evidence** (`#### C1`): measured, four spellings, translator on against off, on a three-vertex meta-property fixture. Reachability of the sub-walk path is read end to end: `walkChild` has five callers (`ConnectiveStepSupport:41`, `NotStepRecogniser:63`, `TraversalFilterStepRecogniser:75`, `WhereTraversalStepRecogniser:36`, `SubTraversalPredicateAdapter:437`), and `SubTraversalPredicateAdapter` is one of only two `RecognitionContext` implementations.

**Refutation considered**: I checked whether `HasStepRecogniser` declines once the boundary output type is pinned to `SINGLE_VALUE`, which would keep the meta-property `has` off the translated path — it does not, and the measured `ON` rows are exactly what a bare `acl = 'private'` filter selects. I checked whether the `or(...)` spelling is exposed too: it is not, because `singleCapturedFilter` requires exactly one captured filter on the boundary and a `properties(key)` child captures none, so the `or` declines (`g.V().or(__.values(age), __.values(nick))` measured with zero boundary steps). I checked the nested case: `SubTraversalPredicateAdapter.walkChild` wraps `this`, and the override is a constant, so a grandchild answers `false` as well and inherits the same hole rather than closing it.

**Suggestion**: gate the escape on the position, using the same condition `AdjacentToIncidentStrategy` itself applies when it produces the element form in a filter child — the step must be the child's last one.

```java
private static boolean elementFormIsUnobserved(StepCursor cursor, RecognitionContext ctx) {
  // A sub-walk capture is safe only at the child's end step, which is the sole position
  // AdjacentToIncidentStrategy rewrites there (its i == size arm). A step after it in the same
  // child reads the payload and has its own filter committed to the parent, so
  // where(properties(k).has(metaKey, v)) would translate to a top-level acl filter.
  if (!ctx.projectsReturnedPayload()) {
    return cursor.peek(0) == null;
  }
  return cursor.peek(0) != null && cursor.peek(0).getClass() == CountGlobalStep.class;
}
```

Add the four measured spellings to `PredicateTraversalEquivalenceTest` as declines, each paired with the same-shape `values(key)` positive control the branch's decline-assertion rule requires.

### BG2 [should-fix] A `values(key)` projection inside a sub-walk contributes no filter at all, so `and(values(a), values(b))` is a no-op

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinProjectionAssembler.java` (lines 67-88); swallowing sites `SubTraversalPredicateAdapter.java:368-375, 420-429`

**Issue**: The escape's premise in `PropertiesStepRecogniser`'s Javadoc is that "only the presence conjunct the projection contributes survives the commit". No presence conjunct is contributed. `configureSingleKeyValues` expresses the drop through `ctx.setResultShaping(... withDropOnAbsent(true).withPresencePropertyKeys(...))` and never calls `putAliasFilter`; the adapter swallows `setResultShaping`, `clearReturnProjection`, `appendReturnColumn` and `setLastPropertyProjection`, and `pinBoundary` only re-records the alias the child was already on. An accepted child therefore commits an empty filter map and an empty pattern:

```
seed: Alice{name, age=30}, Bob{name}, c{age=44, nick}
g.V().and(__.values(age), __.values(name))   ON [Alice, Bob, c]   OFF [Alice]
```

The `and` is dropped entirely. The same hole is what makes BG1 return the wrong row rather than no row.

Both the filtering loss here and the payload leak in BG1 come out of the same escape, and they need different repairs: BG1 narrows which positions the escape covers, this one supplies the contribution the escape assumes is already there.

**Evidence** (`#### C2`): measured, one boundary step on the translated arm, three rows against native's one. The absence of a `putAliasFilter` call on the `configureSingleKeyValues` path is read at the method body; the swallowing is read at each adapter override. BG1's `ON` row (`peter`, which has no `friendWeight` at all) is independent confirmation that no `friendWeight IS DEFINED` conjunct reached the plan.

**Refutation considered**: I checked whether some other recogniser supplies the conjunct for a sub-walk `values(key)` before this one runs. `TraversalFilterStepRecogniser.presenceKey` does exactly that for the `has(key)` desugar, and it is why `g.V().where(__.values(age))` measures correctly (two rows on both arms) — but it matches only a single-step child and returns before `walkChild` is reached, so it covers none of the `and` / `or` / multi-step-child shapes. I checked whether `ByModulatorPresence` reaches the projection path: its `requireProjectedProperty` has two callers, both in `GremlinAggregateAssembler`, and neither is on the projection path.

**Suggestion**: contribute the conjunct on the sub-walk path, where the shaping cannot carry it. `ByModulatorPresence.requireProjectedProperty` already writes exactly this clause through `putAliasFilter`, which the adapter captures.

```java
if (ctx.projectsReturnedPayload()) {
  ctx.setResultShaping(
      ResultShaping.NONE.withDropOnAbsent(true).withPresencePropertyKeys(List.of(propertyKey)));
} else {
  // A captured child's shaping is swallowed, so the drop values(key) performs has to travel as a
  // pattern conjunct instead — otherwise and(values(a), values(b)) commits nothing and filters
  // nothing.
  ByModulatorPresence.requireProjectedProperty(ctx, boundary, propertyKey);
}
```

Keeping the conjunct off the top-level arm matters: DR-S6 records that `IS DEFINED` defeats the MATCH root-selection estimator, and the top-level arm already has `dropOnAbsent`.

### BG3 [should-fix] The by-modulator narrowing withdraws `by(values(k).count())`, which a default strategy rewrites into the element form

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslator.java` (lines 246-248); affected call site lines 186-207

**Issue**: `isSingleValueProperty` is shared by the key-side classifier (`classifyKey`, line 124) and the value-side accumulator classifier (`translateValueModulator`, line 187), and the two positions have different exposure to `AdjacentToIncidentStrategy`. Narrowing both to `PropertyType.VALUE` declines a hand-written shape that translated in agreement with native:

```
g.V().group().by(T.label).by(__.values(age).count())
   post-strategy modulator body: [PropertiesStep(PROPERTY), CountGlobalStep]
   at c252146ba5:                 declines (0 boundary steps)
   with the pre-commit body:      translates, {Person=2} on both arms
   siblings by(values(age).sum()) / .max(): still translate (1 boundary step)
```

The commit message states the cost as nil, on the grounds that "a `by(values(k))` body is rewritten to a ValueTraversal before it arrives, so the element form here is only ever hand-written". That holds for the one-step key-side body and not for the two-step value-side one: `ValueTraversal` covers a single `values(k)`, and `AdjacentToIncidentStrategy`'s count arm rewrites the `PropertiesStep` of a two-step `[values(k), count()]` child to `PropertyType.PROPERTY`. The element form at line 187 arrives from a written `values(key)`, the same way the two projection-path escapes do.

The narrowing is right for every other arm. `by(properties(k).sum())` is not a shape native can evaluate, and the strategy never produces it — the count arm fires only when the successor is a `CountGlobalStep`.

**Evidence** (`#### C3`): measured both ways at the same fixture, with the post-strategy `PropertyType` printed rather than inferred. The strategy's rewrite condition is read from the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT` bytecode: `apply` runs `isOptimizable(prev) && curr instanceof CountGlobalStep` whenever the combined `i == size && isOptimizable(curr)` test fails, and `optimizeStep` replaces the step with `new PropertiesStep(traversal, PropertyType.PROPERTY, keys)`.

**Refutation considered**: I checked whether the key side loses a shape too — it does not. `g.V().groupCount().by(__.values(name))` and `g.V().order().by(__.values(name))` measure identical with and without the narrowing, because `ByModulatorOptimizationStrategy` converts a one-step `by(values(k))` into a `ValueTraversal` that `classifyKey` matches before reaching the `PropertiesStep` arm, and the strategy's end-step arm requires a `NotStep` / `TraversalFilterStep` / `WhereTraversalStep` / `ConnectiveStep` parent that a `GroupStep` modulator is not. I checked whether the withdrawn translation was correct rather than merely accepted: `count(alias.age)` skips nulls and native `values(age)` drops the property-less element, so the two agree, and the measured payloads match on both arms.

**Suggestion**: split the predicate by reading position rather than widening it back.

```java
/** A modulator body the translator can read as a field: only the {@code values(key)} form, because
 *  a key or an accumuland built from a {@code VertexProperty} element is not its payload. */
private static boolean isSingleValueProperty(PropertiesStep<?> step) {
  return step.getReturnType() == PropertyType.VALUE && step.getPropertyKeys().length == 1;
}

/** As above, plus the element form, for the one accumulator that cannot tell them apart: one
 *  property element per value means {@code count} is the same either way. This is the shape
 *  AdjacentToIncidentStrategy produces from a written {@code by(values(k).count())}. */
private static boolean isSingleKeyPropertyOrValue(PropertiesStep<?> step) {
  var type = step.getReturnType();
  return (type == PropertyType.VALUE || type == PropertyType.PROPERTY)
      && step.getPropertyKeys().length == 1;
}
```

The value-side arm tests `isSingleKeyPropertyOrValue` and then keeps `isSingleValueProperty` as the extra condition on the `sum` / `min` / `max` / `mean` branches. Add `g.V().group().by(T.label).by(__.values(age).count())` to `ProjectionEquivalenceTest` as a recognised case beside the new `groupByPropertiesElementForm_declines_whileByValuesStillTranslates`, which currently pins only the key side.

### BG4 [should-fix] The sub-walk escape's positive control never reaches the gate

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ProjectionEquivalenceTest.java` (lines 192-206)

**Issue**: `countConsumedAndSubWalkPropertiesForms_stillTranslate` asserts that `g.V().where(__.values(age))` is recognised, and its Javadoc presents that as the witness for the sub-walk half of the escape. The traversal never enters `PropertiesStepRecogniser`. A `where` over a single-step child arrives as a `TraversalFilterStep` whose filter traversal holds exactly one `PropertiesStep`, which is the `has(key)` desugar `TraversalFilterStepRecogniser.presenceKey` matches at line 56; that path returns `key IS DEFINED` and returns before `walkChild` is called.

Measurement separates the two paths cleanly. `where(__.values(age))` filters correctly, two rows on both arms; `and(__.values(age), __.values(name))`, which does reach the recogniser through `walkChild`, over-emits three against one (`#### C2`). So the escape whose reachability the test is there to establish ships with no exercised control, and the two live defects it covers (BG1, BG2) sit behind it.

This is the fifth instance of the vacuous-acceptance pattern `## Surprises & Discoveries` records on this branch, and the same shape as the four before it: the expected observation is "the shape still translates", which a different code path also produces.

**Evidence** (`#### C4`): measured, both shapes, same fixture. `presenceKey`'s match conditions are read end to end at `TraversalFilterStepRecogniser.java:92-125`; it requires a filter traversal of exactly one `PropertiesStep` over one non-reserved key, which `__.values("age")` is.

**Refutation considered**: I checked whether `where` might route through `WhereTraversalStep` instead, which has no presence-key branch — the measured off-arm step list is `[YTDBGraphStep, TraversalFilterStep]`, so it does not. I checked whether the assertion is still worth keeping: it is, as a pin on the `has(key)` desugar, but it is not a control for this gate.

**Suggestion**: use a spelling that reaches `walkChild`. `g.V().and(__.values(age), __.values(name))` is the shortest one, and once BG2 is fixed it is also a live correctness assertion rather than a recognition-only one. Keep the `where(__.values(age))` case and retitle it for what it pins.

### BG5 [suggestion] The count look-ahead matches `instanceof` where its two siblings match the exact class and say why

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PropertiesStepRecogniser.java` (line 81)

**Issue**: `cursor.peek(0) instanceof CountGlobalStep<?>` diverges from both in-repo precedents for the same question. `RangeGlobalStepRecogniser.followedByCount` (lines 128-131) tests `next.getClass() == CountGlobalStep.class` and its Javadoc names the reason: "a `CountGlobalStep` subclass has no registry entry and would decline the traversal anyway, so treating it as a count here would be the one direction that is not fail-closed". `GremlinStepWalker.postUnionSuffixTranslatable` (line 442) resolves the successor through the registry for the same purpose. The walker's whole dispatch contract is exact-class (`StepCursor`'s "Matching is by exact class" section).

No subclass exists today — the pinned `gremlin-core` jar ships `CountGlobalStep.class` alone, and grep over `core/src/main` finds no subclass — and if one appeared, the walk would accept here and then decline at the unregistered successor, so nothing wrong is returned. The cost is that a later registry entry for a count subtype (the `RangeGlobalStepPlaceholder` pattern) would silently change this gate's meaning while the sibling gates keep theirs.

**Evidence** (`#### C5`): jar listing and grep for the subclass question; the two precedent sites read end to end.

**Refutation considered**: I checked whether the `instanceof` spelling admits a live wrong answer through accept-then-decline — it does not, because a decline anywhere discards the whole walk and the traversal runs natively.

**Suggestion**: `cursor.peek(0) != null && cursor.peek(0).getClass() == CountGlobalStep.class`, or reuse the successor-through-the-registry form if the recogniser gains access to it. Either way, cite `RangeGlobalStepRecogniser.followedByCount` so the two stay together.

### BG6 [suggestion] The third-position note names a decline site neither spelling actually uses

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PropertiesStepRecogniser.java` (lines 73-78)

**Issue**: The Javadoc explains the `values(key).count().is(gt(n))` position as declining "a step later regardless" because "the trailing `is(...)` has no recogniser", and concludes that a look-ahead there "would be untestable defence". Both spellings of the position decline, and neither declines there:

```
g.V().values(age).count().is(gt(1))   post-strategy: [GraphStep, PropertiesStep, RangeGlobalStep,
                                                      CountGlobalStep, IsStep]  → 0 boundary steps
g.V().values(age).limit(1).count()    post-strategy: [GraphStep, PropertiesStep, RangeGlobalStep,
                                                      CountGlobalStep]          → 0 boundary steps
```

For the first, `CountStrategy`'s inserted `RangeGlobalStep` sits at `peek(0)`, so `elementFormIsUnobserved` declines it at this step. For the second, which the Javadoc does not mention and which no `is(...)` guards, the decline comes from `GremlinAggregateAssembler.configureCount`'s `hasPreAggregateCardinalityClause` gate (line 81) — measured with the element-form gate reverted, so the range-intervening shapes decline with or without it.

The reachability the note gets right is worth keeping, and the mechanism is broader than stated: `AdjacentToIncidentStrategy` tracks its predecessor with `if (!(curr instanceof RangeGlobalStep)) prev = curr`, so any number of slices between the projection and the count still produce the element form, `limit` and `skip` and `range` alike. What the note gets wrong is the instruction it leaves behind. Adding an `IsStep` recogniser would open nothing on its own; relaxing `configureCount`'s cardinality gate, or making this gate tolerate an intervening slice, is what would.

**Evidence** (`#### C6`): measured, four range-intervening spellings, at HEAD and with `elementFormIsUnobserved` forced to `true`; the strategy's `prev` bookkeeping read from the pinned jar's bytecode.

**Refutation considered**: I checked whether the gate costs a shape that translated before at this position — it does not (`#### C9`), which is why this is a comment-accuracy finding rather than a coverage one.

**Suggestion**: state the two decline sites and point the next reader at the gate that actually holds.

```
 * <p>Anything else declines, which is the safe direction. That includes the positions where a slice
 * separates the projection from the count — AdjacentToIncidentStrategy skips RangeGlobalStep when
 * tracking its predecessor, so values(key).limit(n).count() and CountStrategy's rewrite of
 * values(key).count().is(gt(n)) both arrive in the element form. Both decline: this gate rejects
 * them because peek(0) is the slice rather than the count, and GremlinAggregateAssembler
 * .configureCount would reject them anyway through hasPreAggregateCardinalityClause. Whoever
 * relaxes either gate has to widen this one in the same change.
```

## Evidence base

METHOD CAVEAT: reference questions in this review were answered by grep over `core/src/{main,test}/java` plus an end-to-end read of every returned site, and by disassembling the pinned `gremlin-core-3.8.1-67860f6-SNAPSHOT` jar. PSI was not used — `steroid_execute_code` times out on this repository (cold kotlinc exceeds the MCP limit). Five of the six findings rest on executed translator-on / translator-off probes, so a missed reference would not flip them. Three claims depend on the search being complete and are bounded rather than established: "`SubTraversalPredicateAdapter` and `WalkerContext` are the only `RecognitionContext` implementations, and no test stub adds a third" (`#### C7`), "`walkChild` has five callers" (`#### C1`), and "no `CountGlobalStep` subclass exists in `core/src/main`" (`#### C5`).

MEASUREMENT METHOD: probes ran as a temporary `GraphBaseTest` subclass in `core`, deleted after the run. Each probe built the traversal twice at the same fixture — once with `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` true, once false — counted `AbstractMatchPlanStep` instances after `applyStrategies()` to confirm whether the shape translated, printed the post-strategy step list so the arriving `PropertyType` and any inserted `RangeGlobalStep` were visible rather than inferred, and compared string-canonicalised payloads with vertices rendered as RIDs. Two counterfactual runs were taken by temporarily editing production source and restoring it: `elementFormIsUnobserved` forced to `true` (the step base's behaviour for the element form), and `isSingleValueProperty` reverted to its pre-commit body. Three fixtures: a three-vertex meta-property graph (`friendWeight` carrying `acl`, plus a vertex with a top-level `acl`), a four-vertex `age`/`name` graph, and a list-cardinality graph.

#### C1 The sub-walk escape lets the element form reach a payload-reading step — CONFIRMED

`elementFormIsUnobserved` returns true for every position inside a captured child, and a child's later steps are dispatched normally and their filters committed to the parent by `commitPureFilterChild`. Measured on four spellings, each with one boundary step and a row set disjoint from native's: `where` / `filter` / `and` over `__.properties("friendWeight").has("acl", "private")` return `[peter]` against native's `[marko]`, and the `not` spelling returns `[marko, josh]` against native's `[josh, peter]`. Both directions of the divergence are explained by the same plan: `WHERE alias.acl = 'private'` with no `friendWeight` conjunct. Raised as BG1.

#### C2 A sub-walk `values(key)` projection contributes nothing — CONFIRMED

`configureSingleKeyValues` (`GremlinProjectionAssembler.java:67-88`) expresses the drop only through `setResultShaping`, which `SubTraversalPredicateAdapter` swallows along with `clearReturnProjection`, `appendReturnColumn` and `setLastPropertyProjection`; `pinBoundary` records the alias the child was already on. No `putAliasFilter` call is on the path. Measured: `g.V().and(__.values("age"), __.values("name"))` returns three rows translated against native's one, with one boundary step. Raised as BG2.

#### C3 The VALUE-only narrowing withdraws `by(values(k).count())` — CONFIRMED

The post-strategy modulator body of `g.V().group().by(T.label).by(__.values("age").count())` prints as `[PropertiesStep(PROPERTY), CountGlobalStep]`, so `AdjacentToIncidentStrategy` supplied the element form from a written `values(key)`. At `c252146ba5` the shape declines (0 boundary steps); with `isSingleValueProperty` reverted to its pre-commit body it translates and matches native (`{Person=2}` on both arms). The `sum` and `max` siblings translate at HEAD, which locates the loss precisely at the strategy's count arm. Raised as BG3.

#### C4 The escape's positive control never reaches the gate — CONFIRMED

`TraversalFilterStepRecogniser.presenceKey` (lines 92-125) matches a `TraversalFilterStep` whose filter traversal is exactly one single-key `PropertiesStep` of either return type and maps it to `IS DEFINED` at line 56, before `recognizeWhereTraversal` and therefore before `walkChild`. `__.values("age")` is that shape. Measured: `where(__.values("age"))` filters correctly (two rows both arms) while `and(__.values("age"), __.values("name"))` over-emits, which separates the two paths without relying on the source read. Raised as BG4.

#### C5 The count look-ahead diverges from both exact-class precedents — CONFIRMED

Line 81 tests `instanceof`; `RangeGlobalStepRecogniser.followedByCount` (128-131) tests `getClass() ==` and documents the `instanceof` direction as the non-fail-closed one; `GremlinStepWalker:442` resolves through the registry. The pinned jar contains only `CountGlobalStep.class`, and grep over `core/src/main` finds no subclass, so nothing is live today. Raised as BG5 at suggestion severity for that reason.

#### C6 The third-position note names the wrong decline site — CONFIRMED

Measured step lists show `CountStrategy` inserting a `RangeGlobalStep` between the projection and the count for `values(age).count().is(gt(1))`, so `peek(0)` is the slice and the gate itself declines. `values(age).limit(1).count()`, `values(age).skip(1).count()` and `properties(age).limit(1).count()` also decline, and they still decline with `elementFormIsUnobserved` forced to `true`, which places their decline at `configureCount`'s `hasPreAggregateCardinalityClause` (line 81). The jar's `apply` skips `RangeGlobalStep` when advancing `prev`, so the element form reaches all of them. Raised as BG6.

#### C7 `SubTraversalPredicateAdapter`'s non-delegating override is wrong, or a nested adapter reintroduces the delegation — REFUTED

CLAIM: every other read on the adapter forwards to the parent, so a constant `false` here is either an inconsistency or breaks a nested child; and some other context implementation may still delegate, making the distinction unreliable.

REFUTATION: the override is correct and no implementation reintroduces the delegation. Grep for `implements RecognitionContext` over `core/src` returns exactly two classes, `WalkerContext:44` and `SubTraversalPredicateAdapter:83`; grep for `projectsReturnedPayload` over `core/src/test` returns nothing, so no test stub adds a third (the recogniser unit tests build a real `WalkerContext` through `contextAfterStart`, which is also why the new decline assertion is not vacuous — `#### C11`). The nested case holds: `SubTraversalPredicateAdapter.walkChild` (line 437) builds a fresh adapter wrapping `this`, and because the method returns a constant rather than reading the parent, a grandchild answers `false` at any depth. The read is consistent with its neighbours on the axis that matters — `boundaryOutputType` and `byModulatorIsProductive` delegate because they describe the whole traversal, while every result-shape write on this adapter is swallowed, which is the same fact `projectsReturnedPayload` reports.

RESIDUE: answering `false` is right about the parent's RETURN clause and wrong as a licence for the whole child, which is BG1 and BG2. The override does not need changing; its consumer does.

#### C8 The `instanceof` count match admits a live wrong answer — REFUTED

CLAIM: `instanceof CountGlobalStep<?>` accepts a subclass the walker's exact-class dispatch would reject, so the element form would be projected as a value and then read by a step the gate mistook for a count.

REFUTATION: the mistake cannot survive to a result. The walker dispatches on `recognisers.get(step.getClass())`, so an unregistered subclass returns no recogniser, `dispatchAll` fails, and the whole walk declines — the traversal runs natively and both arms agree by construction. The pinned jar also carries no subclass. The finding stands only as a consistency drift against the two sibling gates (`#### C5`).

#### C9 The element-form gate costs `values(k).limit(n).count()`, which translated before — REFUTED

CLAIM: `AdjacentToIncidentStrategy` skips `RangeGlobalStep` when tracking its predecessor, so `values(k).limit(n).count()` arrives in the element form with the slice at `peek(0)`; the new gate declines it where the step base accepted it, contradicting the Javadoc's claim that the gate "keeps this recogniser accepting everything it accepted before".

REFUTATION: the shape declined before as well, at a different gate. With `elementFormIsUnobserved` forced to `true`, `values(age).limit(5).count()`, `values(age).limit(1).count()`, `values(age).skip(1).count()` and `properties(age).limit(1).count()` all still measure zero boundary steps, because `GremlinAggregateAssembler.configureCount` declines on `hasPreAggregateCardinalityClause` (line 81) — a `count()` after a SQL `SKIP` / `LIMIT` has no correct composition, the statement-level slice being the step-7 BG3 defect. The Javadoc's accept-set claim therefore holds; only its explanation of why does not, which is BG6.

#### C10 The count escape is wrong for a list-cardinality property — REFUTED

CLAIM: `properties(k).count()` counts one element per value while the translated `count(*)` counts one row per element, so a vertex carrying several values under one key would diverge, and the escape's "one property element per value means the row count is the same either way" would be false.

REFUTATION: not reachable on this storage. Seeding `v.property(Cardinality.list, "tag", "a")` then `"b"` leaves one value: native `g.V().values("tag")` returns `[b]`, so the second assignment replaced the first rather than adding a second property. `values(tag).count()` and `values(tag)` both measure identical on the two arms. The escape's cardinality argument is untested rather than false, and there is no divergence to raise.

#### C11 The new decline assertion passes through a stub default rather than the gate — REFUTED

CLAIM: `propertiesElementForm_declines_whereValuesIsAccepted` asserts `Outcome.DECLINE` against a context whose `projectsReturnedPayload()` may default to `false` under a mock, in which case the gate returns true, the step is accepted, and the test would be measuring nothing — the pattern `## Surprises & Discoveries` records four times on this branch.

REFUTATION: the direction of the default makes the test self-witnessing. `contextAfterStart` returns a real `WalkerContext` (the assertions read its package-private `lastPropertyProjection` field directly), and `WalkerContext.projectsReturnedPayload` returns `true` at line 319. Had it been `false`, the gate would have accepted and the test would fail rather than pass. The second assertion — that a declined step leaves no projection — is true by construction, since the gate returns before `configureSingleKeyValues` runs, so it is a guard against a future reordering rather than a live check.

#### C12 The key-side narrowing also withdraws a shape — REFUTED

CLAIM: `isSingleValueProperty` is shared, so if the value-side loses `by(values(k).count())` the key-side loses `by(values(k))` the same way.

REFUTATION: measured identical with and without the narrowing. `g.V().groupCount().by(__.values("name"))` and `g.V().order().by(__.values("name"))` translate with one boundary step and matching payloads in both configurations, because `ByModulatorOptimizationStrategy` converts a one-step `by(values(k))` into a `ValueTraversal` that `classifyKey` matches at line 115, before the `PropertiesStep` arm. `AdjacentToIncidentStrategy` cannot supply the element form there either: its end-step arm requires the child's parent to be a `NotStep`, `TraversalFilterStep`, `WhereTraversalStep` or `ConnectiveStep`, and a `GroupStep` / `OrderGlobalStep` modulator parent is none of those. The commit message's cost claim is right for the key side and wrong only for the two-step value side (`#### C3`).
