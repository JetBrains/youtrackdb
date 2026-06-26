<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: A1, sev: should-fix, loc: AbstractStorage.java:5660, anchor: "### A1 ", cert: C1, basis: "track names the wrong abort site; the real F55 abort is restoreFileById-returns-null at 5660/5734 caught by catch(RuntimeException) at 5602, not the end-record handling in restoreFrom which never aborts committed units"}
  - {id: A2, sev: should-fix, loc: AbstractStorage.java:5544, anchor: "### A2 ", cert: V1, basis: "the discard-later-units regression is only constructible cross-unit; within a single unit FileCreatedWALRecord precedes its page updates in log order, so the single-unit regression sketch cannot reach the abort"}
  - {id: A3, sev: should-fix, loc: DiskStorage.java:1883, anchor: "### A3 ", cert: T1, basis: "restoreFrom has 2 production callers (from-beginning + DiskStorage IBU/fuzzy restore); the fix and regression must be conscious of both paths or the ~4-file footprint is wrong"}
  - {id: A4, sev: suggestion, loc: AbstractStorage.java:4739, anchor: "### A4 ", cert: T1, basis: "deleteNonDurableFilesOnRecovery + the non-durable-skip machinery is the already-built infrastructure adjacent to the fix; planner should confirm the fix does not duplicate or fight it"}
  - {id: A5, sev: suggestion, loc: track-1.md:69, anchor: "### A5 ", cert: V2, basis: "the must-still-discard half is stated but not pinned to a code-observable assertion; strengthen the acceptance line so a blanket-restore fix is actually caught"}
  - {id: A6, sev: suggestion, loc: RestoreAtomicUnitNonDurableSkipTest.java:120, anchor: "### A6 ", cert: T2, basis: "a unit-level Mockito harness for restoreAtomicUnit already exists; the regression may be a unit test rather than a restart IT, which changes the file count and the window-injection mechanics"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2, verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: T1, verdict: FRAGILE, anchor: "#### T1 "}
  - {id: T2, verdict: HOLDS, anchor: "#### T2 "}
  - {id: S1, verdict: HOLDS, anchor: "#### S1 "}
flags: [CONTRACT_OK]
-->

# Track 1 adversarial review — iteration 1

Track 1 is the foundational prerequisite. Its sizing and the regression's
fidelity constrain the crash-recovery claim every later track leans on (I-A1,
D10), so the scope and invariant challenges run; the cross-track-episode
challenge is dropped because no prior track has executed. The track survives the
review with no blocker: the decision (fix the lazy-consult replay path, no pool)
holds against its rejected alternative. Three should-fix findings target a
mismatch between the track's prose framing and the actual code site, the
constructibility of the regression, and an unlisted second caller of the patched
method. Three suggestions harden the acceptance lines and the footprint estimate.

## Findings

### A1 [should-fix]
**Certificate**: Challenge C1 (Decision D10 — lazy-consult replay fix)
**Target**: Decision D10 / track `## Plan of Work` framing
**Challenge**: The track describes the fix site as "the lazy-consult replay
decision point where a committed-but-unapplied file-creating unit is currently
aborted." That phrasing points a reader at the wrong code. `restoreFrom`
(`AbstractStorage.java:5533-5546`) applies a committed unit — one whose
`AtomicUnitEndRecord` is durable — the instant it sees the end record; it never
aborts a committed unit. A unit whose start was seen but whose end never became
durable is simply left in the `operationUnits` map and silently dropped at loop
end, again with no abort. The only path that aborts the restore and discards
every later unit is the `throw new StorageException(...)` at lines 5660-5664
(`UpdatePageRecord` branch) and 5734-5739 (`PageOperation` branch), reached when
`writeCache.restoreFileById(fileId)` returns `null` because the lazy consult of
`nameIdMap` finds no `-intId` entry. That exception propagates out of
`restoreAtomicUnit`, unwinds `restoreFrom`'s record loop, and is swallowed by the
`catch (final RuntimeException e)` at line 5602 whose log reads "Data restore was
paused ... The rest of changes will be rolled back." If the implementer takes the
prose literally and edits the end-record handling in `restoreFrom`, the fix lands
in the wrong place and the genuine abort path is untouched.
**Evidence**: `AbstractStorage.java:5533-5546` (committed unit applied
immediately, not aborted); `5660-5664` and `5734-5739` (the throw sites); `5602`
(the catch that turns the throw into "discard the rest"); `WOWCache.java:2397-2414`
(`restoreFileById` returns `null` when no matching `-intId` entry exists — the
lazy-consult miss).
**Proposed fix**: In the track `## Plan of Work` and `## Context and
Orientation`, name the precise abort mechanism: the `restoreFileById`-returns-null
→ `StorageException` → `catch(RuntimeException)` chain, citing the two throw sites
and the catch. Decomposition then targets the throw sites (or the consult) rather
than the end-record bookkeeping. D10's design seed in `design.md §"Commit-time
reconciliation"` should carry the same code anchors so the immutable Decision
Record matches the code.

### A2 [should-fix]
**Certificate**: Violation scenario V1 (the discard-later-units regression)
**Target**: Invariant I-A1 (crash-recovery half) / track regression sketch
**Challenge**: The regression as sketched — "creates a file-creating committed
unit, injects a crash in the durable-end-record-before-apply window, restarts,
and asserts both the unit and every later unit are restored" — is not
constructible as a single-unit scenario, so a literal reading produces a test
that never exercises the abort. Within one atomic unit the `FileCreatedWALRecord`
is logged before the `UpdatePageRecord`/`PageOperation` records that touch the new
file (the file must exist in the unit before pages are written into it).
`restoreAtomicUnit` replays the unit's records in list order
(`AbstractStorage.java:5624`), so the `FileCreatedWALRecord` branch
(`5636-5648`) re-adds the file via `readCache.addFile(...)` before any page
update for it is replayed — `restoreFileById` is never consulted and never
returns null for that file. The abort is only reachable when a **later** unit's
page-update references a file whose creating unit was itself not applied (its
`FileCreatedWALRecord` was skipped because the file was deleted as non-durable, or
its creating unit was incomplete), and that file is also absent from `nameIdMap`'s
`-intId` reverse entries. The "discards every later unit" symptom is therefore an
inter-unit property, not an intra-unit one.
**Evidence**: `AbstractStorage.java:5624` (in-order replay within a unit);
`5636-5648` (`FileCreatedWALRecord` re-adds the file early); `5649-5673` /
`5723-5749` (the page-update branches that consult `restoreFileById` and throw on
null); `WOWCache.java:1042-1101` (`deleteNonDurableFilesOnRecovery` proactively
deletes non-durable files pre-replay, which is what makes a later unit's reference
dangle).
**Proposed fix**: Rewrite the regression sketch to a two-unit (or unit-plus-later-
units) construction: unit U1 creates a file and commits (end record durable); the
crash drops U1's physical file create (non-durable / unapplied); unit U2 (and
further units) follow U1 in the log and reference U1's file or simply must survive.
The assertion is that U2 and all later units restore rather than being discarded by
the abort U1 would otherwise trigger. State in the track that the single-unit shape
cannot reach the bug.

### A3 [should-fix]
**Certificate**: Assumption test T1 (single replay entry point)
**Target**: Assumption — "this track touches only that replay path"
**Challenge**: The track assumes one replay path. `AbstractStorage.restoreFrom`
has two production callers, not one: `restoreFromBeginning`
(`AbstractStorage.java:5505`, the crash-restart path) and
`DiskStorage.restoreFromBackup`/IBU restore (`DiskStorage.java:1883`, the
incremental-backup / fuzzy-checkpoint restore that replays a WAL rebuilt from IBU
files). `restoreAtomicUnit` and the throw sites are shared by both. A fix to the
abort behavior changes recovery semantics for backup restore as well as
crash restart. If the planner sized "~4 files" assuming a single crash-restart
path and a single regression, the estimate may miss the backup-restore caller and
its test surface, and the fix's blast radius is wider than the track states.
**Evidence**: PSI find-usages of `AbstractStorage#restoreFrom` returns exactly two
production references — `AbstractStorage.java:5505` and `DiskStorage.java:1883`;
`restoreAtomicUnit` is referenced from `5544` plus three existing unit-test
classes. (Reference-accuracy via mcp-steroid PSI `ReferencesSearch`, not grep.)
**Proposed fix**: Add a sentence to the track `## Interfaces and Dependencies`
naming both callers (`restoreFromBeginning` and `DiskStorage`'s IBU restore at
`1883`) as in-scope behaviorally, and have decomposition decide whether the
regression must cover the backup-restore path or whether the shared
`restoreAtomicUnit` unit test is sufficient. Confirm the ~4-file footprint after
that decision.

### A4 [suggestion]
**Certificate**: Assumption test T1 (single replay entry point)
**Target**: Decision D10 — "no pool; reuse the atomic-operation WAL"
**Challenge**: The rejected-alternative-already-built check turns up adjacent
infrastructure the track does not mention. `deleteNonDurableFilesOnRecovery`
(`WOWCache.java:1042-1101`, called once at `AbstractStorage.java:4739`)
proactively deletes files whose pages were never WAL-logged before replay starts,
and `restoreAtomicUnit` already skips every record type for those file ids via
`deletedNonDurableFileIds` (`5628`, `5638`, `5653`, `5727`). This is a graceful,
no-abort path for one class of not-yet-durable file. The F55 abort is a different
class: a file that the WAL *did* record as created (so it is not in
`deletedNonDurableFileIds`) but whose physical create was lost. The track does not
say how the fix relates to this existing machinery — whether it extends the
non-durable-skip set, adds a fresh lazy-create-on-miss, or makes
`restoreFileById`-null non-fatal. Without that, the implementer risks either
duplicating the skip logic or fighting it.
**Evidence**: `AbstractStorage.java:4739`, `4736-4746` (proactive non-durable
delete + clear); `WOWCache.java:1042-1101` (the deletion); `restoreAtomicUnit`
skip guards at `5628/5638/5653/5727`.
**Proposed fix**: In `## Context and Orientation`, note the existing
`deletedNonDurableFileIds` skip machinery and state explicitly that the F55 case
is the WAL-recorded-but-physically-lost file (distinct from the never-WAL-logged
non-durable file the skip set already handles), so the implementer positions the
fix relative to it rather than re-deriving it.

### A5 [suggestion]
**Certificate**: Violation scenario V2 (blanket-restore regression escape)
**Target**: Invariant I-A1 (must still discard genuinely-incomplete units)
**Challenge**: The track correctly requires that "a genuinely incomplete unit (end
record never made durable) is still discarded," and the ordering constraint says
"the regression must distinguish the two windows so a fix that blindly restores
everything is caught." But the acceptance line is prose, not pinned to a
code-observable assertion. In `restoreFrom`, a unit whose end record never became
durable is dropped by construction: it stays in the `operationUnits` map and is
never passed to `restoreAtomicUnit` (only the `AtomicUnitEndRecord` case at
`5538-5546` invokes it). A fix that touches the throw/consult sites does not
change that drop behavior — so a careless regression could "verify discard" by
asserting on a unit that was never going to be applied anyway, which any fix
trivially passes. The test would give false confidence.
**Evidence**: `AbstractStorage.java:5533-5546` (only an end-recorded unit is
applied; an end-record-never-durable unit is never handed to `restoreAtomicUnit`);
`5602` (the abort that the fix removes for the committed case must NOT also start
restoring incomplete units).
**Proposed fix**: Make the discard assertion observable: the incomplete-unit test
should assert that the records of the never-ended unit are absent after restart
(e.g., the file/page it would have created does not exist), and pair it with the
committed-but-unapplied case in the same test class so the contrast is explicit.
Decomposition writes this as a concrete EARS/Gherkin acceptance line.

### A6 [suggestion]
**Certificate**: Assumption test T2 (regression is a restart IT)
**Target**: Scope — "~4 files ... a crash-replay regression test"
**Challenge**: The track leaves the regression's level open, which leaves the
footprint and the window-injection mechanics ambiguous. A full restart IT
(`LocalPaginatedStorageRestoreFromWALIT` shape) cannot easily inject a crash at
"exactly the durable-end-record-before-apply window" without bespoke fault
injection. A unit-level harness already exists:
`RestoreAtomicUnitNonDurableSkipTest` drives the real `restoreAtomicUnit` via
`Mockito.CALLS_REAL_METHODS` + reflection over `writeCache`/`readCache`/
`deletedNonDurableFileIds`, and `RestoreAtomicUnitPageOperationTest` does the same
for the page-operation branches including a `restoreFileById` stub. The abort path
(consult-returns-null → throw) is directly reachable at that level by stubbing
`restoreFileById` to return null for a WAL-recorded-but-missing file. A unit test
hits the exact window deterministically; an IT does not.
**Evidence**: `RestoreAtomicUnitNonDurableSkipTest.java:104-257` (real-method
harness over `restoreAtomicUnit`); `RestoreAtomicUnitPageOperationTest.java:218,240`
(existing `restoreFileById` stubbing); `LocalPaginatedStorageRestoreFromWALIT.java`
(the heavier restart-based IT shape).
**Proposed fix**: In the track or at decomposition, prefer extending the existing
`RestoreAtomicUnit*Test` unit harness for the deterministic window assertion, and
add an IT only if an end-to-end restart proof is required. Re-confirm the ~4-file
estimate against that choice (a unit-test addition may touch fewer files than a new
IT plus its fixtures).

## Evidence base

#### C1 Challenge: Decision D10 — WAL replay lazy-consult fix, no pool
- **Chosen approach**: Fix the existing atomic-operation WAL replay path so a
  committed file-creating unit caught in the durable-end-record-before-apply
  window replays cleanly; no separate deletion/reinit pool.
- **Best rejected alternative**: the deletion/reinit pool (D10's listed reject).
  The pool is correctly rejected — its only correctness benefit (rollback leaves
  files unchanged) is already free via buffered-intent-in-`commitChanges`, and its
  performance benefit would need a new WAL-logged clear-and-reinit op because
  `truncateFile` is not crash-safe. So the *decision* survives.
- **Counterargument trace**:
  1. The decision survives, but the track's *localization* of the fix does not.
     The prose says a "committed-but-unapplied file-creating unit is currently
     aborted" at a "lazy-consult replay decision point."
  2. In code, `restoreFrom` (`AbstractStorage.java:5533-5546`) applies a committed
     unit immediately on its end record and never aborts it; an incomplete unit is
     dropped silently. The only abort is `restoreFileById`-returns-null →
     `StorageException` (`5660-5664`, `5734-5739`) caught at `5602`.
  3. A reader following the prose edits the wrong site; the genuine abort path
     stays.
- **Codebase evidence**: `AbstractStorage.java:5533-5546`, `5660-5664`,
  `5734-5739`, `5602`; `WOWCache.java:2397-2414`.
- **Survival test**: WEAK — the decision holds; the rationale and prose need the
  precise code site so the immutable Decision Record and the track match the code.

#### V1 Violation scenario: the single-unit discard-later-units regression cannot reach the abort
- **Invariant claim**: a crash in the durable-end-record-before-apply window
  restores the committed unit and all later units (I-A1 crash-recovery half).
- **Violation construction**:
  1. Start state: a single atomic unit U creates a file and writes pages into it,
     end record durable, physical apply not done.
  2. Action sequence: restart → `restoreFrom` builds U's record list →
     `restoreAtomicUnit(U)` at `AbstractStorage.java:5544` replays U's records in
     order (`5624`).
  3. Intermediate state: the `FileCreatedWALRecord` branch (`5636-5648`) runs
     before U's page updates and re-adds the file, so `restoreFileById` is never
     consulted for it.
  4. Violation point: the abort at `5660`/`5734` is never reached for a single
     self-contained unit; the "discards later units" symptom requires a *later*
     unit referencing a file an *earlier* unapplied unit was to create.
  5. Observable consequence: a test built on the literal single-unit sketch passes
     vacuously without exercising the bug, giving false confidence in I-A1.
- **Feasibility**: CONSTRUCTIBLE — the true bug is inter-unit; the cross-unit
  construction (U1 creates+commits, crash drops U1's physical create, U2... follow
  in the log) reaches the abort. The single-unit construction is INFEASIBLE as a
  trigger.

#### V2 Violation scenario: a blanket-restore fix escapes the discard assertion
- **Invariant claim**: a genuinely incomplete unit (end record never durable) is
  still discarded on replay.
- **Violation construction**:
  1. Start state: unit U whose `AtomicUnitStartRecord` is logged but whose
     `AtomicUnitEndRecord` never became durable.
  2. Action sequence: restart → `restoreFrom` reads U's start + body records,
     leaving U in the `operationUnits` map; no `AtomicUnitEndRecord` arrives.
  3. Intermediate state: U is never passed to `restoreAtomicUnit` (only the
     end-record case at `5538-5546` invokes it).
  4. Violation point: a regression that "asserts discard" on this unit passes for
     *any* fix, because the drop is structural and untouched by the lazy-consult
     change. The test does not actually distinguish a correct fix from a blanket
     "restore everything" fix.
  5. Observable consequence: the protection against a fix that blindly restores the
     committed-but-unapplied case while also wrongly restoring incomplete units is
     not real.
- **Feasibility**: CONSTRUCTIBLE — pin the discard assertion to a code-observable
  effect (the would-be file/page is absent after restart) and co-locate it with the
  positive case so the two windows are genuinely contrasted.

#### T1 Assumption test: this track touches a single replay path with a ~4-file footprint
- **Claim**: "this track touches only that replay path" with a ~4-file scope.
- **Stress scenario**: a backup/IBU restore, not only a crash restart.
- **Code evidence**: PSI find-usages of `AbstractStorage#restoreFrom` →
  `AbstractStorage.java:5505` (crash restart) and `DiskStorage.java:1883` (IBU /
  fuzzy-checkpoint restore). Both flow through the shared `restoreAtomicUnit` and
  its throw sites. `deleteNonDurableFilesOnRecovery` (`WOWCache.java:1042-1101`,
  one caller at `AbstractStorage.java:4739`) is adjacent already-built
  infrastructure the track does not position the fix against.
- **Verdict**: FRAGILE — the assumption holds for "one method" but understates the
  caller surface (two production callers) and the adjacent non-durable-skip
  machinery; the ~4-file estimate should be re-confirmed after both are accounted.

#### T2 Assumption test: the regression is a restart integration test
- **Claim**: the track's "crash-replay regression test" implies a restart IT.
- **Stress scenario**: needing to hit "exactly the durable-end-record-before-apply
  window" deterministically.
- **Code evidence**: `RestoreAtomicUnitNonDurableSkipTest.java:104-257` and
  `RestoreAtomicUnitPageOperationTest.java:218,240` already drive the real
  `restoreAtomicUnit` via Mockito `CALLS_REAL_METHODS` + reflection and stub
  `restoreFileById`; the abort path is reachable deterministically there. The IT
  (`LocalPaginatedStorageRestoreFromWALIT`) cannot inject the exact window without
  bespoke fault injection.
- **Verdict**: HOLDS (as a viable cheaper alternative) — a unit-level regression on
  the existing harness is both deterministic and smaller-footprint; the IT is
  optional belt-and-suspenders.

#### S1 Scope: is the track too large or too small / should it split or merge?
- **Claim**: ~4 files, front-of-series prerequisite, shares no files with schema or
  index subsystems.
- **Code evidence**: the fix is confined to `restoreFrom`/`restoreAtomicUnit` in
  `AbstractStorage.java` plus (behaviorally) `DiskStorage`'s IBU caller and the
  existing `RestoreAtomicUnit*Test` harness; no schema/index file is touched. The
  footprint is genuinely small and the no-shared-files claim is accurate
  (find-usages shows only storage/cache symbols).
- **Verdict**: HOLDS — the track is correctly sized as a small standalone
  prerequisite and should neither split nor merge. The only sizing caveat is the
  second caller in T1, which nudges the file count, not the track boundary.
