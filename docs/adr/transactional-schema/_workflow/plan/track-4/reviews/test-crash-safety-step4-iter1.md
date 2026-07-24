<!--
MANIFEST
dimension: test-crash-safety
step: 4
iteration: 1
commit_range: bc7eba6da8~1..bc7eba6da8
verdict: CHANGES_REQUESTED
findings_total: 6
blockers: 0
should_fix: 3
suggestions: 3
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: TY1
    sev: should-fix
    anchor: "#ty1-i-a4-failed-commit-registry-cleanliness-is-untested-and-the-gap-is-under-flagged"
    loc: "SchemaCommitReconciliationTest.java (whole class); AbstractStorage.java:887-901 (undoReconciledCollections branch)"
    cert: C1
    basis: "track Validation/Idempotence sections name I-A4; episode admits no fault hook landed; no test or breadcrumb in the class"
  - id: TY2
    sev: should-fix
    anchor: "#ty2-crash-before-commit-half-of-i-a1-is-untested"
    loc: "SchemaCommitReconciliationTest.java:1334-1346 (rolledBack...); AbstractStorage.java applyCommitOperations rollback path"
    cert: C2
    basis: "track names crash-before-commit (I-A1) leaning on Track 1; only the rollback half is tested; no restore-harness test"
  - id: TY3
    sev: should-fix
    anchor: "#ty3-add-a-no-provisional-id-precondition-assert-at-schemaclassimpltostream"
    loc: "SchemaClassImpl.java:toStream (collectionIds / defaultCollectionId write)"
    cert: C3
    basis: "PSI: toStream writes collection ids with no assert; I-A2/F58 corruption boundary; zero production cost"
  - id: TY4
    sev: suggestion
    anchor: "#ty4-assert-the-commit-local-allocator-id-is-genuinely-free-in-reconcilecollections"
    loc: "AbstractStorage.java reconcileCollections (realId = nextFreeCollectionId; doCreateCollection)"
    cert: C4
    basis: "undoReconciledCollections comment asserts in prose that a resolved id never coincides with a committed id; no runtime assert"
  - id: TY5
    sev: suggestion
    anchor: "#ty5-durable-reload-tests-only-bite-on-the-disk-profile"
    loc: "SchemaCommitReconciliationTest.java:1261-1282, 1356-1383"
    cert: C2
    basis: "DbTestBase default is MEMORY; reload reads same in-memory storage; I-A2 durable-bytes claim has teeth only under youtrackdb.test.env=ci"
  - id: TY6
    sev: suggestion
    anchor: "#ty6-assert-the-post-resolution-postcondition-no-provisional-id-survives"
    loc: "SchemaShared.java resolveProvisionalCollectionIds (after the patch loop)"
    cert: C3
    basis: "resolveProvisionalCollectionIds has no postcondition guard; complements TY3 at the resolution site"
-->

## Findings

### TY1 [should-fix] I-A4 (failed-commit registry cleanliness) is untested and the gap is under-flagged

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` (whole class)
**Production code**: `AbstractStorage.java` — `applyCommitOperations` failure `finally` (diff lines 887-901), `undoReconciledCollections` (diff lines 1087-1101)

**Evidence** (CRASH POINT / RECOVERY CHECK):
- CRASH POINT — Between `reconcileCollections` (which publishes the created collections into `collections` / `collectionMap` synchronously via `registerCollection`, diff line 1049) and a successful `endTxCommit`. The track's `## Validation and Acceptance` names this explicitly: "Force a commit to fail at apply (fault injected at or after `commitChanges`, not before); the in-memory registries ... carry no entry for the failed commit's structures, ... and the next commit reuses the ids (I-A4, A1/R1)."
- The production code added a dedicated recovery path for exactly this window: `undoReconciledCollections` removes the synchronously-published registry entries on the failure branch, gated by `structurePublished`. This is net-new failure-path logic introduced by this step.
- TEST TRACE — `SchemaCommitReconciliationTest` has five tests: create-resolves, durable-reload, record-insert, rolled-back-create, drop-across-commit. None injects a fault at or after `commitChanges`. `rolledBackInTransactionCreateLeavesNoCollection` exercises an explicit `session.rollback()` *before* commit, which never reaches `reconcileCollections` (the structural publication never happens), so it does not exercise the `undoReconciledCollections` branch at all.

**Missing scenario**: A commit that fails *after* `reconcileCollections` has published into the in-memory registries but during/after apply, verifying that (a) `collectionMap` and `collections` carry no entry for the failed commit's collection, and (b) the next successful commit reuses the freed id. The `undoReconciledCollections` method and the `structurePublished` guard are currently covered by zero tests.

**Why it matters**: A regression in `undoReconciledCollections` (wrong id set, off-by-one in the `collections.set(realId, null)`, or the `committedIds.contains(realId)` guard mis-skipping) would leave a phantom collection registered in memory after a failed schema commit. The next lookup by that id, or the next allocator scan, would see a half-created collection whose on-disk file was WAL-reverted — a registry-vs-disk divergence that surfaces as intermittent corruption far from the failed commit. This is the precise failure D10/A1/R1 forbid, and the added recovery code is the riskiest new logic in the step.

**Flagging gap**: The episode (Surprises 2026-06-26T15:39Z) and the track `## Idempotence and Recovery` section both say "If no reusable fault hook exists, decompose a small testability seam rather than improvising one per test." The Step-4 implementation landed `undoReconciledCollections` but no fault-injection seam and no `@Ignore`/`@Disabled` breadcrumb test recording the deferral *in the test class itself*. A reader of `SchemaCommitReconciliationTest` cannot tell that I-A4 was consciously deferred. The deferral is real and defensible (no fault hook exists yet), but it should be visible at the test surface, not only in the workflow log.

**Suggested test** (skeleton — needs the fault seam decomposed first, per the track's own guidance):
```java
@Test
public void failedCommitAtApplyLeavesNoPhantomRegistration() {
  // Arrange: a fault seam (Mockito spy or @VisibleForTesting hook) on the storage that
  // throws at or AFTER commitChanges, so reconcileCollections has already published the
  // created collection into collections/collectionMap before the failure.
  var namesBefore = new HashSet<>(session.getCollectionNames());

  session.begin();
  session.getMetadata().getSchema().createClass("FailAtApply");
  // inject fault fired at/after commitChanges:
  try {
    session.commit();
    fail("commit should have failed at apply");
  } catch (RuntimeException expected) { /* routed through rollback + undoReconciledCollections */ }

  // Assert: no phantom in-memory registration survives.
  assertEquals("a failed schema commit must leave the collection registry unchanged",
      namesBefore, new HashSet<>(session.getCollectionNames()));
  assertFalse("the failed class must not be in the committed schema",
      schemaShared().existsClass("FailAtApply"));

  // Assert: the freed id is reusable — a subsequent successful create takes the same slot.
  session.executeInTx(tx -> session.getMetadata().getSchema().createClass("ReuseAfterFail"));
  // (assert the new class's collection id is the slot the failed commit freed)
}
```
If the seam genuinely cannot land in this step, add an `@Ignore("I-A4: needs the post-commitChanges fault seam, deferred — see track-4 Idempotence and Recovery")` stub so the gap is self-documenting in the test class.

---

### TY2 [should-fix] Crash-before-commit half of I-A1 is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:1334-1346` (`rolledBackInTransactionCreateLeavesNoCollection`)
**Production code**: `AbstractStorage.java` `applyCommitOperations` — the `startTxCommit` / `reconcileCollections` / rollback window; the structural file writes buffered as WAL-reverted intent (diff lines 632-640, 888-897)

**Evidence** (CRASH POINT / TEST TRACE):
- CRASH POINT — A crash between the transaction body completing and `commitChanges` becoming durable. The track `## Validation and Acceptance` requires: "A transaction creates a class and an index, then rolls back; no collection or engine files exist... Repeat with a crash injected after the transaction body and before commit; recovery leaves the same clean state (I-A1, leaning on Track 1)."
- TEST TRACE — `rolledBackInTransactionCreateLeavesNoCollection` covers the *programmatic rollback* half (verdict: COVERED for rollback). The *crash-before-commit* half is NOT exercised by any test — there is no close-copy-restore or WAL-replay test in this class. Track 1 landed `ensureFileForReplay` precisely so this scenario replays cleanly, and the track's `## Idempotence and Recovery` names the reuse pattern: "Leans on Track 1's `ensureFileForReplay`; reuses the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore pattern" (that harness exists in the tree, confirmed).
- Crash timing: the rollback test crashes at a *different* point (explicit `rollback()`, which skips `commitChanges`) than the crash-before-commit scenario (a hard stop mid-window). They share the WAL-revertibility mechanism but exercise different code: rollback runs the `rollback(error, atomicOperation)` Java path; a crash relies on restore-time replay never applying the buffered file-create intent.

**Missing scenario**: Create a schema-changing class, stop the storage hard before commit completes (close-without-clean-shutdown / copy the storage files / restore), reopen, and assert the class and its collection are absent and storage is byte-for-byte clean.

**Why it matters**: The rollback path and the crash-recovery path are *different* guarantees resting on the *same* premise (buffered-intent-only-applied-in-`commitChanges`). A regression that makes `reconcileCollections` apply a file write outside the `startTxCommit`/`endTxCommit` window would pass the rollback test (the Java `rollback()` still runs) but corrupt the crash-recovery case (the orphan file survives restore). Only a restore-harness test distinguishes them. This is the I-A1 half the whole D10 + Track-1-prerequisite chain exists to protect.

**Suggested test** (skeleton, follows the existing `LocalPaginatedStorageRestoreFromWALIT` pattern; disk profile):
```java
@Test
public void crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore() {
  // Arrange: disk storage; record the collection-file set on disk.
  // Act: begin a tx, createClass("CrashCreate"), then simulate a crash BEFORE commit
  //   (close the storage without a clean checkpoint, copy the data dir, restore into a fresh dir).
  // Reopen the restored storage.
  // Assert: schema has no "CrashCreate", getCollectionNames() is unchanged, and no orphan
  //   <class>_<counter> collection file exists on disk (the WAL-reverted intent never applied).
}
```
If a full restore IT is out of scope for this step, an `@Ignore` breadcrumb naming I-A1 and the `LocalPaginatedStorageRestoreFromWALIT` pattern keeps the gap visible (same remedy as TY1).

---

### TY3 [should-fix] Add a no-provisional-id precondition assert at `SchemaClassImpl.toStream`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassImpl.java` — `toStream(session, entity)`, the `defaultCollectionId` / `collectionIds` write

**Evidence** (INVARIANT analysis):
- INVARIANT — At serialization time, every collection id a class writes to durable bytes must be a real id (`>= 0`) or the abstract marker (`-1`); a provisional id (`<= -2`) must never reach `toStream`. The commit calls `resolveProvisionalCollectionIds` immediately before `toStream` (diff lines 644-646) precisely to uphold this.
- When it could break — a future patch-list change that misses a provisional reference (a new id-carrying field, a producer site added without wiring its id into the resolution map), or a reconciliation ordering bug that serializes before resolving. D2's own Risks/Caveats names this as the F58 silent-corruption case: "Skipping the property-value re-point durably writes provisional ids, and the class loses its collections at the next open."
- Current enforcement — NONE. PSI confirms `SchemaClassImpl.toStream` has no assert and no provisional check; it writes `entity.setProperty("defaultCollectionId", defaultCollectionId)` and `entity.newEmbeddedList("collectionIds", collectionIds)` unconditionally.
- Assert candidate — YES. Zero production cost (assertions disabled in production JVMs; the core test module runs with `-ea`, confirmed by the Step 3 episode, so the test suite would catch a regression). This is the single highest-value durability boundary in the step: the exact line where an unresolved provisional id becomes permanent corruption.

**Invariant**: No provisional collection id is ever serialized.

**Suggested assertion** (extract the scan to a helper so JaCoCo does not report phantom branches on the multi-id loop, per architecture.md JaCoCo+assert guidance):
```java
assert noProvisionalCollectionId()
    : "a provisional collection id reached toStream for class " + name
        + " (defaultCollectionId=" + defaultCollectionId
        + ", collectionIds=" + Arrays.toString(collectionIds) + ")";
// helper, same class:
private boolean noProvisionalCollectionId() {
  if (SchemaShared.isProvisionalCollectionId(defaultCollectionId)) {
    return false;
  }
  for (final var id : collectionIds) {
    if (SchemaShared.isProvisionalCollectionId(id)) {
      return false;
    }
  }
  return true;
}
```

**Catches**: Any reconciliation/resolution regression that lets a `<= -2` id slip into a persisted per-class record — caught at the serialization boundary during testing instead of as a "class lost its collections" symptom at the next database open (F58). This is the assertion analogue of the `committedClassAndCollectionSurviveReload` test's `assertTrue("no provisional id may survive a reload", id >= 0)`, but enforced on the *write* side where the corruption actually originates rather than the read side after the fact.

---

### TY4 [suggestion] Assert the commit-local allocator id is genuinely free in `reconcileCollections`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` — `reconcileCollections`, the create loop (`final int realId = nextFreeCollectionId(); final var collection = doCreateCollection(atomicOperation, collectionName, realId);`, diff lines 1047-1048)

**Evidence** (INVARIANT analysis):
- INVARIANT — The id returned by `nextFreeCollectionId()` must be a genuinely free slot: either an index where `collections.get(i) == null`, or `collections.size()` (append). It must never collide with a committed collection's occupied slot.
- When it could break — `undoReconciledCollections` itself states this invariant in prose (diff lines 1091-1093): "A resolved real id that coincides with a committed id cannot happen: the commit-local allocator draws from free slots, and a committed id's slot is occupied." The undo logic *depends* on this being true (it `continue`s past `committedIds.contains(realId)`). If a future change to slot bookkeeping (e.g. a drop in the same reconciliation that nulls a slot the create loop then reuses, interacting with the F88 non-commit registrars) violated it, the undo would silently skip a real cleanup and `doCreateCollection` could overwrite a live collection's slot.
- Current enforcement — NONE at the allocation site. The invariant lives only in a comment.
- Assert candidate — YES. Zero production cost, and it pins down the load-bearing assumption that the whole publish/undo discipline rests on.

**Invariant**: The commit-local allocator never hands back a slot already occupied by a (committed or just-created-this-reconciliation) live collection.

**Suggested assertion**:
```java
final int realId = nextFreeCollectionId();
assert realId >= collections.size() || collections.get(realId) == null
    : "commit-local allocator returned an occupied collection slot " + realId;
final var collection = doCreateCollection(atomicOperation, collectionName, realId);
```

**Catches**: A slot-bookkeeping regression where `nextFreeCollectionId` returns an occupied id (drop/create interleaving within one reconciliation, or a stale `collections` read) — caught at create time during testing rather than as an overwritten-collection corruption.

---

### TY5 [suggestion] Durable-reload tests only bite on the disk profile

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:1261-1282` (`committedClassAndCollectionSurviveReload`), `:1356-1383` (`droppedClassRemovesItsCollectionAcrossCommit`)

**Evidence** (RECOVERY CHECK):
- `DbTestBase.calculateDbType()` returns `DatabaseType.MEMORY` unless `youtrackdb.test.env=ci` is set (then `DISK`), confirmed by reading the base class. The default unit-test run is in-memory.
- `SchemaShared.reload(session)` re-reads via `session.load(identity)` then `fromStream`; `reOpen` closes the session and reopens through `youTrackDB.open(...)`. On a MEMORY storage profile neither discards the storage's in-memory pages, so the "round trip" re-parses bytes that never left RAM. The test's own docstring claims the reload "forces a fromStream re-parse on every storage profile" — true for the *re-parse*, but on MEMORY it does not prove the bytes are *durable* (survive a real storage close/open).
- The I-A2 acceptance line is specifically about durable bytes: "no provisional id reached durable bytes." The fromStream re-parse meaningfully exercises that only when the bytes round-trip through a real on-disk storage, i.e. the CI disk profile.

**Why it matters**: This is not a correctness defect in the tests — the re-parse assertions (`id >= 0` after reload, ids unchanged) are real and valuable, and CI runs the disk profile (`-Dyoutrackdb.test.env=ci`) per the project's standard pipeline, where they do bite. The risk is only that a developer reading the test, or running the default in-memory suite locally, may over-trust the durability claim. The remedy is documentation, not restructuring.

**Suggested**: Add one line to the class or per-test javadoc making the profile dependency explicit, e.g. "The durable-bytes guarantee (I-A2) is exercised when this runs on the disk profile (`youtrackdb.test.env=ci`); on the default in-memory profile the reload verifies only the fromStream re-parse." No assertion change needed.

---

### TY6 [suggestion] Assert the post-resolution postcondition (no provisional id survives)

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` — `resolveProvisionalCollectionIds`, after the `for (cls : classes.values()) cls.replaceProvisionalCollectionIds(resolution)` loop and reverse-map rebuild (diff lines 198-217)

**Evidence** (INVARIANT analysis):
- INVARIANT — After `resolveProvisionalCollectionIds` returns, no class in the tx-local schema carries any provisional id; every provisional id in the `resolution` map has been patched to its real id.
- When it could break — `replaceProvisionalCollectionIds` only patches ids that are *both* provisional *and* present in `resolution` (diff lines 104-122). A provisional id allocated by one producer but never recorded into the resolution map (a missed `recordResolvedCollectionId` from one of the two producers — `createCollections` and `setAbstractInternal`, per the Step 2 episode's two-producer finding) would survive the patch silently and then reach `toStream`.
- Current enforcement — NONE. The method trusts that `resolution` covers every provisional id.
- Assert candidate — YES, zero cost. Complements TY3: TY3 catches the survival at the write boundary; TY6 catches it one step earlier at the resolution boundary, with a message that names the resolution gap directly.

**Invariant**: After resolution, every class's id arrays are provisional-free.

**Suggested assertion** (postcondition, extracted to a helper):
```java
// after the reverse-map rebuild, before releasing the write lock:
assert allCollectionIdsResolved()
    : "a provisional collection id survived resolveProvisionalCollectionIds; "
        + "a producer allocated an id never recorded into the resolution map";
```
where `allCollectionIdsResolved()` scans every class's `collectionIds` / `defaultCollectionId` / `polymorphicCollectionIds` for any `isProvisionalCollectionId`.

**Catches**: A producer/consumer mismatch (a provisional id allocated but never resolved) — caught at the resolution site during testing rather than as the F58 corruption TY3 guards against. Lower priority than TY3 because TY3 sits at the actual durable-write boundary; this one is the earlier, more diagnostic guard.

## Evidence base

#### C1 — I-A4 failed-commit cleanliness is the riskiest new code and has zero test coverage (CONFIRMED-as-issue)
Refutation attempt: could `rolledBackInTransactionCreateLeavesNoCollection` cover the `undoReconciledCollections` branch? Refuted — that test calls `session.rollback()` before commit, so `reconcileCollections` (and therefore the synchronous `registerCollection` publication) never runs; the `structurePublished` guard stays false and `undoReconciledCollections` is never entered. The undo branch (diff 888-897) and `undoReconciledCollections` (diff 1087-1101) are net-new and uncovered. Track `## Validation and Acceptance` line 480-482 and `## Idempotence and Recovery` line 498-503 both name I-A4 as in-scope-but-needing-a-fault-seam. Issue stands.

#### C2 — Crash-before-commit (I-A1) and durable-reload profile dependency
Refutation attempt for TY2: does the rollback test or the durable-reload test cover crash-before-commit? Refuted — rollback exercises the Java `rollback()` path (different code than restore-time replay); durable-reload exercises a successful commit's round-trip, not a crash mid-window. `LocalPaginatedStorageRestoreFromWALIT` exists in the tree (confirmed via find) as the named reuse pattern, and Track 1's `ensureFileForReplay` prerequisite landed specifically for this scenario, so the gap is real and the harness is available. For TY5: `DbTestBase.calculateDbType()` returns MEMORY by default (read confirmed, line 126-135); `reOpen` reopens the same storage and `SchemaShared.reload` reads via `session.load` — neither forces a disk round-trip on MEMORY. The CI disk profile gives the I-A2 durable-bytes claim its teeth; TY5 is documentation-only, not a correctness defect. Both stand at their assigned severities (TY2 should-fix, TY5 suggestion).

#### C3 — No provisional-id guard at the serialization or resolution boundary
PSI-confirmed: `SchemaClassImpl.toStream` writes `defaultCollectionId` and `collectionIds` with no assert and no provisional check (`hasAssert=false, hasProvisionalCheck=false`). `SchemaShared.resolveProvisionalCollectionIds` (diff 198-217) has no postcondition guard. The producer-side carriers DO assert (`TxSchemaState.recordResolvedCollectionId` asserts both `<= -2` and `>= 0`; `getProvisionalCollectionName` asserts non-null — read confirmed lines 183-216), so the gap is specifically on the consume/serialize side, which is the F58 corruption boundary D2 names. TY3 (write boundary) ranked above TY6 (resolution boundary) because TY3 sits at the actual durable write. Both are genuine zero-cost candidates.

#### C4 — Commit-window readLock enumeration is sound; `recordExists` is provably unreachable (BC1 forward-note requirement SATISFIED — no issue)
The Step 3 BC1 forward note required Step 4 to re-run the PSI enumeration of `stateLock.readLock()`-taking methods reachable from inside the window and either make `recordExists` window-aware or record it as provably unreachable. PSI find-usages chain (all confirmed this session): `recordExists(session,RID,AtomicOperation)` <- `DatabaseSessionEmbedded#executeExists` (1 caller) <- `FrontendTransactionImpl#exists` <- `DatabaseSessionEmbedded#exists(RID)` <- `RecordAbstract#exists` <- **zero callers** (one coverage test only). The terminal `RecordAbstract.exists` has no production caller, so the entire `exists` chain is dead with respect to the commit body — `recordExists` is provably unreachable from the commit window. Independently, the six window-aware methods (`isClosed`, `getCollectionNames`, `getCollectionIdByName`, `getIndexEngine`, `getPhysicalCollectionNameById`, `readRecordInternal`) cover the reachable readLock surface; the other non-window-aware readLock methods reached from the commit path were checked and dismissed: `getCollectionName(session,int)` and `getRecordMetadata` have zero callers, `getCollectionRecordConflictStrategy` / `isSystemCollection` route to conflict-strategy / iterator paths off the commit body. The reconciliation core's commit-window guard set is complete for the reachable surface. This was the load-bearing reference-accuracy check for the step; it passes, so it produces no finding — recorded here as the satisfied BC1 obligation.
