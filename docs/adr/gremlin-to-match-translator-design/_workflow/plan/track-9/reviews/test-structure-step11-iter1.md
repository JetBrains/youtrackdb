<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TS10, sev: should-fix, loc: AndStepRecogniserTest.java:206, anchor: "### TS10 ", cert: n/a, basis: "InlineFilterStrategy deletes the AndStep before translation, so the two re-pointed pure-filter cases assert on a plain has-chain and no test now drives an accepted AndStep through the strategy pipeline"}
  - {id: TS11, sev: should-fix, loc: AndStepRecogniserTest.java:323, anchor: "### TS11 ", cert: n/a, basis: "new countBoundarySteps filters the single-plan subtype, so the isZero decline assertion would stay green against a MultiPlanMatchStep splice; three sibling classes count the shared base and document why"}
  - {id: TS12, sev: should-fix, loc: PredicateTraversalEquivalenceTest.java:954, anchor: "### TS12 ", cert: n/a, basis: "declined case left with no unique or non-vacuous assertion and a name still advertising root selection; assertEquivalent's DECLINED branch carries no non-empty guard"}
  - {id: TS13, sev: suggestion, loc: EdgeTraversalEquivalenceTest.java:1134, anchor: "### TS13 ", cert: n/a, basis: "assertNativeFanOut's fan-out check compares two call-site literals; the hasSize pins do the fixture work, and the javadoc credits the wrong line"}
  - {id: TS14, sev: suggestion, loc: PredicateTraversalEquivalenceTest.java:1011, anchor: "### TS14 ", cert: n/a, basis: "new test bundles the values(...) projection scenario under a name that mentions neither projection nor values"}
  - {id: TS15, sev: suggestion, loc: EdgeTraversalEquivalenceTest.java:1011, anchor: "### TS15 ", cert: n/a, basis: "renames left a broken @link and two section comments that describe coverage the block no longer carries"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

Verdicts on the material asked about, before the findings themselves.

**The eight flips.** All eight are honest inversions of a claim that genuinely
changed: each flipped case asserts DECLINE where it asserted ACCEPTED, and each
retains or strengthens its non-mutation assertions. No test was deleted (`@Test`
count across the six files goes 124 → 126). `assertRootsAtOrigin`'s removal from
`whereFragmentPostHopFilter_…` is forced, not convenient — a declined shape
builds no plan to read a root off. Root-selection coverage survives at four of
the five original call sites (`EdgeTraversalEquivalenceTest` 932 / 950 / 987,
`PredicateTraversalEquivalenceTest` 924); the one arm that is gone for good —
root selection on a target captured inside a `where(...)` fragment — cannot be
carried by anything, because no such shape translates now. TS12 covers what the
flipped case was left holding.

**The fan-out helper discriminates.** `assertNativeFanOut` is called at all
three edge-bearing declined cases in `EdgeTraversalEquivalenceTest` (767, 792,
854); the two declined cases in `PredicateTraversalEquivalenceTest` that claim
over-emission carry equivalent inline guards (987, 1019). The remaining
`Recognition.DECLINED` cases in both classes (`Edge` 377/635/879, `Predicate`
160/174/545) pin unrelated shapes and make no over-emission claim, so no guard
is owed. M3 checks out: deleting the second `a` edge drops both `and` join
shapes from 2 rows to 1 against `hasSize(2)`, and deleting alice's second
`knows` drops the `where` join shape from 3 to 2 against `hasSize(3)` — three
loud failures. TS13 is a wording-and-mechanism nit on the same helper, not a
refutation.

**M1 and M2 redden exactly their named targets.** `commitPositiveFilterChild`
has two callers, `WhereTraversalStepRecogniser:39` and
`TraversalFilterStepRecogniser:77`, so M1 reaches
`TraversalFilterStepRecogniserTest.whereTraversal_outKnows_declines`,
`WhereTraversalStepRecogniserTest.edgeBearingChild_declines` and
`…whereTraversalStep_edgeBearingChild_declines`,
`SubTraversalPredicateAdapterTest.commitPositiveFilterChild_edgeBearingChild_declinesWithoutMutatingParent`,
`EdgeTraversalEquivalenceTest.whereOutKnows_declinesAndMatchesNative`, and the
three `where`-fragment cases in `PredicateTraversalEquivalenceTest` — 8 across
5 classes, matching the report. M2 touches only the `AndStep` surface and
reaches the six `AndStepRecogniserTest` decline cases plus the two
`EdgeTraversalEquivalenceTest` AND cases — 8 across 2 classes. Two of the six
M2 targets are weakened by TS10, but both still redden under the mutation.

**The new decline-case trap.** `wherePostHop_edgeBearingChild_declinesAndMatchesNative`
can fail: under M1 the translator-on run engages a boundary step, which breaks
`assertEquivalent`'s `boundaryOn == 0`, and the `values("name")` half breaks
independently because the join reading emits josh's name twice against
`containsExactly("josh")`. Its fan-out precondition (`V(josh).out("created")`
has size 2) is the right one for the claim it makes.

### TS10 [should-fix] The two re-pointed pure-filter AND cases never reach `AndStepRecogniser`

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/AndStepRecogniserTest.java`,
methods `recursiveOptimizationPreservesPureFilterAndTranslation` (line 206) and
`applyStrategies_engagesBoundaryStepOnlyForThePureFilterAnd` (line 227, the
pure-filter half at 244-249).

**Issue**: `InlineFilterStrategy` rewrites `V().and(has(age), has(name))` into a
plain `has`-chain and deletes the `AndStep` before the translator ever sees it,
so both cases assert on a traversal that contains no `AndStep`. Measured against
the fork's `gremlin-core` 3.8.1-67860f6:

- `InlineFilterStrategy` is in `TraversalStrategies.GlobalCache.getStrategies(Graph.class)`,
  which `YTDBGraphImplAbstract.registerOptimizationStrategies` clones as its base
  list, and it is an `OptimizationStrategy` while `GremlinToMatchStrategy` is a
  `ProviderOptimizationStrategy` — the former runs first.
- `applyStrategies()` on `V().and(__.has("age", eq(30)), __.has("name", eq("Hub")))`
  yields `[GraphStep, HasStep([age.eq(30), name.eq(Hub)])]`.
- The test's own manual loop — iterating `getStrategies()` and calling
  `TraversalHelper.applyTraversalRecursively` for each `OptimizationStrategy` —
  produces the same rewrite.

So `recursiveOptimizationPreservesPureFilterAndTranslation` translates a
has-chain under a name claiming AND coverage, and the assertion message "a
pure-filter and(...) must still engage the boundary step" describes something the
assertion does not observe. The contrast half of
`applyStrategies_engagesBoundaryStepOnlyForThePureFilterAnd` still serves its
stated purpose (proving the strategy has not stopped engaging altogether), but
not the AND-specific claim its message makes.

The wider consequence is a coverage hole the step opened without naming: after
this change no test drives an accepted `AndStep` through the real strategy
pipeline. Every pure-filter AND is inlined away — `and(has, or(has, has))`,
`and(has, not(has))` and `and(has, and(has, has))` all collapse — and every AND
that survives inlining is edge-bearing and now declines. The recogniser's
ACCEPTED path is exercised only by hand-driven unit tests over un-strategised
traversals (`pureFilterChildren_andComposesFiltersOnBoundary` at 51,
`productionWalk_andTwoPureFilters_buildsExecutionPlan` at 156, which passes the
un-strategised admin straight to `GremlinToMatchTranslator.translate` and is
therefore sound).

**Suggestion**: pick an AND shape that survives inlining. A barrier in one arm
does it and stays pure-filter:
`V().and(__.has("age", P.eq(30)).barrier(), __.has("name", P.eq("Hub")))`
strategises to `[GraphStep, AndStep([[HasStep, NoOpBarrierStep], [HasStep]])]`,
and `NoOpBarrierStep` is already in the walker's transparent set. Add
`assertThat(TraversalHelper.hasStepOfClass(AndStep.class, admin)).isTrue()` after
`applyStrategies()` in both tests so the AND's survival is pinned rather than
assumed — that assertion is exactly what would have caught this. If keeping the
plain has-chain is preferred, rename both tests and drop the AND claim from the
javadoc and the `.as(...)` message.

### TS11 [should-fix] The new decline assertion counts the single-plan step subtype

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/AndStepRecogniserTest.java`,
helper `countBoundarySteps` (line 323), used by
`applyStrategies_engagesBoundaryStepOnlyForThePureFilterAnd` (line 240).

**Issue**: the new helper filters `YTDBMatchPlanStep`, the single-plan subtype.
Three sibling classes in the same package count the shared base
`AbstractMatchPlanStep` instead — `EdgeTraversalEquivalenceTest:1233`,
`PredicateTraversalEquivalenceTest:1303`, `RepeatDeclineStrategyTest:730` — and
two of them carry the reason in a javadoc: "counting only the single-plan subtype
would let such a shape satisfy a decline expectation while the translator in fact
accepted it." `MultiPlanMatchStep` and `YTDBMatchPlanStep` are siblings under
`AbstractMatchPlanStep`, so the `isZero()` half of the new test — the one
assertion in the class that pins the step's headline behaviour at strategy level
— would stay green if the edge-bearing AND were ever translated into a
multi-plan splice. The pre-existing `isEqualTo(1)` usage did not have this
problem; a decline assertion does.

**Suggestion**: filter on `AbstractMatchPlanStep<?, ?>`, matching the three
siblings, and carry a one-line note saying why the supertype is deliberate.
(Symbol enumeration here is grep-only — mcp-steroid PSI `execute_code` times out
in this repository — but the class declarations were read directly, so the
hierarchy claim does not rest on the search.)

### TS12 [should-fix] The flipped `pinnedOrigin` case keeps a misleading name and loses its last unique assertion

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java`,
method `whereFragmentPostHopFilter_pinnedOrigin_declinesAndMatchesNative`
(line 954).

**Issue**: after the flip the method body holds one call,
`assertEquivalent(..., Recognition.DECLINED, traversal)`, and nothing else. Three
things follow.

- Its javadoc claims the case "returns native's `[marko]`", but no assertion pins
  that. For a DECLINED expectation `assertEquivalent` checks `boundaryOn == 0`,
  `boundaryOff == 0`, and `onIds == offIds` — and the last is trivially true when
  both runs execute natively. The non-empty guard that the RECOGNIZED branch
  carries, with a javadoc explaining precisely this vacuity risk (lines
  1140-1146), has no DECLINED counterpart. The same gap exists in
  `EdgeTraversalEquivalenceTest.assertEquivalent` (lines 1077-1094). The other
  two new DECLINED cases escape it because their own fan-out guards happen to
  assert sizes; this one has no guard.
- The remaining assertion, "an edge-bearing `where(...)` declines", is made twice
  more in the same class, at 999 and 1026, both with result assertions attached.
- The name still says `pinnedOrigin` and the body still pins marko and josh,
  though root selection no longer runs for this shape. A reader who greps for
  root-selection coverage lands here and finds none.

**Suggestion**: give the case the assertion its javadoc already promises —
`assertThat(nativeSortedIds(traversal)).containsExactly(markoId.toString())`,
which also makes it non-vacuous — and rename it to drop `pinnedOrigin` (for
example `whereFragmentPostHopFilter_singleMatchingTarget_declinesAndMatchesNative`,
which is what the javadoc now argues the case is about). Folding it into the
sibling at 978 and deleting it is the other defensible exit. Separately, consider
adding a non-empty guard to the DECLINED branch of both `assertEquivalent`
helpers so future declined cases cannot go vacuous the same way.

### TS13 [suggestion] `assertNativeFanOut`'s fan-out check compares two constants

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java`,
helper `assertNativeFanOut` (line 1116), assertion at 1134-1137.

**Issue**: `assertThat(expectedJoinRows).isGreaterThan(expectedFilterRows)`
compares two `int`s the caller passed as literals. It observes nothing about the
graph and cannot fail for any fixture state — it fires only when an author
supplies equal expectations. What actually fails when a fixture loses its fan-out
is `hasSize(expectedJoinRows)` at 1131, which is measured. The helper works as a
composition, verified above under M3, so this is not a hole. It is a
documentation defect with a maintenance cost: the javadoc at 1113-1114 credits
the guarantee to "requiring strictly more rows from the join shape", pointing the
next editor at the line that does the least work. Someone who later relaxes the
`hasSize` pins to a range, reasoning that the `isGreaterThan` is the real guard,
would silently disarm the helper.

**Suggestion**: assert on the measured lists rather than on the parameters —
capture both `toList()` results and assert `joinRows.size() > filterRows.size()`
alongside the two `hasSize` pins — and reword the javadoc so it names the
`hasSize` assertions as the fixture guard and the comparison as the caller-
argument check it is.

### TS14 [suggestion] The new decline case bundles the projection scenario under an unrelated name

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/PredicateTraversalEquivalenceTest.java`,
method `wherePostHop_edgeBearingChild_declinesAndMatchesNative` (line 1011),
second half at lines 1028-1037.

**Issue**: after the case's own assertions complete at line 1026, the method
starts a second scenario — the same shape with a `values("name")` tail, drained
through the new `drainAsStrings` helper. The comment calls it "the spelling that
first surfaced the over-emission outside this suite", which makes it the more
load-bearing of the two, yet a failure there reports under a method name
mentioning neither the projection nor `values`, and the first half's assertions
have to pass before the second half runs at all. The shapes also differ in kind:
the first returns elements and goes through `assertEquivalent`, the second
returns strings and needs a separate drain helper precisely because
`sortedIds` casts to `Vertex`.

**Suggestion**: split at line 1027 into
`wherePostHop_edgeBearingChild_valuesTail_matchesNative`, carrying the
`drainAsStrings` comparison and its own fan-out precondition. Both halves then
run and report independently.

### TS15 [suggestion] Renames left a broken `@link` and two section comments describing lost coverage

**Issue**: four documentation sites drifted from what the code now does.

- `EdgeTraversalEquivalenceTest.java:1011` — the `seedDualLabeledOutEdges`
  javadoc says "the fixture for `{@link #andTwoOutHops_differingTargets_matchesNative}`".
  That method was renamed in this diff to
  `andTwoOutHops_differingTargets_declinesAndMatchesNative`; the `@link` now
  resolves to nothing.
- `EdgeTraversalEquivalenceTest.java:751` — the section header reads "Connective
  AND over edge filters — alias-isolation trap." Neither test beneath it
  exercises alias isolation any more; both decline before an anonymous alias is
  minted. The end-to-end alias-isolation claim the header names now lives only at
  unit level, in
  `SubTraversalPredicateAdapterTest.siblingChildren_mintDistinctAliasesFromParentSequence`.
- `PredicateTraversalEquivalenceTest.java:897-908` — the section comment says the
  block pins a non-root-alias predicate reaching the executor "directly, inside a
  `where(...)` fragment, and on a `not(...)` sub-traversal". The where-fragment
  arm is gone: all three where-fragment cases in the block decline and build no
  plan. The comment's closing sentence, "Every one of them returns an over-large
  multiset when the constraint is dropped, never an error", is likewise no longer
  true of half the block.
- Adjacent and untouched by this diff, so listed for the record rather than as a
  request: `SubTraversalPredicateAdapterTest.java:367` motivates alias isolation
  with `and(__.out("a"), __.out("b"))`, a shape that now declines.

**Suggestion**: repoint the `@link`, retitle the `EdgeTraversalEquivalenceTest`
section to name the decline it now pins, and rewrite the
`PredicateTraversalEquivalenceTest` block comment to describe the two arms that
survive plus the three declined cases that share the section for shape reasons
rather than for the binding rule.

## Evidence base
