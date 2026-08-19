<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: TS1, sev: should-fix, loc: SubTraversalPredicateAdapterTest.java:481, anchor: "### TS1 ", cert: n/a, basis: "new tests sit under a section header and class javadoc that describe neither layer they belong to; navigation aid now misdescribes the file"}
  - {id: TS2, sev: suggestion, loc: WalkerContextResultShapingTest.java:34,     anchor: "### TS2 ", cert: n/a, basis: "WalkerContext's true answer is pinned only as a control inside the adapter's test class, nowhere in the WalkerContext test class"}
  - {id: TS3, sev: suggestion, loc: WalkerContextResultShapingTest.java:54,     anchor: "### TS3 ", cert: n/a, basis: "the ordering claim rests on lambda-instance identity a reader cannot see; the two ops are textually identical"}
  - {id: TS4, sev: suggestion, loc: SubTraversalPredicateAdapterTest.java:504,  anchor: "### TS4 ", cert: n/a, basis: "the same always-true-existence-filter rationale is restated in four places, all of which must be edited together"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] The two new adapter tests land under a section header and class javadoc that describe neither of them

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/SubTraversalPredicateAdapterTest.java`, methods `supportsListShaping_falseOnSubWalk_trueOnTheParentItWraps` (line 492) and `appendListShapingOp_onSubWalk_throwsAndLeavesTheParentShapingClean` (line 515)

**Issue**: The class javadoc (lines 27-43) states "Two layers are pinned" and binds each layer to a fixture style: layer 1 is the delegate / swallow / capture contract, "driven against a mocked parent so each delegate / swallow / capture is isolated"; layer 2 is the `walkChild` sub-walk seam, "driven against a real registry-bearing `WalkerContext` parent with fixture recognisers and the production `VertexHopRecogniser`". The section comment at lines 163-165 repeats layer 2's framing verbatim as a divider.

The two new tests are layer-1 material by topic. They pin a declared non-delegating answer and a non-swallow on a context method, which is exactly what `pinBoundaryAndSingleReturnColumn_areSwallowed` (line 89) pins for the other two swallowed methods. They were placed at the end of the layer-2 block instead, because they need a real parent rather than a mock (correctly — the step context is right that Mockito's unstubbed `false` would make the pair vacuous). The result is that both navigation aids are now wrong: the layer-2 section header claims its tests drive `walkChild` against a registry-bearing parent, and these two never call `walkChild` and pass `Map.of()` as the registry; the class javadoc's two-item enumeration silently excludes the file's third concern, and its mock-versus-real correspondence no longer holds.

The same drift reaches the helper. `parentWithBoundary` (line 537) is documented as pre-seeding what "a sub-walk reads ... through the adapter" so that "the capture-boundary assertions check it is unchanged after a declined child". The new tests use it for neither purpose — its three seeding calls (`addNode`, `pinBoundary`, `setSingleReturnColumn`) touch nothing the shaping assertions read — so a reader has to prove the seeding is irrelevant before believing `parent.shaping().listShapingOps()` starts empty for a reason other than the seeding.

**Suggestion**: Add a third section divider above line 481 and a third `<li>` to the class javadoc naming the layer — the list-shaping decline channel, driven against a real parent used as a positive control precisely because a mocked parent answers `false` to every unstubbed boolean. Then either drop to `new WalkerContext(true, false)` in both tests (neither drives a sub-walk, so the registry-bearing helper buys nothing) or state in the test javadoc that the boundary seeding is inherited scaffolding the shaping assertions do not read.

### TS2 [suggestion] `WalkerContext.supportsListShaping()` returning true is pinned only inside the adapter's test class

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContextResultShapingTest.java`, method `resultShapingFields_defaultUnset` (line 34)

**Issue**: `WalkerContextResultShapingTest` is the home for the `WalkerContext` shaping contract, and its updated class javadoc now advertises "both write paths". It never asserts the top-level context's `supportsListShaping()` answer. The only assertion on that answer anywhere in the module is `assertThat(parent.supportsListShaping()).isTrue()` at line 496 of `SubTraversalPredicateAdapterTest`, where it exists as a control for the adapter's `false`. So the `WalkerContext` side of the pairing is covered as a side effect of a test about a different class. A rewrite of the adapter test that switches the control to a stub, or drops the positive arm as redundant, silently removes the only pin on the production `true`. (Symbol enumeration here is a grep over `core/src` for the two new method names rather than PSI find-usages: `steroid_execute_code` times out in this repo. The names are new and unique, so a textual sweep is reference-accurate for this question, but the caveat stands.)

**Suggestion**: Add one line to `resultShapingFields_defaultUnset`, or a short dedicated test, asserting `ctx.supportsListShaping()` is `true` with an `as(...)` describing why the top-level walk can carry an op. Keep the adapter test's positive control as a control; the point is that the `WalkerContext` contract should not depend on another class's test for its only pin.

### TS3 [suggestion] The append-order claim rests on lambda-instance identity a reader cannot see

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContextResultShapingTest.java`, method `appendListShapingOp_appendsInDeclaredOrderAndPreservesPinnedFlags` (lines 54-62)

**Issue**: `first` and `second` are textually identical (`upstream -> upstream`), and the assertion `containsExactly(first, second)` discriminates order only because the two lambda expressions produce two distinct instances compared by reference equality. Nothing in the test says so. A reader checking whether the test earns its `as("declared order is the application order")` description has to reason about lambda instantiation to conclude that it does, and if the two references ever became one object the assertion would still pass for either order while claiming to pin order. The ops are also opaque on failure: an `AssertionError` reports two synthetic lambda class names that cannot be told apart.

**Suggestion**: Give the two ops observable identity, for example a private helper `private static ListShapingOp taggedOp(String tag)` returning an op with a `toString()` of `tag`, or make them behaviourally distinct so a reader sees what "declared order" means. Either makes the discriminator visible in the source and legible in a failure message.

### TS4 [suggestion] The same decline-channel rationale is restated in four places that must now be edited together

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/SubTraversalPredicateAdapterTest.java`, method javadoc at lines 504-513

**Issue**: The argument that a swallow would turn `g.V().and(__.out().fold())` into an always-true existence filter, "because a dry upstream still emits one empty list", appears near-verbatim in four places this step touches: `RecognitionContext#supportsListShaping` (javadoc), `SubTraversalPredicateAdapter#supportsListShaping` (javadoc), `SubTraversalPredicateAdapter#appendListShapingOp` (inline comment), and this test javadoc. The companion argument — that a throw cannot serve as the decline channel because `GremlinToMatchStrategy`'s `RuntimeException` net degrades it to a silent decline — appears in the same four. The rationale is worth having; carrying four copies of it means the project's keep-comments-in-sync rule now applies across four sites for any change to the decline design, and the copies have already diverged in their closing clause ("rows disappear with nothing to see" / "every row the `and` should have dropped survives instead" / "would survive").

**Suggestion**: Keep one canonical statement on `RecognitionContext#supportsListShaping` and reduce the others to a one-line summary plus a `{@link}` to it. The test javadoc needs only the scenario and expected outcome the project convention asks for — a recogniser that appends without querying first hits a throw and leaves the parent's shaping untouched — plus the pointer for the why.

## Evidence base
