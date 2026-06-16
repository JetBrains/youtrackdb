<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 1: WAL replay lazy-consult fix (prerequisite)

## Purpose / Big Picture
After this track, a crash partway through an atomic operation's physical apply
replays cleanly instead of aborting the restore of a committed file-creating unit
and discarding every later unit.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Fix the lazy-consult WAL replay path so a crash between an atomic operation's end
record becoming durable and its physical apply completing no longer aborts the
restore. This is the prerequisite the reconciliation crash-recovery claim (I-A1,
D10) rests on: the transactional schema commit creates and drops collections and
engines inside the commit's own atomic operation, so its crash recovery is only
as sound as the replay path underneath it. The fix shares no files with the
schema or index subsystems, so it stands as an independently reviewable unit at
the front of the series.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (skipped — single-step track, fully reviewed in Phase B)
- [x] Track completion
- [x] 2026-06-16T10:58Z [ctx=info] Review + decomposition complete
- [x] 2026-06-16T12:02Z [ctx=safe] Step 1 complete (commit 03a23a0139)
- [x] 2026-06-16T12:51Z [ctx=safe] Track complete (single-step, code review skipped)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- 2026-06-16T12:02Z Step 1 discovered: the replay idempotent-no-op rests on
  `writeCache.exists` flipping to true after `readCache.addFile`, and
  `ensureFileForReplay` is the single reconciliation point for a missing-file
  page redo, reached by both `restoreFrom` callers, so Track 4 (I-A1) can
  assume this prerequisite has landed. See Episodes §Step 1.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Seeded from the frozen
design.md D-records this track owns. -->

#### D10 (recovery prerequisite): WAL replay must restore a committed file-creating unit lazily
- **Alternatives considered**: ship the transactional schema commit on the current replay path and accept the crash-recovery gap; add a separate deletion/reinit pool to sidestep the replay path entirely (rejected under D10 — the pool's only correctness benefit is already free, and its performance benefit needs a new WAL-logged clear-and-reinit op because the one "empty but keep the file" path, `truncateFile`, is not crash-safe).
- **Rationale**: D10 makes structural revertibility free through buffered file-create/delete intent applied only in `commitChanges`, which rollback skips. The "replay cleanly" half of that guarantee (F16) is conditional until the lazy-consult fix lands: today a crash between the end record becoming durable and the physical apply phase completing aborts the restore of a committed file-creating unit and discards all later units. The transactional schema commit is the first feature to lean on file-create/delete inside a recoverable atomic operation as a routine path, so the gap must close before its crash-recovery invariant is real.
- **Risks/Caveats**: the replay path is shared crash-recovery infrastructure; the regression must inject a crash at exactly the durable-end-record-before-apply window and assert later units survive. Out-of-scope failure injection elsewhere in replay is not this track's concern.
- **Implemented in**: this track (step references added during execution)
- **Full design**: design.md §"Commit-time reconciliation" (the WAL revertibility paragraph and its F55 recovery caveat)

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (3 findings, 3 accepted; 0 blocker / 1 should-fix / 2 suggestion). Relocalized the fix to the `restoreAtomicUnit` `restoreFileById`-null throw branches.
- [x] Risk: PASS at iteration 2 (3 findings, 3 accepted; 0 blocker / 2 should-fix / 1 suggestion). Named the IBU second caller of `restoreFrom` and the non-null `restoreFileById` keep-invariant.
- [x] Adversarial: PASS at iteration 2 (6 findings, 6 accepted; 0 blocker / 3 should-fix / 3 suggestion). Cross-unit-only regression, code-observable discard assertion, non-durable-skip coordination.
- Gate verification (consolidated technical+risk+adversarial, iteration 2): PASS — all 12 findings VERIFIED, 0 new, 0 regression; PSI re-confirmed two callers / zero overrides and the two throw branches.
- Step 1 dimensional review (risk: high): PASS at iteration 2. Iter 1 surfaced 1 should-fix + 5 suggestions across bugs-concurrency / crash-safety / test-crash-safety / test-structure; all six applied in one `Review fix:` commit and VERIFIED at the iter-2 gate check (0 regressions, 0 new findings).

## Context and Orientation
`AbstractStorage.restoreFrom` replays atomic operations from the last checkpoint
forward, calling `restoreAtomicUnit` on each `AtomicUnitEndRecord` to do the
unit's physical apply (file creates/deletes and page redos). The whole replay
loop runs inside a `try { … } catch (RuntimeException)` (AbstractStorage.java
~5602): any exception out of `restoreAtomicUnit` is logged as "the rest of
changes will be rolled back" and replay returns, so every unit after the throwing
one is silently discarded.

F55 is reached inside `restoreAtomicUnit`, not in the end-record handling. When a
page redo targets a file that is not present (`!writeCache.exists(fileId)`) the
code consults `writeCache.restoreFileById(fileId)`: a non-null result logs and
continues (the load-bearing fallback the fix must preserve), but `null` throws
`StorageException` "the rest of operations can not be restored" (the
`UpdatePageRecord` branch ~5657-5664 and the `PageOperation` branch ~5731-5739),
which the outer `catch` turns into the discard. The crash window F55 names: a
committed file-creating unit's physical `addFile` is not applied before the crash
(its records fall outside the replayed window), so a later committed unit's page
redo can not find the file and `restoreFileById` can not recover it. The full
F55/F73 mechanism is in the research log; the fix site is the `null` branch above.

This track touches only that replay path and its regression coverage. It does not
introduce any schema, index, or commit-machinery code; those build on top of it
in later tracks. The crash-recovery half of I-A1 (Track 4) cites this fix as its
prerequisite, so Track 4's reconciliation crash tests assume this track has
landed.

## Plan of Work
Change the `restoreFileById`-returns-`null` branches in `restoreAtomicUnit` (the
`UpdatePageRecord` and `PageOperation` arms) so a committed file-creating unit
whose file was not yet physically created is restored and replay continues,
instead of throwing `StorageException` and letting the outer
`catch (RuntimeException)` in `restoreFrom` discard every later unit. The fix is
local to those two branches, not a two-pass pre-scan of the log. Leave the
non-null `restoreFileById` fallback and the `deletedNonDurableFileIds` skip
machinery untouched; the fix narrows only the genuinely-unrecoverable throw, it
does not bypass the non-durable-file guards.

The fix touches shared replay code, so it must hold for both `restoreFrom`
callers (open-time recovery `restoreFromBeginning` and incremental-backup restore
`restoreFromIncrementalBackup`; see Interfaces and Dependencies), and must not
weaken recovery for a genuinely incomplete unit, whose record was never made
durable and which must still be discarded.

Add a crash-replay regression. The discard is only reachable across units: within
one unit a `FileCreatedWALRecord` precedes its own page redos in log order, so by
the time a redo runs the file already exists. The scenario is therefore a
committed file-creating unit whose physical apply is lost, followed by at least
one later committed unit, with the crash injected in the
durable-end-record-before-physical-apply window. After restart, assert both the
file-creating unit and every later unit are restored. A companion assertion holds
the discard path: a genuinely incomplete unit (end record never durable) must
still be discarded, so a fix that blindly restores everything is caught.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. Per-step episodes
do NOT live here; they live in `## Episodes` below. -->

1. Fix the F55 lazy-consult abort in `AbstractStorage.restoreAtomicUnit`: change the `restoreFileById`-returns-`null` handling in the `UpdatePageRecord` and `PageOperation` arms so a committed file-creating unit whose file is not yet physically present is recovered and replay continues, instead of throwing `StorageException` and letting the `catch (RuntimeException)` in `restoreFrom` discard every later unit. Leave the non-null `restoreFileById` fallback, the `deletedNonDurableFileIds` skip machinery, and the genuinely-incomplete-unit discard untouched. Ship the cross-unit crash-replay regression with the fix (per `## Plan of Work` and `## Validation and Acceptance`): decide between a `restoreAtomicUnit` unit test (existing `RestoreAtomicUnitNonDurableSkipTest`/`RestoreAtomicUnitPageOperationTest` harness) and a `LocalPaginatedStorageRestoreFromWALIT` restart IT during implementation, and assert later-unit effects present + genuinely-incomplete-unit effects absent. — risk: high (crash-safety/durability: modifies WAL replay/recovery in `AbstractStorage`, shared by open-time and IBU restore)  [x]  commit: 03a23a0139

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 03a23a0139, 2026-06-16T12:02Z [ctx=safe]
**What was done:** Fixed the F55 lazy-consult abort in
`AbstractStorage.restoreAtomicUnit` behind a new private helper
`ensureFileForReplay`, shared by the `UpdatePageRecord` and `PageOperation`
arms. On a missing-file page redo the helper scans the current atomic unit
forward for a matching `FileCreatedWALRecord` and materializes the file via
`readCache.addFile` (the pending-create consult); failing that it falls
through to the preserved non-null `restoreFileById` fallback; failing that
it throws the original `StorageException` for a genuinely-incomplete unit.
The `deletedNonDurableFileIds` skip stays first in each caller, untouched.
Added regression coverage to `RestoreAtomicUnitPageOperationTest`: both
arms' consult-recovery, cross-unit survival, the genuinely-incomplete unit
still throwing, and the non-null fallback preserved.

**What was discovered:** The cross-unit survival assertion holds only if the
mock models the post-`addFile` state transition. After the consult calls
`readCache.addFile`, `writeCache.exists` must flip to true, so the unit's
own later `FileCreatedWALRecord` replays as the idempotent no-op the design
names. This confirms that no-op claim is real and rests on `exists` flipping
after `addFile`. The fix sits in shared replay code reached by both
`restoreFrom` callers (open-time `restoreFromBeginning` and incremental-backup
`DiskStorage.restoreFromIncrementalBackup`), so the IBU path now also recovers
lazily, with no IBU regression in the WAL/restore spot-check. Track 4's I-A1
crash-recovery tests can now assume this prerequisite has landed;
`ensureFileForReplay` is the single reconciliation point for a missing-file
page redo during replay.

**What changed from the plan:** Settled the test-home choice toward the
existing Mockito unit harness (`RestoreAtomicUnitPageOperationTest`) rather
than a `LocalPaginatedStorageRestoreFromWALIT` restart IT. The harness drives
`restoreAtomicUnit` directly and reproduces the cross-unit discard by running
two units in sequence, which makes the later unit's survival code-observable
without the heavy IT crash-injection. The track left this choice to
implementation, and no production behavior diverges from the Plan of Work.

**Key files:**
- `AbstractStorage.java` (modified)
- `RestoreAtomicUnitPageOperationTest.java` (modified)

## Validation and Acceptance
- A committed file-creating atomic operation that crashes between its durable end
  record and the completion of its physical apply is fully restored on restart,
  and every atomic operation that followed it in the log is also restored. Make
  this code-observable: assert the later units' effects are present after restart
  (the records or pages they wrote), not merely that no exception was logged.
- A genuinely incomplete unit (end record never made durable) is still discarded
  on replay, with no regression to existing recovery behavior — assert the
  incomplete unit's effects are absent, so a blanket-restore fix fails this case.
- The existing WAL recovery test suite continues to pass unchanged, including the
  incremental-backup restore path that shares `restoreFrom`.

Test-construction notes for decomposition (existing seams, confirm in Phase B):
- A crash can be injected by copying the storage files without a clean close
  (`LocalPaginatedStorageRestoreFromWALIT.copyDataFromTestWithoutClose`), or by a
  fault hook after the end record is logged in `commitChanges`.
- A unit-level harness for `restoreAtomicUnit` already exists
  (`RestoreAtomicUnitNonDurableSkipTest`, `RestoreAtomicUnitPageOperationTest`),
  so the regression may be a `restoreAtomicUnit` unit test rather than a restart
  IT; that choice changes the file footprint and is settled during decomposition.
- The F67 file-recycle shape emits no `FileCreatedWALRecord`, so the
  `restoreFileById` consult never fires for it; the test matrix must not assert
  the consult path for that shape.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->
- **Step 1**: the change is one localized edit to `restoreAtomicUnit` plus its
  test; re-running the implementer is idempotent (the orchestrator's
  `git reset --hard HEAD` discards a partial attempt). The implemented behavior
  itself improves replay recovery: a restart after the crash window re-runs
  `restoreFrom` from the last checkpoint and now restores rather than discards
  the later units, and the run is repeatable because replay reads from the
  durable WAL, not from the partially-applied file state.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies
- **In scope**: the `restoreFileById`-returns-`null` branches in
  `AbstractStorage.restoreAtomicUnit` (the `UpdatePageRecord` and `PageOperation`
  arms) and the crash-replay regression test.
- **Shared replay path, two callers (PSI-verified, no overrides)**: `restoreFrom`
  is called from open-time recovery (`AbstractStorage.restoreFromBeginning`) and
  from incremental-backup restore (`DiskStorage.restoreFromIncrementalBackup`,
  DiskStorage.java ~1883). The fix changes shared code, so its blast radius spans
  both paths; the regression covers the open-time path, and the change must not
  regress IBU restore.
- **Out of scope**: all schema, index, and commit-machinery code (later tracks);
  the non-durable-file skip machinery (`deletedNonDurableFileIds`,
  `deleteNonDurableFilesOnRecovery`), which the fix coordinates with but does not
  change; any replay behavior outside the durable-end-record-before-apply window.
- **Inter-track dependencies**: none upstream (this is the front of the series).
  Track 4 (commit-time reconciliation) consumes this fix — its I-A1 crash-recovery
  tests assume the lazy-consult fix has landed.
- **Signatures**: confined to the existing replay path; no public API change.

## Base commit
7a0fbf8cfe48448e60d69cd1bec76ab925e6dc6f
