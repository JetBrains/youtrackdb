<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: BG20, sev: should-fix,  loc: HasStepRecogniser.java:150, anchor: "### BG20 ", cert: C1, basis: "measured: g.V().out().has(name, gt(27)) answers 6 translated and 0 native at boundary 1 — the same fold-only-at-root divergence step 13 declines, on the unnegated post-hop surface the decline does not reach; pre-existing, outside this step's scope"}
  - {id: BG21, sev: suggestion,  loc: GremlinPredicateAdapter.java:573, anchor: "### BG21 ", cert: C2, basis: "the detector inspects HasContainerHolder steps and nested child traversals only, so a WherePredicateStep's range comparison passes the gate; benign today because both arms order by RID, but the Javadoc's however-deeply-nested claim overstates the search"}
  - {id: BG22, sev: suggestion,  loc: NotStepRecogniser.java:78, anchor: "### BG22 ", cert: C4, basis: "the gate is type-unaware, so it withdraws negated range comparisons whose two arms already agree; measured same-type control g.V().out().has(age, gt(30)) agrees 1/1"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 9}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED,   anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED,   anchor: "#### C12 "}
flags: [CONTRACT_OK, ROUTED_OUT_OF_STEP]
-->

## Findings

### BG20 [should-fix] The same divergence runs unnegated after any hop, and the translator answers it wrong

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/HasStepRecogniser.java` (line 150, the `toFilter` accept; the divergence belongs to every translated post-hop range comparison, not to one line)

**Issue**: `g.V().out().has("name", P.gt(27))` on the modern graph returns six rows with the translator on and zero with it off. The translated arm engages one boundary step, so this is a translated answer, not a fallback. The lead the implementer surfaced is real and the step's decline does not reach it.

The mechanism is the one the step's own comment describes, minus the negation. YouTrackDB folds a range `has(...)` into `YTDBGraphStep` only at the traversal source — `YTDBGraphStep` is the sole `HasContainerHolder` in the `core` Gremlin tree, and `GraphStep` appears only at a source. Every `has(key, gt|gte|lt|lte)` after the first hop stays an unfolded `HasStep`, where the fork's `Compare` calls `GremlinValueComparator.comparable` and answers `false` for two operands of different type categories. The emitted SQL instead ranks a String above an Integer and returns the row. So the folded root agrees and everything downstream of a hop can disagree.

**Evidence** (`#### C1`): measured on `b1c8fd9fed` in the step-13 worktree, one probe class, translator forced on then off, boundary steps counted on the strategised traversal.

```
A rootFolded  g.V().has(name, gt(27))        boundary=1  on=6  off=6  AGREE
B postHop     g.V().out().has(name, gt(27))  boundary=1  on=6  off=0  DIVERGE
C postHop     g.V().out().has(name, lt(27))  boundary=1  on=0  off=0  AGREE
D postHopSame g.V().out().has(age, gt(30))   boundary=1  on=1  off=1  AGREE
E notPostHop  g.V().not(out().has(name,gt))  boundary=0  on=6  off=6  AGREE
```

Row A isolates the fold; row B is the defect; row C shows the `lt` direction agreeing only because SQL's ranking puts every String above `27`, so an Integer property against a String comparand would flip it; row D shows the divergence needs a type mismatch and not merely a range comparison; row E confirms this step's decline covers the negated form of the same shape.

**Refutation considered**: three ways this could have been a false alarm, all closed. The shape might not have been translated — the boundary count of 1 says it was. The child might have been folded after all — only `YTDBGraphStep` implements `HasContainerHolder` in `core`, and a scan of all 210 step classes in `gremlin-core-3.8.1-67860f6-SNAPSHOT` finds `HasStep` as the only other implementer, so nothing folds a `has` into a `VertexStep`. Native might have thrown rather than answered zero — `Compare`'s bytecode returns `false` when `comparable` rejects the pair, and the off-arm returned an empty list rather than an error. Cucumber carries no witness because the TinkerPop suite compares `age` against Integers.

**Suggestion**: route this to the plan rather than to step 13. A blanket post-hop decline withdraws a large share of recognised traversals, which the implementer already flagged. The position-aware option is the one the measurement points at: the translator knows whether a `has` sits at the folded source or downstream of a hop, so it can keep today's ranking clause at the source and emit a clause that reproduces the incomparable-is-false rule — a type-category guard on the stored value — at every other position. That matches each native path where that path is the one that runs.

### BG21 [suggestion] The detector does not see a `where(P)` predicate, and its Javadoc says it does

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 573-590)

**Issue**: `traversalHasRangeComparison` reaches a predicate two ways — a step that is a `HasContainerHolder`, and a nested child traversal under `TraversalParent`. `WherePredicateStep` is neither. It holds its `P` in a field read through `getPredicate()`, and `WherePredicateStepRecogniser` translates it into a `$matched.<alias>.@rid` comparison. So `g.V().as("a").out().not(__.where(P.gt("a")))` carries a range comparison inside the negated sub-traversal, passes the gate, and translates.

The method's Javadoc claims the search finds a comparison "however deeply the comparison is nested". That holds for the `has(...)` family and overstates the rest.

**Evidence** (`#### C2`, `#### C3`): grep-only plus bytecode; mcp-steroid PSI times out in this repository, so the "only two entry points into a predicate" claim rests on reading the method and the recogniser registry rather than on find-usages.

**Refutation considered**: the gap looks harmless today, which is why this is a suggestion and not a defect. Both arms order the same values. Native compares two `Vertex` operands, and the fork's `GremlinValueComparator.comparable` forwards an element pair to their `id()`s; YouTrackDB's `id()` returns `RID`, which extends `Identifiable extends Comparable<Identifiable>`, so `naturallyComparable` accepts the pair and native orders by RID natural order. The translated side emits `@rid` on both operands. A label bound to something other than a pattern node does not resolve and already declines. Not measured end to end.

**Suggestion**: pick one. Add a `WherePredicateStep` case to the detector, or narrow the Javadoc to the two entry points the method actually walks so the next reader does not inherit a completeness claim the code does not carry.

### BG22 [suggestion] The gate withdraws negated range comparisons whose two arms already agree

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/NotStepRecogniser.java` (lines 70-81)

**Issue**: the gate fires on the `Compare` kind alone. The divergence it closes needs a type mismatch as well — measured row D, `g.V().out().has("age", P.gt(30))`, agrees 1/1 because every `age` in the fixture is an Integer. So `not(__.out("knows").has("age", P.gt(30)))` is declined although both arms would have answered the same, and the step's own test says as much when it explains why that case needs a boundary-count assertion instead of multiset equality.

The withdrawn set is the commonest negated-range shape: a numeric property against a numeric comparand. Cucumber did not regress (1930/5/14 to 1930/4/14), so nothing is broken by it; the cost is recognised coverage.

**Evidence** (`#### C4`): probe rows D and E above, plus `notWithRangeComparisonBehindHop_declinesToNative` in the diff.

**Refutation considered**: narrowing has to stay sound, and the obvious narrowing is not sound on the fixture that witnessed the problem. `age` is undeclared in `ModernGraphFixture` — only edge `weight` is declared — so a schema-less property can hold any type at any time and its agreement in the fixture is an accident of the data. A narrowing keyed on the runtime values would be wrong; one keyed on a declared schema type would be sound, because a declared property cannot hold a value outside its type.

**Suggestion**: consider gating on the declared schema type where one exists, and keeping the blanket decline for undeclared keys. `RecognitionContext` already reaches the schema through `isDeclaredStringProperty`, so a general declared-type lookup is the missing piece rather than a new plumbing path. Worth weighing against the cost of another type-aware branch; the step's exit is defensible as it stands.

## Evidence base

#### C1 A post-hop cross-type range comparison translates and answers wrong — CONFIRMED

Measured: `g.V().out().has("name", gt(27))` returns 6 translated and 0 native at boundary 1; controls A, C, D, E isolate the fold, the direction, the type mismatch, and this step's decline.

#### C2 `traversalHasRangeComparison` does not inspect a `WherePredicateStep` predicate — CONFIRMED

The method reaches predicates through `HasContainerHolder` and `TraversalParent` children only; `WherePredicateStep` is neither, and its `P` is translated by `WherePredicateStepRecogniser`.

#### C3 The `WherePredicateStep` gap produces wrong answers today — REFUTED

The claim was that `not(__.where(P.gt("a")))` translating without the gate reproduces BG20's divergence on a second surface. Both arms turn out to order the same values, so there is nothing to diverge on.

Native path: `WherePredicateStep` evaluates `Compare.gt` on two traversers. In `gremlin-core-3.8.1-67860f6-SNAPSHOT`, `Compare` delegates to `GremlinValueComparator.comparable`, whose element branch (bytecode offsets 327-390) forwards a `Vertex` / `Edge` / `VertexProperty` pair to `comparable(a.id(), b.id())`. YouTrackDB's `YTDBElementImpl.id()` returns `RID`; `RID extends Identifiable`, and `Identifiable extends Comparable<Identifiable>`. `Type.type(RID)` lands on `Unknown`, both sides equal, so `comparable` falls to `naturallyComparable`, which accepts two same-class `Comparable` instances (bytecode offsets 0-49). Native therefore orders by RID natural order.

Translated path: `GremlinPredicateAdapter.toMatchedLabelFilter` builds `@rid` accessors for both operands (`leftMatchedOperand`, `translateMatchedLabelPredicate`), and an unresolvable label declines rather than emitting a silent accessor.

Same ordering on both sides, so the gap costs correctness nothing that this review can find. It still leaves a Javadoc claim wider than the code, which is what BG21 reports. Not measured end to end; grep and bytecode only.

#### C4 The decline is broader than the divergence it closes — CONFIRMED

The gate branches on the `Compare` kind with no type input, and the measured same-type control (`age > 30`, 1/1) shows a type mismatch is also required for the two arms to disagree.

#### C5 A cached plan can serve a shape the new gate would decline — REFUTED

The claim was that the plan cache keys on a fingerprint coarse enough to let an accepted `not(has(key, eq(v)))` plan be replayed for a declined `not(has(key, gt(v)))`, bypassing the gate.

`GremlinPlanFingerprint.fingerprint` takes a post-walk `MatchPlanInputs`, and `GremlinToMatchStrategy` calls it at line 445, after the walk has produced those inputs, with the cache get and put at lines 447 and 457. A declined walk produces no `MatchPlanInputs`, so it never reaches the fingerprint or the lookup. The cache holds compiled MATCH plans keyed on translated structure, and recognition runs afresh on every traversal. The ordering makes the bypass impossible rather than unlikely.

#### C6 The gate mutates the recognition context before declining — REFUTED

The claim was that the decline is not a clean decline, so a contribution reaches the parent context through a setter the sub-walk adapter would otherwise swallow.

The gate sits at `NotStepRecogniser:78-80`, ahead of the `ctx.walkChild(child)` call. `traversalHasRangeComparison` only reads: `getSteps`, `getHasContainers`, `getLocalChildren`, `getGlobalChildren`, `getPredicate`, `getBiPredicate`, and `NotP.negate()`. Nothing before it on this path writes to `ctx` either — `hasNotPresenceKey` is a pure read and the `putAliasFilter` it enables sits behind its own `return`. Two further layers make it moot: `SubTraversalPredicateAdapter` never runs at all, and `GremlinStepWalker` is all-or-nothing, so a declined step discards the whole walk. Measured: probe row E reports boundary 0 with 6 rows on both arms.

#### C7 `between` / `inside` / `outside` slip past the detector — REFUTED

The claim was that the four-`Compare` switch misses the range predicates that have no `Compare` constant of their own.

TinkerPop decomposes them before the translator sees them — `between(lo, hi)` arrives as `AndP[gte lo, lt hi]`, `inside` as `AndP[gt, lt]`, `outside` as `OrP[lt, gt]` — and `predicateHasRangeComparison` recurses `AndP` and `OrP` before it reads any bi-predicate. The adapter's class Javadoc already documented the decomposition for the translation path, and the diff's own tests assert `P.between`, `P.inside`, `P.outside` all report true, with `notWithBetweenPredicate_declinesToNative` covering the end-to-end form. Read, not independently run.

#### C8 A range comparison nested behind a hop, a connective, or a branch slips past — REFUTED

The claim was that the detector stops at the top-level step list and misses a comparison one level down.

Structurally there are only two places a translatable predicate can sit, and the method covers both. A `has(...)` clause reaches the walker as a `HasContainer` on a `HasContainerHolder`: scanning all 210 step classes in `gremlin-core-3.8.1-67860f6-SNAPSHOT` finds `HasStep` as the only implementer, and `YTDBGraphStep` is the only one in `core`. Every filter connective and branching step exposes its sub-traversals through `TraversalParent`, and the method walks both the local and the global child lists. `WherePredicateStep` is the one exception, which C2 and BG21 own separately. Measured: probe row E declines `not(out().has(name, gt(27)))` at boundary 0; the diff's tests cover the `and`, `union`, and post-hop shapes.

#### C9 The decline swallows the equality form — REFUTED

The claim was that the recursion over-reports and withdraws `not(has(key, eq(v)))`, the commonest predicate under a negation.

Measured: `g.V().not(has("name", eq("marko")))` engages one boundary step and answers 5 on both arms. The switch answers false for `eq` and `neq`, and the leaf branch reads the bi-predicate only after the connective cases, so a `Contains` or `Text` bi-predicate cannot reach the `Compare` switch. The diff's unit tests pin `within`, `without`, `TextP.containing`, and a null predicate at false.

#### C10 The unnegated folded root path is disturbed — REFUTED

The claim was that the step's mutation testing found a real coupling, so the gate leaks into `HasStepRecogniser`.

The diff touches two production files, `GremlinPredicateAdapter` and `NotStepRecogniser`, and adds only new static methods to the first. `HasStepRecogniser` is unchanged, and the M_D mutation the implementer reports is a deliberate injection into that unchanged file rather than an observed coupling. Measured: probe row A, `g.V().has("name", gt(27))`, engages one boundary step and answers 6 on both arms.

#### C11 `NotP.negate()` fails to unwrap, or recurses without a base case — REFUTED

The claim was that routing `NotP` through `negate()` either returns a re-wrapped predicate, which would loop, or a negated leaf, which would flip the detector's answer for `P.not(P.gt(v))`.

`NotP.negate()` in the fork returns the `originalP` field directly (a two-instruction `getfield` / `areturn`), so each recursion strips exactly one wrapper and a nested `P.not(P.not(P.gt(v)))` terminates at the leaf. This is the same route `translate()` already takes, so detection and translation agree on what the wrapped predicate is.

#### C12 `values(k).is(gt(v))` under `not(...)` slips past the detector and translates — REFUTED

The claim was that `IsStep` carries a `P` and is neither a `HasContainerHolder` nor a `TraversalParent`, so `not(__.values("age").is(P.gt(30)))` evades the gate.

It evades the gate and still cannot translate, by two independent routes. `IsStep` has no entry in `GremlinStepWalker.PRODUCTION_RECOGNISERS`, and the walker declines the whole traversal on the first step class with no recogniser, so the sub-walk fails at the `is(...)` and `NotStepRecogniser` declines on a non-accepted adapter. Ahead of that, `InlineFilterStrategy` folds `values(k).is(P)` into `has(k, P)` inside a filter child, which puts the predicate back on a `HasContainerHolder` where the detector does see it. Read, not independently run.
