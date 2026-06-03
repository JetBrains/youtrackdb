# YTDB-382 Transactional Schema — Research Decision Log

Append-only working log for the research phase of YTDB-382 (make schema
changes transactional). Three streams, each appended as we work: the initial
idea, key findings as they surface, and decisions as we make them. A fourth
holding pen tracks open questions so nothing drops on the floor. This file is
a research scratchpad under `_workflow/` and is removed in the Phase 4 cleanup
commit with the rest of the directory; it is not a stamped plan artifact.

Findings are numbered `F<n>`, decisions `D<n>`, open questions `Q<n>` so later
entries can reference earlier ones.

---

## 1. Initial idea

YTDB-382 (corrected description, confirmed by the assignee 2026-06-03): today
YouTrackDB refuses schema updates inside a transaction. Two consequences:

1. Schema-change atomicity is enforced per property, so a migration triggers a
   large number of storage writes and runs slowly.
2. Not being able to run schema changes in a transaction pushes complexity into
   the client-side migration logic.

Target: make the schema fully transactional. Fresh start on the
`transactional-schema` branch — no prior branch to inherit (Andrii Rodionov's
earlier work is dropped).

---

## 2. Key findings

### F1 — The whole schema is a single record
`SchemaShared` holds live state in memory (`classes`, `collectionsToClasses`,
`properties`, `collectionCounter`, `blobCollections`) and serializes all of it
into one `EntityImpl` at `identity` via `toStream` (`SchemaShared.java:644`).
Reads go through a lazily-rebuilt `ImmutableSchema snapshot`, invalidated by
`forceSnapshot` (`SchemaShared.java:218`).

### F2 — Save fires on the outermost write-lock release (root of the perf cost)
Every mutation does `acquireSchemaWriteLock` → mutate maps →
`releaseSchemaWriteLock`. The save fires only when `modificationCounter == 1`,
the outermost release (`SchemaShared.java:423`). A nested composite op saves
once, but each *top-level* op saves once. A 50-property migration is 50
full-schema rewrites, each its own micro-transaction. This is the
"property-level atomicity / many storage writes" the issue describes.

### F3 — `saveInternal` forbids an active user tx and opens its own micro-tx
`SchemaShared.java:817`: throws `SchemaException` if `tx.isActive()`, then runs
`session.executeInTx(t -> toStream(session))` (its own commit), then
`forceSnapshot()`.

### F4 — A second, explicit tx guard on class drop
`dropClass` / `dropClassInternal` (`SchemaEmbedded.java:373,417`) throw
`IllegalStateException("Cannot drop a class inside a transaction")`. Drop also
deletes all records in the class collections, drops its indexes, and drops the
collections.

### F5 — Structural storage ops run in their own atomic operation, outside the user tx
Creating a class allocates physical collections: `createCollections` →
`session.addCollection` → `AbstractStorage.addCollection` (`:1441`) →
`atomicOperationsManager.calculateInsideAtomicOperation(...)` under the storage
state write lock. That is a self-contained, immediately-durable WAL operation,
not part of the user transaction. `dropCollectionInternal` and index
create/drop behave the same way. Consequence: a rolled-back "create class"
leaves an orphaned physical collection unless allocation is deferred to commit
or orphans are reclaimed on abort.

### F6 — Schema is shared across all sessions on a storage (no per-session view)
`SchemaShared` is "shared by all database instances that point to the same
storage." Mutations hit a single shared structure under a RW lock. There is no
per-session transactional view of the schema today, so isolation of
uncommitted schema changes is a net-new capability.

### F7 — Commit already holds the read lock and runs in one atomic operation
`AbstractStorage.commit` (`:2221`) takes `stateLock.readLock()` (`:2285`) and
performs the whole commit inside `frontendTransaction.getAtomicOperation()` —
record-position allocation, `commitIndexes`, etc. Structural ops (e.g.
`addCollection`, `:1444`) instead take `stateLock.writeLock()`. So the
read-lock-on-commit is what allows concurrent commits while excluding
structural change.

### F8 — Storage already exposes `doAddCollection(atomicOperation, …)` primitives
The structural primitives take an `AtomicOperation` parameter (`:1455`,
`:5010`). Today they are invoked inside their own
`calculateInsideAtomicOperation`. At commit they could be driven by the
commit's atomic operation, making collection create/drop atomic with the
record writes and WAL-rolled-back together on crash.

### F9 — At least two metadata records, not one
Schema lives in the schema record (F1). Index definitions live in a separate
index-manager record (`getIndexMgrRecordId` / `setIndexMgrRecordId`,
`indexManagerIdentity` in `IndexManagerEmbedded`). Commit-time reconciliation
must diff both records, not just the schema record.

### F10 — `makeThreadLocalSchemaSnapshot` is a stability snapshot, not isolation
`MetadataDefault:78` pins one `ImmutableSchema` for the duration of a nested
operation via an `immutableCount` counter. It freezes the schema view during
an operation; it does not isolate a session's *uncommitted* schema changes.
Real per-session isolation (F6) is still net-new, but this is the seam where a
tx-local schema view would attach.

### F11 — Storage `stateLock` is a per-storage `ScalableRWLock`
`AbstractStorage:341`. Commit takes `readLock` (`:2285`) so commits run
concurrently; structural ops (`addCollection` and friends) take `writeLock`.
The read/write split is exactly the concurrent-commit-vs-structural-change
boundary D1's commit-time write-lock upgrade rides on.

### F12 — Existing schema/index locks are per-operation, not tx-scoped
`SchemaShared.lock` and `IndexManagerAbstract.lock` are both
`ReentrantReadWriteLock`, acquired and released inside a single operation with
nesting counters (`modificationCounter` / `writeLockNesting`). The index
manager's `acquireExclusiveLock(FrontendTransaction)` (`:188`) takes a tx
parameter but does not use it for lock lifetime; it releases at operation end
(`:201`), not at tx end. A schema lock held from first-mutation to commit (D5)
is therefore a new lock lifetime, not a repurposing of an existing one. Hot
schema reads go through the immutable snapshot (`getImmutableSchemaSnapshot`),
not the lock, so a dedicated schema-writer mutex would not block readers.

### F16 — File create/delete is already WAL-revertible inside an atomic operation
The make-or-break fact for structural rollback, and it is GREEN. On the disk
engine, `AtomicOperationBinaryTracking.addFile`/`deleteFile` perform **no
physical I/O** — `addFile` only books a name→negative-id reservation and buffers
a `FileChanges{isNew}`; `deleteFile` only records the id. All physical action
plus the `FileCreatedWALRecord` / `FileDeletedWALRecord` happen in
`commitChanges`, logged atomically with `AtomicUnitEndRecord`. On rollback,
`AtomicOperationsManager.endAtomicOperation` skips `commitChanges` entirely
(invariant asserted at `:338`–`:341`: "a rollback must perform no physical
write"). Crash recovery (`AbstractStorage.restoreFrom`, `:5512`) is redo-only
and applies a unit only if its end record is present — all-or-nothing. So
collection/index create AND drop can run inside the transaction's atomic
operation (the `commit` pattern, `:2284`) and roll back or replay cleanly with
no orphaned or missing files. The `doAddCollection`/`dropCollectionInternal`
primitives already take an `AtomicOperation` parameter; today they merely wrap
it in their own `calculateInsideAtomicOperation`.

### F17 — `truncateFile` is NOT crash-safe / not WAL-revertible
`AtomicOperationBinaryTracking.commitChanges` flags `truncateFile` as unsafe
(`:1009`–`:1014`); it is the one cache path outside the WAL guarantee. A
"logical truncate" (empty a collection, keep its files) or "clear pages and
re-hand to a new collection" design would need a new WAL-logged
clear-and-reinit operation — file create+delete is safe, file truncate is not.

### F18 — Collection rename renames files OUTSIDE the WAL (not revertible)
`PaginatedCollectionV2.setCollectionName` renames all four files via
`writeCache.renameFile` (`WOWCache:2116`), which mutates the name maps and
renames the OS file directly with no `AtomicOperation` — the only physical
collection mutation that is not crash-revertible. Reached on class rename via
`SchemaClassImpl.renameCollection` (`:1392`, prod caller
`SchemaClassEmbedded.setNameInternal:311`, PSI-confirmed).

### F19 — Physical model and existing reuse
A collection owns four disk-cache files: `.pcl` (data), `.cpm` (position map),
`.fsm` (free-space map), `.dpb` (dirty-page bitset) — `PaginatedCollectionV2`.
An index owns `.cbt` + `.nbt` (+ a histogram file for B-tree engines).
Intra-collection page/position reuse already exists (`.fsm` segment tree +
position map + record GC). Collection ids are already recycled (first-null-slot
scan, `AbstractStorage:4990`). Index page reuse across a *different* index is
infeasible — structural pages are bound to key serializer/types/size at
`create`, and engine types differ; the safe path is delete+create.

### F20 — Index metadata and per-tx index-change tracking are already tx-compatible
The index-manager record holds a link set `CONFIG_INDEXES` ("indexes") of
per-index entity RIDs; both ride the transaction as normal record operations
(`IndexManagerAbstract.addIndexInternalNoLock:212`, `IndexAbstract.save:718`).
Per-tx index changes are keyed by index **name** in
`FrontendTransactionImpl.indexEntries` (a `HashMap<String, …>`, `:102`), and
`FrontendTransactionIndexChanges` captures the `Index` object in memory. The
enqueue path (`IndexOneValue.put` / `addIndexEntry:292`) never touches the
physical engine or `indexId`. So inserting rows into a class indexed by an index
created in the *same* tx records changes without NPE — the engine is read only
at commit-apply time. The user's "new indexes not stored till commit" worry is
not a tracking problem.

### F21 — But commit-apply assumes the engine already exists
`lockIndexes` (`AbstractStorage:2297`) and `commitIndexes` → `doPut`/`doRemove`
(`:2455`, `IndexMultiValues:147`) resolve the engine by `indexId`; a brand-new
index has `indexId == -1` and fails at `lockIndexes` first. So a same-tx new
index can only commit if its engine is created (valid `indexId`) **before**
`lockIndexes`. Also `IndexManagerEmbedded.createIndex` currently forbids an
active tx (`:306`, `"Cannot create a new index inside a transaction"`) — a third
tx guard alongside F3 (`saveInternal`) and F4 (`dropClass`) to rework.

### F22 — Engine-create and population are not commit-driven today
`addIndexEngine` (`AbstractStorage:2738`) opens its own
`calculateInsideAtomicOperation` and takes `stateLock.writeLock()` (`:2752`,
`:2757`) — it takes no `AtomicOperation` parameter. Population (`fillIndex` →
`indexCollection:962`) runs on a **copied session** in its own batched
transactions (`session.copy()` + `executeInTxBatchesInternal:989`-1008),
reading committed rows via `browseCollection`. Neither joins the commit's atomic
operation. Both must be refactored to accept and reuse the commit's single
`AtomicOperation`, and the `stateLock` read-lock-at-commit (`:2285`) vs
write-lock-in-`addIndexEngine` nesting reconciled. Mechanical but non-trivial.

### F23 — The planner selects indexes by definition only, and an engine-less index throws
SQL index selection (`SelectExecutionPlanner.handleClassAsTargetWithIndex` /
`findBestIndexFor`, `:2674`–`:2952`) chooses an index from the schema
definition: it checks `canBeUsedInEqualityOperators()` (hard-coded `true` for
`IndexUnique`/`IndexNotUnique`) and key-condition matching, never `getIndexId()`
or engine existence. So a tx-created index (definition in the tx-local
snapshot, `indexId == -1`) would be selected. Reading it then throws
`IllegalStateException` via `getIndexEngine`→`checkIndexId`
(`AbstractStorage:3077`)→`InvalidIndexEngineIdException`→`doReloadIndexEngine`
(`IndexAbstract:221`)→`loadIndexEngine` returns -1. The throw fires as early as
cost estimation (`IndexSearchDescriptor.cost`→`index.getStatistics`). Loud
failure, not silent wrong results. Two read paths already do the right thing:
(a) an already-built index merges physical results with the tx's pending
`FrontendTransactionIndexChanges` (`IndexOneValue.getRids`/`streamEntries` +
`PureTx*` spliterators); (b) a full class scan overlays tx record changes —
`RecordIteratorCollection` interleaves tx-new records and `executeReadRecord`
checks `isDeletedInTx`/tx `getRecord` before storage
(`DatabaseSessionEmbedded:1099`–`1129`).

### F24 — Index entries already commit the resolved RID (no remap needed)
The temp→persistent RID change at commit is handled correctly today, by
reference + lazy read. Index-change entries store the live `Identifiable` —
either the `EntityImpl` or its `ChangeableRecordId` (`ClassIndexManager:389`,
`:262`); `TransactionIndexEntry.value` keeps that reference, not a copy. The
commit resolves each new record's RID in place during the position-allocation
loop (`AbstractStorage:2300`–`2359`, `setCollectionAndPosition:2350`), then
`commitIndexes` (`:2375`) → `applyTxChanges` reads `op.getValue().getIdentity()`
lazily (`:2482`) — after resolution — so the persistent RID is what gets
written to the index. Extends cleanly to provisional collection ids (D2):
`setCollectionAndPosition` resolves collection id + position together, and the
lazy `getIdentity()` picks up the fully-resolved RID. Invariant to preserve:
RID resolution must run before `commitIndexes`. Orthogonal pre-existing
contract: `applyTxChanges` asserts a RID-valued index *key* is persistent at
apply (`:2479`) — relevant only to link indexes; not introduced here.

### F25 — Index objects are thin storage-backed handles, not in-memory structures
`IndexAbstract` holds `indexId` (`:86`, `-1` until built — a handle into
storage's engine array), a `storage` reference (`:82`), `collectionsToIndex`
(`:89`), the per-index entity RID `identity` (`:92`), and the definition. The
index *data* (BTree entries) lives in the storage engine resolved by `indexId`,
not in the object. `IndexManagerAbstract` holds only two flat in-memory maps —
`indexes` (name → Index, `:49`) and `classPropertyIndex` (class+property →
indexes, `:47`) — plus the manager record RID (`:54`). There is no
self-contained in-memory index content to copy, unlike `SchemaShared`'s class
structures. This is why the index-side tx-local view is a definition overlay,
not a deep copy (D15).

### F15 — A class has no stable on-disk identity; collections do
`SchemaClassImpl.toStream` (`:571`–`:600`) serializes `name`, `description`,
`defaultCollectionId`, `collectionIds`, `properties`, `superClasses`,
`customFields` — no class id or UUID. The on-disk class key is its name. By
contrast, `collectionIds` are stable storage-assigned numeric ids, and the
in-memory `collectionsToClasses` map is keyed by collection id. Abstract
classes carry `collectionIds = {-1}` (`NOT_EXISTENT_COLLECTION_ID`). So the
stable structural identity in the schema is the collection id, not the class
name.

### F14 — Reads are uniformly routed through a per-session immutable snapshot
`MetadataDefault` is per-session (`DatabaseSessionEmbedded:426`) and owns the
`immutableSchema` snapshot plus its `immutableCount` nesting counter.
`SchemaProxy` is also per-session: it wraps the shared `SchemaShared`, pins the
thread-local DB, then delegates. Every data-path class resolution goes through
`getMetadata().getImmutableSchemaSnapshot()` (`DatabaseSessionEmbedded:949`,
`:994`, `:1038`, `:1073`; `EntityImpl:689`, `:737`), and
`SchemaShared.makeSnapshot(session)` (`:194`) builds `new ImmutableSchema(this,
session)` cached in `snapshot`. The per-session `immutableSchema` is the exact
seam where a tx-local schema view attaches: redirect the snapshot source and
the `SchemaProxy` write/read delegation to a tx-local structure while a
schema-changing tx is open, leaving the shared `SchemaShared` at committed
state.

### F13 — Sessions are thread-bound via an `activeSession` ThreadLocal
`assertIfNotActive` (`DatabaseSessionEmbedded:3377`) gates session use on
`activeSession.get()`, a `ThreadLocal<Boolean>`. A session must be activated on
the current thread to be used, so an active transaction is single-threaded in
practice. A thread-owned `ReentrantLock` held across the tx body (D7) is sound.
Caveat: a session detached and re-activated on a different thread mid-tx would
strand the lock on the original thread; v1 assumes the same-thread
begin→commit lifecycle the thread-local already enforces for active use.

---

## 3. Decisions

### D1 — Invert the dependency: metadata-first, storage reconciles at commit
Chosen direction (from the assignee, 2026-06-03). Today storage leads:
create/drop collection/index at the storage level, then reflect it in the
metadata record. Invert it:

- **During the tx:** mutate only the metadata records (schema, index
  definitions). They are ordinary transactional records, so rollback is free —
  no orphaned collections, no in-memory revert problem.
- **At commit:** storage diffs the committed metadata against current structure
  and creates/drops the matching collections/indexes, driven by the commit's
  own atomic operation (F7, F8), so structural changes are atomic with the
  record writes.
- **Locking:** a commit that carries schema changes upgrades from the shared
  `stateLock` read lock to an exclusive write lock (F7), since it now mutates
  storage structure.

Resolves Q2 (allocation is deferred to commit; no eager-allocate-then-reclaim)
and Q4 (architecture supplied). Shapes Q1 toward full transactional semantics.

### D2 — Provisional collection IDs, resolved at commit (mirror temp RIDs)
A new collection gets a provisional (sentinel/negative) collection ID during
the tx, the same way a new record gets a temporary RID today. At commit,
storage creates the real collection, obtains the real ID, and patches every
reference: the class metadata's collection-id list and the RIDs of records
inserted into that collection. The existing temp-RID resolution path is the
template — `ChangeableRecordId.setCollectionAndPosition(...)`
(`AbstractStorage.java:2350`) already rewrites a temp RID to its persistent
form in place at commit, guarded by `assertIdentityChangedAfterCommit`. The
provisional collection ID adds one prior step: resolve
`provisionalCollectionId → realCollectionId` before record-position allocation.

### D3 — Commit ordering: structural reconciliation before record allocation
At commit, create/drop collections and indexes (driven by the commit's atomic
operation) *before* the record-position-allocation loop (`:2300+`). A record
inserted into a new class can only get a position once its collection exists.
Confirmed with the assignee.

### D4 — Isolation is record-local, identical to data-record updates
Schema mutations during a tx change only the tx's copies of the metadata
records (schema record, index-manager record). `SchemaShared` (the shared
in-memory structure) is updated only at commit, when storage applies the
committed metadata. The session sees its own uncommitted schema through the
tx-local record view; other sessions keep seeing the pre-commit schema. Same
isolation model already used for data-record updates. No eager mutation of
`SchemaShared`, so D1's "rollback is free" holds.

### D5 — Single schema-writer enforced by locking, never by rollback
"One schema writer at a time" is enforced *pessimistically*: a tx acquires an
exclusive schema-write lock when it first mutates schema and holds it until tx
end, so a second schema-changing tx blocks rather than racing to a commit-time
conflict. Optimistic concurrency that would abort/roll back a schema-changing
tx on conflict is explicitly rejected by the assignee — schema-tx rollback due
to contention is not acceptable. Locking is acceptable because the schema
update rate is low, so blocking contention is rare.

### D6 — Commit-time delta via the diff approach, built from existing tx tracking
The structural delta is computed by diffing metadata, not by a separate
intent list. `FrontendTransaction` already carries the full set of changed
records (the schema record and index-manager record among them) and the index
operations; `EntityImpl` already tracks per-property changes. Commit reads the
old (persisted) metadata against the new (in-tx) metadata and derives the
collection/index create/drop set from those property-level changes. No new
tx-side bookkeeping. From the assignee, 2026-06-03.

### D7 — A dedicated, transaction-scoped metadata-write mutex
Serialize schema/index-changing txns with a new exclusive lock (a
`ReentrantLock` on the shared context / storage), distinct from `stateLock`,
`SchemaShared.lock`, and `IndexManager.lock`. From the assignee, 2026-06-03.

- **One lock for schema and indexes both** — a class with a unique property
  creates a class and an index in the same tx, so a single metadata-write mutex
  avoids a two-lock ordering problem.
- **Acquire** when a tx first mutates schema or indexes; **release** in the
  `finally` of the outermost `session.commit()` / `session.rollback()`
  (`DatabaseSessionEmbedded:3131` / `:3253`; nested txs counted via
  `amountOfNestedTxs()`). Held across the whole tx body.
- **Does not block** data commits (`stateLock.readLock`) or snapshot-based
  schema reads (F12), so the low-rate → low-contention premise holds.
- **At commit**, structural reconciliation additionally takes
  `stateLock.writeLock()` briefly (D1) to exclude concurrent data commits
  during the physical apply.
- **Lock ordering** is always metadata-mutex → `stateLock.writeLock`; nothing
  takes them in reverse, and a second schema tx blocks on the mutex before
  touching anything else, so it is deadlock-free.
- **Thread assumption** verified in F13 (sessions are thread-bound).
- **Rejected:** holding `stateLock.writeLock` for the whole tx (blocks all
  commits, too coarse); reusing `SchemaShared.lock` for tx lifetime (conflates
  per-op nesting with tx lifetime, still blocks lock-based reads).

### D8 — Tx-local schema view via a per-session copy-on-first-write `SchemaShared`
Approach A. From the assignee, 2026-06-03. When a tx first mutates schema (under
the D7 mutex), seed a tx-local `SchemaShared` from the committed schema. For the
tx duration, `SchemaProxy` routes both writes and snapshot reads to the
tx-local structure, so the session sees its own uncommitted classes while the
shared `SchemaShared` stays at committed state for other sessions (D4).

- **Refinement (2026-06-03): full working structure + explicit changed-class
  set.** The in-memory tx-local view stays a full working `SchemaShared` so
  cross-class derived state (inheritance, `polymorphicCollectionIds`, subclass
  sets, the global-properties table) is maintained correctly by the existing
  mutation methods — a change to one class ripples to its transitive subclasses'
  caches, and the full structure gets that right for free. Alongside it, the
  mutation entry points record which classes were touched in a tx-local
  **changed-class set**. The diff lives at the persistence layer, not in the
  in-memory reads: at commit only the class records in the changed-class set are
  written (D14), never the full schema. So "we don't write the full schema" is
  satisfied by the changed-class set driving the per-class commit, while reads
  use the full working structure.
- **At commit:** for each changed class, `toStream` writes its own record (D14);
  the diff (D6) over those changed records derives the structural delta,
  reconciliation (D1, D3) applies it, then the tx-local structure is promoted to
  the shared `SchemaShared` and `forceSnapshot` invalidates the shared snapshot.
  The commit-time `makeThreadLocalSchemaSnapshot` (`AbstractStorage:2235`) pins
  the tx-local (new) schema so data records inserted into a new class resolve to
  it.
- **At rollback:** discard the tx-local structure and the changed-class set; the
  shared `SchemaShared` was never touched (D4's free rollback).
- **Deferred, not rejected — approach B (in-memory overlay).** An immutable-base
  + changed-class-map overlay would avoid the working copy and inherently know
  the changed set, and D14 removed the old objection (no full-schema rewrite at
  commit). It is deferred because every read then needs overlay-aware resolution
  and must recompute the derived-state ripple closure (a changed superclass
  invalidates all transitive subclasses' cached `polymorphicCollectionIds`) —
  new, error-prone logic in a correctness-critical area. The full working copy
  reuses the existing machinery and sidesteps that risk; the copy is cheap and
  rare (D5). Revisit if transient memory on very large schemas ever matters.
- **Planning notes (not blockers):** seeding the tx-local `SchemaShared` once
  per schema-tx (deep-copy vs `fromStream` re-parse — planning picks);
  `SchemaProxy` read methods (`getClass`, etc.), not only the snapshot, must
  also route to the tx-local structure during a schema-tx, since the schema API
  reads through the proxy. Reuses the existing
  `toStream`/`fromStream`/`makeSnapshot` machinery.

### D15 — Tx-local index-definition overlay (NOT a content copy of the IndexManager)
From the assignee, 2026-06-03. Indexes also need a tx-local view so a session
sees its own uncommitted index create/drop while others do not — but the
mechanism is **not** a deep copy mirroring D8, because indexes are
storage-backed thin handles, not self-contained in-memory structures (F25).
The right model is a lightweight tx-local **overlay of index
definitions/membership**: the effective index set for the tx = committed
indexes + tx-created − tx-dropped. Index *content* (the BTree entries) is never
copied — it stays storage-backed (committed engines) and the tx's own entries
ride the existing per-tx key-entry tracking (F20), merged at read for already-
built indexes. From the assignee correcting an earlier too-hasty "copy the
IndexManager" framing.

- **Why an overlay, not a copy (the asymmetry with D8).** An `Index` object is a
  thin handle: it holds `indexId` (a handle into storage's engine array),
  `storage`, the definition, `collectionsToIndex`, and the per-index entity RID
  — the data lives in the storage engine (F25). Copying Index objects would
  duplicate handles pointing at the *same* shared engines (no isolation), and a
  new index has no engine at all. So there is nothing to deep-copy. The only
  in-memory state to overlay is the index manager's two flat lookup maps:
  `indexes` (name → Index) and `classPropertyIndex` (class+property → indexes).
  No inheritance-style derived-state ripple exists, so the overlay is clean —
  exactly the diff-style approach the overlay was rejected for on the *class*
  side (D8), justified here precisely because indexes lack the connected graph.
- **What the overlay holds.** tx-created index definitions (metadata-only `Index`
  objects, `indexId == -1`, no engine) added to the effective `indexes` /
  `classPropertyIndex`; tx-dropped indexes hidden from them. A tx-local
  **changed-index set** drives the commit.
- **Required coupling — the snapshot reads the index manager.** A class's index
  list in the snapshot is sourced from the index manager:
  `SchemaImmutableClass.getRawClassIndexes` →
  `getSharedContext().getIndexManager().getClassRawIndexes(...)` (`:654`–`657`).
  So during a schema/index tx the snapshot build (and `ClassIndexManager`) must
  resolve to the **overlaid** index set, or the planner will not see the
  tx-created index (and D13's skip-unbuilt guard never fires) and
  `ClassIndexManager` will not enqueue its entries. This needs a new per-session
  routing seam for the index manager (a proxy or a session-level resolver),
  since none exists today.
- **No D14-style split needed for indexes.** The index manager is *already*
  per-entity records: the manager record holds a `CONFIG_INDEXES` link set to
  per-index entities (F20). Changed index entities are naturally dirtied and
  only those are written at commit; the per-record diff (D6) is already
  per-index. D14's write-amplification fix is a classes-only concern.
- **Commit / rollback.** At commit, the changed-index set drives: create engines
  for tx-created indexes (D12, F22), drop engines for tx-dropped ones, write the
  changed per-index entities and update the manager link set, then publish the
  definition overlay into the shared index manager and `forceSnapshot`. At
  rollback, discard the overlay and the tracked key-entries; storage engines
  were never touched (D10/F16).
- **Index-change tracking stays consistent.** The per-tx key-entry tracking
  (F20) references the `Index` object; the session's `ClassIndexManager`
  enqueues changes against the overlaid (tx-created or committed) `Index`
  objects, which resolve correctly at commit.

### D9 — Diff over collection ids and index definitions, not class names
From the assignee, 2026-06-03. Collection id is the stable structural identity
(F15), so the commit-time structural diff matches on it:

- **Rename is structurally inert.** A class rename keeps its `collectionIds`,
  so the collection-id set is identical old-vs-new — zero collection
  create/drop. The name change is a property change in the schema record only,
  no data touched. The rename trap is avoided for free.
- **Create** = collection ids in the new schema absent from the old. New
  classes carry provisional (negative) ids during the tx (D2), resolved to real
  ids at commit.
- **Drop** = real collection ids in the old schema absent from the new — drop
  the collection (intended data loss, e.g. drop-class).
- **Abstract classes** carry `collectionIds = {-1}`, so their create/drop is
  pure metadata, no structural op. Constraint folded back into D2: provisional
  ids must use a sentinel range disjoint from `-1` (`NOT_EXISTENT_COLLECTION_ID`)
  and `COLLECTION_ID_INVALID`.
- **Indexes** diff by index identity from the index-manager record. Index
  rename reading as drop+create is acceptable — an index rebuilds with no data
  loss, unlike a collection — so indexes do not need the stable-id treatment
  collections require.

### D13 — A tx-created index is not query-usable until commit; planner skips unbuilt indexes
From the assignee, 2026-06-03. Inside the creating tx, a new index gives no
query acceleration: its engine does not exist until commit (D12), and reading
an engine-less index throws (F23). The planner must skip any index whose engine
is not built (`getIndexId() < 0`, or exclude/flag tx-created indexes in the
snapshot's `getIndexesInternal()` set) so the WHERE block falls through to
`FetchFromClassExecutionStep`. The scan fallback already returns the correct
merged tx view — committed rows + tx updates − tx deletes (F23). The existing
physical-plus-`FrontendTransactionIndexChanges` read-merge for already-built
indexes must be preserved unchanged (F23). Net contract: a newly-created index
becomes query-usable only after its creating tx commits and the engine is
built; queries inside the tx fall back to a correct full scan. Adds a required
planner-guard change to scope.

### D14 — Split the schema into per-class records (persistence), killing write amplification
From the assignee, 2026-06-03. Replace the single schema record (F1, all classes
in one EMBEDDEDSET) with a schema record that links to per-class entity records,
mirroring the index-manager pattern (F20: a `CONFIG_INDEXES` link set pointing
at per-index entities). Each `SchemaClassImpl` carries its own record RID. At
commit, `toStream` writes each class into its own record (load-by-RID + set
properties); `EntityImpl` per-property dirty tracking (D6) means only the
actually-changed class records are written — a one-class change no longer
rewrites the whole schema. This directly attacks the issue's "big amount of
storage writes." A new class is a new record (temp→persistent RID at commit,
D2/F24); a dropped class deletes its record and unlinks it from the schema
record. Inheritance needs no inter-record RID coupling: superclasses are
already referenced by name in the serialized form (`SchemaClassImpl.toStream`
sets `superClass`/`superClasses` to names, `:587`–`:597`).

- **Composition with D8:** the in-memory tx-local view stays approach A (full
  copy) for v1 — the copy is cheap and reuses all mutation machinery, while
  per-class records deliver the write-amplification win at the persistence
  layer and dirty-tracking decides what is written. The in-memory overlay
  (immutable committed base + changed-class map) becomes a feasible future
  refinement but is not needed for v1.
- **New scope this introduces:** `toStream`/`fromStream` rewrite (per-class
  records + schema-record link set); a schema version bump
  (`CURRENT_VERSION_NUMBER` 4 → new) plus a one-time, crash-safe migration of
  existing single-record schemas to per-class records on open. This **overturns
  the earlier "record format unchanged, no migration" assumption.**
- **Diff (D6/D9) becomes naturally per-class** — changed class records appear
  directly in the tx's changed-record set.
- **Scope: v1** (assignee, 2026-06-03). The split is a primary goal of
  YTDB-382 (write-amplification reduction), and doing it alongside the
  transactional work avoids a second schema-format migration later. Carried as
  its own track with the one-time on-open migration isolated from the
  transactional-commit work.

### D12 — Accept the index build under the exclusive commit lock for v1
From the assignee, 2026-06-03. Option (a): a transactional index build on an
already-populated class runs inside the exclusive-locked commit atomic
operation, accepting the commit-blocking stall and the single large WAL atomic
operation it produces (F22). Justified by the low schema-change rate (D5). A
follow-up YouTrack issue tracks the optimization (move the build off-lock /
stream it / scope-limit eager build to empty classes). The safe commit-time
ordering still holds: create engine → populate from committed data →
`lockIndexes` → write tx records → `commitIndexes` applies tracked changes (each
key counted once; build scan precedes the record-write loop). Follow-up issue:
YTDB-1064 (depends on YTDB-382).

### D10 — Structural revertibility via the existing atomic-operation WAL; no pool
From the assignee, 2026-06-03. Collection/index create and drop run inside the
transaction's atomic operation (D1, D3). Rollback and crash recovery are
already correct with no deletion pool, because file create/delete is buffered
intent applied only in `commitChanges`, which rollback skips (F16): `deleteFile`
records an id (`AtomicOperationBinaryTracking:882`), `addFile` books a
reservation, and `endAtomicOperation` calls `commitChanges` only when not
rolling back (`AtomicOperationsManager:320`, invariant asserted `:338`). A
rolled-back or crashed-before-commit tx leaves the files byte-for-byte
unchanged — no orphan on create, no data loss on drop. A committed delete is
permanent and redone from the WAL after a crash, as intended.

- **Pool / page-reuse rejected for v1.** Its only correctness benefit
  (revertible delete) is already free (F16). Its performance benefit
  (skip OS file create/delete) needs a new WAL-logged clear-and-reinit op,
  because the one "empty but keep the file" path, `truncateFile`, is not
  crash-safe (F17). Premature given the low schema-change rate (D5). Revisit
  only if schema-change throughput ever matters.

### D11 — Artificial collection names, decoupled from class names; rename is metadata-only
From the assignee, 2026-06-03. Generate collection names from a counter alone
(not `<className>_<counter>`), and make class rename a pure metadata change that
never renames collection files. Rationale: today a class rename physically
renames all four collection files via `writeCache.renameFile`, which runs
outside the WAL and is not crash-revertible (F18) — the only non-WAL-safe
physical collection mutation. Decoupling removes that path, strengthens D9 from
"structurally inert" to "touches zero storage," and is a contained change
(name-generation site `SchemaEmbedded.createCollections:340` plus neutering
`SchemaClassImpl.renameCollection:1392`; the metadata layer is already
id-based, F15).

---

## 4. Open questions

(none — the architecture spine D1–D13 is settled; remaining detail is
plan-level)

### Resolved
- **Q1 — Target semantics.** Full transactional schema; single-writer via
  locking is the v1 boundary (D5). Cross-session isolation is record-local,
  same as data (D4).
- **Q2** → D1 (defer allocation to commit).
- **Q3 — Isolation** → D4 (record-local) + D5 (lock-serialized writers).
- **Q4** → D1 (architecture supplied by assignee).
- **Q5 — Collection IDs + ordering** → D2 (provisional IDs) + D3 (ordering).
- **Q6 — During-tx schema view** → D4 (record-local; `SchemaShared` updated at
  commit). Mechanics tracked in Q9.
- **Q7 — Schema-write lock** → D7 (dedicated tx-scoped metadata-write mutex).
- **Q8 — Detection and diff approach** → D6 (diff via tx-changed records +
  `EntityImpl` property tracking). Matching/rename mechanics tracked in Q10.
- **Q9 — Tx-local schema view** → D8 (per-session copy-on-first-write
  `SchemaShared`; `SchemaProxy` routes to it for the tx duration).
- **Q10 — Diff matching / rename** → D9 (diff over collection ids, not class
  names; rename is structurally inert).
