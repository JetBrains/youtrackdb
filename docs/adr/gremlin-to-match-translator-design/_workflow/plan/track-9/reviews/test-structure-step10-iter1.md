<!-- MANIFEST
findings: 7   severity: {blocker: 1, should-fix: 5, suggestion: 1}
index:
  - {id: TS16, sev: blocker,    loc: GremlinStepWalkerTest.java:832, anchor: "### TS16 ", cert: n/a, basis: "four of the six where-scope decline cases have edge-bearing children, which ConnectiveStepSupport declined already at the step's base; all four stay green with the transparency fix reverted"}
  - {id: TS17, sev: should-fix, loc: GremlinStepWalkerTest.java:938, anchor: "### TS17 ", cert: n/a, basis: "assertDeclinesAndMatchesNative compares native against native once the decline fires; its expectRows guard does not rescue that, and two javadocs credit a mutation that is not in the file"}
  - {id: TS18, sev: should-fix, loc: OrderRangeStepRecogniserTest.java:637, anchor: "### TS18 ", cert: n/a, basis: "three javadoc blocks stacked on seedAgeOnLastScannedVertex; the scan-order-independence argument for four cases is attached to the wrong fixture and two fixtures carry no doc"}
  - {id: TS19, sev: should-fix, loc: WhereTraversalStepRecogniserTest.java:116, anchor: "### TS19 ", cert: n/a, basis: "the transparency change makes WhereTraversalStepRecogniser decline unconditionally; two existing cases now decline before the mechanism their javadoc names, and the class has no accepting case left"}
  - {id: TS20, sev: should-fix, loc: OrderRangeStepRecogniserTest.java:569, anchor: "### TS20 ", cert: n/a, basis: "orderThenLimitThenValues credits order().by(k)'s IS DEFINED conjunct, but the slice is recognised before any shaping exists, so the carve-out it names cannot fire in this step order"}
  - {id: TS21, sev: should-fix, loc: OrderRangeStepRecogniserTest.java:713, anchor: "### TS21 ", cert: n/a, basis: "four new copies of the house assertEquivalent harness, all dropping its boundaryOff == 0 pin, so no new case would notice a translator-off arm that stayed on"}
  - {id: TS22, sev: suggestion, loc: OrderRangeStepRecogniserTest.java:475, anchor: "### TS22 ", cert: n/a, basis: "ten end-to-end cases and three fixtures added to a recogniser unit-test class; helpers interleaved mid-class in GremlinStepWalkerTest split the slice group under the where banner"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS16 [blocker] Four of the six where-scope decline cases are satisfied by a gate that predates this step

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, methods `walk_whereChildWithEndLabel_declines` (line 832), `walk_whereChildWithStartLabelOnly_declines` (line 847), `whereWithEndLabel_declinesAndReturnsNativeRows` (line 893), `whereWithSelfComparingEndLabel_declinesAndReturnsNativeRows` (line 910)

**Issue**: All four cases put a hop in the `where` child:

| Case | Child |
|---|---|
| `walk_whereChildWithEndLabel_declines` | `__.as("a").out().as("b")` |
| `walk_whereChildWithStartLabelOnly_declines` | `__.as("a").out()` |
| `whereWithEndLabel_declinesAndReturnsNativeRows` | `__.as("a").out().as("b")` |
| `whereWithSelfComparingEndLabel_declinesAndReturnsNativeRows` | `__.as("a").out().as("a")` |

An edge-bearing positive-filter child already declined before this step. `ConnectiveStepSupport.commitPositiveFilterChild` (line 114) returns `DECLINE` on `adapter.hasEdges()`, and a hop in the sub-walk flips that flag through `SubTraversalPredicateAdapter.addEdge` (line 268). The gate landed in `db3fe3b422` "Decline edge-bearing positive filters", which `git merge-base --is-ancestor db3fe3b422 12b1359ce3~1` confirms is an ancestor of this step's base. `WhereTraversalStepRecogniserTest.whereTraversalStep_edgeBearingChild_declines` (line 116) pinned that outcome for the path-scoped spelling before this step was written.

Restore `WhereStartStep` and `WhereEndStep` to `TRANSPARENT_STEPS` and each of the four children reduces to a bare `VertexStep`, the sub-walk accepts it, `hasEdges` is true, and the filter declines anyway. All four cases stay green with the fix reverted. They are the branch's recurring shape: a decline assertion whose expected value is "nothing happened", satisfied by a mechanism other than the one it names.

The one case that does discriminate is `walk_whereChildLabelledPureFilter_declines` (line 879) with a pure-filter child, and its own javadoc says so — "the child is a pure filter, so no edge-bearing-child gate can account for the decline". `WhereTraversalStepRecogniserTest.whereTraversalStep_pathScopedPureFilter_declines` (line 98) is the recogniser-level twin. Both use `g.V().as("a").where(__.as("a")…)`, where `a` is the current element, which the javadoc correctly labels "the priced surface, not a defect witness".

So the diff has no case covering a shape whose answer the fix changes. The gap is reachable and narrow: a pure-filter child whose start label points at a traverser that is not the current element.

```java
g.V().as("a").out().as("b").where(__.as("a").has("age", 30))
```

Before the fix the skipped `WhereStartStep` ran the `has` against `b`, the hop target, instead of against `a`. That is a wrong row set, no hop is involved, and the edge-bearing gate cannot account for it. The end-label variant `where(__.as("a").has("age", 30).as("b"))` has the same property.

The same reading applies to the three measured divergences in `GremlinStepWalker.TRANSPARENT_STEPS`' javadoc (diff lines 30-37). Two of the three bullets use edge-bearing children, so the figures quoted for them could not have been measured against this step's base. That part belongs to the bugs reviewer; I raise it here because the test javadocs at lines 888-890 and 902-907 repeat the same claims.

**Suggestion**: Add the divergent pure-filter case at the walker level, beside `walk_whereChildLabelledPureFilter_declines`:

```java
/**
 * The start binding points at a traverser that is not the current element, which is where the
 * transparency mattered: skipping the binding ran the filter against the hop target {@code b}
 * instead of against {@code a}, and returned a different row set. No hop in the child, so the
 * edge-bearing gate cannot account for this decline.
 */
@Test
public void walk_whereChildStartLabelOffCurrentElement_declines() {
  var admin =
      graph.traversal().V().as("a").out().as("b").where(__.as("a").has("age", 30)).asAdmin();

  assertThat(GremlinStepWalker.production().walk(admin)).isNull();
}
```

Then either re-point the two end-to-end cases at that shape or drop them (see TS17), and correct the four javadocs so each names the gate that actually carries it.

### TS17 [should-fix] The end-to-end where helper's anti-vacuity guard does not guard

**File**: `GremlinStepWalkerTest.java`, method `assertDeclinesAndMatchesNative` (line 938)

**Issue**: Once the walk declines, the translator-on arm *is* the native pipeline. The multiset equality at line 965 then compares native against native and holds no matter what the fixture contains. The `expectRows` flag documented at lines 933-936 — "passing `true` adds a non-empty guard so the equality is not held over two empty lists" — moves the comparison from empty-versus-empty to non-empty-versus-itself. Neither can fail.

The sibling helper in the same package states the problem and solves it. `OrderRangeStepRecogniserTest.assertClauseThenStepDeclines` (line 713) takes a third supplier, `clauseLastSpelling`, and asserts that native's answer for it differs from native's answer for the shape. Its javadoc: "a decline compares native against native, which agrees no matter what the fixture holds, so without it the test would still pass on a fixture where both spellings mean the same thing and would pin nothing." That control is missing here.

With `expectRows = false`, `whereWithSelfComparingEndLabel_declinesAndReturnsNativeRows` (line 910) reduces to `boundaryOn == 0` over two empty lists. Under TS16 that assertion is already satisfied by the edge-bearing gate, so the case pins nothing at all.

Two javadocs point at a control that is not in the repository. Line 906 says "the mutation below is what proves it" and line 936 says the case "rests on the boundary assertion plus its mutation". There is no mutation harness in the file; `grep -n mutat GremlinStepWalkerTest.java` returns only the unrelated no-mutation-on-decline group at lines 209-259. A reader following "below" finds nothing. If the mutation was an out-of-band experiment, the javadoc should say so and cite the episode; if it was meant to land, it did not.

**Suggestion**: Give the helper the same third argument as its sibling — the traversal the pre-fix translation collapsed the shape onto, which for the where family is the transparent reading with the scope steps stripped:

```java
private void assertDeclinesAndMatchesNative(
    String scenario,
    Supplier<GraphTraversal<?, ?>> shape,
    Supplier<GraphTraversal<?, ?>> transparentReading) {
  …
  assertThat(offRows)
      .as(scenario + ": the fixture must separate the shape from its transparent reading, or "
          + "the assertions below witness nothing")
      .isNotEqualTo(sortedStrings(drain(transparentReading)));
```

For `whereWithEndLabel_declinesAndReturnsNativeRows` the transparent reading is `g.V().as("a").out().as("b").where(__.out())`. Fix the two javadocs in the same pass: drop the phantom mutation, or replace it with a pointer to where the experiment is recorded.

If the two cases are re-pointed at the pure-filter shape from TS16, the same control applies and the fixture already supports it — `seedOneKnowsEdgeNoSelfLoop` gives Alice an out-edge to Bob, so a filter on `a` and a filter on `b` separate.

### TS18 [should-fix] Three javadoc blocks stacked on one fixture helper

**File**: `OrderRangeStepRecogniserTest.java`, lines 637-659, applying to `seedAgeOnLastScannedVertex` (line 660), `seedSharedTargetWithDuplicateNames` (line 678), `seedSingleHub` (line 689)

**Issue**: Three consecutive `/** … */` blocks sit between `sliceThenValueMap_stillTranslates` and `seedAgeOnLastScannedVertex`. Only the last one describes that method. The first documents `seedSingleHub` and the second documents `seedSharedTargetWithDuplicateNames`, both of which are declared 20 and 30 lines further down with no doc comment. Javadoc and the IDE attach only the block immediately preceding the declaration, so the first two are dead comments on the wrong member.

The misplacement costs the file its most load-bearing paragraph. The `seedSingleHub` block carries the scan-order-independence argument for four cases — that the hop's full output is three rows, so `LIMIT 2` yields two and `SKIP 2` yields one while native reaches either all three or none, and "neither case can pass by accident on a lucky scan order". This branch has already retracted one measurement because a fixture's insertion order made RID order and sorted order disagree, which makes that argument the thing a later reader most needs and the thing currently filed under the wrong method.

**Suggestion**: Move each block above the method it describes. Nothing else changes.

### TS19 [should-fix] The transparency change un-covers two existing recogniser cases and leaves the class with no accepting one

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WhereTraversalStepRecogniserTest.java`, methods `whereTraversalStep_edgeBearingChild_declines` (line 116) and `whereTraversalStep_declinedChild_declines` (line 131)

**Issue**: A `WhereTraversalStep` always carries a `WhereStartStep` in its child. The constructor rejects a child with no scope key, and `configureStartAndEndSteps` either replaces a variable start step with a `WhereStartStep` or inserts one before the start when only an end label is present. I read this from the fork's bytecode (`javap -c` on `WhereTraversalStep` in `io.youtrackdb:gremlin-core:3.8.1-67860f6-SNAPSHOT`), so it holds for the version this build resolves.

After this step, `WhereStartStep` is neither transparent nor registered — `TRANSPARENT_STEPS` is `Set.of(NoOpBarrierStep.class)` (GremlinStepWalker line 156) and `PRODUCTION_RECOGNISERS` (lines 190-219, read in full) has no entry for it. `GremlinStepWalker.subWalk` (line 581) strips only leading `GraphStep`s, so the child cursor meets the `WhereStartStep` first and `dispatchAll` fails there. `WhereTraversalStepRecogniser` can therefore only decline.

Two consequences for this class:

`whereTraversalStep_edgeBearingChild_declines`' javadoc says the path-scoped spelling "reaches the same edge-bearing gate as the `TraversalFilterStep` spelling above" and that it is "pinned separately because the two spellings enter through different recogniser classes". That is no longer true — the child sub-walk declines before `commitPositiveFilterChild` reads `hasEdges`, so this entry point no longer reaches the gate. Its remaining assertions (no anon alias, no edges, empty alias filters) still test that a declining filter leaves the outer context clean, which is worth keeping. The javadoc is what is stale, and the diff updated its neighbour at line 88 without touching it.

`whereTraversalStep_declinedChild_declines` names the child's `count()` as the reason. The `WhereStartStep` is the child's first step, so dispatch fails before `CountGlobalStep` is reached.

The class now has five `whereTraversalStep_*` cases and every one asserts `DECLINE`, because the diff converted the only accepting case (line 98) into a decline. A test group whose cases have collapsed into one behaviour, with javadocs still drawing distinctions between them, is the shape that hides the collapse. `whereTraversalStep_nullBoundary_declines` (line 143) is the exception worth noting — the recogniser's `boundary == null` check runs before `walkChild`, so that case still discriminates its own mechanism.

The class-level `TRANSPARENT` constant (lines 36-38) still lists `WhereStartStep` and `WhereEndStep`. It is used only for positioning a cursor in the parent traversal, where those classes never appear, so no assertion depends on it — but it is now a copy of the production set that production no longer has, sitting in the one file a reader will consult about the change.

**Suggestion**: Update the two javadocs to name the sub-walk decline, and note in each that the mechanism they used to pin is now unreachable from this entry point. Drop `WhereStartStep` and `WhereEndStep` from the class's `TRANSPARENT` constant so it mirrors production again. Whether a recogniser that can no longer accept should stay registered is a production question — worth routing to the bugs reviewer rather than resolving here.

### TS20 [should-fix] `orderThenLimitThenValues_stillTranslates` credits a carve-out its step order cannot exercise

**File**: `OrderRangeStepRecogniserTest.java`, method `orderThenLimitThenValues_stillTranslates` (line 569)

**Issue**: The javadoc says the shape keeps translating because "a preceding `order().by(k)` has already contributed `k IS DEFINED` into the pattern … so the shaping drop is a no-op". The walk does not consult that.

`RangeGlobalStepRecogniser` declines on `ctx.dropsRowsOnAbsentProperty()`, which reads `shaping.dropOnAbsent()` (WalkerContext line 623). In `g.V().order().by(name).limit(2).values(name)` the steps are dispatched left to right, so the range step is recognised before `values(name)` sets any shaping. The flag is false at that point, and the case translates whether or not `order().by(k)` wrote anything. The premise is true — `OrderGlobalStepRecogniser` line 71 calls `ByModulatorPresence.requireModulatedProperty` — but it is not what the case demonstrates.

In the other ordering the exemption does not hold either. `g.V().order().by(k).values(k).limit(n)` sets shaping first, and `GremlinProjectionAssembler.configureSingleKeyValues` pins `dropOnAbsent` unconditionally (line 108, no read of the existing alias filters), so the slice declines despite the `IS DEFINED` conjunct. The carve-out as stated in `RangeGlobalStepRecogniser`'s javadoc — "which is why `g.V().order().by(k).limit(n).values(k)` stays translatable" — has no reachable witness in either ordering.

The fixture's name-less vertex has a second, quieter dependency. Its discriminating power runs entirely through native `order().by("name")`'s handling of an absent property: if native drops that vertex, both arms agree for a reason unrelated to the conjunct. The test does not pin which behaviour it is relying on, and this project already tracks null-ordering as a known divergence area between the two pipelines.

**Suggestion**: Rewrite the javadoc to say what the case shows — that `values(k)` is allow-listed after a captured slice, and that a slice recognised before any projection never sees shaping. If the `IS DEFINED` exemption is meant to be real, it needs a production change (have `configureSingleKeyValues` skip `dropOnAbsent` when a presence conjunct already covers the key) plus a case in the `values`-then-slice order; if it is not, the sentence should come out of `RangeGlobalStepRecogniser`'s javadoc too. Either way, add one assertion pinning what native does with the name-less vertex, so the case does not rest on unstated ordering behaviour.

### TS21 [should-fix] Four more copies of the house equivalence harness, all missing its off-arm pin

**File**: `OrderRangeStepRecogniserTest.java`, methods `assertClauseThenStepDeclines` (line 713), `assertTranslatesAndMatchesNative` (line 752), `assertTranslatesAndMatchesNativeValues` (line 782); `GremlinStepWalkerTest.java`, method `assertDeclinesAndMatchesNative` (line 938)

**Issue**: `PredicateTraversalEquivalenceTest.assertEquivalent` (line 1124) is the established form of this harness: a `Recognition` enum selecting the boundary expectation, a non-empty guard on the recognised arm, a multiset comparison, and — the part all four new copies drop — `assertThat(boundaryOff).as(scenario + " (translator off) must never engage a boundary step").isEqualTo(0)`. `EdgeTraversalEquivalenceTest` (line 1054), `ProjectionEquivalenceTest` (line 1436), and `UnionTraversalEquivalenceTest` (line 723) carry the same three-part shape.

That dropped assertion is the only thing pinning that the kill-switch flip took effect. `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to true (GlobalConfiguration line 1019). If `setTranslatorEnabled(false)` ever wrote to a configuration handle the traversal does not read, every new case here would still pass: the decline cases assert `boundaryOn == 0`, which a stuck-on flag satisfies whenever the walk genuinely declines, and the translate cases would compare a translated arm against a translated arm. The two new helpers that build the off arm through `drain(shape)` (line 810) discard the step list, so the count is not merely unasserted — it is not even available.

`test-structure-iter1.md` TS8 already recorded five hand-rolled copies of this harness in this package. This step adds a sixth and seventh class, and repeats the same regression the earlier copy made.

**Suggestion**: In each of the four helpers, build the off arm through `asAdmin().applyStrategies()` rather than `drain`, and add the missing pin:

```java
assertThat(countBoundarySteps(offAdmin.getSteps()))
    .as(scenario + " (translator off) must never engage a boundary step")
    .isEqualTo(0);
```

The larger move is the one TS8 proposed and TS22 repeats: call the existing `assertEquivalent` instead of forking it again.

### TS22 [suggestion] Ten end-to-end cases in a recogniser unit-test class, and helpers splitting a group in two

**File**: `OrderRangeStepRecogniserTest.java`, lines 475-847; `GremlinStepWalkerTest.java`, lines 919-977

**Issue**: `OrderRangeStepRecogniserTest` gains ten executed translator-on/off cases, three fixtures, and seven helpers — roughly 375 lines — in a class whose other 470 lines drive recognisers against a hand-built cursor. The class javadoc explains the mix, and the reason it gives is sound: the rule under test lives in `GremlinStepWalker`'s dispatch loop, so a cursor-level assertion cannot see it. That reason also argues against this class, since the gate belongs to neither `OrderGlobalStepRecogniser` nor `RangeGlobalStepRecogniser`. The package already has four classes whose whole purpose is measured translator-on/off equivalence, and `WhereTraversalStepRecogniserTest`'s own javadoc (line 28) points readers at two of them for exactly this kind of case.

In `GremlinStepWalkerTest` the new helpers sit between test methods rather than at the end. `seedOneKnowsEdgeNoSelfLoop`, `assertDeclinesAndMatchesNative`, and `setTranslatorFlag` (lines 919-977) separate the where group from seven more slice-gate cases at lines 984-1076, so the banner comment "where(...) scope bindings are no longer transparent" (line 822) appears to own `walk_valuesThenSlice_declines` and everything after it. The two end-to-end cases also break the file's `walk_*` naming convention without marking themselves as a different kind of test.

**Suggestion**: Move the ten end-to-end cases and their fixtures into an equivalence class — a new `CardinalityClauseEquivalenceTest` beside the existing four, or into `ProjectionEquivalenceTest` for the `values`-drop pair — and call the harness that already lives there. In `GremlinStepWalkerTest`, move the three helpers below the last test method and add a banner over the resumed slice group.

## Evidence base
