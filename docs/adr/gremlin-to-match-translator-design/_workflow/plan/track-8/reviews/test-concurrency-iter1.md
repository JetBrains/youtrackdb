<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: TX3, sev: should-fix, loc: MultiPlanMatchStepTest.java:562, anchor: "### TX3 ", cert: C1, basis: "post-concat pipeline (c6c66a62b9) has zero unit-level tests; the sole concurrency test drives the no-ops ELEMENT path, so the per-arming dedup/count accumulators have no falsifiable cross-clone isolation check"}
  - {id: TX4, sev: should-fix, loc: GremlinToMatchStrategyTest.java:899, anchor: "### TX4 ", cert: C2, basis: "per-child GremlinPlanCache publication (5c3ed9da5a) has no multi-threaded test; one planningStart snapshot governs N per-child put guards, and the nearest concurrency test races only contains()/invalidate()"}
  - {id: TX5, sev: suggestion, loc: MultiPlanMatchStepTest.java:673, anchor: "### TX5 ", cert: C3, basis: "concurrent clone test parents each isolated context to a Mockito CommandContext, so `parent instanceof BasicCommandContext` is false and the shared-parent up-propagation race clone() names is structurally unreachable"}
  - {id: TX6, sev: suggestion, loc: MultiPlanMatchStep.java:269, anchor: "### TX6 ", cert: C4, basis: "count push-down builds its own child open/close loop instead of MultipleExecutionStream; the one-live-stream and exception-stops-advance invariants are pinned only on the other branch"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED,   anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX3 [should-fix] The post-concat pipeline has no concurrency coverage, and no unit coverage at all

`c6c66a62b9` added four new mutable-state carriers to `MultiPlanMatchStep`'s stream construction. The track's single concurrency test builds the step with an empty op list, so none of them is ever driven by two threads — and no unit test names `PostConcatOp` at all.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java`, method `clone_concurrentDrives_noCrossCloneVariableBleed` (line 562), via the `elementStep(...)` helper (line 659)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` — `startPlanStream()` (line 229) and the state it mints per arming: `dedupConcatStream`'s `final Set<Object> seen = new HashSet<>()` (line 410), `countConcatStream`'s `pending` / `computed` / `total` (line 359), `SkipExecutionStream.skipped` (line 423), `sumChildCountStreams`'s `pending` / `computed` (line 269)
- **Issue**: `elementStep(...)` calls the five-argument constructor, which forwards `List.of()` for `postConcatOps`. Every test in the class — including the only multi-threaded one — therefore takes the `postConcatOps.isEmpty()` path through `startPlanStream()`: plain `MultipleExecutionStream`, no dedup set, no count accumulator, no skip counter. `grep -rn "PostConcatOp" core/src/test/java/` returns nothing, so `getPostConcatOps()` is never asserted either, not even after `clone()`. The only exercise of these ops anywhere is `UnionTraversalEquivalenceTest` (`unionThenCount_...` line 141, `unionThenDedup_...` line 155, `unionThenLimit_...` line 170), which is single-threaded end to end.
- **Evidence**: C1 (gap), C5 (the refutation that establishes the code is currently correct, so this is a coverage finding rather than a defect)
- **Why it matters**: `seen` is the state most likely to migrate. Making dedup survive a `reset()` + re-arm is a natural follow-up, and the one-line way to do it is to hoist `seen` from a `startPlanStream()` local to a field. That field is then shared by every arming of a step and, after `clone()` copies it by reference through `super.clone()`, by every concurrent clone. Clone B silently drops the rows clone A already emitted — wrong results, no exception, no hang. The whole suite stays green: the concurrency test never carries a `Dedup` op, and `unionThenDedup_returnsSameMultisetAsNative` runs one traversal on one thread. Track 8 took the `concurrency` category specifically for the multi-alias clone surface (risk R2); the surface grew after the step-level review ran and the coverage did not follow.
- **Suggested test**: add a `dedupStep(...)` helper alongside `elementStep(...)` that passes `List.of(PostConcatOp.Dedup.INSTANCE)`, then drive two clones over the same row identity. Both clones must emit the row, because each owns its own `seen`.
  ```java
  @Test
  public void clone_concurrentDrivesWithDedup_eachCloneKeepsItsOwnSeenSet() throws Exception {
    // dedupConcatStream keys on result.getEntity("v").getIdentity(), so both clones must be fed
    // rows carrying the SAME identity: with a per-arming set both clones emit one row; with a
    // shared set the loser of the race emits zero.
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 200; i++) {
        var sharedIdentity = rawVertexWithIdentity("#12:0");
        var c1 = child(ListStream.of(vertexRow(sharedIdentity)));
        var c2 = child(ListStream.of(vertexRow(sharedIdentity)));
        // Echo the isolated context back, as recordProbeCopy already does, so each clone drives
        // its own copies against its own context.
        when(c1.plan.copy(any()))
            .thenAnswer(inv -> copyYielding(inv.getArgument(0), sharedIdentity));
        when(c2.plan.copy(any()))
            .thenAnswer(inv -> copyYielding(inv.getArgument(0), sharedIdentity));

        var original = dedupStep(c1, c2); // List.of(PostConcatOp.Dedup.INSTANCE)
        var cloneA = original.clone();
        var cloneB = original.clone();
        cloneA.setTraversal(traversal);
        cloneB.setTraversal(traversal);

        var barrier = new CyclicBarrier(2);
        var emitted = new CopyOnWriteArrayList<Integer>();
        Future<?> fa = pool.submit(countEmittedRows(cloneA, barrier, emitted));
        Future<?> fb = pool.submit(countEmittedRows(cloneB, barrier, emitted));
        fa.get(5, TimeUnit.SECONDS);
        fb.get(5, TimeUnit.SECONDS);

        assertThat(emitted)
            .as("each clone dedups against its own seen set, so both emit exactly one row")
            .containsExactly(1, 1);
      }
    } finally {
      pool.shutdownNow();
    }
  }
  ```
  A cheap structural companion, worth adding regardless: `assertThat(original.clone().getPostConcatOps()).isEqualTo(original.getPostConcatOps())`.

### TX4 [should-fix] Per-child plan-cache publication has no multi-threaded test

`5c3ed9da5a` made every union child a separate `GremlinPlanCache` get/put under one `planningStart` snapshot. Both tests for it are single-threaded, and the repository's only plan-cache concurrency test deliberately avoids the two methods this path calls.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategyTest.java`, methods `apply_multiPlanWithProductionBuilder_cachesEligibleChildren` (line 899) and `apply_multiPlanRidBearingChild_bypassesPlanCache` (line 934)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategy.java` — `buildChildPlans` (lines 443-478), the per-child `planBuilder.buildPlan(...)` call at line 471, and the guard it reuses N times at line 429 (`GremlinPlanCache.getLastInvalidation(session) < planningStart`, with `planningStart` captured once at line 260)
- **Issue**: `buildChildPlans` walks the child list and calls `buildPlan` once per child, passing the same `planningStart` every time. That value is a time-of-check snapshot taken before the walk; it is used N times, at N different instants, to decide whether each child's plan may be published. Neither new test drives a second thread, so no test constructs a mid-loop invalidation. The nearest concurrency test in the repo, `GremlinPlanCacheTest.concurrentContainsAndInvalidate_neverThrowsAndKeepsCountersNonNegative` (line 323), races only `contains()` and `invalidate()`; its own Javadoc says it "complements the `buildPlan` concurrent-invalidation guard" rather than exercising it, and it never calls `get()` (which does `template.copy(ctx)` on the shared cached instance) or `put()` (which does `plan.copy(copyCtx)` then `plan.close()`).
- **Evidence**: C2 (gap), C6 (the refutation ruling out the adjacent shared-fixture theory, so this finding rests on the build loop rather than on test-harness sharing)
- **Why it matters**: two harmful interleavings, neither reachable from any existing test.

  Mixed-snapshot union. T1 builds child 0, misses, builds it against the current schema, finds `lastInvalidation < planningStart`, publishes it. T2 commits a DDL, which bumps `lastInvalidation` and clears the cache. T1 builds child 1, misses, builds it against the *new* schema, finds the guard false, skips the put, and returns. `replaceAllStepsWithBoundary` (line 541) then splices one `MultiPlanMatchStep` whose arm 0 was compiled against the pre-DDL schema and whose arm 1 was compiled against the post-DDL schema. The single-plan path cannot produce that shape — it has exactly one plan, so it is stale or fresh, never both.

  Concurrent copy of one shared template. `GremlinPlanCache.get` returns `cachedTemplate.copy(ctx)` on an instance shared by every session on the database. Union is now the highest-fanout consumer of that call: one traversal issues N of them, and two children of one union can share a fingerprint — `apply_multiPlanWithProductionBuilder_cachesEligibleChildren` itself passes `List.of(childInputs, childInputs)`, so a single build does `put(fp)` and then `get(fp)` against the same entry. Nothing anywhere drives two threads through `get()` concurrently.
- **Suggested test**: two tests in `GremlinToMatchStrategyTest` (JUnit 4, core module). The first pins the mixed-snapshot decision by injecting a plan builder that parks between children while a second thread invalidates.
  ```java
  @Test
  public void multiPlanBuild_invalidationBetweenChildren_neverPublishesAPreDdlChild()
      throws Exception {
    var cache = GremlinPlanCache.instance(session());
    cache.invalidate();
    var childBuilt = new CountDownLatch(1);
    var invalidated = new CountDownLatch(1);
    var strategy =
        new GremlinToMatchStrategy(
            t -> translation,
            (s, tr, planningStart) -> {
              var plan = GremlinToMatchStrategyTest.productionBuild(s, tr, planningStart);
              if (childBuilt.getCount() > 0) {
                childBuilt.countDown();          // child 0 is published
                await(invalidated);              // hold until the DDL lands
              }
              return plan;
            });

    var ddl = new Thread(() -> {
      await(childBuilt);
      cache.invalidate();                        // the concurrent DDL
      invalidated.countDown();
    });
    ddl.start();
    strategy.apply(graph.traversal().V().asAdmin());
    ddl.join(5_000);
    assertThat(ddl.isAlive()).as("no hang in the DDL thread").isFalse();

    assertThat(cache.contains(childZeroFingerprint))
        .as("a child published before the invalidation must not survive it")
        .isFalse();
  }
  ```
  The second drives eight threads through `GremlinPlanCache.get` for one warm fingerprint and asserts every returned plan is a distinct instance carrying the caller's own context — the property `buildChildPlans` relies on when it installs per-child parameters onto `childPlan.getContext()` at line 473. `GremlinPlanCacheTest`'s existing test already documents the constraint to work within: the graph session is thread-affine, so warm the entry and compute the fingerprint on the main thread, then let the workers touch only the cache.

### TX5 [suggestion] The concurrent clone test parents its isolated contexts to mocks, so the shared-parent race is unreachable

`clone()` names up-propagation into the shared template context as the real cross-clone hazard, and the concurrency test cannot execute that branch: `BasicCommandContext` gates it on `parent instanceof BasicCommandContext`, and every parent in the test is a Mockito `CommandContext`.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java`, the `child(ListStream)` helper (line 673), consumed by `clone_concurrentDrives_noCrossCloneVariableBleed` (line 562)
- **Production code**: `MultiPlanMatchStep.clone()` (line 158), specifically `isolatedCtx.setParentWithoutOverridingChild(templateContext)` (line 194) and the invariant comment above it; `BasicCommandContext.setVariable` (lines 291-306) and `hasVariable` (lines 310-316)
- **Issue**: `child(...)` stubs `plan.getContext()` with `mock(CommandContext.class)`. `clone()` parents each fresh `BasicCommandContext` to that mock. `setVariable` only writes through to the parent when `parent instanceof BasicCommandContext && parent.hasVariable(name)` (line 294), and `getVariableFromParentHierarchy` (line 255) has the same `instanceof` gate. A mock of the `CommandContext` interface satisfies neither, so during the concurrent drive `VariableProbeStream`'s write and read both stay inside the isolated child context's own map. In production the parent is a real `BasicCommandContext` and it *is* shared: clone A's child-0 copy and clone B's child-0 copy are parented to the same `childPlan.getContext()`.
- **Evidence**: C3 (gap), C7 (the refutation confirming the test's synchronization is sound, so this is about reach rather than a race in the harness)
- **Why it matters**: the test is falsifiable for the failure it was rewritten to catch — a `clone()` that minted one context instead of two makes both threads write the same map and records a mismatch. It is not falsifiable for the failure the surrounding comment and the fail-fast `assert` (line 187) are about, because that failure runs through the shared parent. The `assert` is the only production guard on that path, and assertions are disabled outside tests, so the hazard that survives into a production JVM is exactly the one the concurrency test structurally cannot reach.
- **Suggested test**: back the template context with a real `BasicCommandContext` so the branch is live, keeping the existing mock-based helper for the tests that need `getVariables()` to return null.
  ```java
  /** Like child(...) but with a REAL parent context, so BasicCommandContext's parent-write and
   *  parent-read branches (`parent instanceof BasicCommandContext`) are actually executed. */
  private Child childWithRealContext(ListStream stream) {
    var plan = mock(InternalExecutionPlan.class);
    var ctx = new BasicCommandContext();       // clean: clone()'s assert must still pass
    lenient().when(plan.getContext()).thenReturn(ctx);
    lenient().when(plan.start()).thenReturn(stream);
    return new Child(plan, ctx, stream);
  }

  @Test
  public void clone_concurrentDrives_realSharedParent_noUpPropagationBleed() throws Exception {
    // Same shape as clone_concurrentDrives_noCrossCloneVariableBleed, built from
    // childWithRealContext(...). Both clones' isolated contexts now share one real parent, so
    // VariableProbeStream's setVariable/getVariable traverse the parent hierarchy the production
    // comment calls the cross-clone race. A regression that seeds the template context (or drops
    // the assert that forbids it) surfaces here as a recorded mismatch under load.
  }
  ```

### TX6 [suggestion] The count push-down branch of `startPlanStream()` is untested on every lifecycle invariant the concurrency category was assigned for

`startPlanStream()` now has two branches. Every lifecycle test drives the `MultipleExecutionStream` one; the push-down branch runs its own hand-rolled child open/close loop and has no unit test.

- **File**: `MultiPlanMatchStepTest.java` — no test reaches the branch; the closest coverage is `UnionTraversalEquivalenceTest.unionThenCount_returnsSameTotalAsNative` (line 141), which asserts only the summed total against native
- **Production code**: `MultiPlanMatchStep.startPlanStream()` line 232 (`PostConcatOp.isPushDownCountOnly(postConcatOps)` → early return) and `sumChildCountStreams()` (lines 269-340), whose `ensure(ctx)` loops over `plans`, rebinds each child's context, opens `new ChildContextStream(childPlan.start(), childContext)`, drains it, and closes it in a `finally`
- **Issue**: the four tests that pin the invariants named in the track file — `processNextStart_opensChildLazily_secondChildNotStartedUntilFirstDrains` (line 129), `processNextStart_firstChildThrows_laterChildrenNeverStarted_allClosed_originalPrimary` (line 231 region), `close_afterNormalDrain_closesEveryChildPlanOnce` (line 292), `processNextStart_iteratesEachChildAgainstItsOwnContext` (line 160) — all build through `elementStep(...)`, so all take the `MultipleExecutionStream` path. `sumChildCountStreams` reimplements the same lifecycle by hand and inherits none of that coverage. Nor does the concurrency test: a clone of a count-shaped union takes this branch, and no clone test constructs one.
- **Evidence**: C4
- **Why it matters**: the track file lists these as the production invariants under review — one live child stream at a time, an exception in child N never opens N+1, `closePlan()` releases every child including the un-run ones. Half of the step's stream-construction surface now asserts none of them. `sumChildCountStreams` currently gets the ordering right, but a change to its `finally` placement or to the `computed = true` flag (set before the loop, so a throw leaves the stream permanently empty rather than retryable) would be caught by nothing.
- **Suggested test**: mirror the existing lifecycle quartet on a `countStep(...)` helper that passes `List.of(PostConcatOp.Count.INSTANCE)`, then add the clone variant.
  ```java
  @Test
  public void countPushDown_childThrows_laterChildrenNeverStarted_allClosed() {
    var c1 = child(ListStream.throwing(new RuntimeException("count child one blew up")));
    var c2 = child(ListStream.of(countRow(7L)));
    var c3 = child(ListStream.of(countRow(9L)));

    var step = countStep(c1, c2, c3); // List.of(PostConcatOp.Count.INSTANCE) → push-down branch

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("count child one blew up");

    verify(c2.plan, never()).start();
    verify(c3.plan, never()).start();
    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
    verify(c3.plan, times(1)).close();
  }

  @Test
  public void countPushDown_iteratesEachChildAgainstItsOwnContext() {
    // sumChildCountStreams wraps each child in ChildContextStream too; pin that the recorded
    // iteration and close contexts are the child's own, not the coordinator's.
  }
  ```
  A concurrent two-clone drive of `countStep(...)` — the TX3 skeleton with `Count.INSTANCE` in place of `Dedup.INSTANCE`, asserting both clones read the same total — closes the clone gap on this branch.

## Evidence base

Roster rendering per the output-routing spec: a claim that survived the Phase-3 refutation check (a genuine gap) is one line; a refuted claim is shown in full.

#### C1 CONFIRMED
TEST TRACE for the post-concat contract: `grep -rn "PostConcatOp" core/src/test/java/` returns zero hits, `elementStep(...)` (line 659) forwards the five-argument constructor which supplies `List.of()`, and `clone_concurrentDrives_noCrossCloneVariableBleed` builds through it — so `dedupConcatStream`'s `seen`, `countConcatStream`'s accumulator, `SkipExecutionStream.skipped`, and `sumChildCountStreams`'s `pending`/`computed` are NOT EXERCISED by any unit test and are unreachable from the only multi-threaded one. → TX3.

#### C2 CONFIRMED
TEST TRACE for the per-child cache contract: `buildChildPlans` calls `planBuilder.buildPlan` N times with one `planningStart`, and its two tests (lines 899, 934) run one thread each; `GremlinPlanCacheTest.concurrentContainsAndInvalidate_...` (line 323) races only `contains()`/`invalidate()` and states in its Javadoc that it complements rather than exercises the build-time guard — so the mid-build-invalidation and concurrent-`get()`-copy interleavings are NOT TESTED. → TX4.

#### C3 CONFIRMED
TEST RACE REACH for `clone_concurrentDrives_noCrossCloneVariableBleed`: `child(...)` (line 673) stubs `plan.getContext()` with `mock(CommandContext.class)`, and both `BasicCommandContext.setVariable` (line 294) and `getVariableFromParentHierarchy` (line 255) gate the parent traversal on `parent instanceof BasicCommandContext`, so the shared-parent write and read branches are dead code during the concurrent drive. → TX5.

#### C4 CONFIRMED
TEST TRACE for the push-down branch: `startPlanStream()` returns `sumChildCountStreams()` at line 233 whenever the op list is a lone `Count`, and no test in `MultiPlanMatchStepTest` constructs that op list, so the lazy-open, exception-stops-advance, close-all, and own-context invariants are pinned only on the `MultipleExecutionStream` branch. → TX6.

#### C5 REFUTED
Candidate finding: "`dedupConcatStream`'s `HashSet seen` is shared across clones or across armings — a live data race."

  - Construction site: `final Set<Object> seen = new HashSet<>()` sits inside the method body at `MultiPlanMatchStep.java:410`, not in a field. `dedupConcatStream` is reached only from `applyPostConcatOp` (line 342), which is reached only from the `for (PostConcatOp op : postConcatOps)` loop inside `startPlanStream()` (lines 259-261).
  - Call frequency: `AbstractMatchPlanStep.openArming()` (line 394) calls `startPlanStream()` exactly once per arming, and `processNextStart()` calls `openArming()` only in the `NEW` / `REARMED` states. A fresh set is therefore minted per arming, and a `reset()` + re-arm correctly restarts the dedup rather than inheriting it.
  - Clone: `postConcatOps` is `final` and holds immutable records (`PostConcatOp.Dedup` is a no-component record), so `super.clone()` copying the list reference shares nothing mutable. Each clone runs its own `startPlanStream()` and gets its own set.
  - Same reasoning holds for `countConcatStream`'s `pending`/`computed`, `SkipExecutionStream.skipped`, and `sumChildCountStreams`'s locals.
  VERDICT: REFUTED as a defect. The code is correct today. What survives is the coverage gap: nothing in the suite would fail if `seen` moved to a field, which is why TX3 is filed as missing coverage rather than as a race.

#### C6 REFUTED
Candidate finding: "The new tests race a shared `GremlinPlanCache` or a shared configuration under `<parallel>classes</parallel>` — `GremlinToMatchStrategyTest.apply_multiPlanWithProductionBuilder_cachesEligibleChildren` calls `cache.invalidate()` and then asserts `contains(fingerprint)`, while `GremlinPlanCacheTest` spawns eight threads that invalidate the cache 10 times each."

  - Surefire config: `core/pom.xml` lines 304-306 do set `<parallel>classes</parallel>` with `<threadCountClasses>4</threadCountClasses>`, and none of the five touched test classes carries `@Category(SequentialTest)`, so the two classes can genuinely run at the same instant.
  - Cache scoping: `GremlinPlanCache.instance(db)` resolves through `db.getSharedContext()`, which is per database. `DbTestBase` sets `dbPath = getBaseDirectoryPathStr(getClass())` (line 120) and creates the database under that per-class path, so each test class owns its own `YouTrackDBImpl`, its own `SharedContext`, and its own cache instance. The two classes cannot see each other's entries or counters.
  - Configuration scoping: `UnionTraversalEquivalenceTest.setTranslatorEnabled` and `GraphCountStrategyTest.withNonPolymorphicDefault` both go through a session's `ContextConfiguration` (`session.getConfiguration()` / `tx.getDatabaseSession().getConfiguration()`), not the JVM-global `GlobalConfiguration` static setter. Both restore the previous value in a `finally`. The pre-existing `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(...)` in `YTDBQueryMetricsStrategyTest` is a genuine global mutation, but that class lives under `**/gremlintest/**`, which the parallel execution excludes.
  VERDICT: REFUTED. No cross-class fixture race. TX4 rests on the `buildChildPlans` loop, not on harness sharing.

#### C7 REFUTED
Candidate finding: "`clone_concurrentDrives_noCrossCloneVariableBleed` is itself racy or can hang."

  - Thread coordination: `CyclicBarrier(2)`; `barrier.await()` is the first statement inside `drive(...)` (line 702), so both clones' open / start / drain paths overlap deterministically. A broken barrier throws into the `catch (Throwable)` and lands in `errors`.
  - Shared test state: `mismatches` and `errors` are `CopyOnWriteArrayList`; `createdCopies` is a plain `ArrayList` but is written only by `recordProbeCopy` (line 690) during `clone()` on the main thread, before either worker is submitted.
  - Assertion timing: `verify(...)` and `assertThat(...)` run after `futureA.get(5, TimeUnit.SECONDS)` and `futureB.get(...)`, both of which establish a happens-before edge, so Mockito's invocation records are read without a data race.
  - Hang handling: the timed `Future.get` converts a corrupted-`HashMap` spin into a `TimeoutException` that fails the test rather than blocking the run; `pool.shutdownNow()` in the `finally` releases the threads.
  - Falsifiability: `thenAnswer` echoing the `copy(...)` argument means two clones report one context only if `clone()` failed to isolate them, so a regression that shared a child context does produce a recorded mismatch.
  VERDICT: SOUND, not a finding. The harness synchronization is correct. TX5 is about what the test can reach, not about a race inside it.

## Reviewer notes

Scope: concurrency-testing quality only, over the cumulative range `8ce646b15cc..HEAD`. The step-level pass (`test-concurrency-step1-iter1.md`, TX1/TX2, both fixed in `8fa280b898`) covered Step 1 alone; this pass re-verified those two fixes as landed — `clone_givesEachCloneAndTheOriginalItsOwnCoordinatorContext` (line 478) is the reflective coordinator check TX2 asked for, and `clone_concurrentDrives_noCrossCloneVariableBleed` now drives real per-run writes through `VariableProbeStream` (line 852) rather than empty streams over stateless mocks, which answers TX1. Neither is re-reported. All four findings here sit on the four later commits (`660b3be634`, `d65317f54f`, `5c3ed9da5a`, `c6c66a62b9`, `0f7ecbb94c`), which no test-concurrency reviewer had seen.

Two categories of the review checklist are empty by construction and are not reported as gaps. The `MatchExecutionPlanner` / `HardwiredCountOptimizations` / `CountFromClassStep` work in `e3d460ef49` and `0f7ecbb94c` is planner-side and single-threaded per query; the YouTrackDB subsystem scenarios (cache page pinning, B-tree index contention, concurrent transactions, WAL writes, storage open/close) are untouched by this track. The translator's own recognition path — `GremlinStepWalker.production()` re-entered recursively through `UnionForkHostImpl.walkFork` — is stateless by construction (an immutable `Map.of(...)` registry of singleton recognisers, a fresh `WalkerContext` and a fresh `UnionForkHostImpl` per `walk()`), so it needs no concurrent test.

Tooling caveat: mcp-steroid PSI (`steroid_execute_code`) times out in this repository, so no find-usages ran. All four findings rest on the diff-visible bodies, direct declaration reads (`BasicCommandContext`, `AbstractMatchPlanStep`, `GremlinPlanCache`, `DbTestBase`, `core/pom.xml`), and exhaustive greps within `core/src/test`, not on caller or implementer enumeration. The one claim that would be weakened by a missed reference is C4's "no test in `MultiPlanMatchStepTest` constructs a lone-`Count` op list"; that rests on a repository-wide `grep -rn "PostConcatOp" core/src/test/java/` returning zero hits, which a symbol search cannot improve on for a type that appears nowhere in the test tree.
