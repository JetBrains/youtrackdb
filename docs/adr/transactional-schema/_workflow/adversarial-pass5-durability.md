# Adversarial pass 5 — durability and crash safety (2026-06-10)

Verdict: 7 new findings — 1 BLOCKER, 5 MAJOR, 1 minor. The BLOCKER is a hole in
the design's foundation claim (F16 "replay cleanly"): recovery of a
file-creating atomic unit aborts when a crash lands between the WAL flush of
the unit and the completion of its physical apply phase, and the design widens
that window from sub-millisecond to seconds at the F48 scale. All code evidence
is from the live tree; symbol questions were resolved through PSI
(`flushPendingOperations` caller set), the rest by direct reads of the WAL,
cache, and commit machinery.

Method note: every finding asks "the process dies at instruction boundary X;
what does recovery see?" against the real replay path
(`AbstractStorage.restoreFrom:5512` → `restoreAtomicUnit:5620`), not against
the design's description of it.

---

## U1: Replay of a file-creating atomic unit aborts mid-restore — page-op records precede FileCreatedWALRecord and booked file ids are not durable [BLOCKER]

**Crash scenario.** The schema-carrying commit (D1/D3/D19) creates collections
and engines inside the transaction's atomic operation. WAL record order inside
that unit is fixed by the machinery:

1. Page-operation records are flushed to the WAL **during** the operation, at
   every component-operation boundary
   (`AtomicOperationsManager.executeInsideComponentOperation:195`,
   `calculateInsideComponentOperation:228` call
   `atomicOperation.flushPendingOperations()`; PSI caller set confirmed). Even
   the leftovers are flushed at the top of `commitChanges`
   (`AtomicOperationBinaryTracking:979`) — **before** any file record.
2. `FileDeletedWALRecord` / `FileCreatedWALRecord` are logged only inside
   `commitChanges` (`:993`, `:1006`), after all page ops.
3. `AtomicUnitEndRecord` (`:1067`), then the **physical** phase:
   `readCache.addFile` per new file, page application to cache
   (`:1088`–`:1177`).

So within one unit the order is `[Start, PageOps…, FileDeleted…, FileCreated…,
End]`, and the physical file creation runs **after** the end record is logged.
The WAL background flusher (`CASDiskWriteAheadLog:311`–`313`, fixed-delay
`RecordsWriter`) makes the end record durable independently of the physical
phase. Kill the process while the physical phase is still running (for the F48
batch: ~24,800 `addFile` calls, each with an fsynced name-id append — a
multi-second phase) and the WAL holds a complete unit whose files partially or
wholly do not exist.

**What recovery sees.** `restoreAtomicUnit` applies the unit's records in list
order, so a `PageOperation`/`UpdatePageRecord` for a never-created file runs
**before** that file's `FileCreatedWALRecord`. The branch checks
`writeCache.exists(fileId)` (`AbstractStorage:5657`, `:5731`) — false, because
`WOWCache.exists(long)` consults the live `files` map (`WOWCache:1195`–`1207`)
— then calls `writeCache.restoreFileById(fileId)` (`:5658`, `:5732`).
`restoreFileById` scans the name-id map for a negative (booked) entry
(`WOWCache:2397`–`2414`), but a booking is **never persisted**:
`bookFileId` writes only the in-memory maps (`WOWCache:732`–`757`);
`writeNameIdEntry` for a negative id happens only on **delete**
(`WOWCache:1965`). After a restart the booking is gone, `restoreFileById`
returns null, and the branch throws `StorageException` ("the rest of operations
can not be restored", `:5661`, `:5735`). `StorageException` is a
`RuntimeException` (`BaseException:28`), so `restoreFrom`'s catch (`:5602`)
logs "Data restore was paused" and **abandons the entire remaining restore**:
the half-applied unit stays half-applied (pages of already-created files were
restored before the throw), and every later committed unit in the WAL is
silently discarded.

**Why this is the design's problem.** The window exists today for every
`addCollection`/`addIndexEngine` (same code path), but it is microseconds wide
and per-4-file unit. The design (a) stakes its whole structural-rollback story
on F16's claim that recovery of file-creating units is "all-or-nothing /
replays cleanly" — that claim is false in this window; (b) moves **all** file
creation into exactly this pattern; and (c) concentrates ~24,800 file creates
into one unit (F48) whose physical phase takes seconds, during which the
background flusher is guaranteed to have made the end record durable. The D20
migration import — the recommended upgrade path — is the most likely victim: a
crash mid-import's schema commit leaves the fresh database in a state where the
restore aborts and the partially-applied schema unit plus any later data units
are lost.

**Evidence.** `AtomicOperationBinaryTracking.commitChanges:979/993/1006/1067`
(record order), `AtomicOperationsManager:195/:228` (mid-op page-op flush, PSI),
`AbstractStorage.restoreAtomicUnit:5649`–`5749` (apply order +
abort-on-missing-file), `restoreFrom:5602`–`5609` (abort swallows the rest),
`WOWCache.bookFileId:732`/`restoreFileById:2397`/`deleteFile:1965`/`exists:1195`,
`CASDiskWriteAheadLog:311`–`313` (background flush).

**Affected entries.** F16, D10 (both claim clean replay), F48/D12/D20 (scale
the exposure), D3 (reconciliation is what plants the file creates in the commit
unit).

**Resolution direction.** Fix the replay, not the design shape: make
`restoreAtomicUnit` two-pass (process `FileCreatedWALRecord`s for the unit
before page records — the needed name+id is already in the buffered unit), or
persist booked name-id entries at booking time, or teach `restoreFileById` to
consult the unit's pending `FileCreatedWALRecord`s. Any of these restores F16's
all-or-nothing property; the plan must carry one of them as a prerequisite
track, with a kill-mid-physical-phase recovery test at batch scale.

---

## U2: The single commit-time atomic unit must fit in heap twice — once forward, once at recovery; unbounded for D12's populated-class index build [MAJOR]

**Crash scenario.** Forward path: every page touched by the atomic operation
keeps its change tree in heap until `commitChanges` applies it
(`pageChangesMap` → `durablePage.restoreChanges(filePageChanges.changes)`,
`AtomicOperationBinaryTracking:1159`); nothing is released early. Recovery
path: `restoreFrom` buffers the **entire unit** as a `List<WALRecord>` —
including every page delta — and applies it only when the end record arrives
(`AbstractStorage:5521`–`5522`, `:5549`, `:5553`, `:5538`–`5544`). So a crash
*after* a successful giant commit (end record durable, pages not yet flushed
from cache) forces the next open to materialize the whole unit in heap before
applying a single page. If recovery OOMs, the database cannot be opened until
the operator raises the heap — and the crash-restart-crash loop looks like an
unopenable database.

**Where the log undersells it.** F48's memory bullet bounds the *empty-class*
batch ("tens of MB") and only on the forward path. It never covers (a) the
recovery-side full-unit buffering, which doubles the requirement at the worst
possible time, and (b) D12's accepted case of an index build over an
**already-populated** class, where the unit size is O(all B-tree pages written
by the population scan) — gigabytes for a large class, with no documented
bound. WAL disk retention compounds it: the unit's segments cannot be cut for
the whole population (full checkpoint refuses while an operation is in
progress, `flushAllData:4509`–`4513`), so WAL disk usage also grows by O(unit).

**Evidence.** `AtomicOperationBinaryTracking:1159` (heap-resident change trees
applied at commit), `AbstractStorage.restoreFrom:5521/5549/5553/5538-5544`
(full-unit buffering at recovery), `flushAllData:4509`–`4513` (no cut during
op).

**Affected entries.** F48 (incomplete memory analysis), D12 (populated-class
build accepted with no size bound), F22.

**Resolution direction.** State a hard bound: v1 documents a per-tx limit on
buffered structural work and scopes the commit-time eager build to classes
whose population fits the bound (or to empty classes, pushing populated-class
builds to YTDB-1064 explicitly). Add the recovery-side memory requirement to
F48's envelope so the plan sizes recovery heap = forward heap.

---

## U3: D8's stated commit ordering serializes provisional collection ids into the durable per-class records; the corruption is invisible until restart [MAJOR]

**Crash/restart scenario.** No crash needed — a clean restart exposes it. D8's
commit narrative reads: "for each changed class, `toStream` writes its own
record (D14); the diff (D6) over those changed records derives the structural
delta, reconciliation (D1, D3) applies it, then the tx-local structure is
promoted…". Taken literally, the per-class record **bytes** are produced before
reconciliation has created the real collections, so a new class's record is
serialized with its provisional ids (≤ −2, D2/F42). The atomic unit commits
flawlessly; the in-memory promotion is patched provisional→real (F42), so the
running process behaves correctly. The damage surfaces only at the next open:
`fromStream` rebuilds the class with negative collection ids, every in-memory
map skips negatives (`SchemaShared.addCollectionClassMap:871`), and the class
permanently loses its collections — the physical collections exist on disk but
no class references them. WAL recovery preserves the bad bytes faithfully; this
is durable, silent schema corruption.

**Why the machinery does not save you automatically.** The actual byte
production happens in `commitEntry`, which runs in the record-write loop
**after** the position-allocation loop (`AbstractStorage:2361`–`2368`), and D3
puts reconciliation before that loop — so the correct ordering is *achievable*
with the existing machinery. But D2's commit-time patch list names only the
in-memory structures ("the class metadata's collection-id list and the RIDs of
records", plus F42's reverse map); no D/F entry says the **record property
values** (the `EntityImpl` the class serializes into) must be re-pointed to the
real ids before `commitEntry` serializes them. D8's sentence orders `toStream`
*before* reconciliation, which an implementer can satisfy by serializing early
(e.g., to feed the diff) — producing exactly the bad bytes.

**Evidence.** `AbstractStorage.commit:2299`–`2368` (positions loop, then
`commitEntry` serialization), `SchemaShared.addCollectionClassMap:871`
(negative skip at load), D8's "At commit" bullet (decision-log lines
1423–1429), D2's patch list.

**Affected entries.** D2, D3, D8, D14, F42.

**Resolution direction.** Add the missing invariant to D2/D8: provisional→real
resolution must land in the per-class record **properties** before the commit
serializes them (i.e., reconcile → patch the class-record entities → serialize
in `commitEntry`); the structural diff must be computed from the in-memory
structures (F43), never from pre-serialized bytes. A restart-after-DDL
round-trip test (create class + insert + commit + reopen) catches it cheaply.

---

## U4: The root schema record's non-link-set payload (globalProperties, collectionCounter, blobCollections) has no write-trigger under D8/D14's "never the full schema" rule [MAJOR]

**Restart scenario.** Under D14 the root schema record still carries monolithic
state besides the per-class link set: the global-property table
(`SchemaShared.toStream:659`–`665`), the artificial-name counter
(`"collectionCounter"`, `:666`), and `blobCollections` (`:667`-ish). Today
every schema save rewrites the whole record, so these are always fresh. D8
says "at commit only the class records in the changed-class set are written
(D14), never the full schema", and the only stated root-record trigger is the
link-set delta (F37/F43, class create/drop). Two committed-but-stale cases fall
out:

- **Property create on an existing class** (the most common DDL): allocates a
  global property (`findOrCreateGlobalProperty:805`) and rewrites the class
  record, which references it by id only
  (`SchemaPropertyImpl.toStream:664` writes `"globalId"`). No link-set change →
  root record not in the write set → committed global table is stale. On the
  next open, `fromStream` resolves `globalRef =
  owner.owner.getGlobalPropertyById(globalId)`
  (`SchemaPropertyImpl:562`–`564`), which is `@Nullable`
  (`SchemaShared:758`) — the property loads with a null `globalRef` and the
  first touch NPEs (`getName:88`, `getType:115`). A committed property is
  durably broken after restart.
- **Collection add without class create** (alter-add-collection, D11/F46 path):
  bumps `collectionCounter` (`nextCollectionIndex:835`–`838`) but changes no
  link set. After restart `fromStream` reads the stale persisted counter
  (`:611`–`613`; the recompute fallback `:616` fires only when the property is
  absent), and the next generated collection name collides with the collection
  committed before the restart. The colliding DDL fails in a loop: each attempt
  mutates only the tx-local copy (D8), the failure rolls it back, and the
  shared counter never advances past the collision.

**Evidence.** `SchemaShared.toStream:659`–`667`, `fromStream:611`–`616`,
`nextCollectionIndex:835`–`838`, `getGlobalPropertyById:758` (@Nullable),
`SchemaPropertyImpl.fromStream:556`–`570`, `toStream:664`.

**Affected entries.** D8, D11, D14, F37, F43; extends F50 (the same
"monolithic manifest record" asymmetry F50 records for the index manager
exists on the schema root, with worse failure modes).

**Resolution direction.** D14 must enumerate the root record's payload and its
dirtiness rule: the root is written whenever the link set, the global-property
table, the counter, or the blob-collection set changes (the `EntityImpl`
dirty-tracking answer is to actually set those properties on the root entity
in the tx, so D6 covers them for free). Alternatively make the counter
load-derived (always run `initCollectionCounterFromExisting`) and keep only
the global table as a write trigger. Either way, add a
property-create → restart → read round-trip test.

---

## U5: Commit-time rollback after reconciliation has no in-memory undo — a user-caused failure (unique-index violation) poisons the storage's registries while the WAL rolls back the disk [MAJOR]

**Scenario.** D3 runs reconciliation (create collections + engines) early in
the commit; `commitIndexes` — where UNIQUE violations from user data surface
(D13: uniqueness is enforced at commit-apply) — runs much later
(`AbstractStorage:2375`). The WAL side of a late failure is clean: `rollback`
just ends the atomic operation with the error (`:3702`–`3705`), `commitChanges`
is skipped, no physical file ever existed (F16). But reconciliation's
**in-memory** side effects are applied eagerly and have no undo:

- `doAddCollection` registers the collection object in the live `collections`
  array (`registerCollection`, `:5026`, `:4963`) before the operation commits.
- The engine-create body (to be extracted as `doAddIndexEngine`, F39) puts the
  engine into `indexEngineNameMap` and `indexEngines` (`:2811`–`2812`).
- `CollectionBasedStorageConfiguration.updateCollection`/`addIndexEngine`
  (`:5028`, `:2814`) may maintain in-memory config caches alongside the
  rolled-back page writes.

After a rolled-back schema commit, the storage holds live collection/engine
objects whose files were never created (the buffered `FileChanges` were
discarded). Every subsequent operation that resolves them — including the
retry of the same tx — reads poisoned state; the first-null-slot id scan
(`:4990`–`4997`) sees occupied slots; and follow-on **durable** writes (config
rewrites, next collection creates) are computed from that state. Today this
window is negligible: reconciliation-equivalent code runs in small
self-contained operations where `registerCollection` happens after
`collection.create` and almost nothing can fail afterwards. The design inserts
minutes of user-data-dependent work (positions, `commitEntry`, link bags,
`commitIndexes` with uniqueness validation) between registration and the end
of the operation, making rollback-after-registration a **routine** event, not
a freak one.

**Evidence.** `AbstractStorage.rollback:3702`–`3705` (no registry undo),
`doAddCollection:5002`–`5035` (`registerCollection:5026`),
`addIndexEngine` body `:2811`–`2815`, `commitIndexes` at `:2375` after
reconciliation's D3 slot.

**Affected entries.** D3, D10 (its "rollback is free" is the disk half only),
D19, F39 (the extracted `doAdd*` primitives carry these mutations with them).

**Resolution direction.** The plan needs an explicit in-memory undo story for
reconciliation: either defer all registry publication (collections array,
engine maps, config caches) to the post-`commitChanges` success path, or keep
an undo list applied in the rollback path. Deferral matches D8/D15's existing
publish-at-commit discipline and avoids new undo code. Add a test: schema tx +
duplicate key in the same tx → commit fails → retry succeeds → restart →
consistent.

---

## U6: D12's commit-time population scan re-enters `stateLock` and the session read machinery under the held write lock; F39's lock-free extraction covers engines only [MAJOR]

**Scenario.** D12 keeps "populate from committed data" inside the
exclusive-locked commit. Today's population (`fillIndex` → `indexCollection`,
F22) reads committed rows through the session read path, and that path takes
the storage read lock: `readRecord` (`AbstractStorage:2002`) acquires
`stateLock.readLock()` (`:2031`). Under D19 the commit thread already holds
`stateLock.writeLock()`; `ScalableRWLock` is non-reentrant with writer
preference (the F33/F39 citations), so the first row read self-deadlocks the
commit **while the WAL atomic operation is open and the write lock is held** —
the storage stalls for every session until the process is killed, and the kill
lands the U1 recovery path. F22 flagged that population "must be refactored to
accept and reuse the commit's single AtomicOperation" and F39 extracted
lock-free primitives, but F39 names only `doAddIndexEngine`/`doDeleteIndexEngine`
and explicitly closes with "collections do not have this problem" — nobody
audited the population **read** path, which is the third re-entrant site and
the only one that runs per-row.

**Durability facet.** The tempting shortcut — keep today's `fillIndex` shape
(copied session + `executeInTxBatchesInternal`, F22) and call it "at commit" —
is also a durability bug, not just a locking one: each batch commits its own
WAL unit, so the index build becomes durable **independently** of the schema
commit's unit. A crash between the population units and the schema unit's end
record replays a populated engine whose creating schema never committed (or
vice versa after rollback) — exactly the torn state D1 was designed to remove.
The population must write through the commit's own atomic operation and a
lock-free scan primitive; neither exists today.

**Evidence.** `AbstractStorage.readRecord:2002/:2031` (stateLock on the read
path), `IndexAbstract` population via copied session + own batched txs (F22's
PSI-verified citations `:962`–`1008`), `ScalableRWLock` non-reentrancy
(`ScalableRWLock.java:64`–`65`, per F33).

**Affected entries.** D12, D19, F22 (incomplete: names the refactor but not the
read-path lock), F39 (incomplete: engine create/delete only).

**Resolution direction.** Extend F39's resolution to the scan: population at
commit iterates the collection through storage-internal, lock-free primitives
(`StorageCollection` iteration with the commit's `atomicOperation`), bypassing
the session/`readRecord` path entirely, and writes engine entries via `doPut`
on the same atomic operation. State in D12 that population must emit zero
additional WAL units.

---

## U7: D20 import has no completion marker and "offline" is not enforced; a crash mid-import yields a fresh database that opens cleanly but is silently incomplete [minor]

**Scenario.** The migration path is operator-driven export → import into a
fresh database (D20). `DatabaseImport` rebuilds schema and records through the
normal API in many transactions; there is no terminal "import complete" marker
and no mechanism that prevents other clients from using the target database
mid-import. A crash (or operator interruption) mid-import leaves a fresh,
version-correct database that passes every open-time check — the D14 version
gate validates *format*, not *completeness* — and silently misses classes,
indexes, or records. The failure mode is operational, and the old database is
untouched (export only reads), so recovery is "delete and re-import"; but
nothing tells the operator the import was partial, and D20 presents the path
as the upgrade story for every existing production database.

**Evidence.** `DatabaseImport.importSchema:495` (multi-tx API rebuild, per
D20's own citations); no completion-marker write anywhere in the import path;
D20 text (decision-log lines 1814–1856) never states an enforcement or
verification step.

**Affected entries.** D20, D14 (version gate checks format only), F49.

**Resolution direction.** Document the procedure: import into a target that is
not yet exposed to clients, verify (the existing round-trip test machinery, or
a class/index/record-count manifest emitted by export and checked by import),
and only then put the database in service. A manifest check is cheap and turns
"silently incomplete" into "loudly incomplete".

---

## Attacks run that produced no finding

- **Per-class records vs the root schema record torn at crash (D14/F45).**
  All metadata records ride the same commit atomic operation; recovery applies
  the unit all-or-nothing on the end record (`restoreFrom:5538`–`5544`). No
  ordering window exists between them — provided U1 is fixed, since U1 breaks
  the all-or-nothing property itself.
- **Engine files vs `CONFIG_INDEXES` link set torn (F50).** Same single unit;
  same answer. The config write inside the engine-create body uses the passed
  atomic operation (`:2814`–`2815`), not a private one.
- **`doAddCollection` config/component writes escaping the commit unit.**
  Verified: `updateCollection(atomicOperation, …)` and
  `linkCollectionsBTreeManager.createComponent(atomicOperation, …)`
  (`:5028`–`5031`) join the caller's operation.
- **WAL truncation under the long-running commit op.** Full checkpoint refuses
  while any atomic operation is in progress (`flushAllData:4509`–`4513`), and
  segment cuts are bounded by the operations table
  (`AtomicOperationsTable.getSegmentEarliestOperationInProgress:415`,
  `getSegmentEarliestNotPersistedOperation:440`). The begin record cannot be
  cut out from under an open unit. (The retained-segments disk growth is noted
  in U2.)
- **D18 genesis crash between the schema tx and the data tx.** The window
  (classes committed, no users yet) already exists today with finer
  granularity — class creation self-commits per op before the user-insert tx
  (F31). D18 narrows the exposure to one boundary; a crashed create leaves a
  partial database the operator deletes, same as today. No new recovery state.
- **F42 reverse-map (`collectionsToClasses`) re-key durability.** The map is
  in-memory derived state rebuilt at load from the per-class records'
  collection ids; its durable carrier is the class-record bytes, which is
  exactly U3's finding — no separate hole.
- **F24/F45 RID resolution vs index entries at crash.** Lazy
  `getIdentity()` reads and the per-class record writes are all inside the
  unit; a crash either replays everything (end record present) or nothing.
