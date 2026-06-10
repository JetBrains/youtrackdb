# YTDB-382 — Adversarial pass 5: concurrency

Fifth adversarial pass (2026-06-10), single lens: races, lock ordering, memory
visibility, atomicity seams. Attacked the D8 promotion step, the D19 lock
architecture, the D12 commit-time build, the F44 engage-points, and the
commit-failure path. All claims verified against live code; symbol reachability
(reload callers, `makeThreadLocalSchemaSnapshot` callers) PSI-verified.

Verdict: 3 BLOCKER, 1 MAJOR, 3 minor. The common root: the spine's lock
analysis (D7/D19, F33/F39/F44) covers the D7 mutex and `stateLock` but never
audits `SchemaShared.lock` and the index-manager lock against the new
commit-window work, and the commit-failure path was never walked.

---

## C1: D8's promotion step deadlocks against `reload`, or tears the shared schema maps — the locking of "promote tx-local into shared" is unspecified and every naive choice fails [BLOCKER]

D8 says: at commit "the tx-local structure is promoted to the shared
`SchemaShared` and `forceSnapshot` invalidates the shared snapshot." F43 adds
that the structural diff reads the committed in-memory `SchemaShared` at
commit. Both run inside the D19 `stateLock.writeLock()` window. Neither D8 nor
D19 says which lock guards them. There are only three choices, and each one
breaks:

**(a) Take `SchemaShared.lock` inside the commit (the natural reading) →
ABBA deadlock with `reload`.** The shared maps are not concurrent —
`classes` is a plain `HashMap` (`SchemaShared:70`), `collectionsToClasses` an
`Int2ObjectOpenHashMap` (`:71`) — and every reader uses the schema read lock
(`getClass:398`, `getClassByCollectionId:342`, `existsClass:377`,
`makeSnapshot:198`). So in-place promotion needs `lock.writeLock()` and even
the F43 diff read needs `lock.readLock()`. But `SchemaShared.reload`
(`:355`–`:370`) holds `lock.writeLock()` (`:356`) while it loads the schema
record: `session.load(identity)` (`:363`) → `readRecordInternal` →
`stateLock.readLock()` (`AbstractStorage:4584`). Lock orders:

- schema-carrying commit (D19): `stateLock.write` → `SchemaShared.lock`
- reload: `SchemaShared.lock.write` → `stateLock.read`

Classic inversion. `ScalableRWLock`'s writer preference (`ScalableRWLock:64`–
`65`) makes it bite: reload's `readLock` parks behind the commit's held
`writeLock`; the commit parks on the schema lock reload holds. Permanent
deadlock, no cycle detection.

Reload is not exotic. PSI find-usages on `SchemaShared.reload`: the
remote-storage branch of `releaseSchemaWriteLock` (`SchemaShared:435`),
user-facing `SchemaProxy.reload()` (`SchemaProxy:287`), `SharedContext.reload`
(`SharedContext:159`, reached from `MetadataDefault.reload:137`), and backup
restore (`YouTrackDBInternalEmbedded:688`/`:700`). The killer caller is the
**data path**: `EntityImpl.getGlobalPropertyById` calls `metadata.reload()`
(`EntityImpl:4173`) whenever deserialization meets a global property id absent
from the session's (stale or pinned) snapshot — which is exactly the state a
reader session is in right after another session's schema-carrying commit added
properties. A migration running several schema txs while readers chew on data
walks straight into the interleaving.

**(b) Skip the schema lock →** concurrent readers under the read lock see a
plain `HashMap` being mutated: torn reads, infinite-loop risk on resize, and a
`makeSnapshot` rebuild (held read lock, `:198`) can cache a half-promoted
schema for every session.

**(c) Defer promotion until after `stateLock` release →** window where storage
has the new collections (reconciliation committed) but the shared schema lacks
them; another session that follows a link into a new-class record resolves
`getClassByCollectionId(realId)` → `null` and NPEs — the exact F42 caller list
(`DatabaseSessionEmbedded:2035`, `EdgeEntityImpl:79`, …), now on *real* ids.

**The swap variant is foreclosed by code.** Replacing the shared `SchemaShared`
instance instead of mutating it does not work: `ProxedResource.delegate` is
`final` (`ProxedResource:30`) and each session's `SchemaProxy` binds the
instance at `MetadataDefault.init` (`MetadataDefault:124`), so live sessions
would keep reading the old schema forever.

**Affected:** D8 (promotion mechanism), D19/D7 (the deadlock-freedom claim
"metadata-mutex → stateLock.writeLock; nothing takes them in reverse" audits
only those two locks), F43 (diff read), F42 (option c).

**Resolution direction.** Pick the global order **D7 mutex →
`SchemaShared.lock` → `stateLock`** and make both sides honor it: the
schema-carrying commit acquires `SchemaShared.lock.writeLock()` *before*
`stateLock.writeLock()` and holds it through promotion + `forceSnapshot`;
`reload` already conforms (schema lock first, then storage reads). Then
in-place promotion under the schema write lock is race-free (readers and
`makeSnapshot` exclude it via the read lock), and the mid-window torn-snapshot
hazard (C7) disappears. State this ordering in D19 explicitly; it is a third
lock in the ordering proof, not an implementation detail.

## C2: Commit-failure rollback leaves phantom engines and collections in the shared in-memory registries — and D13 makes that failure routine [BLOCKER]

D3 reconciliation registers structures in shared in-memory state *before*
`commitIndexes`: `doAddCollection` → `registerCollection`
(`AbstractStorage:4963`, mutating `collections`/`collectionMap`,
`:263`–`:264`) plus the config record, and the to-be-extracted
`doAddIndexEngine` body does `indexEngineNameMap.put` + `indexEngines.add`
(`:2811`–`:2812`, fields `:290`–`:291`) plus
`configuration.addIndexEngine`. Then `commitIndexes` (`:2375`) runs the UNIQUE
validators — D13 *defers uniqueness enforcement to exactly this point*. A
duplicate key throws `RecordDuplicatedException`, a `HighLevelException`
(`RecordDuplicatedException.java:32`): the catch at `:2377` routes to
`rollback(error, atomicOperation)` (`:2398`), which only ends the atomic
operation (`:3702`–`:3705`), and `logAndPrepareForRethrow` (`:5913`–`:5917`)
deliberately does **not** poison the storage — the tx is meant to be
recoverable and retried.

So the WAL/file intent rolls back cleanly (F16/D10), but nothing unwinds the
in-memory registrations. After a routine duplicate-key abort of a schema tx:

- `indexEngines`/`indexEngineNameMap` hold an engine whose files were never
  created; the per-index `indexId` is *valid*, so D13's "planner skips
  `indexId < 0`" guard does not protect anyone — any session resolving that
  engine reads missing files. Engine slot ids are consumed (never reused,
  F29), so retries leak slots.
- `collections`/`collectionMap` and the in-memory
  `CollectionBasedStorageConfiguration` caches hold collections with no files.

D15's rollback bullet ("storage engines were never touched") covers only
pre-commit rollback; no D/F entry covers commit-apply failure. Today the
exposure is a sliver (registration is the last statement of the engine-create
atomic op); the design widens it to every schema commit carrying a UNIQUE
index or any record write that can fail validation at apply.

**Affected:** D3, D12, D13, D15 (rollback bullet), F39 (the extracted
primitives are exactly where the unwind hooks belong), D10.

**Resolution direction.** Specify compensating in-memory unwind on the commit
rollback path, executed under the still-held `stateLock.writeLock()` (so it is
race-free): de-register engines and collections added by this reconciliation,
restore the config caches. Additionally pin the publication order: tx-local →
shared promotion (C1) and the D15 overlay publication must run only after
`endTxCommit` succeeds, so a WAL-flush failure cannot publish a schema that
describes a rolled-back commit.

## C3: The commit-time index build (D12) self-deadlocks on `stateLock` — population reads rows and commits batches through paths that re-acquire the lock the commit holds [BLOCKER]

D12 orders "create engine → populate from committed data → `lockIndexes` →
write tx records → `commitIndexes`", all inside the D19
`stateLock.writeLock()` window. The only population machinery in the codebase
is `IndexAbstract.indexCollection` (`:962`–`:1015`): it copies the session
(`:989`), iterates `browseCollection` (`:990`), and writes through batched
micro-transactions via `executeInTxBatchesInternal` (`:991`). Every leg of
that re-acquires `stateLock`:

- record reads: `readRecordInternal` → `stateLock.readLock()`
  (`AbstractStorage:4584`);
- each batch commit: `commit` → `stateLock.readLock()` (`:2285`).

`ScalableRWLock` is non-reentrant (`ScalableRWLock:64`): read-after-write on
the same thread self-deadlocks; hoisting population onto another thread just
converts it to a two-thread deadlock against the held write lock. F22 flagged
the refactor need generically ("population … must be refactored to accept and
reuse the commit's single `AtomicOperation`"), but F39's resolution extracted
only `doAddIndexEngine`/`doDeleteIndexEngine` — the population path's
`stateLock` re-entry and its nested batch *transactions* (commits inside the
commit) were never resolved, and D12's "populate from committed data" names no
mechanism.

The same seam hits F46: the commit-only membership application calls
`IndexAbstract.addCollection(requireEmpty)` whose emptiness probe is
`session.browseCollection` (`IndexAbstract:676`), and `removeCollection`'s
probe likewise (`:698`) — both re-enter the record read path at commit time
under the held write lock.

**Affected:** D12, D18 (genesis builds the `OUser.name` index via this path),
F22, F39, F46, D19.

**Resolution direction.** Commit-time population must bypass the session and
the public storage read path entirely: iterate the source collection via the
lock-free internal primitives (the `doReadRecord`/collection-level iteration
that takes an `AtomicOperation` and assumes the lock is held), feed keys
straight to the engine's `doPut` within the commit's atomic operation, no
copied session, no nested transactions. The F46 emptiness checks need the same
treatment (an internal `isEmpty(atomicOperation)` primitive). This is a new
extraction of the same shape as F39's, and belongs on its list.

## C4: The F44 engage-points acquire the shared locks *before* the mutex hook can run — a parked second schema tx holds the shared schema write lock for the whole duration of the first, and deadlocks against C1's commit-side schema-lock acquisition [MAJOR]

F44 pins the D7-mutex engage to the two chokepoint methods, and both take
their shared lock as the first statement: `SchemaShared.acquireSchemaWriteLock`
does `lock.writeLock().lock()` at `:415`; `IndexManagerEmbedded.acquireExclusiveLock`
does `lock.writeLock().lock()` at `:189`. A hook *inside* those methods
therefore engages the mutex while already holding the shared lock. D7's own
proof says "a second schema tx blocks on the mutex before touching anything
else" — wired per F44, that is false: tx2's first schema mutation parks on the
D7 mutex (held by tx1 for its entire body, possibly a long migration) while
holding the **shared** schema write lock. Consequences:

- every lock-based schema read in every session stalls for tx1's whole
  duration (`SchemaProxy.getOrCreateClass:87` → `delegate.getClass` →
  `acquireSchemaReadLock`, `SchemaShared:398`), gutting D7's "does not block
  schema reads" premise;
- with C1's resolution in place (commit takes `SchemaShared.lock`), it is a
  hard deadlock: tx1's commit wants the schema lock tx2 holds, tx2 waits on
  tx1's mutex.

There is also a D8-routing wrinkle F44 predates: mid-tx mutations run against
the **tx-local** `SchemaShared`/overlay, so an instance-level hook on the
shared chokepoint either never fires mid-tx (mutex never engages) or fires on
the wrong instance. The engage decision is made where the routing decision is
made — the proxy layer — not inside the shared instance's lock method.

**Affected:** D7 (engage-point bullet), F44, F47 (granularity note), D8.

**Resolution direction.** Engage the D7 mutex strictly *before* acquiring any
shared metadata lock: at the `SchemaProxy`/index-routing layer, on the first
write-routed operation, before seeding the tx-local copy and before delegating
into anything that takes `SchemaShared.lock` or the index-manager lock. Restate
D7's ordering as: D7 mutex → (seed) → tx-local locks only during the body;
shared locks only at commit, in C1's order.

## C5: D17/F46 commit-time in-place mutation of shared `IndexDefinition`/`Index` fields is a data race against lock-free readers [minor]

The commit applies the class-rename re-association by mutating
`IndexDefinition.className` (recursing composites) and membership by mutating
the committed index, under `stateLock.writeLock()` plus per-index locks. But
several hot readers read those objects with no lock and no happens-before
edge: `IndexManagerAbstract.getClassIndex` reads
`indexes.get(name).getDefinition().getClassName()` lock-free (`:154`–`:164`),
and `getClassRawIndexes`/`getIndexOnProperty` iterate `classPropertyIndex`
lock-free (`:85`–`:100`). Snapshot-routed readers are safe (the volatile
`snapshot` null/rebuild gives the edge), but direct readers can see stale
`className` indefinitely under the JMM, or a torn composite (outer definition
re-keyed, nested sub-definitions not yet, F30's recursion is multi-field and
non-atomic). F30 analyzed hash-bucket safety, F40 analyzed mid-tx isolation;
neither analyzed commit-time reader visibility.

The codebase's own pattern for this map is publication, not mutation:
`addIndexInternalNoLock` copies the inner map and republishes it via a CHM
`put` (`IndexManagerAbstract:229`–`:252`).

**Affected:** D17, F40, F46, F30, D15.

**Resolution direction.** Publish rename/membership changes as replacement
objects (new definition, or a rebuilt `Index` entry) installed via the CHM
put, mirroring `addIndexInternalNoLock`'s copy-on-write discipline, instead of
field writes into shared objects. Cheap, and removes the JMM caveat entirely.

## C6: The tx-scoped D7 mutex has no release path on abnormal session termination — one wedged schema tx blocks all schema DDL forever [minor]

D7 holds a `ReentrantLock` from first mutation to the `finally` of the
outermost `commit`/`rollback`. F38 guards the wrong-thread release
(`IllegalMonitorStateException`); the uncovered case is *no release at all*: a
session whose thread dies (OOM, `Thread.stop`-style kill, client connection
dropped with server-side reaping from another thread) never reaches that
`finally`, and a `ReentrantLock` held by a dead or stuck thread cannot be
released by anyone else. Every subsequent schema-changing tx then parks
forever on `lock()` (D5 chose blocking, with no timeout). Today's metadata
locks are per-operation, so the stranded-lock exposure was bounded by one
operation; the tx-scoped lifetime is net-new exposure proportional to user
code between first mutation and commit.

**Affected:** D7, D5, F38, F13.

**Resolution direction.** Tie mutex release to the session-close/cleanup path
on the owning thread (close of a session with an active schema tx must run
rollback's release), use a timed/interruptible acquire with a clear diagnostic
instead of a bare `lock()`, and state the reaper-close semantics as a D7
bullet next to the F38 guard.

## C7: D8 and D15 publish in two steps with independently-worded `forceSnapshot` calls; without C1's lock the mid-window snapshot can cache a torn class-set/index-set combination [minor]

D8 says "promote then forceSnapshot"; D15 says "publish the overlay … then
forceSnapshot." Implemented literally as two publish+invalidate pairs, a
concurrent `makeSnapshot` (legal mid-commit whenever the shared snapshot is
already null from an earlier invalidation; the rebuild takes only the schema
*read* lock, `SchemaShared:198`, and reads the index manager lock-free, F44)
can run between the two publications and cache new classes with the old index
set, or the reverse. The torn snapshot survives until the *second*
`forceSnapshot` and can plan queries against a tx-dropped index whose engine
reconciliation already removed (transient query failure, same shape as
today's drop-window). If C1's resolution is adopted (schema write lock held
across the whole publication), the rebuild is excluded for the whole window
and this collapses to a wording fix: one trailing `forceSnapshot` after both
publications, stated once.

**Affected:** D8, D15, F44.

**Resolution direction.** State the commit-side publication order in one
place: schema promotion and index-overlay publication both complete (after
`endTxCommit`, per C2), then a single `forceSnapshot`, all inside C1's lock
scope.

---

## Attacks run that produced no new finding

- **Tx-local view single-threadedness (F51 invalidation, F47 ripple).**
  Sessions are thread-bound (F13); the tx-local `SchemaShared`, its lazy
  snapshot invalidation, and the lock-free ripple all stay on one thread.
  The `MetadataDefault.immutableSchema` pin (`:78`–`:114`) was checked as a
  second cache layer above F35's invalidation: its only production pin scopes
  are per-read (`DatabaseSessionEmbedded:1091`), per-commit
  (`AbstractStorage:2235`, pinned to the tx-local schema per D8), and the
  `EntityImpl:4173` recovery path — none spans a mid-tx DDL followed by a
  record write, so F35's lazy invalidation is not defeated by the pin.
- **Six self-commit guard interleaving.** Once de-guarded per D1/F46, all six
  paths ride the tx body and are serialized by the D7 mutex (with C4's fix);
  no two-tx interleaving survives on them.
- **Shared `indexes`/`classPropertyIndex` publication safety.** Both are
  `ConcurrentHashMap` (`IndexManagerAbstract:47`–`:49`) with copy-on-write
  inner maps (`:229`–`:252`); commit-time publication via `put` is safe for
  lock-free readers per entry (cross-entry consistency is C7/C1).
- **Data-commit pre-lock phase racing reconciliation.** The pre-`stateLock`
  phase of `commit` (`:2262`–`:2283`) reads `collections` without the lock,
  but the list is a `CopyOnWriteArrayList` (`:264`) and the same race exists
  today against `addCollection`; pre-existing, not introduced by the design.
- **Stale-handle index apply across a concurrent drop/recreate.** A data tx's
  captured `Index` object racing a schema tx's drop+create resolves through
  `doReloadIndexEngine` by name; the failure modes (loud
  `InvalidIndexEngineIdException`, or correct apply onto the rebuilt engine)
  are unchanged from today's non-tx `dropIndex` race; pre-existing.
- **Seed-time consistency.** The D8 seed (toStream→fromStream round-trip)
  reads the committed schema under the schema read lock before any
  `stateLock` is held — consistent with C1's lock order; a concurrent reload
  serializes ahead of or behind it.
