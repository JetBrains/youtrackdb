<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 1, suggestion: 4}
index:
  - {id: TS1, sev: should-fix, loc: GremlinStepWalkerTest.java:1680,        anchor: "### TS1 ", cert: n/a, basis: "the test javadoc makes \"read off a step the DSL built\" load-bearing, but the three steps are taken by bare index with no type check, and the recognisers ignore their argument, so a wrong index cannot fail"}
  - {id: TS2, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:643, anchor: "### TS2 ", cert: n/a, basis: "the only case-table loop among this class's tests; two of its three assertion messages omit the case label, so a failure does not say whether unfold or reverse broke"}
  - {id: TS3, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:703, anchor: "### TS3 ", cert: n/a, basis: "reads as the arm gate's regression net, but both cases are over-determined by the pre-existing contract comparison and stay green with the gate deleted"}
  - {id: TS4, sev: suggestion, loc: GremlinStepWalkerTest.java:1710,        anchor: "### TS4 ", cert: n/a, basis: "reflective absence-of-override tripwire duplicates the method-name literal and the reflection snippet from its sibling, and its failure message states the rationale without the action"}
  - {id: TS5, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:679, anchor: "### TS5 ", cert: n/a, basis: "the eight-vertex width rationale reads as though it governs the four assertions in the method, which are declines where both sides run the same native pipeline"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] The positional-answer test states a premise about its steps that nothing checks

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, method `listShapingMembersOfThePostUnionAllowList_answerThePositionalQuestion` (line 1679), step extraction at lines 1680-1682

**Issue**: The javadoc's second paragraph makes a claim the test depends on: "Each answer is read off a step the DSL built, so a recogniser that inspected the step and answered conditionally would be exercised rather than bypassed." The three steps are taken by bare index — `getSteps().get(1)`, `get(2)`, `get(1)` — and none is checked for its type. All three recognisers currently ignore the `step` argument and return a constant, so a wrong index is invisible: the assertions pass on whatever object arrives.

Two things make that worth closing rather than noting. The indices are read against a custom TinkerPop fork whose step list already carries placeholder variants — this same file registers `VertexStepPlaceholder` and `RangeGlobalStepPlaceholder` beside their plain forms at lines 1868-1874 — so the mapping from a DSL call to a step position is not a fixed property of upstream TinkerPop. And the moment the premise starts to matter is the moment it silently stops holding: whoever makes `TailGlobalStepRecogniser.selectsPositionally` conditional on the window (the recogniser's own javadoc at `TailGlobalStepRecogniser.java:127-134` records `tail(0)` as the case deliberately not special-cased) inherits a test that hands the recogniser an unchecked object and still passes.

The class asserts premises elsewhere rather than narrating them. `cursorAtUnion` (line 1882) asserts it reached a `UnionStep`, `singlePlanTemplate` (lines 1804-1805) asserts both of its fixture premises, the new white-box test opens with one (lines 1752-1755), and the sibling `recogniserOutsideThePostUnionAllowList_inheritsANonPositionalAnswer` (line 1728) asserts its allow-list premise. This test is the one place in the step where a stated premise is left unchecked, and step 2's review raised the same narrated-versus-asserted gap as TS3.

**Suggestion**: Add one `isInstanceOf` per extracted step, keyed on what the recogniser itself dispatches on: `UnfoldStep`, `ReverseStep`, and `TailGlobalStepContract` (the interface `TailGlobalStepRecogniser` imports, which covers the placeholder spelling too). Three lines, and the javadoc's claim becomes a checked fact.

### TS2 [suggestion] The unfold/reverse case-table loop hides which case failed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `postUnionUnfoldAndReverse_translateAndCarryTheirStage` (line 640), loop at lines 643-665

**Issue**: The two shapes are driven from a `List.of(Map.entry(label, supplier))` table. Two of the three walk-level assertions inside the loop carry a message with no label: "a union translates to a multi-plan carrier, not a single plan" (line 655) and "the suffix stage must reach the multi-plan result, or the boundary applies nothing" (line 658). Those are the two assertions most likely to catch a real regression in this step — the second is the one the javadoc calls out as what makes the case discriminating — and neither failure message says whether `unfold` or `reverse` produced it. The first assertion and the `assertEquivalent` scenario string do interpolate `each.getKey()`, so the labelling is half-applied rather than absent by design.

The loop also serialises the two cases: a failure on `unfold` stops the method before `reverse` runs, so one regression reports one case and hides whether the other holds. Every other multi-shape test in this class writes its cases as straight-line calls — `secondPostUnionDedupAndDedupAfterCount_decline` (line 603), `postUnionFoldAndTail_decline` (line 682) with four — which keeps each case's failure message self-describing. This is the only case-table loop among the class's test methods.

**Suggestion**: Extract the body as `private void assertPostUnionStageSurvives(String label, Supplier<GraphTraversal<?, ?>> shape)` and call it twice from two named tests (`postUnionUnfold_translatesAndCarriesItsStage`, `postUnionReverse_…`), which gives per-case failure isolation and lets the helper interpolate `label` into all three messages. Failing that, add `each.getKey()` to the two bare messages and keep the loop.

### TS3 [suggestion] The arm-gate equivalence test reads as the gate's regression net, and is not one

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionArmCarryingAListShapingStage_declines` (line 713), javadoc at lines 703-712

**Issue**: The javadoc opens with the rule this step added — "A list-shaping stage inside an *arm* declines the union" — and then explains the gate's blanket reach over the `unfold` pair. A maintainer reading it concludes the test guards the new gate at `UnionStepRecogniser.java:130`. Both cases would stay green with that gate deleted. Each arm's recogniser appends a fresh op instance per recognition (the deliberate choice recorded at `FoldStepRecogniser.java:97` and its two siblings), so the two arms' `ResultShaping` values compare unequal and the pre-existing projection-contract comparison declines the shape anyway. The production javadoc added in this same step states exactly that over-determination at `UnionStepRecogniser.java:55-62`, and the white-box witness `union_childCarryingAListShapingStage_declines_evenWhenTheArmsAgreeOnIt` (`GremlinStepWalkerTest.java:1749`) opens by explaining that this is why it has to be white-box.

The test earns its place — it pins the observable end-to-end behaviour for two real spellings, including the collateral `unfold` pair no other case covers — so the gap is in what the javadoc lets a reader assume, not in the test. Without the cross-reference, someone triaging a future change to the arm gate reaches for this test first and reads its green as coverage.

**Suggestion**: Add a closing sentence pointing at the witness: both spellings here are over-determined, because two arms appending fresh op instances already disagree at the projection-contract comparison, and `GremlinStepWalkerTest.union_childCarryingAListShapingStage_declines_evenWhenTheArmsAgreeOnIt` is what pins the gate itself.

### TS4 [suggestion] The fold-absence tripwire duplicates its sibling's reflection and does not say what to do when it fires

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, method `foldIsNotOnThePostUnionAllowList_andStatesNoPositionalAnswer` (line 1705), second assertion at lines 1710-1715

**Issue**: The snippet `Arrays.stream(...getDeclaredMethods()).anyMatch(m -> m.getName().equals("selectsPositionally"))` now appears twice, here and in `everyPostUnionRecogniserStatesItsOwnPositionalAnswer` (lines 1655-1657), with the method name as a string literal in both. The two copies fail in opposite directions on a rename of `StepRecogniser.selectsPositionally`: the sibling goes red (which is the right outcome and does keep the rename from passing unnoticed), this one goes vacuously green. A shared helper removes the asymmetry and puts the literal in one place.

The message is the second half. This assertion is a deliberate tripwire on the *absence* of an override, and the production javadoc it encodes (`FoldStepRecogniser`'s "Why there is no `selectsPositionally` override here" section, and the exclusion paragraph on `GremlinStepWalker.POST_UNION_RECOGNISERS`) says plainly that membership-plus-`true` is behaviourally indistinguishable from absence. So the developer who trips this test may well be making a change the design documents as legitimate, and the message they get — "off the allow-list, the question is never asked, so the interface default stands" — states the rationale without stating what the test wants from them. Compare the sibling's message, which names the required action ("must override `selectsPositionally` rather than inherit the `StepRecogniser` default").

**Suggestion**: Extract `private static boolean declaresOwnPositionalAnswer(StepRecogniser r)` and call it from both tests. Extend the message to name the action: adding an override here is a design change that also needs `FoldStepRecogniser` on `POST_UNION_RECOGNISERS` and this test retired, so the reason recorded in the allow-list javadoc gets revisited rather than bypassed.

### TS5 [suggestion] The eight-vertex width rationale describes a regression the method's assertions cannot see

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `postUnionFoldAndTail_decline` (line 682), javadoc paragraph at lines 679-681

**Issue**: The closing paragraph reads "The chain is eight vertices deep so that both arms are wide: over a three-vertex chain a `tail(3)` would keep everything and the shape would agree with native by accident." All four cases in the method expect `DECLINED`, and on that branch `assertEquivalent` runs the same native pipeline on both sides, so the multiset equality holds whatever the seed contains — the helper's own anti-vacuity comment (lines 960-967) says so. Seed width changes nothing about today's assertions; it matters only if the gate regresses and the shape starts translating, at which point the wide arms are what make the divergence visible. The sentence is right about the hypothetical and reads as though it governs the run.

`seedLongKnowsChain`'s own javadoc (lines 791-798) already frames width in terms of what a positional suffix would select, so the reader who follows the helper gets the fuller story; the reader who stops at the test method does not.

**Suggestion**: Name the hypothetical — the chain is eight deep so that a regression which re-admitted these shapes would diverge visibly rather than agree by accident; on the decline path itself both sides run the native pipeline and the width is inert.

## Evidence base
