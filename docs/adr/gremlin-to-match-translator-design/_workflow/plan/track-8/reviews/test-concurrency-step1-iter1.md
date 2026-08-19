<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: TX1, sev: should-fix, loc: MultiPlanMatchStepTest.java:475, anchor: "### TX1 ", cert: C1, basis: "concurrent two-clone test uses stateless mocks + a single empty drain, so the variable-bleed hazard it names is never exercised"}
  - {id: TX2, sev: should-fix, loc: MultiPlanMatchStep.java:178, anchor: "### TX2 ", cert: C2, basis: "fresh-coordinator-per-clone anti-race measure has no assertion that fails on regression"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED,   anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX1 [should-fix] Concurrent two-clone test does not exercise the variable-bleed hazard it names

The suite's only multi-threaded test runs two clones over stateless mocks and empty streams, so the cross-clone/parent variable bleed it claims to guard is never produced. It passes trivially.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java`, method `clone_twoClonesDrivenConcurrently_eachRunsOwnChildCopies` (line ~475)
- **Production code**: `MultiPlanMatchStep.java` `clone()` (lines ~152-190), specifically the per-child isolated-context deep-copy loop (lines ~173-181) whose stated purpose is preventing shared per-run state (`$current` / `$matched` / statistics `HashMap`s) between concurrent executions
- **Issue**: The child plans are Mockito mocks. `copy(any())` returns mock plans (`copy1A.plan` … `copy2B.plan`), and each mock's `start()` returns an empty `ListStream.of()`. No real `SelectExecutionPlan` and no real per-run variable map exists anywhere in the test, so no thread ever writes the mutable state the clone isolation exists to keep disjoint. The two threads then run exactly one `CyclicBarrier`-released drain of empty streams over disjoint mock objects. With no shared mutable state and no repetition, neither a JMM publication defect nor a `HashMap`-corruption race has any surface to appear on.
- **Evidence**: C1
- **Why it matters**: A regression that actually re-shared per-run state between clones would still leave this test green, so the one test carrying the concurrency category verifies the concurrency contract in name only. The method Javadoc ("a regression that re-shared a child plan, minted one shared child context, or shared the coordinator would surface here … not a heisenbug under load", line ~471) overstates what the assertions can catch: of those three, only re-sharing the *original* child plans is caught (via `verify(c1.plan, never()).start()`); a shared child context or shared coordinator is not (see TX2).
- **Suggested test**: either drive real state through the clones under a stress loop, or demote this to a smoke test and pin the true bleed check in the union recogniser's integration tests. A load-bearing unit version:
  ```java
  @Test
  public void clone_concurrentDrives_noCrossCloneVariableBleed() throws Exception {
    // Back each child with a real BasicCommandContext and a stream that WRITES then READS a
    // per-run variable, so a shared context between clones corrupts the observed value.
    int iterations = 2000;
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        var original = elementStep(realChild("A"), realChild("B"));
        var cloneA = original.clone();
        var cloneB = original.clone();
        var start = new CountDownLatch(1);
        var mismatches = new CopyOnWriteArrayList<String>();
        Future<?> fa = pool.submit(driveExpecting(cloneA, "A", start, mismatches));
        Future<?> fb = pool.submit(driveExpecting(cloneB, "B", start, mismatches));
        start.countDown();
        fa.get(5, TimeUnit.SECONDS);
        fb.get(5, TimeUnit.SECONDS);
        assertThat(mismatches).as("no clone observed the other's per-run variable").isEmpty();
      }
    } finally {
      pool.shutdownNow();
    }
  }
  ```

### TX2 [should-fix] Fresh-coordinator-per-clone anti-race measure has no assertion that fails on regression

`clone()` deliberately mints a fresh coordinator context so two concurrent clones never race on its `session` field, but no test would fail if that line were removed.

- **File**: `MultiPlanMatchStepTest.java` — no test covers the property; the concurrency test (line ~475) claims to via its Javadoc
- **Production code**: `MultiPlanMatchStep.java`, `cloned.coordinatorContext = new BasicCommandContext();` (line ~178); field declaration at line ~96, commented "a shared coordinator would let two concurrent clones race on setDatabaseSession"
- **Issue**: The concurrency test cannot catch a shared-coordinator regression. Both clones share one `traversal` (both call `setTraversal(traversal)`), whose `graph.tx().getDatabaseSession()` returns the single `threadSession` mock. If `clone()` stopped reassigning `coordinatorContext`, `super.clone()` would copy the reference by value and both clones would share the original's coordinator — but each thread's `openArming()` would write the *same* `threadSession` value to it (a benign same-value write), each clone would still iterate its own disjoint plan copies, and the `start`-count, no-hang, and no-error assertions would all stay green. There is no reflective distinctness check for the coordinator, unlike `clone_copiesEachChildAgainstItsOwnIsolatedChildContext` (line ~446), which does assert child-context distinctness and parentage via `ArgumentCaptor`.
- **Evidence**: C2
- **Why it matters**: Deleting `cloned.coordinatorContext = new BasicCommandContext();` leaves the entire suite green, so the named anti-race measure is unprotected. A future edit that drops or reorders it would regress the concurrency contract silently.
- **Suggested test**: a single-threaded reflective distinctness assertion mirroring the child-context test (`coordinatorContext` is `private` with no getter):
  ```java
  @Test
  public void clone_givesEachCloneAndOriginalDistinctCoordinatorContext() throws Exception {
    var original = elementStep(child(ListStream.of()), child(ListStream.of()));
    var cloneA = original.clone();
    var cloneB = original.clone();
    Field f = MultiPlanMatchStep.class.getDeclaredField("coordinatorContext");
    f.setAccessible(true);
    var co = f.get(original);
    var ca = f.get(cloneA);
    var cb = f.get(cloneB);
    assertThat(ca).as("clone A gets its own coordinator").isNotSameAs(co).isNotSameAs(cb);
    assertThat(cb).as("clone B gets its own coordinator").isNotSameAs(co);
  }
  ```

## Evidence base

Roster rendering per the output-routing spec: a claim that survived the Phase-3 refutation check (a genuine gap) is one line; a refuted claim is shown in full.

#### C1 CONFIRMED
TEST TRACE for `clone_twoClonesDrivenConcurrently_eachRunsOwnChildCopies` (2 threads, `CyclicBarrier(2)`): child plans are stateless mocks and `start()` yields `ListStream.of()`, so no real `$current`/`$matched`/statistics map is written by any thread and a single non-repeated empty drain cannot expose a publication or corruption race — the variable-bleed contract is NOT EXERCISED. → TX1.

#### C2 CONFIRMED
No assertion fails when `clone()` stops minting a fresh coordinator: both clones share one `threadSession` mock, so start counts, no-hang, and no-error are invariant to coordinator sharing, and there is no reflective coordinator-distinctness check. → TX2.

#### C3 REFUTED
TEST RACE CHECK for `clone_twoClonesDrivenConcurrently_eachRunsOwnChildCopies` — candidate finding "the concurrency test is itself racy / under-synchronized".
  - Thread coordination: `CyclicBarrier(2)`; `barrier.await()` is each driver's first action inside `drive(...)`, so the two clones' open/start/close paths overlap deterministically rather than by luck; a broken barrier throws `BrokenBarrierException` into the `catch` and lands in `errors`, so it cannot hang.
  - Shared test state: results collect into a `CopyOnWriteArrayList<Throwable>` (`errors`) — thread-safe; the driven clones touch disjoint plan mocks (`copy1A`/`copy1B`, `copy2A`/`copy2B`).
  - Assertion timing: every `verify(...)` / `assertThat(...)` runs after `tA.join(5000)` and `tB.join(5000)`; a completed `join` establishes the happens-before edge, so the main thread observes the workers' Mockito invocation records without a data race.
  - Hang handling: timed `join` plus `assertThat(t.isAlive()).isFalse()` turns a deadlocked `forEachRemaining` into a failed assertion instead of an infinite hang.
  VERDICT: SOUND. The synchronization primitives are used correctly; this is not a finding. The test's deficiency is coverage of the concurrency contract (C1, C2), not a race in the test code itself.

## Reviewer notes
Scope: this review covers only concurrency-testing quality of Track 8 Step 1. The step's non-concurrent behavior (concatenation multiset, one-live-stream laziness, exception-stops-advance, close-all-including-un-run, per-child param isolation, reset/clone structural copy) is well covered by the sequential tests and is other reviewers' dimension. `MultiPlanMatchStep` touches no cache/WAL/B-tree/transaction machinery, so the YouTrackDB-specific concurrent-subsystem scenarios do not apply here; the only concurrency contract is clone isolation + safe publication.

Tooling caveat: mcp-steroid PSI `steroid_execute_code` times out in this repo (per session preflight and prior memory), so no PSI find-usages was run. Both findings rest on the test's own structure and the diff-visible production `clone()` body, not on a caller/implementer enumeration, so the grep-residual caveat does not weaken them.
