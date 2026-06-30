<!--
MANIFEST
review_kind: crash-safety
track: 4
iteration: 1
commit_range: 1dd9c0424f40e7aa9ec90858f6eb4b235f3a2c5f..2f295a881f
level: high
verdict: PASS_WITH_FINDINGS
findings_total: 3
blockers: 0
should_fix: 1
suggestions: 2
evidence_base: "## Evidence base"
cert_index:
  - id: CS1
    cert: C1
  - id: CS2
    cert: C2
  - id: CS3
    cert: C3
flags:
  - engine-reconciliation-deferred-to-track-5 (doAddIndexEngine/publishIndexEngine wired only into public addIndexEngine; commit reconciles collections only)
  - two-branch-red-tests-pending-user-decision (MetadataWriteMutexTest, SchemaDeguardTest.renameClassInsideTransactionRecordsNewNameOnly — neither is a crash-safety defect)
index:
  - id: CS1
    sev: should-fix
    anchor: "### CS1"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:1794
    cert: C1
    basis: "applyCommitOperations success-finally: promotion runs after endTxCommit (durable) with no try/catch; a throw leaves committed in-memory SchemaShared un-promoted while records+collections are durable"
  - id: CS2
    sev: suggestion
    anchor: "### CS2"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2075
    cert: C2
    basis: "revertCreatedCollectionStructure opens a fresh atomic operation from the failure-path finally; in-memory-only reclaim, not atomic with the rolled-back commit; bounded single-collection leak on a crash between rollback and this cleanup"
  - id: CS3
    sev: suggestion
    anchor: "### CS3"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:3009
    cert: C3
    basis: "crash-before-commit recovery (I-A1 crash half, D10 + Track 1 ensureFileForReplay) is @Ignore'd; the durability-recovery claim has no executing test in this track"
-->

# Crash Safety & Durability Review — Track 4 (commit-time reconciliation), iteration 1

## Findings

### CS1 [should-fix] Promotion after a durable commit has no error handling — a throw leaves the in-memory committed schema un-promoted while records and collections are durable

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 1755-1806, the success branch of the `applyCommitOperations` `finally`; promotion at 1794-1804)

**Crash scenario.** No process crash is needed to observe the divergence; an in-process exception is enough. If the process crashes after `endTxCommit` but before promotion completes, restart re-parses correctly (see refutation), so the durability is sound. The exposure is the *in-process* path: a `RuntimeException`, `IOException` (wrapped), or `AssertionError` thrown by `session.load(committedSchema.getIdentity())`, `committedSchema.fromStream(...)`, or `committedSchema.forceSnapshot()` — all of which run after `endTxCommit(atomicOperation)` has already made the commit durable.

**Write-path trace (evidence).**
- STEP A `startTxCommit(atomicOperation)` opens the apply window (1475).
- STEP B `reconcileCollections` creates the real collections, publishes them into `collections`/`collectionMap` synchronously (`registerCollection`, reconcile loop), and records each provisional→real id (1487).
- STEP C records apply; `commitIndexes` runs (1720-1735).
- STEP D the `error == null` finally branch calls `endTxCommit(atomicOperation)` → `atomicOperationsManager.endAtomicOperation(atomicOperation, null)` (1780; body at AbstractStorage.java:5367-5369). **The commit is durable here**: the new collection files, the per-class records, the root record are all committed to the WAL and applied.
- STEP E promotion runs with **no `try`/`catch`** around it (1794-1804): `committedRoot = session.load(...)`; `committedSchema.fromStream(session, committedRoot)`; `committedSchema.forceSnapshot()`. `error` is still `null`, so a throw here does **not** route to `rollback(...)` (and rolling back would be wrong — the commit is durable). The exception escapes `applyCommitOperations`, then `commitSchemaCarry`, releasing the four locks via their `finally`s, and propagates to the API caller.

**Resulting state after the throw.** The collections are durable on disk and published in the registry. The records and schema records are durable. But the shared in-memory `committedSchema` was never re-parsed and `forceSnapshot()` may not have fired, so:
- the new class is **absent from the committed in-memory schema** and from the immutable snapshot, while its collection is registered and its per-class record is on disk;
- a dropped class may still be **present in the committed in-memory schema** while its record is deleted on disk and its collection slot freed in the registry.

This is an in-memory/on-disk and registry/schema divergence held until the next `reload`/reopen. The snapshot-first read sites converted in Step 6 (`createVertexWithClass`, `getLowerSubclass`) read `getImmutableSchemaSnapshot()`, which (without the trailing `forceSnapshot`) keeps serving the pre-commit view even though the commit is durable — a lost-update-shaped read window in the same process.

Contrast with the adjacent code: `cleanupSnapshotIndex()` immediately above (1781-1792) *is* wrapped in `try/catch` precisely because "its failure must never mask a successful commit." Promotion has the same property (the commit is already durable) but is left unguarded, so a promotion failure both masks the successful commit (the caller sees an exception) **and** leaves the in-memory view stale.

**Recovery impact.** On a restart the open-time `fromStream` re-parses the durable records and rebuilds the correct committed schema, so the database is consistent across a restart. The defect is confined to the live process between the failed commit and the next reload/reopen: callers see a committed-but-invisible (or dropped-but-still-visible) class.

**Refutation considered.**
- *Could a crash here be unrecoverable?* No — STEP D made the commit durable; open-time `fromStream` reconstructs the schema. Verified against the D10 rationale and the Track 1 `ensureFileForReplay` prerequisite. This is why the finding is should-fix, not blocker.
- *Can promotion actually throw?* `fromStream` reads every linked per-class record through the commit window (the warm-load in `toStream` exists specifically because an un-warmed record read would throw "atomic operation is not active" — Step 5 episode). A genuine cache-miss, a `ConfigurationException` on a non-persistent linked id (Track 2's load-reader contract), or a `-ea` assert along `forceSnapshot` are all reachable throw sites. The `session.load` here runs *after* `endTxCommit`, i.e. after the atomic operation ended, so any record not already warmed into the cache is exactly the "atomic operation is not active" failure the Step 5 warm-load guards against — but that guard lives in `toStream` (the changed/unchanged classes), not necessarily every record `fromStream` re-reads on promotion.
- *Is the divergence masked by the storage moving to error state?* Only for non-retry-family exceptions (`moveToErrorStateIfNeeded`, AbstractStorage.java:4418). A `NeedRetryException`/`HighLevelException`/`InternalErrorException` (the same retry family the new tests deliberately use to keep the storage OPEN) leaves the storage serving against the stale in-memory schema. VERDICT: CONFIRMED as a real in-process consistency gap.

**Suggestion.** Wrap promotion the same way `cleanupSnapshotIndex` is wrapped, but on failure do the opposite of swallowing: since the commit is durable and the in-memory state is now untrustworthy, force a schema reload (or mark the shared schema for lazy re-parse) before returning, and surface the original commit as succeeded. Minimally, ensure `forceSnapshot()` runs even if `fromStream` threw, so a stale snapshot is never served against durable state. At the very least, document why an unguarded promotion failure after a durable commit is acceptable, the way the cleanup-catch comment does.

### CS2 [suggestion] Failure-path orphan cleanup opens a fresh atomic operation while the commit exception propagates — bounded in-memory-only leak window

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 2075-2108, `revertCreatedCollectionStructure`; called from `undoReconciledCollections` 2038)

**Crash scenario.** On a failed schema-carry commit that created a collection, `undoReconciledCollections` removes the phantom in-memory registration and then calls `revertCreatedCollectionStructure`, which runs `atomicOperationsManager.executeInsideAtomicOperation(...)` — a **fresh, separate** atomic operation — to drop the orphaned `global_collection_<id>.grb` component, config entry, and data file. If the process crashes between `rollback(error, atomicOperation)` (which closed the failed commit's operation) and the completion of this fresh atomic operation, the orphan-reclaim never finishes.

**Write-path trace (evidence).** The failed commit's atomic operation is rolled back at 1757 (`rollback(error, atomicOperation)`); on the disk engine that WAL-reverts the created collection's files, so `revertCreatedCollectionStructure`'s `isComponentPresent` guard (2085) reads false and the method is a no-op (confirmed by the Javadoc and the in-memory-vs-disk discriminator design). On the in-memory engine (`DirectMemoryOnlyDiskCache`) the eager `addFile` is not reverted (documented at the method Javadoc and Surprises 2026-06-29T11:21Z), so the fresh atomic operation does the real reclaim. The in-memory engine has no durability, so a crash there loses everything anyway.

**Recovery impact.** Disk engine: no impact (guarded no-op; the rolled-back operation already reverted the files; recovery is clean). In-memory engine: a crash forgets all state regardless, so the "leak" is moot across a restart. The only live exposure is a bounded single-collection orphan within the same in-memory process if the fresh atomic operation itself fails — and that failure is logged-and-swallowed (2097-2107), explicitly accepted as "a bounded single-collection leak."

**Refutation considered.** I checked whether this orphan could reach durable bytes on the disk engine and corrupt a later id-reusing create: it cannot, because the disk path is a guarded no-op and the rolled-back atomic operation already removed the structure. I checked whether the fresh atomic operation could partially apply on disk and leave a torn structure: it is a no-op on disk, so it never writes. VERDICT: CONFIRMED but in-memory-only and bounded; not a durability defect. Recorded as a suggestion because the design accepted this trade-off explicitly (D10 + the YTDB-1175 in-memory-vs-disk divergence) and the disk path — the only durable one — is clean.

**Suggestion.** None required for correctness. If desired, a one-line note at the call site (not just the method Javadoc) that the cleanup is in-memory-engine-only and durably irrelevant would help a future reader who sees a second atomic operation on a failure path and worries about nested-commit crash atomicity.

### CS3 [suggestion] The crash-before-commit recovery test (I-A1 crash half) is @Ignore'd — the durability-recovery claim has no executing test in this track

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` (lines 2999-3016, `crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore`, `@Ignore`d)

**Crash scenario.** The central durability claim of this track (D10 / I-A1): a transaction that creates a class and crashes after the transaction body but before commit becomes durable must restore to a state with no created collection files and no registry entry. That scenario has **no executing test** in this track. The rollback half (`rolledBackInTransactionCreateLeavesNoCollection`, 2814) covers a programmatic `session.rollback()`, and the failed-apply half (`failedSchemaCommitLeavesNoPhantomRegistration`, 2881) covers a fault at/after publication. The crash-and-restore half is the `@Ignore`d breadcrumb, deferred to the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore harness.

**Recovery impact.** The crash-recovery half rests on Track 1's `ensureFileForReplay` (a verified prerequisite per the Track 1 episode) plus the buffered-intent property of `commitChanges` (D10). Both are sound by construction — file create/delete is buffered intent applied only in `commitChanges`, which rollback skips — but the end-to-end "create → crash before commit → restore → no collection" path is not exercised in CI by this track, so a regression in the interaction between reconciliation's eager registry publication, the commit-local allocator seed, and WAL replay would not be caught here.

**Refutation considered.** I checked whether the rollback test (2814) is an adequate proxy: it is not, because `session.rollback()` exits before `reconcileCollections` ever publishes anything (the rollback path skips `commitChanges` and never enters the apply body's structural reconcile), whereas a crash-before-commit must specifically test that structure *buffered inside* the atomic operation but not yet `commitChanges`-applied is reverted by WAL replay. The failed-apply test (2881) exercises the in-memory undo but injects its fault inside the live process, not across a restart, so it does not exercise WAL replay either. VERDICT: a genuine coverage gap for the durability claim, but the underlying mechanism (buffered intent + Track 1 replay) is sound and the gap is explicitly tracked as a deferred integration test. Recorded as a suggestion, not a blocker.

**Suggestion.** Land the deferred IT (or a lighter restart-based unit test using the existing `LocalPaginatedStorageRestoreFromWALIT` pattern) before this track's durability claim is relied on by Track 5/6, so the create-then-crash-before-commit path is exercised against real WAL replay rather than only against `session.rollback()`.

## Evidence base

#### C1 — CONFIRMED (survived refutation)
Promotion (`session.load` → `fromStream` → `forceSnapshot`, AbstractStorage.java:1794-1804) runs in the success branch of the `applyCommitOperations` `finally`, after `endTxCommit` (1780) made the commit durable, with no `try`/`catch`; a throw escapes with `error == null` (so no rollback), leaving the shared in-memory `committedSchema` and its immutable snapshot stale against durable state. Self-heals across a restart via open-time `fromStream`; the exposure is in-process (especially for the retry-exception family that keeps the storage OPEN per `moveToErrorStateIfNeeded`, AbstractStorage.java:4418). Asymmetric with the adjacent `cleanupSnapshotIndex` catch (1781-1792) which guards the same "commit already durable" condition. Should-fix.

#### C2 — CONFIRMED but in-memory-only / bounded (recorded as suggestion)
`revertCreatedCollectionStructure` (AbstractStorage.java:2075-2108) opens a fresh atomic operation from the failure-path `finally` to reclaim an orphaned collection structure that survives rollback only on `DirectMemoryOnlyDiskCache` (eager `addFile` not WAL-reverted; documented in the method Javadoc and Surprises 2026-06-29T11:21Z). On the disk engine the `isComponentPresent` guard (2085) makes it a no-op and the rolled-back operation (1757) already reverted the files, so the only durable path is clean. A crash mid-cleanup leaks at most one collection on the in-memory engine, which loses all state on a restart anyway. Cleanup failures are logged-and-swallowed (2097-2107) by design. No durability defect.

#### C3 — Coverage gap, mechanism sound (recorded as suggestion)
`crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore` is `@Ignore`d (SchemaCommitReconciliationTest.java:3009). The rollback test (2814) is not a proxy because `session.rollback()` exits before `reconcileCollections` publishes any structure; the failed-apply test (2881) injects an in-process fault, not a restart, so neither exercises WAL replay of buffered-but-uncommitted structural intent. The underlying durability is sound by construction (D10 buffered intent in `commitChanges`, which rollback skips; plus Track 1 `ensureFileForReplay`, verified), so the create-then-crash-before-commit path is correct but uncovered in this track's CI.

#### Scope notes (no finding)
- **Engine reconciliation is genuinely deferred to Track 5.** `doAddIndexEngine`/`publishIndexEngine` (AbstractStorage.java:3372/3403) are wired only into the public `addIndexEngine` (3326), not into `reconcileCollections` or the commit body. The engine-id commit-local allocator (`indexEngines.size()` seed) is dormant in this track; `commitIndexes` operates on already-built engines. So Track 4's structural reconciliation is collection-only, and the four-lock + commit-window machinery covers collection structure plus schema-record serialization, not engine create/drop. No crash-safety finding follows from the engine axis in this diff.
- **WAL-before-page ordering is preserved.** All structural mutations route through the atomic operation: `doCreateCollection` (collection.create + config + B-tree component, 2566-2598), `dropCollectionInternal` + `configuration.dropCollection` + `deleteComponentByCollectionId` (reconcile loop), and the record/index writes via `commitEntry`/`commitIndexes`. Durability flips at `endTxCommit` → `endAtomicOperation` (5367), after which page application follows the existing single-lifecycle-gate ordering (unchanged from the pre-track commit body, which was extracted verbatim into `applyCommitOperations`). No new bypass-WAL page mutation was introduced.
- **Provisional ids cannot reach durable bytes.** `resolveProvisionalCollectionIds` (SchemaShared.java) runs before `toStream`, and `SchemaClassImpl.toStream` asserts `noProvisionalCollectionId()` at the serialization boundary; `resolveProvisionalCollectionIds` asserts `allCollectionIdsResolved()` one step earlier. Both are `-ea`-only, so production has no runtime guard, but the resolve-before-serialize ordering (the patch list runs inside the write lock before any per-class `toStream`) is structural, not assert-dependent. No finding.
- **Commit-window lock-free reads are correctly gated.** Every readLock-skipping site (`isClosed` 1402, `getCollectionNames` 2067, `getCollectionIdByName` 2106, `getIndexEngine`/`doGetIndexEngine` 3826, `getPhysicalCollectionNameById` 4505, `readRecordInternal` 5321) tests `isCommitWindowActive()` and the window is only entered while `stateLock.writeLock()` is held (`commitSchemaCarry` 1851), which supplies the exclusion and happens-before edge for the plain-list reads. `exitCommitWindow` clamps at zero and `remove()`s the ThreadLocal, so a pooled thread cannot inherit a leaked window. No crash-safety finding; the depth-counter discipline is sound.
- **Two branch-red tests are not crash-safety defects.** `MetadataWriteMutexTest.twoConcurrentSchemaTransactionsSerializeWithoutAbort` (Track 3/7-owned MVCC contention) and `SchemaDeguardTest.renameClassInsideTransactionRecordsNewNameOnly` (changed-class recording semantics) are flagged in the track file for a Phase C reconciliation decision; neither concerns WAL correctness, durability, or recovery. Out of this dimension's scope.
