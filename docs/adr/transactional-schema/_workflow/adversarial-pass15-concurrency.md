# YTDB-382 — Adversarial pass 15: concurrency, scoped to pass-14 amendments (2026-07-21)

Fifteenth adversarial pass, single lens (concurrency), scoped strictly to the pass-14 amendments
("## Amendments — adversarial pass 14 triage (2026-07-21)" in
`_workflow/track-7-design-drafts.md`) and their splice points into the agreed base design. The base
design and the 2026-07-20 Rulings were attacked in round 1 (pass 14) and are settled; round-1
verdicts are not re-litigated. Amended parts attacked: (1) the CN10 third checkpoint (timed
`stateLock.writeLock` acquisition via `ScalableRWLock.exclusiveTryLockNanos`); (2) the CS10 probe
hoist + defensive paired clear; (3) the Q-A2 skip-protocol specification (whitelist,
owner-as-completer finally, volatile `status`/`storageTxThreadId`); (4) the CN12 widened release
finally at `internalClose`'s outer finally + `DatabasePoolImpl.close` per-session Throwable
isolation; (5) the CS14 underflow guard; (6) the CN16/CN17 pins; (7) the amended invariant
statements.

**Verdict: 1 BLOCKER, 3 SHOULD-FIX, 2 SUGGESTIONS.** The blocker (CN19) is in the CN10 amendment
itself: `ScalableRWLock.exclusiveTryLockNanos` releases the write bit on every drain timeout, so
the timed-acquire loop destroys the admission control that makes the plain `exclusiveLock()`
starvation-free — under sustained reader traffic whose critical sections exceed one polling
interval (ordinary data commits qualify), the armed schema commit can retry indefinitely while
holding `SchemaShared.lock` + the IM lock, an unbounded schema-read outage on the **no-freeze**
path, with general read throughput collapsed to a slip-in trickle for the duration. The
should-fixes are protocol seams in the skip specification (a deterministic double
session-count decrement between the skip whitelist and the owner-as-completer; a
nobody-completes interleaving that falsifies the amended invariant (2)'s "guaranteed to run") and
an unspecified clear-ownership handoff in the CS10 defensive wrap that double-clears
`immutableCount` in the natural implementation. The Q-A2 volatile fix, the widened outer finally,
the pool-loop isolation, the probe hoist's coverage, and the CN17 three-releaser funnel all
**survived** direct attack; traces are in §"Verified-safe claims".

## Surface attacked

`docs/adr/transactional-schema/_workflow/track-7-design-drafts.md` §"Amendments — adversarial
pass 14 triage (2026-07-21)" (lines 515-830) at HEAD `e2605c8ba3`, read against the round-1
reports (`adversarial-pass14-concurrency.md`, `adversarial-pass14-durability.md`) for the intent
each amendment serves. The trailing "Design review verdict" subsection is meta-text, out of scope.

## Code grounding

Read at HEAD `e2605c8ba3` (whole files unless a range is given):
`ScalableRWLock.java` (whole file — `sharedLock:320-357`, `exclusiveLock:389-411`,
`exclusiveUnlock:422-429`, `exclusiveTryLock:531-561`, `sharedTryLockNanos:494-524`,
`exclusiveTryLockNanos:586-616`; class contract: non-reentrant, writer-preference, readers never
touch the internal `StampedLock` — they poll `isWriteLocked()`), `OperationsFreezer.java` (whole —
`startOperation:31-57`, `freezeOperations:72-86`, `releaseOperations:88-112`), `WaitingList.java`
(whole), `AtomicOperationsManager.java` (`startToApplyOperations:128-149`,
`calculateInsideAtomicOperation:151-177`), `AbstractStorage.java` (`close(session):629-640`,
`closeIfPossible:642-653`, `sessionCount` sites `:369,:767,:991`, commit funnel
`commit(clientTx):2476`, `commitPreAllocated:2487`, `commit(tx,allocated):2505-2530` — snapshot
pin `:2525`, schema branch `:2531-2533`, data branch `:2535-2542`,
`applyCommitOperations:2741-3144` — body-opening `try` `:2749`, outermost finally `:3140-3143`
with `clearThreadLocalSchemaSnapshot()` `:3142`, promotion-failure arm `:3113-3137`,
`commitSchemaCarry:3159-3201` — `SchemaShared` `:3175`, IM `:3177`,
`stateLock.writeLock().lock()` `:3179`, `freeze:5508-5560` — readLock `:5510`, park-mode register
`:5526`, `release:5563-5580` — `unfreezeWriteOperations(-1)` `:5571`),
`DatabaseSessionEmbedded.java` (`internalClose:3264-3297` — one-shot guard `:3265-3267`, body try
`:3270`, `closeActiveQueries` `:3271`, `localCache.shutdown` `:3272`, `isClosed` early return
`:3274-3277`, rollback block `:3279-3283`, `status = CLOSED` `:3287`, `storage.close(this)`
`:3290`, outer finally `:3293-3296`; `ensureTxSchemaState:3477-3521`,
`engageMetadataWriteMutex:3585-3600`, `releaseMetadataWriteMutexForTx:3617-3623`,
`close():3840-3842`, `isClosed:668-670`, `checkOpenness:4584-4588`),
`DatabaseSessionEmbeddedPooled.java` (whole — `close:36-42`, `reuse:45-49`, `realClose:56-60`),
`DatabasePoolImpl.java` (whole — `close:128-136`, `release:139-147`), `ResourcePool.java`
(fields/constructor/`getResource` — no per-session count exists at pool level),
`FrontendTransactionImpl.java` (`status:84` plain, `storageTxThreadId:158` plain,
`beginInternal` tid store `:220`, `rollbackInternal:403-463`, `doCommit:690-767` —
`status = COMMITTING` `:726`, catch → `rollbackInternal` `:748-750`, `close():1006-1041` — tid
check/zero `:1013-1024`, mutex-release finally `:1029-1040`), `MetadataWriteMutex.java` (whole),
`MetadataDefault.java` (whole — `makeThreadLocalSchemaSnapshot:77-85`,
`clearThreadLocalSchemaSnapshot:87-93`, `forceClearThreadLocalSchemaSnapshot:95-103`,
`rebuildThreadLocalSchemaSnapshot:105-127`), `YouTrackDBInternalEmbedded.java`
(`checkAndCloseStorages:179-193`, `forceDatabaseClose:1042-1049`).

## Decision criteria and premises

1. A finding is a **blocker** iff a traced interleaving on an **amended** path violates an
   invariant the amended design claims (the four-checkpoint I-freezer-1 statement, the restated
   Draft A invariant (2), the skip-protocol guarantees), with consequences in the design's own
   worst classes (read outage, silent single-writer break, wedge-until-restart, unbounded
   starvation of a liveness-guaranteed path).
2. **Should-fix**: the amendment text, taken literally into implementation, permits (or forces) an
   interleaving/state defect that defeats the amendment's stated purpose, or an amended
   invariant's wording overclaims — but the failure is bounded, loud, or needs a plausible extra
   condition.
3. **Suggestion**: misuse-only exposure, or fully absorbed by an independent belt, or a
   pin-the-wording matter.
4. Pre-existing HEAD pathologies are in scope only where an amendment claims to fix them or
   composes with them into something new.
5. JMM reasoning: synchronization-order consistency for volatile/atomic accesses; plain fields get
   no coherence guarantee. `ScalableRWLock` reader admission is governed solely by
   `stampedLock.isWriteLocked()` polling (readers never enqueue anywhere) [verified `:320-357`].

---

## CN19 [BLOCKER] — The third checkpoint's `exclusiveTryLockNanos` retry loop forfeits writer admission on every timeout; under sustained reader traffic the armed schema commit can starve indefinitely holding `SchemaShared.lock` + IM lock — an unbounded schema-read outage with NO freeze active — while read throughput collapses to a slip-in trickle

**Surface attacked.** The CN10 amendment: "the schema-carry branch acquires `stateLock.writeLock`
through a **timed-acquire loop** using the existing `ScalableRWLock.exclusiveTryLockNanos`
(`:586`) … the loop is otherwise unbounded (a schema commit is never spuriously failed by
contention alone …)"; and its unwind claim "no data commit's `stateLock.readLock` is ever queued
behind an S that gave up, so reads keep flowing"; and the amended invariant "none of which lets an
armed schema commit block or park while an operator freeze is active … the held-lock window
collapses to at most one polling interval."

**Code grounding — what `exclusiveTryLockNanos` actually does** (`ScalableRWLock:586-616`,
answering the mandated questions directly):

- **Phase 1** (`:589`): `stampedLock.tryWriteLock(nanosTimeout, NANOSECONDS)`. The internal
  `StampedLock` is touched **only by writers** of the `ScalableRWLock` (readers poll
  `isWriteLocked()`, `:320-357`; they never acquire the stamped read side). So phase 1 queues the
  caller **against other writers only**; while queued it does **not** block new readers. It is
  also **interruptible** (the method declares `throws InterruptedException`).
- **Phase 2** (`:592-614`): with the stamped write bit now HELD — this is the moment new readers
  start being refused: `sharedLock` sets `READING`, sees `isWriteLocked()`, backs off to
  `NOT_READING`, and spin-yields (`:345-355`) — the writer scans the readers-state array and waits
  for every in-flight reader slot to leave `READING`. A slot the scan has passed cannot re-enter
  `READING` while the bit is held (any new acquisition backs off), so within one attempt the drain
  is monotone. **On budget expiry with any reader still `READING`, it fully releases the write bit
  (`stampedLock.asWriteLock().unlock()`, `:611`) and returns false.** No residual writer-intent
  state survives a timed-out attempt — neither a queue entry nor the bit.
- The single `nanosTimeout` budget is **shared** between phase 1 and phase 2 (`lastTime` is taken
  once, `:587`; the phase-2 check is `System.nanoTime() - lastTime < nanosTimeout`, `:607`): an
  attempt that spent its interval queued behind another writer enters the drain with ~zero budget
  and fails unconditionally.

Contrast with the plain `exclusiveLock()` the amendment replaces (`:389-411`,
`commitSchemaCarry:3179`): it acquires the bit **once** and holds it while draining — new readers
are refused, in-flight readers finish (bounded by the longest residual reader critical section),
the writer then wins. Writer preference = deterministic, bounded acquisition. That bound is
exactly what the timed loop gives up: **a failed drain resets the admission control**, readers
re-enter during the release window, and the next attempt must drain a fresh population within one
interval again.

**Counterexample interleaving (no freeze anywhere; every step HEAD-verified plus the amendment's
addition).** Polling interval T (a "small constant" per the amendment). Sustained data-commit
traffic: multiple threads committing back-to-back, each data commit holding
`stateLock.readLock` for its whole apply (`commit` data branch `:2535-2542` →
`applyCommitOperations` — WAL fsync, page writes; residence realistically ≫ a small T for
non-trivial transactions).

1. Schema commit S passes the hoisted probe (`operatorFreezeRequests == 0`), pins the snapshot,
   takes `SchemaShared.lock` (`:3175`) and the IM lock (`:3177`), and enters the timed loop at the
   `:3179` seam.
2. Attempt 1: phase 1 succeeds immediately (no competing writer); the bit is held; new readers
   (incl. new data commits and any arriving `freeze()`/`synch()`, whose first act is
   `stateLock.readLock`, `:5510`) are refused and spin. In-flight data commit D₁ has residual
   > T → budget expires at `:607` → **bit released** (`:611`), attempt fails.
3. The freeze-counter re-check sees 0 → immediate retry. In the sub-microsecond release window,
   spinning readers race `set(READING)` + `isWriteLocked()` against S's next
   `tryWriteLock` CAS; on a multicore under a large spinning herd, some slip in — each slip-in a
   fresh full-length critical section.
4. Attempt N: at least one in-flight reader (a survivor with residual > T, or a step-3 slip-in)
   is still `READING` when the budget expires → fail → release → slip-ins → retry. **There is no
   attempt whose success is guaranteed**: success requires the maximum residual reader residence
   at bit-acquisition to be < T, and slip-ins at gap boundaries keep re-arming full-length
   residences. S retries unboundedly.
5. The whole time, S holds `SchemaShared.lock` + IM lock: **every lock-based schema read blocks
   indefinitely** — the outage I-freezer-1 exists to prevent, now produced by ordinary contention
   with **no freeze registered**. Meanwhile general reads/commits are admitted only via slip-ins:
   the storage's read path is throttled to near-zero for the duration. The amendment's "reads keep
   flowing" holds only for the give-up (throw) case, not the retry case.

Nothing in the loop escapes this state: the only exit conditions are "drain completed within one
budget" (never guaranteed) and "operator freeze observed" (there is none). The plain `lock()`
would have completed in ≤ max-residual-reader-residence.

**The design's requirements are mutually unsatisfiable with this primitive.** T must be small for
the amendment's own outage bound ("the held-lock window collapses to at most one polling
interval" — that window is precisely one attempt's bit-held drain against a freezer-parked
reader), and T must exceed the longest reader residence for liveness. Data-commit residence is
unbounded (transaction size), so **no constant T satisfies both**. Also noted: "reuse the freezer
gate's polling cadence" is a dangling reference — the freezer gate is park-based
(`OperationsFreezer:47-48`), it has no polling cadence to reuse.

**Alternative-hypothesis check.** (a) "New readers are blocked during attempts, so the reader
population strictly drains" — refuted: the release at `:611` between attempts re-opens admission;
slip-ins are re-armed every failure, and each carries a full residence. (b) "Slip-ins are too rare
to matter" — the counterexample does not need them: a single overlapping sequence of data commits
whose residences each exceed T suffices (commit Dₙ₊₁ slips in during the gap after the attempt
that Dₙ outlived; under back-to-back commit traffic from even one spinning committer thread the
gap-slip repeats), and with many spinning threads the per-gap slip probability is material.
(c) "Phase 1 queues the writer, preserving preference between attempts" — refuted: the stamped
queue orders writers against writers only; readers never consult it (`:320-357`). (d) "SchemaShared
serializes schema commits, so contention is rare" — irrelevant: the starving population here is
readers (data commits), which the base design deliberately leaves on the read-lock fast path.

**Fix direction (stays inside the amendment's shape).** Do not compose the property out of
repeated `exclusiveTryLockNanos` calls. Add one primitive to `ScalableRWLock` — e.g.
`boolean exclusiveLock(BooleanSupplier abort, long pollNanos)`: phase 1 loops
`stampedLock.tryWriteLock(pollNanos)` checking `abort` between attempts (no reader impact while
queued); phase 2 acquires the bit **once** and, inside the existing drain spin (`:604-609`),
polls `abort` each yield — on abort, release the bit and return false. This preserves writer
preference (bounded acquisition = max residual reader residence, deterministic), while the
freeze-abort latency stays one poll granularity — both of the amendment's goals at once. Two
sub-pins to carry regardless of mechanism: (i) `InterruptedException` from the stamped timed
acquire must be handled per Q-A3 pin (3) (restore interrupt flag, throw `DatabaseException`
naming the state) — the amendment is silent on it; (ii) if `exclusiveTryLockNanos` is kept
anyway, its shared phase-1/phase-2 budget means a competing writer (e.g. legacy `addCollection`
`:1657`, `shutdown` `:706`) starves the drain budget of whole attempts — harmless per attempt,
but it widens the retry storm.

**Verdict: blocker.** The amendment trades CN10's freeze-window outage for an unbounded
starvation/livelock on the normal path, violating the design's own liveness stance ("never
spuriously failed by contention alone" — non-abort without progress is not liveness) and
producing the I-freezer-1 outage shape without any freeze.

---

## CN20 [SHOULD-FIX] — Skip-protocol (1)'s "decrement the session count" + skip-protocol (2)'s owner-run "full `internalClose`" double-decrement `AbstractStorage.sessionCount` deterministically; the second decrement can throw `StorageException` out of the owner's completer after a durable commit, and the premature zero arms auto-close under live sessions

**Surface attacked.** Skip-protocol (1): on the skip branch the pool thread performs (whitelist)
"decrement the session count / remove its own thread-local activation bookkeeping", and explicitly
does NOT "call `storage.close(this)`". Skip-protocol (2): "a `finally` in the owner's commit path
checks `teardownIntent` and, if set, runs the **full `internalClose`** itself on the owning
thread."

**Code grounding.** The only session count in this design's scope is
`AbstractStorage.sessionCount` (`:369`; `ResourcePool` has no per-session count — verified whole
file: it holds a semaphore and a `created` counter only). It is incremented once per session open
(`:767`, `:991`) and decremented exactly once per session in `AbstractStorage.close(session)`
(`:631`), which **throws `StorageException`** when the count goes negative (`:633-637`). The full
`internalClose(false)` calls `storage.close(this)` at `:3290`. Auto-close:
`checkAndCloseStorages` (`YouTrackDBInternalEmbedded:179-193`) treats `getSessionsCount() == 0`
as idle for disk storages and, after the delay, runs `forceDatabaseClose` (`:1042-1049`) —
`ctx.close()` **before** `storage.shutdown()`.

**The defect is deterministic, not a race.** On the protocol's own happy path:

1. Pool thread detects the in-flight foreign commit, takes the skip arm, and — per the whitelist —
   decrements the session count (it cannot use `storage.close(this)`, which is blacklisted, so
   this is a direct decrement with the same effect). Count: N → N−1.
2. Owner finishes its commit; its completer finally sees `teardownIntent` and runs the full
   `internalClose(false)` → `storage.close(this)` (`:3290`) → count N−1 → N−2. **One session,
   two decrements — every time the skip is followed by owner completion, i.e. the amendment's
   intended main sequence.**

Consequences, each traced:

- **Spurious `StorageException`, possibly masking a durable commit.** With one session open, the
  owner's decrement is the one that drives the count negative → `close(session)` throws
  (`:633-637`) from inside `internalClose`'s body try → propagates through the (no-op-by-then)
  outer release finally → out of the owner's completer finally → **out of the application's
  `commit()` call after the commit is durable** — the masked-success class the amendments
  themselves treat as poison (CS11). With multiple sessions the negative surfaces later, at the
  storage's genuinely-last close, as a spurious loud failure on an unrelated session.
- **Premature idle-zero.** With two borrowed sessions and one skip, the count reaches 0 while a
  live session still runs → `checkAndCloseStorages` sees idle → `forceDatabaseClose` runs
  `ctx.close()` + `storage.shutdown()` under a live borrower — the CS13 exposure, but now
  *created by bookkeeping corruption* rather than by all sessions genuinely closing.

**Alternative-hypothesis check.** (a) "The whitelist means some pool-level count" — refuted: no
such count exists (`ResourcePool` verified); the only meaningful referent is
`AbstractStorage.sessionCount`. (b) "The owner's `internalClose` will naturally skip
`storage.close`" — nothing in the amendment says so; the protocol explicitly says **full**
`internalClose`, and `:3290` is unconditional on the `!recycle` arm. (c) "The pool thread should
just not decrement" — then, when the owner never returns (the deferral's other outcome), the
count stays pinned and the storage never reaches idle — presumably why the whitelist item exists;
the tension is real and unresolved in the text.

**Fix bound.** Assign the decrement to exactly one actor via a one-shot per-session claim — the
same discipline the amendments already apply to the permit (`getAndSet(engagedOrdinal, 0)`) —
e.g. an atomic `sessionCountReleased` flag consulted by both the skip arm and
`storage.close(this)`; or drop the whitelist item and explicitly accept the pinned-count
consequence for a never-returning owner (FM-A5-adjacent, monitored). Either way the design must
say which; today it specifies both decrements.

**Verdict: should-fix** (deterministic once the skip fires, loud or masked-success outcomes, but
the skip itself is a rare admin-race path and durable state is untouched).

---

## CN21 [SHOULD-FIX] — The CS10 "defensive paired clear" leaves the clear-ownership handoff to `applyCommitOperations`' `:3142` finally unspecified; the natural unconditioned wrapper double-clears `immutableCount` on every exception escaping `applyCommitOperations` — driving the count negative, which is the same session-poisoning class CS10 was reversed to prevent

**Surface attacked.** CS10 amendment: "the pin→`applyCommitOperations`-finally region is wrapped
so that **any** throw escaping between the `:2525` pin and the established `:3142` clear cannot
leak `immutableCount` — a try/finally (or equivalent guard) spanning from immediately after the
pin **through the point where `applyCommitOperations`' own finally takes over**, clearing the
snapshot on the escape."

**Code grounding.** `clearThreadLocalSchemaSnapshot` decrements unconditionally
(`MetadataDefault:87-93`); there is no floor. `applyCommitOperations`' outermost finally
(`:3140-3143`) runs the clear on **every** exit, including every exception — and exceptions out of
`applyCommitOperations` are a **common production path** (version-conflict aborts of ordinary data
commits ride the same method: both call sites, `:2537` schema-carry and data branch, are inside
the pinned region). The method's body-opening `try` is its first statement (`:2749`), so its clear
is armed the instant the method is entered.

**Counterexample (natural implementation).** Implementer reads "a try/finally spanning from
immediately after the pin" and writes exactly that in `commit(tx, allocated)`:
`makeThreadLocalSchemaSnapshot(); try { <branch> } catch/rethrow finally-on-escape { clear(); }`.
Ordinary data commit hits a version conflict inside `applyCommitOperations` → its `:3142` finally
clears (count 1→0, `immutableSchema` nulled) → the exception unwinds into the wrapper → wrapper
clears again → count 0→**−1**. From −1 the session is skewed for life (P6: no reset outside the
trio; pooled sessions recycle with `MetadataDefault` intact):

- the next `make` sees `immutableCount != 0` → **skips the rebuild** and leaves the count at 0
  (`:78-84`) — and worse, a subsequent nested `make` at 0 **rebuilds mid-pin**, silently swapping
  the "pinned" snapshot under an outer logical pin — the stability contract the pin exists for;
- `forceClearThreadLocalSchemaSnapshot` at −1 throws `IllegalStateException` ("usage count is not
  zero: −1", `:95-103`) → `forceRebuildTxSchemaSnapshot` (`DatabaseSessionEmbedded:3563`) fails →
  **DDL on that session is poisoned**, and via the pool the session cycles to arbitrary future
  borrowers — byte-for-byte the CS10 blast radius with the sign flipped.

The amendment's phrase "through the point where the … finally takes over" shows awareness, but no
mechanism is specified, and both simple realizations are defective: the unconditioned wrapper
double-clears (above); a disarm-flag set by the **caller** immediately before invoking
`applyCommitOperations` leaves a (theoretical) frame-entry gap where neither clear runs. The
airtight shapes are: (a) **single-owner clear** — delete the `:3142` clear and put the one clear
in `commit(tx, allocated)`'s own finally spanning everything after the `:2525` pin
(`applyCommitOperations` has exactly two callers, both inside `commit` — verified `:2537`,
`:3187` — so ownership moves cleanly); or (b) an armed flag written as the **first statement
inside** `applyCommitOperations`' `:2749` try. The design must pin one.

**Alternative-hypothesis check.** "The reviewer/test matrix will catch it" — the amended CS10 test
asserts count balance after a **probe** rejection; the double-clear fires on the
*post-probe* exception paths (gate throws, third-checkpoint throws, ordinary commit failures),
which that test does not exercise; the type/message tests pass while the underflow ships.

**Verdict: should-fix** — the amendment closes the leak and opens a symmetric underflow unless the
handoff is pinned; one sentence + one regression case (post-`applyCommitOperations`-throw count
balance) closes it.

---

## CN22 [SHOULD-FIX] — The owner-as-completer handshake is one-sided: the owner's completer finally can read `teardownIntent == false` before the pool thread writes it, so NEITHER actor runs the deferred teardown; the amended invariant (2)'s "whose own teardown (guaranteed to run …)" is falsified, and the owner-never-returns case makes "guaranteed" wrong twice

**Surface attacked.** Skip-protocol (2): "On commit completion (success or failure), a `finally`
in the owner's commit path checks `teardownIntent` and, if set, runs the full `internalClose`
itself"; and the amended Draft A invariant (2): "… hands completion to the owning thread, whose
own teardown (**guaranteed to run**, since the skip does not consume the one-shot guard) carries
the release under the CN12-widened finally."

**Code grounding + interleaving enumeration.** The pool thread's skip sequence is: volatile read
`status == COMMITTING` ∧ `storageTxThreadId != me` → set `teardownIntent = true` → whitelist
bookkeeping → return. The owner's completer sequence is: finish commit → finally: volatile read
`teardownIntent`. The skip *decision read* and the *mark write* are separate acts; nothing orders
the mark write before the owner's completion read. Exhaustive cases:

1. **Mark write < completer read** (pool fast): owner sees the mark, completes teardown — intended
   path (and the CN20 double-decrement fires, see above).
2. **Completer read < mark write** (owner fast): pool read `COMMITTING` at t₁; owner finishes the
   commit and its completer finally reads `teardownIntent == false` at t₂; pool writes the mark at
   t₃ > t₂ and returns, having done mark-only bookkeeping. **No one runs `internalClose`.** The
   session stays `OPEN` with `teardownIntent` set:
   - if the borrower later calls `close()` → `DatabaseSessionEmbeddedPooled.close:36-42` →
     `internalClose(true)` runs the full teardown, `pool.release` throws the loud "The pool is
     closed" (`DatabasePoolImpl:139-146`) — bounded, loud; permit already released by the commit's
     own `tx.close()` finally (`FrontendTransactionImpl:1029-1040`);
   - if the borrower **abandons** the session, no teardown ever runs — and depending on CN20's
     resolution either the count was already decremented (a still-`OPEN`, in-use-capable session
     on a storage that reads as idle → auto-close under it, the CS13 shape created by protocol
     rather than by genuine idleness) or the count is pinned forever (storage never auto-closes).
     Neither is stated in the amendment; the accepted-residual text ("late-skip = commit genuinely
     already finished (the owner's own close then handles teardown)") quietly assumes the
     borrower's cooperative close.
3. **Both act** (mark write < completer read AND pool also… ) — the pool thread never runs the
   teardown on the skip arm by construction, so there is no double-teardown from this pair; the
   double-teardown family stays FM-A4c (CN18, accepted).

Separately, "guaranteed to run" is conditional even in case 1: the completer finally runs only if
the owner's commit path **unwinds**. An owner parked in the freezer under a transient freeze whose
`release` is leaked (the pre-existing leak class pass-14 durability §5 documents:
`freeze()`'s partial-failure path skips the unfreeze) parks forever holding the permit — the
deferred teardown never runs. That collapses to the FM-A5 accepted loud-wedge class (the mid-commit
tx pins tsMin, so the StaleTransactionMonitor reports it) — acceptable, but the invariant's word
"guaranteed" should be conditioned, or the proof lapses on a technicality.

**Fix bound.** Mirror the Dekker discipline the design already owns (V2): after writing
`teardownIntent`, the pool thread **re-reads** `status`; if it no longer reads `COMMITTING`, the
owner may have missed the mark — fall back to the normal full teardown (the `internalClose`
one-shot guard plus the `getAndSet` release claim make the both-run case exactly the accepted
FM-A4c noise, and the owner-side completer's read of the mark makes the both-see case a benign
double attempt gated by the same guard). One sentence in the protocol; alternatively, explicitly
enumerate case 2 as an accepted residual and restate invariant (2) as "… whose own teardown runs
provided the owner's commit path unwinds and either the completer observes the mark or the
borrower later closes."

**Verdict: should-fix** — bounded/loud in the cooperative case, but the amended invariant text
overclaims, and the abandoned-borrower corner is unstated.

---

## CN23 [SUGGESTION] — CS14(a)'s "floor-at-zero clamp + lockstep sanity assert" is race-sensitive as worded: the clamp must be a single atomic RMW, and a cross-thread lockstep assert can fire spuriously inside the design's own legal ordering windows

**Surface attacked.** CS14(a): "the decrement asserts/logs-and-clamps at 0 … Floor-at-zero (or
throw) on **both** the `freezeRequests` and `operatorFreezeRequests` decrements, plus a lockstep
sanity assert."

**Grounding + traces.** (i) A clamp implemented as check-then-decrement
(`if (ctr.get() > 0) ctr.decrementAndGet()`) is itself racy: two concurrent `release()` calls over
one registered freeze both pass the check → the counter still underflows — the guard must be one
atomic RMW (`accumulateAndGet(x -> max(0, x-1))` or equivalent), and its result must feed the
existing `requests == 0` cut decision in `releaseOperations` (`OperationsFreezer:95-110`); with a
clamp, two racing releases can both observe 0 and both run the cut — concurrent cutters were
verified benign in pass-14 V1, so that composition is safe, but only because the cut is; say so.
(ii) A "lockstep" assert of the form `operatorFreezeRequests <= freezeRequests` evaluated on any
thread other than the one mid-transition is spuriously violable **by design**: the pinned arm
order (`op++` before `fr++`, V1) and the pinned retract order (`fr--` before `op--`, V8) both
open windows where `op > fr` is the legal transient state. An assert that samples the two counters
non-atomically from a concurrent thread (or even from inside a racing release) can fire on a
healthy system. Pin: the lockstep assert runs only in single-threaded test harness contexts, or
tolerates a ±1 window, or samples under an external quiescence guarantee.

**Verdict: suggestion** — implementation-note precision on an already-folded guard; nothing here
weakens the CS14(b) one-sidedness acceptance, which was attacked and holds (see V-15.4).

---

## CN24 [SUGGESTION] — CN16's "set `teardownIntent` unconditionally at the top of `internalClose` (both arms)" — if "top" means before the one-shot guard, a no-op close on a non-OPEN session plants a stale mark that makes a later borrower's first engage spuriously self-abort; pin the mark AFTER the guard

**Surface attacked.** CN16 implementation note: "set `teardownIntent` unconditionally at the top
of `internalClose` (**both arms**) so 'teardown marks before it tears' is literally
unconditional."

**Trace.** `internalClose`'s one-shot guard (`:3265-3267`) returns without tearing anything when
`status != OPEN`. If the mark is written before the guard, a stale thread's late `close()` on a
pooled session that currently sits non-OPEN (or races `reuse()`, `DatabaseSessionEmbeddedPooled:45-49`:
stale closer's mark-write lands after `reuse()`'s Q-A4 flag clear while its status read still sees
the pre-`setStatus(OPEN)` value → guard-return) leaves `teardownIntent == true` on a session that
was **not** torn down and is then handed to a new borrower. The borrower's first schema write
engages the mutex; Dekker step 5 reads the stale mark → self-releases and throws "session was
closed while engaging the schema mutex" — and since nothing clears the flag mid-borrow, **every**
engage on that borrow fails: a healthy session is DDL-dead until re-pooled. This is a
false-positive channel that does not exist at HEAD (a no-op close has zero side effects today) and
is created by the pin itself. The exposure requires the same API misuse (foreign/stale `close()`)
CN16 targets — hence suggestion, matching CN16's own class — but the fix is free: write the mark
as the first statement **after** the guard in both arms. "Every teardown that tears marks first"
is exactly the invariant CN16 wants; a guard-returning call tears nothing and must not mark.

**Verdict: suggestion** — same misuse-only severity class as CN16 itself; one-line placement pin.

---

## Verified-safe claims (justifications for the no-issue verdicts on the remaining amended seams)

**V-15.1 — The skip path CAN structurally return before the CN12-widened outer finally, and the
widened finally releases on every non-skip teardown path.** `internalClose`'s real structure
(`:3264-3297`) is: one-shot guard → activity assert → body `try` (`:3270`) → outer `finally`
(`:3293-3296`). A skip check placed between the guard and the body try (or in `realClose` before
`super.close()`, `DatabaseSessionEmbeddedPooled:56-60`) returns before the outer try is entered,
so the finally — and with it the release pass — never runs on the skip arm; the whitelist's
"remove its own thread-local activation bookkeeping" substitutes for the skipped
`activeSession.remove()` (`:3295`), which the pool thread must indeed do since `realClose` called
`activateOnCurrentThread()` first. On every non-skip path the outer finally covers: the
pre-rollback throws (`closeActiveQueries` `:3271`, `localCache.shutdown` `:3272` — CN12's gap),
the `isClosed()` early return (`:3274-3277` — a `return` inside the try runs the finally), the
rollback-throw path, and the recycle arm (where the release pass no-ops via the zero
`getAndSet` harvest since the tx-close finally `FrontendTransactionImpl:1029-1040` already
released). No premature-release path was found: the only `internalClose(false)` entries are
`close()` (`:3840-3842`, owner) and `realClose` (pool, skip-aware); the foreign-misuse entry is
CN16/CN24 territory. The pool-loop per-session `try/catch (Throwable)` composes cleanly with
`pool.getAndSet(null)` (`DatabasePoolImpl:128-136` — close stays one-shot; `p.close()` and
`factory.removePool` still run after the isolated loop).

**V-15.2 — CN17's three-releaser funnel survives the claim race.** Ordering per V2 (pinned):
engage step 3 writes the fresh `Holder`, step 4 stores `engagedOrdinal = ord` (volatile), step 5
reads `teardownIntent`. The self-release now claims first: `getAndSet(engagedOrdinal, 0)`.
Enumerate against a concurrent teardown's claim (mark written at teardown top, claim in the outer
finally — mark ≪ claim in teardown program order): (i) teardown claim before engager's ordinal
store → harvests 0, no-ops; engager (mark-store < getAndSet < ordinal-store < mark-read in SO)
sees the mark, claims, harvests `ord`, releases — one release. (ii) engager's ordinal store before
teardown claim, engager also sees the mark: both race the claim; `getAndSet` is one atomic RMW, so
exactly one harvests `ord` and calls `releaseFor(session, ord)`; the loser no-ops — one release.
(iii) engager misses the mark (teardown mark-write after engager's mark-read): teardown's claim
harvests `ord` and releases; engager proceeds — but its session teardown already started, which is
FM-A3's teardown-heals arm, unchanged. In all branches the per-engage `Holder` CAS remains an
untouched second belt. No new interleaving defect.

**V-15.3 — The probe hoist has complete coverage of `commitSchemaCarry`.** The pin site `:2525`
and the branch `:2531-2533` live in `commit(tx, allocated)` (`:2505`), whose only entries are
`commit(clientTx)` (`:2476`) and `commitPreAllocated` (`:2487`) — both funnel through the same
body; `commitSchemaCarry` is private with exactly one call site (`:2532`);
`applyCommitOperations` has exactly two call sites, both inside `commit` (`:2537`, `:3187`). A
probe placed after the `schemaCarry` computation and before `:2525` is passed by every path that
can reach `commitSchemaCarry`. The freeze-engages-after-probe residual is covered by checkpoints
2–4 by construction. No bypass exists.

**V-15.4 — The volatile `status`/`storageTxThreadId` fix leaves exactly the accepted residuals.**
Both fields are written only on tx-boundary paths (`:220`, `:726`, `:1024`,
`rollbackInternal:416`); making them volatile puts the pool thread's reads into synchronization
order, killing the reused-object stale-`COMMITTING` read (CN13's stranding shape). The two reads
are still non-atomic as a pair, but every torn combination resolves to a decision whose both
outcomes the ruling accepts: a skip when the owner is (or was a moment ago) mid-commit on some tx
of this session defers to a live completer path; a non-skip when the commit just ended is today's
teardown. No third outcome exists because `storageTxThreadId` is non-zero only between
`beginInternal` and tx close (`:220`, `:1024`).

**V-15.5 — The third checkpoint adds no deadlock edge (starvation aside).** Its two outcomes are
throw (unwinds through the `:3196-3199` SchemaShared/IM finallys with the write lock never
acquired) and retry. During an attempt's drain, `freeze()`/`synch()` are blocked at their
`stateLock.readLock` (`:5510`) like any reader — they have registered nothing yet, so no
freeze-vs-lock cycle can form (the ordering stays readLock→freeze, never freeze→stateLock, as V6
proved for the base design); a transient freeze already registered before the loop keeps its
parked readers, which is the bounded-transient case the design accepts (the loop spins for the
transient's duration with `operatorFreezeRequests == 0` — equivalent blocking to HEAD's plain
lock). The CS14(b) retract-window spurious throw applies to the new checkpoint identically
(one-sided guarantee, accepted); no new window kind is introduced.

## Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | The timed-acquire loop starves the armed writer under sustained read traffic (the mandated pointed concern) | `exclusiveTryLockNanos` semantics: queueing, residual intent, drain budget | **Confirmed** → CN19 (drain-timeout releases the bit; no admission persists between attempts; no constant interval satisfies both requirements) |
| H2 | A timed-out attempt leaves residual writer-intent that blocks readers between attempts | `:611` release path; `readersStateArrayRef` residue | Refuted — full release, cache-array residue is invalidated by `addState` (`:305-307`); the reader-blocking comes from the *held* bit during attempts, folded into CN19 |
| H3 | The skip path cannot structurally return before the widened outer finally | `internalClose` real structure | Refuted (V-15.1) — guard→try gap exists; whitelist substitutes the TL removal |
| H4 | Skip whitelist + owner-as-completer double-decrement the session count | `AbstractStorage.close(session)`, count sites, auto-close | **Confirmed** → CN20 (deterministic; StorageException at `:633-637`; premature idle-zero) |
| H5 | Owner-as-completer misses the mark (nobody completes) | Ordering of pool mark-write vs owner completer-read | **Confirmed** → CN22 (one-sided handshake; "guaranteed to run" overclaims; owner-never-returns corner) |
| H6 | CS10 defensive clear double-clears against the `:3142` finally | `MetadataDefault` underflow semantics; `applyCommitOperations` try structure | **Confirmed** → CN21 (count −1 → mid-pin rebuild + forceClear poison; handoff unspecified) |
| H7 | CN17 self-release claim racing a teardown claim loses a release or doubles one | getAndSet/CAS enumeration under V2 ordering | Refuted (V-15.2) |
| H8 | A commit entry path reaches `commitSchemaCarry` without the hoisted probe | All callers of `commit`/`commitSchemaCarry`/`applyCommitOperations` | Refuted (V-15.3) |
| H9 | Volatile skip-detection still permits a harmful torn read | Field write sites; pair-read combinations | Refuted (V-15.4) — residuals are exactly the accepted TOCTOU |
| H10 | Third checkpoint introduces a freeze/lock deadlock cycle | freeze()/synch() registration order vs the loop's held state | Refuted (V-15.5) |
| H11 | CS14 clamp/assert as worded is itself racy | Clamp shape; legal `op>fr` windows on both arm and retract sides | **Confirmed** (bounded) → CN23 |
| H12 | CN16's unconditional pre-guard mark creates a false-positive Dekker channel | Guard-return paths; reuse() ordering | **Confirmed** (misuse-only) → CN24 |
