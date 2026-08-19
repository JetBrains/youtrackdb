<!-- MANIFEST
findings: 14   severity: {blocker: 0, should-fix: 5, suggestion: 9}
index:
  - {id: CQ1, sev: should-fix, loc: GremlinPredicateAdapter.java:557-576, anchor: "### CQ1 ", cert: n/a, basis: "toMatchedLabelFilter's Javadoc orphaned by an interface inserted between it and the method, and its text made stale by the same change"}
  - {id: CQ2, sev: should-fix, loc: SQLFunctionMean.java:74-77, anchor: "### CQ2 ", cert: n/a, basis: "comment credits avg with a reset avg does not perform, contradicting the class's own test"}
  - {id: CQ3, sev: should-fix, loc: docs/yql/YQL-Functions.md, anchor: "### CQ3 ", cert: n/a, basis: "new user-visible mean() SQL function absent from the YQL function reference and its index table"}
  - {id: CQ4, sev: should-fix, loc: core/src/test/.../translator/strategy/*.java, anchor: "### CQ4 ", cert: n/a, basis: "translator-equivalence scaffolding copied 5-11 times; this track added three more copies while extracting ModernGraphFixture next door"}
  - {id: CQ5, sev: should-fix, loc: ShapeClassifier.java:985, anchor: "### CQ5 ", cert: n/a, basis: "comment claims variance/stddev have no running-scalar formulation; both are standard streaming aggregates"}
  - {id: CQ6, sev: suggestion, loc: ByModulatorTranslatorTest.java:11359, anchor: "### CQ6 ", cert: n/a, basis: "step located by getSimpleName().contains(\"Group\") where sibling helpers match on class"}
  - {id: CQ7, sev: suggestion, loc: RangeGlobalStepRecogniser.java + StepRecogniser.java + GremlinStepWalker.java, anchor: "### CQ7 ", cert: n/a, basis: "the post-union interleaving argument is restated near-verbatim in three production Javadocs that must now move together"}
  - {id: CQ8, sev: suggestion, loc: ConnectiveStepSupport.java:68-81, anchor: "### CQ8 ", cert: n/a, basis: "new boundary parameter is read only by an assert and is derivable from the ctx parameter beside it"}
  - {id: CQ9, sev: suggestion, loc: GremlinPredicateAdapter.java:339-342, anchor: "### CQ9 ", cert: n/a, basis: "isOrderComparison(compare) && rangeTypeGuard evaluated twice across two adjacent statements"}
  - {id: CQ10, sev: suggestion, loc: GremlinPredicateAdapter.java:392 and 7 test sites, anchor: "### CQ10 ", cert: n/a, basis: "inline fully-qualified names in new code, including a method return type whose sibling classes import it"}
  - {id: CQ11, sev: suggestion, loc: GremlinPredicateAdapter.java:370, GremlinStepWalker.java:569, WalkerContext.java:647-648, anchor: "### CQ11 ", cert: n/a, basis: "constants declared between methods, and a signature wrapped between return type and method name"}
  - {id: CQ12, sev: suggestion, loc: core/src/main/.../translator/strategy/*.java, anchor: "### CQ12 ", cert: n/a, basis: "113 added production lines exceed the documented 100-column width; Spotless does not reflow comments so the build stays green"}
  - {id: CQ13, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:728, anchor: "### CQ13 ", cert: n/a, basis: "a @Test method sits between two private fixture helpers, away from every other test case in the class"}
  - {id: CQ14, sev: suggestion, loc: EdgeTraversalEquivalenceTest.java:1487 + PredicateTraversalEquivalenceTest.java:1372 + RangeTypeGuardEquivalenceTest.java:9487, anchor: "### CQ14 ", cert: n/a, basis: "plan-root assertions parse ExecutionPlan.prettyPrint debug text for a '+ SET' line, in three copies"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [should-fix] `toMatchedLabelFilter`'s Javadoc is orphaned and stale

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 557-576)

**Issue**: Step 10 inserted the new `LabelResolver` interface, with its own Javadoc, between
`toMatchedLabelFilter`'s Javadoc and the method it documented. Java attaches the *last* preceding doc
comment, so the block at 557-561 now documents nothing and `toMatchedLabelFilter` at 576 carries no
Javadoc at all. The orphan is also wrong on the same point the change was about: it says the method
"compares `$matched.<label>` accessors", while the new interface's Javadoc directly below explains
that `$matched` is keyed on pattern aliases and never on Gremlin labels. Two comments a dozen lines
apart contradict each other, and neither is attached to the method whose behaviour changed. The new
`labelResolver` parameter has no `@param` anywhere.

**Suggestion**: Move `LabelResolver` up beside `PropertyTypeGate` (declared at line 126), where the
class already groups its nested types, then restore the method's Javadoc directly above line 576 —
rewritten to say the accessors read the *resolved alias*, with an `@param labelResolver` covering the
decline-on-unresolved contract.

### CQ2 [should-fix] `SQLFunctionMean` credits `avg` with a reset `avg` does not perform

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMean.java` (lines 74-77)

**Issue**: The comment reads "the running total restarts on every call the same way avg's does", and
the code below it clears both `sum` and `total`. `SQLFunctionAverage` clears only `sum`
(`SQLFunctionAverage.java:63`) and lets `total` accumulate across rows. So the multi-argument forms of
the two functions behave differently, and the comment asserts they behave the same. The class's own
test says the opposite in its name and body —
`SQLFunctionMeanTest.multiArgumentFormIsRowWiseAndResetsBothSumAndCount` records the divergence as
deliberate and explains that `avg` "pollutes every row after the first". The production comment and
the test that covers it disagree.

**Suggestion**: Restate the comment to say what the code does and why it differs: the multi-argument
form is row-wise, so both accumulators reset, unlike `avg`, which resets only the sum. That keeps the
divergence visible to whoever next reads either function instead of hiding it behind a claim of
parity.

### CQ3 [should-fix] The new `mean()` SQL function is undocumented

**File**: `docs/yql/YQL-Functions.md`

**Issue**: `SQLFunctionMean` adds a user-visible SQL function name and registers it in
`DefaultSQLFunctionFactory`, so `SELECT mean(age) FROM …` is now valid hand-written YQL. The YQL
function reference documents every sibling — `avg()` at line 407, plus `median()`, `variance()` and
`stddev()` — and carries an index table of links at lines 13-16. Neither the table nor the body
mentions `mean`. CLAUDE.md § Documentation Sync requires the docs to move with a feature that affects
the public surface, and this one is exactly the case a reader would look up, since `mean` exists only
because `avg` divides integers in integer arithmetic.

**Suggestion**: Add a `### mean()` section next to `### avg()` stating the floating-point division and
the `avg` contrast (the class Javadoc already has the wording), and add the index-table entry beside
`avg()`.

### CQ4 [should-fix] The translator-equivalence test scaffolding is copied five to eleven times

**Files**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/` and
`embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedTranslatorKillSwitchWitnessTest.java`

**Issue**: Every equivalence suite in the package carries its own copy of the same handful of helpers.
Measured across the tree as it stands after this track:

| Helper | Copies |
|---|---|
| `setTranslatorEnabled(boolean)` | 8 |
| `countBoundarySteps(...)` | 10 in `core`, plus 1 in `embedded` |
| `sortedIds` / `drainSortedIds` | 6 |
| `private enum Recognition` | 5 |
| `assertEquivalent(scenario, expected, supplier)` | 5, near-identical bodies |
| `withTranslator` / `withTranslatorRestored` | 4 |
| `translatorEnabled()` | 6 |
| `planRootAlias` + `boundaryPlanText` | 3 |
| the literal `"$g2m_v0"` | 18 files, under two constant names (`BOUNDARY_ALIAS`, `ORIGIN_ALIAS`) |

Most of that predates the track, but the track added three fresh copies —
`RangeTypeGuardEquivalenceTest`, `RepeatDeclineStrategyTest` and the new `embedded` witness — and it
did so in the same commits that extracted `ModernGraphFixture` as a shared package-private seeder.
The extraction pattern is established in the package; this scaffolding did not get it. The cost is
already visible in the diff: `countBoundarySteps` had to be changed from `YTDBMatchPlanStep` to
`AbstractMatchPlanStep` in six separate files, each with its own near-identical Javadoc explaining the
supertype choice, and the five `assertEquivalent` copies have drifted apart (only two of them pin a
non-empty native result on the DECLINED branch).

**Suggestion**: Add a package-private `TranslatorEquivalence` beside `ModernGraphFixture` holding the
flag read/write pair, `withTranslator`, `countBoundarySteps`, `sortedIds`/`sortedStrings`, the
`Recognition` enum and the two alias constants, and have the suites call into it. The per-suite
`assertEquivalent` variants can then reduce to the assertions that genuinely differ (multi-plan
counting in the union suite, tag comparison in the range-guard suite, ordered comparison in the
projection suite). The alias literals in particular should be one constant, since a change to the
walker's minting format currently means editing eighteen files.

### CQ5 [should-fix] `ShapeClassifier`'s new comment overstates why the five aggregates cannot replay

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (line 985)

**Issue**: The comment justifying the `K0_NONE` mapping says "the other five have no running-scalar
formulation at all". `variance` and `stddev` are among those five, and both have textbook streaming
formulations (Welford's algorithm, or running sum and sum-of-squares) — that is how most engines
compute them. The claim that no formulation exists is a statement a future implementer would act on by
not looking, when the real fact is narrower and equally sufficient: no `AggregateState` arm exists for
them today.

**Suggestion**: Replace the universal claim with the local one — these names have no
`AggregateState` replay arm, so they classify `K0_NONE` and re-execute; `median`, `mode` and
`percentile` need the full multiset, while `variance` and `stddev` could be replayed if an arm were
added. That leaves the code's behaviour documented and the door open.

### CQ6 [suggestion] A test locates a step by class-name substring

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/ByModulatorTranslatorTest.java` (the `postStrategyModulator` helper)

**Issue**: The helper finds the group step with
`step.getClass().getSimpleName().contains("Group")`. That matches `GroupStep`, `GroupSideEffectStep`,
`GroupCountStep` and any future class whose name happens to contain the word, and it silently picks
whichever comes first. Every sibling helper added by this track matches on class instead —
`stepOf(admin, Class)` in `OrderRangeStepRecogniserTest`, `YTDBGraphStep.class::isInstance` in
`RangeTypeGuardEquivalenceTest`, `AndStep.class::isInstance` in `AndStepRecogniserTest` — so this is
the one place the pattern breaks.

**Suggestion**: Match on the concrete step classes the helper means (`GroupStep`,
`GroupSideEffectStep`), or filter on `TraversalParent` and assert the local-child count, so a
TinkerPop rename fails the build rather than selecting a different step.

### CQ7 [suggestion] The post-union interleaving argument is restated in three production Javadocs

**Files**:
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/RangeGlobalStepRecogniser.java`
(class Javadoc, "Why a post-union slice needs a following `count()`"),
`.../StepRecogniser.java` (`selectsPositionally`),
`.../GremlinStepWalker.java` (`POST_UNION_RECOGNISERS`)

**Issue**: The same two-sentence argument — `MultiPlanMatchStep` emits child one's rows then child
two's, native `union(...)` interleaves the arms as it pulls each traverser, so a positional selection
returns a different multiset — appears near-verbatim in all three. `PostConcatOp.Range` handles the
same need correctly, stating the conclusion and pointing at the recogniser for the argument
("The recogniser's class Javadoc carries the argument"). CLAUDE.md's rule that comments stay in sync
with the code they describe makes each extra copy a place the next change has to remember.

**Suggestion**: Keep the full argument in `RangeGlobalStepRecogniser`'s class Javadoc, which already
carries the measured figures, and reduce the other two to the conclusion plus a `{@link}` — the shape
`PostConcatOp.Range` already uses.

### CQ8 [suggestion] `commitPureFilterChild`'s new parameter exists only for an assertion

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ConnectiveStepSupport.java` (lines 68-81)

**Issue**: The `boundary` parameter is read by nothing but the `assert` at line 74, and both
production callers pass `ctx.boundaryAlias()` — `AndStepRecogniser` at its commit loop and
`commitPositiveFilterChild` at line 80 of the same file. So the signature widened, three call sites
including the unit tests had to change, and with `-da` the parameter is unread. It also lets a test
pass a boundary that differs from `ctx.boundaryAlias()`, so the assertion can be exercised against a
combination production cannot produce.

**Suggestion**: Drop the parameter and read `ctx.boundaryAlias()` inside the method. The invariant the
assert states is unchanged, the callers get simpler, and the assertion is then guaranteed to describe
the production pairing.

### CQ9 [suggestion] Duplicated guard condition in `translateCompare`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 339-342)

**Issue**: `isOrderComparison(compare) && rangeTypeGuard` is evaluated twice in adjacent statements,
once inside a ternary whose wrap puts the `: null` on its own line, and once again in the `if` that
follows. A reader has to hold both copies in mind to see that the `if` only fires when the ternary
took its first branch.

**Suggestion**: Hoist to a local — `var guardRequired = rangeTypeGuard && isOrderComparison(compare);`
— then `var guardTypeNames = guardRequired ? comparabilityBlock(value) : null;` and
`if (guardRequired && guardTypeNames == null) { return null; }`. The second condition then reads as
"the guard was required and no block could be named", which is what the Javadoc's decline clause says.

### CQ10 [suggestion] Inline fully-qualified names in new code

**Files** (added lines):

- `GremlinPredicateAdapter.java:392` — `literal instanceof java.util.Date` (production)
- `RangeTypeGuardEquivalenceTest.java` — `private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession()`, and `new java.util.ArrayList<Vertex>(604)` in `seedIndexedBulkClass`
- `GremlinPredicateAdapterTest.java` — `new java.util.Date(0)`, `java.time.Instant.ofEpochMilli(0)`, `java.util.UUID.randomUUID()`
- `WherePredicateStepRecogniserTest.java` — `java.util.regex.Pattern.quote("$matched")`

**Issue**: Eight sites in new code spell a type out in full where an import is the codebase norm. Two
are worth calling out specifically. The `graphSession()` return type is fully qualified while the
three sibling classes in the same package (`NotStepRecogniserTest`,
`PredicateTraversalEquivalenceTest`, `EdgeTraversalEquivalenceTest`) import
`DatabaseSessionEmbedded` for the identical helper. And `RangeTypeGuardEquivalenceTest` already
imports `java.util.List` and `java.util.Date`, so the inline `java.util.ArrayList` is inconsistent
inside one file.

**Suggestion**: Import the types. `java.util.Date` in `GremlinPredicateAdapter` is the one to weigh
first, since it sits in production code where the surrounding `comparabilityBlock` arms all use simple
names (`Number`, `String`, `Boolean`).

### CQ11 [suggestion] Declaration placement and one awkward signature wrap

**Files**:
`GremlinPredicateAdapter.java:370` (`NUMERIC_TYPE_NAMES`),
`GremlinStepWalker.java:569` (`POST_CARDINALITY_RECOGNISERS`),
`WalkerContext.java:647-648` (`setLastPropertyProjection`)

**Issue**: Two new constants are declared between methods rather than with the other fields —
`NUMERIC_TYPE_NAMES` sits between `isOrderComparison` and `comparabilityBlock`, and
`POST_CARDINALITY_RECOGNISERS` sits 35 lines *after* the `capturedCardinalityClause` method that
reads it and after the Javadoc at line 512 that links to it. Both files otherwise group their
constants at the top, `TRANSPARENT_STEPS` and `POST_UNION_RECOGNISERS` included. Separately,
`WalkerContext.setLastPropertyProjection` is wrapped between its return type and its method name:

```java
  public void
      setLastPropertyProjection(@Nullable RecognitionContext.PropertyProjection projection) {
```

The same method in `SubTraversalPredicateAdapter` wraps conventionally, after the opening paren.

**Suggestion**: Move both constants up beside the existing field group. For the wrap, import
`RecognitionContext.PropertyProjection` — it is a nested type of an interface in the same package — so
the signature fits on one line and matches the sibling implementation.

### CQ12 [suggestion] 113 added production lines exceed the 100-column width

**Files**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/` mostly,
concentrated in `PropertiesStepRecogniser.java` (30), `GremlinStepWalker.java` (14),
`GremlinProjectionAssembler.java` (11), `RangeGlobalStepRecogniser.java` (10),
`RepeatDeclineStrategy.java` (10)

**Issue**: 113 of the 2281 added production lines are over the 100-character width CLAUDE.md § Code
Style documents (4.95%), and 172 of 6768 added test lines (2.54%). All but one are comment or Javadoc
lines — the exception is an unavoidably long TinkerPop import. Spotless's Eclipse formatter does not
reflow comments, so `spotless:check` passes and nothing in the build objects. The longest is 105
characters, `PropertiesStepRecogniser`'s `CapturedDrop.PRESERVED` doc comment.

**Context, so this is graded proportionately**: the same package already sits at 4.31% over-width at
the track's base commit (404 of 9373 lines), so the track continues an existing pattern rather than
introducing one. Recorded for completeness; no correctness or review-blocking consequence.

**Suggestion**: Reflow when a file is next edited for another reason. If the width is meant to bind
comments, the enforcement gap is the thing to fix rather than these lines — the Eclipse formatter
config in `project-config/eclipse-formatter.xml` can be told to format Javadoc and line comments.

### CQ13 [suggestion] A test method sits inside the fixture-helper region

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java` (line 728)

**Issue**: `unionArmCrossTypeRange_isGuardedAndAgreesWithNative` is declared between two private seed
helpers, `seedLongKnowsChain()` at 698 and `seedKnowsChain()` at 758, with the class's other 20-odd
test cases all above line 630 and the assertion helpers all below 772. A reader scanning the case list
misses it, and a reader scanning the helpers hits a `@Test` in the middle of them.

**Suggestion**: Move it up beside the other union-arm cases — the cross-type range group in
`positionalSuffixAfterUnion_declines`'s neighbourhood reads as its natural home — leaving the seed
helpers contiguous.

### CQ14 [suggestion] Plan assertions parse `prettyPrint` debug output

**Files**: `EdgeTraversalEquivalenceTest.java`, `PredicateTraversalEquivalenceTest.java`,
`RangeTypeGuardEquivalenceTest.java` (each with its own `planRootAlias`)

**Issue**: Three copies of the same helper read the compiled plan's root alias by rendering
`ExecutionPlan.prettyPrint(0, 2)` and scanning the lines for the literal `"+ SET"`, then taking the
next line. The assertions are load-bearing — each is what keeps a root-selection change from silently
turning a multiset comparison vacuous — but they are anchored to a human-readable debug format that
carries no compatibility promise, so a formatting tweak in the plan printer turns three tests red with
an `AssertionError("plan names no root alias on a SET line")` that names the wrong cause. The same
finding's third copy arrived with this track.

**Suggestion**: Read the root alias off the plan's step objects instead of its rendering (the root
scan step exposes its alias), and put the one implementation in the shared helper CQ4 proposes. If
reading the text is the only route available today, keeping one copy makes the coupling a single place
to fix rather than three.

## Evidence base
