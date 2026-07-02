<!--MANIFEST
dimension: test-concurrency
step: 4
iteration: 1
commit_range: bc7eba6da8~1..bc7eba6da8
verdict: changes-requested
evidence_base: "## Evidence base"
cert_index:
  - { id: C1, anchor: "#### C1" }
  - { id: C2, anchor: "#### C2" }
  - { id: C3, anchor: "#### C3" }
flags: []
index:
  - { id: TX1, sev: should-fix, anchor: "### TX1", loc: "core/.../schema/SchemaCommitReconciliationTest.java (whole file)", cert: C1, basis: "PSI find-usages: commitSchemaCarry test=0; track Validation I-U5" }
  - { id: TX2, sev: should-fix, anchor: "### TX2", loc: "core/.../schema/SchemaCommitReconciliationTest.java (whole file)", cert: C2, basis: "PSI: four-lock-order symbols test=0; ScalableRWLock has no owner-thread guard; track Validation I-C1" }
  - { id: TX3, sev: suggestion, anchor: "### TX3", loc: "AbstractStorage.java commitSchemaCarry enterCommitWindow/exitCommitWindow", cert: C3, basis: "PSI: window primitives exercised only by single-thread Step-3 white-box test" }
-->

## Findings

### TX1 [should-fix] I-U5 (concurrent data commit during a held schema-commit write lock) is unverified, and the step does not flag the gap

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` — the whole file is single-threaded.

**Production code**: `AbstractStorage.commitSchemaCarry` (`AbstractStorage.java`, the schema-carry branch added in this commit) takes `stateLock.writeLock()` for the entire commit duration; the pure-data branch keeps `stateLock.readLock()`.

**Issue**: The step introduces the I-U5 contract — a schema-carrying commit holds the write lock for its whole duration while a pure-data commit must keep running concurrently on the read-lock fast path, and an index-only tx serializes on the write-lock branch. No test exercises two commits running at the same time. Every test in `SchemaCommitReconciliationTest` drives the one bound session through `session.executeInTx` / `computeInTx`, so the write-lock branch and the read-lock branch never run on two threads at once. The behavior is asserted single-threaded (the create/resolve/promote round trip, rollback-leaves-clean, the positive drop), which proves the reconciliation outcome but not the concurrency contract the step adds.

The track's `## Validation and Acceptance` names this scenario explicitly (the I-U5 line: "A schema commit holds the write lock for its whole duration with no observable upgrade; a data commit runs concurrently with other data commits on the read-lock path; an index-only tx is serialized as a schema-carrying commit"). The Step 4 plan-of-work line says "commit/rollback/crash/concurrency tests" and the Interfaces section repeats "commit/rollback/crash/concurrency tests" as in-scope. The episode for Step 4 is not yet written, so there is no Surprises/episode note deferring the concurrency tests; the read-site-conversion half of I-U5 is assigned to Step 6, but the held-write-lock-versus-concurrent-data-commit behavior is established here and is the natural place to verify it. The gap is neither covered nor flagged.

**Evidence**: see Evidence base C1 (PSI find-usages: `commitSchemaCarry` and `reconcileCollections` have zero test references; the commit-window primitives are referenced only from the Step-3 single-thread white-box test).

**Why it matters**: The whole D19 design premise is "a schema commit excludes concurrent data commits for its duration, bounded by the low schema-change rate, while the read-lock fast path stays unaffected." If the schema-carry branch ever fell back to the read lock (a regression in the `schemaCarry` entry signal, the `getTxSchemaState() != null` check, or the `isWriteTransaction()` fix this commit also adds), a data commit and a schema commit could mutate the registries (`collections` / `collectionMap`) concurrently with no exclusion. A single-threaded test cannot catch that; it would pass while the isolation guarantee is silently broken. Conversely, if a future change accidentally made the data path take the write lock, the read-path concurrency the design protects would be lost with no test to flag the throughput regression.

**Suggested test** (JUnit 4, core module; `ConcurrentTestHelper` lives at `com.jetbrains.youtrackdb.internal.test.ConcurrentTestHelper`):

```java
/**
 * I-U5: while a schema-carrying commit holds stateLock.writeLock() for its whole duration,
 * a concurrent pure-data commit on a second session proceeds on the read-lock fast path and
 * succeeds. A barrier releases both threads together; the schema commit is held inside its
 * window by a CountDownLatch wired through a test seam so the data commit demonstrably runs
 * while the schema write lock is held, rather than racing by luck.
 */
@Test(timeout = 60_000)
public void dataCommitProceedsWhileSchemaCommitHoldsWriteLock() throws Exception {
  // Two sessions on the same storage. The schema session creates a class (schema-carry,
  // write-lock branch); the data session inserts into a pre-existing class (pure-data,
  // read-lock branch). A test-only hook inside the schema commit window counts down
  // schemaInWindow, then awaits dataCommitted before exiting the window, proving the data
  // commit completed against the read lock while the write lock was held.
  var barrier = new CyclicBarrier(2);
  var schemaInWindow = new CountDownLatch(1);
  var dataCommitted = new CountDownLatch(1);
  // ... install the in-window hook (e.g. a @VisibleForTesting Runnable on AbstractStorage
  //     fired inside enterCommitWindow/exitCommitWindow), run both bodies via
  //     ConcurrentTestHelper, await(), assert both committed and no deadlock fired.
}
```

If a clean in-window test seam does not exist, decompose a small `@VisibleForTesting` hook (the track already contemplates a Mockito/`@VisibleForTesting` commit seam for the I-A4 failed-commit case) rather than relying on `Thread.sleep`.

---

### TX2 [should-fix] I-C1 (four-lock-order deadlock-freedom under contention) is unverified, and there is no timeout-bounded deadlock test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` — no test runs concurrent lock acquirers; no test has a timeout.

**Production code**: `AbstractStorage.commitSchemaCarry` acquires four locks in a fixed order — `committedSchema.acquireSchemaWriteLock` → `indexManager.acquireExclusiveLockForCommit` → `stateLock.writeLock().lock()` (the mutex is engaged earlier on the first schema write). `IndexManagerEmbedded.acquireExclusiveLockForCommit` / `releaseExclusiveLockForCommit` are new in this commit precisely to take the index-manager lock as the third lock without the schema-mutation side effects.

**Issue**: The step establishes the I-C1 contract — "A schema commit, a data-path `reload`, and an `IndexManagerAbstract.load` run concurrently with no interleaving deadlock." The four-lock order is the mechanism that keeps the acquisition acyclic. No test acquires these locks from more than one thread, and no test in the new file carries a `@Test(timeout=...)` bound, so a lock-ordering regression would hang the suite forever rather than fail fast. The Step 4 plan-of-work line claims this step "Covers ... I-C1", but the only new test is single-threaded and cannot observe an ordering violation: a deadlock needs at least two threads taking overlapping lock subsets in opposing orders.

**Evidence**: see Evidence base C2. `SchemaShared.reload` / `load` acquire the schema write lock; `IndexManagerAbstract.load` acquires the index-manager exclusive lock; the commit holds all of `SchemaShared.lock` + index-manager lock + `stateLock.writeLock`. These are exactly the overlapping lock subsets I-C1 names. `ScalableRWLock` exposes no `isWriteLockedByCurrentThread()` owner-thread query (PSI: its public surface is `sharedLock`/`exclusiveLock`/`*TryLock*` only), so the acquisition order cannot be asserted from inside production code — a runtime guard is impossible and a concurrent test is the only mechanism that can verify deadlock-freedom.

**Why it matters**: A lock-ordering bug is the highest-consequence concurrency defect this step can carry: it surfaces only under contention, corrupts no data when it does, and wedges the storage. Because the lock order is not runtime-assertable, a single-threaded test base means a future edit that reorders the four locks (or a new reader path that takes `stateLock` before `SchemaShared.lock`) ships with zero coverage. A timeout-bounded multi-thread test converts an otherwise-silent hang into a fast, diagnosable failure.

**Suggested test** (JUnit 4; the timeout is the deadlock detector):

```java
/**
 * I-C1: a schema-carrying commit, a SchemaShared.reload, and an IndexManagerAbstract.load
 * run concurrently against the same storage without an interleaving deadlock. The test
 * spins N rounds of all three operations from three threads released together by a
 * CyclicBarrier; the @Test(timeout) is the deadlock detector — a lock-order regression
 * hangs and trips the timeout instead of hanging the whole suite.
 */
@Test(timeout = 60_000)
public void schemaCommitReloadAndIndexLoadRaceWithoutDeadlock() throws Exception {
  var rounds = 50;
  var barrier = new CyclicBarrier(3);
  // Thread A: session.executeInTx(tx -> createClass("Racer" + round)) — four-lock commit.
  // Thread B: schemaShared().reload(session) — takes SchemaShared.lock.
  // Thread C: indexManager.load(...) — takes the index-manager lock.
  // barrier.await() each round to maximize interleaving; ConcurrentTestHelper collects
  // results and rethrows; reaching the assertions without timing out is the pass.
}
```

Note: `db.activateOnCurrentThread()` must be the first statement in any body run on a watchdog or helper thread (the session is `ThreadLocal`-bound; see Episodes §Step 1).

---

### TX3 [suggestion] The per-thread commit window's cross-thread safety rests on the held write lock, but only its single-thread re-entry is tested

**File**: the window is opened in `AbstractStorage.commitSchemaCarry` via `enterCommitWindow()` / `exitCommitWindow()`; its tests live in `AbstractStorageCommitPrimitivesTest` (Step 3), all single-threaded.

**Production code**: `isCommitWindowActive()` (a `ThreadLocal<int[]>` depth counter) makes `getPhysicalCollectionNameById`, `readRecordInternal`, `isClosed`, `getCollectionNames`, `getCollectionIdByName`, and `getIndexEngine` skip `stateLock.readLock()` on the commit thread while the window is open.

**Issue**: The window comments assert that the held write lock "supplies the exclusion and the visibility edge" for the lock-free reads. That is a cross-thread memory-visibility claim — other threads must not observe a torn `collectionMap` / `collections` / `indexEngines` while the windowed reads run. The existing window tests (Step 3) only prove the *single-thread re-entry* property: the same test thread takes `stateLock.writeLock()` and confirms a lock-free read resolves and does not self-deadlock the non-reentrant lock. They do not put a second thread on the read path concurrently, so the exclusion-and-visibility claim that justifies skipping the read lock is asserted by argument, not by test. This is correctly a suggestion rather than a should-fix: the exclusion follows from the write lock by construction, and a direct visibility test is hard to make deterministic. If TX1's concurrent-data-commit test lands, it incidentally exercises a second thread on the read-lock path while the window is open and discharges most of this concern.

**Evidence**: see Evidence base C3 (the window primitives are referenced only by the Step-3 single-thread white-box test; `commitSchemaCarry` itself has no test caller).

**Why it matters**: If the window's exclusion premise were wrong (for example, a read path that skips the read lock but is reachable from a thread that does *not* hold the write lock), a concurrent reader could see a half-published collection during reconciliation. The risk is low because `isCommitWindowActive()` is keyed to the committing thread's `ThreadLocal`, so only the write-lock holder takes the lock-free path; a non-holder still takes the read lock and blocks. Documenting this reasoning in the TX1 test (or a short comment on `enterCommitWindow`) is enough.

## Evidence base

#### C1 — I-U5 concurrent-commit coverage absent (CONFIRMED-as-issue)
PSI find-usages (mcp-steroid, project `transactional-schema-b4l1mcdq`): `AbstractStorage#commitSchemaCarry` total=1 / test=0; `AbstractStorage#reconcileCollections` total=2 / test=0; `IndexManagerEmbedded#acquireExclusiveLockForCommit` total=2 / test=0; `SchemaShared#resolveProvisionalCollectionIds` total=1 / test=0. `enterCommitWindow` / `exitCommitWindow` test references are all in `AbstractStorageCommitPrimitivesTest` (Step 3), which is single-threaded. `SchemaCommitReconciliationTest` drives the one bound session through `executeInTx`/`computeInTx` only. No multi-threaded commit exists. Track `## Validation and Acceptance` I-U5 line + Step 4 plan-of-work line ("commit/rollback/crash/concurrency tests") name the scenario. Survived the refutation check (no concurrent test, no episode-level deferral note, gap not flagged) — recorded as an issue.

#### C2 — I-C1 deadlock-freedom coverage absent; not runtime-assertable (CONFIRMED-as-issue)
PSI: `commitSchemaCarry` acquires `acquireSchemaWriteLock` → `acquireExclusiveLockForCommit` → `stateLock.writeLock` (read directly from the diff). Lock-use scan: `SchemaShared#reload` and `SchemaShared#load` take the schema write lock; `IndexManagerAbstract#load` takes the index-manager exclusive lock — the exact overlapping lock subsets I-C1 names. `ScalableRWLock` (fqn `...common.concur.lock.ScalableRWLock`) public method surface = `readLock, writeLock, addState, sharedLock, sharedUnlock, exclusiveLock, exclusiveUnlock, sharedTryLock, sharedTryLockNanos, exclusiveTryLock, exclusiveTryLockNanos` — no `isWriteLockedByCurrentThread()` / owner-thread query, so order cannot be asserted in production; a concurrent timeout-bounded test is the only verification path. `SchemaCommitReconciliationTest` has no `@Test(timeout=...)` and no second thread. Survived refutation — recorded as an issue.

#### C3 — window cross-thread visibility asserted by argument, not test (refuted to suggestion)
The exclusion-and-visibility claim is sound by construction: `isCommitWindowActive()` reads a `ThreadLocal<int[]>` depth counter, so only the thread holding `stateLock.writeLock()` ever takes the lock-free read path; any other thread still takes `stateLock.readLock()` and blocks behind the write lock. There is therefore no concurrent unsynchronized reader the existing single-thread tests fail to cover — the missing coverage is a belt-and-suspenders direct visibility test, not a live hazard. Refuted from should-fix to suggestion on that basis; the TX1 concurrent-data-commit test would incidentally cover the second-thread-on-read-path case.
