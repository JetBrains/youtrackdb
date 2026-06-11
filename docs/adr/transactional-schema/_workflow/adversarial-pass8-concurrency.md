# YTDB-382 — Adversarial pass 8: concurrency (2026-06-11)

Eighth adversarial pass, single lens: races, lock ordering, memory visibility,
atomicity seams, thread-binding. Targeted the pass-7 fold text accepted on
2026-06-11 — F76's operation-scoped `tsMin` release and the D7 reap protocol
built on it, F77's tx-aware population split, F78's freezer throwing variant,
F79's owner-token mutex sketch, and F80's commit-local allocator — plus the
older D text those folds re-premise. All claims verified against the live tree
by direct reads; **mcp-steroid was not reachable in this session**, so every
new reference-accuracy claim ("the initiators I found", "registrar paths") is
grep-based and may miss polymorphic call sites or reflective access. Claims
PSI-verified in prior passes (`resetTsMin`'s only tx-end caller,
`startStorageTx`'s only production caller, `registerCollection`'s caller set)
are cited as such without re-verification.

Verdict: 1 BLOCKER, 3 MAJOR, 3 minor. The F77 split and the F79 token sketch
held against their direct attacks (see the dry list). The common root this
round: the F76/F78 folds each move a single-threaded compound operation onto a
second thread or behind a different lock, and the fold text fixes only the
field-level memory mode — the check-then-act compounds around it
(decrement-then-reset, scope-check-then-rollback, probe-then-acquire) are
still non-atomic, and each has a reachable interleaving that lands in the
unsafe direction.

---

## C20: The reaper's decrement-then-reset on the captured `TsMinHolder` races the owner's min-then-increment at begin — `tsMin` ends at `MAX_VALUE` with a live tx, and cleanup evicts snapshot entries that tx is using [BLOCKER]

The F76 fold (D7 reap bullet) makes `activeTxCount` "a cross-thread-safe
atomic RMW" and has "both `close()` arms plus the reap release by captured
holder". The atomic count is necessary and not sufficient: the release is a
two-field compound — decrement the count, and when it reaches zero, reset
`tsMin` to `MAX_VALUE` — and the begin side is the mirror compound — min-write
`tsMin`, then increment. Today both compounds run on one thread, so the
invariant `activeTxCount == 0 ⟺ tsMin == MAX_VALUE` holds trivially
(`startStorageTx`: min-write at `AbstractStorage:4653`, increment at `:4654`;
`resetTsMin`: decrement at `:4687`, zero-branch reset at `:4688`–`:4696`). The
fold puts the release compound on the reaper's thread with no ordering between
the two threads' four steps beyond the count's own atomicity.

**Interleaving.** Pooled worker thread T's holder carries a stranded schema
tx's pin: `activeTxCount = 1`, `tsMin = 1000`. T is back in the pool serving
session S2; the reaper releases the stranded tx by captured holder.

1. Reaper: `count.decrementAndGet()` → 1→0, observes zero.
2. T (S2 begin): `holder.tsMin = Math.min(1000, 2000)` → 1000 (the min-ratchet
   inherits the stranded residue; `:4653`).
3. T: `count.incrementAndGet()` → 0→1.
4. Reaper (acting on its zero observation): `setTsMin(Long.MAX_VALUE)`.

Final state: `activeTxCount = 1`, `tsMin = MAX_VALUE` — an active transaction
invisible to the cleanup thread. The orderings 2-1-4-3 and 2-1-3-4 produce the
same state, so the race does not depend on a one-instruction window. The
consequence is prompt and in the unsafe direction: `cleanupSnapshotIndex` runs
at every tx close on any thread (`resetTsMin:4698`, commit success path
`:2414`), computes `computeGlobalLowWaterMark` over the holders (`:6451`,
`:6954`–`:6960`), and evicts snapshot/visibility-index entries up to the LWM
(`:6452`–`:6461`). With S2's floor invisible, the LWM jumps to the
`idGen.getLastId()` fallback and eviction removes entries S2's SI reads still
need. `TsMinHolder`'s own contract names this exact failure: "A stale
MAX_VALUE would let cleanup evict entries the read session is actively using"
(`TsMinHolder:33`–`:34`). Wrong visibility on a live transaction is silent
read corruption, and the interleaving needs only the design's routine reap
(idle-timeout on a vanished client) overlapping one tx begin on the same
pooled thread.

Skipping the reaper-side reset does not save the fold: leaving `tsMin` at the
stranded value with `count == 0` floors the LWM until the owner thread's next
begin/end cycle, and an idle pooled thread defers that indefinitely — the F76
leak returns in bounded-only-by-luck form, while D7 claims the reap "releases
the `tsMin` snapshot floor". The same deferral hole sits in the documented
fence-free fallback (owner-plain count plus a reaper-side pending-decrement
folded "at the owner's next tx end"): an owner thread that never runs another
tx never folds the decrement, so the fallback does not fix the leak it is the
fallback for. The fallback is at least race-free (all count/tsMin writes stay
owner-thread); the accepted RMW variant is neither race-free nor explicit
about who resets.

**Affected:** D7 (reap bullet, F76 fold), F76, D12 (heap-bounded migration
guidance assumes the floor releases at reap), D5.

**Resolution direction.** Treat `{activeTxCount, tsMin}` as one atomically
updated state, not two fields. Options with contained envelopes: (a) a small
per-holder lock (or CAS loop over a packed state) taken by begin, end, and
reap — begin/end are per-tx, not per-operation, so the cost argument that
chose the RMW still holds; (b) keep the count as the single authority and
make `computeGlobalLowWaterMark` ignore holders whose count is zero, so the
reaper only decrements and never resets — this requires re-deriving the
TOCTOU argument in `computeGlobalLowWaterMark`'s fallback (`:6900`–`:6903`),
because the min-ratchet lets a new tx inherit a stale residue below the
fallback (`min(1000, 2000)` above), which breaks the "every new transaction
sets tsMin >= idGen.getLastId() at its start" premise the fallback rests on.
Either way, D7's fold text must state the compound invariant and who restores
it; the count's memory mode alone does not.

## C21: The pin release is not once-only — concurrent rollback initiators double-decrement the captured holder, and F76 removes the thread-id gate that makes today's cross-thread arm harmless [MAJOR]

D7's reap protocol names several rollback initiators for one tx, and two of
them can run concurrently: the D7 reaper (cross-thread, the new routine path)
and the zombie owner's own unwind — F79's grounding explicitly keeps the
stalled-but-alive owner in play, and the Gremlin machinery adds a third, the
evaluation-timeout hook that rolls the tx back from the scheduler thread
(`YTDBGremlinSession:219`–`:226`; the kill paths themselves are serialized by
the `synchronized kill` plus the sessions-map guard, `:172`/`:178`/`:207`, but
`afterTimeout` is outside that monitor — grep-based, may miss other
initiators). The tx state those initiators traverse is unsynchronized by
design: `assertOnOwningThread` exempts `close()`/`rollbackInternal`
(`FrontendTransactionImpl:130`–`:131`), `status` is a plain field (`:81`), and
`close()`'s guard is a check-then-act on the plain `atomicOperation` field —
read at `:951`, nulled in the finally at `:964`.

**Interleaving.** Reaper thread R and zombie/timeout thread Z both reach
`rollbackInternal` for the same stranded tx. Both read `status == BEGUN`
before either writes `ROLLBACKING` (`:361`–`:369`, plain field, no
happens-before) → both proceed → both reach `close()` → both read
`atomicOperation != null` (`:951`) before either nulls it → both run the
release arm. Under F76 every `close()` arm releases by captured holder, so
the holder is decremented twice for one tx. If the owner thread had already
begun a new tx between the two releases, the second decrement takes the new
tx's count 1→0 and resets `tsMin` — the C20 end state (live tx invisible to
cleanup) reached through double-release instead of the reset race. If no new
tx intervened, the count underflows and the next healthy tx on that pooled
thread ends with the holder at zero before its own decrement — its close
throws `resetTsMin`'s guard (`:4682`–`:4686`) or its analogue, and the floor
stays pinned.

Today the same concurrent double-close exists (pool shutdown, timeout hook),
but it is harmless to the pin: the cross-thread arm skips `resetTsMin` behind
the thread-id gate (`close():954`–`:956`, PSI-verified pass 7 as the only
tx-end caller), so at most one arm — the owner's — ever releases. F76 removes
that asymmetry on purpose ("both `close()` arms ... release by captured
holder") and adds no replacement once-only guard. The design already solved
this exact problem class one object over: F79's mutex release is a hard CAS
on a per-acquisition token precisely so a stale or duplicate release is a
detected no-op. The pin release got no such treatment.

**Affected:** D7 (reap bullet), F76, F79 (the "tolerate the owner racing it"
caveat names the race but carries no mechanism for the pin), F61.

**Resolution direction.** Make the pin release once-only per tx, mirroring
F79's token: the tx's captured holder reference is consumed with a
`getAndSet(null)` (or an equivalent release-once CAS), and only the winning
arm decrements; every other initiator's release is a logged no-op. One
sentence in D7's reap bullet plus one field-level decision; the F76 regression
test gains a concurrent double-rollback variant.

## C22: "Scoped to between-operations stranding" has no atomic discriminator — the reaper's scope check races the owner's commit entry on plain fields, and the loser can durably commit concurrently-cleared tx state [MAJOR]

D7 (F76 fold) scopes the reap to between-operations stranding and routes
mid-commit-window strands to the storage-error/restart path. For the reaper to
honor that scope it must answer "is the owner inside the commit window right
now?" against an owner that may be alive (F79's premise) and may enter the
window at any moment. Every field it could consult is plain: `status` is
non-volatile (`FrontendTransactionImpl:81`) and written with plain stores
(`doCommit` sets `COMMITTING` at `:668`; `rollbackInternal` sets `ROLLBACKING`
at `:369`), `storageTxThreadId` is plain (`:124`), and no commit-window flag
exists. So the scope check is a TOCTOU on stale-readable state, and the
check-then-rollback compound is not atomic against the owner's
check-then-commit compound (`doCommit`'s own status gate at `:637` is the same
plain read in the other direction).

**Interleaving.** The reaper observes the stranded tx between operations
(`status` reads `BEGUN`) and starts the full rollback: `rollbackInternal` →
`clear()` wipes `recordOperations` and unloads records (`:972`–`:996`) →
`close()` deactivates the operation (`:953`). Concurrently the zombie owner
wakes from its stall and the user code calls commit; its `doCommit` reads the
stale `BEGUN` (no happens-before from the reaper's plain `ROLLBACKING` write),
sets `COMMITTING`, and enters `internalCommit` → `AbstractStorage.commit`,
which iterates `getRecordOperationsInternal()` (`:2237`, backing map
`recordOperations:83`) while the reaper's `clear()` is mid-wipe, and runs
`startTxCommit` (`:2293`) on the operation object the reaper is concurrently
deactivating. Best case is a loud `ConcurrentModificationException` or a
deactivated-operation failure; worst case the commit serializes a
partially-cleared operation set into the WAL and the end record makes it
durable — a half-tx committed, which no restart repairs. Today's cross-thread
close is confined to pool shutdown, where no owner activity exists by
construction; the design promotes it to routine recovery against explicitly
possibly-alive owners, and "the reap path tolerates the owner racing it"
(F79's caveat, folded into D7) names the race without giving the reaper or
the owner any primitive to win it with.

**Affected:** D7 (reap scope sentence), F76, F79, F71, D5.

**Resolution direction.** The scope decision must ride an atomic status
handshake, not an observation: make the tx status (or a dedicated tx-phase
field) atomic with CAS transitions, where the owner's commit entry is
`BEGUN → COMMITTING` and the reaper's claim is `BEGUN → ROLLBACKING`, so
exactly one side wins; the loser sees the other's terminal state and stands
down (the reaper defers to the storage-error path, the owner fails its commit
loudly before touching the storage). This also gives the scope check its
missing discriminator: a tx in `COMMITTING` is mid-window by definition.
State it in D7's reap bullet as a prerequisite mechanism alongside the F79
token.

## C23: The freezer's throwing gate sits below the `stateLock.write` acquisition — a data commit parked at the gate holds `stateLock.read` for the whole freeze window, the DDL commit queues behind it, and the freeze-window read outage returns one lock up [MAJOR]

The F78 fold accepts reject-loudly so that "reads keep flowing for the whole
freeze window". The gate it routes through sits inside `startTxCommit`, which
the commit reaches only after acquiring its locks — under D19 that is the D7
mutex, `SchemaShared.lock`, `IndexManagerEmbedded.lock`, and
`stateLock.writeLock()`. The outage C18 reported is not dissolved by the
throw; it moves to the lock acquisition the gate sits behind.

**Interleaving.** Operator engages a parking freeze (`freeze(db, false)`,
`AbstractStorage:3905`). A concurrent data commit reaches its gate after the
freeze engaged and parks (`OperationsFreezer.startOperation:30`–`:48`) —
holding `stateLock.readLock()`, taken at `:2285` and released only at `:2432`,
far below the park. The freeze's drain spin does not wait for parked entrants
(they decrement `operationsCount` before parking, `:38`), so `freeze()`
returns and the parked commit holds its read lock until `release(db)`. A
client now issues DDL: the schema-carrying commit takes the mutex, the schema
lock, the index lock, then blocks on `stateLock.writeLock()` behind the parked
reader. `ScalableRWLock` is writer-preferring (`ScalableRWLock:64`–`:65`,
prior-pass-verified), so every subsequent `readLock()` acquisition — record
reads via `readRecord:4584`, queries, lock-based metadata reads — parks behind
the queued writer. Total read outage until `release(db)`, exactly C18's end
state; the throwing gate never fires because the commit never reaches it, and
the DDL then proceeds normally once unfrozen, so the promised loud failure
silently degrades into "hang through the freeze while blocking all reads".

The shape needs only one write transaction attempting to commit during the
freeze window, which on a live server is near-certain. A variant of the same
shape exists today (a structural self-commit takes `stateLock.write` at
`addCollection:1444` and parks at its own gate inside
`calculateInsideAtomicOperation`), so the outage is not wholly design-created;
what is design-created is the fold's claim that the throwing variant preserves
read availability. As folded, it does not.

**Affected:** D7 (freezer-gate bullet), F78, D19, F48.

**Resolution direction.** The loud-fail decision must execute before the
commit queues on any lock a reader needs. Two pieces: (a) probe the freezer
(`freezeRequests` or an `isFrozen` accessor) at schema-commit entry, before
the four-lock sequence, and throw storage-frozen there — this closes the
freeze-engaged-first ordering; (b) for the freeze-engages-after-probe
ordering, acquire `stateLock.writeLock()` through a bounded try-acquire loop
that re-probes the freezer on each timeout and throws if a freeze engaged —
this keeps the loud-fail semantics in every ordering instead of queueing
behind a reader parked for the freeze's duration. The in-window gate stays as
the authoritative backstop. D7's freezer bullet should state that the gate
alone cannot deliver the availability claim because parked data commits hold
`stateLock.read` across the freeze.

## C24: "The freezer's throwing variant" names a freeze-side mechanism — the live gate throws only for freezes registered with an exception supplier, so the entrant-side throw the fold needs does not exist [MINOR]

F78's fold says schema commits "route through the freezer's throwing variant"
while "data commits keep parking behavior". In the live machinery the
throw-vs-park split is a property of the **freeze**, not the entrant:
`freezeOperations(throwException)` registers the supplier
(`OperationsFreezer:77`–`:79`), `startOperation` throws only what
`throwFreezeExceptionIfNeeded` finds in `freezeParametersIdMap`
(`:114`–`:118`), and the parking backup freeze registers nothing
(`freeze(db, false)` → `freezeWriteOperations(null)`,
`AbstractStorage:3905`). Against a parking freeze the existing gate cannot
throw for anyone; against a throwing freeze (`freeze(db, true)`, `:3900`–
`:3903`) it throws for everyone, data commits included. An implementer who
wires the fold with the existing API therefore either gets no throw at all
for the backup-freeze case the fold is about, or — by converting the freeze
to the throwing variant — makes data commits throw too, violating the same
bullet's "data commits keep today's behavior".

The fold needs a net-new entrant-side gate (a `startOperationOrThrow()` that
throws on `freezeRequests > 0` regardless of the freeze's own registration),
and one ordering pin for its unwind: the throw must fire before the
per-thread depth increment (`operationDepth.increment()`,
`OperationsFreezer:56`), because `endAtomicOperation`'s outermost finally
calls `endOperation()` unconditionally (`AtomicOperationsManager:441`–`:443`)
and a depth of zero there throws "Invalid operation depth" (`:61`–`:62`).
The existing commit structure keeps that balanced — `startTxCommit` at
`:2293` sits outside the inner try, so a gate throw bypasses both
`rollback(error, op)` and `endTxCommit`, and the session-layer unwind
(`doCommit:690` → `rollbackInternal` → `close()` → `deactivate:953`) never
touches the freezer — but nothing in the fold tells the implementer this
placement is load-bearing.

**Affected:** D7 (freezer-gate bullet), F78.

**Resolution direction.** Reword the bullet: the schema commit enters through
a new entrant-side throwing gate (throws whenever any freeze is engaged,
parking or throwing); the freeze-side `throwException` variant is unchanged
and unrelated. Pin the two unwind facts: gate-throw before depth increment,
and `startTxCommit` outside the rollback/endTxCommit branch.

## C25: F80's "seeded at commit entry" must mean inside the `stateLock.write` window — engine-id registrars exist that take only `stateLock`, never the D7 mutex [MINOR]

D3's F80 sentence justifies the commit-local allocator's seed read as "safe:
schema commits are serialized by the D7 mutex plus `stateLock.write`", but
"at commit entry" leaves the seed's position relative to the lock sequence
open. The D7 mutex serializes schema commits only against each other. At
least two registrar paths mutate the engine registry under `stateLock.write`
alone: `IndexAbstract.rebuild` calls `storage.addIndexEngine`
(`IndexAbstract:305`; engine id allocated from `indexEngines.size()`), and it
is reachable from the crash-recovery rebuild thread — `recreateIndexes` spawns
a dedicated background thread (`IndexManagerEmbedded:489`–`:502`) from session
open (`DatabaseSessionEmbedded.rebuildIndexes:597`–`:605`) — and from the
user-facing rebuild API; `loadExternalIndexEngine` (`IndexAbstract:240`) is
the same shape for external engines. (Grep-based caller inventory; may miss
polymorphic call sites. The non-durable-index rebuild leg is dormant while
the lucene module is excluded, but the user-facing rebuild is live.)

If the seed is read before the commit acquires `stateLock.writeLock()`, a
concurrent rebuild's `addIndexEngine` can register a slot the seeded
allocator also hands out — the F80 duplicate-id failure returns through the
one window the fold left unpinned. Read inside the write-lock window, the
seed is correctly serialized against every registrar, since all of them take
`stateLock.write`; cross-commit visibility is already covered by the D7
mutex ordering publication before release.

**Affected:** D3 (F80 sentence), F80, F53.

**Resolution direction.** One wording pin in D3: the allocator seed is read
after `stateLock.writeLock()` is acquired (and the parenthetical's
justification becomes accurate as stated). The F53/F80 PSI audit should also
enumerate non-commit registrars (`rebuild`, `loadExternalIndexEngine`,
`recreateIndexes`) and state whether each survives under the design or routes
through the schema-commit path.

## C26: The tx's strong capture of the `TsMinHolder` defeats the weak-keyed `tsMins` self-heal for dead-thread leaks [MINOR]

Today the holder is reachable only through the owner thread's `ThreadLocal`
and the weak-keyed `tsMins` set (`AbstractStorage:367`, `:370`–`:375`): when a
thread dies with a stranded tx, the holder becomes weakly reachable, the set
entry is evicted, and `computeGlobalLowWaterMark` stops seeing the stranded
floor — the leak self-heals. F76 adds a strong reference from the
`FrontendTransactionImpl`/`AtomicOperation` to the holder, captured at
`startStorageTx`. For a leaked session whose owner thread died and on which no
reap ever runs (embedded deployments have no reaper role; the design's reap
grounding is the Gremlin server kill path), the strong chain
session → tx → holder keeps the holder in `tsMins` forever, converting
today's self-healing leak into a permanent floor pin. Server deployments are
covered by the reap; the regression is confined to embedded/no-reaper usage,
which is why this is minor.

**Affected:** D7 (reap bullet), F76, D12 (heap guidance).

**Resolution direction.** Either null the captured reference in every
`close()` arm (bounding the strong chain to the tx's active life — the leaked
never-closed case remains, and should be named in D7 as the residual that
weak eviction used to cover), or document the trade explicitly: under F76,
dead-thread floor release requires the close/reap path, and the weak-key
eviction no longer backstops it.

---

## Attacks run that produced no new finding

- **F77 created-then-deleted rows dropping out of the record-operation
  set.** The eager flush removes DELETED-and-new operations from
  `recordOperations` entirely (`FrontendTransactionImpl:792`–`:825`).
  Harmless to the split: the row's temp RID never appears in committed data
  (population cannot encounter it) and no put is owed. Committed-row deletes
  keep their entry (type merged to DELETED, `:604`–`:606`), so the population
  skip-set is complete for every RID that matters.
- **F77 skip-set stability across RID resolution.** Population runs before
  the position-allocation loop (D3), so committed rows are matched by stable
  real RIDs; the identity-change listeners re-key `recordOperations` when
  temp RIDs resolve (`:1187`–`:1230`), and re-derivation's puts read
  identities lazily after resolution (F24). No window in which a RID is "in
  flight between the sets".
- **F77 update-equals-committed and no-op updates.** Population skips the
  row, re-derivation puts the in-memory final state — one put, correct value,
  for both shapes. Updated records are not unloaded mid-tx (only deletes
  carry the unload comment at `:482`), so final values are present at commit.
- **F77 double-puts from the commit-time enqueue composing with
  re-derivation.** The final flush at `commitInternalImpl:232` can enqueue
  entries for a tx-created index via the overlay; F66's "the enqueue source
  ... is the tx's complete record-operation set" reads as replacement, and a
  same-key-same-RID re-put is idempotent at apply. Worth a one-line plan pin
  ("re-derivation replaces, never merges with, the incrementally-enqueued
  entries for tx-created indexes"), not a finding.
- **F79 `releaseStranded` killing a newer healthy acquisition by the same
  session.** Foreclosed by the single permit: the zombie session cannot hold
  a second acquisition while the reaped one is unreleased, so the
  match-by-session CAS can only ever clear the acquisition the reaper rolled
  back. Token identity covers the rest.
- **F79 token visibility and the acquire-to-set window.** `AtomicReference
  .set` is a volatile write, so the reaper's `owner.get()` is well-ordered;
  the null-window no-op is acknowledged in the entry, and a stalled-but-alive
  owner releases via its own matching CAS in the finally. The only stranding
  variant left (thread death between `tryAcquire` and `owner.set`) is the
  accepted re-wait residual.
- **F78 freeze engaging between the gate pass and table registration.** The
  entrant already incremented `operationsCount` (`OperationsFreezer:33`), so
  `freezeOperations`' drain spin (`:81`–`:83`) waits for the in-flight commit
  — exactly the fold's "a freeze that engages after the gate waits for the
  in-flight commit to drain".
- **F78 lost wakeup in the park path.** The re-check before park (`:46`) plus
  the cut-then-unpark in `releaseOperations` (`:105`–`:110`) close the
  add-after-cut and unpark-before-park orderings; a throwing entrant never
  parks.
- **F76 begin-side cost premise.** Re-verified: `startAtomicOperation` does
  the operations-table snapshot for every tx (`AtomicOperationsManager:81`–
  `:104`) and begin writes volatile `tsMin` (`:4653`), read-only included —
  the fold's "begin already pays" claim stands.
- **F76 weak-set iteration vs reap.** The reaper mutates holder fields and
  never structurally modifies `tsMins`; `computeGlobalLowWaterMark`'s
  weakly-consistent iteration is unaffected.
- **F80 seed vs the previous schema commit's deferred publication.** The D7
  mutex serializes schema commits, and publication completes before the
  mutex releases, so the next commit's seed observes it via the mutex's
  happens-before. Failed commits publish nothing and their ids are reusable,
  as the fold states.
- **F64 four-lock order vs the new folds.** The freezer is a park gate
  outside the order (C18 dry-listed the no-deadlock proof; C23 is an
  availability defect, not a cycle), and no fold added a lock acquisition in
  reverse order. Not re-tilled beyond that delta check.
