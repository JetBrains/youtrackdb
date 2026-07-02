<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: CS1, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:999, anchor: "### CS1 ", cert: C1, basis: "engine published inside buildEngineAtCommit but its id recorded only after return; an onIndexEngineChange throw leaves a phantom engine registration the failure-path undo never reverts (I-A4 hole)"}
  - {id: CS2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:900, anchor: "### CS2 ", cert: C2, basis: "eager membership mutation applied during plan build is never reverted when enrollReconciledIndexRecords throws, because the failure-path undo is gated on indexPlan != null"}
  - {id: CS3, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:655, anchor: "### CS3 ", cert: C3, basis: "commit-time index engine build/delete has no crash+WAL-replay test; only rollback and clean-reload are covered"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 5}
cert_index:
  - {id: C1, verdict: ISSUE, anchor: "#### C1 "}
  - {id: C2, verdict: ISSUE, anchor: "#### C2 "}
  - {id: C3, verdict: ISSUE, anchor: "#### C3 "}
  - {id: C4, verdict: SAFE, anchor: "#### C4 "}
  - {id: C5, verdict: SAFE, anchor: "#### C5 "}
  - {id: C6, verdict: SAFE, anchor: "#### C6 "}
  - {id: C7, verdict: SAFE, anchor: "#### C7 "}
  - {id: C8, verdict: SAFE, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### CS1 [should-fix] A phantom index engine survives a failed engine-creating commit when `onIndexEngineChange` throws

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 998-1002), with the root at `IndexAbstract.java` (line 369-380).

**What breaks.** `buildEngineAtCommit` publishes the new engine into the in-memory registries and only then wires it:

- `IndexAbstract.buildEngineAtCommit:375` calls `storage.createIndexEngineInCommitWindow(...)`, which runs `doAddIndexEngine` (WAL files + config) and then `publishIndexEngine(...)` (`AbstractStorage.java:3331`), so on return the engine is live in `indexEngines` and `indexEngineNameMap`.
- `IndexAbstract.buildEngineAtCommit:377` then calls `onIndexEngineChange` → `callIndexEngine` → `engine.init(session, im)`.
- The caller records the engine id for the failure path only *after* `buildEngineAtCommit` returns: `IndexManagerEmbedded.java:999-1000` assigns `externalId` and then appends it to `plan.createdEngineExternalIds()`.

`onIndexEngineChange` (`IndexAbstract.java:1199`) swallows and retries `InvalidIndexEngineIdException`, but a non-`InvalidIndexEngineIdException` `RuntimeException`/`IOException` from `engine.init` propagates. `buildAndDropReconciledEngines` catches only `InvalidIndexEngineIdException` (`IndexManagerEmbedded.java:1003`), so that throw escapes past line 999 — the `createdEngineExternalIds().add(externalId)` on line 1000 never runs — and reaches the commit catch. The failure-path undo, `undoReconciledIndexEngines(indexPlan.createdEngineExternalIds())` (`AbstractStorage.java:2845`), iterates a list that does not contain this engine's id, so the eagerly-published `indexEngines` slot and `indexEngineNameMap` entry are never removed.

**Crash scenario.** If the process does *not* crash — a schema-carrying index-create commit fails when `engine.init` throws after the engine is published but before the id is recorded — the storage stays open (a retry-family failure keeps it live) with a phantom engine registration whose durable config was rolled back. The engine name is now permanently in `indexEngineNameMap`, so the next attempt to create the same index throws `"Index with name ... already exists"` (`createIndexEngineInCommitWindow`, `AbstractStorage.java:3401`) even though the index does not exist on disk, and `nextFreeIndexEngineId` skips the wasted slot. This directly contradicts the track's I-A4 guarantee and the `failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId` claim ("the freed engine ids are reused on the next commit").

**Recovery impact.** On an actual process crash the state self-heals: the engine's config and files were reverted with the rolled-back atomic operation, and `openIndexes` rebuilds `indexEngines` from durable config on reopen, so no phantom survives a restart. The defect is confined to a live process after a *caught* commit failure, so it is a failed-commit registry-cleanliness bug rather than durable corruption.

**Refutation considered.** The passing test injects its fault through `postEngineBuildTestHook`, which fires at `AbstractStorage.java:2803` *after* `buildAndDropReconciledEngines` has completed and every id is recorded, so it exercises only the "all ids captured" path and cannot reach the intra-`buildEngineAtCommit` throw. `onIndexEngineChange`'s internal retry covers `InvalidIndexEngineIdException` only, so the escaping-throw window is real. See C1.

**Suggestion.** Record the engine id inside `buildEngineAtCommit` immediately after `createIndexEngineInCommitWindow` returns (before `onIndexEngineChange`), or drive `undoReconciledIndexEngines` from `plan.created()` reading each handle's `getIndexId()` instead of the separately-appended `createdEngineExternalIds` list, so every published engine is reverted regardless of where the build throws.

### CS2 [should-fix] Eager membership mutations are not reverted when `enrollReconciledIndexRecords` throws mid-way

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 895-914), with the undo gate at `AbstractStorage.java` (line 2838).

**What breaks.** `enrollReconciledIndexRecords` applies committed-index membership deltas eagerly to the shared `Index.collectionsToIndex` set while it builds the plan: `addCollectionRecordAtCommit` / `removeCollectionRecordAtCommit` mutate the in-memory set and call `save(transaction)` (`IndexAbstract.java:163-196`), and the reverting `AppliedMembership` entry is appended only after that call returns (`IndexManagerEmbedded.java:900-901, 910-911`). `save` calls `transaction.loadEntity(identity)` (`IndexAbstract.java:886`), which can throw a `RuntimeException` (for example a record-load failure) *after* the `collectionsToIndex.add`/`remove` already mutated the shared set.

If `enrollReconciledIndexRecords` throws before returning, the assignment `indexPlan = ...enrollReconciledIndexRecords(...)` (`AbstractStorage.java:2196`) never completes, so `indexPlan` stays `null`. The failure-path revert `schemaContext.indexManager().undoAppliedMembership(indexPlan)` is gated behind `if (indexPlan != null)` (`AbstractStorage.java:2838`), so it never runs. The mutations already applied to the shared committed `Index` objects are left in place while the membership record write reverts with the rolled-back atomic operation.

**Crash scenario.** A schema-carrying commit whose `addSuperClass`/alter ripple enrolls two or more membership adds, where the second `save` throws: the first (and the partially-applied second) `collectionsToIndex` additions persist on the shared committed index in memory, but their persisted `CONFIG_COLLECTIONS` records roll back. The in-memory membership now claims a collection the durable definition does not. If the process survives and a later commit inserts a record into that wrongly-covered collection, `ClassIndexManager` consults the in-memory membership and writes a durable index entry for a collection the index does not persist as a member — a durable index/definition inconsistency that survives to the next reopen.

**Recovery impact.** On an immediate process crash the state self-heals (the membership record was reverted; reopen reloads `collectionsToIndex` from the durable index entity). The durable inconsistency only materialises when the process keeps running past the caught failure and a subsequent commit writes index entries under the phantom membership.

**Refutation considered.** The reachable trigger requires `save`/`loadEntity` to throw during enrollment, which is an exceptional path, so this is narrower than CS1. The eager-mutation-before-plan-assignment ordering is confirmed against the code; the failure-path gate `indexPlan != null` cannot see mutations applied during the plan's own construction. This is the same undo-bypass family as the track's documented CS2 (`endTxCommit`-after-reconcile throw in the no-error branch), but that one is gated by the branch selection whereas this one is gated by the null plan. See C2.

**Suggestion.** Assign `indexPlan` to a plan object *before* the eager membership mutations run (or record each `AppliedMembership` on the plan before calling `save`, and have the commit hold a reference the failure path can always reach), so a throw during enrollment still lets `undoAppliedMembership` revert every applied mutation.

### CS3 [suggestion] No crash + WAL-replay test covers the commit-time index engine build or delete

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java` (line 650-656).

**What is missing.** The commit-time engine build (`createIndexEngineInCommitWindow`) and delete (`deleteIndexEngineInCommitWindow`) are new WAL-covered structural operations, yet the only crash-recovery test, `crashRecoveryOfCommitTimeIndexEngineIsDeferredToIT`, is `@Ignore`d and empty. The suite covers the rollback half (`failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId`, `failedDropCommitLeavesTheSurvivingCommittedIndexUsable`) and the clean-reload half (`builtIndexSurvivesReload`), but never a hard stop plus WAL restore that asserts the built engine's files replayed on a committed unit and are absent on a crash-before-commit unit. This is exactly the I-A1 / D10 recovery half that Track 1's lazy-consult fix underwrites.

**Recovery impact.** The mechanism reuses `doAddIndexEngine` / `doDeleteIndexEngine` / `doPut` under the commit's single atomic operation, all already exercised by the non-transactional index path and its `LocalPaginatedStorageRestoreFromWAL*` integration tests, so the residual risk is bounded. The gap is real coverage, not a proven defect.

**Suggestion.** Land the deferred integration test against the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore harness (the breadcrumb the placeholder already names), asserting the built engine's data + null-bucket + histogram files replay for a committed unit and leave no orphan for a crash-before-commit unit.

## Evidence base

#### C1 [ISSUE] Phantom engine on `onIndexEngineChange` throw — SURVIVED
Traced `buildEngineAtCommit` (`IndexAbstract.java:369-380`) publishing via `createIndexEngineInCommitWindow`→`publishIndexEngine` (`AbstractStorage.java:3331`) before `onIndexEngineChange:377`; caller records the id only at `IndexManagerEmbedded.java:1000`; the `catch` at `:1003` filters to `InvalidIndexEngineIdException`; `undoReconciledIndexEngines` (`AbstractStorage.java:2845`) reads the list the escaped throw skipped populating. Confirmed code-path gap; trigger (a non-IIEIE throw from `engine.init`) is an exceptional path, so verdict PLAUSIBLE.

#### C2 [ISSUE] Membership mutation unreverted on enroll throw — SURVIVED
`addCollectionRecordAtCommit` mutates shared `collectionsToIndex` then `save` (which can throw via `loadEntity`) before `appliedMembership.add` (`IndexManagerEmbedded.java:900-901`); a throw leaves `indexPlan` null (`AbstractStorage.java:2196`), so `undoAppliedMembership` under `if (indexPlan != null)` (`:2838`) never runs. Confirmed ordering gap; trigger requires `save`/`loadEntity` to throw, so verdict PLAUSIBLE.

#### C3 [ISSUE] Crash-recovery test absent — SURVIVED
`crashRecoveryOfCommitTimeIndexEngineIsDeferredToIT` is `@Ignore`d and empty (`CommitTimeIndexBuildTest.java:650-656`); no other test drives a hard stop + WAL restore over a commit-time engine build/delete. Confirmed coverage gap.

#### C4 [SAFE] Commit-time index population is WAL-covered by the commit's atomic operation — REFUTED as a defect
Hypothesis: `populateTxCreatedIndex`'s `doPut` writes engine pages outside the commit's atomic operation, so a crash mid-commit could leave partially-populated index pages not covered by the WAL unit. Traced `IndexMultiValues.doPut:158-159` → `session.getActiveTransaction().getAtomicOperation()` (the commit's live atomic operation, since population runs inside the open commit window) → `putRidIndexEntry(indexId, key, rid, atomicOperation)` (`AbstractStorage.java:4638`) → `putRidIndexEntryInternal` → `V1IndexEngine.put(atomicOperation, ...)`. The write takes no `stateLock` and rides the passed atomic operation, so every populated page is in the same WAL unit as the records. A crash before the end record discards the whole unit; after, it replays the whole unit. Safe.

#### C5 [SAFE] Engine files, storage config, and the index entity record commit atomically — REFUTED as a defect
Hypothesis: the engine files, the `CollectionBasedStorageConfiguration` engine entry, and the per-index entity record land in separate atomic operations, so a crash between them yields a half-registered index. Traced `doAddIndexEngine` (`AbstractStorage.java:4009`): `engine.create`, the histogram stats file, and `configuration.addIndexEngine` all take the same passed `atomicOperation`; `saveRecordAtCommit`/`deleteRecordAtCommit` enrol the index entity into the transaction's record operations, applied in the same working-set apply; `indexLinkSet.add/remove` mutates the loaded index-manager entity, also a transaction record. All ride the one commit atomic operation. Safe (and dependent on Track 1's landed lazy-consult replay fix, D10).

#### C6 [SAFE] Provisional collection ids in link values do not reach durable bytes — REFUTED as a defect
Hypothesis: a committed-class record linking to a tx-created-class entity serializes the target's provisional collection id (`<= -2`) into durable bytes, uncaught by `rewriteProvisionalRecordCollectionIds`, which rewrites only each record's own identity. Traced the rewrite through `ChangeableRecordId.setCollectionAndPosition` (`:121`) firing `IdentityChangeListener`s; the registered listeners re-key `FrontendTransactionImpl`'s rid map, `AbstractLinkBag`/`BTreeBasedLinkBag`, `FrontendTransactionIndexChanges`, and `CompositeKey`, so link-bag references re-key on the collection-id change exactly as they do for the temp-position rewrite. Scalar links hold the live `Identifiable` and resolve `getIdentity()` at serialize time in `commitEntry`, which runs after both the collection-id rewrite and the position allocation, so the serialized RID is fully real. The `computeCommitWorkingSet` assert is only a backstop; the real enforcement is the rewrite (a real `StorageException` on `NO_RESOLUTION`, not an assert). Safe.

#### C7 [SAFE] `preallocateRids` cannot hit a provisional collection id on the commit path — REFUTED as a defect
Hypothesis: `preallocateRids` calls `doGetAndCheckCollection(collectionId)` (`AbstractStorage.java:2201`), which throws for a provisional id, corrupting the commit for a tx-created-class record. PSI/grep of `preallocateRids` shows the only callers are `TransactionRidAllocationTest`; the production schema-carry commit path allocates positions in the working-set apply loop after the collection-id rewrite, never through `preallocateRids`. Not reachable in production. Safe.

#### C8 [SAFE] The process-wide schema `version` generator is not a durability hazard — REFUTED as a defect
Hypothesis: routing every `SchemaShared.version` advance through the JVM-wide `VERSION_GENERATOR` (`SchemaShared.java:1487`) persists non-reproducible version numbers that break staleness comparisons across a restart. The `version` field is the in-memory snapshot-generation token consumed by `EntityImpl`'s `immutableClazz` cache (compared only for inequality); it is re-seeded from the generator on construction and `fromStream`, so a reopen assigns a fresh unique number and pre-restart cache entries are gone. It is distinct from the persisted schema *format* version (`CURRENT_VERSION_NUMBER = 6`). No durable dependency. Safe.
