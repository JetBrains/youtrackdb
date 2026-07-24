<!--
schema: review-file/v1 (conventions-execution.md §2.5)
role: reviewer-risk
phase: 3A
track: track-1
iteration: 1
findings: 3
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 "
    loc: "track-1.md §Plan of Work; AbstractStorage.java:5657,5731"
    cert: "Assumption: the fix point is a single abort decision to flip"
    basis: research-log
  - id: R2
    sev: should-fix
    anchor: "### R2 "
    loc: "track-1.md §Interfaces and Dependencies; DiskStorage.java:1883; AbstractStorage.java:5676,5752"
    cert: "Exposure: restoreFrom / restoreAtomicUnit shared replay path"
    basis: psi
  - id: R3
    sev: suggestion
    anchor: "### R3 "
    loc: "track-1.md §Plan of Work, §Validation and Acceptance"
    cert: "Testability: crash-replay regression hitting the exact durable-end-record-before-apply window"
    basis: code-read
evidence_base:
  exposures: 1
  assumptions: 2
  testability: 1
-->

# Track 1 risk review — iteration 1

Three findings, none a blocker. The replay path is narrow and singly-owned (PSI: `restoreFrom` and `restoreAtomicUnit` have zero overrides and only production-internal callers), so the blast-radius worry is not "polymorphic dispatch we cannot see." The real risks are that the track file under-specifies the accepted fix shape relative to the design's F55 resolution (R1, the load-bearing `restoreFileById` fallback could be dropped), that the shared method also feeds the incremental-backup restore path the track file does not name (R2), and a minor gap between the track's vague "inject a crash" wording and the design's pinned, deterministic fault hook (R3). The exact-window crash test is achievable: a direct-invocation seam already exists.

## Findings

### R1 [should-fix]
**Certificate**: Assumption — "the fix point is a single abort decision to flip"
**Location**: track-1.md §Plan of Work ("change it to restore the unit and continue replaying later units rather than discarding them"); `AbstractStorage.java:5657` and `:5731` (the two missing-file branches); design.md §"Commit-time reconciliation" / research-log.md F55 resolution (line 1650).
**Issue**: The track file frames the fix as flipping one decision: where a committed-but-unapplied file-creating unit "is currently aborted," restore it and continue. The design's accepted F55 resolution is more specific and more constrained. The abort is not a standalone decision; it is the `throw` at the tail of the missing-file branch, reached only after `restoreFileById` returns `null`. The accepted fix inserts a new step *before* that fallback: scan the buffered unit forward for a `FileCreatedWALRecord` matching on `internalFileId`, materialize the file through `readCache.addFile`, then apply the page; the later `FileCreated` record then replays as an idempotent no-op (research-log.md:1652-1660). Critically, the resolution states the existing `restoreFileById` fallback "resurrects files deleted by later already-applied units from persisted negative name-id entries, load-bearing, must be kept." An implementer working from the track file's "restore and continue" wording alone could plausibly short-circuit or remove the `restoreFileById` call (it looks like part of the abort path being replaced), silently breaking recovery for the genuinely-deleted-file case. Likelihood medium (the track file does not name the fallback as a keep-invariant); impact high (a regression in a different recovery scenario that the F55 test would not exercise).
**Proposed fix**: Amend the track file's Plan of Work (or the Concrete Steps once decomposed) to pin the four-stage missing-file branch order from the F55 resolution explicitly: `deletedNonDurableFileIds` skip → pending-`FileCreated` forward-scan/consult → `restoreFileById` fallback (kept, load-bearing for later-unit deletions) → throw. State the `restoreFileById` fallback as a preserve-unchanged invariant in §Validation and Acceptance, with a test that a file deleted by a later already-applied unit still resurrects.

### R2 [should-fix]
**Certificate**: Exposure — "restoreFrom / restoreAtomicUnit shared replay path"
**Location**: track-1.md §Interfaces and Dependencies ("In scope: the WAL restore/replay logic"); `DiskStorage.java:1883` (incremental-backup restore caller); `AbstractStorage.java:5676` and `:5752` (the `externalFileId(internalFileId(fileId))` normalization the IBU path needs).
**Issue**: The track file scopes itself to "the WAL restore/replay logic that decides whether to restore or abort a committed file-creating atomic operation" and frames the consumer as crash recovery (Track 4's I-A1 tests). PSI find-usages shows `restoreFrom` has a second production caller: `DiskStorage.restoreFromIncrementalBackup` at `:1883`, the incremental-backup (IBU) restore path. `restoreAtomicUnit` is the single inner apply method both paths share. So a change to the missing-file branch lands in two recovery surfaces, not one. The IBU path is exactly where the `internalFileId`-vs-high-bits subtlety bites: the F55 resolution notes that record file-id high bits differ after backup/restore, so the forward-scan match for a pending `FileCreatedWALRecord` MUST key on `internalFileId`, not the raw `fileId` (research-log.md:1654-1655; the existing branch already normalizes via `externalFileId(internalFileId(...))` at `:5676`/`:5752`). A naive forward-scan that compares raw file ids would match in crash recovery but silently fail to match in IBU restore, re-introducing the abort for the very path most likely to stretch the physical phase. Likelihood medium; impact medium (an IBU restore of a file-creating unit aborts and discards later units, the same F55 symptom, in a path the planned regression may not cover).
**Proposed fix**: Add the IBU restore path (`DiskStorage:1883`) to the track file's §Interfaces and Dependencies as a second in-scope consumer of the shared method. Require the forward-scan to match on `internalFileId` (mirroring the existing branch's normalization) and add an acceptance line that the regression (or a sibling) exercises the fix through the incremental-backup restore path, not only the dirty-flag crash path, since the two enter `restoreFrom` differently.

### R3 [suggestion]
**Certificate**: Testability — "crash-replay regression hitting the exact durable-end-record-before-apply window"
**Location**: track-1.md §Plan of Work ("injects a crash in the durable-end-record-before-apply window") and §Validation and Acceptance.
**Issue**: The track file's "inject a crash" wording is vaguer than the mechanism is. Reliably hitting the window (`AtomicUnitEndRecord` durable, physical `readCache.addFile` not yet flushed) and distinguishing it from the never-durable window is the named risk (a fix that blindly restores everything must be caught). Reading `AtomicOperationBinaryTracking.commitChanges` shows the window is deterministically addressable: `FileCreated`/`FileDeleted` records are logged, then the `AtomicUnitEndRecord` (~`:1067`), then the physical `readCache.addFile` loop (~`:1088`). A fault hook between the end-record log and the physical loop reproduces the durable-end window exactly; a hook before the end-record log reproduces the never-durable window. The two are mechanically separable, which is what makes "distinguish the two windows" achievable rather than flaky. Coverage of the new branch (85% line / 70% branch) is also achievable without a real crash: `RestoreAtomicUnitNonDurableSkipTest` and `RestoreAtomicUnitPageOperationTest` already invoke `restoreAtomicUnit` directly with mocked `WriteCache`/`ReadCache` and hand-built WAL record lists, so the pending-create-consult branch can be unit-tested deterministically and the full IT (`LocalPaginatedStorageRestoreFromWALIT` family) reserved for the end-to-end window assertion. The residual risk is that an implementer reads "inject a crash" as "kill a forked JVM and hope," which is timing-dependent and may not pin the window, wasting iterations on a flaky test.
**Proposed fix**: In the track file (or the decomposed step), name the fault-hook site from the F55 resolution (after the `AtomicUnitEndRecord` log in `commitChanges`, before the physical-apply loop) and name the two test seams: the direct `restoreAtomicUnit` unit-test seam (`RestoreAtomicUnitNonDurableSkipTest` pattern) for branch coverage of the consult/fallback/throw arms, and `LocalPaginatedStorageRestoreFromWALIT` for the end-to-end never-durable-vs-durable discrimination. No severity above suggestion: the test is feasible and the infrastructure exists; this only sharpens the plan so the implementer does not reach for a flaky JVM-kill harness.

## Evidence base

#### Exposure: `restoreFrom` / `restoreAtomicUnit` shared replay path
- **Track claim**: The track touches "the WAL restore/replay logic that decides whether to restore or abort a committed file-creating atomic operation" and treats crash recovery as the consumer.
- **Critical path trace**:
  1. Entry (crash recovery): `recoverIfNeeded()` @ `AbstractStorage.java:4713`, runs only when `isDirty()`; calls `deleteNonDurableFilesOnRecovery` (`:4739`) then `restoreFromWAL()` (`:4742`).
  2. `restoreFromWAL()` @ `:5462` → `restoreFromBeginning()` @ `:5498` → `restoreFrom(writeAheadLog, lsn)` @ `:5505`.
  3. `restoreFrom` @ `:5512` reads WAL in 1000-record batches; an `AtomicUnitEndRecord` (`:5533`) pops the unit from `operationUnits` and calls `restoreAtomicUnit(atomicUnit, ...)` @ `:5544`. A unit whose end record never arrived stays in `operationUnits` and is silently dropped (never applied); this is the never-durable case, already correct.
  4. `restoreAtomicUnit` @ `:5620` walks the unit in list order. The missing-file branches (`UpdatePageRecord` @ `:5657`, `PageOperation` @ `:5731`) call `writeCache.restoreFileById(fileId)`; on `null` they `throw new StorageException(... "the rest of operations can not be restored")` (`:5661`/`:5735`).
  5. That `StorageException` escapes the inner `for`, is caught by `restoreFrom`'s `catch (RuntimeException e)` @ `:5602` ("The rest of changes will be rolled back"), which terminates the whole replay loop; every later committed unit is discarded. This is bug F55.
  6. Second entry (incremental-backup restore): `DiskStorage.java:1883` calls the same `restoreFrom(restoreLog, beginLsn)` over a WAL reconstructed from IBU files. Same `restoreAtomicUnit`, same throw, same abort.
- **Blast radius**: A bug in the modified missing-file branch affects both crash recovery and incremental-backup restore (the two `restoreFrom` callers). A fix that drops the `restoreFileById` fallback breaks recovery of files deleted by a later already-applied unit (a different scenario from F55). A forward-scan keyed on raw `fileId` rather than `internalFileId` fails on the IBU path (high bits differ post-backup). No other consumers: PSI shows zero overrides of `restoreFrom`, `restoreAtomicUnit`, `restoreFromWAL`, `restoreFromBeginning`.
- **Existing safeguards**: `deletedNonDurableFileIds` skip at the head of each branch (`:5628`/`:5638`/`:5653`/`:5727`) already handles non-durable-file records; the existing `restoreFileById` fallback (`WOWCache.java:2397`) resurrects files from persisted negative name-id entries. Disk-only assert guards (`:5688`, `:5765`) document the totality contract. Existing tests: `RestoreAtomicUnitNonDurableSkipTest`, `RestoreAtomicUnitPageOperationTest`, `AtomicOperationBinaryTrackingWALSkipTest` invoke `restoreAtomicUnit` directly; `LocalPaginatedStorageRestoreFromWALIT` exercises end-to-end dirty-restore via a no-flush directory copy under WAL protection.
- **Residual risk**: MEDIUM. The path is narrow and singly-owned, but it is shared by two recovery surfaces (R2) and the fix must preserve a fallback that looks like part of the abort path being replaced (R1).

#### Assumption: the fix point is a single abort decision to flip
- **Track claim**: "Locate the lazy-consult replay decision point where a committed-but-unapplied file-creating unit is currently aborted, and change it to restore the unit and continue replaying later units."
- **Evidence search**: Read of `AbstractStorage.restoreAtomicUnit` (`:5620`–`:5825`) and `restoreFrom` (`:5512`–`:5618`); cross-read of research-log.md F55 resolution (`:1631`–`:1680`) and F73 (`:2209`).
- **Code evidence**: The abort is the `throw` at `:5661`/`:5735`, reached only after `restoreFileById` returns `null`. The accepted fix (research-log.md:1652) is `[deletedNonDurableFileIds skip → pending-create forward-scan/consult → restoreFileById fallback → throw]`, inserting a new consult step and explicitly keeping `restoreFileById` ("load-bearing, must be kept"). There is no standalone "decision point" to flip; the term "lazy-consult" names the new forward-scan-then-consult mechanism the fix adds.
- **Verdict**: UNVALIDATED. The track-file framing is looser than the accepted resolution and omits the keep-invariant on `restoreFileById`.
- **Detail**: The risk is not that the design is wrong; the design (research-log.md / design.md) is precise. The risk is that the track file, which the implementer reads as authoritative for the how, does not carry the four-stage order or the fallback keep-invariant. See R1.

#### Assumption: the shared replay method has only crash-recovery callers
- **Track claim**: §Interfaces and Dependencies names crash recovery (Track 4 I-A1) as the consumer; no other restore caller is mentioned.
- **Evidence search**: PSI `ReferencesSearch` over `AbstractStorage#restoreFrom` and `#restoreAtomicUnit` (mcp-steroid, project `transactional-schema`, all-scope).
- **Code evidence**: `restoreFrom` production callers = `AbstractStorage:5505` (crash recovery) and `DiskStorage:1883` (incremental-backup restore). `restoreAtomicUnit` production caller = `AbstractStorage:5544`; the rest are tests. Zero overrides on either.
- **Verdict**: CONTRADICTED (partially). A second production consumer exists.
- **Detail**: The IBU restore path is the one where the `internalFileId` high-bits subtlety matters; see R2.

#### Testability: crash-replay regression hitting the exact durable-end-record-before-apply window
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: Hitting the precise window (end record durable, physical apply not flushed) and distinguishing it from the never-durable window is the named risk. A real forked-JVM kill is timing-dependent and may not pin the window.
- **Existing test infrastructure**: `RestoreAtomicUnitNonDurableSkipTest` (`core/.../impl/local/`) and `RestoreAtomicUnitPageOperationTest` invoke `restoreAtomicUnit` directly with `mock(WriteCache.class)` / `mock(ReadCache.class)` and hand-built `List<WALRecord>`, a deterministic seam for branch coverage of the new consult/fallback/throw arms. `LocalPaginatedStorageRestoreFromWALIT` provides the end-to-end dirty-restore harness (no-flush directory copy under WAL protection). The fault-hook site is mechanically separable: in `AtomicOperationBinaryTracking.commitChanges` the `AtomicUnitEndRecord` is logged (~`:1067`) before the physical `readCache.addFile` loop (~`:1088`), so a hook between them yields the durable window and a hook before it yields the never-durable window.
- **Feasibility**: ACHIEVABLE.
- **Detail**: Feasible with the existing seams; the only gap is that the track file's "inject a crash" wording does not name the fault hook or the unit-test seam, risking a flaky JVM-kill approach. See R3.
