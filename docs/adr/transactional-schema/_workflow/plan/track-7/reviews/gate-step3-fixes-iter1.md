# Gate verification — Track 7 Step 3 review-fix commit, iteration 1

**Artifact under gate:** commit `f7009df7a7` ("Close teardown races in the mutex handshake"),
plus docs commit `865cb1de7f` (episode + residual record). Branch `transactional-schema`, HEAD
`865cb1de7f` at gate time. The five fix-touched files in the working tree are byte-identical to
`f7009df7a7` (the only uncommitted worktree changes are `ScalableRWLock{,Test}.java` — Step 4
territory owned by another thread; verified via `git status`/`git diff HEAD --stat`).
**Method:** verdicts derived independently from the diff and the working tree, without consulting
fixer reasoning; numbered premises with file:line traces; counterexample construction attempted
for every claimed-closed race (F2 probed hardest per charter); one scratch `javac`/`java`
overload-resolution experiment outside the worktree (F3). Read-only: no Maven executed, no
product code modified. All line numbers cite the working tree at HEAD.

Original findings: `{concurrency,baseline,crash-safety}-step3-iter1.md` in this directory.

---

## F1 (CN32 = BG5 = CS26) — volatile `currentTx` + HB documentation. VERDICT: VERIFIED

**Decision criterion:** every writer of `currentTx` must be a volatile store (no bypass/alias),
the skip-detection chain must contain no plain field read end-to-end, and the documented
happens-before argument must be JMM-sound.

Premises:
1. Declaration is `private volatile FrontendTransaction currentTx`
   (`DatabaseSessionEmbedded.java:289`) with the rationale comment at `:280-288` naming the
   foreign-read hazard, the pairing writes, and the nil cost. [verified]
2. Writer enumeration (grep, exhaustive — field is private, no setter, no subclass access;
   `DatabaseSessionEmbeddedPooled` never touches it): `init()` `:4525`
   (`currentTx = new FrontendTransactionNoTx(this)`), `setNoTxMode()` `:2493`, `begin(tx)`
   `:4528`. All three are direct assignments to the volatile field — volatile-store semantics
   apply identically at each site; no non-volatile alias exists. [verified]
3. Detection chain end-to-end: `hasInFlightForeignCommit()` `:4018-4019` reads volatile
   `currentTx`, then `isCommittingOnForeignThread()` (`FrontendTransactionImpl.java:190-194`)
   reads volatile `status`, volatile `storageTxThreadId`, and a thread-local
   (`Thread.currentThread().threadId()`). Zero plain fields remain — the design pin "skip
   DETECTION must not read plain fields" now holds literally. [verified]
4. HB soundness: volatile accesses are totally ordered in the synchronization order; the pool
   thread's read returns the value of the SO-latest preceding write, so a stale prior-borrow
   object is no longer JMM-legal. If the read returns tx object X, the subsequent volatile
   `status`/`storageTxThreadId` reads on X observe X's current state directly. The only residual
   is the read ordering SO-before the owner's begin-write — exactly the accepted TOCTOU
   (late-skip/late-rollback), which the read-site comment `:4012-4017` states. The original
   CN32 counterexample (stale pre-begin `FrontendTransactionNoTx` → false-no-skip → teardown
   under a live commit) is now unconstructible. [sound]

**Alternative hypothesis checked:** a second cached route to the tx (e.g., a field alias read by
the pool path) — refuted: `hasInFlightForeignCommit` is the only detection entry
(`realClose`:74) and it reads only `currentTx`. `getTransactionStatusForDiagnostics` (`:3965-3968`,
diagnostics-only) also reads only the volatile pair, and `FrontendTransactionNoTx.getStatus()`
exists (`FrontendTransactionNoTx.java:309`) so the "never throws" claim holds.

## F2 (CN33 = BG3 = CS27) — atomic teardown claim. VERDICT: VERIFIED (hard-probed; no regression found)

**Decision criterion:** exactly one full teardown body per open cycle in the both-act shape;
the release-on-throw retry semantics must not re-open the double-listener/double-decrement
window; no new race or deadlock on normal close paths; a recycled borrower must never inherit a
consumed claim.

Structure as landed (`DatabaseSessionEmbedded.java:3364-3435`), order verified:
1. Fast-path status guard `:3365` (plain, comment relabeled "Fast-path guard").
2. `assert assertIfNotActive()` `:3374` (see F7).
3. `teardownClaim.compareAndSet(false, true)` `:3375`; loser returns `:3382`.
4. Mark + flag writes `:3387-3388` strictly after the guards (CN24 placement preserved).
5. Body: `closeActiveQueries`/`localCache.shutdown` → storage-closed early-return arm (sets
   `status = CLOSED` then returns) → `rollback()` in `catch (Exception)` `:3399-3403` →
   `callOnCloseListeners()` `:3405` → `status = STATUS.CLOSED` `:3407` (ADJACENT — no statement
   between) → `storage.close(this)` `:3410` (non-recycle only).
6. Finally: `if (status != STATUS.CLOSED) teardownClaim.set(false)` `:3414-3421`, then the
   widened release pass, `internalCloseInProgress = false`, TL remove.

**Probe 1 — release-on-throw double-teardown window (the charter's hard probe).** The feared
shape "throws AFTER `callOnCloseListeners` ran, claim released, second teardown re-enters →
listeners twice / count double-decremented" decomposes:
- *Throw after `callOnCloseListeners` returned:* impossible before `status = CLOSED` — the two
  statements are adjacent (`:3405-3407`); once CLOSED is written, the finally KEEPS the claim and
  the fast-path status guard independently blocks all re-entry. A throw from `storage.close(this)`
  itself happens with CLOSED already written → claim kept → at most one decrement ever ran.
- *Throw FROM `callOnCloseListeners` (partial listener run):* status still OPEN → claim released
  → a later retry close re-fires all listeners. This is listener re-fire across ATTEMPTS, not a
  concurrent double-run — and it is byte-identical to the PRE-claim retry semantics (the old
  plain status guard also readmitted a retry after any pre-CLOSED throw). The fix strictly
  narrows: pre-fix the same window also admitted a CONCURRENT second full body (double
  decrement); post-fix a concurrent closer either CAS-fails while the claim is held, or claims
  only after the failed attempt's finally released it — i.e., attempts are serialized.
- *Double `sessionCount` decrement:* structurally impossible now. `storage.close(this)` executes
  strictly after `status = CLOSED` (program order, same thread); any subsequent entrant is
  stopped by the status guard AND the kept claim; any prior-throwing attempt never reached the
  decrement. Exactly ≤1 decrement per open cycle in every enumerated interleaving.

**Probe 2 — both-act shape.** Pool fall-through and owner completer can still both pass the
plain status guard; both reach the CAS; exactly one wins; the loser returns before the mark/flag
writes and before any body statement. Listeners once, decrement once — CN33's counterexample
(count 2→0 under a live session; count −1 with swallowed `StorageException`) is closed. The
loser-no-op is non-blocking (no new deadlock); the loser skipping the finally's TL-remove mirrors
the pre-existing guard-return behavior for already-closed sessions (not a regression).

**Probe 3 — throw-then-retry with a concurrent third closer.** After a failed attempt's finally
releases the claim (before its own release pass/flag-clear finish), a concurrent claimant can
start the retry body while the failed thread runs its finally tail. Traced hazards: (a) permit —
both funnel through `getAndSet(0)` (`:3908`), at most one non-zero, mutex CAS second belt intact;
(b) the failed thread's late `internalCloseInProgress = false` can clobber the retry's `true` —
the retry's inner tx-close would then reach the completer, whose `internalClose(false)` re-entry
CAS-FAILS against the retry's held claim and no-ops. The claim contains the very overlap the
plain flag cannot — strictly better than pre-fix, where the same clobber admitted true recursion.

**Probe 4 — reuse()/reopen inheritance.** `reuse()` (`DatabaseSessionEmbeddedPooled.java:47-59`)
runs `clearTeardownIntent(); resetTeardownClaim(); setStatus(STATUS.OPEN)` — the claim reset
strictly BEFORE the status flip, so no window exists where the session is OPEN with a consumed
claim (the reverse order would brick every recycled borrow's close). Every borrow path routes
through `reuseResource → reuse()` (`DatabasePoolImpl.java:101-109`); non-reused resources are
discarded. The only other `status = OPEN` writers (`init()` `:458`, `internalCreate` `:579`) run
exclusively on freshly constructed session objects (claim `false` by construction; verified
against the caller trace in the concurrency review §3, unchanged by this commit). The recycle
teardown reaches `status = CLOSED` → keeps the claim → `reuse()` is the reset, correctly.

**Test:** `concurrentTeardownsRunExactlyOneFullTeardown` (`MetadataWriteMutexTest.java:~905-965`)
holds the first teardown open INSIDE its close listener (status still OPEN — before the CLOSED
write) while the second `internalClose(false)` runs to completion on another thread, then asserts
the loser no-ops promptly, exactly one listener firing, and intact session accounting. Reverting
to the plain status guard makes the second thread pass the guard and double-fire the listener
(count 2) → the test fails. Load-bearing. Comments: `internalCloseInProgress` javadoc
(`:334-343`) and the completer javadoc (`:3992-3995`) now cite the claim. Residual nit (not
verdict-affecting): `realClose`'s header comment (`DatabaseSessionEmbeddedPooled.java:76-77`)
retains "contained by the one-shot close guard and the atomic release claim" — tolerable now only
because the internalClose one-shot guard IS the atomic claim, but the wording predates the fix.

## F3 (BG4 + CQ5) — WARN overload + diagnostics. VERDICT: VERIFIED (one unclaimed CQ5 half remains open as a suggestion)

1. **Overload, verified empirically** (scratch `javac`/`java` reproduction of the three `warn` +
   one `error` signatures from `SLF4JLogManager.java:197,214,231,247`): the fixed call shape
   `warn(this, String.format(...), (Throwable) null)` (`DatabaseSessionEmbeddedPooled.java:98-105`)
   resolves to the `(requester, message, Throwable, Object...)` overload — the `(Throwable)` cast
   makes the dbName `(Object, String, String, Object...)` overload inapplicable, and the Throwable
   overload is more specific than `(Object, String, Object...)`. Experiment output: `throwable`.
   `log()` then emits the message verbatim (empty varargs → no re-format, `SLF4JLogManager.java:84-88`),
   null cause skipped (`:95-97`), and the `[dbName]` prefix still appears via `fetchDbName`
   (requester is a session). The full deferral message with session identity (`%08X`), db name,
   and tx status (via the new lock-free `getTransactionStatusForDiagnostics`, `:3965-3968`,
   which cannot throw — `FrontendTransactionNoTx.getStatus()` exists) is now actually logged.
2. Completer warn (`DatabaseSessionEmbedded.java:3999-4007`): pre-formatted message + real
   Throwable → same overload → message + cause logged, with session identity and db name. [verified]
3. Pool-loop error (`DatabasePoolImpl.java:136-143`): `(Object, String, Throwable, Object, Object)`
   → the sole `error` overload; `%08X`/`%s` args format correctly (experiment: `error-throwable`). [verified]
4. **Residual (open, suggestion-class, NOT claimed by the fix):** CQ5's first half — the
   `releaseFor` skip/lost-race warn-noops (`MetadataWriteMutex.java:207-224`) still omit the
   releasing session's identity (they name only the presented ordinal and the current holder).
   The gate's finding index claims only the pool-skip/completer/pool-loop enrichment, which is
   delivered; recording the leftover half for the orchestrator's suggestion ledger.

## F4 (CN36) — post-acquire Dekker re-check test. VERDICT: VERIFIED

**Decision criterion:** the test must drive the post-acquire branch
(`DatabaseSessionEmbedded.java:3884-3891`) specifically, and must fail if that branch is deleted.

1. **Branch selection is structural, not timing-hopeful:** the test thread holds the permit via a
   direct `mutex.engage(session)` before the victim starts; the victim's engage passes the
   loop-top check unmarked and parks in `tryAcquire` (`awaitThreadParked` proves
   WAITING/TIMED_WAITING); ONLY THEN is the mark set; the holder releases immediately after (the
   `finally`). The woken `tryAcquire` returns true, `engage` installs the holder and returns —
   there is no loop-top re-run after a successful acquire (`MetadataWriteMutex.java:141-186`) —
   so the first mark read after acquisition is the session-side post-acquire re-check `:3884`.
2. **Message pin discriminates the arms:** the test requires
   `contains("while engaging the metadata-write mutex")` (`:3890`); the loop-top arm's message is
   "while waiting to engage the metadata-write mutex" (`MetadataWriteMutex.java:159-161`), which
   does NOT contain the pinned substring. A mis-schedule (mark landing before the victim's
   loop-top, e.g. `awaitThreadParked` catching a transient earlier park) fails the test loudly on
   the message pin — it can never silently pass through the wrong branch. (Small fail-loud flake
   surface; acceptable.)
3. **Deletion sensitivity:** with the re-check block `:3884-3891` deleted, the marked victim's
   `createClass` completes normally (nothing else on the DDL path consults the mark;
   `checkOpenness` reads status, still OPEN) → `victimThrown` stays null →
   `assertNotNull("the marked victim's engage must have failed", ...)` fails. Additionally the
   permit-free follow-up assertions pin the self-release half. Load-bearing on both halves.

## F5 (TQ3) — test split; genuine throw path. VERDICT: VERIFIED

1. **Skipped-rollback variant** (`teardownWithSkippedRollbackStillReleasesPermit`): description
   now states the actual mechanism (isActive gate bypasses rollbackInternal; `tx.close()` never
   runs; the widened pass releases) — matches the trace (`rollback()` `:4893-4903`:
   `isActive()` false for ROLLED_BACK, `FrontendTransactionImpl.java:1544-1548`). The dangling tx
   is now cleaned by an explicit `tx.close()` on the test thread; the completer re-entry it
   triggers is suppressed by its `status != OPEN` guard (session already CLOSED). [verified]
2. **Throw variant** (`teardownThrowBeforeTxCloseStillReleasesPermit`) genuinely takes the
   throwing path to the outer finally: `beforeRollbackOperations` swallows only `Exception`
   (`DatabaseSessionEmbedded.java:4881-4891`), so the listener's `AssertionError` escapes
   `rollbackInternal` (thrown from the BEGUN arm at `FrontendTransactionImpl.java:449-451`,
   strictly BEFORE `clear()` and `close()` — tx.close's release finally never runs), escapes
   `internalClose`'s Exception-typed rollback catch (`:3401`), unwinds through the outer finally
   (status ≠ CLOSED → claim released; widened pass frees the permit), and propagates raw out of
   `victim.close()` — the test pins the propagation, the injected message, and the freed permit.
3. **Retry close completes** (pins the claim's release-on-throw semantics): second close claims
   (CAS succeeds), `rollback()` finds ROLLBACKING active → rollbackInternal's ROLLBACKING no-op
   arm → counter to 0 → `tx.close()` → full cleanup; completer suppressed same-thread by
   `internalCloseInProgress`; teardown reaches CLOSED. Trace complete, no swallow point missed.

## F6 (CN35 = CQ4) — lock-free engage loop probe. VERDICT: VERIFIED

The loop-top is now `session.isTeardownIntentMarked() || session.getStatus() == STATUS.CLOSED`
(`MetadataWriteMutex.java:144-160`) with the rationale comment. `getStatus()` returns the plain
`status` field directly (`DatabaseSessionEmbedded.java:4241-4244`) — no `stateLock`. Loop-body
audit for any other state-lock acquisition: `tryAcquire` (semaphore), the WARN + `describeHolder()`
→ `holder.get()` + `getDatabaseName()` → `storage.getName()` — a plain field return
(`AbstractStorage.java:561-563`). No `isClosed()` and no `stateLock` acquisition anywhere in the
engage path. The weaker plain-status probe loses nothing: volatile `teardownIntent` remains the
authoritative Dekker signal, exactly as CN35 recommended.

## F7 (CS28) — assert placement. VERDICT: VERIFIED

`assert assertIfNotActive()` sits at `:3374`: after the status fast-path, BEFORE the claim CAS
(`:3375`) and before the mark/flag writes (`:3387-3388`), with the intent comment `:3371-3373`.
`assertIfNotActive` (`:5017-5025`) throws `SessionNotActivatedException` without mutating any
state. Under `-ea`, a mis-activated close now unwinds with: claim unconsumed, mark unplanted,
`internalCloseInProgress` untouched — the completer stays armed and a proper later close can
claim normally. The CN12 "release pass covers the whole teardown body" statement is now literally
true (no throw point between the flag writes and the try).

## F8 (CN34) — neither-completes residual recorded. VERDICT: VERIFIED

`track-7.md:414-421` (added by `865cb1de7f`, in the committed tree) records the corner: tx
`close()` throwing before its status write → pool skips forever on stuck-COMMITTING while the
completer may have read a stale mark-free state → neither side completes; permit still safe
(widened pass + claim); session object, session-count slot, and tsMin pin leak loudly
(StaleTransactionMonitor) — and EXPLICITLY marks the round-2 CN22 wording ("the only defeater is
JVM death mid-completer") as SUPERSEDED by a second accepted defeater. This matches CN34's
mechanism, scope (FM-A5-class), and loudness claims exactly. Accurate.
*Nit (episode bookkeeping, not the residual):* the episode's "MetadataWriteMutexTest 22/22"
miscounts — the file carries 23 `@Test` methods (20 pre-fix + 3 net new; no `@Ignore`). Does not
affect any verdict.

## F9 (CN37 = CQ3, TQ5) — javadoc, belt comment, bounded pool-skip test. VERDICT: VERIFIED

1. Test class javadoc (`MetadataWriteMutexTest.java:36-41`): now states the handshake IS
   exercised, itemizes the shapes, and confines the "later step" claim to the freezer gate. [fixed]
2. "Second belt" drift: `reuse()` comment (`DatabaseSessionEmbeddedPooled.java:49-56`) and the
   `clearTeardownIntent` javadoc (`DatabaseSessionEmbedded.java:3941-3946`) now state the
   reuse-clear is the ONLY belt for recycled borrows (the recycle teardown legitimately marks) —
   the exact CN37 correction; the no-mark-on-no-op-close rule is correctly re-scoped to the
   guard-return path. [fixed]
3. `poolCloseDuringCommitDefersTeardownToOwner` bounded: `pool.close()` runs on a spawned
   "pool-closer" thread; the test thread asserts `poolCloseDone.await(10, SECONDS)`. On a skip
   regression (fall-through teardown parking behind the commit window's held write lock) the
   await returns false → `assertTrue` FAILS; the `finally` still releases `releaseWindow`, so the
   owner unblocks and the pool-closer can drain; the `@After` hook joins workers with a 5s bound
   and fails naming leaks, and workers are daemons — the surefire fork cannot hang. Converted
   hang→failure as claimed. [verified]

---

## Regression sweep (RG hunt — teardownClaim on normal close paths)

| Shape | Trace | Outcome |
|---|---|---|
| Plain single-threaded close (non-pooled) | guard → assert → CAS wins → full body | unchanged semantics |
| Pooled recycle close | CAS wins → recycle body → CLOSED keeps claim → `reuse()` resets before OPEN | no inherited claim; no brick |
| Second close of a closed session | fast-path status guard returns (claim never consulted) | unchanged |
| Pool fall-through vs owner completer (both-act) | one CAS winner; loser no-ops | double-run closed (F2) |
| Borrower recycle vs pool full-close race | one winner; if recycle wins, pool arm no-ops → no decrement — same outcome the pre-fix status guard produced when recycle wrote CLOSED first | no NEW leak shape |
| Teardown throw → concurrent/retry claimant | attempts serialized by the claim; permit funnel single; recursive completer re-entry CAS-fails | contained, strictly better than pre-fix |
| Deadlock hunt | CAS non-blocking; no lock added; listener-blocked winner never blocks the loser | none |
| Reopen paths (`init`/`internalCreate`) | fresh objects only; claim false by construction | no consumed-claim inheritance |

No RG finding.

## Residuals for the orchestrator's ledger (none verdict-blocking)

1. CQ5 first half still open (suggestion): `releaseFor` skip/lost-race warns lack the requesting
   session's identity (`MetadataWriteMutex.java:207-224`).
2. Comment nit: `realClose` header (`DatabaseSessionEmbeddedPooled.java:76-77`) still says
   "one-shot close guard" — now factually the claim, but the phrasing predates it.
3. Episode nit: "22/22" test count should read 23.
4. Pre-existing fail-loud flake surface in the F4 test if `awaitThreadParked` catches a transient
   non-mutex park (fails on the message pin, never silently passes).

---

F1 | VERIFIED | `currentTx` volatile at :289; all three writers (:2493, :2525→init :4525, :4528) are direct volatile stores; detection chain end-to-end volatile; documented HB argument JMM-sound with only the accepted TOCTOU residual.
F2 | VERIFIED | CAS at :3375 admits exactly one body; decrement strictly after CLOSED makes double-decrement structurally impossible in both-act AND throw-retry shapes; release-on-throw only re-opens the pre-existing serialized retry (listener re-fire across attempts, same as pre-fix, minus the concurrent double-run); reuse() resets claim before the OPEN flip; load-bearing test confirmed; no new race or deadlock found.
F3 | VERIFIED | Empirical javac experiment: `(this, String.format(...), (Throwable) null)` selects the (requester, message, Throwable) overload → full pre-formatted message logs with session/db/tx-status payload; completer and pool-loop calls resolve and format correctly; CQ5's releaseFor-identity half remains an open suggestion (not claimed).
F4 | VERIFIED | Mark lands only while the victim is parked past the loop-top; no loop-top re-run after acquire, so the "while engaging" pin isolates the post-acquire branch; deleting the re-check leaves victimThrown null → assertNotNull fails; mis-schedules fail loudly, never pass silently.
F5 | VERIFIED | AssertionError from onBeforeTxRollback escapes the Exception-only swallows (:4886, :3401), aborts before clear()/tx.close(), unwinds through the outer finally (claim released + permit freed) and propagates raw; retry-close trace completes the broken session; skipped-rollback sibling relabeled with the dangling tx cleaned.
F6 | VERIFIED | Loop-top uses lock-free getStatus() (plain field, :4241) + volatile mark; full loop-body audit (tryAcquire, WARN, describeHolder→storage.getName()) finds zero stateLock acquisitions.
F7 | VERIFIED | Assert at :3374 precedes the CAS and all flag writes and mutates nothing; an -ea fire strands neither teardownClaim nor internalCloseInProgress nor the mark.
F8 | VERIFIED | track-7.md:414-421 (commit 865cb1de7f) records the neither-completes corner as an accepted FM-A5-class residual explicitly superseding the "only JVM death" wording; content matches CN34 exactly (nit: episode says 22/22, file has 23 tests).
F9 | VERIFIED | Class javadoc and both "second belt" comments corrected as CN37/CQ3 demanded; pool-skip test now closes the pool on a spawned thread under a 10s bounded await that fails (finally still releases the window; bounded @After joins + daemon workers) instead of hanging the fork.
RG | NONE | Full sweep of the claim's interaction with every close path (single, recycle, both-act, throw-retry, reopen, borrower-vs-pool races) found no new race, leak shape, or deadlock; every delta is equal-or-strictly-narrower than pre-fix behavior.
