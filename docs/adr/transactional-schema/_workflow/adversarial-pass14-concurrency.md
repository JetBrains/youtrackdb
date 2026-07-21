# YTDB-382 — Adversarial pass 14: concurrency (2026-07-21)

Fourteenth adversarial pass, single lens (concurrency): races, lost wakeups, missed signals,
memory-visibility holes, atomicity seams, ABA, deadlock/livelock, starvation, and the correctness
of every liveness claim. Surface attacked: **the full Track 7 agreed design** —
`_workflow/track-7-design-drafts.md` at HEAD `e2605c8ba3`, INCLUDING the final "Rulings — user
design review (2026-07-20)" section (Q-A1…Q-A5, Q-B1…Q-B5 as binding composition inputs). Passes
1–13 are settled and were not re-litigated wholesale; every claim *restated* in this document was
fair game, and the doc's central claim that the park-decision re-check closes pass 13's C1
engage-during-enqueue blocker was attacked fresh against the real `WaitingList` /
`OperationsFreezer` code.

Code grounding (live tree reads, line-cited at HEAD `e2605c8ba3`): `OperationsFreezer.java` (whole
file), `WaitingList.java` (whole file), `WaitingListNode.java` (whole file),
`MetadataWriteMutex.java` (whole file), `AtomicOperationsManager.java` (`startAtomicOperation`,
`startToApplyOperations:127-149`, `calculateInsideAtomicOperation:151+`,
`executeInsideAtomicOperation:178+`, `freezeWriteOperations:268`, `endOperation` wiring `:463`),
`DatabaseSessionEmbedded.java` (`status:268` plain, `metadataMutexEngaged:295`,
`ensureTxSchemaState:3477-3524`, `engageMetadataWriteMutex:3585-3600`,
`releaseMetadataWriteMutexForTx:3617-3623`, `internalClose:3264-3296`, `isClosed:668-670`,
`freeze:3220-3226`, `close→internalClose(false):3839-3842`, `activateOnCurrentThread:4575-4577`,
`checkOpenness:4584-4588`), `DatabaseSessionEmbeddedPooled.java` (`close:36-42`, `reuse:45-49`,
`realClose:56-60`), `DatabasePoolImpl.java` (`close:128-137`), `ResourcePool.java`
(`getAllResources:191-195`, `returnResource`, `close`), `FrontendTransactionImpl.java`
(`status:84` plain, `storageTxThreadId:~158` plain, `assertOnOwningThread:167-175`,
`beginInternal:199+`, `rollbackInternal:403-463`, `doCommit:690-767`, `close():1006-1041`,
`clear():1043+`), `AbstractStorage.java` (`close(session):630-640`, `addCollection:1655-1684`,
commit branch `:2523-2543`, `applyCommitOperations:2741-2770` (`checkOpennessAndMigration:2751`,
`makeStorageDirty:2753`, `startTxCommit:2766`), `commitSchemaCarry:3159-3200`, promotion-failure
`setInError:3110-3137`, `synch:5317-5338`, `doSynch:5347-5385`, `freeze:5508-5560`,
`release:5563-5580`, `checkOpennessAndMigration:5872-5883`, config-setter freezer entrants
`:7708+`, `startTxCommit:6340-6342`), `DiskStorage.java` (`copyWALToBackup:351-366`,
`makeStorageDirty:607-610`, `storeBackupDataToStream:1241-1264`), `ScalableRWLock.java` (class
contract: non-reentrant, **writer-preference**; `exclusiveTryLockNanos:586`),
`SchemaEmbedded.java:377-392`, `SchemaClassEmbedded.java:600-615`, `DatabaseImport.java:912`,
`YouTrackDBInternalEmbedded.java:784,837,1045` (SharedContext lifecycle).

**Verdict: 1 BLOCKER, 5 SHOULD-FIX, 3 SUGGESTIONS.** Draft A's Dekker core, the getAndSet release
claim, the ordinal ABA rationale, and Draft B's park-decision re-check (the pass-13 C1 closure)
all **survived** direct attack — the formal traces are recorded in §"Verified-safe claims" so the
step decomposition can pin the orderings they depend on. The blocker (CN10) is a *composition*
hole in Draft B: the gate and probe are both unreachable when the armed schema commit queues on
`stateLock.writeLock` behind a data commit parked in the freezer while holding
`stateLock.readLock` — the full read outage I-freezer-1 targets survives the design in that
interleaving. The should-fixes are composition seams between rulings (Q-A1×Q-A2), a
too-narrow finally placement, two visibility/under-specification gaps in Q-A2, and an understated
accepted-risk record (Q-B1).

Decision criteria and premises, stated up front:

1. A finding is a **blocker** iff a traced interleaving violates an invariant the design *claims
   to establish* (I-handshake-1, I-freezer-1, the Draft A/B behavior matrices), with consequences
   in the design's own worst classes (silent single-writer break, read outage, wedge-until-restart).
2. A finding is a **should-fix** iff the design text, taken literally into implementation, permits
   an interleaving that defeats a ruling's stated purpose, or a ruling's accepted-risk record rests
   on a premise the code does not supply — but the failure is bounded, loud, or requires an
   additional (plausible) fault.
3. A finding is a **suggestion** iff the exposure requires API misuse outside the design's threat
   model, or is fully absorbed by a second independent belt already in the design.
4. Pre-existing HEAD pathologies are in scope **only** where the design claims to fix them or
   where its rulings' risk statements mischaracterize them.
5. JMM reasoning uses synchronization-order consistency for volatile/atomic accesses (a volatile
   read sees the last write to that variable preceding it in the synchronization order; SO is
   total and consistent with program order); plain fields get no such guarantee.

---

## CN10 [BLOCKER] — The freezer gate is unreachable when the armed schema commit queues on `stateLock.writeLock` behind a data commit parked in the freezer holding `stateLock.readLock`; I-freezer-1's read outage survives the design in the probe-to-entry window

**The claim under attack.** Invariant cross-reference: "I-freezer-1 … Upheld by Draft B: the
kind-aware gate at the loop-top and park-decision sites plus the operator-arm cut-and-unpark
guarantee a schema commit never parks while an operator freeze is active (**pre-engaged,
mid-entry, or layered over a transient**)". And B.1's residual-race analysis: "The commit then
acquires the write lock post-freeze and parks → outage" — i.e., the design assumes the commit in
the probe-to-entry window always *reaches* `startOperation`, where the gate throws.

**The hole.** Both the probe (Q-B3, at `commitSchemaCarry` entry) and the in-window gate (inside
`startOperation`) sit on opposite sides of the `stateLock.writeLock` acquisition
(`AbstractStorage:3180`). Nothing in the design gates the writeLock acquisition itself. The design
separately acknowledges — and scopes out as "pre-existing" — that data commits park inside
`stateLock.readLock` (data branch takes the read lock at `:2535`, then
`applyCommitOperations → startTxCommit:2766 → startOperation` parks at `OperationsFreezer:47-48`
while the read lock is held). The **composition** of these two acknowledged facts defeats the
gate.

**Counterexample interleaving** (every step is HEAD-verified code plus the design's own additions):

1. Schema commit S runs the Q-B3 entry probe at `commitSchemaCarry` entry:
   `operatorFreezeRequests == 0` → pass. (S may then wait a long time on `SchemaShared.lock`
   behind a predecessor — the probe-to-entry window is *not* microseconds.)
2. S acquires `SchemaShared.writeLock` (`:3176`) and the IM exclusive lock (`:3178`). It has not
   yet requested `stateLock.writeLock`.
3. Data commit D acquires `stateLock.readLock` (`:2535`) and proceeds toward
   `startTxCommit:2766`.
4. Operator runs `freeze(db, false)`: takes `stateLock.readLock` (`:5510` — shared with D's,
   grantable), increments `operatorFreezeRequests` then `freezeRequests` (design order), runs the
   operator-arm cut. D now reaches `startOperation`: loop-top sees `freezeRequests > 0`, D is
   *not* schema-armed → enqueues, park-decision re-check still `> 0` → **D parks holding
   `stateLock.readLock`** (`OperationsFreezer:44-48`). The freeze's drain spin sees
   `operationsCount == 0` (D decremented at `:37`), `doSynch` runs, `freeze()` returns and
   releases *its own* readLock. The snapshot window is now open — minutes. D stays parked, its
   readLock held, per the design's explicit "data commits: byte-for-byte today's semantics".
5. S now requests `stateLock.writeLock().lock()` → **blocks behind D's held readLock**
   (`ScalableRWLock` exclusiveLock waits for all readers; D exits only when the operator's
   `release()` drops `freezeRequests` to 0 and the cut unparks it). S never reaches
   `startOperation`; the in-window gate never runs; the probe already passed.
6. S is blocked holding `SchemaShared.lock` + IM lock → **every lock-based schema read blocks**.
   `ScalableRWLock` has documented **writer-preference** (class javadoc), so S's queued write
   request also **stalls every new `stateLock.readLock` acquirer** — i.e., the whole storage read
   path (`:761, :920, :1798, …`). Full read+write outage for the entire operator-freeze window —
   exactly I-freezer-1's target scenario, with the gate installed.

**Why the design's own mechanisms don't save it.** The operator-arm cut cannot help: D is a data
entrant and *must* re-park (Q-B4 pins that "data entrants all re-park (none admitted)"). The
park-decision re-check helps only threads that reach the freezer; S never does. The probe helps
only freezes registered before `commitSchemaCarry` entry; here the freeze registers after
(feasibility shown in step 4 — the doc itself identifies this probe-to-entry window as real). The
invariant's mechanism-level restatement ("never *parks*") technically holds — S is blocked on a
lock, not parked in the freezer — but the invariant's headline ("never turns a freeze into a read
outage") is violated. The invariant was operationalized too narrowly.

**Alternative-hypothesis check.** If the gate did cover this, one of these would hold: (a) the
schema commit's writeLock acquisition fails fast under an operator freeze — it does not
(`:3180` is a plain `lock()`; the design adds nothing at that seam); (b) data commits never park
holding the readLock — refuted at `:2535` + `:2766` + `OperationsFreezer:44-48`, and the doc
itself states they do; (c) a freeze cannot register while a data commit holds the readLock —
refuted: `freeze()`'s readLock (`:5510`) is shared. No such evidence exists.

**Fix direction (bounded delta, stays inside Draft B's shape).** Gate the writeLock acquisition
for the schema-carry branch: replace `stateLock.writeLock().lock()` in `commitSchemaCarry` with a
timed-acquire loop (`ScalableRWLock.exclusiveTryLockNanos:586` already exists) that re-checks
`operatorFreezeRequests` each iteration and throws the shared Q-B3/Q-B5 factory exception when an
operator freeze appears. The throw unwinds through the already-held SchemaShared/IM finallys
(`:3196-3199`), so the held-lock window collapses to one polling interval. A second probe
immediately before the lock request narrows but does not close the window (freeze can register
between probe and `lock()`); the timed loop closes it. Note the unbounded-wait semantics of the
data path and of `SchemaShared.lock` itself are untouched — only the armed writer's storage-lock
acquisition becomes freeze-aware, mirroring what the gate already does one seam lower.

Also note: the same composition fuels itself with the *legacy* unarmed freezer entrants that park
holding stateLock (config setters `:7708+` hold readLock; `addCollection:1657` holds writeLock —
see CN15), so the parked-reader population under an operator freeze is not exotic.

---

## CN11 [SHOULD-FIX] — Q-A1's release-pass finally and Q-A2's skip arm compose into a premature permit release under a live commit unless the design explicitly orders them

**The seam.** Q-A1 (ruled): FM-A2 is closed by "a release-pass `finally` wrapped around
`internalClose`'s rollback block", idempotent via the `getAndSet(engagedOrdinal, 0)` claim. Q-A2
(ruled): on detecting a live foreign-thread commit, `realClose` "SKIPS the rollback/clear,
deferring tx teardown to the owner's own path", and "the permit then heals through the owner's own
finally" (Q-A2's stated mechanism in A.4). **Nowhere does the design state that the skip must also
bypass the release-pass finally.** A finally, by definition, runs even when the guarded block is
skipped: the natural literal implementation
`try { if (!skip) rollback(); } catch (…) {…} finally { releaseMetadataWriteMutexForTx(); }`
executes the release pass on the skip path.

**Traced consequence.** Owner mid-commit (COMMITTING, permit held, `engagedOrdinal = ord`). Pool
thread skips the rollback but falls through to the finally: `getAndSet(engagedOrdinal, 0)` returns
`ord` (non-zero — the owner is engaged), `releaseFor(session, ord)` matches the live holder
`(session, ord)`, the CAS wins, `permit.release()` — **the permit is freed while the owner's
schema commit is still running inside the four-lock window**. A waiting schema transaction
engages immediately and runs concurrently with the zombie commit, serialized only by
`SchemaShared.lock` (`:3176`) — i.e., the design silently re-creates the HEAD FM-A4a "permit
OK-ish" state that Q-A2 was ruled specifically to remove, and transiently violates Draft A's
single-writer letter. When the owner's commit finishes, its own finally's `getAndSet` returns 0 →
no-op, so there is no double release (the belt works); the violation is the *premature* release,
which the idempotence argument in Q-A1 ("idempotent via the getAndSet claim") does not cover —
idempotence protects against double release, not early release.

**Fix.** One sentence in the design/step file: on the Q-A2 skip path, control must return before
entering the rollback try/finally (or the release pass must be conditioned on the skip flag), so
the *only* releaser of a live commit's permit is the owner's `FrontendTransactionImpl.close()`
finally (`:1029-1040`). Bounded consequence (zombie-window only) → should-fix, not blocker.

---

## CN12 [SHOULD-FIX] — The FM-A2 closure's finally is placed one block too low: throws from `closeActiveQueries()`/`localCache.shutdown()` and the `isClosed()` early return bypass it, and a single realClose throw aborts `DatabasePoolImpl.close`'s loop, stranding every remaining session

**The claim under attack.** Behavior matrix "A2 — **healed** by the internalClose-level release
finally"; invariant (2) "at least one release per acquisition whose session teardown *starts*".

**The gap.** `internalClose` (`DatabaseSessionEmbedded:3264-3296`) runs, *before* the rollback
block the finally is ruled to wrap (`:3279-3283`): `closeActiveQueries()` (`:3270`),
`localCache.shutdown()` (`:3271`), and the `if (isClosed()) { status = CLOSED; return; }` early
return (`isClosed()` = `status == CLOSED || storage.isClosed(this)`, `:668-670`). None of these
are covered by a finally that wraps only the rollback block:

- **Pre-rollback throw.** `closeActiveQueries()` / `localCache.shutdown()` throwing on a sick
  session is the same `[inferred]`-reachability class the design itself accepts for FM-A2's arms
  (`invalidateChangesInCacheDuringRollback()`/`clear()` throws). If either throws, the release
  finally is never entered; the permit strands — the FM-A2 wedge reproduced one line above its
  fix. The exception then propagates out of `internalClose` (the outer finally at `:3292-3295`
  only clears the ThreadLocal), out of `realClose`, and **aborts the `for` loop in
  `DatabasePoolImpl.close` (`:131-134`)** — every remaining checked-out session is never
  realClosed: their permits strand (FM-A1 resurrected via loop abort) and the Q-A2 protection is
  never applied to them.
- **Early return.** If the storage closed under an open session holding the permit (admin-path
  force close), teardown "starts" but returns at `:3273-3276` without any release pass. Whether
  the stranded permit matters depends on SharedContext lifecycle (`sharedContexts.remove` at
  `YouTrackDBInternalEmbedded:837/:1045` likely makes it moot on drop/force-close, but this is
  unverified for every storage-close path) — flag for verification rather than assumed safe.

**Fix.** Hoist the release pass to `internalClose`'s *outer* finally (next to
`activeSession.remove()`, `:3294`) — it is a no-op when nothing is engaged, so widening costs
nothing — and add a per-session try/catch around `realClose()` in `DatabasePoolImpl.close`'s loop
so one sick session cannot strand the rest. Note the CN11 ordering requirement applies wherever
the release pass lands: the Q-A2 skip must still bypass it.

---

## CN13 [SHOULD-FIX] — Q-A2's skip detection reads two plain (non-volatile) fields cross-thread; the ruling's accepted-risk statement ("late-skip = commit already finished") presumes a coherence the code does not supply, and tx-object reuse makes the failure a stranded permit

**The claim under attack.** Q-A2's pin: "Racy detection accepted: late-skip = commit already
finished; late-rollback = today's behavior, strictly no worse." The doc cites the fields as
"both readable at `FrontendTransactionImpl:158,117`" — readable, but: `status` is a **plain**
field (`protected TXSTATUS status`, `:84`) and `storageTxThreadId` is a **plain** `long`
(`:~158`). The pool thread has no happens-before edge covering the owner's writes to either (its
session reference came from `resourcesOut`, whose enqueue predates the transaction entirely).

**Why "late" is not the only failure mode on paper.** The accepted-risk statement models the race
as *timing* (a coherent-but-slightly-stale read). Plain fields formally permit *stale* reads with
no per-variable coherence guarantee. The sharpest consequence uses a HEAD fact the design
elsewhere relies on: **transaction objects are reused across transactions on a session**
(`beginInternal` resets state on the same object; the `mutationVersion` comment documents reuse).
A pool thread that reads `status == COMMITTING` **left over from a prior transaction** while the
current transaction is idle-BEGUN (and holding the mutex) takes the skip arm → no rollback, no
`tx.close()`, owner gone → **permit stranded**, which *disables* the Track 3 FM-A1 heal the doc's
§0 correction celebrates ("FM-A1 is ALREADY healed at HEAD"). "Late-rollback = today's behavior"
remains true (that direction only removes protection the design never claimed); it is the
late-*skip* direction whose risk statement is unsound without coherence.

**In practice** hardware cache coherence makes a single fresh load see a recent committed store,
so the exposure is theoretical-JMM plus compiler latitude — but this design's entire Draft A
edifice is built on explicit volatile reasoning, and the one new cross-thread decision it adds is
built on two plain fields without comment. **Fix:** make `FrontendTransactionImpl.status` and
`storageTxThreadId` volatile (both are written on tx-boundary paths only; cost is nil), and state
in the design that the accepted residual is then exactly the TOCTOU the ruling describes, no more.

---

## CN14 [SHOULD-FIX] — Q-A2's "safe session-level close bookkeeping" is undefined, and the natural residue of `internalClose` is *not* safe under a live commit: `status = CLOSED` alone can drive the owner's promotion into `setInError`

**The claim under attack.** Q-A2's pin: "on skip, the pool thread still sets `teardownIntent` and
performs only safe session-level close bookkeeping."

**The problem.** If "bookkeeping" means "internalClose minus the rollback", the skip arm still
runs, under a live commit on the same session object: `closeActiveQueries()` (`:3270`),
`localCache.shutdown()` (`:3271`) — the commit path actively uses the local cache and session
query state; `status = CLOSED` (`:3286`); `sharedContext = null` + `storage.close(this)`
(`:3287-3290`). Traced consequences:

- `status = CLOSED` flips `checkOpenness` (`:4584-4588`) under the committing owner. The
  schema-carry commit calls `session.load(...)` during promotion; a `DatabaseException` thrown
  there lands in the **promotion-failure arm** (`AbstractStorage:3110-3137`), which logs and
  `setInError(e)` — the pool-close race now moves the whole **storage into error state** until
  reopen. That is strictly worse than the corrupt-rollback race Q-A2 was ruled to remove.
- `sharedContext = null` NPEs any owner-side `getSharedContext()` use for the rest of the commit.
- `localCache.shutdown()` under the owner's live cache use is the same lock-free
  foreign-mutation class as the `clear()` the skip arm exists to avoid.

**Fix.** Pin the skip arm's action set explicitly in the step decomposition: set
`teardownIntent`, log, and **nothing else** — no status flip, no cache shutdown, no
sharedContext/storage bookkeeping; all session teardown defers to the owner (whose eventual
`close()` → `internalClose` runs the full path; if the owner instead abandons the session, the
outcome is the accepted FM-A5 loud-wedge class). The consequence of deferral (the owner's later
`pool.release` throwing "The pool is closed", `DatabasePoolImpl:141-146`) is loud and
permit-safe — the permit is released in the tx-close finally before `pool.release` runs. Also
restate Draft A invariant (2) ("at least one release per acquisition whose session teardown
starts") to carve out the skip arm, which deliberately starts a teardown that releases nothing.

---

## CN15 [SHOULD-FIX] — Q-B1's accepted-risk record understates the legacy blast radius: legacy DDL also parks in the freezer holding `stateLock.writeLock` (full read outage), not only "schema/IM locks (partial schema-read outage)"

**The claim under attack.** Q-B1 ruling: "legacy DDL can park under an operator freeze holding
schema/IM locks (**partial schema-read outage**); accepted because the legacy path is removed in
an upcoming PR."

**The trace.** The legacy (non-tx-local) class-create/alter branches call `session.addCollection`
(`SchemaEmbedded:389`, `SchemaClassEmbedded:611`; also `DatabaseImport:912`), which lands in
`AbstractStorage.addCollection` (`:1655-1684`): it takes **`stateLock.writeLock()`** (`:1657`),
then `calculateInsideAtomicOperation` (`:1667`) → `startToApplyOperations:156` →
`startOperation` — an **unarmed** depth-0 freezer entrant. Under a park-mode operator freeze it
parks at `OperationsFreezer:47-48` **holding the storage write lock**: every
`stateLock.readLock` acquirer in the storage blocks — the *full* read outage, not the partial
schema-read outage the ruling records. (The `executeInTxInternal` micro-tx family the ruling
describes is real too; this storage-level family is strictly worse and is not mentioned.)

**Why it matters despite the accepted risk.** The user accepted a risk described as smaller than
it is; decision integrity requires the record to match the blast radius. No code change is
demanded by this finding — the risk still evaporates when the legacy path is removed — but the
track file's accepted-risk text (per the Q-B1 pin, it is recorded in the PR description too) must
say "full read outage via `stateLock.writeLock` on the storage-level legacy DDL entry points
(`addCollection` and siblings)", and the revisit trigger ("if the removal slips") inherits the
corrected severity.

---

## CN16 [SUGGESTION] — The Dekker mark is asymmetric: `internalClose(true)` (the pooled recycle path) tears down without ever setting `teardownIntent`

Q-A4 (ruled) sets `teardownIntent` at the top of every `internalClose(false)`. The recycle
teardown — `DatabaseSessionEmbeddedPooled.close():36-42` → `internalClose(true)` — runs the same
rollback → `tx.close()` → release-pass sequence with **no mark**. Its only legitimate caller is
the owner thread (same thread as any in-flight engage), so within the design's threat model the
Dekker pair is never needed there. But the session object is reachable from any thread, and a
foreign-thread `close()` (API misuse, the same physical shape as the pool-close races being
hardened) would run a full teardown that a mid-flight engage's Dekker check cannot see: release
pass reads `engagedOrdinal == 0` → no-op; engager records, reads `teardownIntent` → **false** →
proceeds on a torn-down session; permit strands — FM-A3 resurrected through the unmarked path,
and worse, `internalClose(true)` returns the session to the pool for reuse. Since the flag is
already cleared on every `reuse()` (Q-A4 hygiene pin), setting it unconditionally at the top of
`internalClose` (both arms) costs nothing and makes "teardown marks before it tears" actually
unconditional, which is what the ruling's own wording claims. Misuse-only exposure → suggestion.

---

## CN17 [SUGGESTION] — The engage-path self-release is a third release site not funneled through the getAndSet claim; safe today only via the second belt

Q-A1's ruling records "two release call sites, both funneling through the same atomic claim". The
Dekker self-release in engage step 5 ("self-release (`releaseFor(session, ord)`), zero
`engagedOrdinal`, throw") is a **third** releaser, and as written it calls `releaseFor` directly
and zeroes the ordinal with an unspecified (possibly plain) store. Traced race: engager
self-releases (`releaseFor` CAS wins, permit freed) while a racing teardown's
`getAndSet(engagedOrdinal, 0)` — running before the engager's zero-store — also harvests `ord`
and calls `releaseFor(session, ord)`. Single release still holds, but only because of the second
belt: the holder was already CAS'd to null (or now carries a successor's `(session', ord')` whose
session/ordinal mismatch warn-noops). The design's *stated* discipline ("all releasers funnel
through one atomic claim") is false as written, and future maintenance that weakens `releaseFor`'s
ordinal check would silently lose the protection. Pin in the step decomposition: the self-release
also routes through the `getAndSet` claim (claim first, `releaseFor` only on a non-zero harvest),
making the documented invariant literally true and the belts genuinely independent.

---

## CN18 [SUGGESTION] — FM-A4 enumerates the live-COMMIT and double-release races but not the double-*idle*-teardown: both closers pass the non-atomic status guard and run `rollbackInternal`/`clear()`/`tx.close()` concurrently

Pool `realClose` (foreign thread) and the borrower's own `close()` can both pass `internalClose`'s
plain-read one-shot guard (`if (status != STATUS.OPEN) return`, `:3265` — check-then-act on a
plain field) and both run the full teardown of the same *idle* open transaction. The permit is
safe (the `getAndSet` claim — this is A4b, closed). Not enumerated: both threads concurrently run
`rollbackInternal`'s BEGUN arm (both read BEGUN, both `clear()` the same HashMaps →
CME/corruption noise, swallowed by `internalClose`'s catch `:3280-3283`), and both run
`tx.close()`'s body — `deactivate()` is idempotent (`AtomicOperationBinaryTracking:1434-1436`,
`active = false`), but the `storageTxThreadId` check-then-zero (`:1013-1024`) can make the owner
read the already-zeroed id and **skip `resetTsMin()`**, pinning the tsMin floor (snapshot/WAL
cleanup + StaleTransactionMonitor noise) until the owner thread's next transaction. All residuals
are noise/bounded-nuisance class on a discard path — but the design's I-C3 restatement ("all
tx-scoped state mutation stays on the teardown running the owning session's own close path")
reads as if there is one such teardown, when the design knowingly allows two concurrent ones.
Enumerate as FM-A4c with an explicit accept (or serialize teardown entry with an atomic one-shot
claim on the session, which would subsume the status guard).

---

## Verified-safe claims (justifications for the "no counterexample exists" verdicts)

These are the doc's load-bearing liveness/atomicity claims that were attacked and **held**. Each
carries the implementation orderings it depends on — the step decomposition must pin them.

**V1 — Draft B's park-decision re-check does close pass-13 C1's engage-during-enqueue race.**
Attacked fresh. The dichotomy is sound against the real code: the entrant's enqueue is a
successful CAS on `WaitingList.tail` (`WaitingList:16-27`); the operator-arm cut reads `tail`
(`:35`). Both are synchronization actions on the same `AtomicReference`, so the total
synchronization order places them. Case (i): entrant's tail-CAS precedes the cut's tail read →
the cut's traversal includes the entrant's node → it is unparked (or its park permit is pre-set),
`park` returns, the loop re-runs and the loop-top kind check throws. Case (ii): the cut's tail
read precedes the entrant's tail-CAS → by SO-transitivity (`operatorFreezeRequests`++ <
`freezeRequests`++ < cut's tail read < entrant's tail-CAS < entrant's park-decision reads), the
park-decision re-read of the kind counter **must** observe the operator increment → the entrant
throws instead of parking. There is no third case. **Pinned orderings this depends on:** (a) the
operator arm increments `operatorFreezeRequests` before `freezeRequests` and runs the cut strictly
after both; (b) the entrant's park-decision kind check reads the counters strictly after
`addThreadInWaitingList` returns; (c) both counters are atomics. Also verified benign: the
armed-throw at the park-decision point leaves a dangling node whose thread gets one spurious
unpark at the next cut — the same tolerated class as the existing single-node no-detach quirk in
`cutWaitingList:50-52`; and concurrent cutters (operator-arm cut vs release cut) are safe — the
head-CAS loser retries and at worst re-unparks the tail copy.

**V2 — Draft A's Dekker pair is formally sound.** If the teardown's `getAndSet(engagedOrdinal)`
returns 0, then in SO: markStore < getAndSet < ordinalStore < markRead → the engager sees the
mark and self-releases. If the engager's ordinal store precedes the getAndSet, the teardown
harvests the ordinal and releases. If both act (engager sees mark AND teardown harvests), both
call `releaseFor(session, ord)` with the same fresh `Holder` instance — the `compareAndSet(h,
null)` admits exactly one `permit.release()`. Holder instances are allocated per engage, so the
CAS has no ABA. The parked-waiter variant is covered by Q-A3's loop-top re-check plus the
post-acquire Dekker check (complementary, as the doc says). **Pinned ordering:** ordinal store
(step 4) strictly before the mark read (step 5).

**V3 — The getAndSet claim closes FM-A4b.** All racing teardowns funnel through one atomic RMW on
`engagedOrdinal`; exactly one harvests non-zero. Independent second belt: `releaseFor`'s
`(session, ordinal)`-keyed CAS. The ordinal's ABA rationale is real, not theoretical:
`ResourcePool.returnResource` re-queues the same session instance and `reuse()` re-opens it, so
session-identity keying alone would let a stale releaser free a successor's acquisition.

**V4 — No unarmed freezer side-door inside the armed commit path.** Between
`stateLock.writeLock` acquisition and `startTxCommit:2766`, the only calls are
`checkOpennessAndMigration` (`:5872-5883` — plain status reads, no freezer) and
`makeStorageDirty` (`DiskStorage:607-610` — dirty-flag file write, no atomic operation, no
freezer). Nested `startOperation`s inside the apply run at `operationDepth > 0` and skip the gate
by design (`OperationsFreezer:32-33`).

**V5 — The throw path leaks no freezer state and the unwind is clean.** Count re-balanced before
the throw (`:37-39`), depth untouched; `startTxCommit` sits outside the rollback-paired try
(`:2766`), so no undo arm fires against unpublished structure; the exception unwinds through
`commitSchemaCarry`'s finallys (`:3183-3199`) and `doCommit`'s catch → `rollbackInternal` →
`tx.close()` → the normal mutex release finally (`FrontendTransactionImpl:748-750, :1029-1040`).
Same path as HEAD's already-exercised throw-mode freeze.

**V6 — The deadlock claims for transient bodies hold.** `copyWALToBackup`'s frozen section
(`DiskStorage:357-365`) and `storeBackupDataToStream`'s (`:1249-1263`) are WAL-only — no
`stateLock`/schema/IM acquisition. `doSynch`-under-schema-commit is structurally impossible in
the dangerous direction: its callers hold `stateLock.readLock` (`synch():5319`,
`freeze():5510`) *before* freezing, and a schema commit holds the writeLock — reader/writer
exclusion means a registered `doSynch` freeze and a held commit writeLock cannot coexist; the
ordering is always readLock→freeze, never freeze→stateLock. The `freezeOperations` drain spin
cannot wait on a blocked-on-writeLock commit (it is not yet in `operationsCount`). The
operator-arm cut's only blocking is `linkLatch.await()` on a mid-link adder
(`WaitingListNode:22-31`) — a bounded two-instruction window with no locks held by the adder at
that point.

**V7 — Q-B2's ruling facts re-verified.** Exactly four production `freezeWriteOperations`
registration sites (`AbstractStorage:5349, :5522/:5526, DiskStorage:357, :1249`); exactly one
production `startOperation` caller (`AtomicOperationsManager:128`); `release()` uses the `-1`
sentinel (`:5571`) so the release side must map `release()` → OPERATOR-decrement explicitly
(already a recorded constraint); `freeze()` nests a `doSynch` TRANSIENT inside the OPERATOR
window (`:5549`) — the counter design tolerates it (transient release decrements to a non-zero
count → no cut, parked data entrants stay parked, correct).

**V8 — Release-side counter ordering is benign-racy only.** Retract count-before-kind means an
entrant can read a stale `operatorFreezeRequests > 0` only while its `freezeRequests` read was
itself ordered before the release's decrement — i.e., only while genuinely racing the release;
a spurious abort of a schema commit racing the freeze-release instant is within contract. The
arm-side (publish kind-before-count) was verified in V1.

## Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | Park-decision re-check fails against real WaitingList (pass-13 C1 redux) | An interleaving where an armed entrant parks unwoken under an operator freeze | **Refuted** — tail-CAS/cut-read dichotomy (V1); three orderings must be pinned |
| H2 | Armed schema commit can still cause the outage without parking in the freezer | A blocking edge before the gate | **Confirmed** → CN10 (writeLock queue behind a parked reader) |
| H3 | Unarmed freezer entrant inside the armed commit path pre-gate | `makeStorageDirty`/`checkOpennessAndMigration` entering atomic ops | **Refuted** (V4) |
| H4 | Q-A1 finally fires on Q-A2's skip path → premature release | Design text ordering the two | **Confirmed** → CN11 (no ordering text exists) |
| H5 | FM-A2 finally placement misses teardown throws outside the rollback block | Pre-rollback throw sites and early returns in `internalClose` | **Confirmed** → CN12 |
| H6 | Q-A2 detection fields lack the coherence its accepted-risk statement assumes | Field declarations; tx-object reuse | **Confirmed** → CN13 (both plain; reuse makes stale-COMMITTING a stranded permit) |
| H7 | "Safe session-level bookkeeping" has an unsafe natural reading | Owner-side session use during commit (checkOpenness, localCache, sharedContext) | **Confirmed** → CN14 (incl. promotion `setInError`) |
| H8 | Dekker mark misses a teardown path | `internalClose(true)` callers | **Confirmed** (misuse-only) → CN16 |
| H9 | Draft A Dekker/CAS core has a losing interleaving | Exhaustive two-sided trace incl. parked-waiter and both-see cases | **Refuted** (V2, V3) |
| H10 | Double-idle-teardown residuals beyond the permit | `clear()`/`deactivate()`/`resetTsMin` under two closers | **Confirmed** (bounded) → CN18 |
| H11 | Q-B1 risk record matches the actual legacy blast radius | Storage-level legacy DDL freezer entrants and their held locks | **Refuted** (record understates) → CN15 |
| H12 | Transient-freeze deadlock claims wrong | Lock acquisition inside frozen sections | **Refuted** (V6) |
