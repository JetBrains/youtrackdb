# YTDB-382 — Adversarial pass 15: durability, scoped to pass-14 amendments (2026-07-21)

Verdict: 4 new findings (1 blocker, 2 should-fix, 1 suggestion). No WAL/atomic-operation/on-disk
inconsistency exists at any crash point inside the amended windows — every process-death outcome
still reduces to WAL atomic-unit completeness, and the third checkpoint's throw site provably
touches no durable or table-visible state. The blocker is in the CS10 amendment's **defensive
paired clear**: the mechanism the amendment itself names (a try/finally around the pin region)
cannot express the span the amendment requires ("until `applyCommitOperations`' own finally takes
over"), and the literal implementation **double-clears the snapshot pin on every failed commit**
(and, in the plain-finally variant, on every successful one), driving
`MetadataDefault.immutableCount` negative and poisoning the session in the same class CS10 was
reversed to prevent — now triggered by the *common* failure path instead of an operator freeze.
The should-fixes are exception-path holes in the Q-A2 owner-as-completer protocol: the owner's
finally-run `internalClose` can mask the commit outcome in both directions (including reporting a
durable commit as failed → client retry → duplicate apply), and the skip whitelist contradicts
itself on the session-count decrement (its DO list requires what its DON'T list forbids), with a
double-decrement `StorageException` feeding the masking hole. The suggestion records what the
CS14 underflow guard still cannot see (identity-less counters cannot detect quiesce theft) and
pins the clamp semantics that actually deliver its promise.

## Surface attacked

ONLY the section "## Amendments — adversarial pass 14 triage (2026-07-21)" of
`docs/adr/transactional-schema/_workflow/track-7-design-drafts.md` at HEAD `e2605c8ba3`, and its
splice points into the base design: (1) the CN10 third gate checkpoint (timed
`stateLock.writeLock` acquire loop in `commitSchemaCarry`); (2) the hoisted Q-B3 probe + the
defensive paired clear spanning the pin→`applyCommitOperations`-finally region; (3) the Q-A2 skip
protocol (whitelist, owner-as-completer finally, volatile `status`/`storageTxThreadId`); (4) the
widened FM-A2 release finally at `internalClose`'s outer finally + pool-close per-session
Throwable isolation; (5) the CS14 underflow guard; (6) the amended invariants and risk list.
Round-1 verdicts (adversarial-pass14-durability.md CS10–CS14, adversarial-pass14-concurrency.md
CN10–CN18, V1–V8) were read for context and are **not re-litigated**; they are used here only as
premises where the amendments cite them. Charter: persistence ordering, WAL/atomic-op state,
recovery invariants, failure/exception-path state consistency.

## Code grounding

Read at HEAD `e2605c8ba3`:

- `MetadataDefault.java` — `immutableCount:50`, `makeThreadLocalSchemaSnapshot:78-85` (rebuild
  only on the 0→1 transition, `:79-83`), `clearThreadLocalSchemaSnapshot:88-93` (unconditional
  decrement `:89`, null-out at exactly 0 `:90-92`), `forceClearThreadLocalSchemaSnapshot:95-103`
  (throws whenever count ≠ 0, incl. negative), `rebuildThreadLocalSchemaSnapshot:117-124` (throws
  when count == 0), `getImmutableSchemaSnapshot:127-134` (null-fallback builds a fresh snapshot
  per call).
- `AbstractStorage.java` — `commit:2506-2563` (pin `:2525`, schema-carry branch `:2530-2533`,
  data branch read-lock `:2535-2542`, catch → `logAndPrepareForRethrow` `:2556-2560`);
  `applyCommitOperations:2741-3145` (`checkOpennessAndMigration:2751`, `makeStorageDirty:2753`,
  `startTxCommit:2766`, apply-failure catch `:3012`, `rollback(error, …):3033`,
  `endTxCommit:3077`, `publishReconciledIndexes:3100`, promotion load `:3120`, outermost finally
  `ensureThatComponentsUnlocked:3141` + `clearThreadLocalSchemaSnapshot:3142`);
  `commitSchemaCarry:3159-3201` (schema write lock `:3175`, IM lock `:3177`, `stateLock.writeLock`
  `:3179` — the third-checkpoint splice point, `enterCommitWindow:3185`, unlock/release finallys
  `:3190-3200`); `close(session):630-640` (sole `sessionCount` decrement; throws
  `StorageException` on a negative count `:633-638`); `shutdown:705-716`; `synch:5317` /
  `doSynch:5347-5385` (release in finally `:5381`); `freeze:5508-5560`; `release:5563-5580`
  (`unfreezeWriteOperations(-1)` `:5571`); `endTxCommit:6256`; `startStorageTx:6260`;
  `resetTsMin:6313-6338`; `startTxCommit:6340-6342`;
  `logAndPrepareForRethrow(RuntimeException):7624-7642` (no `setInError` for the
  RuntimeException arm; `HighLevelException` not even error-logged).
- `OperationsFreezer.java` — `startOperation:30-57`, `freezeOperations:72-86`,
  `releaseOperations:88-112` (`decrementAndGet` `:95`, `requests == 0` cut+unpark `:97-110`),
  `throwFreezeExceptionIfNeeded:114-118`.
- `AtomicOperationsManager.java` — `startAtomicOperation:102-125` (table **snapshot read only**),
  `startToApplyOperations:127-149` (the only table registration, `:143`, under `segmentLock`),
  `calculateInsideAtomicOperation:151+`, `endOperation` wiring `:463`.
- `DatabaseSessionEmbedded.java` — `internalClose:3264-3296` (one-shot guard `:3265-3267`,
  `closeActiveQueries:3271`, `localCache.shutdown:3272`, early return `:3274-3277`, guarded
  rollback `:3279-3283`, `callOnCloseListeners:3285`, `status = CLOSED:3287`,
  `sharedContext = null` + `storage.close(this)` `:3288-3291`, outer finally `:3293-3296`);
  `executeReadRecord:2139-2306` (nested pin `:2144`, paired clear `:2303`);
  `commitImpl:4388-4459`; `rollback():4496-4505` (guarded on `currentTx.isActive()`);
  `checkOpenness:4584-4588` (reads **session** `status:268`, not the tx status);
  `callOnCloseListeners:3388-3393` (unguarded listener loop);
  `closeActiveQueries:4674-4678`; `ensureTxSchemaState:3477-3521`;
  `releaseMetadataWriteMutexForTx:3630-3636`; `forceRebuildTxSchemaSnapshot:3559-3565`.
- `FrontendTransactionImpl.java` — `status:84`, `storageTxThreadId:158`,
  `rollbackInternal:403-465` (ROLLBACKING do-nothing arm `:409-411`, BEGUN/COMMITTING arm
  `:415-433`, counter/close tail `:442-463`), `doCommit:690-766` (COMMITTING `:726`,
  `internalCommit:728`, after-commit callbacks `:740-747`, catch → `rollbackInternal:749`,
  `close():757`, COMPLETED `:758`), `close():1006-1041` (deactivate/resetTsMin `:1011-1025`,
  mutex-release finally `:1029-1040`), `clear():1043+`, `isActive:1507-1511`.
- `DatabaseSessionEmbeddedPooled.java` (whole file — `close:36-43` `isClosed` guard +
  `pool.release`, `reuse:45-49`, `realClose:56-60`), `DatabasePoolImpl.java` (whole file —
  `close:128-137`), `EntityImpl.getGlobalPropertyById:4158-4179` (pre-existing unpaired pin
  `:4174`), `ScalableRWLock.exclusiveTryLockNanos:586-618` (acquires internal write mode during
  the attempt, releases it on timeout `:611-613`, throws `InterruptedException`),
  `ModificationOperationProhibitedException` (implements `HighLevelException`).

## Method: decision criteria, premises, hypothesis log

A finding is a **blocker** iff a traced interleaving or exception path permitted by the amendment
text (including any legal implementation reading of an ambiguous pin) produces silent wrong
durable bytes, a poisoned session/storage, or a masked durable outcome — in the design's own
worst classes. A **should-fix** iff the amendment text taken literally permits an implementation
that defeats the amendment's own stated purpose, or an exception path leaves state that later
yields a masked-success/wrong-retry-signal outcome, but the failure is bounded or needs an
additional plausible fault. A **suggestion** iff the residual is pre-existing at HEAD and merely
unrecorded, or is an implementation-pin needed for the amendment to deliver its promise.

Global premises (all [verified] against the cited code):

- P1. `immutableCount` is a per-session plain int with no reset outside the
  make/clear/force-clear trio; the immutable snapshot rebuilds **only** on the 0→1 transition
  (`MetadataDefault:79-83`); `clearThreadLocalSchemaSnapshot` decrements unconditionally and
  null-outs only at exactly 0 (`:89-92`); `forceClearThreadLocalSchemaSnapshot` throws for any
  non-zero count including negatives (`:96-102`); `rebuildThreadLocalSchemaSnapshot` throws at
  count 0 (`:118-123`). Pooled sessions recycle with `MetadataDefault` intact (round-1 P6).
- P2. `applyCommitOperations` is called from exactly two sites, both inside `commit()`
  (`:2537` data branch, `:3187` via `commitSchemaCarry`); its outermost finally
  (`:3141-3142`) runs the paired clear on **every** entry — success and failure alike — before
  the throwable continues unwinding into `commit()`'s frame.
- P3. Nothing durable or table-visible exists for a commit before `startToApplyOperations`
  registers it (`AtomicOperationsManager:143` is the only table write; `startAtomicOperation`
  only snapshots the table; WAL unit records are emitted from `commitChanges`). `startTxCommit`
  (`:2766`) is inside `applyCommitOperations`, which for the schema-carry branch is entered only
  after `stateLock.writeLock` is held (`commitSchemaCarry:3179→3187`).
- P4. The gate/probe/checkpoint exception type `ModificationOperationProhibitedException` is a
  `HighLevelException`, so `commit()`'s catch (`logAndPrepareForRethrow(RuntimeException)`,
  `:7624-7642`) neither error-logs it nor calls `setInError`.
- P5. `AbstractStorage.close(session)` (`:630-640`) is the sole `sessionCount` decrement and
  throws `StorageException` when the count goes negative; `internalClose(false)` reaches it at
  `:3290`; `internalClose(true)` (recycle) does not.
- P6. `internalClose`'s unguarded throw points are `closeActiveQueries()` (`:3271`),
  `localCache.shutdown()` (`:3272`), `callOnCloseListeners()` (`:3285` — unguarded listener
  loop, `:3388-3393`), and `storage.close(this)` (`:3290`); only the rollback is wrapped in
  `catch (Exception)` (`:3279-3283`). This is the same `[inferred]`-reachability class the design
  already treats as real (Q-A1, CN12).
- P7. `session.rollback()` no-ops when the tx is not active (`:4500-4504`); `isActive()` is false
  for INVALID/COMPLETED/ROLLED_BACK and **true** for ROLLBACKING (`FrontendTransactionImpl:
  1507-1511`), whose `rollbackInternal` arm is do-nothing-then-close (`:409-411`, `:442-448`).

Hypotheses raised and outcomes:

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | The defensive paired clear can fire after `:3142` already cleared (double-clear) | **Confirmed → CS15** |
| H2 | The defensive clear can kill a snapshot an outer pin still needs | Confirmed as a sub-case of CS15 (nested/outer pins decremented); no independent path found |
| H3 | Third-checkpoint throw leaks locks / WAL / table / dirty state | Refuted (P3, P4; "failed attacks" §1) |
| H4 | Third-checkpoint throw escapes the paired clear | Refuted structurally (throw is inside the guard span) — but the two amendment sections name **different span endpoints**; folded into CS15 |
| H5 | Owner-as-completer `internalClose` corrupts the success path's result/callbacks | Refuted for the pinned "on completion" placement; **placement is unpinned** — wrong-but-legal placement fires rollback listeners on a durable commit; folded into CS16 pin |
| H6 | Owner-as-completer finally masks the original commit outcome | **Confirmed → CS16** (both directions; masked durable success is the sharp arm) |
| H7 | Skip whitelist is internally consistent about the session count | **Refuted → CS17** (DO list requires `sessionCount` decrement; DON'T list forbids its only mechanism; owner's full `internalClose` decrements again) |
| H8 | Underflow clamp breaks a legitimate release pairing | Refuted (clamp never fires on legal pairings; it **repairs** the counter for subsequent freezes vs HEAD's −1) |
| H9 | Clamp masks a real pairing bug with a different victim | **Confirmed (pre-existing, unrecorded) → CS18** (quiesce theft under a concurrent freeze; plus corrective-store race and throw-in-finally pins) |
| H10 | Volatile `status`/`storageTxThreadId` breaks a staleness-dependent teardown/recovery path | Refuted ("failed attacks" §4) |
| H11 | Widened outer-finally release / early-return routing has a durability edge | Refuted ("failed attacks" §3) |
| H12 | Pool-close per-session `catch (Throwable)` hides a durability hazard | Refuted (discard path; permits released; durable state untouched) |

---

## CS15 — blocker — the "defensive paired clear" as specified double-clears against `applyCommitOperations`' `:3142` finally: every failed commit drives `immutableCount` negative and poisons the session — the fix reintroduces CS10's failure class on the *common* path

**The amended text under attack.** CS10 amendment: *"the pin→`applyCommitOperations`-finally
region is wrapped so that any throw escaping between the `:2525` pin and the established `:3142`
clear cannot leak `immutableCount` — a try/finally (or equivalent guard) spanning from
immediately after the pin through the point where `applyCommitOperations`' own finally takes
over, clearing the snapshot on the escape."* And the CN10 section's cross-reference: *"the CS10
try/finally wrap must span the entire pin→**lock-acquired** region."*

**Premises.**

1. P2: the `:3142` clear runs on **every** entry into `applyCommitOperations`, including every
   failure — the in-apply catch (`:3012`) rethrows, the rollback arm (`:3033`) runs, and the
   outermost finally (`:3141-3142`) clears the pin **before** the throwable continues unwinding
   through `commitSchemaCarry`'s finallys (or the data branch's read-lock finally) into
   `commit()`'s frame — i.e., into the exact region where the new guard lives.
2. The span the amendment requires is **dynamic** ("through the point where
   `applyCommitOperations`' own finally takes over" = the callee's frame entry), but the
   mechanism it names — "a try/finally" in `commit()` — is **lexical**: it necessarily spans the
   whole `commitSchemaCarry(...)` / `applyCommitOperations(...)` call, including every unwind
   that passes through it **after** `:3142` already cleared. No lexical try/finally (or
   catch-rethrow) at the `commit()` frame can distinguish "escaped before `:3142`" from "escaped
   after `:3142`" — the throwable carries no such marker.
3. P1: a second clear against one pin takes `immutableCount` from 1 → 0 → **−1**. From −1 the
   count never recovers: `make` skips the rebuild for any non-zero entry count (`:79`), so
   subsequent paired make/clear cycles oscillate −1↔0 with `immutableSchema` pinned null.
4. Consequences of a negative/zero-idle count on a pooled, recycled session [all traced]:
   - `rebuildThreadLocalSchemaSnapshot` **throws at count 0** (`:118-123`). A later schema-carry
     commit that created a class enters `applyCommitOperations` with the pin at −1→0, reaches
     the mandatory in-window rebuild (`AbstractStorage:2888`), and **fails every time** — schema
     DDL with class creation is permanently broken on that session.
   - `forceClearThreadLocalSchemaSnapshot` throws for count = −1 (`:96-102`), so
     `forceRebuildTxSchemaSnapshot` (`DatabaseSessionEmbedded:3560`) — the first schema write of
     any later transaction — throws: **the CS10 poisoning verbatim**, now minted by the fix.
   - With `immutableSchema` pinned null, every read falls into the
     `getImmutableSchemaSnapshot` null-fallback (`:127-134`) and builds a **fresh snapshot per
     call** — silent, unbounded overhead, and mid-operation snapshot instability where code
     assumes one pinned view.
5. The guard wraps the pin at `:2525`, which covers **both** branches — so the trigger is any
   commit failure that unwinds from inside `applyCommitOperations`: a
   `ConcurrentModificationException` on a contended data commit, a validation failure in
   `commitEntry`, an index-commit failure — the routine failure surface of the database, not the
   operator-freeze corner CS10 needed.
6. Worse variant: the amendment's literal words "a try/finally … clearing the snapshot" — a
   plain finally clears **unconditionally**, i.e., also on the success path where `:3142` already
   cleared: then **every commit**, successful or not, decrements one extra. (The trailing "on
   the escape" suggests exception-only, which needs a catch-rethrow or Throwable-flag — also
   unspecified; either way premise 2 applies to the failure path.)

**Counterexample (minimal, single interleaving-free trace).** Pooled session S runs a plain data
commit that loses a version check: `commit()` pins (`immutableCount` 0→1, `:2525`) → data branch
→ `applyCommitOperations` → `commitEntry` throws `ConcurrentModificationException` → in-apply
catch rethrows, rollback arm runs (WAL unit reverted — durably clean), outermost finally clears
(1→0, `:3142`) → exception unwinds through the read-lock finally into `commit()`'s frame → **the
defensive guard clears again (0→−1)** → exception propagates to the caller (correct NeedRetry
signal — the caller retries and succeeds; nothing looks wrong). S is now at −1 forever: premise 4
consequences follow — the *retry's* schema state is fine, but S's next DDL throws
`IllegalStateException("snapshot usage count is not zero: -1")` and any later class-creating
schema commit fails at the in-window rebuild. Via pool recycling this lands on arbitrary future
borrowers. Nested-pin variant: if S carries an outer pin (`EntityImpl.getGlobalPropertyById`'s
pre-existing unpaired pin at `:4174`, or any future caller pinning around a commit), the
double-clear steals the **outer** pin's count and kills a snapshot the outer frame still owns.

**Why the amendment's own wording doesn't save it.** The two amendment sections name
**different, inconsistent span endpoints**: the CN10 section says the wrap spans "the entire
pin→lock-acquired region" (disarm at write-lock acquisition — leaves the `:2751/:2753`
pre-`startTxCommit` throws of `applyCommitOperations` uncovered by neither guard nor… actually
covered by `:3142`, but leaves a documented contradiction), while the CS10 section says "through
the point where `applyCommitOperations`' own finally takes over" (disarm at callee entry). The
design never specifies the disarm mechanism, and its only named mechanism cannot implement
either endpoint. Round-1's CS12 established the house rule that a spec whose natural legal
reading violates the design's own invariant is a defect; here the *named* reading is the
violating one and the consequence is silent session poisoning on the dominant failure path —
blocker.

**Alternative-hypothesis check.** Could `:3142` be unreachable on failure paths, making the
double-clear moot? No — the outermost try/finally of `applyCommitOperations` encloses the entire
body including the catch and the error-arm finally; only a throw from
`ensureThatComponentsUnlocked` (`:3141`) itself could skip the `:3142` clear, and that is the one
residual shape a disarm-at-entry guard would *also* miss (see fix bound). Could the guard be
intended as idempotent (clear-only-if-armed)? Nothing in the text says so, and "armed" is exactly
the unspecified disarm flag.

**Fix bound (design amendment, small — pin ONE of these).**
(a) **Single-owner clear** (preferred): move the clear out of `applyCommitOperations` entirely —
delete the `:3142` clear and place one clear in `commit()`'s own finally paired lexically with
the `:2525` pin (P2: `applyCommitOperations` has no other caller, so no other path loses the
clear). This also covers the `ensureThatComponentsUnlocked`-throws shape and removes the
double-clear possibility by construction. Verify the pin's slightly longer hold (through the
lock-release finallys) is inert — it is: the pin is session-local and promotion runs before the
`:3141` finally.
(b) **Armed-flag guard**: a boolean armed immediately after the pin and disarmed as the *first
statement inside* `applyCommitOperations`' outer try (or passed-in holder), with the `commit()`
finally clearing only when still armed.
Either way, extend the amended Q-B3 test matrix: the count-balance assertion must cover a
**failed data commit** and a **failed schema-carry commit** (in-apply throw), not only the probe
rejection — the current amended matrix tests exactly the path that was already safe.

---

## CS16 — should-fix — the owner-as-completer finally can mask the commit outcome: a throw from the owner-run `internalClose` converts a durable commit into an apparent failure (retry → duplicate apply) or replaces the real failure's retry signal; the completer's placement and throw-isolation are unpinned

**The amended text under attack.** Skip protocol (2): *"On commit completion (success or
failure), a `finally` in the owner's commit path checks `teardownIntent` and, if set, runs the
full `internalClose` itself on the owning thread. Its `tx.close()` finally releases the mutex
through the normal path."* The amendment pins throw-isolation for the **pool-close loop**
(per-session `catch (Throwable)`) but pins **nothing** for the owner-run `internalClose`, which
now executes inside the commit call's own finally — where a throw replaces the in-flight
outcome.

**Premises.**

1. P6: `internalClose` has four unguarded throw points (`closeActiveQueries`,
   `localCache.shutdown`, `callOnCloseListeners`, `storage.close(this)`); only the rollback is
   swallowed. The design already treats this throw class as real — the CN12 widening exists
   because of it, and the amendment itself says the widened finally "also covers the owner-run
   path" (i.e., it anticipates the owner-run teardown throwing).
2. Java finally semantics: a throwable raised in a finally **replaces** the in-flight
   result/throwable of the guarded region.
3. CS17's double-decrement reading supplies a concrete, deterministic thrower on this exact
   path: the owner's `internalClose(false)` reaches `storage.close(this)` after the pool thread
   already decremented → `sessionCount` negative → `StorageException` (`:633-638`) thrown from
   inside the completer finally.

**Counterexample — masked durable success (the sharp direction).** Pool closes while owner O's
data commit is in `internalCommit`; pool thread skips (whitelist) and sets `teardownIntent`. O's
commit completes durably (`endTxCommit`, `:3077`); after-commit callbacks run; `tx.close()`
releases the mutex; O's completer finally sees the mark and runs `internalClose(false)` →
`callOnCloseListeners()` throws (any registered lifecycle listener misbehaving on a
mid-shutdown session — or premise 3's deterministic `StorageException`). The throw propagates
out of the commit call: **the caller is told the commit failed although it is durable**. A
standard retry loop re-runs the business transaction on a fresh session → **duplicate durable
apply** — silent wrong data, the design's own worst class. On disk everything is "consistent";
the wrongness is minted by the retry the masked signal invites.

**Counterexample — masked failure type (the lesser direction).** O's commit fails with
`ConcurrentModificationException` (a `NeedRetryException`); doCommit's catch rolls back and
rethrows; the completer finally's `internalClose` throws (`localCache.shutdown` on the sick
session — the FM-A2 reachability class). The caller receives the teardown exception instead of
the retryable one → retry logic that keys on `NeedRetryException` gives up (or an error handler
misclassifies). The permit itself is safe (the CN12-widened outer finally releases it before the
throw escapes `internalClose` — verified: the release pass sits in the outer finally and never
throws), so this is purely an exception-identity corruption — but it lands on exactly the path
Q-A2 exists to keep clean.

**Placement is unpinned (folded pin).** "A finally in the owner's commit path" admits a legal
placement wrapping `doCommit`'s try/catch **before** the trailing `close():757`. There the
completer's `internalClose` → `rollback()` finds the tx still COMMITTING (`isActive` true, P7) →
`rollbackInternal`'s BEGUN/COMMITTING arm fires `beforeRollbackOperations`/
`afterRollbackOperations` and clears the tx **after a durable commit** — rollback listeners
invoked for a committed transaction (application-level inconsistency), though the storage state
stays correct. Placement after the tx's own `close()` (at or above the doCommit frame boundary)
makes `rollback()` a no-op (P7: COMPLETED/INVALID not active) — verified clean: one-shot guard
still passes (pool thread never flipped session status, per the whitelist), the pooled `close()`
later no-ops on `isClosed()` (`DatabaseSessionEmbeddedPooled:37-39`), and the returned result map
has no session dependency.

**Fix bound.** Two pins in the skip-protocol text: (1) the completer finally runs **strictly
after the transaction's own `close()`** (commit and rollback paths both) — "completion" defined
as the tx-close boundary, not the storage-commit boundary; (2) the completer's `internalClose`
is **throw-isolated**: wrapped in `catch (Throwable)` that logs (and optionally
`addSuppressed`s onto the in-flight failure) and never propagates into the commit result path —
the exact discipline the amendment already applied to the pool-close loop, applied to the second
new caller of the same body. Plus a test: durable commit + `teardownIntent` set + a throwing
close listener → the commit call returns success (or the original failure), never the teardown
throwable.

---

## CS17 — should-fix — the skip whitelist contradicts itself on the session count: its DO list ("decrement the session count") requires the call its DON'T list forbids (`storage.close(this)`), and the two legal readings yield either a double decrement (negative-count `StorageException` inside the completer / premature auto-close trigger) or an unreachable decrement that invalidates the recorded CS13 premise

**The amended text under attack.** Skip protocol (1): the pool thread performs ONLY *"set
`teardownIntent` = true; **decrement the session count** / remove its own thread-local activation
bookkeeping that is private to the pool thread; log"* — and explicitly does NOT *"set
`status = CLOSED`, or null `sharedContext`, **or call `storage.close(this)`**, or run
`callOnCloseListeners()`"*. Skip protocol (2): the owner later runs *"the **full**
`internalClose`."*

**Premises.**

1. P5: `AbstractStorage.close(session)` (`:630-640`) is the **only** mechanism that decrements
   `sessionCount`, and it throws `StorageException` when the count goes negative. There is no
   other "session count" the pool thread owns: the pool's own resource accounting dies with
   `pool.getAndSet(null)` + `p.close()`, and `activeSession` is a ThreadLocal (the "private
   bookkeeping" clause covers it separately).
2. The owner's full `internalClose(false)` reaches `storage.close(this)` at `:3290`
   unconditionally on the non-recycle arm.
3. `checkAndCloseStorages`/`forceDatabaseClose` key on `getSessionsCount() == 0`
   (round-1 CS13 trace, re-confirmed against `:655-657`).

**Reading A — the pool thread decrements (via `storage.close(this)`, violating its own DON'T
list, or via a new bespoke decrement).** Then the owner's later full `internalClose` decrements
**again** (premise 2): the storage's count is off by one low for every skipped session. If the
skipped session is the last, the count goes **negative** and `close(session)` **throws from
inside the owner's completer finally** — feeding CS16's masking hole with a deterministic
thrower on the success path of a durable commit. If other sessions are open, the skew makes
`sessionCount` hit 0 while a non-zombie session is still live → the auto-close timer's
`forceDatabaseClose` runs `SharedContext.close` under a session the CS13 acceptance never
covered (its recorded scope is "the kept-alive zombie", whose durable writes `stateLock`
serializes; an arbitrary live session's *next* commit now races a closed SharedContext with no
such record).

**Reading B — the pool thread does not decrement (the DON'T list wins; "decrement the session
count" is dead text).** Then `sessionCount` stays ≥ 1 until the zombie's completer runs — which
is actually the **safe** choreography (the auto-close timer cannot fire mid-commit for this
path, *narrowing* CS13's window) — but the amendment's own CS13 note ("after `DatabasePoolImpl.
close` realCloses all sessions and `sessionCount` hits 0, the auto-close timer … while a zombie
may be mid-`publishReconciledIndexes`") then describes a state this path can no longer produce,
and the risk-list entry 4 inherits the stale premise.

**Verdict.** Not a durable-state defect under either reading (crash outcomes stay WAL-governed;
`storage.shutdown()` still serializes on `stateLock.writeLock`), but the amendment ships a
self-contradictory protocol whose worse reading manufactures the CS16 masking throw and
un-scopes an accepted risk. **Fix bound:** strike "decrement the session count" from the
whitelist (Reading B) and state that the count is decremented exactly once, by the owner's
completer via the full `internalClose`; update risk-list entry 4 / the CS13 line to say the skip
path *defers* the count-to-zero transition until the zombie completes (the CS13 race remains
reachable only via non-skip choreographies). If Reading A is wanted for some reason, the DON'T
list must carve out the decrement and the owner's `internalClose` must be taught to skip its
second `storage.close(this)` — a larger diff for no benefit.

---

## CS18 — suggestion — the CS14 underflow guard cannot see the double-release-under-concurrent-freeze shape (quiesce theft → corrupt backup — pre-existing, absent from the amended risk list), and two clamp-semantics pins are needed for the guard to deliver even its own promise

**The amended text under attack.** CS14(a): *"the decrement asserts/logs-and-clamps at 0 …
Floor-at-zero (or throw) on **both** the `freezeRequests` and `operatorFreezeRequests`
decrements, plus a lockstep sanity assert, plus a test for the double-`release()` shape."*

**What the guard genuinely closes [verified].** Double `release()` with no other freeze active:
first release retracts legitimately (fr 1→0 cut+unpark, op 1→0); the second decrement clamps at
0 with a log on both counters — the "gate silently disarmed" outage (op = −1 → next operator
freeze arms at 0) is closed, and vs HEAD the clamp additionally **repairs** `freezeRequests`
(HEAD's −1 makes the *next* freeze register at 0 → quiesce void; with the floor the next freeze
registers at 1 and works). The guard is directionally right.

**Residual 1 — identity-less counters cannot detect theft (unrecorded).** Operator freeze F
active (fr = 1, op = 1) **plus** a transient T active (fr = 2 — e.g. `freeze()`'s own nested
`doSynch`, or a concurrent backup). Double `release()`: first — fr 2→1, op 1→0 (legal-looking);
second — fr 1→**0**, op clamps at 0. **No floor fires** (no value goes negative), the lockstep
assert sees a consistent (0, 0), yet the second decrement **stole T's contribution**: the
`requests == 0` cut unparks all waiters and new entrants stream past the freezer **while T's
frozen body is still running** — writes admitted mid-`doSynch` flush or mid-WAL-backup section →
**corrupt backup / torn synch**, a durability-relevant silent outcome. This shape exists
byte-for-byte at HEAD (the counters have never carried per-freeze identity; only the throw-mode
suppliers have ids), so the amendment neither causes nor worsens it — but the amended risk list
records only the retract-window spurious throw (CS14b) and the guard's test pins only the
no-other-freeze shape, so the record now implies more protection than the guard provides. Fix
bound: one risk-list line ("the underflow guard detects only decrements that would go negative;
a double release concurrent with another registered freeze silently retracts the survivor's
quiesce — pre-existing, identity-less counters") — or, if ever revisited, a per-id release
ledger (the `freezeIdGen` id already exists; `release()`'s `-1` sentinel is the only anonymous
caller).

**Residual 2 — clamp mechanics must be CAS-floor, not decrement-then-correct.** The natural
reading of "logs-and-clamps at 0" — `if (counter.decrementAndGet() < 0) counter.set(0)` — has a
lost-update race: buggy double-release decrements to −1; a **legitimate** freeze increments
−1→0; the corrective `set(0)` then wipes the freeze's registration → that freeze's quiesce is
void (same end state as HEAD's negative counter, so not new-worse — but the guard's promise "a
double release can never … disarm" is not delivered). Pin: the floor must be a CAS loop
(decrement-only-if-positive), under which the same trace leaves the counter at 1 and the freeze
intact — the guard then actually fixes the HEAD hazard.

**Residual 3 — "(or throw)" must be forbidden on release paths reached from finallys.** The
shared `freezeRequests` decrement is reached from `doSynch`'s finally (`:5381`) and the backup
transients' finallys; a throw-mode guard firing there **replaces the frozen body's primary
exception** (e.g., a `flushAllData` failure that operators must see) with a counter-bookkeeping
error — the same finally-masking class as CS16. Pin: log-and-clamp on all decrements reachable
from release finallys; the throw option, if used at all, only on the operator `release()` API
surface.

---

## Failure-point enumeration along the amended windows

**Window 1 — hoisted probe + pin→apply region (`commit():2519-2542` + `commitSchemaCarry`
prologue).**

| Point | State on throw/crash | Verdict |
|---|---|---|
| Hoisted probe throws (pre-`:2525`) | zero pins, zero locks, zero WAL/table state; unwind → doCommit catch → rollbackInternal → tx.close (mutex released, tsMin reset) | clean — the hoist does what CS10 asked |
| Throw between pin and `commitSchemaCarry` entry (`:2526-2531` — no realistic thrower today) | pin held; guard clears once (`:3142` never ran) | clean **iff** CS15's disarm is fixed |
| Schema/IM lock acquisitions (`:3175/:3177` — block, don't throw) | — | n/a |
| **Third checkpoint throws** (`:3179` splice) | SchemaShared + IM held, `stateLock.writeLock` NOT held (`exclusiveTryLockNanos` releases its internal write mode on timeout, `:611-613`); pin held → guard clears once; no table entry (P3), no WAL unit, no `makeStorageDirty` yet (`:2753` unreached), `startTxCommit:2766` unreached; unwind through `:3196/:3199` finallys releases both metadata locks; P4: no `setInError`, not even an error log | clean (see "failed attacks" §1) |
| `InterruptedException` from `exclusiveTryLockNanos` | same unwind, checked exception needs wrapping (unpinned but both plausible handlings unwind identically) | note-level, no defect |
| `enterCommitWindow`→apply entry gap | covered by whichever CS15 endpoint wins — the two sections disagree (CS15) | CS15 |
| Any in-apply failure | `:3142` clears → **guard clears again** | **CS15** |
| `ensureThatComponentsUnlocked` throws before `:3142` | clear skipped; disarm-at-entry guard also misses | CS15 fix bound (favors single-owner) |
| Process crash anywhere in the window | `immutableCount` is memory-only; pre-`endTxCommit` crash → WAL unit incomplete → recovery discards; nothing else durable | clean |

**Window 2 — Q-A2 skip + owner-as-completer.**

| Point | Verdict |
|---|---|
| Skip decision (volatile reads) | clean; residual = accepted TOCTOU (risk item 5) |
| Whitelist actions vs live commit | clean except the session-count contradiction (**CS17**); no status flip → promotion `checkOpenness` passes (CS11 resolved as claimed) |
| Completer on success, teardown clean | clean (P7: `rollback()` no-ops post-`close()`; one-shot guard intact; pooled `close()` later no-ops; result map session-free) |
| Completer on success, teardown throws | **CS16** (masked durable success → retry → duplicate apply) |
| Completer on failure, teardown throws | **CS16** (retry signal replaced) |
| Completer on FM-A2 shape (rollback threw pre-close, ROLLBACKING) | clean heal — `rollbackInternal` do-nothing arm → `close()` → release; widened finally backs it (verified) |
| Completer placement before tx `close()` | rollback listeners fired on a durable commit — folded into CS16's placement pin |
| Late-skip (mark lands after the completer's check) | permit already released via the completed commit's `tx.close()`; teardown defers to borrower `close()`/FM-A5 — amended invariant (2) holds |
| Crash mid-completer | commit durable at `endTxCommit`; teardown state memory-only; recovery unaffected | clean |

**Window 3 — CS14 guard.** Double-release alone → closed; double-release + concurrent freeze →
**CS18** residual; corrective-store clamp race → CS18 pin; throw-mode guard in transient
finallys → CS18 pin; legitimate nesting (`freeze()`+`doSynch`, fr 1→2→1, op = 1) → no clamp
fires, lockstep holds — clean.

## Attacks that failed (justifications for the null verdicts)

1. **Third checkpoint leaves WAL/atomic-op/lock state inconsistent** — refuted. At the throw
   site the commit's atomic operation exists only as an unregistered object (P3: table
   registration happens solely in `startToApplyOperations:143`, WAL records solely from
   `commitChanges`; both post-date `startTxCommit:2766`, which is unreachable before the write
   lock is held). `exclusiveTryLockNanos` cannot leak the write lock (it unlocks its internal
   stamped lock on every timeout return, `:611-613`). The unwind releases IM then SchemaShared
   via the `:3196/:3199` finallys, reaches `doCommit`'s catch → `rollbackInternal` → `tx.close()`
   → `atomicOperation.deactivate()` + `resetTsMin` + mutex release — byte-identical to the
   already-verified V5 class. P4: the exception is a `HighLevelException`, so `commit()`'s catch
   neither error-logs nor `setInError`s. The amendment's "no data commit's readLock is ever
   queued behind an S that gave up" claim is consistent with the tryLock semantics (failed
   attempts dequeue the writer). Confirmed: the paired clear spans the throw (subject to CS15).
2. **The defensive clear kills a snapshot on a retry path** — no in-commit retry exists;
   `commit()` is single-shot and external retries re-enter through a fresh pin. The only
   "snapshot must survive" cases are outer pins, covered as CS15's nested-pin sub-case.
3. **Widened outer-finally release / early-return routing** — no durability edge. The release
   pass is a no-op `getAndSet(0)` when nothing is engaged; on the early-return path
   (`:3274-3277`) nothing durable is touched; the skip path returns before the outer finally per
   protocol (1)+(CN12 note), so no live permit release; the release path never throws by Draft A
   invariant (4), so it cannot itself mask (CS16's maskers are the teardown bodies, not the
   release). Pool-close per-session `catch (Throwable)` runs on a discard path after the
   session's own teardown attempted its release — durable state untouched.
4. **Volatile `status`/`storageTxThreadId` breaks a staleness-dependent path** — refuted.
   Exhaustive check of the two fields' readers: `checkOpenness` reads the **session** status
   (`DatabaseSessionEmbedded:268/:4584-4588`), a different, untouched field. The tx-status
   readers (`isActive`, `checkTransactionValid`, `getStatus`, doCommit's entry checks, the
   CN18 double-teardown guard) and `storageTxThreadId` readers (`assertOnOwningThread:167-171`,
   the `close()` reset check `:1013`) all become strictly *less* racy under volatile — none
   encodes logic that requires missing a concurrent write (the CN18 check-then-zero race stays
   open but identical in kind, already accepted as FM-A4c). Hot-path cost: one volatile load per
   already-object-chasing operation — no ordering-sensitive teardown or recovery consumer
   exists. **Null verdict on (e).**
5. **Underflow clamp breaks a legitimate pairing** — refuted. On every legal pairing the counter
   is ≥ 1 at decrement time, so a CAS-floor never fires; the clamp engages only after a
   pre-existing pairing bug, and its effect is to *restore* the next freeze's correctness vs
   HEAD's negative counter (H8). The masking residue is CS18, not a pairing break.

## Cross-check against the amendments' own dependencies

The amended invariant (2) restatement ("release … whose session teardown **completes** … skip
arm … hands completion to the owning thread, whose own teardown (guaranteed to run …) carries
the release") survives attack *for the permit*: in every traced interleaving (genuine skip,
late-skip, FM-A2-shaped owner failure) either the owner's `tx.close()` finally or the
CN12-widened outer finally releases, and "guaranteed to run" is sound because the completer
finally sits on the owner's own stack (only JVM death defeats it — the accepted class). The
invariant's wording survives; its *machinery* carries the CS16/CS17 exception-path defects
documented above.

## Hypothesis log

See the table in "Method" — H1/H2 → CS15, H6 → CS16, H7 → CS17, H9 → CS18; H3, H4(structural),
H5(pinned-placement), H8, H10, H11, H12 refuted with the justifications in "Attacks that failed".

---

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CS15 | blocker | CS10 amendment, "Defensive paired clear" (+ CN10 §"Snapshot-pin safety" span wording) | The named try/finally mechanism cannot express the required dynamic span; it double-clears against `applyCommitOperations`' `:3142` finally on every failed commit → `immutableCount` → −1 → session poisoned (DDL throws, rebuild-in-window fails, per-read snapshot churn) — CS10's failure class reintroduced on the common path; the two amendment sections also name contradictory span endpoints | Failed data commit (`ConcurrentModificationException` in `commitEntry`): `:3142` clears 1→0, guard clears 0→−1; session's next DDL throws "snapshot usage count is not zero: −1", next class-creating schema commit fails at `rebuildThreadLocalSchemaSnapshot` |
| CS16 | should-fix | Q-A2 skip protocol (2), owner-as-completer finally | Owner-run `internalClose` in the commit-path finally is not throw-isolated (unlike the pool loop) and its placement vs `tx.close()` is unpinned → a teardown throw masks the commit outcome; sharp arm: durable commit reported as failure → client retry → duplicate durable apply | `teardownIntent` set mid-commit; commit durable; completer's `callOnCloseListeners()` (or CS17's negative-count `StorageException`) throws from the finally → caller retries a committed transaction |
| CS17 | should-fix | Q-A2 skip protocol (1), whitelist | Whitelist self-contradiction: DO "decrement the session count" vs DON'T "call `storage.close(this)`" — the only decrement mechanism; Reading A double-decrements (negative-count throw inside the completer, premature `sessionCount==0` → auto-close under live non-zombie sessions); Reading B makes the decrement dead text and invalidates the recorded CS13 premise | Pool thread decrements + owner's full `internalClose` decrements again → last-session case throws `StorageException` from the completer (feeds CS16); multi-session case hits count 0 with a live session → `forceDatabaseClose` outside CS13's accepted scope |
| CS18 | suggestion | CS14(a) underflow guard | Guard cannot detect double-release concurrent with another registered freeze (no per-freeze identity → quiesce theft → corrupt backup; pre-existing, missing from the amended risk list); clamp must be CAS-floor (decrement-if-positive), not decrement-then-`set(0)` (lost-update wipes a legitimate freeze's registration); "(or throw)" must be forbidden on decrements reached from transient release finallys (masks the frozen body's primary exception) | fr=2 (operator + transient), double `release()`: fr 2→1→0 with no negative value — no floor fires, lockstep holds, transient's quiesce silently void while its frozen body runs |

Pointed-concern null verdicts: **(b)** third-checkpoint throw site — clean (no WAL/table/dirty
state exists at the throw; `startTxCommit:2766` unreached; metadata locks released by the
`:3196/:3199` finallys; `HighLevelException` → no `setInError`); **(e)** volatile
`status`/`storageTxThreadId` — clean (no teardown/recovery path depends on the former staleness;
`checkOpenness` reads a different, untouched field; hot-path cost nil).
