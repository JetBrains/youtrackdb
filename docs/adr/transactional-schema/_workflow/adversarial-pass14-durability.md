# YTDB-382 — Adversarial pass 14: durability (2026-07-21)

Verdict: 5 new findings (1 blocker, 2 should-fix, 2 suggestions). No WAL/atomic-operation/on-disk
inconsistency was found on any changed path — every crash point traced resolves to WAL atomic-unit
completeness, and both drafts' throw paths leave freezer depth/count and the atomic-operations
table exactly as the legacy throw-mode freeze does. The blocker is an exception-path
state-consistency defect in the ruled Q-B3 probe placement: as pinned ("at `commitSchemaCarry`
entry"), the probe's throw escapes between `makeThreadLocalSchemaSnapshot` and the only paired
clear, permanently poisoning the (pooled, recycled) session with a stale pinned schema snapshot.
The two should-fixes are specification holes in ruling Q-A2's skip path: the undefined "safe
session-level close bookkeeping" set races the live commit it defers to (natural implementation
lands `setInError` after a durable schema commit), and the unresolved interaction with Q-A1's
release-pass finally lets one legal reading resurrect the FM-A2 permit strand on the exact path
Q-A2 creates.

## Surface attacked

The Track 7 agreed design in `docs/adr/transactional-schema/_workflow/track-7-design-drafts.md`
at HEAD `e2605c8ba3`, including the binding "Rulings — user design review (2026-07-20)" section,
under the crash-safety/durability charter: persistence ordering, WAL/atomic-operation state,
fsync boundaries, recovery invariants, failure/exception-path state consistency. Attack surfaces
per the mandate: (a) Draft B's gate/probe throw paths; (b) ruling Q-A2's skip-under-live-commit
and the downstream pool/storage teardown choreography; (c) Draft A's release-pass finally inside
`internalClose` (ruling Q-A1) on the throwing-rollback path; (d) the `release()` `-1` sentinel →
OPERATOR-decrement mapping and the `freeze()`-nests-`doSynch` two-counter case; (e)
`RecreateIndexesTask`'s transitive `doSynch` freeze. Passes 1–13 are settled and were not
re-attacked; claims restated in the artifact were treated as fair game.

## Code grounding

Read at HEAD `e2605c8ba3`: `OperationsFreezer.java` (whole file — `startOperation:30-57`,
`freezeOperations:72-86`, `releaseOperations:88-112`, `throwFreezeExceptionIfNeeded:114-118`),
`WaitingList.java` (whole file), `AtomicOperationsManager.java` (whole file —
`startToApplyOperations:128-152` region, `endAtomicOperation`, `freezeWriteOperations`,
`unfreezeWriteOperations`), `AbstractStorage.java` (commit path `commit:2506-2563`,
`applyCommitOperations:2741-3147` including `startTxCommit` at `:2766`, the error/success finally
`:3020-3141`, promotion `:3113-3138`, snapshot clear `:3142`; `commitSchemaCarry:3159-3201`;
`reconcileCollections`; `endTxCommit:6256`, `startStorageTx:6260-6293`, `resetTsMin`,
`startTxCommit:6340-6342`; `synch:5317-5338`, `doSynch:5347-5385`, `freeze:5495-5560`,
`release:5563-5580`; `close(session):630-640`, `shutdown:705-716`, `doShutdown:6774-6866`,
`close(db,force):1608-1623`, `setInError:2063-2087`), `DiskStorage.java`
(`copyWALToBackup:351-365`, `storeBackupDataToStream:1241-1263`), `FrontendTransactionImpl.java`
(`rollbackInternal:403-465`, `doCommit:690-766`, `close:1006-1042`, `storageTxThreadId:158`,
`assertOnOwningThread:167-171`), `DatabaseSessionEmbedded.java` (`internalClose:3264-3297`,
`ensureTxSchemaState:3477-3524`, `engageMetadataWriteMutex`, `releaseMetadataWriteMutexForTx:3617-3623`,
`executeReadRecord:2139-2145` openness check), `DatabaseSessionEmbeddedPooled.java` (whole file),
`DatabasePoolImpl.java` (whole file — `close:129-137`), `MetadataWriteMutex.java` (whole file),
`MetadataDefault.java` (`makeThreadLocalSchemaSnapshot:78-85`,
`clearThreadLocalSchemaSnapshot:88-93`, `forceClearThreadLocalSchemaSnapshot:95-103`,
`rebuildThreadLocalSchemaSnapshot`), `SchemaShared.java` (`copyForTx:269-303`, `close:1371-1373`),
`IndexManagerEmbedded.java` (`addIndexInternal:664-672`, `close:611-614`,
`recreateIndexes:1574-1599`), `RecreateIndexesTask.java` (whole file),
`YouTrackDBInternalEmbedded.java` (`checkAndCloseStorages:179-193`, `internalClose:943-980`,
`forceDatabaseClose:1042-1049`), `AtomicOperationBinaryTracking.deactivate:1434-1436`,
`SharedContext.close:143-163`.

## Method: decision criteria, premises, hypothesis log

Decision criteria for a defect: (1) a changed path (throw point, skip branch, release-pass site,
counter site) can leave durable state (WAL/atomic-op table/pages/dirty flag) inconsistent under a
crash at any intermediate point; or (2) an exception path leaves in-memory state that later
produces wrong durable bytes or a masked-success/poisoned-storage outcome; or (3) the design text
is ambiguous in a way where at least one legal implementation reading violates the design's own
invariants. Style, latency, and speculative scope growth are out of bounds.

Global premises (all [verified] by reading the cited code):

- P1. The freezer throw in `startOperation` happens after `operationsCount.decrement()`
  (`OperationsFreezer:38`) and before `operationDepth.increment()` (`:56`); the doc's ":38-40"
  claim is accurate.
- P2. `startTxCommit(atomicOperation)` sits at `AbstractStorage:2766`, before the rollback-paired
  inner `try`; a throw from it reaches neither the `rollback(error, ...)` arm (`:3021`) nor
  `endTxCommit` (`:3077`), but does reach `applyCommitOperations`' outermost finally
  (`ensureThatComponentsUnlocked` + `clearThreadLocalSchemaSnapshot`, `:3141-3142`).
- P3. An atomic operation is registered in the atomic-operations table only inside
  `startToApplyOperations` (`AtomicOperationsManager:145`, under `segmentLock`), and its WAL
  start record is emitted only from `commitChanges`; before `startOperation` returns, nothing
  durable or table-visible exists for the op.
- P4. `AbstractStorage.shutdown()` takes `stateLock.writeLock()` (`:706`) before `doShutdown`;
  a schema-carry commit holds that write lock for its whole apply window
  (`commitSchemaCarry:3179`).
- P5. `SchemaShared.copyForTx` takes the committed schema's write lock (`SchemaShared:274`), so a
  successor schema transaction cannot seed its baseline while a schema-carry commit is in flight.
- P6. `MetadataDefault.immutableCount` is a per-session field with no reset outside the
  make/clear/force-clear trio; a leaked increment pins `immutableSchema` forever (only the 0→1
  transition rebuilds it, `MetadataDefault:79-84`) and makes `forceClearThreadLocalSchemaSnapshot`
  throw (`:95-103`). Pooled sessions are recycled with their `MetadataDefault` intact
  (`DatabaseSessionEmbeddedPooled.reuse:46-50` touches only activation and status).
- P7. The single production `startOperation` caller and the four freeze-registration sites match
  ruling Q-B2's inventory exactly (re-verified by grep: `AtomicOperationsManager:128`;
  `AbstractStorage:5522/:5526`, `:5349`; `DiskStorage:357`, `:1249`); `unfreezeWriteOperations(-1)`
  has exactly one caller (`AbstractStorage:5571`).

Hypotheses raised and their outcomes:

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | Gate throw leaks freezer depth/count | Refuted (P1; see "failed attacks" §1) |
| H2 | Park-decision throw corrupts/leaks `WaitingList` state | Refuted ("failed attacks" §2) |
| H3 | Gate throw fires undo arms / reaches `endTxCommit` | Refuted (P2; "failed attacks" §3) |
| H4 | Gate throw leaves WAL/atomic-op state inconsistent | Refuted (P3; "failed attacks" §4) |
| H5 | Two-counter design misclassifies toward PARK | Refuted ("failed attacks" §5) |
| H6 | `freeze()`-nested `doSynch` TRANSIENT confuses the counters | Refuted ("failed attacks" §5) |
| H7 | `-1` sentinel maps to a wrong decrement / second negative-id caller exists | Refuted (P7); residual underflow hazard → CS14 |
| H8 | Early mutex release under a live commit lets a successor seed a stale schema baseline | Refuted (P5; "failed attacks" §6) |
| H9 | Zombie commit races storage teardown into half-durable disk state | Refuted (P4; "failed attacks" §7); in-memory choreography residue → CS13 |
| H10 | Operator freeze aborts/parks `RecreateIndexesTask` in a recovery-unhandled state | Refuted ("failed attacks" §8) |
| H11 | Probe throw escapes the snapshot make/clear pairing | **Confirmed → CS10** |
| H12 | Q-A2 "safe bookkeeping" races the live commit it defers to | **Confirmed → CS11** |
| H13 | Q-A1 release finally × Q-A2 skip: one reading strands the permit | **Confirmed → CS12** |

---

## CS10 — blocker — ruling Q-B3 probe placement ("at `commitSchemaCarry` entry") leaks the commit's thread-local schema snapshot pin; one probe rejection permanently poisons the session

**Premises.**

1. `AbstractStorage.commit` pins the thread-local schema snapshot at `:2525`
   (`session.getMetadata().makeThreadLocalSchemaSnapshot()`) before branching into
   `commitSchemaCarry` (`:2532`) [verified].
2. The only paired unpin on the commit path is `applyCommitOperations`' outermost finally at
   `:3142` (`clearThreadLocalSchemaSnapshot()`) — grep confirms exactly this one make/clear pair
   in `AbstractStorage` [verified].
3. Ruling Q-B3 pins the probe "at `commitSchemaCarry` entry, before SchemaShared.lock" — i.e.
   after premise-1's pin and before premise-2's clear is armed. The probe throws
   `ModificationOperationProhibitedException` whenever `operatorFreezeRequests > 0` — by design
   the **common, pre-engaged case** ("pre-engaged freeze → probe throws with zero locks",
   Q-B3 pin 2).
4. The unwind path from a `commitSchemaCarry`-entry throw is: `commit()`'s
   `catch (RuntimeException) → logAndPrepareForRethrow` (`:2557`, no finally, no snapshot
   cleanup — verified by reading `logAndPrepareForRethrow` and `setInError`) →
   `FrontendTransactionImpl.doCommit` catch (`:748`) → `rollbackInternal` → `tx.close()`
   (`:1006`) — none of which touch `MetadataDefault.immutableCount` [verified].
5. P6: the leaked `immutableCount` never heals for the session's lifetime, and pooled sessions
   recycle with it.

**Counterexample (state + failure point).** Operator runs `db.freeze(false)` (park-mode OPERATOR
freeze registered; `operatorFreezeRequests = 1`). A user DDL transaction on pooled session S
commits: `commit()` pins the snapshot (`immutableCount` 0→1, `immutableSchema` = the freeze-time
schema) → the Q-B3 probe at `commitSchemaCarry` entry reads the operator counter and throws →
unwind per premise 4 → transaction rolls back, mutex released correctly, freezer untouched (the
probe never reached the freezer). Session S is left with `immutableCount == 1` forever:

- Every later `makeThreadLocalSchemaSnapshot`/`clearThreadLocalSchemaSnapshot` pair on S cycles
  1→2→1; the 0→1 rebuild never fires again, so **every subsequent read, validation
  (`EntityImpl.validate` via the immutable snapshot), and serialization on S runs against the
  freeze-time schema snapshot**, permanently. A concurrent session's later schema commit
  (mandatory property added, index membership changed) is invisible to S's validation path —
  S can produce **durably wrong bytes** (records committed without constraints/routing the live
  schema requires). This is a durability-relevant silent-wrong-data outcome, not just staleness.
- Any later DDL on S reaches `forceRebuildTxSchemaSnapshot` →
  `forceClearThreadLocalSchemaSnapshot` → `IllegalStateException` ("snapshot usage count is not
  zero", `MetadataDefault:99-102`): after one probe rejection the session can never run DDL
  again, and via the pool it cycles to arbitrary future borrowers.

**Why the doc missed it.** §B.2's lock-order proof item 1 traces only the **in-window gate's**
unwind ("releases everything by unwinding through `commitSchemaCarry`'s finallys :3183-3199") —
which is correct, because `startTxCommit` at `:2766` is *inside* `applyCommitOperations`' outer
try, so the gate throw does hit the `:3142` clear. B.1's "already loud, no outage" analysis of the
legacy throw-mode freeze covers the same in-window unwind. The probe was added by ruling Q-B3
without an equivalent unwind trace; at HEAD the window between the `:2525` pin and
`applyCommitOperations` entry contains no realistic throw point (the four lock acquisitions do
not throw), so the pairing is de facto safe today — the probe converts that dead window into the
**designed primary rejection path**. The ruled test matrix (assert exception type, zero locks)
would pass while the leak ships.

**Fix bound (design amendment, small).** Either hoist the probe above the snapshot pin (probe at
`commit()` entry / before `:2525` — still "before SchemaShared.lock", still zero locks), or pin
that the probe throw must be paired with the snapshot clear (wrap the pin..commitSchemaCarry
window in try/finally). Additionally pin a regression test asserting `immutableCount` returns to
its entry value after a probe rejection (the pinned type/message test cannot catch this).

---

## CS11 — should-fix — ruling Q-A2's "safe session-level close bookkeeping" is undefined; the natural set races the live commit the skip defers to, and for a schema-carry zombie lands `setInError` after a durable commit

**Premises.**

1. Ruling Q-A2: on detecting a foreign in-flight commit, `realClose` "SKIPS the rollback/clear …
   the pool thread still sets `teardownIntent` and performs only safe session-level close
   bookkeeping." The safe set is never enumerated. `internalClose`'s non-rollback steps at HEAD
   are: `closeActiveQueries()` (`DatabaseSessionEmbedded:3271`), `localCache.shutdown()`
   (`:3272`), `callOnCloseListeners()` (`:3285`), `status = CLOSED` (`:3287`),
   `sharedContext = null` + `storage.close(this)` (`:3289-3291`), `activeSession.remove()`
   (`:3295`) [verified].
2. The owner's still-running commit **uses the session after the skip point**: the schema-carry
   promotion loads the committed root via `session.load` (`AbstractStorage:3119-3120`), which
   enters `executeReadRecord` whose first statement is `checkOpenness()`
   (`DatabaseSessionEmbedded:2142`); the failure handler for a promotion throw calls
   `setInError(e)` unconditionally (`AbstractStorage:3135`) [verified]. On the failure path,
   `rollbackInternal` → `invalidateChangesInCacheDuringRollback`/`clear()` touch the local cache
   the pool thread shut down; on the success path `doCommit` → `close()` → `clear()` does the
   same (`FrontendTransactionImpl:1008`, `:1045+`).
3. `status` is a plain field read by `checkOpenness` (doc's own [verified] note) — the owner may
   or may not observe the pool thread's `CLOSED` write; when it does, `session.load` throws
   `DatabaseException`.

**Counterexample.** Pool thread runs the Q-A2 skip while owner O is between `endTxCommit`
(`:3077`, commit **durable**) and promotion (`:3113-3138`). Pool thread's bookkeeping sets
`status = CLOSED` (the minimum any close must do to make the session unusable). O reaches the
promotion load → `checkOpenness` throws → promotion catch logs and calls `setInError(e)` →
**the storage is poisoned into error state after a fully durable commit**; every operation on
every session fails until reopen. Q-A2's stated benefit — "letting the zombie finish and release
via its own close" — is defeated for exactly the schema-carry zombies that motivated the ruling:
the zombie finishes durably but takes the storage down with it. (For a pure-data zombie the
promotion block does not run and the residue is smaller: tx-teardown `clear()` racing the
shut-down local cache, worst case an unlogged masked-success throw out of `doCommit` after the
durable commit.)

**What survives.** On-disk state is provably consistent at every crash point: the commit is
durable at `endTxCommit` (WAL atomic unit complete; recovery re-applies), the promotion-failure
handler is designed for "durable bytes, untrusted memory" (reopen re-parses the schema), and
storage teardown proper serializes behind the zombie on `stateLock.writeLock`
(`shutdown():706`). So this is availability + masked-success, not corruption — and it is still
strictly better than HEAD's FM-A4a (pool thread clearing `recordOperations` under a live apply
can abort the commit *pre*-durability with a corrupted tx object). The ruling's "strictly no
worse" holds; its "the zombie finishes cleanly" does not.

**Fix bound.** The design must enumerate the skip path's bookkeeping set and trace each element
against the zombie's residual session use (promotion loads, tx-teardown cache access,
after-commit callbacks). Concretely: either (a) defer the openness flip (and `localCache`
shutdown / query close) to the owner's own teardown — the pool thread marks `teardownIntent`,
decrements `sessionCount`, removes its thread-local, and leaves the rest to the owner; or
(b) keep the natural set and **explicitly accept and document** the
`setInError`-after-durable-commit outcome (it is the promotion handler's designed safe response),
with a test pinning it. Option (a) matches Q-A2's stated intent.

---

## CS12 — should-fix — Q-A1 × Q-A2 interaction unresolved: does the `internalClose` release-pass finally run on the skip path? One legal reading resurrects the FM-A2 permit strand on the very path Q-A2 creates

**Premises.**

1. Ruling Q-A1 places a release-pass finally "wrapped around `internalClose`'s rollback block",
   and Draft A's invariant 2 promises "at least one release per acquisition whose session
   teardown *starts*".
2. Ruling Q-A2's rationale says on skip "the permit then heals through the owner's own finally"
   (`FrontendTransactionImpl:1039`) — implying the pool thread does **not** release; but nothing
   in the ruling says whether the Q-A1 finally is suppressed on the skip branch.
3. The skip fires precisely when `status == COMMITTING` — i.e. when the session's engaged permit
   is **legitimately live**, not orphaned.
4. `internalClose` is one-shot (`status != OPEN → return`, `:3265-3267`): after the pool thread's
   bookkeeping sets `CLOSED`, no future `internalClose` will ever run for this session; the
   owner's `DatabaseSessionEmbeddedPooled.close()` no-ops on `isClosed()` (`:37-39`).

**Reading A — the finally runs on the skip path.** The pool thread's
`getAndSet(engagedOrdinal, 0)` claim succeeds (the owner has not released yet) → CAS → permit
freed **while the owner's schema commit still runs inside the four-lock window**. Not corrupting:
a successor engager's seed blocks on `SchemaShared.lock` (`copyForTx`, P5) until the zombie's
promotion completes, so no stale baseline; the owner's own later release no-ops via the consumed
claim, so no double release. But it contradicts Q-A2's stated rationale, silently degrades
single-writer to lock-serialization for the window, and consumes the claim that the owner's
finally was supposed to own — the design must at least say this is intended.

**Reading B — the finally is suppressed on the skip path (the Q-A2-rationale reading).** Then the
permit's only remaining releaser is the owner's `tx.close()` finally. Counterexample: after the
skip, the owner's commit **fails** (any post-skip failure; made materially more likely by CS11's
bookkeeping races — e.g. `invalidateChangesInCacheDuringRollback` touching the shut-down local
cache). `doCommit`'s catch calls `rollbackInternal` (`:748`); a throw out of `rollbackInternal`
before it reaches `close()` (its pre-close throw points: `:410-411`, `:432-434`, a throw from
`invalidateChangesInCacheDuringRollback()`/`clear()` — the artifact's own FM-A2 inventory)
propagates out of `doCommit`; `tx.close()` never runs; the owner's session `close()` no-ops
(premise 4); the pool's one internalClose shot is spent. **No releaser remains — permit stranded
until restart.** This is FM-A2 verbatim, re-opened by the skip on the exact scenario Q-A2
addresses, and it violates Draft A's invariant 2 (this session's teardown *started* — on the pool
thread — yet no release is guaranteed).

**Fix bound.** Pin the choice explicitly. Reading A is the safe default (invariant 2 holds
unconditionally; P5 makes the early release harmless for schema-baseline correctness) and needs
one sentence plus a test (successor engages during a live zombie commit → seed serializes behind
`SchemaShared.lock`, no stale baseline). If Reading B is chosen for its cleaner ownership story,
the design must add a releaser of last resort for the post-skip owner-rollback-throw case (e.g.
the owner-side `doCommit` catch wrapping `rollbackInternal` in the same atomic-claim release) —
otherwise invariant 2 must be weakened in the artifact, which the user should see.

---

## CS13 — suggestion — the skip's protection ends at the pool thread: the design should record that the zombie it now deliberately keeps alive still races the *post-pool* shutdown choreography (SharedContext.close, auto-close timer), all in-memory

**Trace.** After `DatabasePoolImpl.close` realCloses all sessions (`:129-137`), `sessionCount`
reaches 0 (`storage.close(this)`, `AbstractStorage:630-640`). Two follow-on actors can then run
while the zombie commit still executes:

1. The auto-close timer: `checkAndCloseStorages` (`YouTrackDBInternalEmbedded:179-193`) sees
   `getSessionsCount() == 0` and, after the delay, calls `forceDatabaseClose` (`:1042-1049`) —
   which runs `ctx.close()` **before** `storage.shutdown()`. `SharedContext.close`
   (`SharedContext:143-163`) calls `IndexManagerEmbedded.close()`, which clears the `indexes` /
   `classPropertyIndex` maps **without taking the index-manager lock** (`IndexManagerEmbedded:611-614`)
   — while the zombie holds the IM exclusive lock and may be mid-`publishReconciledIndexes`
   (`AbstractStorage:3100`) mutating those same maps. `SchemaShared.close()` is a no-op
   (`SchemaShared:1371-1373`), so the schema side is inert. A publish throw here is unguarded
   post-durability (no catch around `:3096-3101`) → masked success + spurious rollback of a
   durable commit's tx object.
2. `YouTrackDBInternalEmbedded.internalClose` (`:943-980`) does the same
   `SharedContext::close`-then-`shutdown()` ordering on instance close.

**Why only a suggestion.** Everything durable is safe: `storage.shutdown()` serializes behind the
zombie on `stateLock.writeLock` (P4); crash at any point resolves by WAL atomic-unit completeness
(pre-`endTxCommit` crash → unit incomplete → recovery discards; post → recovery applies); the
in-memory map race exists at HEAD independent of Track 7 (today's pool close corrupts the tx
object instead, which is worse). But Q-A2 changes the design's *reliance*: the ruling's promise
("letting the zombie finish and release via its own close") now depends on the zombie surviving
this choreography, and the artifact is silent about it. One recorded line in the track file
("the skip protects against the pool thread only; SharedContext.close/auto-close remain
unsynchronized with a live commit — in-memory only, durable state serialized by stateLock") plus
an optional courtesy note that `forceDatabaseClose` could take `ctx.close()` after `shutdown()`
would close the gap honestly.

---

## CS14 — suggestion — the kind-counter guarantee is one-sided (spurious gate/probe throws in the retract window are possible), and the `release()` → OPERATOR-decrement mapping should carry an underflow guard, because a double `release()` silently disarms the gate

**One-sidedness.** The pinned ordering (publish kind before count, retract count before kind)
guarantees only "an entrant that observes an operator freeze's `freezeRequests` contribution also
observes `operatorFreezeRequests > 0`" (volatile write order `op++ → fr++`; a reader that sees
the `fr` write sees the `op` write). The converse is not guaranteed: during release
(`fr-- → op--`) there is a window where `op > 0` with the operator freeze's count already
retracted. Counterexample: operator freeze releasing while one backup TRANSIENT is still active —
a schema-armed entrant enters the loop on the transient's `fr == 1`, reads the stale `op == 1`,
and throws "operator freeze in progress" though only a transient remains; the Q-B3 probe has the
same window with zero locks. Direction-safe (throw → clean rollback, retryable per the Q-B5
message) and nanosecond-bounded, so acceptable — but the artifact's invariant list should state
the guarantee as one-sided so the Q-B3/Q-B5 tests don't accidentally pin no-false-positives.

**Underflow.** `release()` is public API with no engaged-freeze precondition; at HEAD a double
`release()` drives `freezeRequests` to −1 (`releaseOperations:95`, guarded only by the `-ea`
assert at `startOperation:36/:54`), after which the *next* legitimate `freeze()` increments it
back to 0 and the quiesce is silently void — a pre-existing backup-integrity hazard. The taxonomy
doubles the surface: the same double `release(-1)` drives `operatorFreezeRequests` to −1, and the
next real operator freeze registers with `op == 0` — **the gate is silently disarmed and the
schema commit parks under the operator freeze again**, the exact outage Track 7 exists to
prevent, with no assert on the new counter at all. Since ruling Q-B2 already mandates making the
`release()` → OPERATOR-decrement mapping explicit, the implementation constraint should also pin:
floor-at-zero (or throw) on both decrements, plus a lockstep sanity assert, plus a test for the
double-`release()` shape.

---

## Failure-point enumeration along the changed paths

Gate/probe throw path (Draft B), each point checked:

| Point | State on throw/crash | Verdict |
|---|---|---|
| Probe at `commitSchemaCarry` entry throws | zero locks; **snapshot pin leaked** (CS10); mutex released via tx teardown; no WAL/table state exists | CS10 |
| `startOperation` loop-top throw (`:40`) | count re-balanced (`:38`), depth untouched (`:56`), no table entry (P3), no WAL start record; unwinds through `:3141-3142` + `commitSchemaCarry:3183-3199` finallys | clean (see below) |
| New park-decision throw (post-enqueue, pre-park) | as above + stale `WaitingList` node | clean (see below) |
| Crash while parked under transient (pre-`startToApplyOperations` registration) | op never in table, no WAL unit → recovery ignores | clean |
| Crash after `endTxCommit`, before promotion | WAL unit complete → recovery applies; schema re-parsed on open | clean |

Q-A2 skip path (Draft A), each point checked: skip decision (racy — late-skip/late-rollback both
bounded per the ruling, accepted); pool bookkeeping vs owner promotion (CS11); pool bookkeeping vs
owner failure-path rollback (CS11/CS12); pool release-pass vs live permit (CS12); post-pool
choreography (CS13); crash at any point (WAL-governed, clean); storage `shutdown()` vs zombie
(serialized on `stateLock.writeLock`, clean).

## Attacks that failed (and why no counterexample exists)

1. **Freezer depth/count leak on any gate/probe throw** — refuted. Both new throw sites sit after
   `operationsCount.decrement()` (`:38`) and before `operationDepth.increment()` (`:56`); the
   count/depth pair is balanced at every throw point, matching the legacy supplier throw
   byte-for-byte. The doc's restated ":38-40 / :2766" claims are accurate against HEAD.
2. **Stale `WaitingList` node from the park-decision throw** — no hazard. The identical residue
   already exists at HEAD when the `:46` re-check sees `freezeRequests == 0` and skips the park,
   leaving the enqueued node behind; the next `requests == 0` cut unparks it
   (`releaseOperations:105-110`), and a spurious `LockSupport.unpark` of a thread parked elsewhere
   is absorbed by the LockSupport contract (all park loops re-check). Bounded strong-ref
   retention only.
3. **Undo arms firing against unpublished structure** — impossible on the gate throw:
   `structurePublished` is set only inside the inner try (`:2795`), which is never entered; the
   `error != null` finally arm (`:3021-3057`) is unreachable because the inner try/finally is
   never entered at all. `endTxCommit` equally unreachable. [verified against `:2766-3077`]
4. **WAL/atomic-op inconsistency from a gate throw** — impossible: table registration and the WAL
   unit-start record both post-date `startOperation` (P3); the tx's atomic operation is
   deactivated and tsMin reset in the owner's `tx.close()` (`FrontendTransactionImpl:1010-1025`),
   identically to today's throw-mode freeze path. `makeStorageDirty()` before the throw only
   arms a no-op recovery.
5. **Two-counter misclassification toward PARK (the dangerous direction)** — refuted by the
   volatile ordering argument: `op++` precedes `fr++` in program order; a schema entrant inside
   the `while (fr > 0)` loop that owes its loop entry to an operator freeze's increment
   necessarily reads `op ≥ 1` at both check sites; the park-decision re-read after enqueue closes
   the cut-missed window (the doc's claim, confirmed). The nested `doSynch` TRANSIENT inside
   `freeze()` (`:5526` → `:5349`) moves only `fr` (1→2→1) while `op` stays 1 — no interleaving
   makes a schema entrant park while `op > 0`. A partial `freeze()` failure (index-freeze loop
   throw at `:5536-5545` skips the unfreeze) leaks both counters **together** (the increments are
   exception-atomic inside `freezeOperations:73-85`), so no skew — a pre-existing leak class,
   unchanged in kind by the taxonomy.
6. **Stale schema baseline behind an early-released mutex** — refuted by P5: the successor's
   `ensureTxSchemaState` seed calls `copyForTx`, which blocks on the committed schema's write
   lock held by the zombie for the entire commit (`commitSchemaCarry:3175` through promotion);
   the seed always reads the post-promotion (or post-rollback-undo) schema. The doc's restated
   FM-A4a "zombie excluded structurally by schema-lock scope" holds.
7. **Zombie vs storage teardown → half-durable disk state** — refuted: the only paths that touch
   durable structures during teardown (`doShutdown`) run under `stateLock.writeLock`
   (`shutdown():706`) or after it in the same thread; the zombie holds that lock (schema) or its
   read side (data) until its apply window closes; crash outcomes at every point reduce to WAL
   atomic-unit completeness. The `close(db, force=true)` → `doShutdown()`-without-stateLock path
   (`:1608-1615`) is not reachable from the pool/instance close choreography traced here.
8. **Gate aborting `RecreateIndexesTask` in a recovery-unhandled state** — refuted: the rebuild's
   transactions go through `addIndexInternal` (`RecreateIndexesTask:146`, `:178` →
   `IndexManagerEmbedded:664-672`), the legacy path that never seeds `txSchemaState`, so its
   commits are data entrants — the gate cannot fire on them (this is Q-B1's accepted legacy
   shape); its finally `synch()` (`RecreateIndexesTask:40-42`) rides the existing `doSynch`
   TRANSIENT site, correctly classified by ruling Q-B2's constraint (1). Under an operator freeze
   the rebuild parks exactly as at HEAD; no new abort path, nothing for recovery to handle.

## Cross-check against the artifact's [inferred] tags

The doc's [inferred] claims relevant to this lens (FM-A2 throw reachability, FM-A3 visibility
miss) were not load-bearing for any finding here; CS12's counterexample uses the FM-A2 throw
inventory only as the design itself does (the design already treats those throws as real enough
to build Q-A1 around, so Q-A2 cannot discount them on its own path).
