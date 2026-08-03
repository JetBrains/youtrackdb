<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 2, suggestion: 5}
index:
  - {id: TS1, sev: should-fix, loc: OrderRangeStepRecogniserTest.java:277, anchor: "### TS1 ", cert: n/a, basis: "two pre-existing decline tests now satisfy two independent decline reasons; deleting the check they name leaves them green"}
  - {id: TS2, sev: should-fix, loc: UnionTraversalEquivalenceTest.java:391, anchor: "### TS2 ", cert: n/a, basis: "three of five count assertions sit outside the assertMultiPlanEngaged guard; range(1,-1).count() engagement is pinned nowhere"}
  - {id: TS3, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:382, anchor: "### TS3 ", cert: n/a, basis: "method name and Javadoc describe a narrower body than the test asserts; one sentence survives from the deleted limit test"}
  - {id: TS4, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:446, anchor: "### TS4 ", cert: n/a, basis: "skip(3) omitted from the diagnostic-first multiset helper without stated reason, though the fixture separates the orders for it too"}
  - {id: TS5, sev: suggestion, loc: GremlinStepWalkerTest.java:967, anchor: "### TS5 ", cert: n/a, basis: "tests named reachesTheFork assert DECLINE with the explanation only on a sibling test 40 lines up"}
  - {id: TS6, sev: suggestion, loc: OrderRangeStepRecogniserTest.java:369, anchor: "### TS6 ", cert: n/a, basis: "getSteps().get(1) magic index where the class already has a type-keyed step locator"}
  - {id: TS7, sev: suggestion, loc: UnionTraversalEquivalenceTest.java:759, anchor: "### TS7 ", cert: n/a, basis: "save/restore block duplicated verbatim between the two translator-toggling helpers"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] Two pre-existing post-union decline tests stopped discriminating when the positional gate landed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/OrderRangeStepRecogniserTest.java`, methods `secondPostUnionRange_declines` (line 277) and `postUnionRangeAfterCount_declines` (line 293)

**Issue**: Both tests build their slice as `graph.traversal().V().limit(1).asAdmin()` — a bare `limit(1)` with nothing behind it. Before this step that fixture had exactly one reason to decline: the `postConcatOps()` loop at `RangeGlobalStepRecogniser:78-83` found an existing `Range` or `Count` op. It now has two. Delete the loop and the walk falls through to `normalize` (skip 0, limit 1, not a no-op), reaches `followedByCount(cursor)`, finds `cursor.peek() == null`, and returns `DECLINE` anyway. Both tests stay green with the check they exist to pin removed.

The loop runs before the positional gate, so the tests are not currently failing for the wrong reason — they are passing for two reasons at once, which is the same vacuous-acceptance shape this step was opened to close. The next person to reorder the two checks, or to fold the second-range case into the positional one, gets no signal.

**Suggestion**: Append `.count()` to both fixtures — `graph.traversal().V().limit(1).count().asAdmin()`. With a count immediately behind the slice, the positional gate accepts, and the only surviving decline reason is the one each test names. `cursorAt(admin, RangeGlobalStep.class)` still lands on the same step, so nothing else in either test moves.

The same double-reason overlap exists end to end at `UnionTraversalEquivalenceTest.postUnionRangeAfterCount_declines` (line 567, `union(...).count().limit(1)`), but there it cannot be fixed: appending a count makes it a second-count decline instead. Leave that one and fix the two unit-level cases, which are the ones that can be made discriminating.

### TS2 [should-fix] Three of the five count assertions in the rewritten slice test sit outside the engagement guard

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionThenSkipAndRangeThenCount_sliceTheConcatenation` (line 391)

**Issue**: The method calls `assertMultiPlanEngaged` for `skip(1).count()` and `range(1, 3).count()` only (lines 393-396), then asserts five counts (lines 399-415). The unsliced `count()`, `limit(2).count()`, and `range(1, -1).count()` run with no engagement check. Every one of those returns the identical value on the native pipeline, so each would pass unchanged if its shape quietly declined. `assertMultiPlanEngaged`'s own Javadoc (line 778) names this exact hazard as its reason to exist.

Two of the three are covered elsewhere: plain `count()` by `unionThenCount_returnsSameTotalAsNative` (line 166) and `limit(2).count()` by `unionThenLimitThenCount_countsTruncatedConcatenation` (line 338, which asserts `RECOGNIZED_MULTI_PLAN`). `range(1, -1).count()` — the unbounded-high post-union slice that normalises to skip-only — is pinned nowhere. The class-level unit test `rangeUnboundedHigh_setsSkipOnly` covers the single-plan path, and `postUnionRangeBeforeCount_appendsPostConcatOpAndLeavesSqlClausesAlone` covers only `Range(1, 2)`, so no test in either class asserts that an unbounded-high post-union slice produces `PostConcatOp.Range(skip, -1)` on a translated plan. The `3L` at line 415 is the sole claim about that shape, and it holds natively.

The gap predates this step for `range(1, -1)`, but the method was rewritten here and the guard is one line.

**Suggestion**: Add `assertMultiPlanEngaged` calls for `range(1, -1).count()` and `limit(2).count()` alongside the two already present, so no assertion in the block can pass through a silent decline. Alternatively, assert `PostConcatOp.Range(1L, -1L)` for the unbounded-high case in `OrderRangeStepRecogniserTest`, which pins the normalisation rather than just the row count.

### TS3 [suggestion] The rewritten slice test's name and Javadoc describe less than the body asserts

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionThenSkipAndRangeThenCount_sliceTheConcatenation` (Javadoc line 382, name line 391)

**Issue**: The name covers skip and range; the body also asserts the unsliced count (line 399), `limit(2).count()` (line 402), and `range(1, -1).count()` (line 412). One sentence survived from the deleted `unionThenLimit_truncatesConcatenationToLimit` and no longer reads against the body it now heads: "with a concatenation no larger than the slice the truncation is invisible and dropping it entirely still passes" argued for a four-row fixture under `limit(2)`, and a reader landing on it now has to reconstruct which of the five assertions it is about.

**Suggestion**: Either narrow the body to skip and range (moving the `limit(2).count()` assertion to `unionThenLimitThenCount_countsTruncatedConcatenation`, which already owns that shape), or widen the name to `unionThenSliceThenCount_sliceTheConcatenation` and rewrite the fixture sentence to say what it now means: four rows is the smallest concatenation on which `limit(2)`, `skip(1)`, and `range(1, 3)` each return a count that differs from the untruncated total.

### TS4 [suggestion] `skip(3)` is left out of the diagnostic-first multiset check without a stated reason

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `positionalSuffixAfterUnion_declines` (lines 429-450)

**Issue**: `assertSameMultisetOnAndOff` runs for `limit(3)` and `range(2, 5)` but not for `skip(3)`, which gets only the `assertEquivalent(..., DECLINED, ...)` treatment at lines 446-449. The helper exists precisely so a re-admitted shape reports the divergence rather than the recognition mismatch, and the asymmetry has no comment explaining it.

The fixture does separate the two orders for `skip(3)`. On the eight-vertex chain the concatenation is seven out-rows (Bob…Hank) followed by seven in-rows (Alice…Gina). Branch-major `skip(3)` drops three out-rows and keeps Alice; native's interleaved prefix is Bob, Carol, Alice, so native drops Alice, which appears exactly once. The surviving multisets differ, so the third shape would get the same diagnostic failure the other two get.

**Suggestion**: Add the third `assertSameMultisetOnAndOff` call for `skip(3)`, or state in the Javadoc why the skip shape is exempt.

### TS5 [suggestion] Two tests named `reachesTheFork` assert `DECLINE`, with the explanation only on a sibling

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalkerTest.java`, methods `union_positionalSuffixEndingInCount_reachesTheFork` (line 967) and `union_noOpSliceWithoutCount_stillReachesTheFork` (line 988)

**Issue**: Both assert `assertThat(outcome).isEqualTo(Outcome.DECLINE)` while their names and Javadocs say the shape is translatable and must reach the fork. The reconciling fact — `CountingUnionForkHost` is a stub whose `walkFork` declines, so the recogniser stops after the first arm and the traversal-level outcome is `DECLINE` either way — appears only in the Javadoc of `union_translatableSuffix_reachesTheFork` at line 922, forty lines above. A reader arriving at either new test from a failure report sees a name and an assertion that contradict each other and has to scroll to find out why they do not.

The outcome assertion also carries no `.as(...)` description, while the `forkCalls` assertion below it does. The undescribed one is the confusing one.

**Suggestion**: Add `.as("the fork stub declines, so the traversal-level outcome is DECLINE either way; forkCalls is the observable under test")` to the outcome assertion in both new tests, or repeat the one-clause explanation in each Javadoc.

### TS6 [suggestion] `selectsPositionally` tests index into the step list where the class has a type-keyed locator

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/OrderRangeStepRecogniserTest.java`, methods `selectsPositionally_nonRangeStep_isFalse` (line 369) and `selectsPositionally_noOpSkip_isFalse` (line 381)

**Issue**: Both reach their step with `admin.getSteps().get(1)`. Every other test in the class locates a step through `cursorAt(admin, StepClass.class)` (line 417), which fails with a named `AssertionError` when the fixture does not produce the expected step. The bare index says nothing about which step it selects, and it breaks with an `IndexOutOfBoundsException` rather than a readable message if a TinkerPop upgrade changes what the builder emits for `V().skip(0)`.

**Suggestion**: Add a sibling to `cursorAt` that returns the step rather than a cursor — `private static Step<?, ?> stepOf(Traversal.Admin<?, ?> admin, Class<?> stepType)` — and call `stepOf(admin, CountGlobalStep.class)` and `stepOf(admin, RangeGlobalStep.class)`. Each call then states which step it means.

### TS7 [suggestion] The two translator-toggling helpers duplicate their save-and-restore block

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, methods `assertEquivalent` (line 703) and `assertSameMultisetOnAndOff` (line 759)

**Issue**: The new helper copies the seven-line read-original / try / finally-restore frame from `assertEquivalent` verbatim. Two copies of a restore contract is where the third copy forgets the `finally`.

**Suggestion**: Extract `private <T> T withTranslatorRestored(Supplier<T> body)` (or a `Runnable` form) holding the read-original / try / finally frame, and let both helpers call it. This also gives the several test bodies that call bare `setTranslatorEnabled(true)` without restoring — `unionAntiCartesian_returnsSumNotProduct`, `unionThenSkipAndRangeThenCount_sliceTheConcatenation`, and others — an obvious thing to adopt if the per-test database ever stops being fresh.

## Evidence base
