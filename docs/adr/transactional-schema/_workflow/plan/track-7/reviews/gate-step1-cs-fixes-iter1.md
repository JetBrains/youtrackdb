<!-- MANIFEST
gate: crash-safety review-fix commit 7d2369cda0 ("Restore link-bag registration in failed-commit undo")
source-review: crash-safety-step1-iter1.md (CS20-CS25; CS23 not in gate scope)
verdicts:
  - {id: CS20, verdict: VERIFIED}
  - {id: CS21, verdict: VERIFIED}
  - {id: CS22, verdict: VERIFIED}
  - {id: CS24, verdict: VERIFIED, note: "addSuppressed skip justification holds"}
  - {id: CS25, verdict: VERIFIED, note: "residual sensitivity caveat documented, non-blocking"}
regressions: none
flags: [READ_ONLY, NO_MAVEN_RUN, VERDICTS_DERIVED_INDEPENDENTLY]
-->

# Gate verification — crash-safety review fixes (Track 7 Step 1, iter 1)

**Artifact:** commit `7d2369cda0` ("Restore link-bag registration in failed-commit undo").
**Finding source:** `crash-safety-step1-iter1.md` (CS20, CS21, CS22, CS24, CS25; CS23 out of gate scope).
**Method:** read-only, verdicts derived from code, not from fixer reasoning. No Maven executed
(another thread owns test execution in this worktree). Every trace cites file:line of code
actually read in the post-fix working tree.

---

## CS20 [should-fix] — link-bag `fileIdBTreeMap` restore in the drop-restore arm — **VERIFIED**

### Premises

1. **The restore mirror exists and is shape-correct.**
   `LinkCollectionsBTreeManagerShared.restoreComponentByCollectionId`
   (`LinkCollectionsBTreeManagerShared.java:125-137`): resolves the id-named component file via
   `atomicOperation.fileIdByName(generateLockName(collectionId))`, returns (no-op) on `fileId < 0`,
   otherwise constructs a fresh `SharedLinkBagBTree(storage, FILE_NAME_PREFIX + id, FILE_EXTENSION)`,
   `load`s it, and `put`s it under `AbstractWriteCache.extractFileId(fileId)` — the exact key
   `deleteComponentByCollectionId` removed (`:100-110`, same `fileIdByName` + `extractFileId`
   derivation) and the exact key `loadIsolatedBTree` (`:169-177`) and `doCreateRidBag` (`:139-166`)
   look up.
2. **Pure-drop failure shape restores correctly.** The failed commit's file delete was only
   *buffered* (`AtomicOperationBinaryTracking.deleteFile:923-938` adds to
   `deletedFiles`/`deletedFileNameIdMap`, applied only in `commitChanges`), so on the failure path
   the write cache never dropped the file. The fresh snapshot-only operation's own
   `newFileNamesId`/`deletedFileNameIdMap` are empty, so `fileIdByName`
   (`AtomicOperationBinaryTracking.java:955-968`) falls through to `writeCache.fileIdByName` and
   returns the intact file's id ≥ 0 → construct, load, re-put. The map entry the drop removed
   eagerly is back under the identical key.
3. **Idempotent on slot-reuse.** On the reuse path the failed create's `createComponent`
   (`:90-98`) already re-put a replacement tree under the *resurrected identical* file id (per the
   original review's C1: `addFile` un-deletes rather than allocating fresh). The create-undo's
   slot-reuse branch (`AbstractStorage.java:3585-3595`) deliberately never calls
   `deleteComponentByCollectionId`, so the replacement entry survives into the drop-restore arm;
   the restore's `put` replaces it with an equivalent object. `SharedLinkBagBTree` carries no state
   beyond `volatile long fileId` (+ immutable name/serializer wiring,
   `SharedLinkBagBTree.java:38-46`), and `load` re-derives that id from the same
   `openFile(getFullName())` — the replacement is observationally identical. The `ridBagIdCounter`
   is untouched by both delete and restore, so id monotonicity is preserved.
4. **No-link-bag no-op.** `fileId < 0` → early return (`:129-131`) — a collection whose component
   file never existed (or in-doubt registry states) restores nothing rather than fabricating a
   component.
5. **The "lightweight read-only atomic operation" violates no lifecycle rule.**
   - `AtomicOperationsManager.startAtomicOperation()` (`AtomicOperationsManager.java:102-125`) is
     snapshot-capture only: a brief `segmentLock.sharedLock()` to read `idGen.getLastId()`, a pure
     read-only table scan in `snapshotAtomicOperationTableState`
     (`AtomicOperationsTable.java:233-` — scan under `compactionLock.sharedLock()`, no
     registration), and object construction (`AtomicOperationBinaryTracking` ctor `:199-230` —
     stores references, `active = true`, registers nothing into shared structures; local overlay
     buffers stay lazily null). No operations-table entry (`startOperation` lives only in
     `startToApplyOperations:127-149`), no write-freezer, no thread-local current-op.
   - **Deadlock:** the undo holds `stateLock.writeLock()`; the only lock the scope takes is
     `segmentLock` (shared, momentary). The established ordering everywhere is
     stateLock → segmentLock (`startToApplyOperations` runs inside the commit window), and no
     `segmentLock` critical section acquires `stateLock` — no cycle.
   - **Leak:** `deactivate()` (`AtomicOperationBinaryTracking.java:1434-1436`) is `active = false`,
     complete teardown for a never-applied op; wrapped in `finally`
     (`AbstractStorage.java:3510-3516`). The only side effect of `load` on the op is a
     `fileChanges.computeIfAbsent` in `loadFile` (`:910-919`) — private to the discarded op.
   - **Operations table:** never touched (no `startOperation`/`commitOperation`/
     `rollbackOperation`) — nothing to corrupt.
   - **Works on poisoned storage:** neither `startAtomicOperation` nor any code in the restore path
     consults `checkErrorState` (single production caller is
     `AtomicOperationsManager.java:524`, see CS21 below).
6. **`SharedLinkBagBTree.load` takes no component-write lock.** `load`
   (`SharedLinkBagBTree.java:81-92`) calls `acquireExclusiveLock()` — the component's *own*
   per-object `ReentrantReadWriteLock` from `SharedResourceAbstract`
   (`SharedResourceAbstract.java:35-37`), on a freshly constructed, unshared object (uncontended,
   released in `finally`). It does **not** route through `executeInsideComponentOperation` /
   `acquireExclusiveLockTillOperationComplete` (contrast `create:53-79` and `delete:95-106`, which
   do), so no `checkErrorState`, no lock registered till operation completion.
7. **Failure containment:** the wiring catches `RuntimeException | AssertionError` and error-logs
   (`AbstractStorage.java:3517-3524`); `restoreComponentByCollectionId` can throw no checked
   exception (`load` wraps its `IOException` into `StorageException`,
   `SharedLinkBagBTree.java:85-90`), so the catch coverage is complete and the propagating primary
   commit failure is never masked.
8. **Regression probe:** `linkBagComponentResolvable` (`SchemaCommitReconciliationTest.java:100-109`)
   resolves via `getComponentByCollectionId` (`LinkCollectionsBTreeManagerShared.java:239-247`) —
   the same file-present-then-map-get sequence the NPE counterexample rode. Wired into the three
   failed-commit tests (`:446` pure-drop — the exposed shape from the original counterexample;
   `:520` slot-reuse; `:608` routed endTxCommit failure). Red-first is structurally sound: without
   the restore, the pure-drop shape leaves the file present (`fileIdByName ≥ 0`) and the map entry
   absent → probe returns `null` → `assertTrue` fails.

### Alternative hypotheses checked

- *Concurrent map access hazard:* `fileIdBTreeMap` is a `ConcurrentHashMap`; the undo holds
  `stateLock.writeLock()`, excluding concurrent commits; a put on the restored key cannot disturb
  readers of other keys. No new hazard.
- *Wrong shape on the in-memory profile:* the drop's file delete is buffered on both engines
  (delete is applied only in `commitChanges`), so premise 2 holds for both; the test suite's
  default in-memory profile exercises the same path.
- *Probe weaker than an end-to-end link-bag write:* true, but the probed map entry is exactly the
  state the counterexample's NPE depends on; the finding's fix demand is met.

**Verdict: VERIFIED.**

---

## CS21 [should-fix, disposition (c): comment alignment only] — shape-2 containment comment — **VERIFIED**

The rewritten comment (`AbstractStorage.java:3090-3108`) makes four claims; each checks out:

1. *"the error state gates component-WRITE operations only (checkErrorState is consulted at the
   component-lock acquisition)"* — grep over production sources: `checkErrorState()` has exactly
   one caller, `AtomicOperationsManager.acquireExclusiveLockTillOperationComplete:524`, the entry
   gate of every component write (`executeInsideComponentOperation:205-210`). Accurate.
2. *"writes fail fast while reads and registry queries keep serving"* — matches the original
   review's C10 trace (reads, schema-snapshot queries, `getCollectionNames` take no error gate;
   `checkOpennessAndMigration` checks `status`, which stays `OPEN`). Accurate.
3. *"in the durably-committed sub-shape (a post-commitChanges throw) other sessions can read the
   new durable rows under the still-unpromoted, stale schema until the reopen"* — this is exactly
   the CS21 premise-3b divergence the old comment hid; now stated. Accurate.
4. *"the dirty flag is preserved by the error state, so the next open replays the WAL"* — verified:
   `DiskStorage.clearStorageDirty:612-616` skips `clearDirty()` when `isInError()`, and
   `DiskStorage.postCloseSteps:521-532` skips both `setLastTxId` and `clearDirty()` when
   `internalError` — the two paths that could clear the flag on close. Accurate.

The finding's disposition explicitly scoped the fix to honesty of the comment (option (c) of the
review's suggestion), with the behavioral read-gate left as accepted divergence. The comment now
states the real contract including its cost. **Verdict: VERIFIED.**

---

## CS22 [suggestion] — AssertionError wrapped before `setInError` in the shape-2 arm — **VERIFIED**

1. The arm (`AbstractStorage.java:3114-3119`) now passes
   `e instanceof AssertionError ? BaseException.wrapException(new StorageException(name, "endTxCommit
   failed without an internal rollback"), e, name) : e` to `setInError`. The wrapped value is a
   `StorageException` (RuntimeException) with the AssertionError as cause — the setter's
   `instanceof AssertionError` early-return (`:2077-2079`) no longer fires, so `error.set(e)` runs
   and poisoning is **unconditional under `-ea`**. The setter's own `-ea` postcondition
   (`:2085-2086`) checks the *argument*, which is now the wrapper, not an AssertionError — it
   passes.
2. The global stray-assert guard in `setInError` (`:2063-2088`) is byte-for-byte untouched by the
   diff (the diff touches only the call site) — verified against `git show 7d2369cda0` (no hunk in
   the `setInError` body) and the current file. Other callers (`moveToErrorStateIfNeeded:2050-2054`,
   promotion arm `:3201`, `:5540`, `:5862`) keep the skip semantics.
3. Downstream consistency: the poisoned state preserves the dirty flag (CS21 premise 4) regardless
   of whether the stored error is the original throwable or the wrapper — `isInError()` only tests
   non-null.

**Verdict: VERIFIED.**

---

## CS24 [suggestion] — assert-in-catch removal in `restoreReconciledDroppedIndexEngines` — **VERIFIED** (skip justification for `addSuppressed` holds)

1. **Both asserts are gone.** grep over the method body (`AbstractStorage.java:3836-3894`): zero
   `assert` statements remain. The no-captured-data arm (`:3841-3854`) is now
   `LogManager.error(this, "...slot %d...", null, internalId)` + `continue`; the reconstruction
   catch (`:3872-3893`) is now log-and-continue with the poisoned case annotated via an inline
   `isInError()` suffix. Signature check: `error(Object, String, Throwable, Object...)`
   (`SLF4JLogManager.java:247-253`) — both calls match (format `%d`/string-concat, args aligned);
   the log call itself has no throw path of concern.
2. **No remaining masking path in the method.** Enumerated: (a) the `for` header — `droppedEngines`
   is the non-null plan list, iteration cannot throw; (b) the null-data arm — log + continue;
   (c) the slot-occupied guard (`:3857-3860`) — bounds-checked list reads, no throw; (d) the
   reconstruction `try` — its catch covers `IOException | RuntimeException | AssertionError`, and
   `executeInsideAtomicOperation` itself converts lambda-body `Exception | AssertionError` into
   `StorageException` (`AtomicOperationsManager.java:181-203`), so everything except non-Assert
   `Error`s (OOM/StackOverflow/Linkage — deliberately uncontained storage-wide) lands in the catch;
   (e) the catch body — string concatenation on the non-null `engineData`, `isInError()` read, log
   call. No path throws.
3. **`undoAppliedMembership` cannot be skipped by this method.** Caller sequence
   (`AbstractStorage.java:3417-3420`): `undoReconciledIndexEngines` → `restoreReconciledDroppedIndexEngines`
   → `indexManager.undoAppliedMembership(indexPlan)`. Since (2) proves the restore method never
   throws, the trailing membership undo always runs. (Also checked the *preceding* arm for
   completeness: `undoReconciledIndexEngines` (`:3793-3807`) contains no asserts, and its delegate
   `revertCreatedIndexEngineStructure` (`:3906+`) is fully try/caught with a warn-log — so the whole
   three-arm sequence is throw-free under `-ea`.)
4. **`addSuppressed` skip justification holds.** The primary commit exception is not in scope: the
   method takes only `List<DroppedIndexEngine>`, and its caller
   `undoSchemaCarryRegistryPublication(schemaContext, indexPlan, droppedCollections)` (`:3411-3422`)
   does not thread a `Throwable` either — attaching suppression would require widening two internal
   signatures from both invocation sites (the `error != null` finally arm at `:3057-3059` and the
   endTxCommit shape-1 catch at `:3111-3113`). The gain is diagnostics-only: the secondary failure
   is already fully recorded (message + stack trace) by the error log, which the rewritten comment
   designates as "the surfacing". For a suggestion-severity sub-item, skipping is proportionate.
5. Javadoc updated in sync (`:3825-3833`): "logged loudly and never thrown", poisoned-case
   expectation documented — no stale comment residue.

**Verdict: VERIFIED** (including the SKIP of the addSuppressed half).

---

## CS25 [suggestion] — `poisonedEndTxCommitFailureRecoversOnReopen` — **VERIFIED** (with a documented sensitivity caveat)

1. **The test matches the demanded shape.** (`SchemaCommitReconciliationTest.java:688-785`):
   dedicated DISK-typed database (`DatabaseType.DISK`, own directory
   `getBaseDirectoryPathStr(getClass()) + "-poisoned-reopen"` — no clash with the shared test
   context; cleaned in `finally`); poison via `setEndTxCommitPostDurabilityFailureTestHook`
   (fires in `endTxCommit` *after* `endAtomicOperation(op, null)` succeeded,
   `AbstractStorage.java:6484-6510`, so `isRollbackInProgress()` is false → shape-2 arm →
   `setInError` — the exact poisoned shape); sanity-asserts the poison via `checkErrorState`
   before close; closes session + whole context; reopens a fresh context.
2. **Assertions cover the finding's demand** ("dropped class gone, created class present and
   queryable, storage no longer in error"): post-reopen `storage.checkErrorState()` must not throw
   (`:757`), `existsClass("ReopenDropped")` false (`:760-761`), `existsClass("ReopenCreated")` true
   (`:762-763`), the dropped collection name unregistered (`:764-766`), plus a strictly stronger
   writability probe — a row round-trip through the recovered class (`:768-777`).
3. **Failure sensitivity.** The close path with `isInError()` preserves the dirty flag on both
   close hooks (`DiskStorage.clearStorageDirty:612-616`, `postCloseSteps:521-532` with
   `internalError=true` — `AbstractStorage.doShutdown:7114` passes `isInError()`), so the reopen
   deterministically routes through `recoverIfNeeded` (`AbstractStorage.java:829`). If the replay
   path errored, produced a divergent committed schema, or left the registries inconsistent with
   the durable truth, the open call or the schema/collection/row assertions fail — the test is a
   genuine end-to-end pin of the recovery contract on the poisoned shape.
4. **Caveat (documented, non-blocking):** the error-state close still flushes applied pages
   (`doShutdown` skips `flushAllData` when poisoned, but `readCache.closeStorage(writeCache)` →
   `writeCache.close()` → `flush()`, `LockFreeReadCache.java:756-759`, `WOWCache.java:2274-2275`),
   so the data files are complete at close and WAL replay over them is an idempotent no-op. A
   hypothetical *dirty-flag-preservation* regression (flag cleared despite error) would therefore
   not fail this test — the test discriminates "reopen recovery produces the correct end state,"
   not "each internal premise of the mechanism held." The finding demanded exactly the end-state
   assertions ("a close-reopen tail asserting the dropped class is gone, the created class is
   present and queryable, and the storage is no longer in error"), which the test delivers; a
   `wereDataRestoredAfterOpen`-style probe would strengthen it but was not demanded. Suggestion
   severity; no follow-up filed.

**Verdict: VERIFIED.**

---

## Regression scan of the fix diff

- `restoreComponentByCollectionId` is public but single-caller; misuse surface nil (id-keyed no-op
  contract is safe against arbitrary ids).
- The removed `-ea` asserts trade an eager test-time throw for log-only surfacing of a genuine
  no-captured-data invariant break — precisely the finding's requested trade; the log is at error
  level and carries the slot id.
- The fresh snapshot-only op in the undo follows the established fresh-read-scope pattern
  (start + deactivate, per the original review's C4 certification of the identical lifecycle in
  `DatabaseSessionEmbedded`); no new lock-order edge (stateLock → segmentLock only).
- Test additions are test-scoped; the new DISK test manages its own context and deletes its
  directory in `finally`.

**No regression found. No RG finding filed.**

## Hypothesis log

| # | Hypothesis | Outcome |
|---|-----------|---------|
| G1 | The restore re-puts under a different map key than delete removed / lookups use | REFUTED — identical `fileIdByName` + `extractFileId` derivation at all four sites |
| G2 | The fresh snapshot-only op leaks, deadlocks, or corrupts the operations table on the poisoned failure path | REFUTED — no table entry, no freezer, momentary shared segmentLock under the standard stateLock→segmentLock order, `deactivate()` in finally |
| G3 | `SharedLinkBagBTree.load` routes through the component-lock gate and fails on `checkErrorState` when poisoned | REFUTED — `load` uses only the component's own `SharedResourceAbstract` lock; `checkErrorState`'s single caller is `AtomicOperationsManager:524`, not on this path |
| G4 | Slot-reuse re-put replaces a stateful object with a divergent one | REFUTED — `SharedLinkBagBTree` carries only `fileId`, re-derived identically |
| G5 | A remaining throw path in `restoreReconciledDroppedIndexEngines` masks the primary or skips membership undo | REFUTED — full path enumeration; only non-Assert `Error`s escape (uncontained storage-wide by design) |
| G6 | The shape-2 comment still overstates containment | REFUTED — all four rewritten claims traced to code |
| G7 | The AssertionError wrap trips the setter's own `-ea` postcondition | REFUTED — postcondition tests the (wrapped) argument |
| G8 | The reopen test passes vacuously even with recovery broken | PARTIALLY — insensitive to a hypothetical dirty-flag regression (pages flushed at close), but genuinely fails on replay/registry-rebuild breakage; demanded assertions all present (CS25 caveat) |

## Verdict summary

| Finding | Verdict | Justification |
|---------|---------|---------------|
| CS20 | VERIFIED | Restore mirror is key-correct for pure-drop, idempotent on slot-reuse, no-op without a component file; snapshot-only op wiring violates no lifecycle rule and works poisoned; `load` takes no component-write lock; red-first probe wired into all three failure shapes |
| CS21 | VERIFIED | All four claims of the rewritten shape-2 comment traced to code (single `checkErrorState` caller at the component-lock gate; reads unguarded; durably-committed sub-shape divergence named; dirty-flag preservation verified on both close hooks) |
| CS22 | VERIFIED | AssertionError wrapped into StorageException before `setInError` → poisoning unconditional under `-ea`; the setter's stray-assert guard untouched |
| CS24 | VERIFIED | Both asserts replaced with loud error logs; exhaustive path walk shows no masking throw and `undoAppliedMembership` always runs; addSuppressed skip justified (primary not in signature scope, diagnostics-only gain, log already captures the secondary) |
| CS25 | VERIFIED | Test delivers exactly the demanded close-reopen contract (error cleared, dropped class gone, created class present + writable) on a dedicated DISK DB via the post-durability poison; sensitivity caveat (flushed pages make replay idempotent) documented, non-blocking |
