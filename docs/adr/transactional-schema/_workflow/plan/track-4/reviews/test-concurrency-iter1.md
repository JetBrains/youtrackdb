<!--MANIFEST
dimension: test-concurrency
iteration: 1
review_target: "Track 4, Step 1 — c766f693ef~1..c766f693ef"
verdict: PASS
blocker_count: 0
finding_count: 2
evidence_base: "4 PSI/source checks; ScalableRWLock semantics confirmed via execute_code"
cert_index: [C1, C2]
flags: []
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-no-timeout-guard-on-the-deadlock-bearing-test"
    loc: "core/.../AbstractStorageCommitPrimitivesTest.java:137-161"
    cert: C1
    basis: "ScalableRWLock.sharedLock busy-spins on Thread.yield while isWriteLocked; no @Test(timeout) on the regression-detecting test; surefire core has no forkedProcessTimeout"
  - id: TX2
    sev: suggestion
    anchor: "#tx2-no-negative-control-pinning-the-public-wrapper-self-deadlock"
    loc: "core/.../AbstractStorageCommitPrimitivesTest.java:137-161"
    cert: C2
    basis: "Test pins doGetIndexEngine resolves under the write lock but never demonstrates getIndexEngine would NOT under the same condition; true multi-threaded coverage deferred to Step 2 (not penalized)"
-->

## Findings

### TX1 [should-fix] No timeout guard on the deadlock-bearing test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java`, method `doGetIndexEngineResolvesWhileWriteLockHeld` (lines 137-161)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`, `doGetIndexEngine(int)` (lines 3330-3337); the regression form is the read-lock acquire in `getIndexEngine` (line 3294); the lock is `ScalableRWLock.sharedLock` (busy-spin loop).
- **Issue**: The load-bearing test holds `stateLock.writeLock()` and calls `doGetIndexEngine` to prove the resolver does not re-acquire `stateLock.readLock()`. If `doGetIndexEngine` ever regressed to the public-wrapper form (`stateLock.readLock().lock()`), the test thread would not fail with an assertion — it would **busy-spin forever** inside `ScalableRWLock.sharedLock`, which loops `while (stampedLock.isWriteLocked()) Thread.yield();` whenever the write lock is held (the check is `isWriteLocked()`, not "held by another thread", so there is no same-thread relief). The test method carries no `@Test(timeout = …)`, and the core surefire config (`core/pom.xml`, `surefire-junit47`) sets no `forkedProcessTimeout`. A regression therefore wedges the surefire fork at 100% CPU until a CI-global kill (or never locally), and because the thread never reaches the `finally` it never releases the write lock, so teardown wedges too — a hang masquerading as a stuck build rather than a clean red test.
- **Evidence**: INTERLEAVING (single-thread self-deadlock form) for the D3 T1/T2 contract.
  ```
  INTERLEAVING for [doGetIndexEngine must not re-take stateLock under the held write lock]:
    Thread T1 (the only thread):
      - storage.stateLock.writeLock().lock()            // test line 153 -> exclusiveLock -> stampedLock.writeLock()
      - doGet.invoke(storage, internalId)               // test line 155
          -> [regressed] stateLock.readLock().lock()    // AbstractStorage:3294 form
          -> ScalableRWLock.sharedLock():
               set READING; isWriteLocked()==true;
               lazySet NOT_READING;
               while (isWriteLocked()) Thread.yield()   // never false — T1 itself holds the write lock
    Critical point: the resolver re-enters the non-reentrant read lock while T1 holds the write lock.
    Consequence: infinite Thread.yield busy-spin; finally at line 160 never runs; write lock never released; fork wedged.
    Test needed: the SAME test, but bounded so the hang converts to a clean failure — @Test(timeout = N).
  ```
  Confirmed mechanism (cert C1): `stateLock` is `protected final ScalableRWLock` (a custom `ReadWriteLock`, not `ReentrantReadWriteLock`); `sharedLock` busy-spins on `Thread.yield()` while `stampedLock.isWriteLocked()`; `exclusiveLock` calls `StampedLock.writeLock()` (itself non-reentrant), so the test's `writeLock().lock()` genuinely holds the exclusive lock at the invoke site.
- **Why it matters**: A test whose only failure mode is "hang the build forever" gives weaker protection than one that fails red, and it is the canonical "tests that could hang forever / missing timeout-based deadlock detection" gap. The fix is the established house convention: the sibling lock test in the same package, `ScalableRWLockTest`, guards every lock-acquisition case with `@Test(timeout = 30_000)` / `10_000`, and 23 core test files use the pattern. Bounding the test makes a future regression of the load-bearing D3 invariant fail in seconds with a `TestTimedOutException` naming the method, instead of a silent fork wedge.
- **Suggested test**:
  ```java
  // A regression that re-takes stateLock.readLock() here would busy-spin forever on the
  // non-reentrant ScalableRWLock; the timeout converts that hang into a clean red failure
  // that names this method, matching the ScalableRWLockTest convention in this package.
  @Test(timeout = 30_000)
  public void doGetIndexEngineResolvesWhileWriteLockHeld() throws Exception {
    // ... unchanged setup ...
    storage.stateLock.writeLock().lock();
    try {
      var resolved = (BaseIndexEngine) doGet.invoke(storage, internalId);
      assertThat(resolved)
          .as("doGetIndexEngine must resolve the engine under the held write lock")
          .isSameAs(engine);
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }
  ```

### TX2 [suggestion] No negative control pinning the public-wrapper self-deadlock

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageCommitPrimitivesTest.java`, method `doGetIndexEngineResolvesWhileWriteLockHeld` (lines 137-161)
- **Production code**: `AbstractStorage.getIndexEngine(int)` (line 3294, the `stateLock.readLock().lock()` the commit window must avoid) vs `doGetIndexEngine(int)` (lines 3330-3337).
- **Issue**: The test proves the positive half of the D3 contract (`doGetIndexEngine` resolves under the held write lock) but never demonstrates the negative half it exists to contrast with: that routing the commit window through the public `getIndexEngine` under the held write lock would in fact wedge. A reader cannot tell from this test alone whether `doGetIndexEngine` is lock-free *by contrast with* a lock-taking wrapper, or whether the whole `stateLock` is simply reentrant and the distinction is moot. The contrast is what makes the D3 hazard real.
- **Evidence**: TEST RACE CHECK for `doGetIndexEngineResolvesWhileWriteLockHeld` — VERDICT SOUND (single thread, lock held in try/finally at the invoke site, result is a thread-local var, no `Thread.sleep`, no shared mutable test state). The soundness is exactly why the negative half cannot be asserted in-thread: calling `getIndexEngine` here would hang the same single thread, so a negative control needs a separate bounded thread (e.g. a `Future` resolved through an executor with `get(2, SECONDS)` expecting `TimeoutException`), which is real multi-threaded machinery.
- **Why it matters**: Low — the positive test plus the confirmed `ScalableRWLock` semantics already make the contract non-vacuous, so this is robustness, not a gap that hides a bug. Track 4 Step 1's brief explicitly defers true multi-threaded race coverage of the commit consumer to Step 2, and a deadlock-of-the-public-wrapper control is most naturally folded into that Step-2 work rather than added here. Recorded so Step 2 carries the contrast assertion (a bounded thread on which `getIndexEngine` under a foreign-held write lock times out) rather than leaving the D3 hazard demonstrated only by the absence of a hang.
- **Suggested test** (Step 2 candidate, not required here):
  ```java
  // Negative control for the D3 hazard: the public getIndexEngine, taken while another
  // thread holds stateLock.writeLock(), must block (it re-acquires the read lock). A
  // bounded wait turns the expected block into an assertion instead of a hang.
  @Test(timeout = 30_000)
  public void publicGetIndexEngineBlocksWhileForeignWriteLockHeld() throws Exception {
    // ... resolve externalId of a registered engine ...
    var storage = (AbstractStorage) db.getStorage();
    var started = new CountDownLatch(1);
    var exec = Executors.newSingleThreadExecutor();
    storage.stateLock.writeLock().lock();
    try {
      Future<?> f = exec.submit(() -> {
        started.countDown();
        return storage.getIndexEngine(externalId); // re-takes readLock -> blocks
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .as("public getIndexEngine must NOT resolve while a foreign write lock is held")
          .isInstanceOf(TimeoutException.class);
    } finally {
      storage.stateLock.writeLock().unlock();
      exec.shutdownNow();
    }
  }
  ```

## Evidence base

#### C1 — ScalableRWLock non-reentrancy makes the load-bearing test non-vacuous; TX1 timeout gap survives

Refutation attempt: "Maybe `ScalableRWLock` is reentrant, so a regressed `doGetIndexEngine` taking `readLock()` would resolve fine and the test would pass either way — meaning the test is already vacuous and TX1 (timeout) is irrelevant." REFUTED. `AbstractStorage.stateLock` is `protected final com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock` (PSI field-type read), a custom `java.util.concurrent.locks.ReadWriteLock` — not `ReentrantReadWriteLock`. `readLock().lock()` delegates to `sharedLock()`, which sets the per-thread state to `SRWL_STATE_READING` and then, whenever `stampedLock.isWriteLocked()` (any holder, no same-thread relief), backs off to `NOT_READING` and loops `while (stampedLock.isWriteLocked()) Thread.yield();`. `writeLock().lock()` delegates to `exclusiveLock()` → `StampedLock.writeLock()`, itself non-reentrant. So a thread holding the write lock that calls `readLock().lock()` busy-spins forever. The test is therefore non-vacuous on the D3 regression — but the failure shape is a hang, not a red assertion, and no `@Test(timeout)` or surefire `forkedProcessTimeout` (core `surefire-junit47`, `core/pom.xml`) bounds it. Both halves of TX1 hold: the test catches the regression in principle, and it does so by wedging the fork. Mechanism read via `steroid_execute_code` PSI on the open `transactional-schema-b4l1mcdq` project (`sharedLock` / `exclusiveLock` / `InnerReadLock.lock` / `InnerWriteLock.lock` bodies); `getIndexEngine` (read-lock form, line 3294), `doGetIndexEngine` (lines 3330-3337), and `checkIndexId` (no lock acquisition — `doGetIndexEngine` is genuinely lock-free) read from source.

#### C2 — The test is sound and the positive contract is genuinely exercised; only the negative-control contrast is absent (TX2)

The single-threaded happy-path test is internally sound: the write lock is acquired at line 153 before the `try`, `doGet.invoke` runs at line 155 inside the `try` with the lock provably held, the result is a thread-local variable (no cross-thread publication, no visibility concern), the unlock is in `finally` at line 160, and there is no `Thread.sleep` or shared mutable test state. `checkIndexId` (called inside `doGetIndexEngine`) reads `indexEngines` directly with no lock, so the resolver under test is lock-free as claimed. The positive contract — "resolves under the held write lock" — is genuinely exercised. What is absent is the contrasting negative control (the public `getIndexEngine` would block under a foreign-held write lock), which cannot be asserted in the same single thread without hanging it and so belongs to the Step-2 multi-threaded work the brief defers. TX2 is a robustness suggestion, not a gap that hides a bug. No refutation needed beyond noting the deferral; recorded one line per the survived-claim rendering.
