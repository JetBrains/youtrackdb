<!--MANIFEST
dimension: test-crash-safety
prefix: TY
iteration: 1
commit_range: 057620f8a0~1..057620f8a0
findings: 2
verdict: changes-recommended
blockers: 0
should_fix: 1
suggestions: 1
evidence_base: 2
cert_index:
  - id: C1
    claim: "Cross-unit survival test asserts redo-invocation, not the later unit's durable page effect the acceptance criterion names."
    verdict: confirmed-as-issue
  - id: C2
    claim: "Genuinely-incomplete-unit test asserts the throw but not the effect-absent postcondition the acceptance criterion names."
    verdict: confirmed-as-issue
flags: []
index:
  - id: TY1
    sev: should-fix
    anchor: "#ty1-cross-unit-survival-asserts-redo-invocation-not-the-durable-effect"
    loc: "core/.../RestoreAtomicUnitPageOperationTest.java:283-330"
    cert: C1
    basis: "track-1.md ## Validation and Acceptance criterion 1; sibling test line 132-134; production AbstractStorage.java:5707-5760"
  - id: TY2
    sev: suggestion
    anchor: "#ty2-incomplete-unit-test-omits-the-effect-absent-postcondition"
    loc: "core/.../RestoreAtomicUnitPageOperationTest.java:338-358"
    cert: C2
    basis: "track-1.md ## Validation and Acceptance criterion 2; production AbstractStorage.java:5820-5851"
-->

## Findings

### TY1 [should-fix] Cross-unit survival asserts redo-invocation, not the durable effect

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/RestoreAtomicUnitPageOperationTest.java` (lines 283-330)
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (`restoreAtomicUnit`, PageOperation arm 5707-5760; `ensureFileForReplay` 5820-5851)

**Evidence (CRASH POINT + RECOVERY CHECK).**
CRASH POINT: between a committed file-creating unit's durable end record and the completion of its physical `addFile` (the F55 window), a later committed unit's page redo points at a file the cache never created. `testLaterUnitSurvivesAfterRecoverableFileCreatingUnit` is the one regression that exercises the *cross-unit* discard — the only shape that actually reaches the bug, since within one unit a `FileCreatedWALRecord` precedes its own page redos.
RECOVERY CHECK: the test crashes at the right point (unit 1 recoverable file-creating, unit 2 durable, driven in sequence exactly as `restoreFrom`'s loop does), but its survival assertion for unit 2 is `verify(unit2Op).redo(any(DurablePage.class))` (line 329) — it asserts redo was *invoked*. The track's acceptance criterion 1 is explicit: assert the later unit's effects are present "(the records or pages they wrote), **not merely that no exception was logged**." Because `readCache` is mocked, `unit2Op.redo` runs against a mock `DurablePage` and no page byte or LSN is ever read back.

**Missing scenario**: a refactor that still *calls* `redo` on the later unit but drops or misorders its durable effect (e.g. the `pageLsn.compareTo(pageOp.getLsn()) < 0` guard inverted, or `setLsn` removed) leaves `verify(...).redo(...)` green while the later unit's page is silently not advanced — exactly the data-loss class this regression exists to catch.

**Why it matters**: the regression's headline claim is "the later unit was NOT discarded." Discard manifests as a missing durable page mutation, not a missing method call. The sibling happy-path test already shows the stronger assertion is cheap and supported by this harness: `testPageOperationRedoAppliedAndLsnUpdated` reads `DurablePage.getLogSequenceNumberFromPage(buffer)` and asserts it equals the WAL LSN (lines 132-134). Mirroring that on `unit2` makes survival code-observable per the acceptance criterion.

**Suggested test** (extend the existing method, do not duplicate it):
```java
// After the redo verifies, assert unit 2's page actually advanced to its WAL LSN —
// survival is a durable page mutation, not just a redo() invocation.
var unit2Buffer = unit2Entry.getCachePointer().getBuffer();
assertEquals(
    "Later unit's page LSN must advance to its WAL LSN after replay",
    unit2Lsn,
    DurablePage.getLogSequenceNumberFromPage(unit2Buffer));
```

### TY2 [suggestion] Incomplete-unit test omits the effect-absent postcondition

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/RestoreAtomicUnitPageOperationTest.java` (lines 338-358)
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (`ensureFileForReplay` throw branch 5847-5850)

**Evidence (RECOVERY CHECK).**
`testGenuinelyIncompleteUnitStillThrows` holds the discard path the fix must not erase: a page redo for a missing file with no matching `FileCreatedWALRecord` and a `null` `restoreFileById`. It asserts `assertThrows(StorageException.class, ...)` (lines 355-357). The track's acceptance criterion 2 phrases the postcondition as the *effect being absent*: "assert the incomplete unit's effects are absent, so a blanket-restore fix fails this case." The test proves the exception fires, but does not pin that the incomplete unit produced no page mutation or file materialization before throwing.

**Missing scenario**: a future change that throws *after* partially applying the incomplete unit (e.g. materializing the file then failing later) would still pass `assertThrows` while leaking a half-applied effect — the asymmetry the criterion guards against.

**Why it matters**: the throw currently happens in `ensureFileForReplay` before `loadOrAddForWrite` and before any `redo`, so no effect leaks today; the negative verify documents and pins that ordering so a reorder cannot silently regress it.

**Suggested test** (add to the existing method, before/after the `assertThrows`):
```java
// The incomplete unit must produce no effect: no file materialized, no redo applied.
verify(readCache, never()).addFile(any(), anyLong(), any());
verify(pageOp, never()).redo(any(DurablePage.class));
```

## Evidence base

#### C1 — Cross-unit survival test asserts redo-invocation, not the durable page effect

CONFIRMED-as-issue. Refutation attempt: "the mocked `readCache` makes a page-LSN assertion impossible, so redo-invocation is the strongest available check." Refuted — the sibling test `testPageOperationRedoAppliedAndLsnUpdated` (lines 132-134) reads back `DurablePage.getLogSequenceNumberFromPage` from the buffer produced by `createCacheEntryWithLsn` and asserts it against the WAL LSN, on the same mocked-`readCache` harness; the cross-unit test already wires `unit2Entry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, pageLsn)` (line 311), so the buffer is available and the stronger assertion is one statement away. Second refutation attempt: "the acceptance criterion is satisfied because the later unit's redo running is its effect." Refuted by the criterion's own parenthetical — "(the records or pages they wrote), not merely that no exception was logged" — which names the durable page write, not the dispatch, as the observable. Reference-accuracy: PSI confirmed `TestPageOperation(long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn, int testValue)` and that `DurablePage.getLsn`/`setLsn` drive the page-LSN compare in the production PageOperation arm (5739-5754), so a survival assertion on the page LSN is well-typed.

#### C2 — Incomplete-unit test asserts the throw but not the effect-absent postcondition

CONFIRMED-as-issue (suggestion-grade). Refutation attempt: "the throw fires inside `ensureFileForReplay` before `loadOrAddForWrite` and before any `redo`, so no effect can leak — the negative verify is redundant today." Partially holds for the current code, which is why this is a suggestion, not a should-fix: the call order is `ensureFileForReplay(atomicUnit, fileId)` at 5715, then `externalFileId` at 5718, then `loadOrAddForWrite`/`redo`. The throw at 5847 precedes all of that, so no page effect leaks at HEAD. The finding stands as defense-in-depth: the acceptance criterion phrases the postcondition as effect-absence, and the negative verify pins the throw-before-apply ordering so a later reorder cannot regress it without a red test. Reference-accuracy: PSI confirmed the `FileCreatedWALRecord(long, String, long)` constructor and `getFileId()`/`getFileName()` accessors the consult matches on, so the "no matching `FileCreatedWALRecord`" precondition the test sets up (a unit with only a `PageOperation` and an end record) genuinely drives `ensureFileForReplay` past the consult loop into the `restoreFileById`-null throw.
