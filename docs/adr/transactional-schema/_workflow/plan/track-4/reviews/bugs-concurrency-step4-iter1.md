<!--MANIFEST
dimension: bugs-concurrency
step: 4
iteration: 1
commit_range: bc7eba6da8~1..bc7eba6da8
verdict: CHANGES_REQUESTED
counts: {blocker: 0, should-fix: 2, suggestion: 1}
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-should-fix--partial-reconcilecollections-failure-leaves-phantom-in-memory-registration"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2455-2464,2593-2603"
    cert: C1
    basis: "code-trace + control-flow"
  - id: BC2
    sev: should-fix
    anchor: "#bc2-should-fix--failed-schema-commit-does-not-restore-the-in-memory-registry-for-dropped-collections"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2716-2797"
    cert: C2
    basis: "code-trace + PSI"
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion--drop-then-create-collection-id-reuse-within-one-atomic-operation"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2716-2772,5688-5703"
    cert: C3
    basis: "code-trace"
-->

## Findings

### BC1 [should-fix] — Partial `reconcileCollections` failure leaves phantom in-memory registration

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2455-2464, 2593-2603)
- **Issue**: The failure-path guard that undoes synchronously-published collections is keyed on
  `structurePublished`, which is set to `true` only *after* `reconcileCollections(...)` returns
  (line 2464). `reconcileCollections` publishes each created collection into the live registries
  *inside its own loop* — `doCreateCollection` → `registerCollection` (writes `collections` and
  `collectionMap`) → `recordResolvedCollectionId` (line 2742-2744). If `reconcileCollections` throws
  partway through the create loop (or the drop loop), the collections already published in that loop
  stay in the in-memory registries, but the catch/finally observes `structurePublished == false`
  (line 2596) and skips `undoReconciledCollections`. The result is exactly the phantom in-memory
  registration that D10 / I-A4 / the R1/A1 deferral was added to prevent — for the single most likely
  throw site, the reconciliation itself.
- **Evidence**: `reconcileCollections` can throw mid-loop on a realistic fault: `doCreateCollection`
  → `collection.create(atomicOperation)` / `configuration.updateCollection` /
  `linkCollectionsBTreeManager.createComponent` all throw `IOException` (wrapped to an unchecked
  `StorageException` at line 2746-2752 and re-thrown out of the method), and `registerCollection`
  throws `ConfigurationException` (a `RuntimeException`, uncaught by the `IOException`-only catch in
  `reconcileCollections`) on a duplicate generated collection name. Both propagate past line 2464
  before `structurePublished` is set, so the create N-1 collections already registered at line 2743
  survive in `collections`/`collectionMap` after `rollback` reverts only the on-disk files. The
  happy-path tests in `SchemaCommitReconciliationTest` (all 5 green on the in-memory profile) never
  reach this fault window, and the I-A4 failed-commit registry-cleanliness test named in the track's
  Validation section is not yet present in this step's diff.
- **Refutation considered**: Could `reconcileCollections` be unable to throw after a partial publish?
  No — `registerCollection` publishes before the loop can complete, and every create-side call it
  makes has a checked-or-unchecked throw path (confirmed by reading `doCreateCollection`
  line 5602-5629 and `registerCollection` line 5525-5548). Could a higher-level handler re-clean the
  registry? No — the only failure-path cleanup is `undoReconciledCollections`, gated behind
  `structurePublished` (line 2596), and `rollback` touches WAL/files only, not the Java maps (the
  diff comment at line 2597-2600 states exactly this). Confirmed.
- **Suggestion**: Set `structurePublished = true` *before* calling `reconcileCollections` (the intent
  is "reconciliation may have published into the live maps"), so the undo runs whenever reconciliation
  was entered. `undoReconciledCollections` is already idempotent against a never-published id (its map
  lookup misses), so running it after a partial publish is safe.

### BC2 [should-fix] — Failed schema commit does not restore the in-memory registry for dropped collections

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2716-2797)
- **Issue**: `reconcileCollections` applies drops synchronously: `dropCollectionInternal` removes the
  collection from `collectionMap` and nulls its `collections` slot in memory (line 5699-5700), inside
  the atomic operation. On a failed commit, `rollback` reverts the on-disk file delete (WAL-buffered
  intent), but the failure-path cleanup `undoReconciledCollections` only undoes *creates* — it
  iterates `txSchemaState.getResolvedCollectionIds().values()` (the created real ids) and removes
  them (line 2784-2796). Nothing re-registers a dropped collection. So a schema commit that dropped a
  class and then failed anywhere downstream leaves the dropped collection present on disk (file
  restored by rollback) but *absent* from the in-memory `collections`/`collectionMap` registries —
  an in-memory/on-disk divergence that persists until the storage reopens, the mirror image of the
  BC1 phantom create.
- **Evidence**: The drop happens first in `reconcileCollections` (line 2729-2733), so the failure
  window is the entire apply body after it: `resolveProvisionalCollectionIds`, `toStream`,
  `computeCommitWorkingSet`, `lockCollections`/`lockLinkBags`/`lockIndexes`, the position-allocation
  loop, `commitEntry`, the link-bag `executeOperations`, `commitIndexes`, and the lifecycle persist
  hook in `endTxCommit` — every one of which has a throw path that routes to the failure `finally`
  (line 2593-2603). In that path `structurePublished == true`, so `undoReconciledCollections` runs,
  but it has no drop-restore arm (read in full, line 2781-2797). A dropped vertex class with several
  collections would lose all of them from the in-memory registry; subsequent reads of those
  collection ids on the same open storage would resolve to `null`.
- **Refutation considered**: Is a drop reachable together with a downstream failure? Yes — a
  `dropClass` commit enrols the per-class-record DELETE and the root-record UPDATE, both of which flow
  through `commitEntry`/the lifecycle hook after the drop already mutated the registry; any apply-time
  fault (e.g. the `AssertionError`/`IOException` cases the apply-body catch explicitly handles at
  line 2574-2592) triggers it. Could storage reopen mask the divergence? Only on a restart — within
  the live storage instance the registry stays wrong, and the design's contract (D10) is in-memory
  cleanliness without requiring a reopen. Could the file restore plus a missing in-memory entry be
  benign? No — `doGetAndCheckCollection` and the name map would no longer see a collection whose file
  exists, breaking later reads/writes to it. Confirmed.
- **Suggestion**: Give the failure path a drop-restore arm symmetric to the create-undo arm — e.g.
  capture the dropped collections (id + config/name) before `dropCollectionInternal` nulls them and
  re-register them in `undoReconciledCollections`, or defer the in-memory drop (the `collectionMap`
  remove + slot null) to the post-`commitChanges` success path the same way the create publication is
  meant to defer (D10 / the `deleteIndexEngine` discipline the track cites). Add a failed-commit test
  that drops a class and faults at/after `commitChanges`, asserting the dropped collection is still in
  the registry (this is the I-A4 test the track's Validation section already calls for, extended to
  the drop case).

### BC3 [suggestion] — Drop-then-create collection-id reuse within one atomic operation

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2716-2772, 5688-5703)
- **Issue**: In a commit that both drops and creates collections, `reconcileCollections` runs the drop
  loop first — `dropCollectionInternal` nulls the dropped slot in `collections` (line 5700) — then the
  create loop calls `nextFreeCollectionId()` (line 2741), which returns the first null slot and so can
  hand a *just-dropped* id back to a newly created collection within the same atomic operation. The
  new collection's files (`doCreateCollection` → `collection.create` and
  `linkCollectionsBTreeManager.createComponent`, which names the B-tree file `FILE_NAME_PREFIX +
  collectionId`) are then created at the same id/name whose files were deleted earlier in the same
  atomic operation. Whether creating a file at a name marked deleted-in-the-same-atomic-op is correct
  (idempotent vs "file already exists" vs stale-page reuse) is a WAL/atomic-op-layer property this
  reviewer cannot settle from the commit path alone.
- **Evidence**: `nextFreeCollectionId` (line 2765-2772) scans `collections` for the first null,
  identical to the legacy `doAddCollection` allocator (line 5550-5562); `dropCollectionInternal`
  nulls the slot synchronously (line 5700); the create loop calls the allocator after the drop loop
  completes. The drop+create-in-one-tx scenario has no test in this step's diff
  (`SchemaCommitReconciliationTest` drops in a separate transaction from creates), so the interaction
  is unexercised.
- **Refutation considered**: Does any in-tx drop+create path exist? A `dropClass` followed by a
  `createClass` in one transaction reaches it; not common but not impossible. Is it definitely a bug?
  No — it depends on the file-create-over-same-atomic-op-delete semantics, which is crash-safety
  territory. Routed here as a suggestion because the id-reuse mechanism is concrete in this diff;
  the crash-safety reviewer owns the file-layer verdict.
- **Suggestion**: Either exclude same-commit-dropped ids from the create allocator (seed
  `nextFreeCollectionId` from a snapshot of free slots taken before the drop loop, or skip ids dropped
  in this reconciliation), or add a drop+create-in-one-tx test on the disk profile and confirm the
  atomic-op file layer tolerates create-after-delete at the same id. Flag for the crash-safety
  reviewer.

## Evidence base

#### C1 — BC1 phantom-create reachability
CONFIRMED-as-issue: `structurePublished` is set at line 2464, strictly after `reconcileCollections`
returns (line 2462-2463); the create loop publishes via `registerCollection` then records resolution
(line 2742-2744); `doCreateCollection`/`registerCollection` have IOException/ConfigurationException
throw paths (read at line 5525-5548, 5602-5629); the failure guard at line 2596 is `structurePublished
&&`, so a mid-reconcile throw skips the undo. Survived refutation (no alternate registry cleanup;
`rollback` is file/WAL-only per the diff's own comment).

#### C2 — BC2 drop-restore omission
CONFIRMED-as-issue: `dropCollectionInternal` nulls the in-memory slot and removes from `collectionMap`
synchronously (read at line 5688-5703); `undoReconciledCollections` iterates only
`getResolvedCollectionIds().values()` (created ids) and has no drop-restore arm (read in full at
line 2781-2797); `getResolvedCollectionIds` returns the provisional→real map values (verified in
`TxSchemaState` line 211-239). The failure window spans the whole apply body after the drop loop.
Survived refutation (divergence persists within the live storage instance; later id resolution returns
null).

#### C3 — BC3 id-reuse mechanism
PARTIAL / routed as suggestion: the `nextFreeCollectionId` first-null-slot scan plus
`dropCollectionInternal`'s synchronous slot-null make same-atomic-op id reuse possible; the
correctness verdict rests on the WAL/atomic-op file-create-over-same-op-delete semantics
(`createComponent` names files by collection id, read at `LinkCollectionsBTreeManagerShared`
line 90-98), which is crash-safety scope. No same-tx drop+create test exists. Not refuted, not fully
confirmed — handed to the crash-safety dimension.

#### C4 — readLock-re-entry enumeration (the step's load-bearing question; no finding, recorded for the gate)
CONFIRMED-SOUND (no issue): PSI `ReferencesSearch` over the commit body's record-read surface, run
against the open `transactional-schema` project, establishes the window enumeration is complete:
- `recordExists` (NOT window-aware, line 5161-5185) is reachable only via
  `DatabaseSessionEmbedded#executeExists` ← `FrontendTransactionImpl#exists` (callers: a single test,
  `FrontendTransactionImplCoverageTest`) and `RecordAbstract#exists` (zero callers). The commit body's
  schema serialization/promotion uses `session.load`, never `.exists()`, so `recordExists` is
  provably unreachable under the held write lock — the BC1 forward note from Step 3 is resolved; no
  window-awareness is required for it.
- `getCollectionName(DatabaseSessionEmbedded, int)` (NOT window-aware, line 4363-4385) has zero
  callers — provably unreachable.
- Every readLock method the commit body does reach is window-aware: `getPhysicalCollectionNameById`
  (line 4317, ← `executeReadRecord`), `readRecordInternal` (line 5125, ← `executeReadRecord` cache
  miss), `getCollectionNames` (line ~2060, ← `SchemaShared#initCollectionCounterFromExisting` during
  `fromStream`), `getCollectionIdByName` (line 2091, the third converted read site), `isClosed`
  (line 1393, ← `EntityImpl.toString` logging), and `getIndexEngine` (line 3637, ← the index-apply
  path) all self-route on `isCommitWindowActive()`.
- `commitSchemaCarry`'s four nested try/finally blocks release all four locks plus the commit window
  on every exception path (read at line 2678-2703); `makeThreadLocalSchemaSnapshot`/
  `clearThreadLocalSchemaSnapshot` stay paired across both branches.
- Empirical confirmation: `SchemaCommitReconciliationTest` (create / reload / record-insert / rollback
  / drop) runs 5/5 green on the in-memory profile (`./mvnw -pl core clean test`). Note for the
  test-completeness/crash-safety reviewers: these ran on `memory:` storage, so the disk-profile
  reconciliation (file create/drop, WAL revertibility, the I-A4 failed-commit and I-A1
  crash-before-commit cases the track's Validation section lists) is not exercised by this step's diff.
