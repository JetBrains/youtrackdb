<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: TX1, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:594, anchor: "### TX1 ", cert: C1, basis: "publish-race test does not deterministically place the reader inside the phase-3 two-map publish window, so its named crash-free-against-publish property is only probabilistically exercised"}
  - {id: TX2, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:40, anchor: "### TX2 ", cert: C2, basis: "only CommitTimeIndexBuildTest carries the stuck-thread Timeout rule; the other two commit-driving classes would hang the fork silently on a re-entrant-stateLock regression"}
  - {id: TX3, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:480, anchor: "### TX3 ", cert: C3, basis: "new eager commit-time membership mutation of the shared Index.collectionsToIndex set has no concurrent-reader test; getCollections returns a live view"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX1 [suggestion] Publish-race test does not deterministically cross the commit-time publish window

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, method `concurrentReaderNeverCrashesAndSeesEventuallyConsistentPublish` (line 594)
- **Production code**: `AbstractStorage.publishReconciledIndexes` (phase 3, run after `commitChanges` succeeds) — the non-reader-atomic two-map publish into shared `indexes` / `classPropertyIndex`.
- **Issue**: The test's stated purpose is that a concurrent lock-free reader "never crashes on the lock-free lookup-map read path **against the commit-time index publish**." The `CyclicBarrier(2)` only synchronizes thread *start*; nothing places the reader's `existsIndex`/`getIndex` loop inside the phase-3 publish slice. The publish is a tiny window near the end of a single `executeInTx(createIndex)`, so the reader may complete or stall entirely outside it and the crash-free-during-torn-publish property is exercised only probabilistically. The `AtomicBoolean stop` / `AtomicReference readerError` plumbing is correct — this is a contention-window weakness, not a test-code race.
- **Evidence**: CONTRACT (best-effort publish is not reader-atomic; YTDB-1101) + TEST TRACE (2 threads; `CyclicBarrier` start-only; contention point = shared lookup maps; interleaving = single create commit vs an unbounded read loop with no guarantee of overlap; verdict WEAK) + cert C1.
- **Why it matters**: A regression that made a lock-free reader throw mid-publish (e.g. a null-deref on the transiently-half-populated second map) could pass this test on most runs, since the reader need never be inside the window when the throw is possible — the exact false-confidence shape.
- **Suggested test**: The two existing commit-window seams (`commitWindowTestHook`, `postEngineBuildTestHook`) both fire *before* `commitChanges`, so neither straddles the phase-3 publish; deterministic coverage needs either a small new seam at the publish site or an in-flight iteration floor. Minimal in-place hardening:
  ```java
  // Reader records how many reads it completed while the commit was in flight.
  var readsInFlight = new java.util.concurrent.atomic.AtomicLong();
  var committing = new AtomicBoolean(false);
  // reader loop body:
  while (!stop.get()) {
    im.existsIndex(indexName);
    im.getIndex(indexName);
    if (committing.get()) { readsInFlight.incrementAndGet(); }
  }
  // main thread:
  committing.set(true);
  session.executeInTx(tx -> ...createIndex...);
  committing.set(false);
  stop.set(true);
  reader.join(TimeUnit.SECONDS.toMillis(30));
  assertTrue("the reader must actually read while the create commit was in flight",
      readsInFlight.get() > 0);
  ```
  (Better still: a `publishWindowTestHook` at the top of `publishReconciledIndexes` that blocks until the reader signals it has looped at least once, so the reader is provably inside the torn-map window.)

### TX2 [suggestion] Stuck-thread deadlock guard is present on only one of the three commit-driving test classes

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java` (class, line 40) and `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java` (class, line 43) — neither declares a class-level `@Rule Timeout`.
- **Production code**: `AbstractStorage` commit-window primitives (`createIndexEngineInCommitWindow`, `deleteIndexEngineInCommitWindow`, `getExactRecordsCountInCommitWindow`, `callIndexEngine`'s `isCommitWindowActive()` self-route, plus the Track-4 `toStream`/`fromStream` lock-free substrate) — all run under the non-reentrant `stateLock.writeLock()` and MUST NOT re-acquire it, or they busy-spin forever.
- **Issue**: `CommitTimeIndexBuildTest` deliberately carries a 120s `Timeout` rule with `withLookingForStuckThread(true)` (line 73) precisely because a regressed commit-window primitive that re-takes `stateLock` hangs the whole surefire fork with no signal. But `TxAwareSchemaSnapshotTest` (e.g. `commitSucceedsWhenTheTransactionCreatesAClassAndInsertsAnEntityOfIt`, `strictModeAddedToACommittedClassInTxIsEnforcedAtCommitTime`) and `SchemaDeguardTest` also drive schema-carrying commits through the same commit-under-write-lock substrate, with no timeout. A re-entrancy regression exercised only through their commit paths becomes a silent fork hang rather than a fast, named failure.
- **Evidence**: INTERLEAVING (a commit-window read that re-enters `stateLock.readLock()` under the held write lock never returns → the committing thread spins; the whole fork wedges) + the episodes' record of three separate self-deadlock discoveries during Step 2, which is exactly the regression class the guard exists to catch.
- **Why it matters**: The central concurrency hazard of this track is the non-reentrant-lock busy-spin. The guard is the difference between a diagnosable "stuck thread commit-..." failure and a CI job that hangs to its wall-clock limit and reports nothing useful.
- **Suggested test**: Add the same rule to both classes:
  ```java
  @Rule
  public Timeout globalTimeout =
      Timeout.builder().withTimeout(120, TimeUnit.SECONDS).withLookingForStuckThread(true).build();
  ```

### TX3 [suggestion] Eager commit-time membership mutation of a shared set is tested only single-threaded

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, method `committedMembershipChangeMakesParentIndexCoverSubclassRows` (line 480) — the only coverage of the membership-persist path, and it is single-threaded.
- **Production code**: `IndexAbstract.addCollectionRecordAtCommit` / `removeCollectionRecordAtCommit` (mutate `collectionsToIndex` under the per-index write lock during the enroll phase) versus `IndexAbstract.getCollections()` (`IndexAbstract.java:820-823`), which returns `Collections.unmodifiableSet(collectionsToIndex)` — a live view over the same `HashSet` — and releases the per-index read lock before the caller iterates.
- **Issue**: This track adds a *new* trigger that mutates the cross-session-visible `Index.collectionsToIndex` set mid-commit. No test has a concurrent reader traverse an index's collection membership (via `getCollections()` or a polymorphic query that walks the index's collections) while a membership commit is applying. The commit runs under the exclusive `stateLock.writeLock()`, so concurrent *writers* are excluded, but lock-free readers holding the returned live view are not.
- **Evidence**: TEST TRACE (membership-persist path: thread count 1; synchronization none; contention point = `Index.collectionsToIndex`; interleaving = sequential only; verdict NOT TESTED for the concurrent case) + cert C3. Note: whether the live-view-plus-mutation is an actual production race (versus a benign one, given the exclusive commit lock and the pre-existing shape of `getCollections()`) is the `review-bugs-concurrency` dimension's call; this finding is scoped to the *test gap*.
- **Why it matters**: If a reader iterating the live `getCollections()` view races the commit-time `collectionsToIndex.add/remove`, the symptom is a `ConcurrentModificationException` or a torn membership read on a plain `HashSet` — a class of bug no single-threaded test can surface.
- **Suggested test**: Mirror the existing two-thread pattern (a reader session on its own thread, `CountDownLatch`-gated, `AtomicReference` error capture) with the reader repeatedly consuming `parentIndex.getCollections()` (or running a polymorphic lookup) across an `addSuperClass`/alter-add-collection commit; assert the reader neither throws nor observes a corrupt membership. If `review-bugs-concurrency` confirms the read path copies under lock, this test degrades to a cheap regression guard rather than a bug reproducer.

## Evidence base

#### C1 CONFIRMED — publish-race test contention window is not deterministic
`concurrentReaderNeverCrashesAndSeesEventuallyConsistentPublish`: `CyclicBarrier(2)` gates thread start only; the phase-3 publish is a sub-slice of one `executeInTx`; no seam (`commitWindowTestHook`/`postEngineBuildTestHook` both fire pre-`commitChanges`) reaches the publish, so reader/publish overlap is probabilistic — the crash-free-against-publish claim is under-exercised.

#### C2 CONFIRMED — stuck-thread Timeout rule present only on CommitTimeIndexBuildTest
`CommitTimeIndexBuildTest` line 73 carries `Timeout ... withLookingForStuckThread(true)`; `TxAwareSchemaSnapshotTest` (line 40) and `SchemaDeguardTest` (line 43) carry no `@Rule`/`Timeout` (grep-confirmed) yet both drive schema-carrying commits through the same non-reentrant-`stateLock` commit-window substrate, so a re-entrancy regression there hangs the fork silently.

#### C3 CONFIRMED — new eager membership mutation has no concurrent-reader coverage
`addCollectionRecordAtCommit`/`removeCollectionRecordAtCommit` mutate `collectionsToIndex` mid-commit; `getCollections()` (`IndexAbstract.java:820-823`) returns a live `unmodifiableSet` view with the read lock released before iteration; the only membership test (`committedMembershipChangeMakesParentIndexCoverSubclassRows`, line 480) is single-threaded.

#### C4 REFUTED — Test A has no test-code race in its join/assert sequence
Considered: the `reader.join(30s)` then `assertNull(readerError.get())` sequence could race a still-running reader and pass vacuously. Refuted: `stop.set(true)` precedes the join and the reader loop is a tight `while (!stop.get())`, so the reader exits promptly; `readerError` is written via `compareAndSet` before the reader falls out of its `catch`, and the 120s class `Timeout` is a backstop against a genuinely wedged reader. The only weakness is the contention window (C1/TX1), not the coordination.

#### C5 REFUTED — the two cross-thread isolation tests are sound and non-vacuous
Considered: `overlayIsInvisibleToAConcurrentReaderOnAnotherThread` (`SchemaDeguardTest:1300`) and `concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId` (`TxAwareSchemaSnapshotTest`) might have coordination races or read outside the overlay window. Refuted: both gate the reader with a `CountDownLatch` that the writer counts down only *after* the mid-tx DDL has recorded the overlay and (re)built the session-private snapshot, and both hold the transaction open (rollback in `finally`) across the reader's read, so the reader provably observes the shared snapshot during the open-overlay window; error propagation uses `AtomicReference` asserted after `join`. These are the load-bearing isolation tests for the track's core new concurrent surface (session-private state must not leak) and they are well-built.

#### C6 REFUTED — the process-wide version generator needs no dedicated concurrent test
Considered: the Step-3 BC1 fix (per-instance `version++` → process-wide `VERSION_GENERATOR.incrementAndGet()`) is a concurrency fix and might warrant a multi-threaded test. Refuted: the thread-safety of the advance is `AtomicInteger`'s contract (testing it would test the JDK), and the *logical* failure it fixes — a committed-space version number colliding with a tx-local-space number, making `EntityImpl.getImmutableSchemaClass`'s `!=`-keyed cache serve a stale class — manifests single-threaded and is covered by `entityClassCachedOutsideTheTxReResolvesDespiteAVersionNumberReplay` (which deliberately walks the tx version onto the cached committed number). Cross-thread version reads are additionally exercised by C5's isolation tests. The `SchemaSharedLockApiTest` relaxation from exact `+1` to `versionAfter > versionBefore` correctly de-flakes the shared-generator behavior under parallel surefire.

#### C7 REFUTED — two concurrent schema/index transactions' overlays need no Track-5 isolation test
Considered: two sessions each with an open `IndexOverlay`/tx-local snapshot could interleave and cross-contaminate. Refuted: the `MetadataWriteMutex` (Track 3) engages on the first schema/index write and serializes schema-changing transactions, so two overlays can never be live-and-mutating concurrently — the second writer blocks. The serialization primitive and its concurrency test are Track 3/7's (the known-red `MetadataWriteMutexTest.twoConcurrentSchemaTransactionsSerializeWithoutAbort` merge-blocker), out of Track 5's scope. Track 5's reader-vs-writer isolation (the reachable concurrent shape) is covered by C5.

#### C8 REFUTED — the YqlExecutionPlanCache schema-tx bypass needs no concurrent cross-session test
Considered: the Step-4 get/put bypass keyed on `db.getTxSchemaState() != null` guards a cross-session cache-poisoning hazard, suggesting a concurrent foreign-session test. Refuted: the put-side bypass prevents a tx-shaped plan from ever entering the process-shared Guava cache, so no concurrent reader can pick one up; `polymorphicPlanBuiltInsideASchemaTxNeverEntersTheSharedCache` checks shared-cache *membership* directly (with a committed control entry proving the cache is live), which covers the concurrent case by construction — the shared structure either holds the entry or it does not, independent of the observing thread.
