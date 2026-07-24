<!--MANIFEST
dimension: bugs-concurrency
step: 5.2
iteration: 1
commit_range: d2b1632652~1..d2b1632652
verdict: CHANGES_REQUESTED
counts: {blocker: 0, should-fix: 3, suggestion: 1}
evidence_base: cert-index-below
cert_index: [C1, C2, C3, C4]
flags: [psi-backed]
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-failed-commit-does-not-restore-a-tx-dropped-indexs-in-memory-engine-registry"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2710
    cert: C1
    basis: "PSI: undoReconciledIndexEngines has one caller (applyCommitOperations) and iterates createdEngineExternalIds only; deleteIndexEngineInCommitWindow mutates indexEngines/indexEngineNameMap synchronously"
  - id: BC2
    sev: should-fix
    anchor: "#bc2-drop-then-recreate-of-the-same-index-name-in-one-transaction-throws-already-exists-and-leaks-the-old-engine"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3155
    cert: C2
    basis: "Read: overlay recordDropped+recordCreated nets to txCreated-only; in-tx createIndex path (lines 503-541) has no committed-name guard; createIndexEngineInCommitWindow rejects the still-present name"
  - id: BC3
    sev: should-fix
    anchor: "#bc3-the-only-failed-commit-test-injects-its-fault-before-the-engine-is-built-so-the-engine-revert-arm-is-never-exercised"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:299
    cert: C3
    basis: "Read: commitWindowTestHook fires at AbstractStorage.java:2511, before enroll (2562) and build (2670); indexPlan is null when it throws, so undoReconciledIndexEngines/revertCreatedIndexEngineStructure never run"
  - id: BC4
    sev: suggestion
    anchor: "#bc4-v1-empty-source-bound-relies-on-an-approximate-record-count"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:417
    cert: C4
    basis: "Read: rejectNonEmptySourceCollection uses getApproximateRecordsCountInCommitWindow > 0; populateTxCreatedIndex only scans tx record ops, so an under-reporting count would silently drop committed rows"
-->

## Findings

### BC1 [should-fix] Failed commit does not restore a tx-dropped index's in-memory engine registry

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2710-2719, 3207-3221, 3240-3251)

**Issue**: The failure-path undo reverts only *created* engines, never *dropped* ones, but the drop side already mutated the in-memory engine registry synchronously. `deleteIndexEngineInCommitWindow` (line 3207) runs `doDeleteIndexEngine` and then `indexEngines.set(internalIndexId, null)` + `indexEngineNameMap.remove(engine.getName())` (lines 3219-3220) as an eager, non-WAL side effect. On a failed commit the finally block calls `undoReconciledIndexEngines(indexPlan.createdEngineExternalIds())` (line 2719), which iterates only the *created* engine ids (confirmed by PSI: it has a single caller and reads `createdEngineExternalIds`). Nothing restores the dropped engine's `indexEngines` slot or its `indexEngineNameMap` entry. The comment at lines 2715-2718 justifies this by pointing at the deferred *shared-lookup-map* (`indexes`) removal — but that is a different map from the *engine registry* (`indexEngines`/`indexEngineNameMap`) that `deleteIndexEngineInCommitWindow` already emptied.

**Evidence**: `commitSchemaCarry` (line 2821) → `applyCommitOperations` try body → `buildAndDropReconciledEngines` (`IndexManagerEmbedded.java:942`) builds creates first, then loops drops calling `deleteIndexEngineInCommitWindow`. If a *later* drop fails after an earlier drop already completed its map mutation — two dropped indexes where the second's `checkIndexId`/`doDeleteIndexEngine` throws (`InvalidIndexEngineIdException`/`IOException`) — `error` is set, control reaches the finally, `rollback` reverts the WAL files, and `undoReconciledIndexEngines` skips the drops. Result: `indexes["Foo"]` still holds the committed index handle (publish phase was skipped), but `indexEngines[internalId]` is `null` and `indexEngineNameMap` lost the name. On the disk profile the files came back but the in-memory slot is gone, so the next use of that committed index dereferences a `null` engine slot → NPE or `InvalidIndexEngineIdException`. The `endTxCommit`-throws-in-finally-else path amplifies this (neither creates nor drops are undone then), but that is a broader pre-existing shape.

**Refutation considered**: Could WAL rollback restore the in-memory maps? No — WAL reverts files, not `indexEngines`/`indexEngineNameMap`. Could `undoReconciledIndexEngines` already cover drops? No — PSI shows it reads `createdEngineExternalIds` only. Is the trigger reachable? The narrow in-`try` trigger is 2+ drops with a later delete failure; the single-drop-plus-`endTxCommit`-failure path also reaches it. Both are rare (schema-change rate is low), so should-fix rather than blocker. Track 6 adds a rename category riding this same publish/undo machinery, which will make multi-index reconciliations more common.

**Suggestion**: Add a drop-restore arm symmetric to the create arm: capture the dropped engines (or their `IndexEngineData`) in the plan during phase 2, and on the failure path re-register them into `indexEngines`/`indexEngineNameMap` (the WAL brought the files back on disk; the in-memory profile needs the re-`addFile` guard analogous to `revertCreatedIndexEngineStructure`). At minimum, correct the comment at lines 2715-2718 so it does not claim the engine-registry removal is deferred when `deleteIndexEngineInCommitWindow` performs it eagerly.

### BC2 [should-fix] Drop-then-recreate of the same index name in one transaction throws "already exists" and leaks the old engine

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 3155); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 503-541)

**Issue**: A transaction that drops a committed index and then recreates an index with the same name commits into a broken state. In the overlay, `recordDropped("Foo")` adds `Foo` to `txDropped`, then `recordCreated(Foo)` does `txDropped.remove("Foo"); txCreated.put("Foo", handle)` (`IndexOverlay.java:106-127`), so `Foo` ends up in `txCreated` only, never in `txDropped`. At commit `enrollReconciledIndexRecords` therefore treats `Foo` as a pure create with no matching drop, so the *old* committed `Foo` engine is never deleted. In phase 2 creates run before drops, so `createIndexEngineInCommitWindow` for the new `Foo` reaches the guard `indexEngineNameMap.containsKey("Foo")` (line 3155), which is still true, and throws `IndexException("Index with name Foo already exists")` — failing the whole commit with a misleading message. Had the guard not fired, the old engine would leak (no drop enrolled to delete it).

**Evidence**: The in-transaction `createIndex` path (lines 503-541) records the create purely through `txState.ensureIndexOverlay().recordCreated(deferredHandle)` (line 538) with no check that a committed index of the same name exists; the committed-path duplicate guard is bypassed for the de-guarded in-tx branch. `dropIndex` records the drop via `recordDropped(iIndexName)` (`IndexManagerEmbedded.java:742`). Sequencing the two in one tx nets to a create-only overlay entry.

**Refutation considered**: Is drop+recreate-same-name reachable? Yes — it is a plausible "replace an index's definition/type in one transaction" pattern, and nothing on the in-tx path rejects it. Could a higher layer reject the duplicate create before commit? No — the in-tx create branch (503-541) does not consult the committed registry, and the deferred handle is intentionally absent from it. Verdict: real, specific scenario, low frequency → should-fix.

**Suggestion**: On the in-tx `createIndex` path, if the name exists in the committed registry and is not being dropped in this transaction, reject early (as the committed path does); or make `recordCreated` after a `recordDropped` of the same name record a *replace* (keep the name in `txDropped` so the old engine is deleted before the new one is built). Either way the commit-time build must not collide with a still-registered engine of the same name.

### BC3 [should-fix] The only failed-commit test injects its fault before the engine is built, so the engine-revert arm is never exercised

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java` (line 289-320)

**Issue**: `failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId` documents that it exercises "the create-side engine-file revert arm" on the in-memory profile (the arm added as `undoReconciledIndexEngines` → `revertCreatedIndexEngineStructure`, mirroring the collection arm that YTDB-1175 caught). It does not. The injected fault runs through `commitWindowTestHook`, which fires at `AbstractStorage.java:2511` — after `reconcileCollections` but *before* `enrollReconciledIndexRecords` (line 2562) and *before* `buildAndDropReconciledEngines` (line 2670). When the hook throws, `indexPlan` is still `null`, so the entire `if (indexPlan != null)` failure block (including `undoReconciledIndexEngines`) is skipped and no engine was ever built. The test's assertions (`existsIndex` false, `engineIsRegistered` false, id reused) pass trivially because no engine registration occurred in the failed commit.

**Evidence**: The hook site (2505-2514) sits above phase-1 enroll and phase-2 build. `createdEngineExternalIds` is populated only inside `buildAndDropReconciledEngines`. With the fault fired before that call, the list is empty, `undoReconciledIndexEngines` iterates nothing, and `revertCreatedIndexEngineStructure` (the in-memory engine-file-leak drop, `AbstractStorage.java:3255`) never runs. No other test injects a phase-2 fault, and no test calls the revert methods directly.

**Refutation considered**: Is the revert arm actually load-bearing? Yes — the plan (`track-5.md` Plan of Work item 1) and Validation both require asserting failed-commit engine cleanliness "on both the in-memory and disk profiles," and cite the in-memory `addFile`-not-reverted-on-rollback defect (YTDB-1175) that this arm exists to close. Leaving it unverified is a real resource-leak risk (a surviving in-memory engine file blocks the next id-reusing create), not a cosmetic gap. Could the trivial pass still catch a regression? No — a broken revert arm would leave the assertions green because the arm is never entered.

**Suggestion**: Add a fault-injection seam that fires *after* the engine build (for example a second hook fired inside `buildAndDropReconciledEngines` after the first `buildEngineAtCommit`, or throw from a definition whose `getDocumentValueToIndex` fails during population) so a non-empty `createdEngineExternalIds` reaches `undoReconciledIndexEngines`, then assert on the in-memory profile that the next id-reusing create succeeds (which is exactly the leak `revertCreatedIndexEngineStructure` prevents).

### BC4 [suggestion] v1 empty-source bound relies on an approximate record count

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 417)

**Issue**: `rejectNonEmptySourceCollection` gates the v1 build on `getApproximateRecordsCountInCommitWindow(collectionId) > 0`. The population (`populateTxCreatedIndex`) then scans only the transaction's own record operations, on the premise that the committed base is empty. If the approximate count ever under-reports (returns 0 while committed rows exist), the bound is silently bypassed and those committed rows are never indexed, producing a built index that is missing entries — a silent correctness defect rather than a loud rejection.

**Evidence**: The population loop iterates `transaction.getRecordOperationsInternal()` only; there is no committed-row scan (the v1 design deliberately relies on the empty-source bound). The bound's soundness is only as good as the exactness of `getApproximateRecordsCount()` for an empty collection.

**Refutation considered**: For a freshly created (same-tx) collection the count is 0 by construction and exact, so the common path is safe. The risk is confined to a pre-existing collection whose approximate count can drift to 0 while non-empty. D12 documents the approximate-count reliance as an accepted v1 caveat, so this is a suggestion, not a defect in this step.

**Suggestion**: If an exact empty check is cheaply available inside the commit window, prefer it for the bound; otherwise document at the call site that the guarantee is exact only because same-tx source collections are empty by construction, and that a populated pre-existing source is out of scope until YTDB-1064.

## Evidence base

#### C1: undoReconciledIndexEngines reverts creates only; drop path mutates the engine registry eagerly
- PSI (`ReferencesSearch`, project scope): `AbstractStorage#undoReconciledIndexEngines` has exactly one code caller, `applyCommitOperations`; it is passed `indexPlan.createdEngineExternalIds()`. `AbstractStorage#deleteIndexEngineInCommitWindow` has exactly one code caller, `IndexManagerEmbedded#buildAndDropReconciledEngines`. (Other reported hits resolve to `{@link}` javadoc references in neighboring methods.)
- Read: `deleteIndexEngineInCommitWindow` (AbstractStorage.java:3207-3221) runs `doDeleteIndexEngine` then `indexEngines.set(internalIndexId, null)` + `indexEngineNameMap.remove(...)`. The finally block (2700-2725) calls `undoReconciledIndexEngines(createdEngineExternalIds)` and `undoAppliedMembership` only; no drop-engine restore.
- Verdict CONFIRMED-as-issue (survived refutation: WAL reverts files not in-memory maps; undo path is create-keyed; trigger reachable via 2+ drops or endTxCommit failure).

#### C2: drop-then-recreate-same-name nets to a create-only overlay entry with no committed-name guard
- Read: `IndexOverlay.recordCreated` (106-110) removes the name from `txDropped`; `recordDropped` (120-127) adds committed names to `txDropped`. Sequenced drop-then-create leaves the name in `txCreated` only.
- Read: in-tx `createIndex` branch (IndexManagerEmbedded.java:503-541) records via `recordCreated` with no committed-registry duplicate check. `createIndexEngineInCommitWindow` guard at AbstractStorage.java:3155 throws on a still-present name; creates run before drops in `buildAndDropReconciledEngines` (960-968).
- Verdict CONFIRMED-as-issue (survived refutation: pattern reachable, no earlier rejection on the in-tx path).

#### C3: the failed-commit test fires its fault before any engine is built
- Read: `commitWindowTestHook` fires at AbstractStorage.java:2511, above `enrollReconciledIndexRecords` (2562) and `buildAndDropReconciledEngines` (2670). `indexPlan` is assigned at 2562, so it is `null` when the hook throws; the `if (indexPlan != null)` failure block (2710-2724) is skipped.
- Read: `CommitTimeIndexBuildTest` line 299 is the only `setCommitWindowTestHook` fault injection in the diff; no test calls `undoReconciledIndexEngines`/`revertCreatedIndexEngineStructure` directly.
- Verdict CONFIRMED-as-issue (survived refutation: revert arm is load-bearing per plan + YTDB-1175; trivial pass cannot catch a broken arm).

#### C4: v1 bound rests on an approximate count; population scans tx ops only
- Read: `rejectNonEmptySourceCollection` (IndexManagerEmbedded.java:406-424) uses `getApproximateRecordsCountInCommitWindow > 0`; `populateTxCreatedIndex` (478-520) iterates `transaction.getRecordOperationsInternal()` only.
- Verdict: refuted to suggestion — common path (same-tx empty collection) is exact; residual drift risk is a documented D12 v1 caveat, not a defect introduced here.
