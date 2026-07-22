# Crash-Safety / Durability Review — Track 7 Step 3, Iteration 1

- **Artifact:** commit `11bf0eda26` — "Harden metadata-write mutex lifecycle handshake"
  (Draft A: metadata-write-mutex permit handshake + Q-A2 skip protocol).
- **Branch:** `transactional-schema` (HEAD `b286e46e29` adds only the plan-episode doc; all
  product code in the working tree is byte-identical to the reviewed commit — verified via
  `git show b286e46e29 --stat`).
- **Perspective:** crash safety / durability (project CS charter). Concurrency-correctness
  findings are recorded only where they intersect a durability claim, and are cross-referred.
- **In-scope files:** `MetadataWriteMutex.java`, `DatabaseSessionEmbedded.java`,
  `DatabaseSessionEmbeddedPooled.java`, `DatabasePoolImpl.java`, `FrontendTransactionImpl.java`,
  `MetadataWriteMutexTest.java`.
- **Method:** decision criteria + numbered premises; file:line traces; exhaustive
  failure/crash-point enumeration along changed paths; counterexample per defect; justification
  per no-issue; alternative-hypothesis check; hypothesis log. NULL verdict licensed for this
  step (mostly in-memory lifecycle).
- **Read-only review:** no builds or tests were run (another thread owns test execution in this
  worktree). All claims are static traces.

## 0. Decision criteria

A finding is a **blocker** if the changed code can (a) make a durably-committed transaction's
outcome differ from what WAL replay reconstructs, (b) write torn/garbage state that survives
replay, or (c) report a durable commit as failed in a way that invites a client re-apply of a
durably applied change. It is a **should-fix** if it weakens a durability-*protective* mechanism
this step introduces (i.e., leaves a formally reachable hole in the protection, even if the
resulting behavior is no worse than HEAD), or contradicts a pinned design invariant on such a
mechanism. It is a **suggestion** if it is an in-memory lifecycle/robustness wart with no
durable-state exposure (these are noted and cross-referred where another charter owns them).

## 1. Premises (numbered, with anchors)

1. **P1 — the tx-close wrap.** `FrontendTransactionImpl.close()` (`FrontendTransactionImpl.java:1028-1041`)
   now delegates to `closeInternal()` (`:1042-1080`) and, in a new outer `finally`, calls
   `session.completeDeferredTeardownAfterTxClose()`. The `closeInternal()` body is **verbatim**
   the pre-commit `close()` body (verified against `git show 11bf0eda26~1`): `clear()` →
   `atomicOperation.deactivate()` → conditional `resetTsMin()` → inner finally
   (`atomicOperation = null; storageTxThreadId = 0`) → `setNoTxMode()` → `status = INVALID` →
   outer finally `session.releaseMetadataWriteMutexForTx()`. Only the trailing comment text of
   the release finally changed.
2. **P2 — tx.close() call sites.** Exactly two: `rollbackInternal()` at
   `FrontendTransactionImpl.java:469` (base nesting level only) and `doCommit()` at `:778`
   (after `internalCommit` and `afterCommitOperations`, before `status = COMPLETED`). No new
   call sites were added.
3. **P3 — storage commit finalization is complete before tx.close().**
   `session.internalCommit(...)` → `AbstractStorage.commit(...)`; *all* durable and
   registry finalization — `endTxCommit` (WAL atomic-unit end/apply, `AbstractStorage.java:6484-6512`),
   the failure-shape routing (registry undo vs `setInError`, `:3053-3135`), snapshot cleanup, index
   publication, and schema promotion (`:3137-3200`) — runs inside `storage.commit`'s own
   try/finally and returns (or throws) before control reaches `doCommit`'s `close()`/`catch`.
4. **P4 — the completer.** `DatabaseSessionEmbedded.completeDeferredTeardownAfterTxClose()`
   (`DatabaseSessionEmbedded.java:3921-3936`) guards on
   `teardownIntent && !internalCloseInProgress && status == STATUS.OPEN`, runs
   `internalClose(false)` and catches `Throwable`, logging it.
5. **P5 — internalClose.** (`DatabaseSessionEmbedded.java:3338-3392`) one-shot guard on
   `status != OPEN` (`:3339`), then `teardownIntent = true` (`:3350`),
   `internalCloseInProgress = true` (`:3351`), `assert assertIfNotActive()` (`:3353`, **outside**
   the try), body (closeActiveQueries, localCache.shutdown, isClosed early-return `:3358`,
   rollback `:3364`, listeners, `status = CLOSED`, `sharedContext = null` + `storage.close(this)`
   `:3373-3374`), outer finally: `releaseMetadataWriteMutexForTx()` (`:3386`) →
   `internalCloseInProgress = false` (`:3387`) → `activeSession.remove()` (`:3389`).
6. **P6 — the skip arm.** `DatabaseSessionEmbeddedPooled.realClose()`
   (`DatabaseSessionEmbeddedPooled.java:63-93`): `markTeardownIntent()` (`:71`, volatile write),
   `hasInFlightForeignCommit()` (`:72`), on skip: one `LogManager.warn` + `return`. Activation
   (`:91`) and `super.close()` (`:92`) happen only on fall-through.
7. **P7 — skip detection.** `DatabaseSessionEmbedded.hasInFlightForeignCommit()`
   (`DatabaseSessionEmbedded.java:3943-3946`) reads the **plain** field `currentTx` (`:279`) and
   then `FrontendTransactionImpl.isCommittingOnForeignThread()`
   (`FrontendTransactionImpl.java:190-194`), which reads the **volatile** `status` (`:91`) and
   `storageTxThreadId` (`:167`).
8. **P8 — release funnel.** `releaseMetadataWriteMutexForTx()`
   (`DatabaseSessionEmbedded.java:3864-3886`): `engagedMutexOrdinal.getAndSet(0)` claim; on a
   non-zero claim, `mutex.releaseFor(this, ordinal)` (`MetadataWriteMutex.java:191-215`) —
   `(session, ordinal)`-keyed `compareAndSet`, holder nulled before `permit.release()` (`:205`);
   mismatches and lost CAS races warn-noop.
9. **P9 — pool loop.** `DatabasePoolImpl.close()` (`DatabasePoolImpl.java:127-146`) wraps each
   `realClose()` in `catch (Throwable)` log-and-continue, then `p.close()` (`:144`) and
   `factory.removePool(this)` (`:145`).
10. **P10 — session-count close.** `AbstractStorage.close(DatabaseSessionEmbedded)`
    (`AbstractStorage.java:630-640`) is decrement-only (`sessionCount.decrementAndGet()`, a
    `StorageException` throw when negative, `lastCloseTime` update). It performs no durable I/O
    and does not itself shut the storage down.
11. **P11 — reuse clears the mark.** Every pooled re-borrow passes
    `ResourcePool.getResource` → `listener.reuseResource` → `DatabaseSessionEmbeddedPooled.reuse()`
    (`DatabaseSessionEmbeddedPooled.java:46-54`, `DatabasePoolImpl.java:100-106`,
    `ResourcePool.java:100-106`), which calls `clearTeardownIntent()`.
12. **P12 — accepted residuals (design pins).** Draft A Amendments
    (`track-7-design-drafts.md:667-689`): skip detection "must not read plain fields"; residual
    TOCTOU accepted = late-skip (commit already finished; owner/pool fall-through handles
    teardown) and late-rollback (= today's behavior). FM-A5 (leaked session, permit held
    forever, loud wedge, no reaper) is the accepted residual (`track-7.md:435-436`,
    drafts `:130, :430, :1027`).

## 2. Charter item (1) — the `close()` → `closeInternal()` wrap

### 2.1 Ordering preservation

By P1, the wrap is a pure extraction: every statement of the prior `close()` body, in the same
order, with the same inner-finally guarantees (`atomicOperation` nulled and `storageTxThreadId`
zeroed even when `deactivate()`/`resetTsMin()` throws; the mutex release in the outer finally of
`closeInternal` unchanged in position — it still runs *before* anything the new wrap adds).
**The prior finally ordering is exactly preserved.** The single addition is that
`completeDeferredTeardownAfterTxClose()` runs after `closeInternal` completes (normally or
exceptionally).

### 2.2 Can the completer's `internalClose` overlap the just-finished commit's WAL/atomic-op finalization?

**No — same-thread program order excludes it on every path.** Enumeration:

- **Commit-success** (`doCommit`, `FrontendTransactionImpl.java:756-790`): by P3, `storage.commit`
  has fully returned — WAL atomic-unit end record written and applied, locks released, promotion
  and snapshot cleanup done — before `close()` at `:778` is invoked. Inside `close()`,
  `closeInternal` deactivates and nulls `atomicOperation` *before* the outer finally runs the
  completer (program order, owner thread). The completer's `internalClose` → session
  `rollback()` (`DatabaseSessionEmbedded.java:4819-4829`) finds `currentTx` swapped to
  `FrontendTransactionNoTx` by `setNoTxMode()` (or status `INVALID`), so `currentTx.isActive()`
  is false and the rollback is a no-op — it cannot re-enter any storage tx machinery. The
  remaining completer work (`callOnCloseListeners`, `status = CLOSED`, `sharedContext = null`,
  `storage.close(this)`) touches no WAL or atomic-op state (P10). `doCommit` then sets
  `status = COMPLETED` and returns the result map; neither `commitImpl`
  (`DatabaseSessionEmbedded.java:4711-4783`) nor `finishTx` (`:5151-5163`) touches the session
  after a successful `commit()` return, so a closed-and-deactivated session causes no
  post-success throw.
- **Commit-failure** (`doCommit` catch → `rollbackInternal()` at `:769`): by P3 the storage
  already completed its internal failure handling (rollback of the atomic operation, registry
  undo or `setInError`) before the exception reached `doCommit`. `rollbackInternal`
  (`:425-486`): status → `ROLLBACKING`, `beforeRollbackOperations` (session still TL-active),
  cache invalidation, `clear()`, then `close()` at `:469`. `closeInternal` deactivates the (already
  storage-ended) `atomicOperation`; the completer then runs with nothing in flight, `rollback()`
  no-ops as above. Two enumerated behavior deltas, both non-durable: (i) after the completer's
  `activeSession.remove()`, `rollbackInternal`'s tail check `session.isActiveOnCurrentThread()`
  (`:481`) is false, so `afterRollbackOperations` listeners are **skipped** on this exotic
  (pool-skip + commit-failed) path — an in-memory callback omission on a session that is being
  terminally closed; pre-change the pool would have concurrently torn the session down, so no
  defined listener ordering is regressed; (ii) `status = ROLLED_BACK` and the two asserts at
  `:472-477` still hold (`atomicOperation` nulled by `closeInternal`, `recordOperations` cleared
  by `clear()`). `doCommit` then rethrows the **original** exception; `commitImpl`'s catch
  (`:4753-4781`) finds `currentTx.isActive()` false and rethrows unchanged. **Caller-visible
  failure preserved.**
- **Rollback** (user `rollback()` → `rollbackInternal` → `close()` at `:469`): identical shape to
  the failure path minus the storage error shapes. The completer runs on the rollback outcome as
  designed ("both commit and rollback outcomes"). Nested frames (`txStartCounter > 0`) skip
  `close()` entirely, so the completer cannot fire mid-nesting.
- **Session-close-driven rollback** (`internalClose` → `rollback()` → `rollbackInternal` →
  `tx.close()`): the completer is suppressed by `internalCloseInProgress` (P4/P5) — same-thread
  plain-field read is sound for same-stack reentrance. No recursion. (The cross-thread
  weakness of this guard is CS27, durability-null.)

**Verdict for (1): NULL** — wrap preserves ordering; the completer cannot run concurrently with
the same transaction's WAL/atomic-op finalization; commit-success, commit-failure, and rollback
paths all enumerated with only the two benign in-memory deltas noted above.

## 3. Charter item (2) — skip protocol: crash points while the zombie completes

The pool thread's total footprint on the skip arm (P6) is: one volatile heap write
(`teardownIntent = true`), two volatile heap reads (`status`, `storageTxThreadId`) plus one plain
heap read (`currentTx`), one diagnostic log line, `return`. After the loop: `p.close()` and
`factory.removePool(this)` — both pure heap mutations of pool bookkeeping. **No ThreadLocal is
touched on the skip arm** (activation happens only on fall-through, `:91`); the owner's
`activeSession` TL is removed only by the owner's own completer, on the owner thread. None of
these writes reach the WAL, the page store, or the dirty flag — the only inputs to replay/reopen
reconstruction.

Crash-point enumeration (JVM death), pool marked at T0, owner commit in flight:

| # | Crash point | Durable state at crash | Replay/reopen outcome | Pool-thread state influence |
|---|---|---|---|---|
| C1 | Before the WAL atomic-unit end record is flushed | Incomplete atomic unit | Unit rolled back; schema change absent | none (mark/log are non-durable) |
| C2 | After end-record flush, before/during page apply | Complete unit | Unit replayed; schema change present | none |
| C3 | After `storage.commit` returns, during owner's `closeInternal` (deactivate/resetTsMin) | Commit durable | Replayed as committed | none |
| C4 | During the owner's completer (`callOnCloseListeners` / `storage.close(this)` decrement) | Commit durable | Replayed as committed | none |
| C5 | After `p.close()`/`removePool`, zombie still open, owner mid-apply | Same as C1/C2 by WAL progress | Same as C1/C2 | none |

In every row the reconstruction depends only on WAL/page/dirty-flag state written by the owner
(and identical to a crash with no pool close at all). **Verdict for (2): NULL** — nothing the
pool thread touched is durable; verified line-by-line against the whitelist comment
(`DatabaseSessionEmbeddedPooled.java:73-88`), which matches the code: no rollback/clear, no
mutex release, no status flip, no session-count decrement, no one-shot-guard consumption.

## 4. Charter item (3) — the widened `internalClose` outer-finally release pass

**Does it run when rollback throws mid-teardown?** Yes: a throw from `rollback()` is caught and
logged at `:3364-3367`; throws from `closeActiveQueries()`/`localCache.shutdown()` (`:3355-3356`),
from `callOnCloseListeners()` (which does **not** swallow `AssertionError` — pinned by
`poolCloseLoopSurvivesThrowingSessionTeardown`), and from `storage.close(this)` all unwind through
the outer finally at `:3386`. The `isClosed()` early return (`:3358-3361`) also routes through it.

**No-throw audit of the release pass** (contract: "never throws", it runs inside teardown
finallys): `engagedMutexOrdinal.getAndSet(0)` — cannot throw. `engagedMutex` volatile read —
cannot throw; the null branch logs and returns (defensively, "unreachable by construction" since
the engage publishes the reference at `:3830` before the ordinal at `:3838`).
`MetadataWriteMutex.releaseFor` (`:191-215`): `holder.get()`, string building in
`describeHolder()` (`:226-235`) — `current.session().getDatabaseName()` is `storage.getName()`
(`DatabaseSessionEmbedded.java:4172-4174`); the `storage` field is never nulled by teardown (only
`sharedContext` is), so this is safe on a closed/foreign session; `current.thread().getName()` —
holder thread ref is never null; `compareAndSet` and `Semaphore.release()` — cannot throw;
`LogManager` warn/error — used ubiquitously in finallys across this codebase. **No throwing
statement found**, so it cannot mask the in-flight teardown exception, and the two statements
after it in the finally (`internalCloseInProgress = false`, `activeSession.remove()`) cannot
throw either — the teardown exception always propagates unchanged.

**Durable state:** the pass touches an `AtomicLong`, an `AtomicReference`, a `Semaphore`, and the
diagnostic log. Nothing durable.

**One caveat (CS28, suggestion):** `assert assertIfNotActive()` at `:3353` sits *between* the
mark/flag writes (`:3350-3351`) and the `try` — the only throw point in `internalClose` not
covered by the outer finally. An assert-fire (misuse + `-ea`) strands `internalCloseInProgress =
true` (permanently disabling the completer for that session) and plants the teardown mark without
running the release pass. The permit itself still has the tx-close-finally releaser, and nothing
durable is involved; but the design's "the release pass covers the whole teardown body" (CN12,
drafts `:700-706`) is one statement short of literally true. See finding CS28.

**Verdict for (3): NULL** (with CS28 as a robustness suggestion) — the pass runs on every
teardown throw point inside the try, touches no durable state, and cannot mask the teardown
exception.

## 5. Charter item (4) — completer throw-isolation and the two endTxCommit hook seams

### 5.1 Hook seams (Step-1 semantics)

- **`endTxCommitFailureTestHook`** (pre-durability shape, `AbstractStorage.java:6485-6497`): the
  injected failure ends the atomic operation *with* the error (rollback, nothing durable) and
  rethrows; the commit-finally catch (`:3108-3133`) sees `rollbackInProgress == true`, runs the
  registry undo, and rethrows. All of this completes **inside** `storage.commit` (P3) — i.e.,
  strictly before the new wrap's code can run. `doCommit` catch → `rollbackInternal` → `close()`
  → (marked case) completer; a completer teardown throw is swallowed (P4), so the **original
  injected exception** is what `doCommit:775` rethrows. Nothing durable existed to protect.
  **Seam semantics preserved.**
- **`endTxCommitPostDurabilityFailureTestHook`** (post-durability shape, `:6499-6511`): the
  operation is fully ended (durable), the hook throw routes the commit-finally catch to the
  `setInError` arm — registry publication left standing, storage in error state — and rethrows.
  Again all inside `storage.commit`. The wrap's only involvement is afterwards: the completer's
  `internalClose` touches no registry, no error-state flag, no WAL (its `rollback()` no-ops on
  the INVALID tx; `storage.close(this)` is decrement-only per P10), so it can neither undo the
  standing publication nor clear the error state nor add durable writes. The caller still
  receives the original exception (the durable outcome stands and is reconstructed at reopen —
  the Step-1 accepted in-doubt contract). **Seam semantics preserved.**

### 5.2 Durable-commit outcome protection

A teardown throw after a durable commit is confined to
`completeDeferredTeardownAfterTxClose`'s `catch (Throwable)` (P4) — including `AssertionError`
from close listeners (the listener loop does not swallow it), which is exactly what
`throwingCloseListenerNeverMasksCommitOutcome` pins: `commit()` returns normally, the class is
durably present. Post-completer, `doCommit` runs only `status = COMPLETED` + asserts on
already-established invariants + `return result`; `commitImpl` and `finishTx` add no
session-touching code after a successful return (§2.2). **No path converts a completer throw
into a caller-visible commit failure.** Catching `Throwable` does swallow VM errors
(OOM/StackOverflow) on the teardown path — the correct trade for this seam (a durable commit
must not be reported failed), and it is logged.

**Verdict for (4): NULL** — both hook seams' semantics are preserved; the wrap adds code only
after the hook-driven control flow has fully resolved inside `storage.commit`, and the completer
is effectively throw-isolated on both outcomes.

## 6. Charter item (5) — pool-close loop isolation under mid-loop JVM death

The loop body's per-session work (`realClose`) is unchanged from HEAD except for the throw
isolation and the skip arm; its durable footprint (an open transaction's storage-side rollback
during `internalClose → rollback()`, which may append WAL rollback records) is pre-existing and
per-session-independent. JVM death mid-loop leaves: sessions already closed → their atomic
operations ended (rolled back) in WAL; sessions not reached (or skipped) → open-ended atomic
operations → rolled back by replay at reopen. Both are the correct semantics for uncommitted
transactions, and identical to HEAD's crash-at-arbitrary-point behavior. The *new* behavior —
continuing past a throwing `realClose` to close more sessions, then `p.close()` +
`factory.removePool()` — adds only in-memory work and, transitively, more *orderly* session
closes (decrements; any storage shutdown triggered elsewhere by a zero count is itself an
orderly flush/checkpoint). A session whose `realClose` threw *before* its rollback ran leaves a
live atomic operation exactly as HEAD did when the whole loop aborted. **Verdict for (5): NULL —
no new durable hazard vs HEAD.**

## 7. Charter item (6) — preserved-seams check (Step-1 fixes, Step-2 reload guard)

- **Diff surface:** the commit touches exactly the six in-scope files (diffstat). `AbstractStorage`
  (endTxCommit gating/CS2 routing, `undoReconciledCollections` slot-reuse guard, commit-window and
  both endTxCommit hooks), `LinkCollectionsBTreeManagerShared` (CS20 link-bag restore),
  `SchemaShared` (copyForTx fresh-read seed, reload), and `IndexManagerEmbedded`
  (`recordMembershipChangeIntoTxLocalView` reload-guard branch) are all **untouched**.
- **`computeWithFreshCommittedReads`** appears nowhere in the diff; the promotion fresh-read scope
  at `AbstractStorage.java:3183` is unchanged.
- **`ensureTxSchemaState` hunk** (`DatabaseSessionEmbedded.java:~3605`) is comment-only; the
  Step-1 engage-undo call `releaseMetadataWriteMutexForTx()` at `:3612` is retained.
- **Indirect perturbation:** making `FrontendTransactionImpl.status`/`storageTxThreadId` volatile
  strictly strengthens visibility; all pre-existing accesses were same-thread (reads of own
  writes), so no Step-1 logic changes meaning. The `engage()` signature change (returns the
  ordinal) has exactly one production caller (`engageMetadataWriteMutex`). The foreign-teardown
  `resetTsMin` skip (`closeInternal`'s `storageTxThreadId == currentThread` check) now reads a
  volatile — behavior identical, visibility better.

**Verdict for (6): NULL — the Step-1 fixes and the Step-2 reload guard are not perturbed.**

## 8. Findings

### CS26 — should-fix — `DatabaseSessionEmbedded.hasInFlightForeignCommit()` (`DatabaseSessionEmbedded.java:279, 3943-3946`)

**Skip detection reads the plain `currentTx` field, violating the pinned "skip DETECTION must not
read plain fields" invariant and leaving a formal hole in the live-commit protection.**

The Draft A amendment (drafts `:667-678`) resolves CN13 by making `status` and
`storageTxThreadId` volatile so that "the residual after this change is exactly the benign
TOCTOU". But the detection chain starts one dereference earlier: `final var tx = currentTx;` —
`currentTx` (`:279`) is a **plain** field, reassigned on every transaction boundary
(`begin` installs a fresh `FrontendTransactionImpl` at `:4454`; `setNoTxMode` swaps in a
`FrontendTransactionNoTx` at `:2465-2469`), and the pool thread reads it with **no
happens-before edge** to the owner's begin-time write (the pool handoff at acquire precedes the
write; nothing synchronizes afterwards). The volatile fields inside the tx object cannot repair a
stale read of the *reference to* the tx object.

**Counterexample (durability-relevant direction — missed skip):** pooled session S. Owner
thread: `acquire()` → `begin()` (plain write `currentTx = freshImpl`) → schema write → `commit()`
parks mid-WAL-apply (`status = COMMITTING`, volatile). Pool thread: `close()` → `realClose()` →
`markTeardownIntent()` → `hasInFlightForeignCommit()` reads `currentTx`; the JMM permits the racy
read to return the pre-begin `FrontendTransactionNoTx` (or a prior tx object with a terminal
status) → `instanceof FrontendTransactionImpl` fails (or `isCommittingOnForeignThread()` is
false) → **fall-through**: `super.close()` → `internalClose` → `rollback()`/`clear()` of the live
commit's transaction object mid-WAL-apply — exactly the "corrupting or falsely failing a durable
commit" hazard the skip exists to close (the skip arm's own comment,
`DatabaseSessionEmbeddedPooled.java:73-77`). **Reverse direction (false skip):** a stale
reference to an abandoned prior Impl frozen at `COMMITTING` (Error-escape path) makes the pool
skip an idle session with no commit in flight → no completer ever fires → zombie session +
possibly stranded permit — which degrades into the *accepted* FM-A5 loud-wedge class (P12), so
the missed-skip direction is the one that matters.

**Mitigation of severity:** vs HEAD this is strictly no worse (HEAD tore down unconditionally),
and practical exploitability is very low (single first read of a coherently-cached reference; on
mainstream JVMs/hardware the read sees the latest value). It is therefore not a blocker. But it
is a one-keyword conformance gap in the *headline protective mechanism* of this step, and the
design pin is explicit. **Fix:** make `currentTx` `volatile` (its write frequency is
tx-boundary-only; cost is nil — the same argument the amendment already made for the other two
fields), or route the probe through a volatile/acquire read.

### CS27 — suggestion (cross-charter → concurrency, CN22) — `completeDeferredTeardownAfterTxClose` vs `realClose` fall-through both-act (`DatabaseSessionEmbedded.java:3921-3936, 3338-3339`; `DatabaseSessionEmbeddedPooled.java:63-93`)

**The "both acting is the benign overlap case, contained by the one-shot close guard" claim is
overstated: the one-shot guard is a plain check-then-act and both sides can enter
`internalClose` — with no durable exposure, but with a poisoned session count.**

Interleaving: owner finishes commit → `closeInternal` sets tx status `INVALID` → completer sees
`teardownIntent` (pool marked) and session `status == OPEN` → enters `internalClose`. Pool
thread's re-validation concurrently sees `INVALID ≠ COMMITTING` → falls through →
`super.close()` → `internalClose`. Session `status` (`:270`) is a plain field checked without a
lock or CAS (`:3339`), so **both** threads can pass the guard and run the body: close listeners
invoked twice, `storage.close(this)` decrements `sessionCount` twice (P10). The double decrement
is absorbed silently until some later, innocent session's close drives the count negative and
throws `StorageException("Amount of closed sessions ... is bigger than ...")`
(`AbstractStorage.java:633-638`) out of *that* session's teardown.

**Durability assessment (why this is not mine to block on):** every doubled statement is
in-memory (listeners, TL, status, decrement); the mutex permit is protected by the atomic claim
funnel (P8 — that half of the containment claim is sound, and pinned by
`doubleReleaseKeepsSinglePermit`); no WAL/page/dirty-flag writes occur on any doubled statement,
and an eventual zero-count-triggered storage close is an orderly flush elsewhere. Recommend the
concurrency reviewer (CN22 owns the completer Dekker handshake) decide whether the one-shot
guard needs to be a real CAS; the code comments claiming benignity should at minimum stop citing
the "one-shot status guard" as a containment belt for the cross-thread case.

### CS28 — suggestion — `internalClose` mark/flag writes precede the un-covered assert (`DatabaseSessionEmbedded.java:3350-3353`)

`teardownIntent = true` and `internalCloseInProgress = true` are written before
`assert assertIfNotActive()`, which sits **outside** the try/finally. Under `-ea`, a
mis-activated close call fires the assert after planting both flags: `internalCloseInProgress`
stays `true` forever (the completer is permanently disabled for this session — its guard treats
every future tx-close as reentrant), the teardown mark is planted without the release pass
running, and `activeSession` is not reset. Nothing durable, `-ea`-only, and the permit still has
the tx-close-finally releaser; but it silently defeats the completer and contradicts CN12's "the
release pass covers the whole teardown body". **Fix:** move the assert above the mark writes (it
does not depend on them) or move it inside the try.

## 9. Alternative-hypothesis check

For each null verdict, the strongest alternative was hunted explicitly:

- *"The completer could interleave with background WAL finalization (flush/checkpoint threads)
  rather than the owner's own frames"* — rejected: the completer performs no operation that
  reads or gates WAL state; `storage.close(this)` is decrement-only (P10); background flushers
  do not consult session lifecycle state.
- *"The widened release pass could free a live foreign commit's permit"* — rejected: the skip
  arm returns before `internalClose` is ever entered (P6), and any other releaser must win the
  `getAndSet` claim against the owner's engage-published ordinal, then the `(session, ordinal)`
  CAS (P8). The owner's live permit ordinal can only be claimed by a teardown of the owner's own
  session — which the skip arm was built to prevent, and whose residual reachability is exactly
  finding CS26.
- *"A recycled session's stale ordinal could release a successor's permit"* — rejected: ordinals
  are unique per mutex (`ordinalGenerator`), the funnel zeroes the session record atomically, and
  the mutex CAS is keyed on both session and ordinal; a stale presentation warn-noops
  (`MetadataWriteMutexTest.doubleReleaseKeepsSinglePermit` pins the single-permit property).
- *"The recycle-arm teardown mark could strand a healthy borrower's first engage"* — rejected:
  every re-borrow passes `reuse()` → `clearTeardownIntent()` (P11); a discarded (non-reused)
  resource never serves transactions again.
- *"The owner may never reach tx.close (Error escapes `doCommit`'s `catch (Exception)`), leaving
  a marked zombie the one-shot pool close will never revisit"* — confirmed reachable but
  **accepted by design**: this is the FM-A5 leaked-session loud-wedge class (P12) — permit
  release still heals if the user's finally closes the session (the widened pass), the engage
  loop WARNs every ~10 s naming the holder, and no durable state is involved. Not a finding.
- *"The test file could mask a durability regression"* — the skip-protocol test asserts the
  strong durable postconditions (commit undisturbed, class durably visible, storage usable,
  single session-count decrement); the masking test pins the durable-outcome protection. No test
  weakens a durable assertion present before the commit.

## 10. Hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | Completer overlaps the same tx's WAL/atomic-op finalization | Refuted (§2.2, P3, program order) |
| H2 | Outer-finally release pass can release a live foreign commit's permit | Refuted (§9; P6, P8) |
| H3 | Owner finally + foreign teardown double-release the permit | Refuted (P8; test-pinned) |
| H4 | Pool-thread skip-arm state influences WAL replay / reopen | Refuted (§3, crash table C1-C5) |
| H5 | Teardown throw after durable commit alters outcome / caller result | Refuted (§5.2; test-pinned) |
| H6 | Skip detection can miss a live commit | **Confirmed** — plain `currentTx` read → CS26 |
| H7 | "Both-act contained by one-shot guard" claim sound cross-thread | **Partially refuted** — durability-null → CS27 (cross-charter) |
| H8 | No-op close plants a stale teardown mark | Refuted (guard-before-mark, `:3339-3350`; reuse second belt P11) |
| H9 | Recycle-arm mark survives into a fresh borrow | Refuted (P11) |
| H10 | Release pass can throw and mask the teardown exception | Refuted (§4 no-throw audit); residual assert gap → CS28 |
| H11 | Error-escape zombie strands permit with no diagnostic | Reachable but accepted (FM-A5, P12); heals on session close via widened pass |
| H12 | Wrap perturbs Step-1/Step-2 seams | Refuted (§7) |
| H13 | Mid-loop JVM death in pool close creates new durable hazard | Refuted (§6) |
| H14 | Commit-failure path loses the original exception via the completer | Refuted (§2.2; completer swallows its own throws only) |

## 11. Verdict

**No blocker.** The step's durable-outcome protections are correctly placed and correctly
ordered on all three tx-close paths; the skip arm is genuinely whitelist-only and leaves nothing
durable behind for replay to trip on; both Step-1 hook seams' semantics are preserved; the
Step-1/Step-2 seams are untouched. One should-fix: the skip-detection chain reads a plain field
(`currentTx`), a literal violation of the design's own "no plain fields in skip detection" pin
that leaves a formal (practically tiny, HEAD-no-worse) hole in the live-commit protection —
a one-keyword fix. Two suggestions: the cross-thread both-act containment claim (concurrency's
call), and the assert-before-try flag strand.
