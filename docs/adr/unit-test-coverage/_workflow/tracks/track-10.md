# Track 10: Query & Fetch

## Description

Write tests for query infrastructure and fetch plan execution.

Target packages:
- `core/query` (237 uncov, 38.8%) — query helpers (QueryHelper.like,
  BasicResultSet, ExecutionPlan)
- `core/query/live` (272 uncov, 13.4%) — live query infrastructure
  (LiveQueryQueueThread, LiveQueryListener)
- `core/fetch` (248 uncov, 46.6%) — fetch plan execution

QueryHelper.like() is a pure function — excellent quick win. Live
query tests need threading. Fetch plan tests need a database session
with linked records to verify depth-based loading.

**Scope:** ~4 steps covering query helpers, live query, fetch plans,
and verification
**Depends on:** Track 1

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review (1/3 iterations — all iter-1 should-fix items applied across commits `a8c918b74b` (initial batch) and `adc9ce95bb` (remaining deferrals: TB3/TC1/TC2/TC3/TC4/CQ2/CQ3/CQ4/BC4+TS4/TS5/TS1). iter-2 gate check pending — started under context pressure (25% warning) so context needs to be refreshed before spawning fresh sub-agents.)

### Iteration-1 code-review resolution

Six dimensional agents ran in parallel (CQ / BC / TB / TC / TS / TX). Synthesized
findings in `reviews/track-10-code-review-iter1.md`. No blockers. Multi-reviewer
convergence flagged seven issues; all dispositioned below.

**Applied in commit `a8c918b74b`** (live-query files; all 48 tests pass, spotless
clean):

| ID | Dimensions | Disposition |
|---|---|---|
| BC2 / TX1 | bugs-concurrency + test-concurrency | `awaitThreadState` now throws `AssertionError` on timeout so lone-interrupt preconditions fail loudly. |
| BC1 | bugs-concurrency | V2 lone-interrupt Javadoc corrected (states RUNNABLE tight catch/continue); second `awaitThreadState(WAITING)` removed — V2 never re-parks after a re-interrupted loop. Liveness pinned via bounded join + `isAlive()`. |
| TB4 / TX8 | test-behavior + test-concurrency | V1 `cloneSharesQueueAndSubscribers` now pins queue-sharing: start only the clone, enqueue on the original, assert the listener's latch trips. |
| TX3 / TB8 | test-concurrency + test-behavior | `v2_runLoopBatchesAndDispatches` guards explicitly on `QUERY_REMOTE_RESULTSET_PAGE_SIZE >= 2` so a future JVM-option change does not silently flip the single-batch collapse assertion. |
| TB1 (V1 + V2) | test-behavior | `removePendingDatabaseOpsWithLiveSupportDisabledIsNoOp` (both V1 and V2) now populate pending-ops first so the size-preservation check is falsifiable — a regression removing the `QUERY_LIVE_SUPPORT` guard would evict the entry. |
| BC3 / TS3 (V1 + V2) | bugs-concurrency + test-structure | `removePendingDatabaseOpsOnClosedSession` (both V1 and V2): `secondary.close()` + precondition assertions moved into the try block so `session.activateOnCurrentThread()` in finally always restores the fixture session. Added defensive double-close. |
| TB2 / BC6 / TX2 | test-behavior + bugs-concurrency + test-concurrency | `v2_subscribeThroughStaticEntryPointRegistersListenerAndReachesAutoStart` now pins the auto-start branch via bounded enqueue-reaches-listener latch — without a running dispatcher the latch would never fire. Javadoc acknowledges the unbounded `ops.close().join()` limitation as Track 22 scope. |
| CQ1 | code-quality | FQN ceremony in the `BasicLiveQueryResultListener` / `LiveQueryResultListener` zero-impl pins replaced with explicit imports for `DatabaseSessionEmbedded`, `BasicResult`, `Result`, `BaseException`. |

**Applied in commit `adc9ce95bb`** (remaining iter-1 should-fix items;
all tests pass in affected classes, spotless clean):

| ID | Dimensions | Disposition |
|---|---|---|
| TB3 | test-behavior | Rewrote `rangeBracketWithOmittedUpperBoundDefaultsToInfinity` to pin only the in-range and deep-level observables; dropped the level-2 duplicate that was really exercising the else-branch key-equality. |
| TC1 | test-completeness | Added `rangeBracketWithOmittedLowerBoundStartsAtZero` (`[-N]ref:L`) and `rangeBracketWithBothBoundsOmittedDefaultsToInfiniteRange` (`[-]ref:L`) parser pins — symmetric to the existing `[N-]` pin. |
| TC2 | test-completeness | Added `hasThrowsNullPointerExceptionForNullFieldPath` pin and `hasReturnsTrueForEmptyFieldPathOnAnyPlan` pin documenting the "empty path matches every main-map key via startsWith" behaviour (direction-of-startsWith regression guard). |
| TC3 | test-completeness | Added four new `checkFetchPlanValid` pins in `FetchHelperDeadCodeTest`: first-good-then-bad compound plan, first-good-then-triple-colon, leading-whitespace, trailing-whitespace. |
| TC4 | test-completeness | Added `testGetVertexReturnsLinkedVertex` (vertex link on a vertex row) and `testGetEdgeReturnsLinkedEdge` (edge link on a vertex row via non-reserved property name — reserved `in`/`out` names are schema-guarded, so user-level link properties cannot use them). |
| CQ2 | code-quality | Extracted `withLiveSupport(boolean, Runnable)` + `noopListenerV1()` / `countingListenerV1(AtomicInteger)` / `noopListenerV2(int)` / `countingListenerV2(int, AtomicInteger)` helpers. Replaced 8 inline save/restore + anonymous listener boilerplate call sites in `LiveQueryHookStaticApiTest`. |
| CQ3 / TS1 | code-quality + test-structure | Added canonical single-asterisk Apache-2 license banner to the four Step 1 files (BasicResultSetDefaultMethodsTest, ExecutionStepToResultTest, QueryRuntimeValueMultiTest, ResultDefaultMethodsTest). Step 2/3 files' existing banners accepted by Spotless per iter-1 application plan. |
| CQ4 | code-quality | Extracted `withSingleVertexResult` + `withSingleEdgeResult` + `withProjectionRow` helper trio on `ResultDefaultMethodsTest`; refactored 8 test bodies to use them. Kept the `testAsVertexOnVertexResult` inline pattern because its custom `"name":"v1"` setup differed from the helper's default. |
| BC4 / TS4 / TS5 | bugs-concurrency + test-structure | Added defensive `stopExecution + join(1000)` to `v1_unsubscribeUnknownKeyIsNoOp` and defensive `ops.close()` in `finally` to `v1_opsExposeFreshQueueThread`, `v2_opsSubscribersLifecycle`, `v2_opsUnsubscribeUnknownTokenIsNoOp`, `v2_opsEnqueueRoutesToSharedQueue`. The already-symmetric `v1_opsCloseOnNeverStartedOpsIsIdempotent` / `v2_opsCloseOnNeverStartedIsIdempotent` self-close tests need no additional guard; `v2_liveQueryOpConstructorAllowsNullBeforeAndAfter` creates a pure value object (no thread). |

**Deferred further (merge-ready or out-of-iter-1 scope):**

| ID | Dimensions | Disposition |
|---|---|---|
| TS2 | test-structure | `session.begin()/session.commit()` setup without try/finally in V2 tests: kept current style because `TestUtilsFixture.rollbackIfLeftOpen` already covers the failure case and adding explicit try/finally around every setup block would bloat the file without tangible safety win. Merge-ready. |
| TS6 | test-structure | Listener stub duplication across three files — defer to Track 22 DRY sweep (already acknowledged in Step 2 episode CQ8/TS6). |

**Suggestion-tier items** (TB5-TB9, TC5-TC9, CQ5-CQ11, TS7-TS14, TX4-TX7)
are merge-ready per the step episodes' original dispositions; several are
absorbed into Track 22 via the markers planted in Steps 1-3.

## Base commit
`5b8ec34bcc`

## Reviews completed
- [x] Technical (iter-1: 0 blockers / 3 should-fix / 7 suggestions) → `reviews/track-10-technical.md`
- [x] Risk (iter-1: 0 blockers / 3 should-fix / 3 suggestions) → `reviews/track-10-risk.md`
- [~] Adversarial — skipped (not warranted: no major architectural decision; test-additive only; precedent inherited from Tracks 5–9)

### Iteration-1 review resolution

Both reviews converge on the same core insight: **Track 10's live-query
and fetch scope is substantially dead code** in the core module. This
mirrors Track 9's `CommandExecutorScript` / `ScriptDocumentDatabaseWrapper`
situation. All fixes are plan/decomposition changes (not code changes)
and are resolved by following the Track 7/9 `*DeadCodeTest` + `// WHEN-FIXED:
Track 22` precedent. No re-review iteration needed.

**Cross-module caller grep (performed at iter-1 synthesis)** confirmed:
- `FetchHelper` — 0 callers in `server/`, `driver/`, `embedded/`,
  `gremlin-annotations/`, `tests/` modules. Only `core/src/main/FetchHelper.java`
  self-references + `DepthFetchPlanTest.java` in core/src/test.
- `FetchPlan` / `FetchContext` / `FetchListener` types — 0 cross-module
  callers outside `core/fetch/`.
- Live-query subsystem — the only live surface is `LiveQueryHookV2.
  unboxRidbags`, called from `CopyRecordContentBeforeUpdateStep.java:52`.
  All other public-static surface (`subscribe`/`unsubscribe`/`addOp`/
  `notifyForTxChanges`/`removePendingDatabaseOps`/`calculateBefore`/
  `calculateProjections`) has 0 production callers in core and 0 cross-
  module callers.

Findings and their dispositions:

| ID | Severity | Disposition | Where addressed |
|---|---|---|---|
| **T1 ≡ R1** | should-fix | Reframe the live-query step as a dead-code pin step. Create `LiveQueryDeadCodeTest` with observable-shape pins for `LiveQueryHook`/`LiveQueryHookV2` public statics + `LiveQueryQueueThread`/`LiveQueryQueueThreadV2` + zero-impl interface pins for `LiveQueryListener`/`LiveQueryListenerV2`/`BasicLiveQueryResultListener`/`LiveQueryResultListener`/`LiveQueryMonitor`. Use `@Rule Timeout(10s)` + prefer synchronous tests on `subscribe`/`unsubscribe`/`enqueue` surface over starting real threads (per R4). Include separate LIVE test for `LiveQueryHookV2.unboxRidbags` (LinkBag unbox + non-LinkBag passthrough — the sole live entry point). Absorb deletions into Track 22. | Step 2 |
| **T2 ≡ R2** | should-fix | Cross-module grep confirmed `FetchHelper` is dead everywhere, not just in core. Split fetch into (a) standalone `FetchPlanParserTest` covering constructor branches, `has`/`getDepthLevel` wildcard matching — pure string parsing, no DB; (b) `FetchHelperDeadCodeTest` with observable-shape pins on `fetch`/`isEmbedded`/`buildFetchPlan`/`checkFetchPlanValid`/`processRecordRidMap`/`removeParsedFromMap` + `// WHEN-FIXED: Track 22 — delete core/fetch/ package`; (c) enhance `DepthFetchPlanTest` to extend `TestUtilsFixture` (adopt `@After rollbackIfLeftOpen`) and add 1-2 more fixture scenarios for reachable branches. Absorb `core/fetch/*` deletion into Track 22. | Step 3 |
| **R3** | should-fix | All new DbTestBase-extending tests extend `TestUtilsFixture` (Track 8 Step 1 pattern) for the `@After rollbackIfLeftOpen` safety net. Retrofit `DepthFetchPlanTest` in Step 3 — small regression-proofing win. | Step 3 convention |
| **T9** | should-fix | Step 1 re-runs coverage analyzer and writes `track-10-baseline.md` mirroring `track-9-baseline.md`. Baseline excludes pinned dead-code LOC (`core/query/live/*` + `core/query/BasicLiveQueryResultListener`/`LiveQueryResultListener`/`LiveQueryMonitor` + `core/fetch/*` — except `LiveQueryHookV2.unboxRidbags` and the `FetchPlan` parser surface) from the 85% line / 70% branch denominator. | Step 1 |
| **T3** | suggestion | Extend `StandaloneComparisonOperatorsTest` with the remaining 6 regex escape chars (`\`, `.`, `^`, `$`, `+`, `|`, `{`, `}`) and Turkish-locale case (Track 7 TC3 precedent) — do NOT create a new duplicating `QueryHelperLikeTest`. Accept. | Step 1 |
| **T4** | suggestion | Fold V1 (`break`) vs V2 (`continue`) `InterruptedException` behavioral inconsistency pin into `LiveQueryDeadCodeTest` with `// WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling`. Accept. | Step 2 |
| **T5** | suggestion | Pin `ExecutionStep.java:41` duplicate-`getSubSteps()` call (harmless but wasteful) in the `ExecutionStep.toResult` test with `// WHEN-FIXED: Track 22 — remove duplicate getSubSteps() call`. Accept. | Step 1 |
| **T6** | suggestion | Add pure-Java `BasicResultSetDefaultMethodsTest` (no DbTestBase) with a package-private `TestResultSet` inner class. Tests: empty source / single-element / multi-element / close called by terminal stream ops / `findFirst` NoSuchElementException on empty. Accept. | Step 1 |
| **T7** | suggestion | Separate `FetchPlan` parser unit tests (standalone, ~15-20 tests covers all constructor branches) from end-to-end tests that require DbTestBase. Already in Step 3 disposition. Accept. | Step 3 |
| **T8** | suggestion | Add three `checkFetchPlanValid` boundary cases (`null` → no-op, `""` → no-op, `" "` → "Fetch plan is invalid") to `FetchHelperDeadCodeTest`. Accept. | Step 3 |
| **T10** | suggestion | `DepthFetchPlanTest` style modernization (`executeInTx` callbacks instead of top-level `begin`/`commit`) — absorb into Track 22 DRY queue (see plan update). | Track 22 |
| **R4** | suggestion | `@Rule Timeout(10s)` + synchronous `subscribe`/`unsubscribe`/`enqueue` surface tests preferred over real thread starts, per Track 7 `SqlQueryDeadCodeTest` precedent. Accept. | Step 2 convention |
| **R5** | suggestion | `Result`/`ResultSet` default-method tests mix stub (for `BasicResultSet.stream`/`toList`/`findFirst*`) + DB-backed (for `Result.asEntity`/`asVertex`/`asEdge` dispatch via `session.query("SELECT FROM V")`). Accept. | Step 1 split |
| **R6** | suggestion | Pin `LiveQueryHookV2.calculateProjections` always-returns-empty-or-null pre-existing bug with `// WHEN-FIXED: Track 22 — calculateProjections populates result set` marker. Accept. | Step 2 |

## Test-strategy precedent (carry-forward from Tracks 5–9)

- **DbTestBase via `TestUtilsFixture`** for DB-path tests (per-track D2
  override). Standalone tests (no base class) for pure utility / pure
  function code — `QueryHelper.like` corners, `FetchPlan` parser branches,
  `BasicResultSet` default-method shim, `ExecutionStep.toResult` with
  null session or anonymous stub, `LiveQueryHookV2.unboxRidbags`,
  `RemoteFetchContext` no-op callbacks.
- **`@After rollbackIfLeftOpen`** safety net inherited from
  `TestUtilsFixture` (Track 8 Step 1) — extend `TestUtilsFixture` for
  any DbTestBase-requiring test; retrofit `DepthFetchPlanTest` in this
  track.
- **Dead-code pinning** via dedicated `LiveQueryDeadCodeTest` +
  `FetchHelperDeadCodeTest` mirroring Track 7's `SqlQueryDeadCodeTest`
  and Track 9's `CommandScriptDeadCodeTest`. Each pin carries a
  `// WHEN-FIXED: Track 22 — delete <class>` marker tied to a falsifiable
  observable (stub return value, zero-callers assertion, behavioral
  shape).
- **`@Rule Timeout(10s)`** class-level backstop on every dead-code pin
  test that could block a queue/thread (live-query `run()` loop, any
  `queue.take`/`join` call).
- **`// forwards-to: Track NN`** for failures attributable to other
  subsystems. Pin and work around in Track 10; do not block.

## Steps

- [x] Step 1: Baseline + QueryHelper.like corners + BasicResultSet defaults + ExecutionStep.toResult
  - [x] Context: warning
  > **What was done:**
  > Landed 5 new test files + 1 extended test + 1 baseline-exclusions doc
  > (~1,100 added LOC, test-only) across two commits:
  > - `f4bf389f1f` — initial Step-1 implementation: `track-10-baseline.md`,
  >   `BasicResultSetDefaultMethodsTest` (standalone stub-driven default-
  >   method coverage + toList-no-auto-close pin), `ExecutionStepToResultTest`
  >   (anonymous ExecutionStep stub + duplicate-getSubSteps pin at line 41),
  >   `QueryRuntimeValueMultiTest` (toString / getters), `ResultDefaultMethodsTest`
  >   (DB-backed asVertex / asEdge / is*-flags dispatch via
  >   `session.query("SELECT FROM V")`), and an extension to
  >   `StandaloneComparisonOperatorsTest` (`testLikeUsesEnglishLocaleForLowercasing`).
  > - `4d3c0b2bc9` — review-fix commit addressing the iter-1 dimensional
  >   sweep (5 agents: CQ / BC / TB / TC / TS).
  >
  > **What was discovered:**
  > Review fixes applied in iter-1:
  > - `Locale.setDefault(tr-TR)` in the new Turkish-locale test raced with
  >   surefire's `<parallel>classes</parallel>` configuration (BC-1 / TB1 /
  >   TS1 convergent). Rewrote the test to pin `Locale.ENGLISH` via input
  >   characters (U+0130 "İ" lowercases to "i̇" under ENGLISH, to "i"
  >   under tr-TR) — removing process-state mutation entirely.
  > - `testAsVertexOnProjectionThrowsIllegalState` used `@Test(expected=...)`
  >   + `try/finally { session.commit(); }` (CQ-1 / BC-2 / TS6 convergent).
  >   The commit-on-expected-throw path could mask the test's real failure
  >   mode. Replaced with `Assert.assertThrows` + message-content assertion
  >   on "not an entity" to pin `ResultInternal.asEntity`'s specific ISE
  >   throw site rather than any ISE.
  > - `TestResultSet.close()` incremented `closeCount` unconditionally
  >   (CQ-2 / TB2 / TS2 convergent). Made close idempotent (increment only
  >   on first call) and tightened the two stream-onClose assertions from
  >   `>= 1` to `== 1` — future mutations that double-wire `onClose` are
  >   now detectable.
  > - `testGetDefinitionReturnsStoredReference` and `testGetCollateReturnsByIndex`
  >   were coverage-driven rather than behavior-driven (TB3). Replaced with
  >   Mockito-backed identity pin (getDefinition) and distinct-collates pin
  >   (getCollate) so mutations that hard-code null or ignore the index
  >   parameter are caught.
  > - Missing null-element branch in `detachedStream`'s anonymous
  >   `tryAdvance` (TC1). Added `testDetachedStreamSkipsNullElements` +
  >   `testDetachedStreamAllNullsYieldsEmpty`.
  > - Missing negative-dispatch paths for `as*OrNull` on cross-kind rows
  >   (TC3). Added `testAsEdgeOrNullOnVertexRowReturnsNull`,
  >   `testAsBlobOrNullOnVertexRowReturnsNull`,
  >   `testAsVertexOrNullOnEdgeRowReturnsNull`.
  > - Missing boundaries on `QueryRuntimeValueMulti` (TC4): leading-null
  >   toString, empty-array reference parity, collate-mismatch
  >   (values > collates). Added three tests.
  > - Deleted redundant / misnamed `testToResultWithNonNullSessionAcceptedAsBindTarget`
  >   (CQ-6 / TB4 / TS7 convergent) and replaced with
  >   `testToResultRecursesThroughMultipleLevels` pinning depth > 1
  >   sub-step recursion.
  >
  > **Remaining findings deferred** (suggestion-grade, not fixed in-step):
  > - CQ-3 / TS4 — inconsistent stream-close pattern across tests
  >   (try-with-resources in some, not in others). Trivial cosmetic; pick
  >   a convention during Phase C track-level review if still relevant.
  > - CQ-4 — magic number 42L for cost. Aesthetic.
  > - CQ-5 / TS9 — NULL_DEF constant over-engineered. Keep-as-is
  >   (call-site readability).
  > - CQ-7 — detach-equality-via-id javadoc cross-reference. Nit.
  > - CQ-8 — baseline doc 76-LOC clarification. Nit.
  > - BC-3 — TestResultSet not thread-safe (thread-confined in practice).
  >   Document in Phase C if stubs are promoted to test-commons.
  > - BC-4 — `assertSame` on getValues pins reference-identity. Kept as
  >   intentional.
  > - TB5 — already addressed.
  > - TB6 — testGetVertex/Edge only covers null short-circuit. Defer
  >   (linked-vertex happy-path test adds complexity; null path is the
  >   dominant production flow).
  > - TB7 — testDetachEmpty null-filter branch. Already covered by the
  >   new null-element tests (TC1 fix).
  > - TC5 — depth > 1 recursion. Addressed by `testToResultRecursesThroughMultipleLevels`.
  > - TC6 / TC7 — `findFirst*` null function / function-returning-null.
  >   Low-value corner.
  > - TC8 — null subSteps. Refuted (violates `@Nonnull`).
  > - TC9 — literal dot escape already covered by
  >   `testLikeSpecialRegexCharsEscaped`.
  > - TC10 — stream-after-close. Low-value.
  > - TS3 — promote TestResult/TestResultSet to test-commons. Defer
  >   until Step 2 or later track demonstrates reuse.
  > - TS5 — per-test V-class naming overhead. Cosmetic.
  > - TS8 — section banner for pin tests. Cosmetic.
  > - TS10 — TestResult.equals by-id comment. Minor.
  >
  > Iter-2 gate check deferred — session context reached `warning` at 30%
  > after iter-1 fixes. The iter-1 finding set was fully dispositioned
  > (should-fix fixed in commit `4d3c0b2bc9`, suggestions noted above).
  >
  > **Key files:**
  > - `docs/adr/unit-test-coverage/_workflow/track-10-baseline.md` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/BasicResultSetDefaultMethodsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/ExecutionStepToResultTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/QueryRuntimeValueMultiTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/ResultDefaultMethodsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/operator/StandaloneComparisonOperatorsTest.java` (modified — added Turkish-locale pin + Locale import removed after iter-1 rewrite)
  >
  > **What changed from the plan:**
  > No structural deviation. Iter-1 review identified cross-cutting
  > fragility in Locale.setDefault; fix is contained to this step and
  > does not affect Steps 2–4. Cross-track impact: **Continue** — none
  > of the findings weaken assumptions for Tracks 11–21 or Track 22.

- [x] Step 2: Live-query dead-code pinning + unboxRidbags live test
  - [x] Context: warning
  > **What was done:**
  > Landed 3 new standalone / DB-backed test files covering the live-query
  > package as dead code plus the sole live static (`LiveQueryHookV2.unboxRidbags`)
  > across two commits:
  > - `f57732f51c` — initial Step-2 implementation: `LiveQueryDeadCodeTest`
  >   (standalone, `@Rule Timeout(10s)`, 22 `@Test`s — V1/V2 dispatcher thread
  >   shape, clone-shares-subscribers/ops pins, interrupt handling, zero-impl
  >   interface pins), `LiveQueryHookStaticApiTest` (extends `TestUtilsFixture`,
  >   `@Rule Timeout(15s)`, 19 `@Test`s — V1/V2 `getOpsReference`, subscribe /
  >   unsubscribe / addOp / notifyForTxChanges / removePendingDatabaseOps gates,
  >   `calculateBefore` null-vs-projection pins, `calculateProjections`
  >   always-empty known-bug pin), `LiveQueryHookV2UnboxRidbagsTest` (extends
  >   `TestUtilsFixture`, 9 `@Test`s — LinkBag empty / single / multi / fresh-
  >   copy pins + 5 non-LinkBag passthrough pins). Every pin carries a
  >   falsifiable observable + `// WHEN-FIXED: Track 22` marker naming the
  >   class the final sweep track should delete.
  > - `9d09fcde01` — dimensional review fix pass (6 agents: CQ / BC / TB /
  >   TC / TS / TX). All six reviewers converged on the same structural
  >   observation: the initial pass had several headline pins whose
  >   assertions did not distinguish the behaviour the pin's Javadoc claimed
  >   to observe.
  >
  > **What was discovered:**
  > Iteration-1 dimensional review surfaced (and iter-1 fixed) these
  > convergent assertion-precision gaps and missing happy-path tests:
  > - **V1/V2 interrupt-handling pin not falsifiable** (TB2 / TX1 — two-
  >   reviewer convergent): `v1_stopExecutionInterruptsRunLoopAndExits`
  >   and `v2_stopExecutionExitsRunLoop` both called `stopExecution()`,
  >   which atomically sets `stopped=true` AND interrupts — a refactor
  >   swapping V1's `break` branch for V2's `continue` (or vice versa)
  >   would leave both tests green. Split into two pairs: the
  >   `_stopExecutionExitsRunLoop` tests still verify orderly shutdown;
  >   the new `_loneInterrupt*` tests call `Thread.interrupt()` alone to
  >   pin the observable difference — V1 exits on a bare interrupt via
  >   its `break` path, V2 absorbs and stays alive until `stopped=true`.
  >   Added `awaitThreadState` helper that waits for `WAITING` state
  >   before probing so the interrupt never races thread-start.
  > - **V2 batch-dispatch non-falsifiable** (CQ1 / TB1 / TS2 / TC6 / TX2 —
  >   five-reviewer convergent): `v2_runLoopBatchesAndDispatchesToSubscribers`
  >   only asserted `received.contains(first)` and had a stale "drain up
  >   to one more batch" comment with no drain code; the second op's
  >   arrival and the single-batch collapse were both unverified.
  >   Rewrote to enqueue both ops before starting the consumer
  >   (deterministic single-take) and latch on both ops arriving; the
  >   new single-batch-size assertion pins the inner `queue.poll()`
  >   drain loop that batching relies on.
  > - **Dispatcher-survives-past-throw gap** (TC10 / TX4 / TS4): the V1
  >   throwing-listener test verified only that one op reached the good
  >   listener after a peer threw. Added a second `enqueue` + post-throw
  >   assertion so a regression that auto-deregisters the throwing listener
  >   or breaks the run loop after swallowing the exception is caught.
  >   Also added the missing `assertFalse(isAlive)` after `join(1000)`.
  > - **Clone/ops-lifecycle thread-leak hazard** (BC1): tests that
  >   constructed `LiveQueryQueueThread` / `LiveQueryQueueThreadV2`
  >   without calling `stopExecution`+`join` in a `finally` would leak
  >   hung daemon threads into the surefire JVM if a future mutation
  >   auto-started the dispatcher at construction — exactly the kind of
  >   regression the pin is designed to catch. Added defensive cleanup
  >   to `v1_cloneSharesQueueAndSubscribers`, `v2_cloneSharesOpsReference`,
  >   `v2_opsSubscribersLifecycle`. `v2_cloneSharesOpsReference` now also
  >   actually observes the shared `ops` reference by starting the clone
  >   (not the original) and asserting that an op enqueued on the shared
  >   ops reaches the clone's dispatcher.
  > - **Missing static-entry-point happy paths** (TC1 / TC2 / TC3 / TC4 /
  >   TC5 — completeness gaps flagged as should-fix): initial pass only
  >   covered disabled-support short-circuits. Added three tests —
  >   `v2_subscribeThroughStaticEntryPointRegistersListenerAndReachesAutoStart`
  >   exercises the `synchronized(ops.threadLock)` clone+start branch;
  >   `v2_notifyForTxChangesDrainsPendingIntoQueueAndClearsMap` drives
  >   the drain-into-queue branch past the empty-fast-return; and
  >   `v2_addOpUpdatedTwiceOnSameEntityMergesInPlace` pins the UPDATED-
  >   dedup branch in `addOp` (second UPDATED on same entity instance
  >   merges in place, not a second pending entry).
  > - **Closed-session pin weak + V2 sibling missing** (CQ3 / TB8 / BC5):
  >   `v1_removePendingDatabaseOpsOnClosedSessionIsNoOp` only asserted
  >   "no throw" — a regression that removed the `isClosed()` guard would
  >   reach the `DatabaseException` catch and silently swallow the
  >   failure. Snapshot `pendingOps.size()` before the call and assert
  >   it is unchanged after. Moved `activateOnCurrentThread` into
  >   `finally` so the `@After` safety net runs cleanly even if the
  >   call throws. Added V2 sibling.
  > - **Known-bug pin had dead state + vacuous metadata assertion** (CQ2 /
  >   TS1 / BC2 / TB5): `v2_calculateProjectionsAlwaysEmptyOrNull_knownBug`
  >   had a `captured` list populated with `addAll(List.of(iRecords))`
  >   (wraps in a single-element list — silently broken semantics) but
  >   never asserted. Removed the dead state, replaced listener body with
  >   no-ops + explanatory comment. Added `@rid` and `@version` metadata
  >   assertions so a regression in `calculateAfter`'s unconditional-
  >   metadata branch is distinguished from a fix to the buggy property-
  >   filter loop. `@version` assertion uses `committedVersion + 1` to
  >   pin the documented off-by-one semantics.
  > - **Zero-impl interface pins vacuous** (TB7): `BasicLiveQueryResult
  >   Listener` and `LiveQueryResultListener` tests previously did only
  >   `assertNotNull(anon)`, which cannot fail for a `new` expression.
  >   Both now invoke all five callbacks and assert the invocation
  >   count — so a mutation promoting a method to `default` (silently
  >   hiding a production impl gap) is caught.
  > - **Identity-vs-value assertion weaknesses** (TB3 / TB6): `subscribe`
  >   return-value pin now uses `Integer.valueOf(5000)` (outside JDK
  >   Integer cache) + `assertSame`. `unboxRidbags` passthrough tests
  >   use `new String(...)` and `Integer.valueOf(200)` so `assertSame`
  >   is a real identity check, not a pool/cache artefact.
  > - **Fresh-copy pin incomplete** (BC6): `linkBagResultIsFreshCopy`
  >   asserted only bag size after `list.clear()` — a future aliasing
  >   regression that shared storage with a size cache would pass.
  >   Strengthened to also iterate the bag and assert the original entry
  >   is present, and to assert the returned list is a concrete
  >   `ArrayList` per the production allocation.
  > - **Silent-overwrite pin added** (TC7): added
  >   `v1_subscribeWithExistingTokenSilentlyOverwritesReplacedListener`
  >   documenting the pre-existing `ConcurrentHashMap.put` behaviour —
  >   a second subscribe to the same token drops the previous listener
  >   without calling its `onLiveResultEnd`.
  > - **Cosmetic fixes**: split the combined `instanceof && isEmpty()`
  >   assertion in `linkBagEmptyProducesEmptyList` so the failure
  >   message identifies which half failed (CQ6 / TB4). Fixed the
  >   malformed `{@link Timeout(15)}` Javadoc reference (CQ4 / TS3).
  >
  > **Remaining findings deferred as merge-ready / Track-22 scope**
  > (suggestion-grade, not fixed in-step):
  > - CQ5 — `v1_` / `v2_` / `deadInterface_` underscore-prefix naming
  >   diverges from Track 7/9's pure camelCase convention. Intentional
  >   readability aid for paired V1/V2 tests.
  > - CQ7 — Track 7/8 `executeInTx` callback idiom for begin/commit
  >   pairs. The `rollbackIfLeftOpen` safety net covers the exceptional
  >   path; Track 10's own Phase A queued `DepthFetchPlanTest`
  >   modernization (T10) for Step 3 / Track 22.
  > - CQ8 / TS6 — extract `CollectingV1Listener` / file-local listener
  >   factories to deduplicate ~80 LOC of anonymous-class ceremony.
  >   Deferred to Track 22's DRY sweep.
  > - TC9 — happy-path `removePendingDatabaseOps` duplicate of TC2's
  >   `notifyForTxChanges` workflow; TC2 covers the same drain path.
  > - TC11 — defensive `@After` asserting `QUERY_LIVE_SUPPORT == true`.
  >   `DbTestBase` creates a fresh database per `@Test` so the flag
  >   is session-scoped — no cross-test leak risk in practice.
  > - TC12 — classpath scan for zero-impl interfaces. Needs classgraph
  >   dependency; current WHEN-FIXED textual markers follow project
  >   precedent.
  > - TS5 — `CollectingV2Listener.batches` parameter never asserted by
  >   any call site. Cosmetic — helper stays useful even with the
  >   unused arg.
  > - TS8 — rename `deadInterface_*` tests. Cosmetic.
  > - TS9 — comment at subscribe-before-disabled-flag-flip explaining
  >   intent. Self-evident on reading the production `addOp` guard.
  > - TX5 — unbounded `join()` in `@After` if a test's dispatcher leaks.
  >   Speculative — no observed hang in the test suite; address if
  >   surefire regression is seen.
  > - BC3 / BC4 — `notifyForTxChangesWithLiveSupportDisabled` leaves
  >   pending-ops dirty; additional `calculateBefore` mutated-entity
  >   scenarios. Dirty state cleaned by `DbTestBase.afterTest`'s drop
  >   of the database; enhanced mutation scenarios deferred to Track 22
  >   along with the `calculateProjections` bug fix.
  >
  > All 57 tests in the three live-query classes pass:
  > `LiveQueryDeadCodeTest` (25), `LiveQueryHookStaticApiTest` (23),
  > `LiveQueryHookV2UnboxRidbagsTest` (9). Spotless clean. No production
  > code changed in this step.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryDeadCodeTest.java` (new in `f57732f51c`, updated in `9d09fcde01`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryHookStaticApiTest.java` (new in `f57732f51c`, updated in `9d09fcde01`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryHookV2UnboxRidbagsTest.java` (new in `f57732f51c`, updated in `9d09fcde01`)
  >
  > **What changed from the plan:**
  > No structural deviation. Step-brief decomposition (zero-impl interface
  > pins, V1/V2 dispatcher shape pins, thread-start path + V1/V2 interrupt
  > pins, LinkBag unbox + non-LinkBag passthrough) all landed.
  > Iter-1 review added 4 happy-path tests (static subscribe, notifyForTxChanges
  > drain, UPDATED-dedup, closed-session V2 sibling) and 1 token-collision
  > pin that Phase A's R4 prompt had not anticipated — these fall within
  > Step 2's scope (live-query dead-code + static API) and do not affect
  > Steps 3–4. Cross-track impact: **Continue** — none of the findings or
  > fixes weaken assumptions for Tracks 11–21 or Track 22.

- [x] Step 3: FetchPlan parser (standalone) + FetchHelper dead-code pins + DepthFetchPlanTest modernization
  - [x] Context: warning
  > **What was done:**
  > Landed 3 new standalone test files plus modernized `DepthFetchPlanTest`
  > across two commits:
  > - `7019f638d7` — initial Step-3 implementation: `FetchPlanParserTest`
  >   (30 tests; constructor branches + error paths, `getDepthLevel` in-range
  >   and out-of-range branches, `has()` semantics), `FetchHelperDeadCodeTest`
  >   (28 tests; `buildFetchPlan` null / singleton / custom branches,
  >   `checkFetchPlanValid` null / empty / whitespace / multi-entry / extra-colon
  >   boundaries, null-plan and DEFAULT-plan early returns in
  >   `processRecordRidMap`, protected `removeParsedFromMap` helper pinning),
  >   `RemoteFetchContextTest` (3 tests; null-arg no-op sweep, peer-dispatch
  >   drift pin via a MethodCounters subclass, `fetchEmbeddedDocuments=false`
  >   contract), and modernized `DepthFetchPlanTest` (rebased on
  >   `TestUtilsFixture`, ported begin/commit → `executeInTx`/`computeInTx`
  >   callbacks, added four new scenarios: cycle-dedup via parsedRecords,
  >   shallow-format suppression, null-plan no-sendRecord, default-plan
  >   sendRecord bypass).
  > - `c1360eaa55` — dimensional review fix pass (4 agents: CQ / BC / TB / TC).
  >   Applied the convergent should-fix items: tightened `count <= 1` →
  >   `count == 0` on the null-plan test (three-reviewer convergent CQ3 / BC1 /
  >   TB1), deleted four tautological sanity/helper tests on fastutil and the
  >   file-local stub (CQ2 / TB2), replaced FQN ceremony in `MethodCounters`
  >   with imports (CQ1), reworded brittle line-number comments (CQ4), renamed
  >   `noCallbackInSuperTriggersPeerCallback` → `superMethodsDoNotDispatch...`
  >   (CQ7), switched `assertFalse(a == b)` to `assertNotSame` (CQ6), weakened
  >   the "prevents .equals mutation" claim on the DEFAULT_FETCHPLAN guard
  >   (TB4 — FetchPlan inherits Object.equals so the mutation is
  >   indistinguishable on this input), strengthened
  >   `checkFetchPlanValidAcceptsEveryStringThatBuildFetchPlanAccepts` with a
  >   per-sample `SemanticProbe` so each input pins a specific depth bookkeeping
  >   observable (TB5), dropped the dead null-check in `fetchAndCount` (BC3).
  >
  > **What was discovered:**
  > Iteration-1 dimensional review surfaced convergent observations about
  > test-code quality:
  > - **Non-falsifiable range assertion** (three-reviewer convergent CQ3 /
  >   BC1 / TB1): the null-plan test used `assertTrue(count <= 1)` which
  >   accepted both 0 and 1 — two outcomes of different production
  >   behaviours. Tracing through `FetchHelper.fetch` with a null plan
  >   confirms `processRecordRidMap` short-circuits on the null guard so
  >   `parsedRecords` contains only the root; `fetchEntity` then sees
  >   `fieldDepthLevel=-1` and takes the else branch (`parseLinked`, no-op)
  >   rather than `fetchLinked` (which would `sendRecord`). Count is
  >   deterministically 0. A mutation that pre-populates `parsedRecords` for
  >   null plans would have been invisible to the loose pin.
  > - **Four tautological tests** (CQ2 / TB2 convergent): `fixedIdentifiable
  >   ReturnsProvidedRid`, `fixedIdentifiableCompareToDelegatesToRid`, and
  >   two `sanityCheckParsedRecordsMap*` tests exercised either the file-
  >   local `FixedIdentifiable` stub or fastutil's `Object2IntOpenHashMap`
  >   sentinel — not production code. Their pass/fail outcome is independent
  >   of any mutation to `FetchHelper`. Deleted; the stub is transitively
  >   validated by `removeParsedFromMap*` tests.
  > - **Line-number references in comments** (CQ4): "`The first guard at
  >   line 131 early-returns…`" is brittle — any edit above line 131 in
  >   FetchHelper.java silently invalidates the doc. Rewrote to reference
  >   the condition by name.
  > - **Misleading trace comments** (TB3): comments narrated the wrong
  >   branch ("the part-equality block is SKIPPED by design" where actually
  >   the whole in-range block is skipped). Reworded to match the actual
  >   evaluation path.
  > - **Singleton-vs-equals mutation claim unobservable** (TB4): the
  >   DEFAULT_FETCHPLAN guard pin claimed to catch a future `==` → `.equals`
  >   mutation, but `FetchPlan` inherits `Object.equals` so the mutation is
  >   indistinguishable on this input. Weakened the claim to what the test
  >   actually verifies (the guard fires before the null-record walk).
  > - **FQN ceremony in MethodCounters** (CQ1): every `@Override` parameter
  >   listed its full package path, ballooning signatures to 4–6 lines each.
  >   Imports reduce to 1–2 lines per signature — a pure readability win.
  >
  > **Remaining should-fix items deferred to Track 22 scope** (with
  > explicit step-plan authorization, "absorb residual into Track 22 if
  > scope tight"):
  > - **TC1** — `FetchHelper.isEmbedded` positive branch (embedded
  >   EntityImpl), LinkBag short-circuit, and `MultiValue.getFirstValue`
  >   exception-swallow branches untested. Each requires a live session
  >   (EntityImpl construction) or a LinkBag stub + schema — beyond the
  >   standalone-test scope of this step. Track 22 entry suggested
  >   wording: "Complete `FetchHelper.isEmbedded` branch coverage —
  >   embedded-EntityImpl (via `session.newEmbeddedEntity()`), LinkBag
  >   short-circuit (requires schema/session), and exception-swallow
  >   through `MultiValue.getFirstValue` via a throwing Iterable stub".
  > - **TC2** — `DepthFetchPlanTest` missed five step-plan scenarios
  >   (map-of-links, array-of-links, embedded-with-nested,
  >   `RecordNotFoundException` mid-fetch, explicit depth-cutoff). Two of
  >   these (map-of-links / `fetchMap`, RNF / `fetchCollection`'s catch at
  >   line 914) are the ONLY reachable entry points for those production
  >   branches in core/fetch/. Step-plan's "absorb into Track 22" clause
  >   applies. Attempted `mapOfLinksIsFetchedForEachEntry` during initial
  >   implementation but it required deeper setup (LinkMap value-binding
  >   behaviour under `processRecordRidMap`'s traversal was subtle and
  >   produced count=0 instead of the expected 3); root-caused enough to
  >   know the fix requires either schema-declared `LINKMAP`-typed property
  >   or a longer investigation outside Step 3's scope. Track 22 entry
  >   suggested wording: "Add `DepthFetchPlanTest` scenarios for
  >   map-of-links (plan `mapField:1 *:-2` with `PropertyType.LINKMAP`-
  >   declared field), array-of-links (`PropertyType.LINKLIST`), and
  >   `RecordNotFoundException` mid-fetch (delete linked record within
  >   active tx — exercises the catch at FetchHelper.java:914)".
  >
  > **Suggestion-tier items** (merged implicitly into Track 22's DRY
  > sweep): CQ5 commit-message "this track's review phase" leak (noted;
  > amendment discouraged so deferred as precedent correction in future
  > commits); CQ8 `throws FetchException` declaration nit; CQ9 WHEN-FIXED
  > marker slightly overstates dead status for `FetchPlan`; CQ10 license
  > banner cross-step inconsistency; BC2 misleading "same-cluster compare"
  > test-message; TC3 FetchPlan empty-`from` / bare-`-` range boundaries;
  > TC4 whitespace-padded `checkFetchPlanValid` cases; TC5 compound
  > first-good-then-bad plan error; TC6 `has("", 0)` empty-path probe
  > against prefix-map; TC7 `removeParsedFromMap(parsed, null)` NPE
  > contract; TC8 non-null-arg invocation of `RemoteFetchContext`
  > callbacks.
  >
  > Iter-2 gate check deferred — session context reached `warning` at 33%
  > after iter-1 fixes. The iter-1 finding set was fully dispositioned
  > (should-fix applied in commit `c1360eaa55`, deferrals noted above).
  >
  > All 63 tests in the four affected classes pass:
  > `FetchPlanParserTest` (30), `FetchHelperDeadCodeTest` (24),
  > `RemoteFetchContextTest` (3), `DepthFetchPlanTest` (6). Spotless clean.
  > No production code changed in this step.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/fetch/FetchPlanParserTest.java` (new in `7019f638d7`, updated in `c1360eaa55`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/fetch/FetchHelperDeadCodeTest.java` (new in `7019f638d7`, updated in `c1360eaa55`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/fetch/remote/RemoteFetchContextTest.java` (new in `7019f638d7`, updated in `c1360eaa55`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/fetch/DepthFetchPlanTest.java` (modified in `7019f638d7`, updated in `c1360eaa55`)
  >
  > **What changed from the plan:**
  > Two scenarios from the step plan's fixture list (map-of-links,
  > RecordNotFoundException mid-fetch) + three optional scenarios
  > (array-of-links, embedded-with-nested, explicit depth-cutoff) deferred
  > to Track 22 per the step-plan's explicit "absorb into Track 22 if
  > scope tight" authorization. Four alternative scenarios (cycle-dedup,
  > shallow-format, null-plan, default-plan bypass) landed in this step —
  > together they pin the structural traversal behaviours that the
  > standalone test files cannot reach (cycle detection via parsedRecords,
  > shallow-format short-circuit, null/default plan early-return paths).
  > Cross-track impact: **Continue** — none of the findings or deferrals
  > weaken assumptions for Tracks 11–21 or Track 22; the deferrals simply
  > expand Track 22's scope (already absorbing five `core/fetch/` dead-code
  > deletions), which is in-scope for the final sweep track.

- [x] Step 4: Verification + track-10-baseline update
  - [x] Context: info
  > **What was done:**
  > Track 10 verification pass — no code changes, no new commits. Ran
  > the four gates defined for this step and updated
  > `track-10-baseline.md` with the per-class / per-package aggregate
  > coverage delta (under `## Post-Step-4 coverage (verified)`):
  > - **Coverage build**: `./mvnw -pl core -am clean package -P coverage`
  >   — BUILD SUCCESS. 1,657 unit tests ran (13 skipped per environment
  >   guards) with 0 failures / 0 errors across `core/src/test`. JaCoCo
  >   report written to `.coverage/reports/youtrackdb-core/jacoco.xml`.
  > - **Coverage gate**: `python3 .github/scripts/coverage-gate.py
  >   --line-threshold 85 --branch-threshold 70 --compare-branch
  >   origin/develop --coverage-dir .coverage/reports` — PASSED
  >   at 100.0% line (6/6) + 100.0% branch (2/2) on changed production
  >   lines. The tiny denominator is expected: Track 10 is purely
  >   test-additive (4,574 added LOC across 13 files, 100% of which are
  >   in `core/src/test`), so the production-code delta vs. develop is
  >   minimal.
  > - **Spotless**: `./mvnw -pl core spotless:check` clean (1,048 files,
  >   0 changes needed).
  > - **WHEN-FIXED audit**: greped all new test files for `WHEN-FIXED`
  >   markers. Every Track-22-tagged marker maps to one of the six
  >   absorption items already recorded in Track 22's block in
  >   `implementation-plan.md` (entire `core/query/live/` package,
  >   orphan listener interfaces in `core/query/`, entire `core/fetch/`
  >   package, `calculateProjections` bug, `ExecutionStep.java:41`
  >   duplicate-`getSubSteps` cleanup, V1/V2 interrupt-handling
  >   reconciliation, `DepthFetchPlanTest` modernization). Two markers
  >   are behavioral-shape pins ("WHEN-FIXED: if X is changed to Y" on
  >   `ExecutionStepToResultTest.testToResultInvokesGetSubStepsTwicePerStep`
  >   and `BasicResultSetDefaultMethodsTest.testToListDoesNotAutoCloseUnderlyingStream`)
  >   — these document a future-fix flip point rather than gating on a
  >   Track 22 deletion, so no Track 22 entry is required.
  >
  > **What was discovered:**
  > Per-class aggregates pulled from the fresh `jacoco.xml` and recorded
  > in `track-10-baseline.md`. Every live-code target in scope met or
  > exceeded 85% line / 70% branch:
  > - `BasicResultSet` 100% / 100% (default methods, via standalone stub)
  > - `QueryHelper` 95.7% / 75.0% (live subset; `like` + locale corners)
  > - `QueryRuntimeValueMulti` 100% / 100%
  > - `ExecutionStep` 100% (default `toResult`; no branches)
  > - `LiveQueryQueueThread` 97.4% / 90.0% (V1 dispatcher, pinned dead)
  > - `LiveQueryQueueThreadV2` 85.4% / 75.0% (V2 dispatcher, pinned dead)
  > - `LiveQueryHookV2` 79.5% / 72.7% class aggregate — `unboxRidbags`
  >   live path + pinned dead static surface
  > - `FetchPlan` 99.0% / 89.5% (parser + `has` + `getDepthLevel`)
  > - `RemoteFetchContext` 100% (no branches)
  >
  > Pinned dead classes hold the package aggregates below target — as
  > expected. Track 22 deletes them, at which point the denominator
  > shrinks and aggregate package coverage rises naturally. Specifically:
  > - `FetchHelper` 45.8% / 33.7% (entire class pinned dead; 165/360
  >   lines exercised via `DepthFetchPlanTest` + `FetchHelperDeadCodeTest`
  >   boundary probes — enough for falsifiable pins, not enough to hit
  >   the gate thresholds on the full surface).
  > - `LiveQueryHook` 51.5% / 54.2% (entire public-static surface pinned;
  >   live subset covered via `LiveQueryHookStaticApiTest`).
  > - `RemoteFetchListener` 41.2% / 0.0% (7/17 lines, 0/4 branches — a
  >   no-op callback shell that `RemoteFetchContextTest` exercises only
  >   at the `RemoteFetchContext` seam. Deletion scheduled in Track 22
  >   alongside `core/fetch/remote/`).
  > - `Result.java` 32.8% / 26.2% at the class level — live via
  >   query/fetch flows; `ResultDefaultMethodsTest` targets only the
  >   entity/vertex/edge dispatch subset. Broader `Result` coverage is
  >   out of scope for Track 10 and belongs to a later track that
  >   targets `core/query/Result` comprehensively.
  >
  > No new cross-track impact surfaced. Assumptions for Tracks 11–21
  > hold; Track 22 absorption list already reflects every WHEN-FIXED
  > marker this track planted.
  >
  > **Key files:**
  > - `docs/adr/unit-test-coverage/_workflow/track-10-baseline.md` (updated —
  >   added `## Post-Step-4 coverage (verified)` section with per-class,
  >   pinned-dead, and package-aggregate tables + `## Provenance` block)
  >
  > **What changed from the plan:**
  > No deviation from the Step 4 brief. All four verification sub-tasks
  > (coverage analyzer, WHEN-FIXED audit, full core suite, spotless)
  > ran cleanly. Cross-track impact: **Continue** — Track 10 closes
  > without altering any assumption in Tracks 11–21; Track 22's
  > absorption list already reflects every dead-code pin and
  > production-bug WHEN-FIXED marker planted in Steps 1–3.

