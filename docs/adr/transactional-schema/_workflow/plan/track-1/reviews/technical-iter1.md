<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "AbstractStorage.java:5657,5731 (fix site); track-1.md ## Plan of Work / ## Context and Orientation", anchor: "### T1 ", cert: "Premise P5 / Edge case E1", basis: "Track prose under-specifies the decision point as a unit-level abort in restoreFrom; the real fix is the four-step missing-file branch in restoreAtomicUnit (F55/F73), so a decomposer could implement a wrong-shaped two-pass fix"}
  - {id: T2, sev: suggestion, loc: "LocalPaginatedStorageRestoreFromWALIT.java:156 (copyDataFromTestWithoutClose); track-1.md ## Plan of Work", anchor: "### T2 ", cert: "Integration I2", basis: "Crash-injection seam already exists (copy-files-without-close); naming it de-risks the regression feasibility claim"}
  - {id: T3, sev: suggestion, loc: "track-1.md ## Validation and Acceptance; research-log F73", anchor: "### T3 ", cert: "Edge case E2", basis: "F67 recycle shape produces no file records, so the consult never fires; the test matrix must not assert it for that shape"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 9}
cert_index:
  - {id: P5, verdict: PARTIAL, anchor: "#### P5 "}
  - {id: E1, verdict: PARTIAL, anchor: "#### E1 "}
  - {id: E2, verdict: PARTIAL, anchor: "#### E2 "}
flags: [CONTRACT_OK]
-->

# Track 1 technical review — iteration 1

Track 1 (WAL replay lazy-consult fix) is technically sound and accurately
grounded in the current code. Every production class it names exists, the F55
failure mechanism it describes matches the real `restoreFrom` /
`restoreAtomicUnit` path line-for-line, the durable-end-record-before-apply
window is real and is distinguishable from the genuinely-incomplete case via the
in-memory-only file booking, and the ~4-file scope plus crash-injection
regression are feasible against existing test infrastructure. No blockers. One
should-fix on prose precision that could mislead step decomposition, and two
suggestions to harden the regression.

## Findings

### T1 [should-fix]
**Certificate**: Premise P5 / Edge case E1
**Location**: `core/.../impl/local/AbstractStorage.java:5657` and `:5731` (the
actual fix site); `track-1.md` `## Plan of Work` ("the lazy-consult replay
decision point where a committed-but-unapplied file-creating unit is currently
aborted") and `## Context and Orientation` ("The current replay logic aborts the
restore of such a unit and discards every unit that followed it").
**Issue**: The track prose frames the bug as a *unit-level* decision — replay
"aborts the restore of a committed file-creating unit and discards every later
unit." Read literally that points a decomposer at `restoreFrom`'s
end-record-gated apply loop (`:5530`–`:5546`), where an incomplete unit is in
fact silently dropped, not "aborted." The real abort is narrower and lives one
level down: inside `restoreAtomicUnit`, the **first page-op record of a
not-yet-physically-created file** hits `writeCache.exists → false`,
`restoreFileById` returns `null` (the booking is in-memory only — `bookFileId`
writes the negative name-id entry to `nameIdMap`/`idNameMap` but never persists
it, `WOWCache:749`), so `restoreAtomicUnit` throws `StorageException`
(`AbstractStorage:5661`), and `restoreFrom`'s `catch(RuntimeException)`
(`:5602`) then abandons the rest of the restore. The design settled this as F55
option 3 ("lazy consult"), and F73 pinned the exact branch shape the fix must
take: **`[deletedNonDurableFileIds skip → pending-create consult (internal-id
match) → restoreFileById fallback → throw]`** at `:5657`/`:5731`. The track's
`## Decision Log` D10 cites the design's WAL-revertibility paragraph and F55
caveat but does not carry these pins, and the `## Plan of Work` does not name the
`restoreAtomicUnit` missing-file branch as the surgical site. A decomposer
working from the track prose alone could implement a structurally wrong fix —
e.g., a blind two-pass that applies all `FileCreatedWALRecord`s first (which
F55's resolution explicitly rejected because it would have to re-derive the
intra-unit pages-then-`FileDeleted` ordering by hand), or a unit-completeness
relaxation in `restoreFrom` that does not address the throwing branch at all.
**Proposed fix**: Tighten `## Plan of Work` (or seed the `## Decision Log` D10
entry) to name the surgical site and branch shape explicitly: the fix is the
missing-file branch of `restoreAtomicUnit` (`AbstractStorage:5657`/`:5731`),
turned into the four-step branch
`[non-durable skip → pending-create consult on internalFileId →
restoreFileById fallback → throw]`, materializing a consulted pending create via
the same `readCache.addFile(name, id, writeCache)` call the `FileCreatedWALRecord`
branch uses (`:5643`). Three load-bearing details from F73 must survive into the
steps: (a) the `deletedNonDurableFileIds` gates stay first
(`:5628`/`:5638`/`:5653`); (b) the `restoreFileById` fallback is kept, not
replaced — it resurrects files deleted by a later already-applied unit from
persisted negative name-id entries; (c) the page-record↔`FileCreated` match keys
on `internalFileId`, because backup/restore changes the high bits
(`:5676`/`:5752`). YTDB-1099 already carries these pins as a comment; the track
should reference it.

### T2 [suggestion]
**Certificate**: Integration I2
**Location**: `core/.../paginated/LocalPaginatedStorageRestoreFromWALIT.java:156`
(`copyDataFromTestWithoutClose`); `track-1.md` `## Plan of Work` (the regression
description).
**Issue**: The track asserts a crash-replay regression that "injects a crash in
the durable-end-record-before-apply window" is feasible, but does not name how
the crash is injected. The existing IT family already simulates a crash without a
forked process: `LocalPaginatedStorageRestoreFromWALIT` copies the live on-disk
storage files mid-operation (without closing the source) into a sibling
directory, then opens the copy and lets WAL recovery run (`copyDataFromTestWithoutClose`,
`:156`–`:185`). That is exactly the seam this regression needs — the copy must be
captured after the `AtomicUnitEndRecord` is fsync'd (logged at
`AtomicOperationBinaryTracking.commitChanges:1067`) but before the physical
`readCache.addFile` phase that follows it completes. F55's resolution names the
same home ("the `LocalPaginatedStorageRestoreFromWALIT` family") and a fault hook
after the end-record log in `commitChanges:1067`.
**Proposed fix**: Have decomposition name the copy-files-without-close seam and
the post-`:1067` fault point in the regression step, so the step author does not
re-derive a crash-injection mechanism from scratch. A test-only fault hook (e.g.,
gated by a package-private flag the test sets) between the end-record log and the
physical phase is the cleanest way to land the copy inside the window
deterministically.

### T3 [suggestion]
**Certificate**: Edge case E2
**Location**: `track-1.md` `## Validation and Acceptance`; research-log F73
(the F67-recycle interaction note).
**Issue**: The track's acceptance criteria correctly require that a
genuinely-incomplete unit (end record never durable) is still discarded. F73
adds a second negative case the test matrix should respect: the lazy-consult
branch **never fires for the F67 recycle shape**, where a unit produces no file
records at all (no `FileCreatedWALRecord` to consult). A regression that asserts
the consult path is taken for every file-touching unit would over-specify and
break on that shape. This is a low-risk omission — the track's stated criteria do
not contradict it — but it is worth carrying into the EARS/Gherkin acceptance
lines so the test author scopes the consult assertion to units that actually
carry a `FileCreatedWALRecord`.
**Proposed fix**: Add an acceptance line (or a `## Idempotence and Recovery`
note) stating that the pending-create consult applies only to units that log a
`FileCreatedWALRecord`; a unit with no file records replays unchanged and the
consult does not engage.

## Evidence base

#### P1 AbstractStorage exists and owns the replay path — MATCHES
- **Track claim**: "The WAL restore path replays atomic operations from the last
  checkpoint forward" (track `## Context and Orientation`).
- **Search performed**: PSI `JavaPsiFacade.findClass` +
  `OverridingMethodsSearch` + `ReferencesSearch` on `restoreFrom`,
  `restoreAtomicUnit`, `restoreFromWAL`, `restoreFromBeginning`,
  `recoverIfNeeded`.
- **Code location**: `core/.../impl/local/AbstractStorage.java`.
- **Actual behavior**: `restoreFrom(WriteAheadLog, LogSequenceNumber)` @ `:5512`
  and `restoreAtomicUnit(List<WALRecord>, ModifiableBoolean)` @ `:5620` both have
  **NO overriders**. `DirectMemoryStorage` uses `MemoryWriteAheadLog` (a no-op
  for replay), so the replay logic is single-sourced. `DiskStorage:1883` is a
  *caller* of `restoreFrom` (backup-restore path), not an override.
- **Verdict**: CONFIRMED.
- **Detail**: The fix lands in exactly one production method, with no polymorphic
  divergence — confirms the narrow scope the track claims.

#### P2 WAL record classes named by the design exist — MATCHES
- **Track claim**: replay distinguishes file-create / file-delete / page-update /
  unit-start / unit-end records.
- **Search performed**: PSI `findClass` against reconstructed FQNs.
- **Code location**: all under `...impl.local.paginated.wal`.
- **Actual behavior**: `FileCreatedWALRecord`, `FileDeletedWALRecord`,
  `UpdatePageRecord` (also `PageOperation`), `AtomicUnitStartRecord`,
  `AtomicUnitEndRecord` all FOUND.
- **Verdict**: CONFIRMED.

#### P3 The atomic-unit end record makes the unit durable; physical apply follows — MATCHES
- **Track claim**: "An atomic operation's end record makes the unit durable; the
  physical apply phase then writes the buffered file creates and deletes."
- **Search performed**: grep + Read on `AtomicOperationBinaryTracking.commitChanges`.
- **Code location**: `AtomicOperationBinaryTracking.java:1067` (end-record log)
  followed by the `readCache.addFile`/page-application physical phase.
- **Actual behavior**: `FileCreatedWALRecord` is logged at `:1007`; the
  `AtomicUnitEndRecord` at `:1067`; the physical `readCache.addFile` phase runs
  after. The background flusher (`CASDiskWriteAheadLog:311`–`:313`) makes the end
  record durable independently of the physical phase. Confirms the window is real.
- **Verdict**: CONFIRMED.

#### P4 deletedNonDurableFileIds is the pre-replay deletion set, and the gates run inside restoreAtomicUnit — MATCHES
- **Track claim**: replay must "preserve the existing behavior for genuinely
  incomplete units (those whose end record never became durable)."
- **Search performed**: grep + Read on `recoverIfNeeded`,
  `deleteNonDurableFilesOnRecovery`, the three `deletedNonDurableFileIds.contains`
  gates.
- **Code location**: `AbstractStorage.java:4739` (populate), `:4745` (clear);
  `WOWCache.java:1042` (`deleteNonDurableFilesOnRecovery`); gates at
  `AbstractStorage:5628`/`:5638`/`:5653`/`:5727`.
- **Actual behavior**: Non-durable (never-fsync'd) files are physically deleted
  before replay; their internal ids feed the skip gates so replay does not
  reapply stale data. This is the existing path the fix must not weaken.
- **Verdict**: CONFIRMED.

#### P5 The "lazy-consult replay decision point" the track names — PARTIAL
- **Track claim**: "Locate the lazy-consult replay decision point where a
  committed-but-unapplied file-creating unit is currently aborted" (`## Plan of
  Work`).
- **Search performed**: Read of `restoreFrom` (`:5512`–`:5618`) and
  `restoreAtomicUnit` (`:5620`–`:5821`); cross-checked against research-log F55
  (`:1631`) and F73 (`:2209`) pins.
- **Code location**: `AbstractStorage.java:5657`/`:5731` (the missing-file branch
  that throws) — NOT a unit-level check in `restoreFrom`.
- **Actual behavior**: There is no single "abort the unit" decision point at the
  unit level. An incomplete unit (no durable end record) is silently dropped (it
  stays in `operationUnits` and `restoreAtomicUnit` is never called for it,
  `:5538`–`:5546`). The abort-and-discard-later-units behavior is the throw at
  `:5661`/`:5735` from the missing-file branch, caught at `:5602`. The "lazy
  consult" name refers to consulting the buffered unit for a pending
  `FileCreatedWALRecord` precisely inside that missing-file branch.
- **Verdict**: PARTIAL.
- **Detail**: The mechanism is real and matches the design; the track prose
  localizes it imprecisely (unit-level vs the `restoreAtomicUnit` missing-file
  branch). Produces finding T1.

#### P6 restoreFileById returns null for a booked-but-uncreated file — MATCHES
- **Track claim** (implicit, via F55 caveat in D10): a crashed file-creating unit
  is distinguishable from a genuinely-incomplete one.
- **Search performed**: Read of `WOWCache.bookFileId:732` and
  `restoreFileById:2397`.
- **Code location**: `WOWCache.java:749` (booking writes in-memory negative
  name-id entry), `:2397`–`:2414` (`restoreFileById` scans `nameIdMap` for a
  negative entry).
- **Actual behavior**: `bookFileId` writes the negative name-id entry only to the
  in-memory `nameIdMap`/`idNameMap`; it is not persisted to the side files (only
  `addFile` and explicit registry writes persist). After a crash the reloaded
  `nameIdMap` has no entry for a merely-booked file, so `restoreFileById` returns
  `null` and `restoreAtomicUnit` throws. A genuinely-incomplete unit (no durable
  end record) never reaches `restoreAtomicUnit` at all. The two windows are thus
  distinguishable, and a fix can preserve discard for the never-durable case.
- **Verdict**: CONFIRMED.

#### E1 Crash between durable end record and physical-apply completion — PARTIAL
- **Trigger**: process killed mid-physical-phase, with the `AtomicUnitEndRecord`
  already fsync'd by the background flusher but `readCache.addFile` not yet run
  for some file in the unit.
- **Code path trace**:
  1. Replay reaches the unit's `AtomicUnitEndRecord` → `restoreAtomicUnit(unit)`
     @ `AbstractStorage:5544`.
  2. First record processed is a page-op for the not-yet-created file → file
     does not exist (`writeCache.exists → false`) @ `:5657`/`:5731`.
  3. `restoreFileById(fileId)` returns `null` (booking was in-memory only,
     per P6) @ `WOWCache:2397`.
  4. `restoreAtomicUnit` throws `StorageException` @ `:5661`/`:5735`.
  5. `restoreFrom`'s `catch(RuntimeException)` @ `:5602` logs "rest of changes
     rolled back" and stops the loop, abandoning every later committed unit.
- **Outcome**: today — half-applied unit + all later committed units silently
  discarded (bug F55). With the fix — the missing-file branch consults the
  buffered unit forward for the matching `FileCreatedWALRecord` (on
  `internalFileId`), materializes the file via `readCache.addFile`, applies the
  page, and replay continues.
- **Track coverage**: yes (this is the bug the track exists to fix), but the
  track prose localizes the decision point imprecisely. Feeds finding T1.

#### E2 Unit with no file records (F67 recycle shape) — PARTIAL
- **Trigger**: a replayed unit that touches pages but logs no
  `FileCreatedWALRecord` (the F67 recycle / same-name shape after option (b)).
- **Code path trace**:
  1. `restoreAtomicUnit` reaches a page-op for an existing file → file exists,
     no missing-file branch → applies normally.
  2. The pending-create consult never engages — there is no `FileCreatedWALRecord`
     to find.
- **Outcome**: unit replays unchanged; the consult is a no-op for this shape.
- **Track coverage**: not addressed in `## Validation and Acceptance`. F73's note
  warns the YTDB-1099 test matrix must not expect the consult to fire here. Feeds
  finding T3.

#### E3 Genuinely-incomplete unit (end record never durable) — MATCHES
- **Trigger**: crash before the `AtomicUnitEndRecord` becomes durable.
- **Code path trace**:
  1. Replay never sees the unit's `AtomicUnitEndRecord` → `restoreAtomicUnit` is
     never called for it (`restoreFrom:5538`–`:5546` applies only at the end
     record).
  2. The buffered partial unit is dropped when `restoreFrom` finishes.
- **Outcome**: unit discarded, as required. The fix touches only the missing-file
  branch *inside* `restoreAtomicUnit`, which this unit never reaches, so the
  discard behavior is preserved by construction.
- **Track coverage**: yes — the track's ordering constraint and second acceptance
  criterion require exactly this.

#### I1 restoreAtomicUnit has existing unit-test coverage — MATCHES
- **Plan claim**: "The existing WAL recovery test suite continues to pass
  unchanged" (`## Validation and Acceptance`).
- **Actual entry point**: `restoreAtomicUnit` @ `AbstractStorage:5620`.
- **Caller analysis** (PSI `ReferencesSearch`): production callers are
  `restoreFrom` only; test callers are `RestoreAtomicUnitNonDurableSkipTest`,
  `RestoreAtomicUnitPageOperationTest`, and `AtomicOperationBinaryTrackingWALSkipTest`
  (multiple call sites each). These directly exercise the method the fix edits.
- **Breaking change risk**: the fix adds a branch to the missing-file path; the
  existing tests assert the non-durable-skip and page-operation paths and must
  still pass.
- **Verdict**: MATCHES — the regression-safety claim is testable against existing
  coverage.

#### I2 Crash-injection seam for the integration regression — MATCHES
- **Plan claim**: a crash-replay regression "injects a crash in the
  durable-end-record-before-apply window" (`## Plan of Work`).
- **Actual entry point**: `LocalPaginatedStorageRestoreFromWALIT.copyDataFromTestWithoutClose`
  @ `:156`, which copies the live storage files mid-operation (no close) into a
  sibling directory, then opens the copy so WAL recovery runs.
- **Caller analysis**: the IT itself; the copy-without-close idiom is the proven
  crash simulation for this family.
- **Breaking change risk**: none — the regression is additive.
- **Verdict**: MATCHES — confirms the regression is feasible; feeds suggestion T2
  (name the seam + the post-`commitChanges:1067` fault point in decomposition).

#### I3 Scope is confined to the replay path plus its regression — MATCHES
- **Plan claim**: "This track touches only that replay path and its regression
  coverage … no public API change" (`## Interfaces and Dependencies`).
- **Actual entry point**: the fix edits the missing-file branch of
  `restoreAtomicUnit` (one production method in `AbstractStorage`); the
  regression lives in the `LocalPaginatedStorageRestoreFromWALIT` family; a
  test-only fault hook may touch `AtomicOperationBinaryTracking.commitChanges`
  around `:1067`.
- **Caller analysis**: `restoreAtomicUnit` is `protected final`, single
  production caller; no signature change is implied.
- **Breaking change risk**: none to public API; the change is internal to the
  recovery path.
- **Verdict**: MATCHES — the ~4-file scope is plausible (AbstractStorage fix +
  IT regression + optional commitChanges fault hook + possibly a unit test under
  `RestoreAtomicUnit*Test`).
