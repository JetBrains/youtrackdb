<!--
MANIFEST
review_kind: test-concurrency
track: 4
iteration: 1
commit_range: 1dd9c0424f40e7aa9ec90858f6eb4b235f3a2c5f..2f295a881f
verdict: PASS
blocker_count: 0
should_fix_count: 0
suggestion_count: 3
findings_total: 3
evidence_base: present
cert_index: present
flags: []
index:
  - id: TX1
    sev: suggestion
    anchor: "#tx1-suggestion-datacommitserializesbehindheldschemawritelock-infers-the-block-from-a-1-second-negative-wait"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:493
    cert: C1
    basis: "Negative-wait timing inference of lock exclusion; no direct lock-state probe"
  - id: TX2
    sev: suggestion
    anchor: "#tx2-suggestion-schemacommitreloadandindexloadracewithoutdeadlock-has-a-narrow-per-round-contention-window"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:534
    cert: C3
    basis: "CyclicBarrier(3) aligns round starts but the three ops have unequal durations; deadlock-overlap window per round is small"
  - id: TX3
    sev: suggestion
    anchor: "#tx3-suggestion-no-multi-threaded-test-pins-the-lock-free-read-against-a-concurrent-mutation-of-the-shared-collection-list"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java:202
    cert: C2
    basis: "Lock-free-read tests are single-thread-holds-write-lock; the cross-thread visibility/exclusion edge is argued from the held write lock, not exercised"
-->

# Track 4 — concurrency-test review (iteration 1)

## Findings

### TX1 [suggestion] `dataCommitSerializesBehindHeldSchemaWriteLock` infers the block from a 1-second negative wait

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, method `dataCommitSerializesBehindHeldSchemaWriteLock` (line 493)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 2298-2308, 2709-2733) — the schema-carry write-lock branch versus the pure-data read-lock branch (I-U5).
- **Issue**: The test pins the schema commit inside its window, then asserts the data commit is excluded with `assertFalse(dataCommitted.await(1, TimeUnit.SECONDS))`. A one-second negative await is the canonical way to assert "did not happen yet", and it is paired with the positive `dataCommitted.await(30s)` after release plus a `@Test(timeout=60s)` deadlock net, so the test is sound. The residual is that the exclusion is inferred from timing rather than observed directly: a regression that made the data commit merely *slow* (rather than lock-excluded) for under one second would read as exclusion.
- **Evidence**: CONTRACT C1 (I-U5: schema commit holds `stateLock.writeLock()` for its whole duration; pure-data commit takes `stateLock.readLock()` and is excluded). TEST TRACE: two real threads, separate sessions, latch-pinned via `setCommitWindowTestHook` (fires inside the held write lock + open commit window, confirmed at AbstractStorage.java:2482-2485). Verdict EXERCISED. The 1 s window is generous relative to the in-memory data-commit cost, so the false-positive risk is low.
- **Why it matters**: A timing-only inference can mask a partial-exclusion regression that re-introduces a read-lock fast path under contention.
- **Suggested test**: optionally strengthen by also asserting `storage.stateLock.isWriteLocked()` is true while pinned (a direct lock-state probe `ScalableRWLock` does expose), so the exclusion is observed at the lock and not only at the wall clock. Leave the 1 s negative await as the behavioral backstop.

### TX2 [suggestion] `schemaCommitReloadAndIndexLoadRaceWithoutDeadlock` has a narrow per-round contention window

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, method `schemaCommitReloadAndIndexLoadRaceWithoutDeadlock` (line 534)
- **Production code**: `AbstractStorage.commitSchemaCarry` four-lock order (AbstractStorage.java:2709-2733), against `SchemaShared.reload` (schema write lock) and `IndexManagerAbstract.load` (index-manager lock) — I-C1.
- **Issue**: The three racer threads align each round start with a `CyclicBarrier(3)`, which is the right primitive. But the three operations differ sharply in cost — a create+drop commit pair versus a `schema.reload` versus an `indexManager.load` — so after the barrier releases, the three threads drift apart within the round and the actual lock-overlap window is small. The 30-round loop partly compensates, but a lock-order regression that only manifests under tight three-way overlap could slip through more often than the round count suggests.
- **Evidence**: CONTRACT C3 (four locks acquired in one acyclic order; the three overlapping subsets must not deadlock). TEST TRACE: 3 threads, `CyclicBarrier(3)`, 30 rounds, `@Test(timeout=60s)` deadlock detector, error captured via `AtomicReference`. Verdict EXERCISED but timing-sensitive — the barrier synchronizes starts, not the lock-acquisition instants.
- **Why it matters**: Deadlock tests that rely on incidental overlap can pass green for a long time while a real lock-order inversion lurks; the failure is non-deterministic.
- **Suggested test**: keep the structure but raise the round count (or wrap the barrier loop so each thread loops several lock-acquire cycles per barrier release), to widen the cumulative overlap. This is a robustness improvement, not a correctness gap — the existing test would still catch a deterministic inversion.

### TX3 [suggestion] No multi-threaded test pins the lock-free read against a concurrent mutation of the shared collection list

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java`, methods `getPhysicalCollectionNameByIdResolvesLockFreeWhileWriteLockHeld` / `readRecordResolvesLockFreeWhileWriteLockHeld` / `doGetIndexEngineResolvesWhileWriteLockHeld` (lines 202, 242, 163)
- **Production code**: `AbstractStorage.reconcileCollections` → `registerCollection` and the lock-free readers (AbstractStorage.java:2790-2804, 3941-3965) — the commit-window reads run on a plain `collections` / `indexEngines` list with no `stateLock.readLock()` (C2).
- **Issue**: The three lock-free-read tests are single-threaded: one thread takes the write lock, opens the window, and resolves. They prove the read does not self-deadlock under the held write lock, which is the load-bearing property. They do not exercise a *second* thread mutating the shared `collections` list concurrently with the lock-free read. This is the TX3 "concurrent registrar" case the Step 3 episode deferred as optional.
- **Evidence**: CONTRACT C2 (lock-free reads of shared mutable state under the held write lock). INTERLEAVING analysis: the safety argument is that every registrar that mutates `collections`/`indexEngines` (`registerCollection` inside the commit, plus the non-commit registrars `rebuild` / `loadExternalIndexEngine` / `recreateIndexes` — the D10 "F88 pin") requires `stateLock.writeLock()`, so a commit holding the write lock excludes all of them and there is no concurrent mutation to race. PSI confirmation of the full registrar lock set was attempted but the field-mutation query under-matched (qualifier-side `.set`/`.add` calls and helper indirection); the exclusion claim rests on the read production code (`commitSchemaCarry` holds the write lock for the whole window) plus the design's F88 note rather than on a mechanical writer enumeration. With that caveat, the deferral is a genuine non-issue, not a gap — the held write lock is the exclusion, and the C2 tests already prove the read resolves under it. Verdict: EXERCISED for the deadlock-freedom property; the concurrent-mutation visibility edge is argued, not tested.
- **Why it matters**: If a future change ever published into `collections`/`indexEngines` off the write lock (e.g. a lock-free registrar), the lock-free read would race it and the single-threaded tests would not catch it. A standing concurrent test would be a tripwire for that regression.
- **Suggested test**: optional — a two-thread test where thread A holds the write lock with the window open and repeatedly resolves a collection id, while thread B *blocks* trying to register a collection (asserting B cannot proceed until A releases) would convert the exclusion argument into an exercised one. Lower value than TX1/TX2 because the held-write-lock exclusion is structurally simple; record as a tripwire only.

## Evidence base

The Track 4 concurrency-test surface is genuinely multi-threaded, not single-threaded approximation. Every concurrency contract introduced by the diff has a corresponding test that takes the real lock / runs real threads, with `@Test(timeout)` deadlock nets throughout. The Phase-3 test-race check found no racy test: thread coordination uses `CountDownLatch` / `CyclicBarrier` / single-thread `ExecutorService`, result collection is via `AtomicReference<Throwable>` compared after `join`, and assertions run after all threads complete. No `Thread.sleep()` is used for coordination. No `synchronized` block in test code hides a production race.

#### C1 — I-U5, schema-carry write-lock exclusion: CONFIRMED exercised

`dataCommitSerializesBehindHeldSchemaWriteLock` (SchemaCommitReconciliationTest.java:409) is a real two-thread test. The schema thread runs `createClass + commit` on its own ThreadLocal-bound session; the in-window hook (`setCommitWindowTestHook`) fires at AbstractStorage.java:2482-2485, which is inside `commitSchemaCarry`'s held `stateLock.writeLock()` (2713) with the commit window open (2719). The data thread runs a pure-data commit that takes `stateLock.readLock()` at AbstractStorage.java:2302 and is therefore excluded. The block-then-release shape is correct (an "await while holding the lock" shape would deadlock by construction, as the test Javadoc notes). Survived the refutation check. See TX1 for the one residual (timing inference).

#### C2 — lock-free commit-window reads under the held write lock: CONFIRMED exercised

`doGetIndexEngineResolvesWhileWriteLockHeld` (line 163), `getPhysicalCollectionNameByIdResolvesLockFreeWhileWriteLockHeld` (line 202), and `readRecordResolvesLockFreeWhileWriteLockHeld` (line 242) each take the real `storage.stateLock.writeLock()` on the test thread and resolve through the lock-free path; a regression that re-took `stateLock.readLock()` would busy-spin on the non-reentrant `ScalableRWLock` and trip the `@Test(timeout=30s)` (core surefire sets no fork timeout, so the timeout is the only thing that converts the hang to a red test — the test comments call this out correctly). These are the genuine TX1-style "lock-free read while write lock held" tests the dispatch asked about; they are real, not single-threaded approximations of a multi-threaded property — the property *is* single-threaded (a self-deadlock on one thread). See TX3 for the concurrent-mutation edge, which is argued from the held-write-lock exclusion rather than exercised.

#### C3 — I-C1, four-lock-order deadlock-freedom: CONFIRMED exercised

`schemaCommitReloadAndIndexLoadRaceWithoutDeadlock` (line 534) runs the exact three overlapping lock subsets (commit's four-lock path, `reload`'s schema write lock, `load`'s index-manager lock) across 30 barrier-synchronized rounds with a `@Test(timeout=60s)` deadlock detector and post-join error propagation. Survived the refutation check. See TX2 for the per-round overlap-window robustness note.

#### C4 — commit-window depth counter (ThreadLocal, clamp, remove-at-zero): CONFIRMED exercised

Four tests pin the depth-counter contract: `commitWindowDepthComposesAndClosesBalanced` (nested enter/exit, with an explicit negative-control assertion that the predicate reads false outside the window — the branch-decision assertion that value-equality alone cannot make), `leakedWindowStaysActiveOnTheSameThread` (an unbalanced enter is observably leaked, not self-healed), `balancedWindowDoesNotLeakAcrossTasksOnAReusedPooledThread` (a real single-thread `ExecutorService` forces thread reuse and proves the `remove()`-at-zero hardening), and `overExitIsDetectedUnderAssertionsAndLeavesDepthClampedAtZero` (the `-ea` assert fires and the depth is left at zero). The pooled-thread test correctly notes that a `@Test(timeout)` watchdog thread is not the production pool, so it uses an explicit executor to exercise reuse. All survived the refutation check.

#### C5 — I-A4, failed-commit registry cleanliness under the held write lock: CONFIRMED exercised (concurrency aspect)

`failedSchemaCommitLeavesNoPhantomRegistration` (line 256) and `failedSchemaCommitWithDropRestoresDroppedRegistration` (line 329) inject a fault inside the held write lock via the in-window hook and verify `undoReconciledCollections` leaves the in-memory registries clean. These are single-threaded by design and correctly so: the undo runs under the same held write lock as the failed publish, so no interleaving is needed to exercise it. The deliberate choice of `CommandInterruptedException` (a retry-family fault that keeps the storage OPEN, so a phantom registration would be observable rather than masked by a forced reopen) is the right fault model for the concurrency-visibility question. Not a concurrency gap.

#### Deferred-test ledger (cross-checked, all non-gaps for this dimension)

- **TX2 negative control (Step 1 episode)** — superseded; the Step 3 `commitWindowDepthComposesAndClosesBalanced` test now carries the branch-decision negative control. Non-issue.
- **TX2 negative control (Step 3 episode)** — fixed in Step 3 (the test now asserts the lock is taken outside the window, not only the returned name). Non-issue.
- **TX3 concurrent-registrar (Step 3 episode)** — see finding TX3: a genuine non-issue given the held-write-lock exclusion, recorded as an optional tripwire suggestion.
- **`matchLowerSubclassResolvesWhileSchemaWriteLockIsHeld` (Step 6, dropped)** — `getLowerSubclass` is package-private in `sql.parser`; the held-write-lock liveness is structurally guaranteed by the lock-free snapshot read and already validated on the commit side by `dataCommitSerializesBehindHeldSchemaWriteLock`. Dropping it is justified; no concurrency gap.
- **`crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore` (`@Ignore`d, line 384)** — a crash-recovery test deferred to the IT layer; this is a crash-safety dimension concern, not a concurrency-test gap. Out of scope for this review.

#### Branch-red tests (noted, out of concurrency-test scope)

The track file records two branch-red, non-`@Ignore`d tests: `MetadataWriteMutexTest.twoConcurrentSchemaTransactionsSerializeWithoutAbort` (owned by Track 3 / Track 7, a stale tx-local-seed contention, confirmed pre-Track-4) and `SchemaDeguardTest.renameClassInsideTransactionRecordsNewNameOnly` (origin within Track 4 unresolved, flagged for Phase C reconciliation). The first is a concurrency *test* that is red, but its failure mode is owned by another track's mutex/seed work, not by Track 4's commit-reconciliation concurrency. Neither is a Track-4 concurrency-test-quality defect; both are merge-gate reconciliation items already tracked in the episode log. No new finding raised.
