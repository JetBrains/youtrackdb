<!-- MANIFEST
findings: 9   severity: {blocker: 0, should-fix: 5, suggestion: 4}
scope: "git show 11bf0eda26 — Track 7 Step 3 (Draft A: metadata-write-mutex permit handshake + Q-A2 skip protocol), code-baseline charter"
index:
  - {id: BG3, sev: should-fix, loc: "DatabaseSessionEmbedded.java:270,3338-3344,3921-3934 + DatabaseSessionEmbeddedPooled.java:63-95", anchor: "### BG3 ", basis: "both-act overlap (pool fall-through teardown vs owner completer) can double-run internalClose: the 'one-shot status guard' is a plain non-atomic check-then-act on the non-volatile session status; double storage.close(this) double-decrements sessionCount"}
  - {id: BG4, sev: should-fix, loc: "DatabaseSessionEmbeddedPooled.java:84-89", anchor: "### BG4 ", basis: "the Q-A2 skip WARN resolves to the warn(Object,String dbName,String message,Object...) overload — the emitted message is just the database name; the deferral text lands in the dbName slot (verified by compile+run experiment)"}
  - {id: BG5, sev: should-fix, loc: "DatabaseSessionEmbedded.java:279,3943-3946", anchor: "### BG5 ", basis: "hasInFlightForeignCommit routes through a plain (non-volatile) currentTx read; the pool thread has no happens-before edge to the owner's begin(), so it can miss a genuinely in-flight commit — contradicting the 'safe from a foreign thread' doc claim"}
  - {id: CQ3, sev: should-fix, loc: "MetadataWriteMutexTest.java:36-37 (class Javadoc)", anchor: "### CQ3 ", basis: "class Javadoc still says the abnormal-termination handshake 'is a later track and not exercised here' while this commit adds exactly those tests"}
  - {id: CQ4, sev: suggestion, loc: "MetadataWriteMutex.java:147 + AbstractStorage.java:1575-1601", anchor: "### CQ4 ", basis: "engage loop-top self-check calls session.isClosed(), which takes the storage stateLock read lock — behind a schema commit's held write lock the waiter blocks uninterruptibly there, degrading the pinned interruptibility/WARN cadence; the new test deliberately avoided isClosed() for this exact reason"}
  - {id: CQ5, sev: suggestion, loc: "MetadataWriteMutex.java:199,209 + DatabaseSessionEmbedded.java:3928-3932 + DatabasePoolImpl.java:135-139", anchor: "### CQ5 ", basis: "diagnostic payload gaps: releaseFor warn-noops omit the releasing session's identity; completer warn and pool-close error omit the database name"}
  - {id: TQ3, sev: should-fix, loc: "MetadataWriteMutexTest.java teardownRollbackThrowBeforeTxCloseStillReleasesPermit", anchor: "### TQ3 ", basis: "test description claims rollbackInternal throws 'already rolled back' ISE; actually session.rollback() skips the inactive ROLLED_BACK tx entirely — no throw occurs; the test pins a valid scenario (tx.close never ran) under a wrong mechanism, and the forced-status hack leaves the storage tx's atomicOperation un-deactivated"}
  - {id: TQ4, sev: suggestion, loc: "MetadataWriteMutexTest.java engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree + DatabaseSessionEmbedded.java:3839-3847", anchor: "### TQ4 ", basis: "contains(\"while\") is a near-vacuous message pin; the engage post-acquire Dekker self-release branch has no deterministic test (only the loop-top branch is exercised); doubleRelease cleanup NPEs if the prober died early"}
  - {id: TQ5, sev: suggestion, loc: "MetadataWriteMutexTest.java poolCloseDuringCommitDefersTeardownToOwner", anchor: "### TQ5 ", basis: "no timeout guard: a regression that makes pool.close() block inside the commit window hangs the surefire fork (the window-release finally is never reached) instead of failing the test"}
evidence_base: {section: "## No-issue verifications (null verdicts)", certs: 11}
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED]
-->

# Code-baseline review — Track 7 Step 3, commit 11bf0eda26 (iter 1)

**Artifact:** `git show 11bf0eda26` ("Harden metadata-write mutex lifecycle handshake" — Draft A: metadata-write-mutex permit handshake + Q-A2 skip protocol).
**In-scope files:** `MetadataWriteMutex.java` (rewrite), `DatabaseSessionEmbedded.java`, `DatabaseSessionEmbeddedPooled.java`, `DatabasePoolImpl.java`, `FrontendTransactionImpl.java`, `MetadataWriteMutexTest.java`.
**Charter:** correctness & bugs (logic, null safety, lifecycle — captured `engagedMutex` reference, close/closeInternal wrap semantics, catch-Throwable sites, InterruptedException handling); code quality (state-machine readability, log payload); test quality (behavior-vs-implementation, regression sensitivity, jstack-debugged patterns, flake risk).
**Method:** decision criteria + numbered premises; file:line traces against the diff and surrounding code as landed; branch/error-path enumeration on every changed path; counterexample per defect, justification per no-issue; alternative-hypothesis check; hypothesis log. Read-only; no Maven executed (another thread owns test execution in this worktree). One scratch `javac` experiment outside the worktree (overload-resolution verification, see BG4).

## Decision criteria

1. **Single-permit invariant.** In every interleaving of the three release sites (owner tx-close finally, foreign teardown release pass, engage Dekker self-release), the permit is released **exactly once** per acquisition — never zero (strand), never twice (single-writer break).
2. **No unreleased acquisition.** Every path that acquires the permit must have a reachable releaser, including all throw points between acquire and the normal release site.
3. **Prior close semantics preserved.** The `close()`→`closeInternal()` wrap must not change what the pre-change `close()` did, including finally ordering and exception propagation; additions must be strictly additive and throw-isolated.
4. **Teardown containment claims must hold as coded.** Where a comment claims a race is "contained" by a guard, the guard must actually contain it under the JMM (atomicity + visibility), not just under sequential intuition.
5. **Diagnostics must diagnose.** New WARN/error paths exist to make abnormal states operator-visible; they must emit the payload they claim (session, ordinal, thread, elapsed) through the actual logging API resolution.
6. **Tests pin behavior, not implementation.** Each test must fail on a regression of the contract it names; its description must match the mechanism it actually exercises; no unbounded timing assumptions.

---

## Findings

### BG3 — should-fix — both-act overlap can double-run `internalClose`; the "one-shot status guard" does not contain it

**Location:** `DatabaseSessionEmbedded.java:270` (plain `private STATUS status`), `:3338-3344` (one-shot guard), `:3921-3934` (`completeDeferredTeardownAfterTxClose`), `:321-330` (`internalCloseInProgress` doc claiming containment); `DatabaseSessionEmbeddedPooled.java:63-95` (`realClose` fall-through arm); `AbstractStorage.java:630-640` (`close(session)` decrement + `StorageException` on negative count).

**Premises:**
1. The pool-side handshake is: `markTeardownIntent()` (volatile write), then `hasInFlightForeignCommit()` re-validation; when the tx is **not** COMMITTING-on-foreign-thread, `realClose` falls through to `super.close()` → `internalClose(false)` on the pool thread (`DatabaseSessionEmbeddedPooled.java:73-94`).
2. The owner-side completer is: tx-close (`FrontendTransactionImpl.close()`:1028-1040) → `completeDeferredTeardownAfterTxClose()`, which runs `internalClose(false)` on the owner thread when `teardownIntent && !internalCloseInProgress && status == STATUS.OPEN` (`DatabaseSessionEmbedded.java:3921-3926`).
3. The field-level doc (`:326-329`) explicitly names the cross-thread overlap "the benign both-act case, contained by the one-shot status guard and the atomic release claim."
4. The one-shot guard is `if (status != STATUS.OPEN) return;` (`:3339`) on a **plain** field (`:270`) with a plain-write `status = STATUS.CLOSED` later in the body (`:3373`). `internalCloseInProgress` is also plain by design (`:330`).
5. Two threads can therefore both pass the guard: (a) visibility — the owner may read a stale `OPEN` after the pool thread's plain `CLOSED` write (no happens-before edge exists from the pool's status write to the owner's read; the volatile `teardownIntent` read only orders against the *mark* write, which precedes the status write); and (b) atomicity — even with perfect visibility, check-then-act on `status` is not atomic, so two threads reading `OPEN` concurrently both proceed.

**Trace of the both-act interleaving (all steps legal under the JMM):**
- Owner is in `doCommit` → `close()` → `closeInternal()`; `closeInternal` writes tx `status = TXSTATUS.INVALID` (volatile, `FrontendTransactionImpl.java:1064`).
- Pool thread runs `realClose`: `markTeardownIntent()` (W_mark), then reads tx status — sees `INVALID` (the owner's write is in the volatile synchronization order) → `isCommittingOnForeignThread()` false → **fall-through**: `activateOnCurrentThread(); super.close()` → `internalClose(false)`.
- Owner finishes `closeInternal`, enters the wrapper finally → `completeDeferredTeardownAfterTxClose()`: reads `teardownIntent == true` (volatile, sees W_mark), `internalCloseInProgress` — plain read, may see `false` (stale or not-yet-written), `status` — plain read, sees `OPEN` (pool hasn't written `CLOSED` yet, or the write isn't visible) → **also** calls `internalClose(false)`.
- Both threads pass `status != STATUS.OPEN` (both read `OPEN`), both run the full body: `rollback()` (both no-op, tx INVALID), `callOnCloseListeners()` **twice**, `storage.close(this)` **twice**.

**Counterexample consequence:** `AbstractStorage.close(session)` (`AbstractStorage.java:631`) is an unconditional `sessionCount.decrementAndGet()`. A double decrement for one session makes the count wrong by one: either a later legitimate close of an *unrelated* session throws `StorageException("Amount of closed sessions in storage ... is bigger than amount of open sessions")`, or storage idle-close logic fires while a live session exists. Close listeners also fire twice. Note `DbTestBase.java:140-142` shows pools being closed and re-created against a *live* storage at runtime — this is not confined to process shutdown.

**Alternative hypotheses checked:**
- *"The window can't happen because fall-through requires !COMMITTING while the completer requires the owner inside tx.close."* Refuted: `closeInternal` writes tx status `INVALID` **before** the wrapper finally runs the completer — the owner is inside tx.close precisely while the tx status is already non-COMMITTING. The window is the entire tail of every tx close that overlaps a pool close.
- *"The atomic release claim contains it."* It contains the **permit** (verified, see null verdict N2) — but nothing else in `internalClose` is idempotent-under-concurrency: the sessionCount decrement and listener invocation are not gated by the claim.
- *"Making `status` volatile fixes it."* No — volatility fixes only the visibility half; the check-then-act remains. Containment needs an atomic claim (e.g., a `getAndSet`/`compareAndSet`-style one-shot on teardown entry, reset on pooled `reuse()`), mirroring exactly the pattern the commit already uses for the permit.

**Recommendation:** make the `internalClose` one-shot guard an atomic claim (dedicated atomic field claimed after the current status check, reset in `reuse()`/`internalOpen`), or route both the completer and the pool fall-through through a single atomic "teardown ticket." Update the `internalCloseInProgress` field doc, which currently asserts a containment that does not hold.

---

### BG4 — should-fix — the Q-A2 skip WARN logs the database name as the whole message (wrong `warn` overload)

**Location:** `DatabaseSessionEmbeddedPooled.java:84-89`.

**Premises:**
1. The call is `LogManager.instance().warn(this, "Pool close found session of database '%s' mid-commit...; deferring...", getDatabaseName())` — shape `(Object, String, String)`.
2. `SLF4JLogManager` declares three `warn` overloads (`SLF4JLogManager.java:197, 214, 231`): `(Object, String message, Object...)`, `(Object, String message, Throwable, Object...)`, and `(Object, String dbName, String message, Object...)`.
3. For a `(Object, String, String)` call, both the 2-string and the dbName overloads are applicable in the varargs phase; JLS most-specific rules pick the **dbName overload** (`String` is more specific than the `Object` varargs component).
4. **Empirically verified** with a scratch javac/java reproduction of the three signatures: the call resolves to the dbName overload (printed "dbName overload"); the same experiment confirmed the `MetadataWriteMutex` engage-loop warn (`(Object, String, long, String)`) correctly resolves to the message overload.

**Counterexample:** a pool close hits the Q-A2 skip in production; the only log line evidencing the deferral emits — as its entire message — the raw database name (e.g. `test_db`), with the descriptive text consumed as the `dbName` context field and the `%s` never formatted. The one diagnostic this branch exists to produce is garbled.

**Recommendation:** pass the db name through the format args of the message overload unambiguously, e.g. use the 4-arg form explicitly `warn(this, getDatabaseName(), "Pool close found session mid-commit...; deferring...", ...)` or make the third argument non-String (or cast to `Object`). Grep-check: the other five new log call sites in this commit resolve correctly (verified individually — `MetadataWriteMutex.java:167,199,209` have a non-String arg in third position; `DatabasePoolImpl.java:135` and `DatabaseSessionEmbedded.java:3874,3928` use the Throwable-bearing overloads).

---

### BG5 — should-fix — skip detection reads the tx object through a plain field; the "safe from a foreign thread" claim doesn't hold end-to-end

**Location:** `DatabaseSessionEmbedded.java:279` (`private FrontendTransaction currentTx` — plain), `:3937-3946` (`hasInFlightForeignCommit` + its doc); `FrontendTransactionImpl.java:84-91, 162-167, 186-194` (the volatile fields and their rationale comments).

**Premises:**
1. `hasInFlightForeignCommit()` reads `currentTx` (plain), then the tx's volatile `status`/`storageTxThreadId`. The doc — and the commit message — claim the detection "reads only the transaction's volatile status/storageTxThreadId, so it is safe from a foreign thread."
2. `currentTx` is reassigned on the owner thread (`:4454`, `begin()` path; `:2467/2499`, no-tx placeholders). The pool-close thread's only happens-before edges to a checked-out session date from the pool handoff machinery at acquire time — there is no edge covering a `begin()` executed after checkout.
3. Therefore the pool thread may legally read a **stale** `currentTx`: a previous transaction object (whose volatile `status` reads `INVALID`/`COMPLETED`/`ROLLED_BACK`) or a `FrontendTransactionNoTx` placeholder — while the owner is mid-COMMITTING on the current tx object.

**Counterexample:** owner borrows a pooled session, `begin()`s (new `FrontendTransactionImpl` assigned to `currentTx`), writes schema, enters `internalCommit`. Pool close runs `realClose`: reads a stale pre-begin `currentTx` → `instanceof FrontendTransactionImpl` fails or its status is `INVALID` → `hasInFlightForeignCommit()` false → fall-through full teardown of the live committing session — exactly the live-commit mutation (tx `clear()` mid-apply, cache shutdown under promotion reads) the Q-A2 protocol was built to prevent.

**Alternative hypothesis checked:** *"this is the residual TOCTOU the design accepts."* The accepted residual is a **timing** race on fresh values (late-skip / late-rollback around the volatile reads). This is a **visibility** gap on the route to those volatile fields: the detection can miss a commit that has been in flight for arbitrarily long, not just one that starts "at the same instant." The consequence equals today's (pre-change) behavior, so this is not a regression — but the design/doc claim of foreign-thread safety is inaccurate as coded, and the fix is one word.

**Recommendation:** make `currentTx` volatile (single-writer field, mutated only on the owner thread; no performance concern on this path), and soften/correct the two doc claims.

---

### CQ3 — should-fix — stale class-level Javadoc in `MetadataWriteMutexTest`

**Location:** `MetadataWriteMutexTest.java:36-37`.

The class Javadoc ends: *"The abnormal-termination permit handshake and the freezer gate are a later track and are not exercised here."* This commit adds ten tests exercising precisely the abnormal-termination handshake (strand heal, Dekker both shapes, double release, FM-A7 throw, Q-A2 skip matrix, interrupt, pool-loop isolation). Only the freezer-gate half of the sentence remains true. Per the repo's keep-comments-in-sync rule, this actively misleads the next reader about the file's coverage scope. Trivial fix.

---

### CQ4 — suggestion — engage loop-top `session.isClosed()` takes the storage state lock, weakening the interruptibility/cadence pins

**Location:** `MetadataWriteMutex.java:143-151` (loop-top self-check); `DatabaseSessionEmbedded.java:724-726` (`isClosed()` → `storage.isClosed(this)`); `AbstractStorage.java:1575-1601` (`isClosed` acquires `stateLock.readLock()` unless in the calling thread's own commit window).

**Premises:**
1. The loop-top check runs before the first `tryAcquire` and after every ~10 s timeout.
2. A schema-carry commit holds `stateLock.writeLock()` through the commit window (per the comment at `AbstractStorage.java:1577-1583`). A waiter parked on the mutex during such a commit wakes at the 10 s boundary, emits the WARN, then blocks **uninterruptibly** in `stateLock.readLock().lock()` inside `isClosed()` until the writer finishes.
3. During that block: the interrupt pin ("the waiter is killable") and the abort-on-own-teardown check are suspended; the wait is still bounded by the holder's commit, so this is a degradation, not a deadlock (waiter holds nothing while blocked — no inversion).
4. The commit's own test suite already encodes this hazard: `poolCloseDuringCommitDefersTeardownToOwner` deliberately uses a "lock-free status probe" (`pooled.getStatus()`) with the comment *"isClosed() would take the storage state lock and block behind the parked commit's held write lock"* — the production loop-top uses the very call the test had to avoid.

**Recommendation:** use a lock-free openness probe in the loop-top (e.g., `getStatus() == STATUS.CLOSED`, which together with `isTeardownIntentMarked()` covers the abort condition the loop needs) and keep the lock-taking `isClosed()` out of the wait path. Not should-fix because no invariant breaks and the block is bounded by the holder's progress.

---

### CQ5 — suggestion — diagnostic payload gaps in the new warn/error sites

**Location:** `MetadataWriteMutex.java:197-201` (release-skip warn), `:206-212` (lost-race warn); `DatabaseSessionEmbedded.java:3928-3932` (completer failure warn); `DatabasePoolImpl.java:134-139` (per-session close error).

The holder-side payload is exemplary (`describeHolder()`: db name, ordinal, thread name, elapsed seconds — `MetadataWriteMutex.java:226-234`), but:
1. The releaseFor skip/lost-race warns name the presented ordinal and the *current holder*, not the **releasing session** (`this` requester is the mutex). On a storage with many sessions, "release for ordinal 7 skipped" is not actionable without knowing who presented it. Add the releasing session's db name/identity.
2. The completer-failure warn and the pool-close per-session error carry no database/session name in the message (the requester object only yields a class name in the log record). Both fire in multi-database processes where "a pooled session failed to close" is ambiguous.

---

### TQ3 — should-fix — `teardownRollbackThrowBeforeTxCloseStillReleasesPermit` documents the wrong mechanism and leaves a dangling storage tx

**Location:** `MetadataWriteMutexTest.java` (first new test, ~line 649).

**Premises:**
1. The description claims: forcing tx status to `ROLLED_BACK` makes "rollbackInternal throw its 'already rolled back' IllegalStateException before reaching close()".
2. Actual trace: `victim.close()` → `internalClose` → session `rollback()` (`DatabaseSessionEmbedded.java:4819-4829`) → `if (currentTx.isActive())` — `isActive()` is false for `ROLLED_BACK` (`FrontendTransactionImpl.java:1544-1548`) → rollbackInternal is **never called**; nothing throws. The teardown proceeds normally; `tx.close()` simply never runs, so the tx's own release finally never fires and the widened outer-finally release pass is what frees the permit.
3. The test therefore **does** regression-pin the right contract (pre-change, this path stranded the permit: the only release site was `FrontendTransactionImpl.close()`'s finally, which is never reached) — but under a described mechanism that does not occur. A future reader "fixing" the test to actually make rollback throw, or a reviewer auditing coverage of the rollback-throw path, is misled: the *actual* rollback-throw path (rollback throwing mid-teardown) is still untested.
4. Side effect of the forced-status hack: the begun tx's `atomicOperation` is never deactivated (`closeInternal`'s deactivate block never runs; `rollbackInternal`'s `clear()` never runs), leaving the storage tx machinery on the test thread to be cleaned up by incidental tolerance. The test then reuses the same thread for `session.executeInTx` — currently green, but the contamination is invisible and thread-pool-order-dependent.

**Recommendation:** rewrite the description to state the actual scenario ("a teardown in which the session-level rollback is skipped and tx.close() never runs"), or drive a genuine rollback throw (e.g., a listener/hook that throws inside the rollback path) so the description and mechanism agree; note the dangling atomic operation explicitly if the hack stays.

---

### TQ4 — suggestion — weak message pin; the Dekker post-acquire self-release branch has no deterministic test; cleanup NPE mask

**Location:** `MetadataWriteMutexTest.java` `engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree`, `doubleReleaseKeepsSinglePermit`; `DatabaseSessionEmbedded.java:3839-3847`.

1. `assertTrue(expected.getMessage().contains("while"))` is a near-vacuous pin — it is satisfied by almost any sentence and doesn't distinguish the intended "closed while waiting/engaging" family from unrelated failures. `contains("metadata-write mutex")` (present in both engage-abort messages) would pin the contract at no fragility cost.
2. The test deterministically exercises only the **loop-top** branch (mark set before engage → throw before `tryAcquire`). The **post-acquire re-check** branch (`engagedMutexOrdinal.set(ordinal); if (teardownIntent) { releaseMetadataWriteMutexForTx(); throw ... }`) — the half of the Dekker pair that self-releases an already-acquired permit — is reachable only in a nanosecond window and has no test. This is precisely the branch whose regression (e.g., someone reordering the ordinal store after the mark read) silently re-opens the strand. A deterministic test needs a seam (e.g., a package-private hook between `mutex.engage` return and the mark re-read, or marking the session from a `SchemaShared.copyForTx` interceptor); worth noting for the coverage gate even if deferred.
3. `doubleReleaseKeepsSinglePermit` cleanup dereferences `secondSession.get()` unconditionally; if the prober died before `openDatabase()` returned, the NPE masks the real failure. Guard with `assertNotNull` or a null check.

---

### TQ5 — suggestion — pool-close skip test can hang the fork instead of failing on regression

**Location:** `MetadataWriteMutexTest.java` `poolCloseDuringCommitDefersTeardownToOwner`.

`pool.close()` is called on the test thread inside the `try` block while the owner is parked in the commit window holding `stateLock.writeLock()`. The skip branch returns promptly today. If a regression removes or breaks the skip, `realClose` falls through into `internalClose` → `closeActiveQueries()`/`rollback()` against a mid-commit session, which can block behind the held write lock — **before** the `finally` that releases `releaseWindow` runs. The test then deadlocks (owner waits for the window, test thread waits for the owner) and the failure mode is a surefire fork timeout, not a test failure. The other latches in the file are all await-with-timeout; this one structural hazard has none. Consider closing the pool from a spawned worker and asserting bounded completion (join with timeout), keeping the window release on the test thread.

---

## No-issue verifications (null verdicts)

**N1 — Dekker engage/teardown pair is formally sound.** Teardown: W_mark (volatile `teardownIntent = true`, `DatabaseSessionEmbedded.java:3350`) precedes RMW_claim (`engagedMutexOrdinal.getAndSet(0)`, `:3865`, reached from the outer finally `:3386`). Engage: W_ordinal (`engagedMutexOrdinal.set`, `:3838`) precedes R_mark (`:3839`). All four are volatile accesses in the total synchronization order. If RMW_claim returns 0 (missed the engage), then RMW_claim <so W_ordinal; with W_mark <so RMW_claim (program order), W_mark <so R_mark, so the engage sees the mark and self-releases. If RMW_claim harvests the ordinal, the teardown releases and the engage's own claim returns 0 (no double release). No interleaving leaves the permit unreleased or released twice. The in-code comment (`:3831-3837`) matches the proof.

**N2 — single-permit invariant across all three release sites.** Every releaser funnels through `releaseMetadataWriteMutexForTx()` (`:3864`): the `getAndSet(0)` admits exactly one claimant per acquisition; `releaseFor`'s `(session, ordinal)`-keyed CAS (`MetadataWriteMutex.java:191-215`) is an independent second belt (stale ordinal → warn-noop; CAS-lose → warn, no release). Traced interleavings: owner-finally vs pool release-pass (one wins claim); engage self-release vs teardown harvest (claim decides; harvested `releaseFor` CAS succeeds on the still-recorded holder); mid-engage teardown claim between `mutex.engage` return and the session-side ordinal store (claim returns 0; engage's re-check self-releases). Double-release requires two claimants for one ordinal — impossible.

**N3 — captured `engagedMutex` reference lifecycle.** The engage writes `engagedMutex` (volatile, `:3830`) strictly before `engagedMutexOrdinal.set` (`:3838`); a foreign claimant that harvests the ordinal therefore observes the reference write (its RMW read is ordered after the ordinal write, which is after the reference write in SO). "Never cleared" is safe: the only consumer is the release funnel, and the funnel only acts on a claimed non-zero ordinal, which always pairs with the reference stored by the same engage; a session cannot engage against two different mutex instances within one storage binding (SharedContext swap requires storage close, which closes the session first). The `mutex == null` defensive arm (`:3869-3878`) is correctly log-not-throw for a teardown-finally context.

**N4 — `close()`→`closeInternal()` wrap preserves prior semantics.** The diff moves the pre-change `close()` body into `closeInternal()` verbatim (only the finally comment updated); the wrapper (`FrontendTransactionImpl.java:1028-1040`) adds the completer in a `finally`, so it runs on both normal and throwing closeInternal exits, after the mutex release, and is itself throw-isolated inside `completeDeferredTeardownAfterTxClose` (catch Throwable, log). `close()` remains reachable only from the two outermost frames (`:469` rollbackInternal, `:778` doCommit) — verified no other callers of `FrontendTransactionImpl.close()` exist (the `tx.close()` in `YTDBGraphImplAbstract.java:390` is a TinkerPop transaction). No subclasses override `close()` (grep: none extend `FrontendTransactionImpl`). Recursion via internalClose→rollback→tx.close→completer is cut by the same-thread `internalCloseInProgress` guard, which is sound for that same-stack purpose (program order).

**N5 — InterruptedException handling.** The catch (`MetadataWriteMutex.java:156-165`) restores the flag **before** constructing/throwing, wraps via `BaseException.wrapException` preserving the cause, and names the holder. The loop-top abort and FM-A7 throw paths don't touch the flag (correct — no interrupt involved). Pinned by `interruptedEngageWaiterThrowsAndRestoresInterruptFlag`, which asserts type, message, and flag.

**N6 — catch-Throwable sites are intentionally broad and adequately logged.** (a) `DatabasePoolImpl.java:132-140`: pool close is one-shot; propagating would strand every remaining session's resources including a held permit — log-and-continue is the designed isolation, and the error includes the throwable. (b) `completeDeferredTeardownAfterTxClose` (`:3927-3933`): swallowing here is the *point* — a durable commit's outcome must not be masked by teardown failure (pinned by `throwingCloseListenerNeverMasksCommitOutcome`, which uses an `AssertionError` specifically because the listener loop doesn't swallow Errors). Neither site swallows silently.

**N7 — widened release pass placement.** The release sits in `internalClose`'s **outer** finally (`:3386`), covering: pre-rollback throw points (`closeActiveQueries`, `localCache.shutdown`), the storage-closed early return (`:3359-3362`, inside the try → finally still runs), rollback throws, and listener throws. The `status != OPEN` guard-return at `:3339` correctly precedes the mark write, so a no-op close plants no stale mark; `reuse()` clears the mark as second belt (`DatabaseSessionEmbeddedPooled.java:50`). Residual gap: `assert assertIfNotActive()` sits between the mark write and the try (`:3353`), so under `-ea` a mis-activated teardown call would strand the flags and skip the release pass — broken-caller-contract territory, `-da` in production, not filed.

**N8 — tx `status`/`storageTxThreadId` volatility.** Both promotions are semantics-preserving for all existing same-thread readers; the new foreign reader (`isCommittingOnForeignThread`, `FrontendTransactionImpl.java:190-194`) correctly requires `storageTxThreadId != 0` (set at begin `:242`, cleared in closeInternal `:1060`), so a never-begun or already-closed tx can't false-positive. The COMMITTING window bounds are right: set at `doCommit:748`, exited via closeInternal's `INVALID` write at `:1064` *before* the completer runs — which is what makes the owner's half of the completer handshake ("publish completion, then read mark") real.

**N9 — no leftover references to the old API.** Grep confirms no remaining `metadataMutexEngaged`, single-arg `releaseFor(`, or ordinal-less `engage(` call sites in main; the only `engage`/`releaseFor` callers are the session funnel and the test.

**N10 — jstack-debugged test patterns are sound, not fragile.** (a) The lock-free `getStatus()` probe in `poolCloseDuringCommitDefersTeardownToOwner`: the plain read is race-free in context because the `inWindow` latch gives a happens-before chain from the owner's `reuse()`/`begin()`/engage writes to the test thread's reads (countDown → await); the probe legitimately avoids the `stateLock` deadlock (see CQ4). (b) Re-activation before cleanup close in `throwingCloseListenerNeverMasksCommitOutcome`: required because the completer's `internalClose` finally removed the thread-local activation even though the listener throw aborted the status flip — the cleanup path (unregister listener, re-activate, close) then completes the teardown with the one-shot guard still open (status stayed OPEN because the throw preceded `status = CLOSED`). Both are correct-by-trace, and both carry explanatory comments.

**N11 — flake audit of the new tests.** All ten are latch- and state-driven; no `Thread.sleep` anywhere; parked-state probes (`awaitThreadParked`) and joins are bounded (5–15 s); `@After` join catches leaked workers with a named failure. The parked-state assertion tolerates both `WAITING` and `TIMED_WAITING` (correct for the new timed re-wait loop — the old comment in `awaitThreadParked`'s Javadoc still references `acquireUninterruptibly`, a cosmetic staleness folded under CQ3's theme). The only structural timing hazard found is the hang-not-fail shape filed as TQ5. Commit-window hook (`setCommitWindowTestHook`) pre-exists this commit; its use here is deterministic (latch handshake, cleared in finally).

## Hypothesis log

| # | Hypothesis | Outcome |
|---|-----------|---------|
| H1 | Dekker ordinal-store/mark-read pair has a missed-interleaving strand | Refuted by SO argument → N1 |
| H2 | Two racing releasers can double-release the permit | Refuted (funnel + keyed CAS) → N2 |
| H3 | `engagedMutex` null/stale read in the funnel strands or misroutes a release | Refuted (publish-before-ordinal; single-storage lifetime) → N3 |
| H4 | `close()`→`closeInternal()` wrap drops or reorders prior close semantics | Refuted (verbatim body; additive throw-isolated finally) → N4 |
| H5 | "Both-act is benign, contained by the one-shot status guard" | **Refuted — guard is plain check-then-act; double `storage.close`** → BG3 |
| H6 | New log call sites resolve to wrong LogManager overloads | **Confirmed for `realClose` warn (javac experiment); others clean** → BG4 |
| H7 | Q-A2 detection can miss an in-flight commit despite volatile tx fields | **Confirmed — plain `currentTx` route** → BG5 |
| H8 | InterruptedException handling loses the flag or the cause | Refuted → N5 |
| H9 | catch-Throwable sites swallow too much / silently | Refuted (intentional, logged, pinned by tests) → N6 |
| H10 | A teardown throw point exists between acquire and release with no releaser | Refuted (outer-finally placement; `-ea` assert gap noted, not filed) → N7 |
| H11 | Stale teardown mark survives into a fresh pooled borrow | Refuted (guard-before-mark + `reuse()` clear) — noted under N7 |
| H12 | FM-A7 throw can false-positive on a normally-released prior tx | Refuted (holder cleared before permit release; same-thread ordering) |
| H13 | Engage loop-top `isClosed()` introduces deadlock | Refuted (waiter holds nothing; bounded by holder progress) — degradation filed as CQ4 |
| H14 | Rollback-throw test doesn't actually exercise its described mechanism | **Confirmed** → TQ3 |
| H15 | New tests carry sleep/timing flake risk | Refuted for 9/10 (latch/state-driven); hang-not-fail shape filed → TQ5, N11 |

## Verdict

No blockers. The core novel machinery — the ordinal+CAS holder, the release funnel, the Dekker engage/teardown pair, the close-wrap, the interrupt handling — is correct as designed and well-commented; the tests are substantially behavior-pinning and non-flaky. The five should-fixes are: one real (if narrow) lifecycle race whose in-code containment claim is wrong (BG3), one garbled diagnostic on the protocol's only observability point (BG4), one one-word visibility fix with a doc correction (BG5), one stale test-class Javadoc (CQ3), and one test description that misstates its mechanism (TQ3).
