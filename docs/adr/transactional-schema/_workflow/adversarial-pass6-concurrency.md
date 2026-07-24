# YTDB-382 — Adversarial pass 6: concurrency

Sixth adversarial pass (2026-06-10), loop-until-dry, single lens: races, lock
ordering, memory visibility, atomicity seams. Targeted the seams the pass-5
resolutions created or moved: the F52 three-lock order, F53's commit-local
resolution, F56's proxy-layer engage, F62's single trailing `forceSnapshot`,
F60's replacement-object publication, F54's lock-free population scan, and
F61's timed acquire. All claims verified against live code; symbol questions
(ClassIndexManager callers, callback-flush call chain) PSI-verified.

Verdict: 3 BLOCKER, 2 MAJOR, 3 minor. The common root this round: pass 5
ordered the schema lock and walked the storage commit, but never audited the
**session-layer commit phase** (index-entry enqueue, listener dispatch) or the
**second metadata registry** (the index manager's maps and lock) against the
new commit window.

---

## C8: The index-manager lock is missing from the F52 order — `reload`'s clear-and-rebuild races the commit's lock-free overlay publication, and the naive lock fix re-creates the C1 deadlock on the second lock [BLOCKER]

F52 added `SchemaShared.lock` to the ordering proof (D7 mutex →
`SchemaShared.lock` → `stateLock`) and F60/D15 chose **lock-free** CHM `put`s
for publishing the index overlay into the shared manager maps. Pass 5 verified
that publication against lock-free *readers* ("safe for lock-free readers per
entry") — it never audited the other *writer* of those maps.

**The other writer.** `IndexManagerAbstract.load(transaction, entity)`
(`IndexManagerAbstract:191`) does `indexes.clear()` (`:192`),
`classPropertyIndex.clear()` (`:193`), then repopulates via
`addIndexInternalNoLock` (`indexes.put:219`, `classPropertyIndex.put:251`).
It runs under the index-manager write lock only
(`IndexManagerEmbedded.reload:89`, lock field `:60`) — a lock the commit-side
publication deliberately does not take. And it is reachable from the data
path: `EntityImpl.getGlobalPropertyById` → `metadata.reload()`
(`EntityImpl:4173`) → `MetadataDefault.reload:137` → `SharedContext.reload`
(`SharedContext:156`) → `indexManager.reload(database)` (`:160`) — the same
killer caller C1 established, one statement further down. A reader session hits
it routinely right after a schema commit added global properties, i.e. exactly
during a multi-tx migration.

**Interleaving (lost update).** Session B: `SharedContext.reload` finishes the
schema half (serialized against the commit by the F52 schema lock), then
`indexManager.reload`: takes the index write lock, `loadEntity` (`:91`) reads
the manager record under `stateLock.read` **before** the schema commit (A)
takes `stateLock.write` — old bytes. B's `load()` rebuild is pure in-memory
under the index lock only. A meanwhile holds D7 mutex + schema lock +
`stateLock.write` — a fully **disjoint** lock set — commits, and publishes the
overlay via CHM puts. B's clear+rebuild from stale bytes runs concurrently
with (or completes after) A's publication: the committed index is erased from
`indexes`/`classPropertyIndex`, or a tx-dropped index is resurrected. The next
snapshot rebuild caches the clobbered set, `ClassIndexManager` stops enqueuing
entries for the erased index (the F32/F35 silent-untracked failure), and every
subsequent data commit durably corrupts the index until the next reload.

**The naive fix deadlocks.** Making publication take the index-manager write
lock while holding `stateLock.write` is the C1 ABBA shape on the second lock:
commit `stateLock.write → indexLock.write` vs reload `indexLock.write →
stateLock.read` (`IndexManagerEmbedded.reload:89` → `:91`). Same writer-
preference parking, same data-path trigger.

**Affected:** F52, F60, D15, D19, D7, F44.

**Resolution direction.** The index-manager lock joins the global order as a
fourth member: **D7 mutex → `SchemaShared.lock` → `IndexManagerEmbedded.lock`
→ `stateLock`**. The schema-carrying commit acquires the index write lock
after the schema lock and before `stateLock.writeLock()`, and holds it through
overlay publication and the trailing `forceSnapshot`; publication then mutates
the maps under the lock (the CHM-put discipline stays for reader safety).
`reload`/`load` already conform (index lock → `stateLock.read`), and no path
takes the index lock before the schema lock (`releaseExclusiveLock:208` calls
`forceSnapshot`, which takes only `snapshotLock`; the snapshot rebuild reads
the manager lock-free per F44). State the four-lock order in D7/D19.

## C9: The per-class and per-property proxies bind the shared impl in a `final` delegate — the canonical `getClass` → mutate idiom routes the tx's first schema write into the SHARED schema, and `getImplementation()` leaks shared class objects into the tx-local graph [BLOCKER]

F56 moved the D7 engage to "the `SchemaProxy` / index-routing layer", and D8
says proxy read methods route to the tx-local structure during a schema-tx.
Both statements assume the proxy layer re-resolves per call. It does not:

- `SchemaProxy.getClass` wraps the resolved impl in a **new
  `SchemaClassProxy`** (`SchemaProxy:218`–`:219`), and `SchemaClassProxy
  extends ProxedResource<SchemaClassImpl>` whose `delegate` is **`final`**
  (`ProxedResource:30`) — the same code fact F52 used to foreclose
  instance-swap, now one level down.
- Every class-level mutation delegates to that captured impl:
  `createProperty` (`SchemaClassProxy:56`–`:60`), `set` (`:152`), `setName`
  (`:296`), `addSuperClass` (`:276`–`:279`), `setAbstract` (`:220`).
  `SchemaPropertyProxy` mirrors the shape.

**The broken idiom is the canonical one, not an exotic stale handle.** D8
seeds the tx-local copy on the tx's *first write*. The standard DDL shape is
`var cls = schema.getClass("Foo"); cls.createProperty(...)` — `getClass` runs
*before* any write, so no tx-local structure exists yet and the proxy captures
the **shared** `SchemaClassImpl`. The subsequent `createProperty` is the first
write, flows through the stale proxy, and lands on the shared impl:
`owner.acquireSchemaWriteLock` on the **shared** `SchemaShared`, shared maps
mutated mid-tx. Consequences: an uncommitted schema change visible to every
session (D4 violation), unrecoverable on rollback, absent from the tx-local
changed-class set (not persisted at commit), and the F43 diff's "old" side
polluted mid-tx. If the engage hook is read literally as `SchemaProxy`'s
methods only, the D7 mutex never engages either — two sessions can do this
concurrently. Even with hooks on the class-proxy methods, the hook cannot fix
where the captured object points.

**The argument-unwrapping facet re-opens F41.** `SchemaProxy` unwraps
caller-supplied classes via `getImplementation()`
(`SchemaProxy:93`/`:104`/`:116`/`:127`) and passes the raw impl as superclass
into `createClass`. A pre-seed proxy unwraps to the **shared** impl; handed to
the tx-local `createClass`, the tx-local class's `superClasses` list points
into the shared graph, and the polymorphic ripple
(`addPolymorphicCollectionId`, `SchemaClassEmbedded:631`/`:644`) walks into
and mutates **committed** class objects — exactly the cross-graph corruption
F41 fixed for the seed, re-introduced through the API boundary.

**Affected:** F56, D7 (engage-point bullet), D8 (routing note), F41, F44.

**Resolution direction.** The routing seam must be per-call, not per-object:
`SchemaClassProxy`/`SchemaPropertyProxy` re-resolve their target by name
against the session's current write-view on every mutating call (and engage
the mutex there), instead of delegating to a captured impl; impl-typed
arguments are re-resolved by name on the tx-local side before linking. State
in D8 that class/property proxies are name-binding, not instance-binding,
during a schema tx — the `final` delegate as it stands cannot carry the D8
routing story.

## C10: `deleteRecord`'s eager callback flush freezes the index-entry enqueue against the pre-`createIndex` index set — records flushed before a later same-tx `createIndex` are never tracked for the new index; the commit builds a durably wrong index [BLOCKER]

The spine's model (F32: "on every record write, `ClassIndexManager`
resolves...") has the enqueue timing wrong, and D12's "each key counted once"
accounting inherits the error. PSI-verified call chain: `ClassIndexManager`'s
enqueue (`checkIndexesAfterCreate/Update/Delete`) is called only from
`FrontendTransactionImpl.preProcessRecordOperationAndExecuteBeforeCallbacks`
(`:873`/`:878`/`:884`/`:910`), which runs from
`preProcessRecordsAndExecuteCallCallbacks` at exactly two production points:

- the outermost commit (`commitInternalImpl:232`) — so for most txs the
  enqueue *is* commit-time and sees the final overlay set (F35), and the
  insert/update-before-createIndex attack fails;
- **every `deleteRecord` call** (`:483`, comment at `:482`: "execute it here
  because after this operation record will be unloaded") — and this flush
  processes **the whole pending queue**, not just the deleted record
  (`:767`–`:811`).

Processed operations are removed from `operationsBetweenCallbacks` (`:782`)
and re-processed at commit only if re-dirtied (`:775` filter; counter pinned
at `:926`, track data cleared at `:923`). The index set is resolved at flush
time via `cls.getRawIndexes()` (`ClassIndexManager:58`/`:78`), i.e. the
snapshot of that moment.

**Interleaving 1 — delete, then createIndex (single tx, no concurrency
needed).** Tx deletes a committed record `r` of class `Foo`
(`checkIndexesAfterDelete` enqueues removes for the *then-existing* indexes
only), then runs `createIndex(Foo.x)`, then commits. At commit, D12/F54's
population scan reads the **committed** collection — `r` is still there (its
delete applies later, in the record-write loop) — and `doPut`s `r`'s key into
the new engine. `commitIndexes` has no remove for the (`r`, new-index) pair.
Result: a **dangling entry** (key → deleted RID) durably in the new index;
for a UNIQUE index the phantom key blocks future inserts of that value with a
spurious `RecordDuplicatedException`. The delete-bad-rows-then-add-UNIQUE-index
migration — the exact YTDB-382 workload — hits the loud half of this at its
own commit (population's key collides with the tracked re-insert) and the
silent half when nothing re-inserts.

**Interleaving 2 — insert, delete anything, createIndex.** Tx inserts `r`
(class `Foo`), deletes some *unrelated* record (the flush at `:483` processes
`r`'s pending CREATE: enqueue against the pre-createIndex set — nothing for
the future index), then `createIndex(Foo.x)`, commits. `r` is not committed
(population scan misses it) and not tracked for the new index (flushed
early, not re-dirtied, so the `:775` filter skips it at commit). Result: `r`
**missing** from the new index, and — for a UNIQUE index — `r`'s key never
validated.

Today both shapes are unreachable: `createIndex` inside a tx is forbidden
(F21). The design de-guards it, so this is design-created, and no entry covers
it: F20/F32/F35 analyze tracking for ops *after* the index exists in the
overlay; D12's "each key counted once; build scan precedes the record-write
loop" assumes all tx rows are tracked against the final index set.

**Affected:** D12, F54, F32 (timing model), F35, F20, D3.

**Resolution direction.** D12 gains a completeness invariant: for every
tx-created index, the commit must account for **all** of the tx's record
operations, not just those enqueued after the createIndex. Mechanically:
at commit, for each tx-created index, re-derive index entries from the full
record-operation set (the tx already holds it) instead of trusting the
incrementally-flushed `indexEntries`; or have the population phase consult the
tx's record-operation set to skip tx-deleted RIDs and add tx-created ones.
Either way, state that `deleteRecord`'s eager flush freezes enqueue state and
is therefore not a sufficient tracking source once mid-tx index DDL exists.
Regression tests: delete-then-createIndex-then-commit (dangling entry), and
insert-delete-other-createIndex-commit (missing entry / unvalidated unique).

## C11: D8's promotion has the same final-owner object-identity trap F41 fixed for the seed — adopting tx-local class objects into the shared schema silently destroys mutual exclusion [MAJOR]

F41 pinned the **seed** direction (committed → tx-local) to `fromStream`
re-parse because `SchemaClassImpl.owner` is `final` and the class graph is
object references. The **promotion** direction (tx-local → shared, D8 "the
tx-local structure is promoted to the shared `SchemaShared`") has the same
constraint and no specified mechanism. F52 fixed *which lock* guards
promotion; nothing says *which objects* end up in the shared maps:

- **Adopting tx-local objects** (`classes.putAll(txLocal.classes)` or
  per-entry puts of the tx-local `SchemaClassImpl`s) installs objects whose
  `final owner` is the **tx-local** `SchemaShared`. Every future mutation
  through them takes `owner.acquireSchemaWriteLock` on the *tx-local*
  instance's lock (`SchemaClassImpl:1169` → `SchemaShared:414`) — a different
  `ReentrantReadWriteLock` than the one readers and the next commit use. No
  mutual exclusion, no error, intermittent map tearing under the next
  concurrent DDL + read. Partial adoption (changed classes only) is worse:
  superclass/subclass references then span two owner graphs and the
  polymorphic ripple mutates across both.
- **Field-copy into the existing shared instances** must re-wire
  `superClasses`/`subclasses` object references for every changed class onto
  shared-owned instances and reconstruct new classes via
  `createClassInstance` bound to the shared owner — i.e. exactly a
  fromStream-shaped reconstruction, which no entry requires.

An implementer who satisfied F41 for the seed has no instruction stopping the
adoption shortcut for promotion; it type-checks and passes single-threaded
tests.

**Affected:** D8 (promotion sentence), F41, F45 (RID carry has the same
both-directions shape), F52.

**Resolution direction.** State the promotion mechanism in D8, mirroring
F41: promotion reconstructs into the **shared** instance (fresh
shared-owner-bound class objects built from the tx-local in-memory state, or
a reload-style `fromStream` of the just-committed records), never adopts
tx-local objects; existing shared instances are updated field-wise with graph
references re-wired to shared-owned objects; the per-class record RIDs (F45)
ride the same reconstruction. All under the F52 lock scope already specified.

## C12: The commit-side publication never fires `MetadataUpdateListener` — and D14 breaks the only existing schema trigger — so the YQL/GQL execution-plan caches serve stale plans indefinitely after a schema commit [MAJOR]

Plan caches invalidate **only** via listeners: `YqlExecutionPlanCache.get`
does no schema-version check (`YqlExecutionPlanCache:119`–`:139`);
invalidation comes from `onSchemaUpdate`/`onIndexManagerUpdate`/
`onStorageConfigurationUpdate` (`:149`–`:173`; registered at
`SharedContext:91`/`:98`). Today's triggers:

- **Schema:** the post-commit dispatch fires only when the tx's record set
  contains **the** schema record — `record.getIdentity().equals(schemaId)`
  (`DatabaseSessionEmbedded:1620`–`:1627`). Under D14 most DDL writes only
  per-class records: class rename, `addSuperClass`, property-attribute
  changes (`setMandatory` etc.) touch neither the link set nor the F59 root
  payload, so the root record is *correctly* not written — and the trigger
  never matches. Per-class records are internal entities with no class, so
  they fall into the `else` branch and match nothing.
- **Index manager:** `onIndexManagerUpdate` fires only from
  `releaseExclusiveLock` at write-nesting zero
  (`IndexManagerEmbedded:211`–`:221`). Under D15, mid-tx index DDL routes to
  the tx-local overlay and the commit publishes via CHM puts — the shared
  lock's release path never runs with `notifyChanges` for the committing tx.

Net: after a committed class rename or index drop, every session keeps
serving cached execution plans bound to the old schema — plans referencing a
dropped index resolve a deleted engine (`InvalidIndexEngineIdException` at
query time), plans for a renamed/dropped class target stale collection sets,
and new indexes are never picked up (silent de-acceleration) — until some
unrelated DDL happens to write the root record. The decision log's
publication story (D8/D15/F62) covers maps and snapshot but never mentions
the listener fan-out; F59's root-dirtiness rule, correct for durability,
*reduces* root writes and thereby starves the only existing trigger further.

**Affected:** D8, D14, D15, F59, F62.

**Resolution direction.** Add listener dispatch to the commit-side
publication step: a schema-carrying commit fires `onSchemaUpdate` and (when
the changed-index set is non-empty) `onIndexManagerUpdate` once, after the
trailing `forceSnapshot` — and after the F52 locks are released, since
listener code is arbitrary (today's dispatch at
`DatabaseSessionEmbedded:1624` also runs post-commit, lock-free). The
schema-record identity check at `:1622` is dead under D14 and is replaced by
the schema-carrying signal D19 already computes.

## C13: A concurrent pure-data commit whose enqueue phase overlaps the schema commit misses the new index — pre-existing window, narrowed but not closed; D12's completeness story should say so [minor]

A data tx B resolves its index set in the session layer, **outside** any
storage lock (`commitInternalImpl:232` → `cls.getRawIndexes()` via the
shared cached snapshot), then parks at `stateLock.read` (`:2285`). A schema
commit A (createIndex on B's class) that interleaves between those two points
populates the engine from **committed** data (B's rows are not committed) and
publishes; B then writes its rows with no tracked entries for the new index —
rows durably missing from the index, uniqueness unvalidated. D19's
`stateLock.write` excludes B's storage phase, not B's enqueue phase; nothing
re-validates the index set inside the lock.

This window exists today with the same shape (enqueue before
`createIndex`'s publication at `IndexManagerEmbedded:362`–`:364`, commit
after `fillIndex`'s scan passes the rows, `:375`), so it is pre-existing, and
the design narrows it (population can no longer interleave with B's record
writes). Recorded because D12/F54's correctness argument ("population covers
committed rows, tracked changes cover tx rows") silently assumes an exclusion
that holds for neither today's code nor the design.

**Affected:** D12, D13, F54.

**Resolution direction.** State the residual window in D12 and pick a v1
stance: accept-and-document (matches today), or close it by having the
schema-carrying commit re-check, under `stateLock.write`, for in-flight
commits that resolved their index sets before publication (e.g. a cheap
epoch/version stamp on the snapshot validated inside `doCommit`, retrying the
enqueue on mismatch).

## C14: F61's accepted resolution under-specifies the timed acquire — a timeout that aborts violates D5 for legitimately long schema commits, and the "release on the owning thread where possible" clause cannot recover a dead owner with a `ReentrantLock` [minor]

D7 (amended per F61) prescribes "a timed/interruptible acquire with a
diagnostic naming the holder, never a bare `lock()`". Two gaps:

- **Timeout semantics vs D5.** D5: "schema-tx rollback due to contention is
  not acceptable". An F48-scale commit legitimately holds the mutex for
  minutes; a second schema tx whose timed acquire *throws* on timeout is
  aborted by contention against a healthy holder — a D5 violation, not a
  stranded-holder diagnosis. The entry must say: timeout → emit the
  diagnostic and **re-wait in a loop**, never abort; only an operator-level
  interrupt breaks the wait.
- **Dead-owner recovery is not possible as specified.** A `ReentrantLock`
  held by a dead thread cannot be released by any other thread —
  `unlock()` from the reaper throws `IllegalMonitorStateException` (the F38
  guard exists precisely because of this). "Runs rollback's release on the
  owning thread where possible" therefore covers only the graceful-close
  case; after owner-thread death the mutex is stranded until process restart,
  with diagnosis but no recovery. If reap-recovery is wanted, the mutex must
  be an owner-tracked, cross-thread-releasable primitive (e.g. a
  `Semaphore(1)` with explicit owner bookkeeping) — and then the F38
  same-thread assertion moves into that bookkeeping. If it is not wanted,
  D7 should say "stranded = schema DDL unavailable until restart" explicitly.

**Affected:** D5, D7, F38, F61.

## C15: D7 says genesis never engages the mutex; D18 says the genesis schema tx acquires it — one of them misleads the implementer into breaking the other's seeding story [minor]

D7's engage bullet: "Engage only inside an active user tx (load/reload/
**genesis** paths never engage)". D18: "The schema tx **acquires the D7
mutex** (no contention at genesis, F31)", and D8/D18 require the genesis
schema tx to seed the tx-local copy and commit through the normal diff path.
Under D18 genesis *is* an active user tx (`SharedContext.create` runs
`schema.createClass` for `V`/`E` at `SharedContext:185`–`:187`, to be wrapped
in explicit txs per F31), so the F44-era parenthetical is stale: an
implementer who special-cases genesis out of engage-and-seed gets genesis
mutations applied directly to the shared empty schema with no tx-local copy —
then D18's commit has no tx-local structure to diff and nothing to promote.
Harmless concurrency-wise (genesis is single-threaded, and the
`SharedContext.lock` → mutex ordering it creates cannot cycle because no
user tx can run during `create`), but the engage rule is load-bearing for
D18's seeding. Fix the D7 parenthetical to "load/reload never engage; genesis
engages through its explicit D18 transactions".

**Affected:** D7, D18, F31, F44.

---

## Attacks run that produced no new finding

- **`forceSnapshot`/`snapshotLock` ordering inside the F52 lock scope.**
  `makeSnapshot` orders schema read lock → `snapshotLock`
  (`SchemaShared:198` → `:200`); `forceSnapshot` takes only `snapshotLock`
  (`:223`) and already runs under the held schema write lock today
  (`saveInternal:828` inside `releaseSchemaWriteLock`, unlock at `:446`).
  The trailing F62 `forceSnapshot` under the F52 scope reuses a pre-existing,
  consistent order. No inversion.
- **Storage code under `stateLock` acquiring the schema lock (F52 acyclicity
  audit).** `AbstractStorage` touches the metadata layer only at `:2235`
  (`makeThreadLocalSchemaSnapshot`, before `stateLock` at `:2285`) and
  `:2429` (clear, after release). The snapshot pin may take the schema read
  lock — schema-before-state, conforming. No reverse edge exists in current
  code; the only new reverse edge the design adds is C8's index-lock case.
- **F53 commit-local resolution read sites.** `lockIndexes`/`commitIndexes`/
  `doGetAndCheckCollection` reading the shared registries are exactly the
  acknowledged first-implementation-step PSI audit in F53; all are
  same-thread within the commit and coverable by commit-local references. No
  cross-thread reader of the unpublished structures exists: registry readers
  either hold `stateLock.read` (excluded by the commit's write lock) or are
  the pre-lock COW-list phase already dry-listed in pass 5 as pre-existing.
- **F62 stale pinned snapshots after the single trailing `forceSnapshot`.**
  Pin scopes are per-read/per-commit (pass-5 verified); unpinned reads route
  through `makeSnapshot`, whose cache the trailing `forceSnapshot`
  invalidates. A data commit pinned to the old snapshot that parks behind the
  schema commit fails loudly at `doGetAndCheckCollection` if its target was
  dropped — same as today's drop race. The only substantive residue is C13
  (enqueue-phase overlap), recorded separately.
- **F60 stale `Index`-object references across replacement publication.**
  Per-tx key tracking captures the `Index` object, but commit-apply resolves
  the engine by `indexId` (unchanged for rename/membership publication) and
  drop+recreate resolves via `doReloadIndexEngine` by name — both shapes
  pre-existing and dry-listed in pass 5. `interpretTxKeyChanges` reads no
  definition field the rename mutates.
- **F54 same-tx inserts/updates before `createIndex` (no mid-tx flush).**
  Enqueue for creates/updates is commit-time (`commitInternalImpl:232`,
  before `doCommit`), resolved against the final overlay snapshot (F35), so
  population + tracked changes compose correctly for the flush-free case.
  Only the `deleteRecord`-flush variants break — that is C10.
- **`SharedContext.lock` cycles.** Reload orders `SharedContext.lock` →
  schema lock → index lock → `stateLock.read` (each metadata lock released
  before the next), conforming. The reverse edge (D7 mutex →
  `SharedContext.lock` via a schema tx's own mid-tx `metadata.reload()` at
  `EntityImpl:4173`) cannot cycle: the only path taking `SharedContext.lock`
  before the mutex is genesis `create`, which cannot run concurrently with
  user txs. Noted inside C15.
- **A second schema tx parked on the D7 mutex under the F56 fix.** With the
  proxy-layer engage, tx2 parks holding no shared lock — the C4 freeze/
  deadlock is gone, provided C9's per-call routing closes the class-proxy
  bypass.
