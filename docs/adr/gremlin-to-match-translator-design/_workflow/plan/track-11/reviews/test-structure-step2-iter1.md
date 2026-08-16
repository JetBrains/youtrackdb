<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 1, suggestion: 4}
index:
  - {id: TS1, sev: should-fix,  loc: SubTraversalPredicateAdapterTest.java:491, anchor: "### TS1 ", cert: n/a, basis: "step 1's layer-3 divider says \"Neither test\"; the step added a third test under it, so the navigation aid now miscounts the block it labels"}
  - {id: TS2, sev: suggestion,  loc: GremlinStepWalkerTest.java:675,            anchor: "### TS2 ", cert: n/a, basis: "the new section divider frames all four tests as loop-level and fixture-driven; the last two call a static predicate with a synthetic set and never build a walker"}
  - {id: TS3, sev: suggestion,  loc: GremlinStepWalkerTest.java:737,            anchor: "### TS3 ", cert: n/a, basis: "\"the production allow-list is empty\" is asserted in prose only; the field is private, so nothing in the suite notices when a later step populates it"}
  - {id: TS4, sev: suggestion,  loc: GremlinStepWalkerTest.java:745,            anchor: "### TS4 ", cert: n/a, basis: "two textually identical lambdas stand in for the two recogniser kinds, repeating the opacity step 1's TS3 fixed one file over with taggedOp"}
  - {id: TS5, sev: suggestion,  loc: WalkerContextResultShapingTest.java:15,    anchor: "### TS5 ", cert: n/a, basis: "class javadoc still names one list-shaping query; the step adds a test for a second one the walker's loop reads"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] The layer-3 section divider still says "Neither test" after this step added a third one

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/SubTraversalPredicateAdapterTest.java`, section divider at lines 488-492; new test `carriesListShapingOp_falseOnSubWalk_evenWhenTheParentCarriesOne` (line 526)

**Issue**: Step 1's review (TS1) added the layer-3 divider and its closing sentence: "Neither test drives a sub-walk, so no registry is needed." Step 2 dropped a third `@Test` into that block and updated the class javadoc's third `<li>` (lines 42-48) to name `carriesListShapingOp()`, but left the divider untouched. Three tests now sit under a divider that counts two. The substance still holds — all three pass `Map.of()` as the registry and none calls `walkChild` — so what breaks is only the reader's trust in the aid: whoever lands on a failure in this block has to count the methods to find out whether the divider describes them, which is exactly the work the divider was added to save. This is the drift the focal points predicted for a second step editing the same file, and the divider is the one navigation aid step 1's review created.

The same javadoc edit left line 45 at 109 characters, where every other prose line in the file wraps at 103 or below. The clause "used as a" was appended to the existing line instead of the paragraph being re-flowed, so the edit reads as unfinished.

**Suggestion**: Make the divider count-free — "No test in this block drives a sub-walk, so no registry is needed" — so a fourth test does not falsify it again. Re-wrap lines 42-48 while the block is open.

### TS2 [suggestion] The new section divider describes only half of the block it labels

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, section divider at lines 675-680

**Issue**: The divider reads "the loop refuses a step dispatched behind a captured stream stage. Driven with fixture recognisers because the four terminators that append a stage do not exist yet". Four tests sit under it, and only the first two match that description: `walk_listShapingOpOnTheLastStep_translatesAndCarriesTheOp` (line 690) and `walk_stepBehindACapturedListShapingOp_declinesTheWholeWalk` (line 712) build a `GremlinStepWalker` over a fixture registry and run the dispatch loop. The last two — `mayFollowListShaping_admitsOnlyTheAllowListedShapers` (line 742) and `mayFollowListShaping_refusesAnAllowListedShaperBehindADrain` (line 766) — call the package-private static predicate with a synthetic `Set` and never construct a walker, a traversal, or a context. Their `(cursor, ctx) -> Outcome.ACCEPTED` bodies are never invoked, so no fixture recogniser drives anything.

The two halves also fail for different reasons, which is what makes the distinction worth marking: the loop tests break if the gate's call site in `dispatchAll` moves or its polarity flips, the predicate tests break only if `mayFollowListShaping`'s boolean algebra changes. The class javadoc's new `<li>` (lines 75-79) does draw the line ("the two-input may-follow rule is asserted directly"), so the file's top-level aid is accurate; the local divider — the one a reader hits first when landing on a failing method — is not.

**Suggestion**: Split the divider in two, one over the loop-level pair and one over the rule-level pair, or add a sentence to the existing one: the last two tests assert the rule the loop applies, called directly, because no production traversal shape reaches its admit branch yet.

### TS3 [suggestion] The synthetic allow-list stands in for a production field the suite cannot see

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, methods `mayFollowListShaping_admitsOnlyTheAllowListedShapers` (javadoc line 737) and `mayFollowListShaping_refusesAnAllowListedShaperBehindADrain` (line 766)

**Issue**: Both tests document what they stand in for — "asserted over a synthetic allow-list because the production one is empty until the per-payload shapers land" — and the class javadoc repeats the premise at line 78 ("no production traversal shape reaches its admit branch yet"). Nothing checks it. `POST_LIST_SHAPING_RECOGNISERS` is `private static final` (`GremlinStepWalker.java:659`), so the test class cannot read it at all, which is also why both tests take the set as a parameter.

Contrast the sibling gate in this same class. `POST_UNION_RECOGNISERS` is package-private `static final` (`GremlinStepWalker.java:267`), and two tests read it directly: `everyPostUnionRecogniserStatesItsOwnPositionalAnswer` (line 1469) iterates the production membership and fails the build on an omission, and `recogniserOutsideThePostUnionAllowList_inheritsANonPositionalAnswer` (line 1494) opens with an explicit `as("fixture premise: …")` assertion over the field. The file's own convention is to assert premises rather than narrate them.

The consequence is a silent staleness. When a later step adds `UnfoldStepRecogniser` / `ReverseStepRecogniser` to the production set, no test in this suite changes colour, both test javadocs and the class javadoc become false, and a reader who trusts them concludes the admit branch is still unreachable while production shapes reach it. (Symbol enumeration here is grep over `core/src` for the field names rather than PSI find-usages — `steroid_execute_code` times out in this repo. The names are unique and the field is private, so a textual sweep is reference-accurate for this question, but the caveat stands.)

**Suggestion**: Widen the field to package-private and open one of the two tests with the file's own premise idiom — `assertThat(GremlinStepWalker.POST_LIST_SHAPING_RECOGNISERS).as("premise: no production recogniser is admitted yet, so these two tests stand in for the real membership").isEmpty()`. That turns the staleness into a build failure at the moment the shapers land, which is the moment the tests want to be revisited. If widening the field is unwanted, at least keep the premise in one javadoc and `{@link}` to it from the other two sites, so a single edit retires all three.

### TS4 [suggestion] Two identical lambdas stand in for the two recogniser kinds the rule distinguishes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, methods `mayFollowListShaping_admitsOnlyTheAllowListedShapers` (lines 745-747) and `mayFollowListShaping_refusesAnAllowListedShaperBehindADrain` (lines 767-768)

**Issue**: `perPayloadShaper` and `clauseWriter` are the same expression, `(cursor, ctx) -> Outcome.ACCEPTED`; only their variable names say which is which, and only instance identity makes the assertions discriminate. This is the finding step 1's review raised as TS3 against `WalkerContextResultShapingTest`, and step 1's accepted fix was the `taggedOp(String tag)` helper in that file (lines 179-191) whose `toString()` names the op. The pattern comes back here one file over.

Two costs follow. A failure in either test reports the same synthetic lambda class for both arms, so the message cannot say which recogniser was admitted. And the stand-in declarations are duplicated across the two test methods, so the shape has to be edited twice — the two tests already share `mayFollow` construction verbatim.

**Suggestion**: Add a `private static StepRecogniser taggedRecogniser(String tag)` beside `startThenAppendsListShapingOp()` (line 785), returning an anonymous `StepRecogniser` whose `toString()` is the tag, and build both tests' stand-ins from it. The identity then shows up in the source and in any `AssertionError`, and the inline comment at lines 743-744 no longer has to argue in prose that the rule reads identity rather than outcome.

### TS5 [suggestion] The class javadoc names one list-shaping query; the file now pins two

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContextResultShapingTest.java`, class javadoc line 15; new test `carriesListShapingOp_tracksTheOpsOnTheShaping` (line 67)

**Issue**: The class javadoc enumerates what the file covers and names a single list-shaping query: "answers the list-shaping query a terminator reads before it contributes" — that is `supportsListShaping()`, pinned by `supportsListShaping_isTrueOnATopLevelWalk`. The step adds a test for a second query with a different reader and a different contract: `carriesListShapingOp()` is read by the walker's dispatch loop once per iteration as the last-step gate, not by a terminator deciding whether to contribute. The javadoc was left as it was, so the class's stated scope excludes its newest test. Both other touched test classes had their class javadoc extended in this step, which makes this one the odd file out rather than a consistent omission.

**Suggestion**: Extend line 15 to cover both — "answers both list-shaping queries: the one a terminator reads before it contributes and the one the walker's dispatch loop reads as its last-step gate" — matching the third `<li>` this step added to `SubTraversalPredicateAdapterTest`.

## Evidence base
