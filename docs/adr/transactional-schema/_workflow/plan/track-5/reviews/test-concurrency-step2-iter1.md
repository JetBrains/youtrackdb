<!--
MANIFEST
dimension: test-concurrency
step: 5.2
iteration: 1
commit_range: d2b1632652~1..d2b1632652
verdict: CHANGES_REQUESTED
level: high
findings_total: 3
blockers: 0
should_fix: 2
suggestions: 1
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: [mcp-steroid-psi-used]
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-should-fix--no-test-races-a-concurrent-reader-or-pure-data-committer-against-the-commit-time-publish-into-the-shared-index-maps"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java
    cert: C1
    basis: "PSI find-usages: getIndex/existsIndex/getClassRawIndexes take no lock; indexes/classPropertyIndex are ConcurrentHashMap; publishReconciledIndexes does a two-map update under the write lock that lock-free readers do not honor"
  - id: TX2
    sev: should-fix
    anchor: "#tx2-should-fix--the-commit-window-lock-free-primitives-are-exercised-only-single-threaded-with-no-timeout-so-a-re-entrancy-regression-hangs-forever-instead-of-failing"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java
    cert: C2
    basis: "callIndexEngine/createIndexEngineInCommitWindow/getApproximateRecordsCountInCommitWindow all reached only from the single commit path; every build test drives them but no test has a bounded timeout, so a regression to re-taking stateLock deadlocks the suite"
  - id: TX3
    sev: suggestion
    anchor: "#tx3-suggestion--the-known-ytdb-1101-concurrent-commit-vs-publish-boundary-is-neither-documented-nor-pinned-by-a-negative-control-test"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java
    cert: C3
    basis: "D12 Risks/Caveats (track-5.md:61) names the boundary; the test file's class Javadoc and cases do not mention or pin it"
-->

## Findings

### TX1 [should-fix] — No test races a concurrent reader or pure-data committer against the commit-time publish into the shared index maps

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, whole class (new, 390 lines)
- **Production code**: `IndexManagerEmbedded.publishReconciledIndexes` (IndexManagerEmbedded.java:535-546) and `AbstractStorage.applyCommitOperations` phase 3 (AbstractStorage.java:2751-2760)
- **Issue**: Step 2's publish step mutates the two process-shared lookup maps (`indexes`, `classPropertyIndex`) that concurrent reader and pure-data-committer threads consult. No test exercises that publish under concurrency. Every one of the eight `CommitTimeIndexBuildTest` cases drives a single session sequentially (`session.begin()` … `session.commit()`), so the publish step is only ever observed by the same thread that performed it, after it completed.
- **Evidence**:
  ```
  CONTRACT: publishReconciledIndexes registers a tx-created index (indexes.put + a
            classPropertyIndex mutation inside addIndexInternalNoLock) and removes a
            tx-dropped index (removeClassPropertyIndexInternal + indexes.remove) into
            process-shared maps, claimed safe because it "runs under the index-manager
            write lock ... through acquireExclusiveLockForCommit" (Javadoc, IndexManagerEmbedded.java:531).
  PSI (find-usages, IntelliJ):
    - IndexManagerAbstract.indexes            = ConcurrentHashMap (IndexManagerAbstract.java:49)
    - IndexManagerAbstract.classPropertyIndex = ConcurrentHashMap (IndexManagerAbstract.java:47-48)
    - getIndex(name)    -> return indexes.get(iName);        // NO lock
    - existsIndex(name) -> return indexes.containsKey(iName); // NO lock
    - getClassRawIndexes -> super.getClassRawIndexes(...) on the committed maps, no acquireSharedLock
    The index-manager lock is a ReentrantReadWriteLock (IndexManagerEmbedded.java:69). The commit's
    write lock (acquireExclusiveLockForCommit, AbstractStorage.java:2839) only excludes readers that
    call acquireSharedLock(). The direct getIndex/existsIndex/getClassRawIndexes readers take no lock,
    so the write lock does not exclude them.
  TEST TRACE: every CommitTimeIndexBuildTest case + the modified SchemaDeguardTest assertion
    - Thread count: 1
    - Synchronization used: none (single session)
    - Contention point: none — reads happen after commit returns on the same thread
    - Interleaving exercised: none
  INTERLEAVING (uncovered):
    T1 (schema commit): publishReconciledIndexes -> addIndexInternalNoLock puts into `indexes` (line
       of indexes.put) ... [not yet reached classPropertyIndex mutation]
    T2 (query/other session): getClassRawIndexes / getIndex reads the half-published state — index in
       `indexes` but absent from `classPropertyIndex` (or, for a drop, removed from classPropertyIndex
       but still in `indexes`).
    Consequence: a concurrent reader can see a torn two-map view of a publishing index; a
       ConcurrentHashMap makes each single map op safe but gives no cross-map atomicity.
  VERDICT: NOT TESTED
  ```
  The only concurrency test in the whole track, `SchemaDeguardTest.overlayIsInvisibleToAConcurrentReaderOnAnotherThread` (SchemaDeguardTest.java:1261), signals `overlayBuilt` and joins the reader *before* commit (its `finally` rolls back), so it verifies the pre-commit overlay isolation, never the commit-time publish. Step 1's episode called it "a concurrent-reader test"; it does not reach Step 2's contract.
- **Why it matters**: A schema-carrying commit is the only writer that touches these shared maps, and the design explicitly runs pure-data commits and queries concurrently against them (D19 — a schema commit excludes concurrent data *commits* via the write lock, but not lock-free *reads*). A reviewer cannot tell from this test suite whether a concurrent reader sees a torn publish or a stale drop, because nothing runs two threads across the publish. If the two-map update is not reader-atomic (it is not, for a lock-free reader), a polymorphic query on another thread mid-publish gets a wrong index set — precisely the class of bug a concurrency test exists to catch.
- **Suggested test** (JUnit 4, core module; use a barrier to force the reader into the publish window, not `Thread.sleep`):
  ```java
  @Test
  public void concurrentReaderNeverSeesATornPublishOfATxCreatedIndex() throws Exception {
    // A committed empty class so the v1 build bound is met.
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PublishRaceTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "PublishRaceTarget.name";

    var readerReady = new CyclicBarrier(2);
    var stop = new AtomicBoolean(false);
    var torn = new AtomicReference<String>();

    // A reader on its own session/thread hammers the two shared maps through the public read path.
    // A torn publish would show the index present in `indexes` (existsIndex/getIndex) but absent
    // from the class's raw index set (classPropertyIndex), or the reverse for the drop.
    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        var im = other.getSharedContext().getIndexManager();
        readerReady.await(5, TimeUnit.SECONDS);
        while (!stop.get()) {
          boolean inNameMap = im.existsIndex(indexName);
          boolean inClassSet = other.getMetadata().getImmutableSchemaSnapshot()
              .getClassInternal("PublishRaceTarget").getIndexesInternal().stream()
              .anyMatch(i -> indexName.equals(i.getName()));
          if (inNameMap != inClassSet) {
            torn.compareAndSet(null, "inNameMap=" + inNameMap + " inClassSet=" + inClassSet);
          }
        }
      } catch (Throwable t) {
        torn.compareAndSet(null, "reader error: " + t);
      }
    }, "publish-race-reader");
    reader.start();

    session.activateOnCurrentThread();
    readerReady.await(5, TimeUnit.SECONDS);
    // Create + drop across several commits so the reader crosses many publish/unpublish windows.
    for (int i = 0; i < 50; i++) {
      session.executeInTx(tx -> session.getMetadata().getSchema().getClass("PublishRaceTarget")
          .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
      session.executeInTx(tx -> session.getSharedContext().getIndexManager()
          .dropIndex(session, indexName));
    }
    stop.set(true);
    reader.join(TimeUnit.SECONDS.toMillis(10));
    assertNull("a concurrent reader must never observe a torn two-map publish", torn.get());
  }
  ```
  If this test fails, it is a real production finding to route to the concurrency-bug dimension (the publish is not reader-atomic); if it passes, it documents the guarantee. Either outcome is worth more than the current zero coverage. (Note: `getClassRawIndexes` also reads the committed maps without `acquireSharedLock`, so this is a genuine open question, not a test-only concern.)

### TX2 [should-fix] — The commit-window lock-free primitives are exercised only single-threaded with no timeout, so a re-entrancy regression hangs forever instead of failing

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, all `@Test` methods (no `@Test(timeout=…)` and no `@Rule Timeout`)
- **Production code**: the `isCommitWindowActive()` self-routing in `AbstractStorage.callIndexEngine` (AbstractStorage.java:4278-4290), `createIndexEngineInCommitWindow` (AbstractStorage.java:2778), `deleteIndexEngineInCommitWindow` (AbstractStorage.java:2843), and `getApproximateRecordsCountInCommitWindow` (AbstractStorage.java:2797)
- **Issue**: The whole point of these Step 2 primitives is to avoid re-acquiring the non-reentrant `stateLock` while the commit holds it for writing (the self-deadlock hazard the task calls out). The tests do drive every one of these primitives — `indexCreatedAndPopulated…` builds an engine (`createIndexEngineInCommitWindow` + `callIndexEngine` via `onIndexEngineChange`), `buildOnNonEmptySource…` hits `getApproximateRecordsCountInCommitWindow`, `indexDropped…` hits `deleteIndexEngineInCommitWindow`. But a regression that routed any of these back through `stateLock.readLock()` would **busy-spin / block forever** on the non-reentrant `ScalableRWLock`, and because no test sets a JUnit timeout, that regression hangs the whole surefire fork rather than failing a bounded assertion.
- **Evidence**:
  ```
  CONTRACT: inside the commit window the primitives must NOT re-take stateLock, because
            ScalableRWLock is non-reentrant and the commit holds writeLock (design constraint,
            track-5.md / D3, D19; AbstractStorage.java:4280-4283 comment: "Re-acquiring the read
            lock there would busy-spin forever on the non-reentrant ScalableRWLock").
  PSI (find-usages): isCommitWindowActive reached from isClosed, getCollectionNames,
            getCollectionIdByName, getIndexEngine, callIndexEngine, getPhysicalCollectionNameById,
            readRecordInternal — all on the single commit thread. createIndexEngineInCommitWindow
            reached only from IndexAbstract.buildEngineAtCommit + undoReconciledIndexEngines.
  TEST TRACE: indexCreatedAndPopulatedInSameTransactionContainsRowsAfterCommit @ CommitTimeIndexBuildTest.java:1021
    - Thread count: 1
    - Synchronization used: none
    - Assertion timing: after commit returns
    - Deadlock guard: NONE — no @Test(timeout) and no Timeout @Rule
  VERDICT: WEAK — the lock-free routing is exercised, but a regression manifests as a hang, not a
           failure, so it degrades the whole suite (matching the environmental parallel-surefire
           fork-start crash already noted on this host) instead of pointing at the regressed method.
  ```
  Deadlock-risk coverage guidance for this reviewer: tests that could hang forever should carry a timeout so a lock-ordering / re-entrancy regression fails fast.
- **Why it matters**: The self-deadlock was discovered twice during Track 4 via thread dumps (per the track history), and Step 2 re-derives the same hazard for the index-engine path. A bounded timeout converts "the CI job times out after 45 minutes with no signal" into "this test failed in 30s pointing at the commit-window build" — the difference between a diagnosable regression and a mystery hang. This is cheap: add a class-level rule.
- **Suggested change** (JUnit 4 class rule, bounds every method):
  ```java
  // A commit-window primitive that regresses to re-taking the non-reentrant stateLock deadlocks
  // rather than throwing; a per-method timeout turns that hang into a fast, diagnosable failure.
  @Rule
  public org.junit.rules.Timeout globalTimeout =
      org.junit.rules.Timeout.builder()
          .withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
          .withLookingForStuckThread(true)  // dumps the stuck (commit) thread on timeout
          .build();
  ```

### TX3 [suggestion] — The known YTDB-1101 concurrent-commit-vs-publish boundary is neither documented nor pinned by a negative-control test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, class Javadoc (lines 987-997) and all cases
- **Production code**: the phase-3 publish deferral in `AbstractStorage.applyCommitOperations` (AbstractStorage.java:2751-2760) and `publishReconciledIndexes` (IndexManagerEmbedded.java:535)
- **Issue**: D12's Risks/Caveats (track-5.md:61) and the plan's Non-Goals ("Closing the residual concurrent-data-commit-vs-new-index window (YTDB-1101)", plan:317) explicitly carry a known boundary: a concurrent pure-data commit whose index-entry enqueue ran *before* the new index published still misses the new index (same shape as today's `fillIndex` race). The test file neither names this boundary in its class Javadoc nor pins it with a negative-control test that asserts the *documented* (accepted-miss) behavior. A future reader has no test-visible marker distinguishing "this miss is a known, accepted v1 limitation" from "this is a bug we forgot to test."
- **Evidence**:
  ```
  CONTRACT (accepted limitation, NOT a fixed guarantee): a pure-data commit on another session
    that enqueued its index-entry changes before the tx-created index published does not back-fill
    into the new index (YTDB-1101, closure deferred).
  TEST TRACE: none — no test creates the interleaving, and the class Javadoc (CommitTimeIndexBuildTest.java:987)
    enumerates the covered contracts (build, populate, drop, provisional-id resolve, v1 bound,
    failed-commit cleanliness) with no mention of the YTDB-1101 boundary.
  VERDICT: NOT DOCUMENTED / NOT TESTED
  ```
- **Why it matters**: This is a suggestion, not a should-fix, because the miss is a documented non-goal, not a regression. But an accepted concurrency limitation with no test marker is the kind of thing a later track "fixes" by accident or a reviewer flags as a missing case repeatedly. A single documented negative-control test (or at minimum a class-Javadoc sentence) freezes the v1 semantic and cites YTDB-1101, so the boundary is intentional-and-visible rather than absent-and-ambiguous.
- **Suggested test** (documents the accepted v1 behavior; adjust the assertion to match the actual accepted semantic once confirmed against a run):
  ```java
  /**
   * Documents the accepted v1 boundary (YTDB-1101): a concurrent pure-data commit whose index-entry
   * enqueue happened before a tx-created index published does not retroactively back-fill into the
   * new index. This is a known non-goal, pinned here so the semantic is intentional and visible, not
   * mistaken for missing coverage. If YTDB-1101 later closes the window, this test flips to asserting
   * the row IS present and moves to the positive-coverage set.
   */
  @Test
  public void concurrentPureDataCommitBeforePublishMissesNewIndex_knownV1Boundary() throws Exception {
    // Skeleton: two sessions; sequence a data commit's enqueue to precede the index publish via a
    // barrier at the publish seam, then assert the pre-enqueued row is absent from the new index and
    // present via a full scan (correct data, unindexed) — the documented accepted state.
  }
  ```

## Evidence base

#### C1 — publish-into-shared-maps is not reader-atomic for the lock-free read path (survived refutation → CONFIRMED as a real coverage gap)
PSI find-usages (IntelliJ, project scope) established: `indexes` and `classPropertyIndex` are `ConcurrentHashMap` (IndexManagerAbstract.java:47-49); `getIndex`/`existsIndex` return a plain map op with no lock; `getClassRawIndexes` calls the committed super with no `acquireSharedLock`. The commit-time write lock is a `ReentrantReadWriteLock` (IndexManagerEmbedded.java:69) acquired at AbstractStorage.java:2839; it excludes only `acquireSharedLock` readers, of which the direct readers are none. `publishReconciledIndexes` does a two-map update (via `addIndexInternalNoLock`: `indexes.put` then a `classPropertyIndex` mutation; via the drop arm: `removeClassPropertyIndexInternal` then `indexes.remove`). Refutation attempt: "maybe all readers route through `acquireSharedLock`" — refuted by the two no-lock reader method bodies above. The coverage gap is therefore real regardless of whether the underlying production behavior is ultimately a bug; the test suite has zero coverage of the concurrent publish.

#### C2 — the lock-free primitives are single-thread-driven with no bounded-timeout guard (CONFIRMED)
PSI find-usages: `isCommitWindowActive` and `createIndexEngineInCommitWindow` are reached only from the single commit path (`applyCommitOperations` → `IndexAbstract.buildEngineAtCommit`) and the failure-path undo. Grep over `CommitTimeIndexBuildTest.java` for `Thread|Latch|Barrier|Executor|Concurrent|timeout` returns nothing. The production comment at AbstractStorage.java:4280-4283 states a re-acquire "would busy-spin forever on the non-reentrant ScalableRWLock." Conclusion: a re-entrancy regression manifests as a hang, and the absence of any `@Test(timeout)`/`Timeout` rule means the hang is unbounded.

#### C3 — the YTDB-1101 boundary is a documented non-goal, absent from the test file (CONFIRMED)
track-5.md:61 (D12 Risks/Caveats) and implementation-plan.md:317 (Non-Goals) both name the concurrent-data-commit-vs-new-index window as deferred to YTDB-1101. The class Javadoc of `CommitTimeIndexBuildTest` (lines 987-997) enumerates the covered contracts and does not mention this boundary; no test constructs the interleaving. This is a documentation/negative-control gap, correctly ranked a suggestion because the miss is an accepted limitation, not a regression.

#### C4 — the sole existing concurrency test covers pre-commit isolation, not the Step 2 publish (context, refutes a false "already covered")
`SchemaDeguardTest.overlayIsInvisibleToAConcurrentReaderOnAnotherThread` (SchemaDeguardTest.java:1261) starts a reader thread, signals `overlayBuilt` after a mid-tx `createIndex`, joins the reader, then rolls back in the `finally` (line 1150 pattern mirrored). It asserts the concurrent reader does not see the *uncommitted* overlay and that the `volatile` shared-snapshot publish is visibility-safe. It never commits, so it does not reach `publishReconciledIndexes`. This confirms TX1/TX2 are genuine gaps and not duplicates of existing coverage.
