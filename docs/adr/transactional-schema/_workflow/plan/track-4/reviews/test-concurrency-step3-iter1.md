<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: TX1, sev: should-fix, loc: AbstractStorageCommitPrimitivesTest.java:280, anchor: "### TX1 ", cert: C1, basis: "leaked-window pooled-thread-reuse hazard (the substrate's primary danger, called out in its own Javadoc) is untested; ThreadLocal never remove()'d"}
  - {id: TX2, sev: should-fix, loc: AbstractStorageCommitPrimitivesTest.java:302, anchor: "### TX2 ", cert: C2, basis: "the only negative control proves correctness of the returned name, not that the read lock is actually taken outside the window; a stuck-active predicate passes it"}
  - {id: TX3, sev: suggestion, loc: AbstractStorageCommitPrimitivesTest.java:197, anchor: "### TX3 ", cert: C3, basis: "no multi-threaded test runs a concurrent registrar against an in-window lock-free read; the write-lock exclusion premise is asserted only by inspection"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX1 [should-fix] Leaked-window / pooled-thread-reuse hazard is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java`, method `commitWindowDepthComposesAndClosesBalanced` (line 280) — the only test touching the depth counter.
- **Production code**: `AbstractStorage.java` lines 391-392 (`commitWindowDepth` ThreadLocal, never `remove()`-ed), 3381-3389 (`enterCommitWindow`), 3398-3403 (`exitCommitWindow`), 3970/4786 (the two read methods that consult `isCommitWindowActive`).
- **Issue**: The substrate's most dangerous failure mode is a window left open on a pooled thread: a single unbalanced `enterCommitWindow()` (a missing `finally`, or a commit that throws between enter and exit) leaves the depth positive on that thread, and a *later, unrelated* read on the same reused thread then silently skips `stateLock.readLock()` and races a concurrent registrar on the plain `collections` `ArrayList`. The production code's own field comment (lines 9-13 of the diff) and both method Javadocs name this exact hazard ("later reads on the same (pooled) thread would wrongly skip the read lock"). No test exercises it. `commitWindowDepthComposesAndClosesBalanced` only ever opens-and-closes balanced; it never simulates a leak and then a follow-up read that should still take the lock. Because the storage runs on pooled threads and the `ThreadLocal` is never `remove()`-ed, a leak persists indefinitely on a reused worker.
- **Evidence**: see `#### C1` — PSI confirms `commitWindowDepth` has three references, all `.get()`, none `.remove()`; the only balance contract under test is the happy path.
- **Why it matters**: A regression that drops one `exitCommitWindow()` from the Step 4 commit path (the most likely future bug, since the contract is "MUST be balanced in a finally") would leak the window. Production reads on that worker afterward would skip the read lock and corrupt or tear a read against a concurrent schema commit on another thread — the very data race the read lock exists to prevent. The substrate ships its own danger-warning in prose but no test enforces it. This is testable today with the private `isCommitWindowActive()` predicate already reached by reflection, plus an asymmetric enter without a matching exit.
- **Suggested test** (JUnit 4, this package):
  ```java
  // A leaked open window (an enterCommitWindow without its matching exit) must be
  // detectable: the window stays active, so a later read on the same (pooled) thread
  // would unsafely skip stateLock.readLock(). Pins that the predicate reflects the
  // leak rather than self-healing, the contract the production Javadoc relies on.
  @Test
  public void leakedWindowStaysActiveOnTheSameThread() throws Exception {
    var storage = (AbstractStorage) db.getStorage();
    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    storage.enterCommitWindow(); // deliberately NOT balanced
    try {
      assertThat((boolean) active.invoke(storage))
          .as("a leaked (unbalanced) enter leaves the window active — the pooled-thread hazard")
          .isTrue();
    } finally {
      storage.exitCommitWindow(); // clean up so the worker thread is not poisoned for sibling tests
    }
  }
  ```
  A stronger variant would run the leaking enter and the follow-up read on the *same* pooled executor thread (a single-thread `ExecutorService`, awaited with `Future.get`) to prove the depth survives task boundaries, since `@Test(timeout)` watchdog threads are not the production pool. Either form closes the gap; the executor form additionally documents the reuse mechanism.

### TX2 [should-fix] The negative control proves the returned name, not that the lock is taken outside the window

- **File**: `core/src/test/java/.../AbstractStorageCommitPrimitivesTest.java`, method `commitWindowDepthComposesAndClosesBalanced` (line 302, the tail post-window read).
- **Production code**: `AbstractStorage.getPhysicalCollectionNameById` (line 3962-3990) — the `if (!lockFree) { stateLock.readLock().lock(); }` branch the control is meant to cover.
- **Issue**: The focus asks for a negative control "proving a read OUTSIDE the window still takes the lock." The tail of `commitWindowDepthComposesAndClosesBalanced` reads `getPhysicalCollectionNameById` after the window closes and asserts the *correct name* comes back. That asserts read correctness, not lock acquisition. A regression where `isCommitWindowActive()` returned stuck-true (window never effectively closes, the worst-case leak) would still return the correct name on this single-threaded test and pass — the lock-free body and the read-lock body return the same value when there is no concurrent registrar. So the control does not actually distinguish "took the read lock" from "ran lock-free." The genuine deadlock-avoidance tests (`getPhysicalCollectionNameByIdResolvesLockFreeWhileWriteLockHeld`, `readRecordResolvesLockFreeWhileWriteLockHeld`) are sound — they hold the real `stateLock.writeLock()` on the test thread, so a regression that re-took the read lock would self-deadlock and trip the 30 s timeout. The asymmetry is only on the negative side: there is no test that *fails* if the lock-free branch is wrongly taken outside the window.
- **Evidence**: see `#### C2` — the post-close assertion is value-equality (`isEqualTo(collectionName)`), which is invariant under the lock-free-vs-read-lock branch choice in a single-threaded test.
- **Why it matters**: The leaked-window hazard (TX1) and a stuck-active predicate are the two ways the window stays open when it should not. Neither is caught by the existing "negative control," because correctness of the returned name does not depend on which branch ran. A real negative control must observe the branch decision (the `isCommitWindowActive()` predicate is already in reach by reflection) or observe lock state, not the read result.
- **Suggested test**:
  ```java
  // Negative control: with no window open and no write lock held, the predicate must
  // report inactive, so getPhysicalCollectionNameById takes the read-lock branch. This
  // is the branch-decision control the value-equality assertion cannot make: it fails
  // if isCommitWindowActive() were ever stuck-true, which the name-equality check would
  // pass silently.
  @Test
  public void readOutsideWindowReportsInactiveAndTakesTheLockBranch() throws Exception {
    var storage = (AbstractStorage) db.getStorage();
    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    assertThat((boolean) active.invoke(storage))
        .as("outside any window the predicate is inactive — the read takes stateLock.readLock()")
        .isFalse();
    // And the read still resolves through the locked path (no write lock held here,
    // so a read-lock acquire succeeds rather than deadlocks).
    db.createVertexClass("NegControlProbe");
    var n = storage.getCollectionNames().stream()
        .filter(s -> s.startsWith("negcontrolprobe")).findFirst().orElseThrow();
    assertThat(storage.getPhysicalCollectionNameById(storage.getCollectionIdByName(n)))
        .isEqualTo(n);
  }
  ```
  Folding the predicate-is-inactive assertion into the existing tail of `commitWindowDepthComposesAndClosesBalanced` (one extra `assertThat(active.invoke(storage)).isFalse()` before the read) is the minimal fix and keeps the control where the reviewer expects it.

### TX3 [suggestion] No multi-threaded test runs a concurrent registrar against an in-window lock-free read

- **File**: `core/src/test/java/.../AbstractStorageCommitPrimitivesTest.java`, methods `getPhysicalCollectionNameByIdResolvesLockFreeWhileWriteLockHeld` (line 197) and `readRecordResolvesLockFreeWhileWriteLockHeld` (line 237).
- **Production code**: `doGetPhysicalCollectionNameById` (line 3999-4006) and `readRecordInternal` (line 4771-4804) — both read the plain `collections` `ArrayList` off-lock when the window is active.
- **Issue**: The substrate's safety claim, stated verbatim in the field comment and both Javadocs, is "the held write lock already excludes every concurrent registrar and supplies the happens-before edge." Every test runs single-threaded: the only thread that touches `collections` during an open window is the test thread itself, so the exclusion premise is asserted by inspection, never exercised. A test that started a second thread attempting a collection/index create (a registrar) while the first holds the write lock with the window open would demonstrate the registrar genuinely blocks on `stateLock.writeLock()` for the window's duration.
- **Evidence**: see `#### C3` — all three window tests take the write lock on a single thread; no `CountDownLatch` / `CyclicBarrier` / second thread appears in the file.
- **Why it matters**: This is the weakest of the three because the new code adds no shared-state *mutation* — it only *reads* under a lock that is already exclusive by construction, so a multi-threaded test would largely re-verify `ScalableRWLock`'s mutual exclusion rather than the new substrate. Step 1's episode already deferred a "true multi-threaded race test" (TX2 there) and the design rests the safety on the write lock. Worth adding for robustness, not blocking: it would convert the "registrars are excluded" prose premise into an executed assertion and guard against a future change that weakens the lock branch.
- **Suggested test** (use `ConcurrentTestHelper` or a `CyclicBarrier`; never `Thread.sleep` for coordination):
  ```java
  // The in-window read's safety rests on the held write lock excluding registrars.
  // Prove it: thread A holds stateLock.writeLock() with the window open and reads
  // lock-free; thread B attempts a collection create and must block until A releases.
  // A CyclicBarrier rendezvous removes any sleep-based timing. The 30 s bound turns a
  // regression (B not excluded -> concurrent ArrayList mutation/torn read) into a
  // visible failure rather than flaky corruption.
  @Test(timeout = 30_000)
  public void inWindowReadExcludesAConcurrentRegistrar() throws Exception {
    db.activateOnCurrentThread();
    db.createVertexClass("ExclusionProbe");
    var storage = (AbstractStorage) db.getStorage();
    int id = storage.getCollectionIdByName(
        storage.getCollectionNames().stream()
            .filter(s -> s.startsWith("exclusionprobe")).findFirst().orElseThrow());

    var entered = new CountDownLatch(1);
    var registrarFinished = new AtomicBoolean(false);
    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      var t = new Thread(() -> { storage.addCollection(...); registrarFinished.set(true); });
      t.start();
      // While we hold the write lock, the registrar must NOT have completed.
      Thread.onSpinWait(); // give it a chance to attempt and block
      assertThat(registrarFinished.get())
          .as("a registrar must block on stateLock.writeLock() while the window holder reads")
          .isFalse();
      assertThat(storage.getPhysicalCollectionNameById(id)).isNotNull();
      storage.exitCommitWindow();
    } finally {
      storage.stateLock.writeLock().unlock();
    }
    // After release the registrar proceeds; join with the bound.
  }
  ```
  Treat the skeleton as illustrative — `addCollection`'s real signature and the registrar's own locking need adapting; the load-bearing part is the rendezvous proving exclusion, not the exact call.

## Evidence base

#### C1 Leaked-window hazard is real and untested — CONFIRMED
PSI `ReferencesSearch` on `AbstractStorage.commitWindowDepth` returns three references, all `.get()` (lines 3388, 3399, 3411); no `.remove()` anywhere in the project scope, so a leaked window persists for the life of a pooled thread. The production callers of `enterCommitWindow`/`exitCommitWindow` are exclusively the three test methods (PSI: 6 + 5 refs, all in `AbstractStorageCommitPrimitivesTest`; the AbstractStorage:3370-3406 hits are `{@link}` Javadoc references, not call sites), confirming this is additive substrate Step 4 will wire into `commit` — which is exactly where an unbalanced enter/exit could be introduced. `isCommitWindowActive` is consulted by `getPhysicalCollectionNameById` (line 3970) and `readRecordInternal` (line 4786), so a stuck-active counter poisons both record-read legs on the affected thread. The single depth test only opens-and-closes balanced. Survived the refutation check: the hazard is not caught elsewhere (no other test in the diff touches the counter), the ThreadLocal genuinely has no cleanup, and the production code itself documents the danger — so the gap is a real should-fix, not a false alarm.

#### C2 Negative control is correctness-only, not branch-discriminating — CONFIRMED
The post-window read in `commitWindowDepthComposesAndClosesBalanced` (line 309) asserts `isEqualTo(collectionName)`. `doGetPhysicalCollectionNameById` returns the same value whether reached via the read-lock branch or the lock-free branch (it reads the identical `collections` list); in a single-threaded test with no concurrent registrar, both branches are observationally identical. Therefore a mutant with `isCommitWindowActive()` stuck-true (the lock-free branch always taken) passes this assertion. The genuine deadlock tests are sound by contrast: they hold the real `stateLock.writeLock()` (lines 216, 253) on the test thread, and `ScalableRWLock.sharedLock()` busy-spins on `Thread.yield()` while `stampedLock.isWriteLocked()` (lines 334-336 of `ScalableRWLock.java`), which is true even for the write-lock-holding thread (non-reentrant, no owner check), so a re-take would hang into the 30 s `@Test(timeout)`. The positive (deadlock-avoidance) side is well covered; only the negative (lock-is-taken-outside) side is missing. Refutation check: the claim is not that the control is absent but that it tests the wrong property — verified by tracing that name-equality is branch-invariant single-threaded.

#### C3 No concurrent-registrar test exercises the write-lock exclusion premise — CONFIRMED
Textual and structural scan of the test file shows no second thread, `CountDownLatch`, `CyclicBarrier`, `Phaser`, `ExecutorService`, or `ConcurrentTestHelper` usage; all three window tests acquire `storage.stateLock.writeLock()` on the single test/watchdog thread. The substrate's correctness premise ("the held write lock excludes every concurrent registrar") is thus never executed against an actual registrar. Weighed to suggestion rather than should-fix: the new code adds no shared-state mutation (it reads plain `collections` under an already-exclusive lock), so a multi-threaded test mostly re-verifies `ScalableRWLock` mutual exclusion already covered by `ScalableRWLockTest` in this package; the design and the Step 1 episode (deferred TX2) both rest safety on the write lock by construction. Real gap, low marginal catch — robustness improvement, not a confidence-restoring blocker.
