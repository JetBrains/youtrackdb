<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 1, suggestion: 4}
scope: "git show 449d1745c0 — Track 7 Step 4 (ScalableRWLock.exclusiveLockWithAbort primitive + unit tests), code-baseline charter"
index:
  - {id: BG6, sev: should-fix, loc: "ScalableRWLock.java:705-735 (phase 2)", anchor: "### BG6 ", basis: "a RuntimeException thrown by the abort predicate inside the phase-2 drain poll (:717) or the success-edge re-check (:731) propagates with the write bit still held — no try/finally; the bit leaks permanently (readers spin-yield forever on isWriteLocked, writers park forever), and the caller cannot know it must unlock"}
  - {id: CQ6, sev: suggestion, loc: "ScalableRWLock.java:692-700", anchor: "### CQ6 ", basis: "interrupt-path diagnostics: the message hard-codes 'storage write lock' in a generic primitive used by 8+ non-storage locks (WAL segment/cutting, AsyncFile, config, compaction), and the 'queued against …' clause misreports 'contending writers' for a pre-interrupted thread on a free lock (StampedLock checks Thread.interrupted() before attempting acquisition)"}
  - {id: TQ6, sev: suggestion, loc: "ScalableRWLockTest.java (new section :300-624)", anchor: "### TQ6 ", basis: "the pollNanos <= 0 IllegalArgumentException branch (:675-677) has no test; the null-predicate shape is also unpinned; multi-waiter contention on the primitive itself (two concurrent exclusiveLockWithAbort callers) is untested"}
  - {id: TQ7, sev: suggestion, loc: "ScalableRWLockTest.java:328-353, :361-410, :441-495", anchor: "### TQ7 ", basis: "on assertion-failure paths the helper threads leak (abort flag never set → waiter polls forever at 5 ms; latches never counted down → reader parks forever); contained by test-local lock instances and surefire's fork exit, but a try/finally cleanup would keep failed runs quiet"}
  - {id: TQ8, sev: suggestion, loc: "ScalableRWLockTest.java:575,591 + :289-290", anchor: "### TQ8 ", basis: "test-code nits: dead `attempt` AtomicInteger in the stress writer (incremented, never read); fully-qualified java.util.function.BooleanSupplier in awaitCondition's signature instead of an import"}
evidence_base: {section: "## No-issue verifications (null verdicts)", certs: 18}
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED]
-->

# Code-baseline review — Track 7 Step 4, commit 449d1745c0 (iter 1)

**Artifact:** `git show 449d1745c0` ("Add abort-predicate write acquisition to ScalableRWLock" — the CN19/CN25 `exclusiveLockWithAbort` primitive for the Track 7 freezer gate's third checkpoint).
**In-scope files:** `core/src/main/java/com/jetbrains/youtrackdb/internal/common/concur/lock/ScalableRWLock.java` (appended method `exclusiveLockWithAbort` + 3 import lines, :26-27, :37, :621-735); `core/src/test/java/com/jetbrains/youtrackdb/internal/common/concur/lock/ScalableRWLockTest.java` (+362 lines: 7 new tests + `awaitParked`/`awaitCondition` helpers, :270-624).
**Charter:** correctness & bugs (parameter validation — `pollNanos <= 0`, null predicate; exception paths; `DatabaseException` construction and message quality; no write-bit leak on ANY exit including runtime exceptions from the predicate itself); code quality (readability, guarantee-pair citation comments per spec, duplication vs `exclusiveLock`/`exclusiveTryLockNanos`); test quality (contract vs implementation coupling, soundness of `awaitParked`/`awaitCondition`, the corrected stress assertion, thread cleanup on failure, `@Test` timeouts).
**Method:** decision criteria + numbered premises; file:line traces against the file as landed; full branch/exit-path enumeration on the new method (including predicate-throws and interrupt paths); counterexample per defect, justification per no-issue; alternative-hypothesis check; hypothesis log. Read-only; no Maven executed (another thread owns test execution in this worktree); no product code modified.

## Decision criteria

1. **No leaked write bit.** On every exit of `exclusiveLockWithAbort` — normal `true`, abort `false`, `IllegalArgumentException`, `DatabaseException` (interrupt), and *any* exception the predicate itself throws — the `stampedLock` write bit must be either provably not held or provably owned by a caller who knows to release it. A leaked bit is storage-fatal in the intended deployment: this class backs `AbstractStorage.stateLock`, the WAL segment/cutting locks, `AsyncFile`, and the atomic-operations locks; readers spin-yield on `isWriteLocked()` with no timeout.
2. **Contract as documented.** The two-guarantee pair (single-acquisition writer preference / one-poll-granularity abort), the CN25 success-edge re-check, and the Q-A3 interrupt shape (flag restored, `DatabaseException`, no state held) must hold exactly as the javadoc and the plan Step 4 text (`plan/track-7.md:202-208`) state.
3. **Existing methods byte-for-byte unchanged.** The commit's own risk-containment claim: readers and all pre-existing writer paths must be untouched by the diff.
4. **Tests pin the contract, are bounded, and don't poison the suite.** Each test must fail on a contract regression, must not false-positive through its wait helpers, must carry a timeout wherever a regression would hang, and a failing test must not corrupt later tests in the same fork.

## Branch / exit-path enumeration — `exclusiveLockWithAbort` (ScalableRWLock.java:674-735)

| # | Path | Lock state on exit | Verdict |
|---|------|--------------------|---------|
| E0 | `pollNanos <= 0` → IAE (:675-677) | none touched | OK (untested — TQ6) |
| E1 | `abort == null` → NPE at first `abort.getAsBoolean()` (:683) | none touched (first poll precedes any acquisition) | OK (N2) |
| P1a | phase-1 poll true (:683-685) → `return false` | none held; a timed-out `tryWriteLock` node is unlinked by StampedLock internally | OK (N3) |
| P1b | phase-1 poll **throws** (:683) | none held (throw precedes acquisition) | OK — no leak on this sub-path |
| P1c | `tryWriteLock` returns 0 (:687) → loop | queued only; readers unaffected (they poll only `isWriteLocked()`) | OK |
| P1d | `tryWriteLock` throws `InterruptedException` (:688-702) → flag restored, `DatabaseException` | none held (`stamp == 0` guaranteed — the throw comes from the failed acquire) | OK (N4); message nits → CQ6 |
| P1e | `tryWriteLock` succeeds → phase 2 | bit held from here | — |
| S | snapshot block (:707-714) | bit held; byte-identical to `exclusiveLock` (:407-415) | OK (N6) |
| D1 | drain poll true (:717-722) → unlock, `return false` | released fully | OK |
| D2 | drain poll **throws** (:717) | **bit still held, exception propagates — LEAK** | **BG6** |
| D3 | success-edge poll true (:731-733) → unlock, `return false` | released fully | OK (N7) |
| D4 | success-edge poll **throws** (:731) | **bit still held, exception propagates — LEAK** | **BG6** |
| R | `return true` (:734) | bit held by contract; caller releases via `exclusiveUnlock()` | OK |

---

## Findings

### BG6 — should-fix — a predicate that throws during phase 2 leaks the write bit permanently

**Location:** `ScalableRWLock.java:705-735` — the phase-2 drain poll (:717) and the success-edge re-check (:731) call `abort.getAsBoolean()` while the write bit is held, with no `try`/`finally` or `catch` around them.

**Premises:**
1. From :687's successful `tryWriteLock` until :734's `return true`, the write bit is held (that is the method's core design — single acquisition, writer preference).
2. `abort.getAsBoolean()` is arbitrary caller code (`BooleanSupplier`). The javadoc constrains it to be "cheap" and to "not itself touch this lock" (:663-665) but says nothing about throwing, and nothing enforces either constraint.
3. If :717 or :731 throws a `RuntimeException`, the method exits exceptionally with the bit held. There is no unwind: neither unlock site (:721, :732) is on the exceptional path.
4. The caller cannot recover safely. The contract (:666-668) defines lock state only for the `true`/`false` returns; on an exceptional exit the caller does not know whether the bit is held (a phase-1 predicate throw exits with *no* bit held — the same exception type can mean either state). Calling `exclusiveUnlock()` "just in case" would throw `IllegalMonitorStateException` when the bit was *not* held (phase-1 throw), so no blanket `finally` on the caller side is correct either.
5. Consequence of the leak: `isWriteLocked()` stays true forever. Every `sharedLock()` spins in the yield loop (:352-354) forever, uninterruptibly; every writer parks forever. On the intended consumer (`AbstractStorage.stateLock`, Step 5 checkpoint (2)) this is a full-storage hang.

**Counterexample (deterministic, fresh lock — two predicate calls total per N13's poll-shape analysis):**
```java
var lock = new ScalableRWLock();
var calls = new AtomicInteger();
try {
  lock.exclusiveLockWithAbort(() -> {
    if (calls.incrementAndGet() == 2) {   // call 1: phase-1 entry poll → false;
      throw new IllegalStateException();  // call 2: success-edge re-check → throws
    }
    return false;
  }, 1_000_000L);
} catch (IllegalStateException expected) { }
// lock.isWriteLocked() == true forever; lock.sharedLock() now never returns.
```
The same shape hits the drain poll (:717) whenever a residual reader is present.

**Alternative hypotheses checked:**
- *"The intended predicate (`operatorFreezeRequests > 0`, one atomic read — plan :210) cannot throw, so the path is unreachable in practice."* True for Step 5's wiring, which is why this is should-fix and not blocker. But the method is a public member of a shared lock class used storage-wide (8+ instantiation sites: `AbstractStorage.java:376/501`, `CASDiskWriteAheadLog.java:151/155`, `AsyncFile.java:34`, `AtomicOperationsManager.java:66`, `AtomicOperationsTable.java:105`, `CollectionBasedStorageConfiguration.java:155`); nothing scopes the predicate, and the failure mode (silent permanent storage hang, no exception at the hang site) is maximally expensive to diagnose. The charter's own criterion — "no leaks of the write bit on ANY exit incl. unexpected runtime exceptions from the predicate itself" — is not met.
- *"The javadoc constraint on the predicate is the containment."* Rejected: the constraint text does not mention throwing, and a documented constraint is not a release path. The hardening is one construct: wrap phase 2 (from :707 to :734) so that any throw releases the bit before propagating, e.g. `try { … } catch (Throwable t) { stampedLock.asWriteLock().unlock(); throw t; }`, or set a `success` flag and unlock in `finally` when `!success && !returnedFalse`. Cost: ~4 lines, zero effect on the hot path.
- *"`exclusiveLock` has the same exposure."* Rejected: `exclusiveLock` (:400-424) runs no foreign code between acquisition and return — its only conceivable throws are VM-level errors (OOM in `toArray`), against which nothing defends anywhere. This method is the first in the class to invoke a caller-supplied callback while holding the bit; the exposure is genuinely new with this commit.

**Suggested fix shape (for the fix thread — do not apply here):** enclose the snapshot + drain + edge-check in a try that on any `Throwable` unlocks and rethrows. Keep the two existing explicit unlock-and-return-false sites as they are (they must not double-unlock — either move them to the flag-and-finally form or have the catch rethrow only, with the unlock done exactly once). Add a regression test: predicate returns false once then throws; assert the exception propagates AND `isWriteLocked()` is false AND `sharedTryLock()` succeeds afterwards.

### CQ6 — suggestion — interrupt-path diagnostics: caller-specific wording in a generic primitive; the queued-against clause can misreport

**Location:** `ScalableRWLock.java:692-700`.

**Premises:**
1. The message hard-codes "the storage write lock" (:694). `ScalableRWLock` is a generic utility with 8+ non-storage instantiations (see BG6 premise 5's site list). If any of them ever adopts the primitive, the exception names the wrong lock. The class itself knows nothing about "storage".
2. The parenthetical diagnosis `(… queued against " + (stampedLock.isWriteLocked() ? "a holding writer" : "contending writers") + ")"` (:696-697) has two soft spots: (a) it is read *after* the interrupt, so the holder may have released in between — a wait that was in fact against a holding writer reports "contending writers"; (b) `StampedLock.tryWriteLock(long, TimeUnit)` checks `Thread.interrupted()` **before** attempting acquisition (JDK behavior), so a thread entering pre-interrupted throws immediately even when the lock is completely free — the message then claims "queued against contending writers" when there was neither queueing nor contention.
3. Both are diagnostic-quality issues only: the flag restore (:690), the exception type (spec-pinned, see N5), and the "write bit not acquired" claim (:695 — always true on this path, `stamp == 0`) are all correct.

**Alternative hypothesis checked:** *"The wording is intentional — the sole planned caller is the storage third checkpoint, and naming the deployment makes the operator log actionable."* Plausible and consistent with the plan (Step 5 is the only consumer); that intent is why this is a suggestion, not should-fix. A phrasing that stays truthful for any owner ("the exclusive lock" / dropping the racy queued-against diagnosis, or sampling `isWriteLocked()` best-effort with a hedge word) would cost nothing.

### TQ6 — suggestion — parameter-validation and multi-waiter shapes unpinned

**Location:** `ScalableRWLockTest.java` new section (:300-624); `ScalableRWLock.java:675-677`.

**Premises:**
1. The `pollNanos <= 0` → `IllegalArgumentException` branch is new public contract ("Must be positive", :665) with zero tests. Trivial by inspection, but the repo's test-authorship policy asks for coverage of new behavior, and the test is two lines.
2. The null-predicate shape (NPE before any lock state — N2) is likewise unpinned; if the team prefers an explicit `Objects.requireNonNull`, a test would force the decision.
3. No test runs two concurrent `exclusiveLockWithAbort` callers against each other (the delivered phase-1 test queues one abort-waiter behind a plain `exclusiveLock` holder). The primitive introduces no per-instance state, so the value is modest — but the phase-1 "abort leaves no trace" claim under *mutual* primitive contention (winner acquires, loser aborts, then roles swap) is the one contract combination with no direct witness.

**Counterexample gist:** a future edit that weakens the guard to `pollNanos < 0` (accepting 0 and busy-spinning `tryWriteLock(0)`) passes the entire suite today.

### TQ7 — suggestion — helper threads leak on assertion-failure paths

**Location:** `ScalableRWLockTest.java:328-353` (`abortWhileQueuedBehindWriterReturnsFalsePromptly`), :361-410 (`abortDuringReaderDrainReleasesBitAndReadersProceed`), :441-495 (`writerPreferenceRefusesNewReadersWhileBitHeld`); pattern also in :502-533.

**Premises:**
1. In :328-353, if the `awaitParked` assertion (:339-341) fails, `abort` is never set and the main thread never releases its `exclusiveLock` — the non-daemon waiter thread then re-arms `tryWriteLock(5 ms)` forever. The `@Test(timeout = 30_000)` aborts the *test*, not the waiter.
2. In :361-410 and :441-495, an assertion failure between `reader.start()` and `readerMayLeave.countDown()` leaves the reader parked on the latch forever (and in :441-495 the writer parked on `mayRelease`), holding a shared/exclusive lock on the test-local instance.
3. Containment (why this is only a suggestion): every test uses a **fresh** `ScalableRWLock`, so a leaked thread cannot block any other test's lock; surefire's forked booter exits via `System.exit`, so non-daemon leftovers cannot hang the build; the leaked waiter's 5 ms poll is the worst CPU cost. The pre-existing tests in this file (e.g. `testWriterBlocksWhileReaderHoldsLock`, :243-270) have the same shape, so the new code is consistent with the file's existing idiom.
4. A `try`/`finally` that counts down all latches, sets the abort flag, and `join`s with a short bound (or daemon-marking the helpers) would make failed runs clean and keep thread dumps readable when diagnosing a real failure.

**Counterexample gist:** `awaitParked` returns `RUNNABLE` on a badly stalled CI box → assertion fails → the surviving waiter thread pollutes the thread dump of every subsequently diagnosed failure in the same fork, at 200 wakeups/second.

### TQ8 — suggestion — test-code nits: dead counter, fully-qualified type

**Location:** `ScalableRWLockTest.java:575` + :591; :289-290.

1. The stress writer's `attempt` `AtomicInteger` (:575) is incremented (:591) and never read — leftover from the pre-correction assertion iteration described in the episode log (`plan/track-7.md:400-404`). Dead weight; delete.
2. `awaitCondition` spells out `java.util.function.BooleanSupplier` in its signature (:290) although the type could be imported (the production file imports it; the test file currently has no conflicting import). Import order/style is otherwise Spotless-clean by inspection (2-space indent, ≤100 cols, no wildcards) — could not run `spotless:check` under the read-only constraint.

---

## No-issue verifications (null verdicts)

- **N1 — `pollNanos` validation.** :675-677 rejects `<= 0` with a message carrying the value, before any state is touched. Correct fail-fast; only the missing test (TQ6) is noted.
- **N2 — null predicate.** First dereference is :683, before `tryWriteLock` — an NPE exits with no lock state. On JDK 21 helpful NPEs name the parameter (`... because "abort" is null`), so the implicit failure is diagnosable. No leak; explicit `requireNonNull` optional (TQ6 premise 2).
- **N3 — phase-1 abort leaves no trace.** A timed `tryWriteLock` that returns 0 has had its queue node cancelled/unlinked inside `StampedLock`; readers never consult the stamped queue (they poll only `isWriteLocked()`, :318/:352). Verified against the reader paths (:340-359) — a queued candidate is invisible to them. The test at :328-353 witnesses holder-undisturbed + reusable.
- **N4 — interrupt path state and exception plumbing.** The throw at :692 can only originate from :687 with `stamp == 0` (the assignment never completed), so "write bit not acquired" (:695) is always true. `BaseException.wrapException(exc, cause, (String) null)` (BaseException.java:39-64): the cause (`InterruptedException`) is neither `BaseException` nor `HighLevelException`, so the method `initCause`s and returns the *same* `DatabaseException` — message intact, cause attached, dbName null. The flag restore (:690) precedes the throw. The test (:502-533) pins type, message fragment, and flag.
- **N5 — `DatabaseException` vs package precedent.** The same package's own interrupt convention is `ThreadInterruptedException` (used via `wrapException` in `AdaptiveLock.java:97-101`, `OneEntryPerKeyLockManager.java:212-217`, `ResourcePool.java:93`). The deviation is **spec-pinned**: plan Step 4 (`plan/track-7.md:202`) and the Q-A3 pin (3) name `DatabaseException` explicitly, and Step 5's gate consumes that type. Recorded as checked-and-deferred-to-spec, not a finding.
- **N6 — snapshot/drain duplication.** The snapshot block (:707-714) is byte-identical to `exclusiveLock` (:406-415) / `exclusiveTryLock` (:551-560) / `exclusiveTryLockNanos` (:601-610) — now a 4th copy. Extracting a helper would touch the three existing methods, contradicting the commit's stated risk-containment invariant ("all existing methods are byte-for-byte unchanged" — javadoc :660, commit message) on a battle-tested primitive. Deliberate, documented trade-off; accepted. A follow-up refactor is possible once the primitive has soaked, but is not requested here.
- **N7 — CN25 success-edge re-check.** :728-735 runs after the drain for-loop completes, which on an empty snapshot is immediately after bit acquisition — exactly the zero-residual-readers case the comment (:729-730) and the plan pin describe. Unlock precedes the `return false`. Verified against the deterministic test (:417-431).
- **N8 — guarantee-pair citation comments: present and accurate.** The spec/episode requirement (`plan/track-7.md:392-394`: guarantee pair + no-new-deadlock-edge cited in javadoc/inline comments) is met by :625-668 (two-guarantee bullet pair, success-edge paragraph, no-new-deadlock-edge paragraph, interrupt paragraph) and the three inline phase comments (:679-681, :704-706, :718-720, :728-730). Accuracy audit: single-acquisition-and-hold ✓ (no release between :687 and the abort/return sites); "polled … on every yield iteration" ✓ (:717 precedes :723); "between phase-1 attempts" ✓ (:683, loop-top, includes entry); "released fully — no residual writer-intent state" ✓ for the `false` returns (the predicate-throws exception is BG6); "no parking channel to lose a wakeup on" ✓ for readers (yield-spin on the bit, :352) — parked *writers* are woken by `StampedLock`'s own unlock. The only claim BG6 dents is the `@param abort` sentence "returns false with no lock state held", which is silent about throws.
- **N9 — no new deadlock edge.** The method blocks only at :687 (stamped queue, bounded by `pollNanos` per attempt, abort-checked between) and the drain spin (:716-724, abort-checked per iteration). Both are waits `exclusiveLock` already performs, now abort-bounded. No lock ordering introduced; the predicate is documented to not touch the lock (:664).
- **N10 — memory model.** The drain reads the readers' `AtomicInteger` states (volatile semantics) identically to `exclusiveLock`; the Dekker reasoning documented at :341-343/:404 is unchanged. The abort predicate's visibility is the supplier's own concern (tests use `AtomicBoolean`/`AtomicInteger`; Step 5 will use an atomic counter read). No new ordering obligations created.
- **N11 — `awaitParked` cannot false-positive.** :275-286. The observed thread's runnable (`exclusiveLockWithAbort` only) reaches WAITING/TIMED_WAITING solely inside `tryWriteLock`'s park — `Thread.yield()` stays RUNNABLE, there are no sleeps/latches on that thread, and class-loading/JIT stalls report RUNNABLE or BLOCKED. Accepting both WAITING and TIMED_WAITING is harmless slack. On timeout it returns the last state and the caller's assertion fails with the state named — bounded, diagnosable.
- **N12 — `awaitCondition` sound.** :288-297. Bounded spin with a final post-deadline re-check (avoids a false *negative* from a descheduling right at the deadline); returns the real condition value, so no false positive is constructible.
- **N13 — the exact-2-polls assertion is deliberate implementation coupling, correctly derived.** :417-431. On a fresh lock: poll 1 at :683 (false) → `tryWriteLock` succeeds (no contention) → snapshot of an empty `readersStateList` (the per-instance `ThreadLocal` guarantees no cross-test reader registration) → drain loop body never runs → poll 2 at :731 (true) → abort. Exactly two calls; the assertion is a scenario-validity canary whose failure message says precisely that ("a different count means the scenario drifted off the edge", :425-426). The contract assertions (returns false, bit released, reusable) stand separately. A benign future refactor adding a poll would fail this test — accepted cost for a deterministic CN25 witness; the message makes triage immediate.
- **N14 — corrected stress assertion is sound.** :544-624. Even-`k` attempts have `abortingAttempt == false`, and `&&` short-circuits before `polls.incrementAndGet()` — an even attempt's predicate is constant-false, and a constant-false-predicate acquisition cannot return false (phase 1 loops until acquired; the drain terminates because the held bit blocks reader re-entry — each reader's `sharedLock` backs off at :347-349). So `successes >= writerIterations / 2` is exactly "every non-aborting attempt acquires" (the CN19 bounded-acquisition witness), and `successes + aborts == writerIterations` holds because each loop iteration increments exactly one counter and any throw is routed to `failures` (:592-594) and rethrown (:606-608). The episode-documented correction (an "aborting" odd attempt may legitimately succeed when acquisition beats the 3-poll flip threshold, `plan/track-7.md:400-404`) is reflected in the comment (:610-614) and is the right reading of the contract.
- **N15 — timeouts and bounded waits everywhere.** All 7 new tests carry `@Test(timeout = 30_000)` (:306, :328, :361, :417, :442, :502) or `60_000` (:544); every `join`/`await` is bounded (5 s / 50 s) with follow-up `isAlive`/result assertions. A regression that hangs any phase (phase-1 park, drain spin, stress) is converted to a test failure, not a build hang.
- **N16 — interrupt test is race-free.** :502-533. If the interrupt lands while the waiter is between parks (in the :683 poll), the next `tryWriteLock` still throws — `StampedLock.tryWriteLock(long, TimeUnit)` checks `Thread.interrupted()` at entry. Every interleaving after `awaitParked` reaches the catch; no flake window.
- **N17 — spec test-matrix coverage.** Plan Step 4's six scenarios (`plan/track-7.md:206`) map onto the seven tests: phase-1 queue abort → :328; drain abort → :361; CN25 edge → :417; interrupt → :502; no-abort path → :306; bounded acquisition under sustained readers → :544 (all non-aborting attempts acquire under 4 looping readers) plus the deterministic no-inter-attempt-release-window witness :441. The "≤ max residual reader residence" bound is pinned qualitatively (all non-aborting attempts complete), which is the strongest deterministic form available without timing assertions.
- **N18 — existing methods byte-for-byte unchanged.** The production diff is append-only: 2 import lines (:26-27), 1 import (:37), and the new method (:621-735). No reader or pre-existing writer path is touched; the commit's containment claim holds.

## Hypothesis log

| # | Hypothesis | Outcome |
|---|-----------|---------|
| H1 | Abort already true at entry acquires something before returning false | Refuted — :683 precedes :687; E1/P1a paths hold nothing |
| H2 | A window exists where the bit is acquired but the loop-top abort poll is skipped, missing an abort forever | Refuted — post-acquisition aborts are the drain poll (:717) + CN25 edge (:731); zero-reader case covered by the edge check |
| H3 | `asWriteLock().unlock()` mismatched with the stamp-based acquire | Refuted — view unlock releases regardless of stamp value; same idiom as :554/:614/:431 |
| H4 | Snapshot block diverges from `exclusiveLock`'s linearizability recipe | Refuted — byte-identical (N6) |
| H5 | Predicate result visibility needs extra fencing in the drain loop | Refuted — supplier-owned; loop already reads volatile reader states (N10) |
| H6 | Interrupt during the phase-2 drain corrupts state | Refuted — drain is an uninterruptible yield spin exactly like `exclusiveLock`'s; documented at :657-659; flag survives to the caller |
| H7 | Predicate throwing while the bit is held leaks the bit | **Confirmed — BG6** (paths D2/D4) |
| H8 | `awaitParked` can observe a park unrelated to `tryWriteLock` | Refuted (N11) |
| H9 | Stress assertion `successes >= half` can pass while a non-aborting attempt failed | Refuted — constant-false predicate makes `false` unreachable for even attempts (N14) |
| H10 | Phase-1 abort leaves a stale stamped-queue node that blocks later writers | Refuted — StampedLock unlinks timed-out nodes; reuse pinned by :349-352 |
| H11 | Diff silently modifies an existing method | Refuted — append-only (N18) |
| H12 | `DatabaseException` violates the package's `ThreadInterruptedException` convention as an oversight | Refuted as a finding — spec-pinned choice (N5); wording nits filed as CQ6 |
| H13 | "queued against …" diagnosis always truthful | Confirmed misreport windows (post-release race; pre-interrupted-on-free-lock) — CQ6 |
| H14 | Failed assertions leak threads that poison later tests | Partially confirmed — threads leak but cannot poison other tests (fresh lock per test, fork exits via `System.exit`) — TQ7 |

## Verdict summary

The primitive is a faithful implementation of the CN19/CN25/Q-A3 spec: single bit-acquisition with writer preference, per-iteration abort polling, a correct success-edge re-check, clean abort releases on both polled paths, a correct interrupt shape, and an append-only diff that honors the byte-for-byte containment claim. The seven tests are well-aimed at the contract, deterministic where determinism is achievable (the 2-poll CN25 canary), and honestly corrected where the first stress assertion over-claimed. One real defect: the write bit leaks on the one exit class the method's own release discipline doesn't cover — a throwing predicate in phase 2 (BG6, should-fix, ~4-line hardening + 1 regression test). Everything else is polish.
