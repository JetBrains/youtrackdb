<!--
MANIFEST
dimension: crash-safety
step: 4.4
commit: bc7eba6da8
iteration: 1
verdict: CHANGES_REQUESTED
blockers: 1
findings_total: 3
evidence_base: 4 certs (C1-C4), PSI-backed
cert_index: C1,C2,C3,C4
flags: none
index:
  - id: CS1
    sev: blocker
    anchor: "#cs1-blocker-committed-drop-leaves-the-storage-config-entry-and-link-bag-b-tree-component-on-disk"
    loc: "AbstractStorage.java:2725-2733,2716,5688-5703; vs dropCollection(int) 1571-1583; CollectionBasedStorageConfiguration.java:1448-1462; LinkCollectionsBTreeManagerShared.java:90-110"
    cert: C1
    basis: "Read: reconcileCollections drops via dropCollectionInternal only (file delete + in-memory removal); the public dropCollection(int) also calls configuration.dropCollection + linkCollectionsBTreeManager.deleteComponentByCollectionId inside the same atomic op. PaginatedCollectionV2.delete touches only the collection's own files. grep confirms no config/component drop anywhere on the reconcile path. Create side (doCreateCollection 5602-5629) writes both, so the drop side is asymmetric."
  - id: CS2
    sev: should-fix
    anchor: "#cs2-should-fix-failed-schema-carry-commit-containing-a-drop-leaves-the-in-memory-registry-diverged-from-disk"
    loc: "AbstractStorage.java:2594-2603,2781-2797,5688-5703; rollback 4233-4236; moveToErrorStateIfNeeded 4238-4245"
    cert: C2
    basis: "Read: undoReconciledCollections iterates getResolvedCollectionIds().values() (creates only); a drop's in-memory removal is never restored. rollback only ends the atomic op (file reverted, survives). ConcurrentCreateException extends NeedRetryException, which moveToErrorStateIfNeeded skips, so the storage stays OPEN after such a failure -> reachable divergence."
  - id: CS3
    sev: suggestion
    anchor: "#cs3-suggestion-bc1-forward-note-discharged-recordexists-is-unreachable-from-the-commit-window-body"
    loc: "AbstractStorage.java:5172-5185 (recordExists); 2688-2694 (window scope); DatabaseSessionEmbedded#executeExists"
    cert: C3
    basis: "PSI find-usages: recordExists(3-arg, takes readLock) has exactly one caller executeExists <- FrontendTransactionImpl#exists <- RecordAbstract#exists. The window body (toStream/fromStream via session.load, commitEntry, position alloc, executeOperations, commitIndexes) does not reach RecordAbstract.exists; schema serialization uses session.load. Confirmed unreachable -> the Step 3 BC1 forward note is discharged."
-->

# Crash Safety & Durability Review — Track 4, Step 4 (commit-time reconciliation core)

Commit `bc7eba6da8`. Reviewed for crash safety and durability only.

The WAL-ordering spine of this step is sound: the structural file writes
(`doCreateCollection`, `dropCollectionInternal`, the config and link-bag-component
writes performed by the create path) all run inside the `startTxCommit` /
`endTxCommit` window on the commit's own `atomicOperation`, so they are buffered as
WAL intent before `commitChanges` (the durability boundary inside
`endAtomicOperation`) and revert atomically with the record writes on a rollback.
The phantom-create case (I-A4) is correctly handled: the create's in-memory
publication is undone in the failure `finally`. The promotion read on the success
path runs inside the still-open commit window, so the `session.load` it issues
routes through the lock-free read substrate rather than re-entering the read lock.

The one blocker (CS1) is not in the create path the step's tests exercise — it is in
the **drop** path, which is asymmetric with the create path and with the existing
public `dropCollection(int)`: the committed delete removes the data file but leaves
the storage-configuration entry and the link-bag B-tree component on disk. CS2 is a
narrower in-memory/on-disk divergence on a failed commit that contained a drop.

## Findings

### CS1 [blocker] Committed drop leaves the storage-config entry and link-bag B-tree component on disk

**File:** `core/.../storage/impl/local/AbstractStorage.java` — `reconcileCollections`
drop loop (lines 2725-2733), `dropCollectionInternal` (5688-5703); compare the public
`dropCollection(int)` (1571-1583). Supporting:
`CollectionBasedStorageConfiguration.dropCollection` (1448-1462),
`LinkCollectionsBTreeManagerShared.deleteComponentByCollectionId` (90-110),
`PaginatedCollectionV2.delete` (467-481).

**Crash / durability scenario:** This is not a crash-timing window — it is a
permanent on-disk inconsistency produced by a *successful* committed drop. When a
schema-carry commit drops a collection, `reconcileCollections` calls
`dropCollectionInternal(atomicOperation, committedId)` and nothing else.
`dropCollectionInternal` does exactly three things: `collection.delete(atomicOperation)`
(deletes the data file, position map, free-space map, dirty-page bitset — confirmed in
`PaginatedCollectionV2.delete`), `collectionMap.remove(...)`, and
`collections.set(collectionId, null)`. It never removes:

1. the storage-configuration entry — the public `dropCollection(int)` path calls
   `((CollectionBasedStorageConfiguration) configuration).dropCollection(atomicOperation, collectionId)`
   (AbstractStorage:1577-1578), which drops the WAL-logged
   `COLLECTIONS_PREFIX_PROPERTY + collectionId` property and nulls the persisted
   collections-list slot (config:1454-1458). The reconcile path skips this entirely.
2. the link-bag B-tree component — the public path calls
   `linkCollectionsBTreeManager.deleteComponentByCollectionId(atomicOperation, collectionId)`
   (AbstractStorage:1579-1580). The reconcile path skips this too.

The create side is asymmetric: `doCreateCollection` (5602-5629) writes *both* the
config entry (`configuration.updateCollection`, 5623-5624) and the component
(`linkCollectionsBTreeManager.createComponent`, 5626). So a create-then-reconcile is
complete, but a drop-then-reconcile leaves two committed orphans.

**Recovery impact (the durable consequence):** Both orphans are committed (WAL-logged
and made durable at `commitChanges`), so they survive every restart.

- *Config orphan.* At the next open, `openCollections` (1015-1052) iterates
  `configuration.getCollections()`, finds the dropped collection's slot still
  non-null, builds the collection from config, and calls `.open(atomicOperation)` —
  which throws `FileNotFoundException` (the data file is gone). The catch
  (1032-1048) logs a warning and excludes the collection. So the data is correctly
  gone, but every open re-derives the exclusion and emits a noisy warning forever,
  and the persisted config never converges to the true structure.
- *Component orphan + id-reuse fault (the escalation).* The link-bag B-tree file
  `FILE_NAME_PREFIX + collectionId` is never deleted. The commit-local allocator
  `nextFreeCollectionId` (2765-2772) scans the in-memory `collections` list; after a
  reopen the dropped slot is null, so a later schema-carry commit will reuse that id.
  `doCreateCollection` then calls `createComponent(atomicOperation, reusedId)` →
  `SharedLinkBagBTree.create` → `addFile(atomicOperation, getFullName())`
  (SharedLinkBagBTree:58). `addFile` on an already-existing file name throws — so the
  next drop-then-create-reusing-the-id commit faults at reconciliation. The orphan
  turns a clean future commit into a failure.

**Refutation considered:**
- *Is the drop config/component removal handled elsewhere on the commit path?* No.
  grep over `reconcileCollections` + `applyCommitOperations` (2440-2754) shows the
  drop loop's only structural call is `dropCollectionInternal`; there is no
  `configuration.dropCollection`, `updateCollection`, or `deleteComponent*` anywhere
  on the reconcile path.
- *Does promotion's `fromStream` / `forceSnapshot` repair the config?* No — promotion
  re-parses the schema records into the in-memory shared `SchemaShared`; it does not
  touch the storage `configuration` collections list or the link-bag manager.
- *Does the test miss it because of `memory:`?* The new
  `SchemaCommitReconciliationTest.droppedClassRemovesItsCollectionAcrossCommit` passes
  on disk too (verified: `-Dyoutrackdb.test.env=ci`, 5/5 green), because it asserts
  only `session.getCollectionNames()` after reload — and the `FileNotFoundException`
  catch path removes the orphaned name from `collectionMap`, masking the stale config
  at exactly the surface the test checks. The orphaned component file and the config
  property are never asserted on, so the masked inconsistency ships green.

**Suggestion:** In the `reconcileCollections` drop loop, mirror the public
`dropCollection(int)` body: after `dropCollectionInternal` returns `false` (a real
drop happened), also call
`((CollectionBasedStorageConfiguration) configuration).dropCollection(atomicOperation, committedId)`
and `linkCollectionsBTreeManager.deleteComponentByCollectionId(atomicOperation, committedId)`
on the same `atomicOperation`, so all three structural deletions revert together on
rollback and all become durable together on commit. Add an assertion to the drop test
that the link-bag component file is gone and that a reopen logs no
`FileNotFoundException` for the dropped id (or assert the config collections list no
longer carries the slot). A create-reusing-the-dropped-id-after-restart test would
pin the escalation path.

### CS2 [should-fix] Failed schema-carry commit containing a drop leaves the in-memory registry diverged from disk

**File:** `core/.../storage/impl/local/AbstractStorage.java` — failure `finally`
(2594-2603), `undoReconciledCollections` (2781-2797), `dropCollectionInternal`
(5688-5703), `rollback` (4233-4236), `moveToErrorStateIfNeeded` (4238-4245).

**Crash / failure scenario:** If a schema-carry commit drops a collection and then
fails *after* `reconcileCollections` ran but before `endTxCommit` — for example a
`ConcurrentCreateException` in the position-allocation loop (2538-2542), or any
`RuntimeException` from `commitEntry` / `executeOperations` / `commitIndexes` — the
failure `finally` runs `rollback(error, atomicOperation)` then, because
`structurePublished` is true, `undoReconciledCollections`. But
`undoReconciledCollections` iterates `txSchemaState.getResolvedCollectionIds().values()`
(2784) — the **created** ids only. A dropped collection's in-memory removal
(`collectionMap.remove` + `collections.set(id, null)`, done eagerly inside
`dropCollectionInternal`) is never restored.

**State after the failure:** `rollback` only ends the atomic operation, so the
dropped collection's data file is WAL-reverted and *survives on disk*. Its
`configuration` entry also survives (it was never removed — see CS1). But its
in-memory `collections` / `collectionMap` entry stays removed, and the failure path
does not run promotion (`fromStream`), so nothing re-registers it. The live storage
now reports a collection as absent that is fully present on disk and in config, until
the next storage open re-syncs from config.

**Why the storage stays open (reachability):** The natural assumption is that a failed
commit poisons the storage (`setInError`) and forces a reopen that would re-sync.
That does not hold for the retry family: `ConcurrentCreateException extends
NeedRetryException`, and `moveToErrorStateIfNeeded` (4238-4245) explicitly skips
`NeedRetryException`. So after a `ConcurrentCreateException` the storage stays
`OPEN` and serves further transactions against the diverged in-memory view.

**Refutation considered:**
- *Is a drop-plus-failure combination reachable?* A schema-carry commit can carry both
  a drop (the dropped class's record `DELETED` op) and new-record creation; the
  position loop runs for the created records and can throw `ConcurrentCreateException`.
  The drop runs first in `reconcileCollections`, so by the time the loop throws, the
  in-memory removal already happened. Reachable.
- *Does `undoReconciledCollections` accidentally cover drops?* No — it only iterates
  resolved (created) ids. A symmetric restore for drops would need to re-register the
  dropped collections from `committedSchema.getRealCollectionIds()` minus the
  tx-local set.
- *Is it self-healing?* Only on the next storage open (config re-sync). While the
  storage stays open after a `NeedRetryException`, the divergence is live.

**Suggestion:** On the schema-carry failure path, restore the dropped collections'
in-memory registration symmetrically — re-register each committed-but-not-tx-local id
whose `dropCollectionInternal` already ran (the file is WAL-reverted, so the
`StorageCollection` can be re-opened from config / the surviving file), or defer the
drop's in-memory mutation to the post-`commitChanges` success path the way the create
publication is deferred, so a rollback leaves the in-memory registry untouched. If CS1
is fixed by deferring all three drop deletions into the atomic op, the in-memory
removal still needs the same defer-or-undo treatment as the create publication.

### CS3 [suggestion] BC1 forward note discharged: recordExists is unreachable from the commit window body

**File:** `core/.../storage/impl/local/AbstractStorage.java` — `recordExists`
(5172-5185, takes `stateLock.readLock()`); window scope `commitSchemaCarry` (2688-2694).

Step 3's episode left a forward obligation for Step 4: when the full
reconciliation-plus-promotion body runs inside the commit window, re-run the PSI
enumeration of `stateLock.readLock()`-taking methods reachable from inside the window
and either make `recordExists` window-aware or record it as provably unreachable.

PSI find-usages discharges it as unreachable. The 3-arg `recordExists` (the one that
takes the read lock) has exactly one caller, `DatabaseSessionEmbedded#executeExists`,
which is reached only via `FrontendTransactionImpl#exists ← RecordAbstract#exists`.
The window body — `txLocalSchema.toStream`, `committedSchema.fromStream` (both reading
records through `session.load` → `executeReadRecord`, covered by the lock-free
`getPhysicalCollectionNameById` + `readRecordInternal`), the position-allocation loop,
`commitEntry`, `recordSerializationContext.executeOperations`, and `commitIndexes` —
does not call `RecordAbstract.exists`. Schema serialization reads via `load`, not
`exists`. So `recordExists` correctly keeps its read lock and is never reached under
the held write lock. No change needed; recorded here so the Step 3 BC1 obligation is
visibly closed.

One adjacent confirmation: the index-apply re-entry `lockIndexes` →
`IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine(int)` is window-aware
(the diff adds the `isCommitWindowActive()` self-route at 3637-3651 routing to
`doGetIndexEngine`), so the index path is covered even though index ops are empty
until Track 5.

## Evidence base

PSI was reachable this session; `steroid_list_projects` confirmed the open project
`transactional-schema-b4l1mcdq` matches the working tree. Symbol audits used PSI
find-usages; file-structure reads used the diff and direct file reads.

#### C1 — Drop path is asymmetric and omits config + component deletion (CS1, CONFIRMED-as-issue)

Survived refutation. `reconcileCollections` (2716-2754) drop loop calls only
`dropCollectionInternal`; `dropCollectionInternal` (5688-5703) does file delete +
in-memory map removal, no config/component drop. Public `dropCollection(int)`
(1571-1583) additionally calls `configuration.dropCollection` (config:1448-1462,
WAL-logged `dropProperty`) and `linkCollectionsBTreeManager.deleteComponentByCollectionId`
(LinkCollectionsBTreeManagerShared:90-110). `PaginatedCollectionV2.delete` (467-481)
deletes only the collection's own files. Create side `doCreateCollection` (5623-5626)
writes both config and component, establishing the asymmetry. `openCollections`
(1015-1052) shows the open-time `FileNotFoundException` exclusion that masks the
config orphan at `getCollectionNames` level. `SharedLinkBagBTree.create` →
`addFile` (52-78) shows the id-reuse fault path. Test
`droppedClassRemovesItsCollectionAcrossCommit` passes on disk (`ci` env, 5/5) yet
never asserts on the component file or config property.

#### C2 — Drop's in-memory removal not undone on failed commit; storage stays open after retry-class failure (CS2, CONFIRMED-as-issue)

Survived refutation. `undoReconciledCollections` (2781-2797) iterates
`getResolvedCollectionIds().values()` (creates only); the drop's eager in-memory
removal in `dropCollectionInternal` (5699-5700) is not restored. `rollback`
(4233-4236) only ends the atomic op (file reverted). `ConcurrentCreateException
extends NeedRetryException` (grep) and `moveToErrorStateIfNeeded` (4238-4245) skips
`NeedRetryException`, so the storage stays OPEN — the divergence is live until the
next open.

#### C3 — recordExists provably unreachable from the window (CS3, informational, discharges BC1 forward note)

PSI find-usages: `recordExists`(3-arg) ← single caller `executeExists` ←
`FrontendTransactionImpl#exists` ← `RecordAbstract#exists`. Window body uses
`session.load` (covered by the Step 3 substrate), never `RecordAbstract.exists`.
Index-apply re-entry `getIndexEngine` made window-aware in the diff (3637-3651). No
new lock-free variant needed for Step 4.

#### C4 — WAL ordering spine of the step is sound (no finding; positive evidence)

`endAtomicOperation` (AtomicOperationsManager:258-405) confirms `commitChanges`
(341) is the durability boundary, skipped on rollback (320-344). The structural
writes run inside `startTxCommit` (5271-5273) / `endTxCommit` (5187-5189) on the
commit's `atomicOperation`, so they revert atomically with record writes on
rollback (D10). The create publication is correctly undone on failure
(2594-2603) and promotion runs only on success inside the still-open window
(2631-2641). LSN / page-LSN / WAL-flush machinery is untouched by this diff. The
crash-before-commit half of I-A1 leans on Track 1's `ensureFileForReplay` (already
landed); nothing in this step regresses it.
