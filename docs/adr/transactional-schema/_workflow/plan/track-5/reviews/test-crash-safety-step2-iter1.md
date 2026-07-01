<!--MANIFEST
dimension: test-crash-safety
step: 5.2
iteration: 1
commit_range: d2b1632652~1..d2b1632652
evidence_base: 4
cert_index: [C1, C2]
flags: []
findings:
  - id: TY1
    sev: should-fix
    anchor: "#ty1-failed-commit-test-fires-its-fault-before-any-engine-is-created-so-the-create-side-revert-arm-is-never-exercised"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:1239
    cert: C1
    basis: "PSI call-chain + source ordering: hook at AbstractStorage.java:2511 precedes indexPlan assignment at :2562 and buildAndDropReconciledEngines at :2676"
  - id: TY2
    sev: suggestion
    anchor: "#ty2-no-crash-before-commit-breadcrumb-for-the-engine-file-wal-revert-arm-unlike-the-collection-arm"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:390
    cert: C2
    basis: "Track-4 sibling SchemaCommitReconciliationTest.java:385-392 documents the collection arm's crash half with an @Ignore breadcrumb; the engine arm has none"
-->

# Test crash-safety & assertions review — Track 5, Step 2 (iter 1)

Two findings. The one that matters: the failed-commit test that claims to exercise the create-side engine-file revert arm fires its fault before any engine is created, so the revert code never runs and the "no phantom engine / id reused" post-conditions pass trivially (TY1, should-fix). Secondary: the engine-file WAL-revert arm has no crash-before-commit breadcrumb, unlike the collection arm Track 4 documented honestly (TY2, suggestion). The three new production `assert` statements are all well-formed, zero-cost invariant guards; no assert defects and no redundant-assert recommendations.

## Findings

### TY1 — Failed-commit test fires its fault before any engine is created, so the create-side revert arm is never exercised

**Severity:** should-fix
**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java` (lines 1239-1282, `failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId`)
**Production code:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (hook fire at line 2511; `indexPlan` assignment at line 2562; `buildAndDropReconciledEngines` at line 2676; failure-path `if (indexPlan != null)` at line 2710) and `undoReconciledIndexEngines` / `revertCreatedIndexEngineStructure` (lines 3240-3281)

**Evidence (CRASH POINT + TEST TRACE).** The test's own Javadoc states its intent: it exercises "the create-side engine-file revert arm" of the failed-commit engine-cleanliness guarantee (I-A4 / TB2), and its comment says the fault "fires on the failure-path side" after the engine is published. That is the crash point the plan asks this step to cover: `Validation and Acceptance` requires that after a failed engine-creating commit, `indexEngines`/`indexEngineNameMap` carry no phantom entry *and* the create-side revert arm (mirroring Track 4's component-guarded `undoReconciledCollections`) drops the surviving in-memory engine files.

Trace of what the test actually reaches:

1. The test installs `commitWindowTestHook` to throw `CommandInterruptedException`, then commits a tx that created an index on an empty class.
2. In `applyCommitOperations`, `reconcileCollections` runs (line 2501), then the hook fires at **line 2511-2514** and throws.
3. `indexPlan` is assigned only later, at **line 2562** (`enrollReconciledIndexRecords`). The engine is created and published only in phase 2, `buildAndDropReconciledEngines` at **line 2676** — which calls `createIndexEngineInCommitWindow` → `publishIndexEngine`. Both are past the throw.
4. In the failure-path `finally`, the engine-undo is gated on `if (indexPlan != null)` at **line 2710**. Because the hook threw at line 2511, `indexPlan` is still `null`, so `undoReconciledIndexEngines` and `undoAppliedMembership` are **skipped entirely**. `revertCreatedIndexEngineStructure` — the fresh-atomic-op, component-guarded file drop the test's Javadoc names as the exercised arm — is never called.

PSI find-usages confirms the only call path to engine publication is `buildAndDropReconciledEngines` (phase 2) → `IndexAbstract.buildEngineAtCommit` → `AbstractStorage.createIndexEngineInCommitWindow`; there is no earlier caller, so no engine can exist when the hook fires.

**Missing scenario / why it matters.** The intended crash point is "commit created and published an index engine, then failed before the record apply made it durable" — the engine-file leak the in-memory profile (`DirectMemoryOnlyDiskCache` never reverts an eager `addFile`) is prone to, and the exact leak shape YTDB-1175 recorded for the collection arm. The test never reaches that state: it fails one step earlier (structure reconciled, engine not yet created), so the phantom-engine assertions (`existsIndex` false, `engineIsRegistered` false, `loadIndexEngine == -1`, id reused) all pass **vacuously** — they would pass even if `undoReconciledIndexEngines`/`revertCreatedIndexEngineStructure` were deleted. A real regression in the engine-revert arm (e.g. dropping the component-present guard, or a wrong id in the fresh atomic op) would ship green. This is a false-coverage crash-safety test: it looks like it protects the failed-commit engine-cleanliness invariant but exercises no engine lifecycle at all.

**Suggested test.** Fire the fault *after* the engine is published, not before, so the create-side revert arm actually runs. The existing pre-engine hook is fine for the collection-arm test, but the engine arm needs a fault injected inside or after phase 2. Two options:

```java
@Test
public void failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId() {
  // ... create class + property as today ...

  // Inject the fault AFTER the engine is built and published, so the failure-path
  // undo genuinely reverts a published engine + its surviving in-memory files.
  // Requires a post-phase-2 injection seam (see note below) rather than the
  // pre-record-apply commitWindowTestHook, which fires before any engine exists.
  storage.setPostEngineBuildTestHook(() -> {
    // Precondition: the engine IS registered at this point — prove the arm has
    // something to revert. A vacuous test would find nothing here.
    assertTrue("engine must be published before the fault so the revert arm has work",
        engineIsRegistered(indexName));
    throw new CommandInterruptedException(session.getDatabaseName(), "post-build fault");
  });
  try {
    session.begin();
    session.getMetadata().getSchema().getClass("FailBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    try {
      session.commit();
      fail("the index-building commit must fail when the post-build fault hook throws");
    } catch (final RuntimeException expected) {
      // routed through rollback + undoReconciledIndexEngines + revertCreatedIndexEngineStructure
    }
  } finally {
    storage.setPostEngineBuildTestHook(null);
  }

  // Now the assertions are load-bearing: the revert arm removed a real registration
  // and (on the in-memory profile) dropped the surviving engine files.
  assertFalse(engineIsRegistered(indexName));
  assertEquals(-1, storage.loadIndexEngine(indexName));
  // id-reuse and post-failure clean build as today ...
}
```

If adding a second injection seam is judged too heavyweight for this step, the minimum acceptable fix is to **correct the test's Javadoc and comments** so they stop claiming to exercise the create-side engine-file revert arm (they currently assert a scenario the test cannot reach), and open a follow-up for the post-build fault-injection coverage — the same honesty the collection-arm breadcrumb (TY2) applies. But the preferred fix is real coverage: the in-memory engine-file leak is precisely what this arm exists to close, and it is currently untested.

### TY2 — No crash-before-commit breadcrumb for the engine-file WAL-revert arm, unlike the collection arm

**Severity:** suggestion
**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java` (whole file — no `@Ignore` crash breadcrumb present; nearest reload test at line 1133 `builtIndexSurvivesReload`)
**Production code:** `AbstractStorage.createIndexEngineInCommitWindow` / `deleteIndexEngineInCommitWindow` (engine files "buffered as WAL-reverted intent", per the method Javadocs and D10)

**Evidence (RECOVERY CHECK).** D10 and D12 rest the engine arm's durability on WAL crash recovery: the commit-window engine create/delete run on the commit's own atomic operation, so "the engine files are buffered in this same atomic operation, so they revert with a rollback" and a committed engine's files replay from the WAL. The new tests verify the **clean-shutdown** half only: `builtIndexSurvivesReload` and `indexDroppedInTransactionIsRemovedAndEngineDeletedAtCommit` use `reload` + `reOpen`, which is a clean close/open (`DbTestBase.reOpen`), not a hard stop. No test simulates a crash (stop the storage before/after commit becomes durable, restore from WAL) for the engine create or delete.

That is an acceptable deferral — the crash half needs the heavier `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore harness — but Track 4 handled the identical situation for its **collection** arm by leaving a self-documenting `@Ignore`d breadcrumb: `SchemaCommitReconciliationTest.crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore` (lines 385-392), whose Javadoc explains the rollback half is covered programmatically and the crash half is deferred to the integration-test layer "so the gap is visible at the test surface." Track 5's engine arm — whose whole D10 story is WAL-reverted engine-file intent — leaves no such marker, so the crash-recovery gap for engine create *and* engine delete is invisible.

**Missing scenario / why it matters.** Two engine-arm crash points go unrepresented at the test surface: (1) crash-before-commit-durable of a tx-created index leaves no engine files after restore; (2) crash after a committed tx-created/tx-dropped index leaves the built engine (or the deleted engine's absence) correctly replayed from the WAL. If a future change to `doAddIndexEngine` / `doDeleteIndexEngine` buffering broke WAL revertibility, nothing in this suite would flag the gap, and unlike the collection arm there is not even a breadcrumb pointing a maintainer at the IT harness.

**Suggested test.** Mirror the Track-4 breadcrumb — an `@Ignore`d placeholder documenting the deferral, so the engine-arm crash gap is visible:

```java
/**
 * Breadcrumb for crash-before-commit / crash-after-commit recovery of a tx-created index engine.
 * The rollback half is covered by failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId; the
 * clean-reload half by builtIndexSurvivesReload. The crash half — stop the storage hard, restore
 * from the WAL, and assert the built engine's files replayed (committed) or are absent
 * (crashed-before-commit) — leans on the LocalPaginatedStorageRestoreFromWALIT close-copy-restore
 * harness, deferred to the integration-test layer. Kept as a placeholder so the gap is visible at
 * the test surface, mirroring SchemaCommitReconciliationTest's collection-arm breadcrumb.
 */
@Test
@Ignore("crash recovery of a commit-time index engine build/delete: needs the "
    + "LocalPaginatedStorageRestoreFromWALIT harness; deferred to the integration-test layer")
public void crashRecoveryOfCommitTimeIndexEngineIsDeferredToIT() {
  // Intentionally empty: see the Javadoc breadcrumb.
}
```

## Evidence base

Phase-3 recovery-path verification. Two claims were checked against production ordering; the surviving issue compresses to one line, the non-issue is shown in full.

#### C1 — CONFIRMED (TY1): the failed-commit test's fault precedes engine creation

TY1 survived the refutation check. Refutation attempted: "could an engine be published before the hook, via `enrollReconciledIndexRecords` or `reconcileCollections`?" Rejected by PSI find-usages — the sole call chain to `createIndexEngineInCommitWindow` (the only method that publishes an engine into `indexEngines`/`indexEngineNameMap` in the commit window) is `buildAndDropReconciledEngines` (phase 2) → `IndexAbstract.buildEngineAtCommit` → `createIndexEngineInCommitWindow`, and `buildAndDropReconciledEngines` has one production caller, `applyCommitOperations` at line 2676. The hook fires at line 2511, `indexPlan` is assigned at line 2562, and the failure-path engine-undo is gated on `indexPlan != null` at line 2710. Throwing at 2511 leaves `indexPlan == null`, so the undo is skipped and no engine was ever created — the test's post-conditions are vacuous. Confirmed as an issue.

#### C2 — CONFIRMED (TY2): the engine arm has no crash breadcrumb where the collection arm does

Refutation attempted: "is a crash-recovery test actually expected at the unit-test layer here, or is the omission fully covered elsewhere?" Partially refuted — a real crash test IS reasonably deferred (it needs the IT harness, as Track 4 established), so this is a suggestion, not a should-fix. What survives is narrower: Track 4 left a visible `@Ignore`d breadcrumb for the deferred collection-arm crash half (`SchemaCommitReconciliationTest` lines 385-392), and the parallel engine arm in this step left none, so the deferral is silent rather than documented. The finding is the missing breadcrumb, not a missing full crash test.

Assert-statement dimension (no findings). The three new production asserts were reviewed for defects and for redundant-recommendation risk:

- `IndexAbstract.buildEngineAtCommit` line 144, `assert indexId >= 0;` — meaningful: `createIndexEngineInCommitWindow` returns `generateIndexId`, and a downstream `extractInternalId` throws `IllegalStateException` on a negative external id, so this guards the built-handle-id contract at zero cost. Not tautological, no side effects. Keep.
- `AbstractStorage.createIndexEngineInCommitWindow` lines 3171-3172, `assert generatedId >= indexEngines.size() || indexEngines.get(generatedId) == null : "..."` — protects the commit-local first-null-slot allocator invariant against an occupied-slot bug during reuse-within-one-reconciliation. Well-formed, message-bearing, zero-cost. Keep.
- `AbstractStorage.deleteIndexEngineInCommitWindow` line 3216, `assert internalIndexId == engine.getId();` — consistency check between the extracted internal id and the fetched engine's own id. Zero-cost, no side effects. Keep.

No additional asserts recommended: `populateTxCreatedIndex` already has a real `if (rid == null || !rid.isPersistent()) continue;` guard on the RID it feeds to `doPut`, so an assert there would duplicate an existing check (a bad candidate per the assert-recommendation rules). `getApproximateRecordsCountInCommitWindow` already validates its bounds with a real `if` returning 0, not an assert. No unprotected commit-path invariant found that an assert would catch at zero cost.

Scope note: this step changes index-engine lifecycle inside an existing commit atomic operation; it introduces no new WAL record types or page-mutation code, so torn-page / double-write-log scenarios do not apply — the engine files ride the commit's existing atomic operation and its WAL revert.
