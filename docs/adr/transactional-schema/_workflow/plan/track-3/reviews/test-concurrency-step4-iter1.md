<!--
MANIFEST
dimension: test-concurrency
step: track-3-step-4
iteration: 1
commit_range: 5b08dfdc3766673914aef9ad0513bdf400c5b952~1..5b08dfdc3766673914aef9ad0513bdf400c5b952
verdict: changes-requested
finding_count: 2
blocker: 0
should_fix: 1
suggestion: 1
evidence_base: 5
cert_index: C1,C2,C3,C4,C5
flags: none
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-second-writer-blocking-proven-by-threadsleep500-not-deterministically"
    loc: "core/.../db/MetadataWriteMutexTest.java:twoConcurrentSchemaTransactionsSerializeWithoutAbort (diff line 454)"
    cert: C1
    basis: "Thread.sleep(500) + negative assertion is the only proof the second schema tx BLOCKS; gives one-directional false confidence (passes if second is merely slow); PSI-confirmed both sessions share one mutex via SharedContext"
  - id: TX2
    sev: suggestion
    anchor: "#tx2-foreign-park-proof-is-timing-based-negative-await-tighter-but-still-non-deterministic"
    loc: "core/.../db/MetadataWriteMutexTest.java:differentThreadParksUntilRelease (diff line 615)"
    cert: C2
    basis: "foreignEngaged.await(500ms)==false proves parking by absence-of-progress; tighter window than TX1 but still timing-based, not a thread-state observation"
-->

# Test concurrency review — Track 3, Step 4 (`MetadataWriteMutex`), iteration 1

## Findings

### TX1 [should-fix] Second-writer blocking proven by `Thread.sleep(500)`, not deterministically

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, method `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (diff line 454).
- **Production code**: `core/.../core/db/MetadataWriteMutex.java`, `engage()` (diff lines 182-197) — `permit.acquireUninterruptibly()` blocks a second engager until the first releases; reached in production through `DatabaseSessionEmbedded.ensureTxSchemaState → engageMetadataWriteMutex` (PSI: single funnel).
- **Issue**: The test's two deterministic halves are sound — the first writer signals `firstHoldsMutex` after its `createClass` engages the permit, and `secondDone.await(5s)` plus the post-release assertions prove the second completes once the first releases. The middle claim, "the second schema tx BLOCKS while the first holds the mutex," rests on `Thread.sleep(500)` followed by `assertFalse(secondCreatedClass.get())`. That is a negative, timing-based assertion: it observes only that the second thread has *not finished creating its class* after 500 ms, not that it is *parked on the permit*. The criteria for this review call out `Thread.sleep()`-for-synchronization as the pattern to flag.
- **Evidence**: CERT C1. CONTRACT: a second schema-changing tx blocks on the single `Semaphore(1)` permit rather than aborting. TEST RACE CHECK: coordination = latch (hold + completion, sound) + `Thread.sleep(500)` (blocking sub-proof, weak); shared state = `AtomicBoolean`/`AtomicReference` (thread-safe); assertion timing = the negative assert runs concurrently with the second worker's startup. VERDICT: WEAK — the blocking proof is one-directional. If the mutex were broken so the second did **not** block, the assertion would still pass whenever the second worker is merely slow to traverse `openDatabase()` → `begin()` → proxy resolution → `createClass` within 500 ms, which under a loaded CI forked JVM is not improbable. The test therefore cannot fail-closed on a regression that lets the second writer through; it only proves the release-then-completion direction.
- **Why it matters**: This is the headline acceptance line for the track (I-A6: "the second blocks on the mutex until the first completes, and neither aborts"). A blocking proof that can pass without the mutex blocking anything leaves the single-writer-by-locking guarantee — the whole point of `MetadataWriteMutex` — without a fail-closed regression guard. A future change that turned the engage into a non-blocking `tryAcquire`, or moved the engage below the seed so the second raced past, could slip through green.
- **Suggested test**: Replace the `Thread.sleep(500)` negative assert with a deterministic observation that the second worker has *reached and is parked on* the engage. Have the second worker count down a latch immediately before its first schema write (the call that blocks), then assert it is BLOCKED/WAITING on the semaphore rather than asserting absence-of-progress on a sleep:
  ```java
  var secondAboutToEngage = new CountDownLatch(1);
  var secondThreadRef = new AtomicReference<Thread>();
  // inside the second worker, on its thread:
  //   secondThreadRef.set(Thread.currentThread());
  //   secondAboutToEngage.countDown();
  //   second.getMetadata().getSchema().createClass("SecondSchemaTx"); // parks here
  assertTrue(secondAboutToEngage.await(5, TimeUnit.SECONDS));
  // Poll for the second worker to settle into WAITING/BLOCKED on the permit, deterministically.
  var t = secondThreadRef.get();
  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
  while (System.nanoTime() < deadline) {
    var s = t.getState();
    if (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) {
      break;
    }
    Thread.onSpinWait();
  }
  assertFalse("second must not have created its class while parked", secondCreatedClass.get());
  assertTrue("second must be parked, not running", t.getState() == Thread.State.WAITING);
  // ... then firstMayCommit.countDown(); secondDone.await(5, SECONDS); as today.
  ```
  An even cleaner option, since the test already has direct mutex access elsewhere, is to drive the blocking half directly against `MetadataWriteMutex.engage` from two threads and observe the parked thread's state — mirroring `differentThreadParksUntilRelease` (TX2) but keeping the full schema-tx path for the release-and-complete half.

### TX2 [suggestion] Foreign-park proof is a timing-based negative await — tighter, but still non-deterministic

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, method `differentThreadParksUntilRelease` (diff line 615).
- **Production code**: `core/.../core/db/MetadataWriteMutex.java`, `engage()` (diff lines 182-197).
- **Issue**: The parking proof is `assertFalse(foreignEngaged.await(500, MILLISECONDS))` — the foreign thread "must park" is inferred from the engaged-latch *not* counting down within 500 ms. This is the better idiom than TX1 (the window is tight and the engage is driven directly against the mutex with nothing between `engage()` and `foreignEngaged.countDown()`), and the release half (`foreignEngaged.await(5s)` after `releaseFor`) is deterministic. But it is still an absence-of-progress proof: a foreign worker that has not yet *reached* `mutex.engage(other)` within 500 ms (it first does `openDatabase()` + `activateOnCurrentThread()`) would also satisfy the assertion without the permit blocking anything.
- **Evidence**: CERT C2. CONTRACT: a foreign-thread engager parks on the held permit until release. TEST RACE CHECK: coordination = direct `engage`/`releaseFor` + a 500 ms negative `await`; shared state = `AtomicReference` (thread-safe). VERDICT: SOUND-but-WEAK — the false-pass window is small (the foreign worker's pre-engage work is trivial) and one-directional, so this is a robustness note, not a correctness gap.
- **Why it matters**: Same class of false-confidence as TX1, far smaller blast radius because the engage is direct and the pre-engage path is short. Worth tightening for symmetry once TX1 adopts a thread-state observation; reuse the same helper.
- **Suggested test**: After `foreignEngaged.await(500ms)` returns false, additionally assert the foreign worker thread is `WAITING` (parked on the semaphore) via the same spin-poll-on-`getState()` helper proposed for TX1, so the proof is "observed parked" rather than "did not progress."

## Evidence base

#### C1 — Two concurrent schema txs: blocking proof is `Thread.sleep`-based (CONFIRMED-as-issue)

Survived the refutation check: PSI confirmed `openDatabase()` (DbTestBase:152) opens a second session on the same named DB, so first/second sessions share one `SharedContext` and one `MetadataWriteMutex` (real contention, not two mutexes); the blocking sub-proof is `Thread.sleep(500)` + negative `assertFalse(secondCreatedClass)`, which the review criteria flag as `Thread.sleep`-for-synchronization and which gives one-directional false confidence. Issue stands.

#### C2 — Foreign-park proof is timing-based negative await (CONFIRMED-as-issue, suggestion-tier)

Survived as a suggestion: `foreignEngaged.await(500ms)==false` infers parking from absence-of-progress; tighter than TX1 (direct `engage`, short pre-engage path) but not a thread-state observation. Low blast radius, kept at suggestion.

#### C3 — Engage-order asserts fire under `-ea` and cover BOTH shared locks (refuted as a finding)

Candidate finding considered: "the engage-order assert tests may be no-ops if assertions are disabled, or may cover only one lock." Refuted. `core/pom.xml:36` puts `-ea` on the surefire `<argLine>`, so an `AssertionError` is thrown in the test JVM and the `fail(...)`-then-`catch(AssertionError)` shape is live. PSI confirmed `engageMetadataWriteMutex` (the sole engage site, DatabaseSessionEmbedded.java:2449) asserts BOTH `SchemaShared.isWriteLockHeldByCurrentThread()` and `IndexManagerEmbedded.isWriteLockHeldByCurrentThread()` before `engage()`. `engageOrderAssertFiresWhenSchemaLockHeld` drives the schema-lock arm via the real `acquireSchemaWriteLock`; `engageOrderAssertFiresWhenIndexManagerLockHeld` drives the index-manager arm via reflection on the real `lock` field and then asserts a no-lock engage succeeds and releases. Both arms exercised deterministically (same-thread lock acquisition, no timing). No finding.

#### C4 — Same-thread loud-reject is triggered on the same thread (refuted as a finding)

Candidate finding considered: "the reject may not actually be triggered on one thread." Refuted. `sameThreadSecondSessionEngageThrows` runs entirely on the test thread: `mutex.engage(outer)` then `mutex.engage(inner)` with `inner != outer` and `currentThread()` unchanged, hitting the `current.thread() == Thread.currentThread() && current.session() != session` arm of `engage()` (MetadataWriteMutex.java:184-193) deterministically (no sleeps, no second thread). It asserts the message names "different session" and that the outer hold survives the rejected inner engage, then releases in `finally`. Deterministic and correctly placed. No finding.

#### C5 — Permit-leak / thread-leak cleanup is sound (refuted as a finding)

Candidate finding considered: "a failed assertion could leak a held permit into later tests." Refuted on two independent grounds. (1) DB isolation: `DbTestBase.@Before` derives a unique `databaseName` per `@Test` method and `@After` drops it and closes `youTrackDB`, so each test gets a fresh `SharedContext` and a fresh `MetadataWriteMutex` instance — a leaked permit cannot cross test boundaries at the mutex level. (2) Thread + permit hygiene: every worker is spawned daemon via `spawn(...)` and bounded-joined in `@After joinSpawnedWorkers` (5 s, then interrupt + `fail`), so a stuck `acquireUninterruptibly` cannot keep the forked JVM alive or silently swallow a leak; and the two direct-mutex tests (`sameThreadSecondSessionEngageThrows`, `differentThreadParksUntilRelease`) `releaseFor(...)` in `finally`. The release-on-`fail` story is solid. No finding.
