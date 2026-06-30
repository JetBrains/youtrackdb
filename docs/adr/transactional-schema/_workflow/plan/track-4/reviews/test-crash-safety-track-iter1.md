<!--
MANIFEST
dimension: test-crash-safety
scope: track
track: 4
iteration: 1
commit_range: 1dd9c0424f40e7aa9ec90858f6eb4b235f3a2c5f..2f295a881f
verdict: CHANGES_REQUESTED
findings_total: 3
blockers: 0
should_fix: 2
suggestions: 1
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3]
flags: []
index:
  - id: TY1
    sev: should-fix
    anchor: "#ty1-crash-before-commit-and-committed-structural-wal-replay-have-zero-executing-coverage"
    loc: "SchemaCommitReconciliationTest.java:3009-3016 (@Ignore crash breadcrumb); AbstractStorage.java applyCommitOperations / reconcileCollections (commit-durable + WAL-revert paths)"
    cert: C1
    basis: "track Purpose + Validation name crash-before-commit (I-A1) and committed-delete WAL redo (D10); the only crash test is @Ignore'd and reOpen() is a session re-open, not a storage restart"
  - id: TY2
    sev: should-fix
    anchor: "#ty2-durable-round-trip-tests-do-not-restart-storage-so-the-no-provisional-on-disk-and-f59-claims-bite-only-on-the-disk-profile"
    loc: "SchemaCommitReconciliationTest.java:2741-2762, 2836-2863, 3266-3287, 3510-3540; DbTestBase.java:126-150"
    cert: C2
    basis: "DbTestBase default is MEMORY; reload() re-parses the same in-memory storage and reOpen() re-opens only the session, so the durable-bytes (I-A2) and counter-persistence (F59) assertions never round-trip through disk unless youtrackdb.test.env=ci"
  - id: TY3
    sev: suggestion
    anchor: "#ty3-no-fault-seam-after-commitchanges-and-the-divergence-from-the-acceptance-text-is-undocumented-at-the-test-surface"
    loc: "SchemaCommitReconciliationTest.java:2881-2940 (failedSchemaCommitLeavesNoPhantomRegistration); AbstractStorage.java applyCommitOperations (hook fires before endTxCommit)"
    cert: C3
    basis: "track Idempotence section requires a fault 'at or after commitChanges'; the implemented hook fires before endTxCommit, matching the publish-during-reconcile design, but the after-commitChanges partial-durability window has no seam and the divergence is unrecorded in the test class"
-->

## Findings

### TY1 [should-fix] Crash-before-commit and committed-structural WAL replay have zero executing coverage

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` (lines 3009-3016, the `@Ignore`'d breadcrumb)
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` — `applyCommitOperations` (the `startTxCommit`/`endTxCommit` atomic-operation window) and `reconcileCollections` (the WAL-buffered structural create/drop)

**Evidence** (CRASH POINT / TEST TRACE):
- CRASH POINT 1 — between the transaction body and a durable commit. The track Purpose states the goal verbatim: "a rolled-back or crashed-before-commit schema transaction leaves storage byte-for-byte unchanged." D10 splits this into a *rollback* half and a *crash* half; the crash half "leans on Track 1's `ensureFileForReplay`" and "reuses the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore pattern."
- CRASH POINT 2 — the redo side of D10: "A committed delete is permanent and redone from the WAL after a crash." A schema-carrying commit's collection create/drop rides the commit atomic operation (`doCreateCollection` / `dropCollectionInternal` + `configuration.dropCollection` + `deleteComponentByCollectionId`), so a crash *after* the commit became durable must replay those structural ops on restore.
- TEST TRACE — the rollback half is covered (`rolledBackInTransactionCreateLeavesNoCollection` at line 2814; `SchemaDeguardTest.rolledBackInTransactionCreateLeavesNoCollectionOnDisk` at diff 3619; `...SetAbstractFalse...` at diff 3656). The *crash* half is covered by nothing that executes: `crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore` is `@Ignore`'d with an empty body, and there is no test that crashes after commit and replays the committed structural create/drop from the WAL.
- The `reload()` + `reOpen()` pattern the durable tests use is **not** a crash-restart: `DbTestBase.reOpen` (DbTestBase.java:139-150) closes and re-opens the *session* and pool but leaves `youTrackDB` (the storage-holding context) open, and on the default `DatabaseType.MEMORY` profile the storage never touches disk. So no test in this track exercises a true storage stop-and-restore or any WAL replay of the reconciliation's structural ops.

**Missing scenario**: (a) Create a class (and ideally an inserted record) inside a transaction, stop the storage hard before commit becomes durable, restore from the WAL, and assert the created collection and its files are absent — the close-copy-restore harness the breadcrumb names. (b) Create-and-commit a class, then drop-and-commit it, crash after the drop is durable, restore, and assert the collection files and registry entry stay gone (the committed-delete WAL redo). VERDICT for both: NOT TESTED.

**Why it matters**: D10/I-A1 is the entire crash-safety contract of this track — structural change is atomic with the record writes and recoverable from the WAL. The reconciliation buffers file create/delete as WAL-reverted intent applied only in `commitChanges`; the claim that a crash-before-commit leaves files byte-for-byte unchanged, and that a committed drop redoes on restore, is asserted only in prose. A regression in the atomic-operation buffering (a structural write that escapes the WAL, or a redo that fails to replay the config-entry/component drop) would corrupt storage on a real crash and pass every test in this track. This is the highest-value crash test the track names and the one it does not run.

**Suggested test** (skeleton — needs the close-copy-restore harness, hence the `@Ignore` today):
```java
@Test
public void crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore() throws Exception {
  // 1. Create a class inside a tx; do NOT commit. Capture the storage data dir.
  // 2. Copy the live storage dir to a sibling path WITHOUT a clean close
  //    (LocalPaginatedStorageRestoreFromWALIT close-copy-restore pattern).
  // 3. Open the copy; WAL replay runs (ensureFileForReplay from Track 1).
  // 4. Assert: no <class>_<n> collection file/registry entry exists and the
  //    class is absent from the restored committed schema.
}

@Test
public void committedDropRedoesFromWalAfterCrash() throws Exception {
  // 1. Create+commit a class with a record and an index; commit a drop of it.
  // 2. Close-copy the storage AFTER the drop commit is durable but before a clean shutdown.
  // 3. Open the copy; assert the dropped collection's files/registry entry stay gone
  //    and the data is unreadable (the committed delete redid from the WAL).
}
```

### TY2 [should-fix] Durable round-trip tests do not restart storage, so the "no provisional on disk" and F59 claims bite only on the disk profile

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` — `committedClassAndCollectionSurviveReload` (line 2741), `droppedClassRemovesItsCollectionAcrossCommit` (line 2836), `classCreateAdvancesCounterPersistedThroughRestartSoNamesDoNotCollide` (line 3266), `classRenameRewritesOnlyTheClassRecordAndLeavesTheRootUnwritten` (line 3510)
**Production code**: `DbTestBase.java:126-150` (`calculateDbType` defaults to `MEMORY`; `reOpen` re-opens only the session)

**Evidence** (RECOVERY CHECK):
- These tests assert durability-flavoured postconditions: "no provisional id reached durable bytes" (I-A2, line 2758), the dropped collection "must not exist after a reload" (line 2861), the advanced counter "persisted through restart so names do not collide" (the F59 root-omission guard, line 3266), and a renamed class keeps its collection ids "after a durable reload" (line 3537). Each calls `schemaShared().reload(session)` then `reOpen("admin", ADMIN_PASSWORD)`.
- The test Javadoc claims the reload "forces a fromStream re-parse on every storage profile" (line 2738) — which is accurate, but a `fromStream` re-parse is not a durable round trip. On the default `MEMORY` profile the schema records are re-read from the *same in-memory storage instance* that the commit wrote; the bytes never touch disk and the storage is never restarted. The "no `<= -2` provisional id survived on disk" and "the counter persisted through a restart" assertions therefore test the in-memory promotion path, not durability, unless the suite runs under `youtrackdb.test.env=ci` (which flips `calculateDbType` to `DISK`).
- This is the same caveat the track's own Step-4 review raised (TY5) and it is unresolved at the track level: the durable-bytes half of I-A2 is, by the implementer's own Step-2 episode, "deferred to Step 3's reconciliation plus the crash-restore harness."

**Missing scenario**: A round trip that actually re-reads from disk — either a dedicated disk-profile assertion (a test that constructs a `DISK`-typed context, commits, fully closes `youTrackDB`, re-opens, and asserts) or, minimally, an explicit acknowledgement that the durable-bytes assertions are CI-profile-only so a green local run is not mistaken for durability coverage. VERDICT: WRONG TIMING — the assertions fire after an in-process reload, not after a storage restart, on the profile the suite runs by default.

**Why it matters**: The most consequential corruption this track can produce is a provisional id (`<= -2`) reaching durable bytes — the F58 silent-corruption case D2 calls out ("the class loses its collections at the next open"). The `assert noProvisionalCollectionId()` in `SchemaClassImpl.toStream` guards the write boundary at runtime, but the *test* that "no provisional id survived a reload" only proves it for an in-memory re-parse by default. A serialization-ordering regression that wrote a provisional id to a real on-disk page, then resolved it correctly only in the in-memory promotion, would pass these tests on the default profile and corrupt a real (disk) database. The coverage exists but does not bite where the corruption lives.

**Suggested test**:
```java
@Test
public void noProvisionalIdSurvivesAFullDiskRestart() {
  // Build a DISK-typed YouTrackDB context (not the MEMORY default), create+commit a
  // class, fully close the context, re-open it, and assert every collection id is >= 0
  // and resolves to an existing on-disk collection. Run unconditionally so the
  // durable-bytes guarantee has teeth on every CI lane, not only youtrackdb.test.env=ci.
}
```

### TY3 [suggestion] No fault seam after `commitChanges`, and the divergence from the acceptance text is undocumented at the test surface

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` — `failedSchemaCommitLeavesNoPhantomRegistration` (line 2881), `failedSchemaCommitWithDropRestoresDroppedRegistration` (line 2954)
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` — the `commitWindowTestHook.run()` call fires after `reconcileCollections` and *before* `computeCommitWorkingSet`/`endTxCommit` (which invokes `endAtomicOperation` → `commitChanges`)

**Evidence** (CRASH POINT / RECOVERY CHECK):
- The failed-commit tests are a genuine improvement over the Step-4 state (where the fault hook had not landed) — they exercise the net-new `undoReconciledCollections` create-phantom and drop-restore arms, use a `NeedRetryException`-family fault so the storage stays OPEN and the in-memory registry is observable, and assert id reuse. The recovery path under test is real and the assertions are precise.
- However, the track's `## Idempotence and Recovery` section specifies the fault point as "fired **at or after `commitChanges`**" and states "Failing **before** `commitChanges` does not exercise the deferred-publication window." The implemented hook fires *before* `endTxCommit`/`commitChanges`. This is not a defect: the implementation switched from the planned defer-publication-past-`commitChanges` approach to publish-during-reconcile + undo-on-failure (track Surprises 2026-06-26T15:39Z), which moves the critical window *earlier*, so the hook correctly targets it. The test exercises the actual recovery code.
- The remaining untested window is the *post*-`commitChanges* partial-durability case: a fault after the WAL is durable but before in-memory promotion completes. There is no seam to inject a fault there, and no test or breadcrumb records that this window (and the original acceptance wording) was consciously superseded.

**Missing scenario**: Nothing for the implemented design needs adding (the before-`commitChanges` window is the right one). What is missing is a one-line breadcrumb in the test class noting that the I-A4 acceptance text's "at or after `commitChanges`" was superseded by the publish-during-reconcile design, so the hook fires before `endTxCommit` by design — so a future reader auditing against the track's literal acceptance criteria does not mistake the timing for a gap. Optionally, a fault-after-`commitChanges` seam if the post-durable promotion path is later judged to carry its own recovery risk.

**Why it matters**: Low severity — the recovery code is exercised and correct. The risk is purely auditability: the next reviewer comparing the test against the track's `## Idempotence and Recovery` wording will find an apparent contradiction (text says "at or after `commitChanges`", code fires before) with no in-test note that the design moved. A short comment closes the loop at the surface where the discrepancy is visible.

**Suggested change** (no skeleton needed — a comment in `failedSchemaCommitLeavesNoPhantomRegistration`):
```java
// NOTE: the track's I-A4 acceptance text asks for a fault "at or after commitChanges".
// The publish-during-reconcile + undo-on-failure design (Surprises 2026-06-26T15:39Z)
// moved the critical window earlier: reconcileCollections publishes synchronously, so
// the recovery path is exercised by faulting BEFORE endTxCommit, which is what the
// in-window hook does. The post-commitChanges promotion has no separate recovery arm.
```

## Evidence base

The Phase-3 refutation pass tested each candidate against the production commit path
(`AbstractStorage.applyCommitOperations` / `commitSchemaCarry` / `reconcileCollections` /
`undoReconciledCollections`), the test harness (`DbTestBase.reOpen` / `calculateDbType`),
and the track's `## Validation and Acceptance` + `## Idempotence and Recovery` sections.
Candidates that survived as issues are stated in one line; refuted candidates are written
in full.

#### C1 — Crash-before-commit and committed-structural WAL replay are untested (TY1) — CONFIRMED
Survived: the only crash test (`crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore`,
line 3009) is `@Ignore`'d with an empty body; `grep` over both new test classes finds no
close-copy / storage-close / `Files.copy` / restore pattern (only the `@Ignore` Javadoc
mentions `LocalPaginatedStorageRestoreFromWALIT`); `DbTestBase.reOpen` re-opens the session,
not the storage, and the default profile is `MEMORY`. The track itself owns the deferral
(Step-4 episode, `## Idempotence and Recovery`), which confirms rather than refutes the gap.

#### C2 — Durable round-trip tests do not restart storage (TY2) — CONFIRMED
Survived: `DbTestBase.calculateDbType` returns `MEMORY` unless `youtrackdb.test.env` is `ci`/`release`;
`reOpen` leaves `youTrackDB` open; `SchemaShared.reload` re-parses from the live storage. The
durable-bytes (I-A2) and counter-persistence (F59) assertions are therefore in-memory re-parse
checks by default. The runtime `assert noProvisionalCollectionId()` in `SchemaClassImpl.toStream`
narrows but does not close the gap (it guards the write boundary, not a disk round trip).

#### C3 — Fault-hook timing vs. the acceptance text (TY3) — CONFIRMED as a documentation-only issue
Refutation outcome: the candidate "the failed-commit test injects the fault at the wrong point"
was **refuted as a defect**. The hook fires after `reconcileCollections` (which publishes
synchronously) and before `endTxCommit`; this is the correct critical window for the
publish-during-reconcile + undo-on-failure design the implementation actually uses (track Surprises
2026-06-26T15:39Z; `undoReconciledCollections` + `structurePublished` guard in
`applyCommitOperations`). The test exercises the real recovery arms (create-phantom drop and
drop-restore) with a retry-family fault that keeps the storage OPEN, so the registry cleanliness is
genuinely observable. What survives is only the auditability mismatch: the track's literal
"at or after `commitChanges`" wording is superseded but not annotated at the test surface, and the
post-`commitChanges` promotion window has no fault seam. Downgraded from a correctness finding to a
suggestion accordingly.

#### Production asserts added by this track — reviewed, no recommendations
The track added six well-formed, zero-cost `assert` statements that protect exactly the
commit-window invariants this dimension cares about; none duplicates an `if`, has side effects,
sits in a tight loop, or is tautological, and the two multi-id scans were extracted into helpers
(`noProvisionalCollectionId`, `allCollectionIdsResolved`) per the JaCoCo+assert guidance:
- `SchemaClassImpl.toStream` — `assert noProvisionalCollectionId()`: no provisional id reaches
  durable bytes (I-A2 / F58), caught at the serialization boundary rather than as "class lost its
  collections" at the next open.
- `SchemaShared.resolveProvisionalCollectionIds` — `assert allCollectionIdsResolved()`: the
  post-resolution postcondition, catching a producer that allocated a provisional id never recorded
  into the resolution map one step before `toStream`'s boundary assert.
- `reconcileCollections` — `assert realId >= collections.size() || collections.get(realId) == null`:
  the commit-local allocator must hand back a genuinely free slot, or `doCreateCollection` would
  overwrite a live collection and the undo would skip a real cleanup.
- `exitCommitWindow` — `assert depth[0] > 0`: an unbalanced exit is surfaced loudly under `-ea`
  before the production clamp absorbs it.
- `SchemaPropertyProxy.recordWriteTarget` — `assert ownerClass != null`: a tx-local property
  resolved for a write must have an owner class to record changed.
- `doGetIndexEngine` / `getIndexEngine` — `assert internalId == engine.getId()`: the registry slot
  matches the engine's self-reported id (carried forward from the pre-existing lock-based path).

No additional assert is recommended. The one invariant that would be a natural candidate — the
commit-local allocator seed and the four-lock-order reads must run while `stateLock.writeLock()`
is held — is not assertable: `ScalableRWLock` exposes no `isWriteLockedByCurrentThread()`, and the
production code already documents the precondition in prose at every lock-free read site, which is
the correct treatment given the missing owner-thread query.
