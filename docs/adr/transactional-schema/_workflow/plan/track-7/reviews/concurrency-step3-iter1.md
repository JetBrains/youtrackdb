# Concurrency review — Track 7 Step 3, iteration 1

**Artifact:** commit `11bf0eda26` ("Harden metadata-write mutex lifecycle handshake"), branch
`transactional-schema` (HEAD at review time `b286e46e29`).
**Perspective:** concurrency, design-conformance-critical.
**Spec of record:** `docs/adr/transactional-schema/_workflow/track-7-design-drafts.md` — Draft A
§A.2, Rulings Q-A1..A5, pass-14 Amendments (skip protocol, CN12 widened finally, CN16/CN17/CN18
notes), round-2 Amendments (CN20+CS17 whitelist, CN22 completer Dekker, CS16 completer placement,
CN24 mark placement), and the V2 mandatory ordering. Deviations register: `plan/track-7.md`
§Episodes Step 3 ("What changed from the plan", deviations 1–6).
**Method:** decision criteria per charter item, numbered premises with file:line traces,
exhaustive interleaving enumeration per claimed-safe window, concrete counterexample per defect,
hypothesis log with dispositions. All line numbers cite the working tree at `b286e46e29` (identical
to `11bf0eda26` for the six in-scope files).

Charter scope: (1) V2 ordering; (2) single-claim release funnel + double-release enumeration;
(3) `engagedMutex` captured-reference deviation; (4) skip protocol / CN22 interleavings;
(5) completer placement, throw isolation, both-act; (6) Q-A3 loop + FM-A7; (7) volatile
`status`/`storageTxThreadId` sufficiency (CN13); (8) test quality for the concurrency shapes.

---

## 0. Code map (in-scope seams, verified by reading)

| Seam | Location |
|---|---|
| Holder record `(session, ordinal, thread, acquiredAtNanos)`, `AtomicReference` | `MetadataWriteMutex.java:82-89, 104` |
| Ordinal generator (starts at 1; 0 = "not engaged" sentinel) | `MetadataWriteMutex.java:96-98` |
| Engage: FM-A7 reject / same-thread reject / timed re-wait loop / holder write | `MetadataWriteMutex.java:121-181` |
| `releaseFor(session, ordinal)` keyed CAS, warn-noop, winner-nulls-before-release | `MetadataWriteMutex.java:194-217` |
| Session claim record `engagedMutexOrdinal` (`AtomicLong`) | `DatabaseSessionEmbedded.java:295` |
| Captured mutex reference `engagedMutex` (volatile, never cleared) | `DatabaseSessionEmbedded.java:305` |
| Dekker mark `teardownIntent` (volatile) | `DatabaseSessionEmbedded.java:318` |
| Reentrance guard `internalCloseInProgress` (plain, deliberate) | `DatabaseSessionEmbedded.java:330` |
| `internalClose`: one-shot guard → mark → body → OUTER finally release pass | `DatabaseSessionEmbedded.java:3338-3392` |
| Engage wiring: `engagedMutex` store → ordinal store → mark re-read (V2) | `DatabaseSessionEmbedded.java:3829-3848` |
| Release funnel `releaseMetadataWriteMutexForTx` (`getAndSet(0)` claim) | `DatabaseSessionEmbedded.java:3864-3881` |
| Owner completer `completeDeferredTeardownAfterTxClose` | `DatabaseSessionEmbedded.java:3921-3935` |
| Skip detection `hasInFlightForeignCommit` → `isCommittingOnForeignThread` | `DatabaseSessionEmbedded.java:3943-3946`; `FrontendTransactionImpl.java:190-194` |
| Volatile tx `status` / `storageTxThreadId` | `FrontendTransactionImpl.java:91, 167` |
| tx `close()` = `closeInternal()` + completer finally; closeInternal finally releases | `FrontendTransactionImpl.java:1028-1079` |
| Pool skip branch (mark-first → re-validate → whitelist-or-fall-through) | `DatabaseSessionEmbeddedPooled.java:63-93` |
| `reuse()` mark-clear | `DatabaseSessionEmbeddedPooled.java:47-54` |
| Pool-close per-session `catch(Throwable)` | `DatabasePoolImpl.java:129-142` |
| Seed-failure release arm (pre-existing, now funnel-routed) | `DatabaseSessionEmbedded.java:3600-3614` |

Funnel completeness (grep-verified): `mutex.engage` has exactly one production call site
(`DatabaseSessionEmbedded.java:3829`); `mutex.releaseFor` has exactly one production call site
(`DatabaseSessionEmbedded.java:3880`, inside the funnel). All four release paths — owner tx-close
finally (`FrontendTransactionImpl.java:1076`), `internalClose` outer finally
(`DatabaseSessionEmbedded.java:3386`), engage Dekker self-release (`:3844`), seed-failure catch arm
(`:3612`) — call `releaseMetadataWriteMutexForTx`. No bypass exists.

---

## 1. Charter (1) — V2 ordering. VERDICT: HOLDS (null finding)

**Decision criterion:** the engage must store the acquire ordinal with volatile semantics strictly
before re-reading the volatile teardown mark; the teardown must write the mark strictly before its
release-pass `getAndSet`; both orderings must carry explicit comments (pass-14 mandate: "the safety
proofs lapse" without them).

Premises, all [verified]:

1. Engage side: `engagedMutex = mutex` (volatile store, `:3830`) → `engagedMutexOrdinal.set(ordinal)`
   (AtomicLong volatile store, `:3838`) → `if (teardownIntent)` (volatile read, `:3839`). Explicit
   V2 comment at `:3831-3837` naming the ordering and its proof obligation.
2. Teardown side, session path: `teardownIntent = true` at `:3350` (strictly after the one-shot
   guard `:3339`) → release pass `getAndSet` in the outer finally at `:3386`. Explicit comment
   `:3345-3349` ("mark-write before the release pass").
3. Teardown side, pool path: `markTeardownIntent()` at `DatabaseSessionEmbeddedPooled.java:70`
   before `super.close()` → `internalClose` (marks again, then claims). Explicit comment `:63-69`.
4. Types: `teardownIntent` volatile (`:318`); `engagedMutexOrdinal` AtomicLong (volatile semantics,
   deviation 2 — semantically identical to the sketched volatile-long + field-updater); mutex
   `holder` `AtomicReference` (`:104`); tx `status`/`storageTxThreadId` volatile.

**Dekker proof against the real code** (the comment's claim, re-derived): if the teardown's
`getAndSet(engagedMutexOrdinal, 0)` returns 0, the engage's `set(ordinal)` follows that RMW in the
variable's synchronization order; the teardown's mark-write precedes its RMW in program order and
the engage's mark-read follows its ordinal-store in program order, hence mark-write <so mark-read
and the engage sees the mark, self-releases through the funnel, and throws
(`DatabaseException("session was closed while engaging...")`, `:3845-3846`). If the `getAndSet`
returns the ordinal, the teardown harvests and releases. Both-see: the engage's self-release claim
returns 0 (teardown claimed first) or the teardown's claim returns 0 (engage self-claimed first) —
`getAndSet` atomicity admits exactly one harvester; the loser warn-noops inside `releaseFor` only
if it even reaches the mutex (it does not — a zero claim returns at `:3866-3868`). No interleaving
leaves the permit without a releaser; no interleaving produces two `permit.release()` calls.

Window enumeration for the engage (permit acquired at `:154`, ordinal stored at `:3838`):

| Teardown claim timing | Outcome |
|---|---|
| Claim before ordinal store (returns 0) | mark visible to engage re-check → self-release + throw. One release. |
| Claim after ordinal store (harvests) | teardown releases; engage's re-check may also see the mark → its self-claim returns 0, no-op, throws. One release. |
| No teardown | normal lifecycle; R1/R2 funnel. One release. |

One non-finding worth the record: between `tryAcquire` success (`:154`) and `holder.set` (`:179`)
an async throw (OOM in `new Holder`) would strand the permit with a null holder. This window is
byte-identical to HEAD's `acquireUninterruptibly`→`new Holder` window — pre-existing, OOM-only, not
a Step 3 regression. No finding.

## 2. Charter (2) — single-claim release funnel. VERDICT: INTACT (null finding); one adjacent defect (CN33, §5)

**Decision criterion:** every release path routes through the `getAndSet(0)` claim; the
`(session, ordinal)` CAS second belt is intact; every enumerable multi-releaser interleaving yields
exactly one `permit.release()` per acquisition.

Release-site enumeration (complete, per the grep in §0): R1 owner tx-close finally
(`FrontendTransactionImpl.java:1076`); R2 `internalClose` outer finally
(`DatabaseSessionEmbedded.java:3386` — reached by pool fall-through teardown, owner completer,
non-pooled `close()`, and the early-return `isClosed()` arm `:3358-3361`, which passes through the
finally); R3 engage self-release (`:3844`); R4 seed-failure catch (`:3612`). All call the funnel
`:3864`: `getAndSet(0)` → zero ⇒ return; nonzero ⇒ `mutex.releaseFor(this, ordinal)`.

Second belt intact [verified `MetadataWriteMutex.java:194-217`]: `releaseFor` re-reads the holder,
warn-noops on null/session/ordinal mismatch, `compareAndSet(current, null)` gates the single
`permit.release()`, winner-nulls-before-release preserved (FM-A8 ordering).

Interleaving enumeration (one acquisition, ordinal `o`, stored exactly once):

1. **R1 vs R2 (FM-A4b, owner finally vs pool teardown pass):** two concurrent `getAndSet(0)` on one
   AtomicLong — exactly one returns `o`; the other returns 0 and never reaches the mutex. One
   `releaseFor(S,o)`, holder matches, CAS wins, one release. ✓
2. **R3 vs R2 (Dekker both-see):** as §1 — one harvester. ✓
3. **R1 then completer's R2 (same thread, sequential):** completer's claim returns 0 (the comment
   at `FrontendTransactionImpl.java:1032-1034` states this; verified). ✓
4. **Triple race R1 + pool R2 + completer R2 (both-act case):** one claim winner; the two losers
   return at the zero-check. One release. ✓
5. **Stale ordinal presented directly to the mutex** (recycled-session earlier acquisition): the
   funnel cannot produce it (the claim is destructive); a hypothetical direct presenter mismatches
   the ordinal → warn-noop (`:198-208`), no CAS attempt. Test
   `doubleReleaseKeepsSinglePermit` pins exactly this with `mutex.releaseFor(session, 999_999L)`. ✓
6. **Delayed harvester vs re-engage on the same mutex:** a releaser that harvested `o` always
   presents `o`; a subsequent engage of the same session installs holder `(S, o')` with `o' > o`
   (per-mutex monotonic generator, `:96-98`) → mismatch → warn-noop. The re-engage itself is only
   reachable after the prior acquisition was released (else FM-A7 throws), so the harvested-`o`
   presenter races only the *free* or *successor-held* holder — both warn-noop. ✓

**At-least-one-release** (invariant 2, amended form): for every acquisition whose ordinal was
stored, one of R1–R4 runs unless the transaction is abandoned with the session never closed —
FM-A5, the accepted loud wedge (no change). The Q-A2 skip path returns from `realClose` before
`internalClose` (`DatabaseSessionEmbeddedPooled.java:87-92`), so the widened finally can never
release a live foreign commit's permit (CN11 ordering) — and the deferred acquisition is released
by the owner's own R1 at tx-close, which runs on both outcomes. Even in the neither-completes
corner traced in §6 (CN34), R1 has already released the permit — the leak there is the *session*,
never the permit.

## 3. Charter (3) — `engagedMutex` captured reference (deviation 3). VERDICT: SAFE (null finding)

**Threat:** a stale captured reference releases against the WRONG mutex instance, defeating the
ordinal's ABA protection (per-instance generators restart at 1, so `(S, 1)` could collide across
instances).

Premises, all [verified]:

1. `engagedMutex` is written only at engage (`:3830`), before the ordinal store, and never cleared;
   volatile, so a foreign harvester that observed the nonzero ordinal (AtomicLong read = acquire
   edge over the engage's prior writes) reads the reference the same engage published.
2. A cross-instance wrong release requires the same session object to engage two DIFFERENT
   `MetadataWriteMutex` instances in its lifetime. `SharedContext.metadataWriteMutex` is
   `private final` (`SharedContext.java:57`), so that requires the session to rebind
   `sharedContext`. The only assignments are `init(...)` (`DatabaseSessionEmbedded.java:425`) and
   `internalCreate(...)` (`:552`) — and every production caller invokes them on a freshly
   constructed object (`YouTrackDBInternalEmbedded.java:343-346` `newSessionInstance`, `:349-353`
   `newCreateSessionInstance`, `:507-513` `newPooledSessionInstance`); no re-init of a used session
   exists.
3. Pooled recycling reuses the session object but never the context: `reuse()` touches only
   activation/mark/status (`DatabaseSessionEmbeddedPooled.java:47-54`), and a session whose backend
   closed is discarded by `reuseResource`'s `isBackendClosed()` check
   (`DatabasePoolImpl.java:100-107`) — a new object is created against the new context.

Therefore every release keyed to session S presents to the ONE mutex instance S ever engages; the
"wrong mutex" scenario is structurally unreachable. The `mutex == null` arm in the funnel
(`:3870-3878`) is correctly labeled unreachable-by-construction and log-only. Deviation 3 breaks no
design guarantee.

## 4. Charter (4) — skip protocol and CN22. VERDICT: conforms structurally; one JMM hole (CN32); accepted-residual notes

**Whitelist conformance (CN20+CS17)** [verified `DatabaseSessionEmbeddedPooled.java:70-92`]: the
skip branch performs exactly mark → re-validate → log → return. It does NOT rollback/clear, does
NOT release, does NOT flip session status, does NOT decrement the session count, does NOT null
`sharedContext`, does NOT run close listeners, does NOT consume the one-shot guard (it returns
before `internalClose` is entered). Deviation 5 (vacuous "remove pool-thread-private activation")
is verified vacuous: `activateOnCurrentThread()` sits on the fall-through side only (`:93`), after
the skip check, so the skip plants nothing on the pool thread — nothing to remove, and the seam
comment documents it (`:88-90`). The pool-closing thread's own pre-existing activation is untouched
by the skip (the fall-through's `activeSession.remove()` in `internalClose`'s finally is
pre-existing behavior). No guard-state or ThreadLocal leak.

**Mark-first-then-revalidate vs publish-completion-then-read (CN22)** [verified]: pool side —
`markTeardownIntent()` (`:70`) strictly before `hasInFlightForeignCommit()` (`:71`). Owner side —
the volatile tx-status write leaving COMMITTING (`status = TXSTATUS.INVALID`,
`FrontendTransactionImpl.java:1064`, or `ROLLBACKING` at `:438` on the failure path) strictly
precedes the completer's mark-read (`close()`'s finally, `:1038`, → `:3922`).

**CN22 interleaving enumeration against the real code:**

- **(a) pool marks, re-check sees COMMITTING → skip.** Then pool-mark-write <so pool-status-read;
  the read seeing COMMITTING places it before the owner's INVALID write in synchronization order,
  hence pool-mark-write <so owner-INVALID-write <po owner-mark-read → the owner's completer sees
  the mark, `teardownIntent && !internalCloseInProgress && status == OPEN` passes, and the owner
  runs the full `internalClose` on the owning thread. ✓ (Pinned by
  `poolCloseDuringCommitDefersTeardownToOwner`.)
- **(b) commit finishes first → pool re-check sees non-COMMITTING → pool falls through** to
  `activateOnCurrentThread() + super.close()` → full teardown on the pool thread. ✓ (Pinned by
  `poolCloseFallsThroughToFullTeardownWhenNotCommitting`, idle-BEGUN shape.)
- **(c) both act:** pool re-check reads a status the owner has already left COMMITTING (or the
  ROLLBACKING/BEGUN boundary), falls through, WHILE the owner's completer also sees the mark and a
  stale-OPEN status/false `internalCloseInProgress` (both plain) — both pass the plain one-shot
  guard (`:3339`) before either writes CLOSED. The PERMIT is safe (one claim winner, §2 case 4).
  The residual is NOT confined to the enumerated FM-A4c noise — see CN33 (§5).
- **Neither acts:** requires the owner never to publish a post-COMMITTING status before its
  mark-read AND the pool to keep seeing COMMITTING. On the normal path impossible (chain in (a));
  reachable only when tx `closeInternal` throws before `status = INVALID` — see CN34 (§6).

**CN32 — the detection chain reads a PLAIN field.** `hasInFlightForeignCommit`
(`DatabaseSessionEmbedded.java:3943-3946`) dereferences `currentTx` — a plain field (`:279`) that
IS reassigned across transaction boundaries (`currentTx = transaction` at `:4454` on begin;
`currentTx = new FrontendTransactionNoTx(this)` at `:2467/:2499` via `setNoTxMode`). The design's
CN13 amendment pins, verbatim: "Skip DETECTION must not read plain fields." The volatile hardening
was applied to `status`/`storageTxThreadId` but the reference through which they are reached is
plain, and the pool thread has no happens-before edge to the borrower's `currentTx` write (the
session reached the pool thread via `resourcesOut`, populated at acquire time, BEFORE the borrower
began the transaction — `ResourcePool.java:129`, `:191-193`).

*Counterexample (false-NO-skip):* borrower thread T1: acquire session S (S enqueued in
`resourcesOut`) → `begin()` (writes `currentTx = txN`, plain) → first schema write engages the
mutex → `commit()` → `status = COMMITTING` (volatile, but on `txN`, which the pool may not see) →
parks inside the commit window. Pool thread T2: `pool.close()` → `realClose` → mark →
`hasInFlightForeignCommit()` reads `currentTx` with no HB edge → JMM-legally observes the
pre-begin `FrontendTransactionNoTx` → `instanceof FrontendTransactionImpl` fails → returns false →
fall-through: full teardown (rollback/`clear()`/cache shutdown/status flip) under T1's LIVE commit
— exactly the FM-A4a tx-object corruption the Q-A2 skip exists to prevent. The volatile writes on
`txN` are unreachable through the stale reference, so CN13's "impossible" claim does not hold as
implemented. The false-SKIP direction stays impossible for stale-object reads on the normal path
(every tx that enters COMMITTING later writes COMPLETED/ROLLBACKING/INVALID volatile-ly, and a
stale object's last status is never COMMITTING except in the CN34 throw corner).

*Severity calibration:* the failure outcome equals HEAD behavior (which the ruling class
"late-rollback = today's behavior, strictly no worse" tolerates for TIMING races), but the design
explicitly promoted the detection to a defined-happens-before mechanism and the implementation
leaves a JMM-legal stale read in the chain; on weak-memory hardware this is not theoretical. Fix
cost is one keyword (`private volatile FrontendTransaction currentTx`) or routing the detection
through a volatile handle. **Should-fix.**

## 5. Charter (5) — completer, throw isolation, both-act. VERDICT: placement and isolation conform; both-act exceeds the accepted noise (CN33)

**Placement/isolation conformance (CS16)** [verified]: the completer runs in `close()`'s finally,
strictly after `closeInternal()` (`FrontendTransactionImpl.java:1028-1040`), on both outcomes
(`doCommit:779` success path and `rollbackInternal:469` failure path both call `close()`); the
mutex is released by `closeInternal`'s own finally first (`:1076`), making the completer's release
pass a no-op via the claim; `completeDeferredTeardownAfterTxClose` wraps its `internalClose(false)`
in `catch (Throwable)` log-only (`:3925-3934`), so a teardown throw can never mask the commit
outcome (pinned by `throwingCloseListenerNeverMasksCommitOutcome`, whose listener throws
`AssertionError` — deliberately outside `catch(Exception)` nets). P7 holds: on the normal path
`closeInternal` ran `setNoTxMode()` before the completer, so the completer's `internalClose` →
`rollback()` finds `currentTx.isActive() == false` (`:4824-4828`) and no rollback listeners fire on
a durable commit. The `internalCloseInProgress` filter correctly suppresses the recursive
tx-close-inside-internalClose case (same-thread program order; deliberately plain, documented
`:320-330`).

**CN33 — both-act runs TWO non-recycle teardowns → double `storage.close(this)` → session-count
corruption.** In the CN22 case (c) overlap, the pool's fall-through `internalClose(false)` and the
owner completer's `internalClose(false)` can both pass the plain one-shot guard (`:3339`) before
either writes `status = CLOSED` (`:3370`) — the design itself established the guard is passable
concurrently (CN18). Both then execute `storage.close(this)` (`:3373`), and
`AbstractStorage.close(session)` unconditionally decrements `sessionCount`
(`AbstractStorage.java:630-640`), throwing `StorageException` only after the decrement landed.

*Counterexample:* storage shared by pool A and an independent context/pool B; `sessionCount == 2`
(A's mid-commit session S, B's live session). Pool A closes; skip marks S; owner commits;
owner-INVALID-write and pool-status-re-read interleave so the pool falls through while the owner's
completer also proceeds (stale-plain `internalCloseInProgress`/status reads, or pure timing: both
pass the guard in the window before either sets CLOSED). Both decrement: count 2 → 0 with B's
session still live → the auto-close sweep (`checkAndCloseStorages`,
`YouTrackDBInternalEmbedded.java:179-191`, fires on `getSessionsCount() == 0`) runs
`forceDatabaseClose` → `SharedContext.close` under B's live session — the precise hazard the
CN20+CS17 amendment classified as "outside CS13's accepted scope" when it struck the pool-side
decrement from the whitelist. In the last-session variant the second decrement drives the count to
−1 and the thrown `StorageException` is swallowed on both sides (completer `catch(Throwable)`;
pool-loop `catch(Throwable)`), leaving the count permanently skewed.

*Why this is not covered by the accepted FM-A4c entry:* CN18's enumerated shape was pool `realClose`
vs the borrower's own `close()` — for a pooled session the latter is `internalClose(true)`
(recycle, `DatabaseSessionEmbeddedPooled.java:37-44`), which does NOT call `storage.close`; only ONE
decrement existed in that shape, and the accepted residuals were CME noise and a skipped
`resetTsMin`. The completer introduced by this step creates the first two-non-recycle-teardown
overlap. The design leaned on "the `internalClose` one-shot guard (one full teardown)" in CN22(c) —
an overclaim its own CN18 finding contradicts; the decomposition's optional hardening ("serialize
teardown entry with an atomic one-shot session claim") was not taken.

*Fix direction:* make the one-shot guard an atomic claim (e.g., CAS `status` OPEN→CLOSING, or an
`AtomicBoolean teardownClaimed.compareAndSet(false, true)` at `internalClose` entry) so exactly one
full teardown body runs; the release funnel already tolerates either winner. **Should-fix.**

## 6. Charter (6) — Q-A3 loop and FM-A7. VERDICT: conforms (null findings); two residuals recorded

**Interrupt path** [verified `MetadataWriteMutex.java:157-167`]: `Thread.currentThread().interrupt()`
before the throw; `DatabaseException` names the holder via `describeHolder()`. Pinned by
`interruptedEngageWaiterThrowsAndRestoresInterruptFlag` (flag assertion captured inside the catch,
before any cleanup — load-bearing).

**WARN naming the holder** [verified `:168-173`]: fires per 10s timeout, names database, ordinal,
thread, elapsed (Q-A3 pin 2 satisfied; interval a non-configurable constant, `:73-79`).

**Loop-top self-teardown check — can a waiter miss its own teardown and park forever?** No.
Enumeration: (i) mark set while the waiter is between loop-top and `tryAcquire` → the 10s timeout
returns false → next loop-top check throws (bounded by one cadence). (ii) mark set while parked and
the permit is then RELEASED → `tryAcquire` returns true → the post-acquire Dekker re-check
(`:3839`) catches the mark → self-release + throw. (iii) mark set while parked, permit never
released → case (i) at the next timeout. Worst-case detection latency: one cadence window (~10s).
No forever-park.

**FM-A7 detection — false positive on a legitimate path?** No. The check (`:122-132`) fires on
`holder.session() == session` regardless of thread (deviation 4 — strictly wider than the sketch).
A legitimate same-session engage requires the previous acquisition to have been released, and on
the only legal caller sequence (one thread per session; engage once per tx via the
`ensureTxSchemaState` seed-once contract, `:3576-3586`) the release precedes the next engage in
program order; `AtomicReference.get` then reads null-or-successor. A cross-thread same-session
engage concurrent with a pool teardown's in-flight release could transiently read the old holder
and throw — but that caller is by definition racing its own session's teardown (misuse), and the
outcome is a loud throw with no permit action: within the Q-A5 contract. Deviation 4 breaks no
guarantee. Type/message pins verified against the ruling (ordinal, thread, elapsed, "never
released"; `IllegalStateException`) and pinned by `strandedSameSessionReengageThrowsLoudly`.

**CN34 (residual, suggestion)** — the neither-completes corner. If tx `closeInternal` throws before
`status = TXSTATUS.INVALID` (`clear()` at `:1044` — the same [inferred] reachability class the
design accepts for FM-A2), the tx status is stuck COMMITTING and `storageTxThreadId` stays nonzero.
The pool's re-validation then always sees an "in-flight" commit and skips; the owner's completer
DID run (close()'s finally) but, if its mark-read preceded the pool's mark-write in synchronization
order, it saw no mark — neither side ever tears the session down. The permit is NOT stranded
(`closeInternal`'s finally released it at `:1076` before the completer ran), but the session, its
count, and its pinned atomic operation leak until process end — loud via the YTDB-550 monitor
(tsMin pinned because `clear()` threw before `atomicOperation.deactivate()`). The CN22 amendment's
"the only defeater is JVM death mid-completer" mildly overclaims: a pre-INVALID teardown throw is a
second defeater, collapsing to the same FM-A5 loud-leak class. Sub-corner: if the completer DOES
see the mark on this path, its `internalClose` → `rollback()` finds the un-swapped COMMITTING tx
active and runs rollback machinery (incl. rollback listeners) on a durably-committed transaction's
tx object — in-memory only, on a discard path, downstream of a pre-existing close-throw masking
shape. Recommend: record both as an accepted FM-A5-class residual in the track file (no code
change demanded by this review).

**CN35 (residual, suggestion)** — the loop-top `session.isClosed()` (`MetadataWriteMutex.java:147`)
resolves to `AbstractStorage.isClosed(db)` (`AbstractStorage.java:1575-1592`), which acquires
`stateLock.readLock()` unless the CALLING thread is inside its own commit window. A waiter (and
every first-engage fast path) therefore takes a state-lock round-trip; while the current mutex
holder is inside its commit window holding `stateLock.writeLock`, the waiter's loop-top check
blocks in the reader spin — uninterruptible and outside the WARN cadence — for up to the commit
window's duration. No deadlock (the engage-order guard proves the waiter holds no metadata locks,
`:3813-3826`; the read acquisition is bounded by the holder's window) and the wait is one the
waiter would serve anyway on the permit; but the Q-A3 interruptibility pin is weakened during that
phase, and under the not-yet-landed Step 5 freezer outage the phase can be long. The step's own
test had to avoid `isClosed()` for exactly this reason (episode note; the test uses the lock-free
`getStatus()` probe). Recommend the loop-top use the lock-free probe
(`getStatus() == STATUS.CLOSED`) — `teardownIntent` remains the authoritative Dekker signal, so the
weaker probe loses nothing.

## 7. Charter (7) — CN13 volatile sufficiency. VERDICT: fields conform; chain does not (CN32, §4)

`status` and `storageTxThreadId` are volatile (`FrontendTransactionImpl.java:91, 167`) with
rationale comments; production writes are confined to tx-boundary paths (begin `:231/:243`,
rollback `:438/:470`, commit `:748/:779`, close `:1059-1064`; `setStatus` `:573` has no production
caller — test-only [verified by grep]). The two-volatile non-atomic read pair in
`isCommittingOnForeignThread` (`:191-194`) admits only the accepted TOCTOU residuals: COMMITTING
read + zeroed-thread-id read ⇒ no-skip at the commit's tail (FM-A4c-class overlap, accepted);
COMMITTING + live thread id at the tail ⇒ late-skip, owner's completer chain covers it (§4(a)).
The reused-object stale-COMMITTING false-skip CN13 targeted is impossible THROUGH THE VOLATILE
FIELDS — but the plain `currentTx` reference re-opens a stale-object channel the amendment's
"written only on tx-boundary paths" premise implicitly assumed away (CN32).

## 8. Charter (8) — test quality. VERDICT: 9 of 10 shapes carry load-bearing assertions; one load-bearing branch has no regression net (CN36)

Regression-detection audit of the ten new tests (would the test fail if the pinned mechanism were
reverted?):

| Test | Reverted mechanism | Fails? | Load-bearing assertion |
|---|---|---|---|
| `teardownRollbackThrowBeforeTxCloseStillReleasesPermit` | widened outer-finally release (CN12) | YES | `isEngagedBy == false` immediately after `close()` (fast-fail before the follow-up DDL would hang) |
| `engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree` | loop-top self-check | PARTIAL — the post-acquire belt still satisfies the contract; deleting BOTH belts hangs the test (join-timeout fail) | throw type + `isEngagedBy == false` |
| `foreignTeardownHarvestsEngagedPermit` | teardown harvest via claim | YES | `isEngagedBy == false` after foreign `internalClose` + next-writer progress |
| `doubleReleaseKeepsSinglePermit` | getAndSet claim / releaseFor CAS+ordinal | YES | the second engager's OBSERVED PARKED STATE (`awaitThreadParked`) — a double-released permit admits it immediately; plus the stale-ordinal warn-noop probe |
| `strandedSameSessionReengageThrowsLoudly` | FM-A7 reject | YES (would park → join-timeout) | ISE type + "already held"/"never released" message pins |
| `poolCloseDuringCommitDefersTeardownToOwner` | skip protocol / completer | YES | mid-window lock-free `getStatus()==OPEN`, `isEngagedBy==true`, commit-latch still up; then `pooled.isClosed()` (completer acted), permit free, class durable, storage usable |
| `poolCloseFallsThroughToFullTeardownWhenNotCommitting` | fall-through re-validation | YES | session closed + permit harvested by the pool thread |
| `throwingCloseListenerNeverMasksCommitOutcome` | CS16 throw isolation | YES | `victim.commit()` returns normally under an `AssertionError` listener; class exists |
| `interruptedEngageWaiterThrowsAndRestoresInterruptFlag` | Q-A3 pin 3 | YES | flag captured in-catch + exception type/message |
| `poolCloseLoopSurvivesThrowingSessionTeardown` | pool-loop isolation | YES | `pool.close()` returns; storage usable |

**CN36 — the post-acquire Dekker re-check has no regression net, and deviation 6's
"not deterministically driveable" claim is wrong for this shape.** Delete the re-check block
(`DatabaseSessionEmbedded.java:3839-3847`) — the V2-load-bearing engage-side belt — and ALL current
tests stay green: `engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree` is intercepted by the
loop-top check (the mark is set before engage starts, and the permit is free so `tryAcquire` would
succeed only after the loop-top already threw); `foreignTeardownHarvests...` exercises the
harvest side only. Yet the interleaving the re-check exists for IS deterministically driveable with
the file's own helpers: session A engages (holds the permit); victim V's engage on a worker thread
passes the loop-top (unmarked) and parks in `tryAcquire` — `awaitThreadParked` proves the park;
test marks V (`markTeardownIntent()`); test releases A's permit; V's `tryAcquire` returns true
directly (no loop-top re-run), V stores holder+ordinal, reads the mark, must self-release and throw
`DatabaseException` with the permit ending FREE (`isEngagedBy(V) == false`, and a follow-up engage
succeeds). Without the re-check the same schedule strands the permit (teardown's claim already ran
and returned 0) — the test fails loudly. This is the FM-A3 "engage missed by the teardown"
interleaving, the exact shape whose closure is Draft A's headline; it deserves a deterministic pin.
The CN22(c) both-act shape, by contrast, genuinely requires two in-body pauses and is fairly
covered by the claim/CAS design plus `doubleReleaseKeepsSinglePermit` — deviation 6 stands for that
half. **Should-fix (test-only).**

**CN37 (suggestion)** — comment drift, two instances: (1) `MetadataWriteMutexTest` class javadoc
(`:36-38`) still reads "The abnormal-termination permit handshake and the freezer gate are a later
track and are not exercised here" — false as of this commit for the handshake half. (2)
`reuse()`'s comment (`DatabaseSessionEmbeddedPooled.java:48-51`) and the `teardownIntent` javadoc
(`DatabaseSessionEmbedded.java:316-317`) call the reuse-clear the "second belt", with the primary
belt being "a guard-returning no-op close never plants the mark" — but every pooled RECYCLE
(`internalClose(true)`, a real teardown, correctly marks per CN16/CN24) returns the session to the
pool with the mark SET, so for every recycled borrow the reuse-clear is the ONLY thing keeping the
first engage alive. The mechanism is correct and conforms to Q-A4's hygiene pin ("cleared on every
re-open/reuse() path" — the borrow path is single: `reuseResource` → `reuse()`,
`DatabasePoolImpl.java:100-107` [verified]); only the redundancy claim in the comments is wrong.
Per the thread guidelines' comments-in-sync rule, both should be corrected.

## 9. Deviation register assessment (the six documented deviations)

1. **`Holder.acquiredAtNanos`:** conforms — required by the Q-A5/Q-A3 "elapsed" message pins; no
   concurrency surface (written once by the permit owner, read diagnostically). No issue.
2. **AtomicLong vs volatile-long + field-updater:** semantically identical (same volatile access
   and same atomic `getAndSet` claim); simpler. No issue.
3. **Volatile `engagedMutex` captured at engage:** SAFE — §3 trace; the deviation's stated reason
   (`internalClose` nulls `sharedContext` at `:3372` before the outer finally at `:3386` runs the
   widened release) is real and the capture is the correct fix; without it the widened finally
   would NPE or lose its path on exactly the late-throw teardowns it exists for. No issue.
4. **FM-A7 keyed on session regardless of thread:** strictly wider guard, no legitimate
   false-positive path (§6). No issue.
5. **Vacuous whitelist activation item:** verified vacuous — detection precedes activation
   (`DatabaseSessionEmbeddedPooled.java:70-93`); documented at the seam. No issue.
6. **Two interleavings covered indirectly:** HALF-WRONG — the FM-A3 engage-first interleaving is
   deterministically driveable and currently unpinned (CN36); the CN22(c) both-act half is fairly
   accepted.

## 10. Hypothesis log

| # | Hypothesis | Disposition |
|---|---|---|
| H1 | V2 ordering broken or under-typed | REJECTED — §1; correct volatile/atomic types, correct order, explicit comments |
| H2 | A release path bypasses the funnel | REJECTED — single `releaseFor` call site; four paths all claim-first (§0, §2) |
| H3 | `engagedMutex` stale reference releases wrong mutex instance | REJECTED — sessions never rebind SharedContext; mutex final per context (§3) |
| H4 | Skip detection JMM-unsound via a plain read | CONFIRMED — plain `currentTx` (CN32) |
| H5 | Both-act overlap exceeds accepted FM-A4c noise | CONFIRMED — double `storage.close` decrement → count skew/premature auto-close (CN33) |
| H6 | Completer handshake admits a neither-acts corner | CONFIRMED (narrow) — pre-INVALID teardown throw; session leak only, permit safe, FM-A5-class (CN34) |
| H7 | Waiter misses its own teardown and parks forever | REJECTED — loop-top + post-acquire belts; ≤1 cadence latency (§6) |
| H8 | Interrupt flag lost or wait made bounded/spurious | REJECTED — §6; unbounded wait, flag restored, tested |
| H9 | Stale mark survives into a fresh borrow (CN24 channel) | REJECTED mechanically — guard-first mark placement + reuse-clear on the single borrow path; comment overstates redundancy (CN37) |
| H10 | Loop-top `isClosed()` introduces a blocking/uninterruptible phase | CONFIRMED (bounded, no deadlock) — CN35 |
| H11 | Post-acquire Dekker re-check unpinned by tests / deviation-6 claim wrong | CONFIRMED — CN36 |
| H12 | Skip branch leaks one-shot guard state or ThreadLocals | REJECTED — §4 whitelist trace |
| H13 | Completer fires from a non-boundary tx-close site with bad effect | REJECTED — triple guard (mark, in-progress, status) covers recycle/re-close/graph-layer closes |
| H14 | Double/triple release reaches two `permit.release()` calls | REJECTED — §2 enumeration; CAS second belt intact |
| H15 | FM-A7 false positive on a legitimate path | REJECTED — §6 |
| H16 | Engage-throw double-release via the seed catch arm | REJECTED — engage call sits above the seed try (`:3586-3588`); funnel idempotent anyway |
| H17 | Fall-through TOCTOU releases a live commit's permit | REJECTED as new risk — accepted class (late-rollback = HEAD behavior; A4a permit-side outcome accepted by ruling) |
| H18 | OOM between acquire and holder-write strands the permit | REJECTED as a Step 3 defect — byte-identical window at HEAD; OOM-only |

## 11. Verdict summary

The headline design guarantees hold in the implementation: V2 ordering is real and correctly
typed; the single-claim funnel is complete with the CAS second belt intact and exactly-one-release
provable across all enumerated interleavings; the captured-mutex deviation is safe; the skip
whitelist, completer placement, and throw isolation conform to CN20/CN22/CS16; the Q-A3 loop and
FM-A7 pins are met and tested. No blocker. Two should-fix code defects (CN32 plain-`currentTx` in
the skip detection chain; CN33 both-act double session-count decrement), one should-fix test gap
(CN36 post-acquire Dekker re-check unpinned), and three suggestions (CN34 residual to record, CN35
lock-free loop-top probe, CN37 comment sync).

## Findings

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CN32 | should-fix | `DatabaseSessionEmbedded.java:279, 3943-3946`; `FrontendTransactionImpl.java:190-194` | Q-A2 skip detection dereferences the plain `currentTx` field from the pool thread, violating the CN13 pin "skip detection must not read plain fields"; the volatile `status`/`storageTxThreadId` are reached through a reference with no happens-before edge | Pool thread JMM-legally reads the pre-begin `FrontendTransactionNoTx` → `hasInFlightForeignCommit` false → full teardown (rollback/clear/status-flip) under the owner's live COMMITTING transaction — the FM-A4a corruption the skip protocol exists to prevent. Fix: make `currentTx` volatile |
| CN33 | should-fix | `DatabaseSessionEmbedded.java:3339, 3373`; `AbstractStorage.java:630-640` | CN22 both-act: pool fall-through and owner completer can both pass the plain one-shot guard and both run non-recycle `internalClose` → double `storage.close(this)` decrement — beyond the accepted FM-A4c residuals (CN18's shape had one decrement); the design's "one-shot guard (one full teardown)" is an overclaim | Storage shared with pool B at count 2: both-act double-decrement → 0 with B's session live → auto-close sweep `forceDatabaseClose`/`SharedContext.close` under a live session (CN20's own out-of-scope hazard); last-session variant leaves count −1 with the `StorageException` swallowed on both sides. Fix: atomic teardown-entry claim |
| CN36 | should-fix (test) | `MetadataWriteMutexTest.java` (gap); `DatabaseSessionEmbedded.java:3839-3847` | The post-acquire Dekker re-check — the V2 engage-side belt — has no regression net: deleting it leaves all 19 tests green; deviation 6's "not deterministically driveable" is wrong for this shape | Deterministic schedule with existing helpers: hold permit via session A → victim parks in `tryAcquire` (`awaitThreadParked`) → mark victim → release A → victim acquires, must self-release + throw with permit free; without the re-check this schedule strands the permit and the test fails |
| CN34 | suggestion | `FrontendTransactionImpl.java:1042-1064`; `DatabaseSessionEmbedded.java:3921-3924` | Neither-completes corner: tx `closeInternal` throw before `status = INVALID` leaves status stuck COMMITTING → pool re-check skips forever while the owner's completer may have already missed the mark; CN22's "only defeater is JVM death" overclaims | `clear()` throws post-durability → permit released (closeInternal finally) but session/count/tsMin leak until restart (loud via YTDB-550); record as accepted FM-A5-class residual in the track file |
| CN35 | suggestion | `MetadataWriteMutex.java:147`; `AbstractStorage.java:1575-1592` | Engage loop-top `session.isClosed()` acquires `stateLock.readLock` — a blocking, uninterruptible, WARN-silent phase whenever any commit holds the write lock; the step's own test had to avoid `isClosed()` for this reason | Waiter's interrupt response and diagnostics stall for the holder's whole commit window (unbounded under the pre-Step-5 freezer outage); use the lock-free `getStatus()` probe — `teardownIntent` stays the authoritative signal |
| CN37 | suggestion | `MetadataWriteMutexTest.java:36-38`; `DatabaseSessionEmbeddedPooled.java:48-51`; `DatabaseSessionEmbedded.java:316-317` | Comment drift: test class javadoc still says the handshake "is a later track"; the reuse-clear is described as a "second belt" though every recycled borrow depends on it as the ONLY belt (recycle teardowns correctly plant the mark) | A reader trusting the "second belt" wording could remove the reuse-clear as redundant → every recycled borrow becomes DDL-dead (first engage self-aborts) — the exact CN24 hazard |

Null verdicts (explicit): V2 ordering — holds; release funnel + CAS second belt — intact, exactly
one release per acquisition across all enumerated races; `engagedMutex` captured reference
(deviation 3) — safe; skip whitelist/guard/ThreadLocal conformance — exact; CN22 (a)/(b) — proven
by the volatile Dekker chain; completer placement/isolation (CS16) — conforms; Q-A3 loop semantics
and FM-A7 pins — conform and tested; deviations 1, 2, 4, 5 — no issue.
