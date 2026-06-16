<!--
schema: review-file/v1 (conventions-execution.md §2.5, verdict-producer variant)
role: orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial
phase: 3A
track: track-1
iteration: 2
review_type: consolidated (technical + risk + adversarial)
overall: PASS
findings: 0
verdicts:
  - {id: T1, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify T1 "}
  - {id: T2, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify T2 "}
  - {id: T3, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify T3 "}
  - {id: R1, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify R1 "}
  - {id: R2, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify R2 "}
  - {id: R3, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify R3 "}
  - {id: A1, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify A1 "}
  - {id: A2, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify A2 "}
  - {id: A3, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify A3 "}
  - {id: A4, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify A4 "}
  - {id: A5, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify A5 "}
  - {id: A6, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify A6 "}
psi_recheck:
  - {claim: "restoreFrom callers/overrides", result: "2 callers (restoreFromBeginning AbstractStorage:5505, restoreFromIncrementalBackup DiskStorage:1883), 0 overrides", holds: true}
  - {claim: "restoreAtomicUnit throw branches", result: "2 restoreFileById calls (5658,5732) + 2 throw StorageException (5661,5735), 0 overrides", holds: true}
  - {claim: "test classes exist", result: "RestoreAtomicUnitNonDurableSkipTest, RestoreAtomicUnitPageOperationTest, LocalPaginatedStorageRestoreFromWALIT all found", holds: true}
flags: [CONTRACT_OK]
-->

# Track 1 Phase A gate verification — iteration 2 (consolidated)

All twelve iteration-1 findings (technical T1-T3, risk R1-R3, adversarial A1-A6) are
resolved in the updated `track-1.md`, with no regression and no internal contradiction
across the edited sections. The three load-bearing code claims the edits added
(`restoreFrom`'s two callers, the two `restoreAtomicUnit` throw branches, the three
test-class names) were re-confirmed by mcp-steroid PSI against the open
`transactional-schema` project; every claim holds line-for-line. The verification
surfaced zero new findings. Overall: PASS.

The fixes converge on one corrected mental model: the F55 abort is the
`restoreFileById`-returns-`null` throw inside `restoreAtomicUnit` (caught by
`restoreFrom`'s `catch (RuntimeException)`), the discard is cross-unit only, the
shared method has a second IBU caller, the non-durable-skip machinery is left
untouched, and the regression must make later-unit-present / incomplete-unit-absent
effects code-observable. The track file now states all of these consistently.

#### Verify T1 / R1 / A1: fix-site relocalization to the restoreAtomicUnit missing-file throw
- **Original issue**: The track prose framed the fix as a unit-level abort decision in `restoreFrom`'s end-record handling. The real abort is the `restoreFileById`-returns-`null` → `throw StorageException` in the two `restoreAtomicUnit` arms, swallowed by `restoreFrom`'s `catch (RuntimeException)`. A decomposer reading the old prose could edit the wrong site or implement a blind two-pass pre-scan F55 explicitly rejected.
- **Fix applied**: `## Context and Orientation` now names the outer `try { … } catch (RuntimeException)` at AbstractStorage.java ~5602, then localizes F55 inside `restoreAtomicUnit`: the `!writeCache.exists(fileId)` path consults `writeCache.restoreFileById(fileId)`, a non-null result logs and continues ("the load-bearing fallback the fix must preserve"), `null` throws `StorageException` "the rest of operations can not be restored" — the `UpdatePageRecord` branch ~5657-5664 and the `PageOperation` branch ~5731-5739 — "which the outer `catch` turns into the discard." `## Plan of Work` opens by changing "the `restoreFileById`-returns-`null` branches in `restoreAtomicUnit` (the `UpdatePageRecord` and `PageOperation` arms)" and states "The fix is local to those two branches, not a two-pass pre-scan of the log."
- **Re-check**:
  - Track-file location: `## Context and Orientation` ¶2-3, `## Plan of Work` ¶1, `## Interfaces and Dependencies` In-scope bullet.
  - Current state: the fix site is now named as the two `restoreAtomicUnit` throw branches with the exact line bands; the end-record handling is no longer cited as the abort; the two-pass alternative is explicitly excluded.
  - Criteria met: T1's "name the surgical site and branch shape," R1's "the abort is the `throw` at the tail of the missing-file branch, not a standalone decision," A1's "name the precise `restoreFileById`-returns-null → `StorageException` → `catch(RuntimeException)` chain."
  - PSI re-check: `restoreAtomicUnit` contains exactly two `restoreFileById` calls (lines 5658, 5732) and two `throw new StorageException` (lines 5661, 5735); zero overrides. The track's ~5657-5664 / ~5731-5739 bands correctly bracket the call+throw of each arm. The outer catch line ~5602 matches the evidence-base reference.
- **Regression check**: Checked that the non-null fallback is preserved as a keep-invariant (it is — "the load-bearing fallback the fix must preserve" in C&O; Plan of Work: "Leave the non-null `restoreFileById` fallback … untouched"). No contradiction introduced between the now-narrower fix-site framing and the Purpose/Big Picture summary, which still describes the user-visible outcome (committed file-creating unit replays, later units survive). Clean.
- **Verdict**: VERIFIED.

#### Verify R2 / A3: second caller (incremental-backup restore) named in scope
- **Original issue**: The track scoped itself to one replay path; `restoreFrom` has a second production caller, `DiskStorage.restoreFromIncrementalBackup` (IBU restore), which shares the patched `restoreAtomicUnit`. The fix's blast radius and the ~4-file footprint understated this.
- **Fix applied**: `## Interfaces and Dependencies` now carries a dedicated bullet "Shared replay path, two callers (PSI-verified, no overrides)": `restoreFrom` is called from open-time recovery (`AbstractStorage.restoreFromBeginning`) and from incremental-backup restore (`DiskStorage.restoreFromIncrementalBackup`, DiskStorage.java ~1883); "the fix changes shared code, so its blast radius spans both paths; the regression covers the open-time path, and the change must not regress IBU restore." `## Plan of Work` ¶2 repeats: "must hold for both `restoreFrom` callers (open-time recovery `restoreFromBeginning` and incremental-backup restore `restoreFromIncrementalBackup`)." `## Validation and Acceptance` third bullet: "including the incremental-backup restore path that shares `restoreFrom`."
- **Re-check**:
  - Track-file location: `## Interfaces and Dependencies` second bullet; `## Plan of Work` ¶2; `## Validation and Acceptance` third acceptance line.
  - Current state: both callers named with the IBU caller's file/line; the IBU path is called out as a must-not-regress surface.
  - Criteria met: R2's "add the IBU restore path as a second in-scope consumer," A3's "name both callers as in-scope behaviorally."
  - PSI re-check: `restoreFrom` references = exactly 2 — `restoreFromBeginning` (AbstractStorage.java:5505) and `restoreFromIncrementalBackup` (DiskStorage.java:1883); zero overrides. `DiskStorage.restoreFromIncrementalBackup` exists (one definition). The track's "~1883" matches the PSI reference line exactly.
- **Regression check**: Verified the track did not over-claim that the regression must run *through* the IBU path — it states the regression covers the open-time path and the IBU path must not regress, which matches R2's softer "regression (or a sibling) exercises … or the shared unit test is sufficient" and A3's "decomposition decides." No new internal inconsistency. Clean.
- **Verdict**: VERIFIED.

#### Verify A2: cross-unit-only regression construction
- **Original issue**: The single-unit regression sketch is not constructible: within one unit a `FileCreatedWALRecord` precedes its own page redos in log order, so the file exists by the time a redo runs; the abort is reachable only across units. A literal single-unit test passes vacuously.
- **Fix applied**: `## Plan of Work` ¶3: "The discard is only reachable across units: within one unit a `FileCreatedWALRecord` precedes its own page redos in log order, so by the time a redo runs the file already exists. The scenario is therefore a committed file-creating unit whose physical apply is lost, followed by at least one later committed unit." This is restated in `## Context and Orientation` ("a later committed unit's page redo can not find the file").
- **Re-check**:
  - Track-file location: `## Plan of Work` ¶3 (regression description).
  - Current state: the regression is now explicitly cross-unit, with the intra-unit ordering reason stated, so a decomposer cannot build the vacuous single-unit test.
  - Criteria met: A2's "rewrite to a two-unit construction; state that the single-unit shape cannot reach the bug."
  - Code corroboration: consistent with the evidence base — in-order replay within a unit (`AbstractStorage:5624`) and the `FileCreatedWALRecord` branch re-adding the file before page redos. No PSI re-check needed beyond the throw-branch confirmation already done; the claim is about log ordering, which the review evidence and design F55 establish.
- **Regression check**: The cross-unit framing is consistent with the F55 window description in C&O (committed file-creating unit's `addFile` lost; later unit's redo dangles). No contradiction with the "genuinely incomplete unit still discarded" companion case, which is a distinct never-durable scenario. Clean.
- **Verdict**: VERIFIED.

#### Verify A5: code-observable later-unit-present / incomplete-unit-absent assertions
- **Original issue**: The must-still-discard half was prose, not pinned to a code-observable assertion; a careless regression could "verify discard" against a unit that was never going to be applied anyway, which any fix passes.
- **Fix applied**: `## Validation and Acceptance` first bullet: "Make this code-observable: assert the later units' effects are present after restart (the records or pages they wrote), not merely that no exception was logged." Second bullet: "assert the incomplete unit's effects are absent, so a blanket-restore fix fails this case." `## Plan of Work` ¶3 mirrors with the companion assertion.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` bullets 1-2; `## Plan of Work` ¶3.
  - Current state: both the positive (later-unit effects present) and negative (incomplete-unit effects absent) assertions are now required to be code-observable.
  - Criteria met: A5's "make the discard assertion observable … pair it with the committed-but-unapplied case so the contrast is explicit."
  - Code corroboration: consistent with evidence-base V2 (the never-durable unit is structurally dropped, so the assertion must check absence of its effects rather than the drop itself).
- **Regression check**: The two assertions do not contradict the cross-unit construction (A2) — the positive case asserts later-unit survival, the negative case asserts incomplete-unit absence; both are well-formed against the named windows. Clean.
- **Verdict**: VERIFIED.

#### Verify A4: non-durable-skip machinery left untouched and coordinated
- **Original issue**: The track did not position the fix against the existing `deleteNonDurableFilesOnRecovery` / `deletedNonDurableFileIds` skip machinery; an implementer risked duplicating or fighting it.
- **Fix applied**: `## Plan of Work` ¶1: "Leave the non-null `restoreFileById` fallback and the `deletedNonDurableFileIds` skip machinery untouched; the fix narrows only the genuinely-unrecoverable throw, it does not bypass the non-durable-file guards." `## Interfaces and Dependencies` Out-of-scope bullet: "the non-durable-file skip machinery (`deletedNonDurableFileIds`, `deleteNonDurableFilesOnRecovery`), which the fix coordinates with but does not change."
- **Re-check**:
  - Track-file location: `## Plan of Work` ¶1; `## Interfaces and Dependencies` Out-of-scope bullet.
  - Current state: the skip machinery is named, declared out of scope, and flagged as coordinated-with-not-changed — the F55 class (WAL-recorded-but-physically-lost) is distinguished from the never-WAL-logged non-durable file the skip set handles.
  - Criteria met: A4's "note the existing skip machinery and state the F55 case is distinct from the never-WAL-logged non-durable file."
- **Regression check**: The out-of-scope declaration is consistent with R1's keep-invariant on the fallback and with the in-scope bullet limiting edits to the two throw branches. No contradiction. Clean.
- **Verdict**: VERIFIED.

#### Verify T2 / R3 / A6: test seams named (crash-injection, unit harness, IT)
- **Original issue**: The track asserted a crash-replay regression was feasible without naming how the crash is injected or which seams exist; an implementer could reach for a flaky forked-JVM kill, and the regression level (unit vs IT) and footprint were ambiguous.
- **Fix applied**: `## Validation and Acceptance` adds a "Test-construction notes for decomposition" block naming: the copy-files-without-close seam (`LocalPaginatedStorageRestoreFromWALIT.copyDataFromTestWithoutClose`) and a `commitChanges` fault hook after the end record is logged; the existing unit harness (`RestoreAtomicUnitNonDurableSkipTest`, `RestoreAtomicUnitPageOperationTest`) with the note that the regression "may be a `restoreAtomicUnit` unit test rather than a restart IT; that choice changes the file footprint and is settled during decomposition."
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` test-construction notes (bullets 1-2).
  - Current state: all three seams are named; the unit-vs-IT footprint trade-off is explicitly deferred to decomposition, matching A6.
  - Criteria met: T2's "name the copy-files-without-close seam and the post-`commitChanges` fault point," R3's "name the fault-hook site and the two test seams," A6's "prefer the existing unit harness; re-confirm footprint."
  - PSI re-check: `RestoreAtomicUnitNonDurableSkipTest`, `RestoreAtomicUnitPageOperationTest`, and `LocalPaginatedStorageRestoreFromWALIT` all exist at the expected FQNs (single match each). The `copyDataFromTestWithoutClose` method name and `commitChanges` fault point are named consistently with the review evidence base (not separately PSI-confirmed here, but the host classes exist and the methods were PSI/grep-confirmed in iter-1).
- **Regression check**: The test-construction notes are framed as decomposition guidance ("confirm in Phase B"), not as locked-in choices, which avoids over-constraining the step author. No contradiction with the cross-unit construction or the IBU-must-not-regress scope. Clean.
- **Verdict**: VERIFIED.

#### Verify T3: F67 no-FileCreatedWALRecord caveat carried
- **Original issue**: The F67 recycle shape emits no `FileCreatedWALRecord`, so the lazy-consult branch never fires for it; a regression asserting the consult path for every file-touching unit would over-specify and break on that shape.
- **Fix applied**: `## Validation and Acceptance` test-construction notes, third bullet: "The F67 file-recycle shape emits no `FileCreatedWALRecord`, so the `restoreFileById` consult never fires for it; the test matrix must not assert the consult path for that shape."
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` test-construction notes, bullet 3.
  - Current state: the caveat is present and correctly scopes the consult assertion away from the F67 shape.
  - Criteria met: T3's "add an acceptance line stating the consult applies only to units that log a `FileCreatedWALRecord`."
  - Code corroboration: consistent with evidence-base E2 (a unit with no file records replays unchanged; the consult is a no-op).
- **Regression check**: The caveat does not contradict the positive consult-path requirement (A2/A5), which is scoped to file-creating units; the two are complementary. Clean.
- **Verdict**: VERIFIED.

## Findings

(none — pure-verdict pass; all twelve iter-1 findings VERIFIED, three PSI claims re-confirmed, no regression or internal inconsistency surfaced)
