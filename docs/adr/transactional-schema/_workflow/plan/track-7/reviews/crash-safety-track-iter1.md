<!--
review: crash-safety / durability — CUMULATIVE Track 7 composition review (iter 1)
scope: "git diff e2605c8ba3..5808d9b11e (production + test code; docs/adr workflow files skipped)"
head: 5808d9b11e
findings:
  - {id: CS32, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3190", anchor: "### CS32 ", basis: "publishReconciledIndexes is the sole unguarded seam in the composed post-durability tail: a throw there after endTxCommit reports a durable commit as failed, leaves the shared index maps partially published and the schema unpromoted, and does NOT poison — pre-existing at baseline, gate-noted residual, never filed; composition confirms it is now the only such seam"}
  - {id: CS33, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3145-3166 (catch condition)", anchor: "### CS33 ", basis: "the in-doubt (shape-2) poison containment is conditioned on schemaContext != null: a PURE-DATA commit whose commitChanges throws mid shared-cache apply (durable + torn cache) is rethrown without poison, exactly the baseline exposure; not a regression (baseline had no catch at all) — recorded so the schema-only scope of the containment is a decision, not an accident"}
null_verdicts: [N1-composition-gap, N2-crash-sweep, N3-pin-clear, N4-undo-under-operator-freeze, N5-uncaught-error-family, N6-reopen-contracts, N7-track6-it-fixes]
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED, SLICE_VERDICTS_NOT_RELITIGATED]
-->

# Crash-safety / durability review — Track 7 CUMULATIVE composition (iter 1)

**Artifact:** the entire Track 7 diff, `git diff e2605c8ba3..5808d9b11e` on branch
`transactional-schema` (Steps 1–5, their review-fix commits, and the four Track-6 IT expectation
fixes). All file:line citations are at HEAD `5808d9b11e` unless marked "baseline".
**Method:** read-only static traces; no Maven executed. Slice-review verdict blocks
(`{crash-safety,concurrency,baseline}-step{1,3,4,5}-iter1.md`, `gate-*-iter1.md`) were read and
are **not re-litigated**; this review works strictly at the composition level — where the slices'
mechanisms meet.

---

## 0. Decision criteria

1. **No family gap:** every failure point of a schema-carry commit at HEAD must belong to exactly
   one handled family — (A) gate aborts with zero WAL/registry side effects, (B) pre-durability
   failures routed through `rollback` + the Step-1 undo machinery, (C) `endTxCommit` failures
   dispatched by `isRollbackInProgress()` into undo (rolled back ⇒ nothing durable) or poison
   (in-doubt ⇒ publication stands, storage in error). A point handled by none, or by two, is a
   defect.
2. **Crash atomicity:** every crash point across the composed track must reduce, after WAL
   replay, to "the commit's atomic unit fully applied or fully absent", with any auxiliary
   durable action being its own atomic unit.
3. **Pin/clear and pin-analogue pairing:** exactly one thread-local schema-snapshot pin and one
   clear per commit invocation on every path (including all four new checkpoint throws); exactly
   one mutex engage and at most one release per acquisition; fresh-read scopes never nest.
4. **Reopen heals:** each terminal live-process divergence the track accepts (poisoned in-doubt
   commit, gate abort, stranded-holder wedge) must reduce to a clean state on reopen, and that
   reduction must be mechanically backed (dirty flag preserved, WAL replay, fresh SharedContext).
5. **Track-6 IT fixes:** test-side file resolution only; no recovery-semantics assertion weakened.

---

## 1. Charter (1) — end-to-end failure taxonomy and the family-gap scan

The composed schema-commit timeline at HEAD, with every throw point assigned to a family. Points
marked ▸ are new in Track 7; unmarked points pre-exist at baseline and are listed to prove the
scan is exhaustive.

| # | Point (file:line) | Family | State on exit | Verdict |
|---|---|---|---|---|
| T1 ▸ | checkpoint 1 entry probe throw (`AbstractStorage.java:2545-2547`) | A | pre-pin, pre-lock, no WAL/table/registry state | clean (N1) |
| T2 | pin itself throws (`:2549` → `MetadataDefault.makeThreadLocalSchemaSnapshot:78-84` — `makeSnapshot()` precedes the `immutableCount++`) | pre-A generic | count unchanged; nested try never entered, so no clear fires | balanced (N3) |
| T3 | `getSortedIndexOperations` / `getAtomicOperation` throw (inside nested try, `:2561-2562`) | generic pre-lock | clear fires once (`:2589`); tx-rollback path ends the atomic op | clean |
| T4/T5 | `acquireSchemaWriteLock` / `acquireExclusiveLockForCommit` throw (`:3280/:3283`) | generic | locks unwind via finallys; clear once | clean |
| T6 ▸ | checkpoint 2 abort (`:3294-3296` — `exclusiveLockWithAbort` returns false, or throws: interrupt shape / BG6-guarded predicate throw) | A | nothing held on the state lock (Step-4 slice, not re-litigated); two metadata locks unwind; clear once | clean (N1) |
| T7 | `enterCommitWindow` throw | generic | write lock unlocks via finally | clean |
| T8 | `checkOpennessAndMigration` / `makeStorageDirty` throw (`:2786-2788`, before `startTxCommit`) | generic pre-apply | dirty flag possibly set (idempotent boolean, benign); no publication, no apply; tx-rollback ends the op | clean, pre-existing family |
| T9 ▸ | checkpoints 3/4 in-freezer throws (`OperationsFreezer.java:126/:151`, reached via `startTxCommit:2802` → `startToApplyOperations` first statement) | A | freezer count re-balanced before both throws (`:120` decrement precedes; loop-bottom `:158` re-increment not yet reached); no table registration (`AtomicOperationsManager.java:153-160` never reached); cp4 leaves an abandoned waiting-list node (benign stray unpark, Step-5 slice) | clean (N1) |
| T9b | `startToApplyOperations` post-freezer throw (table registration, `AtomicOperationsManager.java:153-162`) | — | freezer op-count leaked (no `endOperation`) → later freezes spin forever at `OperationsFreezer.java:229` | **pre-existing, byte-unchanged by the track (signature-only diff); hypothesis log H6** |
| T10 ▸ | `structurePublished = true` (`:2809`) through `postEngineBuildTestHook` — every throw in the apply body incl. version conflicts, reconcile, toStream/enroll fresh-read scopes, working-set apply, `commitIndexes`, engine build | B | `catch (IOException\|RuntimeException\|AssertionError)` (`:3070-3089`) sets `error`; finally runs `rollback(error, op)` (`:3091` = `endAtomicOperation(op, error)`, `:5582-5585`) then `undoSchemaCarryRegistryPublication` (`:3093`) — the Step-1 machinery with the slot-reuse-aware revert (`:3505-3560`, `revertCreatedCollectionStructure:3628` with `slotReused`) and the link-bag restore (`:3567-3583`) | handled (slice-verified BG1/CS20/CS23/CS24 gates; composition adds nothing) |
| T11 ▸ | `endTxCommit` failure, shape 1 (`:3144-3148`, `isRollbackInProgress()==true`) | C-undo | `endAtomicOperation` already rolled back internally (rollbackInProgress set only pre-`commitChanges`, and the branch+assert at `AtomicOperationsManager.java:380-393` prove `commitChanges` never ran ⇒ nothing durable); undo arms run, then `throw e` (`:3166`) | handled (N1) |
| T12 ▸ | `endTxCommit` failure, shape 2 (`:3149-3164`, rollbackInProgress false ⇒ in-doubt) | C-poison | publication stands; `setInError` (AssertionError pre-wrapped, `:3150-3156`, closing CS22); clear once; reopen heals (§4) | handled (N1) |
| T13 ▸ | non-AssertionError `Error` (OOME, LinkageError…) escaping the `endTxCommit` dispatcher (`:3145` catches only `IOException\|RuntimeException\|AssertionError`) | C-poison via outer catch | propagates to `commit()`'s `catch (Error)` (`:2592`) → `logAndPrepareForRethrow(Error):8011-8032` → `setInError` → publication stands + storage poisoned — the SAME containment end-state as shape 2, minus the explicit log line | handled (N5) |
| T14 | `cleanupSnapshotIndex` throw (`:3168-3180`) | contained | log-and-continue | clean |
| T15 | `publishReconciledIndexes` throw (`:3186-3191`) — success tail, commit durable | **none** | user sees failure, shared index maps partially published, schema unpromoted, **no poison** | **CS32** (pre-existing; the only unguarded seam) |
| T16 | promotion throw (`:3204-3238`) | contained | log + best-effort `forceSnapshot` + `setInError` (`:3237`) → reopen heals; durable commit reported successful | clean |
| T17 | outer finally `ensureThatComponentsUnlocked` (`:3243`) | — | idempotent lock release (`releaseLocks` idempotence comment, `AtomicOperationsManager.java:525-529`) | clean |

**Family-gap verdict (N1): NULL — no failure point falls between the families.** The boundary
between family A and family B is exactly the freezer entry inside `startTxCommit` (`:2802`):
everything at or before it has published nothing and applied nothing (gate aborts + generic
pre-apply failures, both resolved by the caller's tx rollback, which ends the still-inactive
atomic operation), and the very first statement after the inner `try` opens sets
`structurePublished = true` (`:2809`) — there is no instruction between the freezer entry and the
undo-arming flag other than the `try` itself. The boundary between B and C is `endTxCommit`
(`:3144`), and the dispatcher's `isRollbackInProgress()` test is exhaustive over the two shapes
(premise: `rollbackInProgress` is set at exactly two sites, `AtomicOperationsManager.java:320`
inbound-error and `:365` Hook-A persist failure, both strictly before `commitChanges:393`, and
nothing clears it — so `true ⇒ nothing durable`, `false ⇒ commitChanges was at least entered ⇒
in-doubt`). The two residuals found by the scan (T9b, T15) both pre-exist at baseline
(`git show e2605c8ba3:...AbstractStorage.java:3100` shows T15's call unguarded there too) and are
filed/logged accordingly.

**Undo-arm throw-safety (composition premise for T10/T11):** every sub-arm that can realistically
throw is individually contained — `revertCreatedCollectionStructure`'s
`catch (IOException|RuntimeException|AssertionError)` (`:3663-3674`), the link-bag restore's
catch (`:3576-3583`), `revertCreatedIndexEngineStructure`'s catch and its non-B-tree warn-skip
(`:3959+`), `restoreReconciledDroppedIndexEngines`' per-engine catch with the CS24 assert removal
(`:3912-3944`) and the log-not-assert null-data arm (`:3893-3903`). The uncontained residue is
pure in-memory list/map mutation (`collections.set`, `registerCollection`,
`undoAppliedMembership`'s set flips — `IndexManagerEmbedded.undoAppliedMembership`, plain
in-memory adds/removes), whose throw surface is effectively bug-only; a throw there would replace
the primary exception, which is the pre-existing shape of the whole finally (baseline had the
identical rollback-then-undo-in-finally structure).

---

## 2. Charter (2) — crash-point sweep at the composition level

Durable-write inventory first (the premise that makes the sweep tractable): on the entire
composed commit path, durable mutation funnels through exactly (a) the commit's single atomic
operation, written to WAL+cache only inside `commitChanges`
(`AtomicOperationsManager.java:393`) — before that call the operation is buffered intent with no
WAL/cache footprint, and a rollback skips the call entirely (branch + assert `:380-393`); (b) the
undo arms' own `executeInsideAtomicOperation` units (fresh operations, each its own WAL unit);
(c) `makeStorageDirty` (idempotent metadata boolean). The freezer, the gate counters, the mutex,
the pin count, the fresh-read scopes, and the completer are all process-memory only.

| Crash point | On-disk/WAL state at crash | Replay outcome |
|---|---|---|
| During a gate abort (any of cp1–cp4, incl. mid-`exclusiveLockWithAbort`) | at most the dirty flag (cp3/4 follow `makeStorageDirty:2788`); the commit's unit never reached `commitChanges` ⇒ absent from WAL | recovery runs (dirty), finds no unit → no-op; clean |
| During `rollback(error, op)` (`:5582` = `endAtomicOperation` with error) | unit absent from WAL (`commitChanges` skipped); table ops in-memory | clean revert by absence |
| During `undoReconciledCollections` in-memory arms | registry mutations vanish with the process | reopen rebuilds registries from durable truth |
| During `revertCreatedCollectionStructure` / `revertCreatedIndexEngineStructure` cleanup ops | each is its own atomic unit: crash mid-unit ⇒ unit incomplete ⇒ truncated (orphan file survives, bounded leak, reopen orphan pass eligible); crash post-unit ⇒ delete replays | atomic either way |
| During the link-bag restore (`:3567-3576`) | read-only operation (`startAtomicOperation` + `deactivate`, never applied) — zero WAL | nothing to replay |
| During `endTxCommit` (the in-doubt window itself) | the canonical WAL dichotomy: end record flushed ⇒ unit replays committed; not flushed ⇒ truncated ⇒ reverted | atomic — the exact property the shape-2 poison arm relies on |
| During the completer's deferred `internalClose` (`DatabaseSessionEmbedded.java:3364-3434`, `completeDeferredTeardownAfterTxClose:3980-3998`) | queries/cache/listeners/session-count/mutex — all in-memory (Step-3 slice verdict NULL, corroborated: no durable write in the body) | storage-level durable state untouched |
| During an operator freeze with commits aborting | freeze counters, retained-id deque (`operatorFreezeIds`, `AbstractStorage.java:310`), waiting list — all in-memory; the freeze body's own flush (`doSynch`) is pre-existing synch semantics | reopen starts unfrozen; aborted commits left nothing |

**Verdict (N2): NULL** — every crash point reduces to a clean atomic unit. The only durable
residue class is the bounded orphan-file leak on the in-memory-engine cleanup path, which the
Step-1 slice already accepted and which is crash-atomic per row 4.

---

## 3. Charter (3) — fresh-read scopes and pin/clear ownership as composed

**Pin/clear (N3): exactly one pin, one clear, on every path.** Grep over production sources:
the commit path's pin/clear pair is `:2549`/`:2589` and nothing else (the two other
`makeThreadLocalSchemaSnapshot` sites are the pre-existing balanced pair in
`executeReadRecord`, `DatabaseSessionEmbedded.java:2226/2403`, and the pre-existing unpaired
deserialization pin in `EntityImpl.java:4178` — different subsystem, untouched by the track;
hypothesis log H7). Path check: cp1 throws pre-pin (no clear — correct, `:2540-2547` precede
`:2549`); a pin-internal throw increments nothing (T2); cp2/cp3/cp4, every family-B failure,
both family-C arms, T13's escaping Error, and success all funnel through the single nested
finally; the old clear site inside `applyCommitOperations`' finally is comment-only
(`:3244-3247`); `rebuildThreadLocalSchemaSnapshot` is count-preserving and
`forceClearThreadLocalSchemaSnapshot` throws on a held pin (`MetadataDefault.java:106-113`), so
neither can decrement. Test pin: `FreezerGateTest.pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions`
plus per-checkpoint pin asserts in the cp1/cp2 tests (`FreezerGateTest.java:135-137, 219-220`).

**Mutex: one engage, at most one release, incl. the new checkpoint throws.** A gate abort at any
checkpoint unwinds to the user; the engage-time permit is released exactly once at tx close
(`FrontendTransactionImpl.closeInternal` finally `:1069-1075` → the atomic
`getAndSet(0)` funnel, `DatabaseSessionEmbedded.releaseMetadataWriteMutexForTx:3920-3945`), and
any racing foreign teardown's release pass no-ops via the same claim (Step-3 slice N2/F2, not
re-litigated). The checkpoints add no new release site and no new engage site.

**Fresh-read scopes: non-nesting holds at composition.** Four production scopes exist
(`SchemaShared.copyForTx:289`, toStream `:2879`, enroll `:2909`, promotion `:3218`). They are
temporally disjoint: the seed scope runs at first-schema-write time (only when
`txSchemaState == null`, so it cannot recur inside the commit); the three commit scopes are
strictly sequential in one method. The one re-entrancy hazard probed: the promotion `fromStream`
ripples into the index-manager seam (`IndexManagerEmbedded.java:238-263`) — during promotion the
session's `txSchemaState` is non-null, so `ensureTxSchemaState` short-circuits and `copyForTx`
(the only other scope opener) is unreachable; the reload path's ripples are absorbed by the
`isReloadingSchema()` guard (`:251-262`) and `reload` opens no scope. The always-on nesting throw
(`DatabaseSessionEmbedded.java:3746-3750`) backstops all of this. The `EntityImpl` page-frame
fallback (`EntityImpl.java:3781`) and both `executeReadRecord` read sites route through
`getEffectiveReadAtomicOperation` (`:3782-3790`), so no read inside a scope can silently ride the
begin-time snapshot. Scope teardown (`finally`: field null then `deactivate`, `:3768-3771`) is
crash-irrelevant (read-only op) and exception-safe (field cleared first).

**Verdict (N3): NULL.**

---

## 4. Charter (4) — reopen/recovery contract spot-checks

- **Poisoned via post-durability failure:** the mechanism chain is complete at HEAD —
  `setInError` (`:2077-2103`) sets the error ref; `doShutdown` passes `isInError()` into
  `postCloseSteps` (`:7231`), and `DiskStorage.postCloseSteps` skips `clearDirty()` exactly when
  in error (`DiskStorage.java` postCloseSteps: `if (!internalError) { ... clearDirty(); }`), so
  the next open replays the WAL. Tested end-to-end with a real DISK restart:
  `SchemaCommitReconciliationTest.poisonedEndTxCommitFailureRecoversOnReopen` (`:699-782`)
  asserts error-state cleared, dropped class gone, created class present and row-round-trip
  usable after reopen — closing CS25. The live-process half
  (`endTxCommitFailureWithoutRollbackKeepsPublicationAndPoisonsStorage`, `:636-686`) pins
  publication-stands + `checkErrorState` throw.
- **Aborted via gate:** nothing durable is created by any checkpoint (§1 T1/T6/T9), so the reopen
  contract is trivially clean; the stronger live-process contract — the same session/pool remains
  DDL-usable after an abort, no restart needed — is pinned by `assertRecycledBorrowRunsDdl` tails
  in every `FreezerGateTest` gate test (e.g. `:150, :228`).
- **Wedge-healed:** a stranded mutex holder is detected loudly rather than deadlocking
  (`MetadataWriteMutex.engage:118-127` same-session stranded-holder throw;
  `MetadataWriteMutexTest.strandedSameSessionReengageThrowsLoudly:1010`), and the release belts
  (`seedFailureReleasesPermitSoTheNextWriterIsNotStranded:578`,
  `teardownWithSkippedRollbackStillReleasesPermit:654`,
  `foreignTeardownHarvestsEngagedPermit:775`) make the strand itself near-unreachable; the true
  reopen heal is structural — the mutex lives in the `SharedContext`, which is rebuilt on storage
  reopen, so no permit state survives a restart. A stranded operator FREEZE (freezer counters)
  likewise cannot survive a restart (in-memory only).

**Verdict (N6): NULL — the composed reopen contract holds.**

---

## 5. Charter (5) — Track-6 IT expectation fixes

Commit `dc5dfe006a` touches exactly four test files (`--stat` verified: `StorageTestIT`,
`TruncateOrphansAfterRecoveryIT`, `InvalidRemovedFileIdsIT`, `StorageBackupMTRestoreIT`; zero
production files). Diff content check: every change replaces the class-name-prefix file scan
(`findFileName`/`findFileId` prefix matching) with resolution through the class's collection id
via the open session (`collectionFileName`, e.g. `StorageTestIT.java:327-352`), keeping the
loud-presence asserts ("must be present in the write cache") and leaving the extension-keyed
selectors and every recovery/backup/restore *behavior* assertion untouched — the
`TruncateOrphansAfterRecoveryIT` selector interface gains a session parameter only so the `.pcl`
selector can resolve through the schema. **Verdict (N7): NULL — test-side file resolution only,
no product recovery semantics touched.**

---

## 6. Highlighted composed null verdicts (with proofs)

### N4 — the failure-path undo can never park under an unbounded operator freeze

This was the strongest composition hazard candidate found (and refuted): the undo arms open fresh
freezer-entrant atomic operations (`executeInsideAtomicOperation` →
`startToApplyOperations` → unarmed `startOperation`) while holding all four commit locks — an
unarmed entrant parks under ANY active freeze, so an operator freeze active at undo time would
convert the failure path into the exact four-lock outage the gate exists to prevent (the gate
checkpoints protect only the commit's entry, not the undo's fresh operations).

**Refutation:** production operator freezes arm exclusively through `AbstractStorage.freeze`
(`:5793`), whose registration (`:5811-5822`) runs under `stateLock.readLock()` (`:5795`) — and
`ScalableRWLock` readers back off while the write bit is held
(`ScalableRWLock.java:339-351`), so the freeze thread blocks at the read lock for the entire
commit write window (`commitSchemaCarry` holds `stateLock.writeLock` from `:3294` through every
undo arm — the whole `applyCommitOperations` finally sits inside the window). Therefore
`operatorFreezeRequests` cannot transition 0→1 while any undo arm runs; only TRANSIENT freezes
that arm without `stateLock` (the incremental-backup WAL copy, `DiskStorage.java:358-369`) can be
active, and those are bounded by their try/finally bodies — a bounded park, identical to the
pre-Track-7 semantics of the same path. (Corollary: checkpoints 3/4 are defense-in-depth against
manager-level operator-freeze callers — today only tests — which is safety-positive redundancy,
not a gap.)

### N5 — the uncaught-Error family degenerates into the poison containment

An `Error` other than `AssertionError` thrown by `endTxCommit` bypasses the dispatcher's catch
(`:3145`) but lands in `commit()`'s `catch (Error)` (`:2592`) →
`logAndPrepareForRethrow(Error):8011` → `setInError` (OOME/LinkageError are not
`HighLevelException`). End state: publication stands + storage poisoned + dirty flag preserved —
containment-equivalent to shape 2. `AssertionError` is the one Error the dispatcher must (and
does) handle itself, because `setInError` skips raw AssertionErrors — the shape-2 arm's
pre-wrapping (`:3150-3156`) closes that under `-ea`. The gate exception itself
(`ModificationOperationProhibitedException` — verified `implements HighLevelException`,
`ModificationOperationProhibitedException.java:31-32`) is exempt from poisoning and error-logging
in `logAndPrepareForRethrow(RuntimeException):7991-7999`, keeping gate aborts loud-but-clean.

---

## 7. Findings

### CS32 [suggestion] — `publishReconciledIndexes` is the sole unguarded seam in the composed post-durability tail

- **Location:** `AbstractStorage.java:3186-3191` (success branch, after `endTxCommit`, before the
  guarded promotion).
- **Premises:** (1) at that point the commit is durable (`endTxCommit` returned); (2) the call is
  not wrapped — a throw propagates out of the finally as the commit's failure; (3) the throw
  surface is in-memory map/metadata mutation
  (`IndexManagerEmbedded.publishReconciledIndexes` — `removeClassPropertyIndexInternal`,
  `installReassociatedMetadataAtCommit`, `addIndexInternalNoLock`), small but nonzero
  (bug-triggered CME, a broken definition, an assert under `-ea`); (4) unlike its two neighbors —
  the endTxCommit dispatcher before it and the promotion catch after it (`:3226-3238`), both of
  which poison — a throw here leaves the storage **live and writable** with the shared index maps
  partially published, the schema unpromoted, and the user told the durable commit failed.
- **Counterexample:** a drop-then-recreate transaction whose phase-3 `reassociated` loop throws
  after the drop-publish removed the old handle: the shared lookup maps now miss the index while
  its records and engine are durably committed; no poison, no reopen forcing — the divergence
  persists until an unrelated restart, and a client retry of the "failed" commit re-runs a
  duplicate DDL against the half-published view.
- **Status:** pre-existing at baseline (verified: `git show e2605c8ba3` has the identical
  unguarded call at its line 3100); recorded by the Step-1 gate as a non-blocking residual
  ("consider filing for a later hardening pass", `gate-step1-fixes-iter1.md:304-308`) and never
  filed. Filed here because the composition scan proves it is now the ONLY point in the entire
  post-durability tail where a failure neither surfaces as success nor poisons. **Not a Track 7
  regression; follow-up-eligible.** Fix shape: mirror the promotion containment (catch →
  error-log → `setInError`) or fold the call inside the promotion's guarded block.
- **Alternative hypothesis checked:** "phase-3 cannot throw because all inputs were validated in
  phases 1–2" — rejected: `installReassociatedMetadataAtCommit`/`addIndexInternalNoLock` consult
  live shared maps that phases 1–2 do not freeze, and `-ea` asserts inside them are reachable.

### CS33 [suggestion] — the in-doubt poison containment is schema-commit-only; a pure-data commit's `commitChanges` throw keeps the baseline unpoisoned torn-cache exposure

- **Location:** `AbstractStorage.java:3145-3166` — the dispatcher's containment (both arms) is
  gated on `schemaContext != null && structurePublished`; for a pure-data commit the catch is a
  bare `throw e` (`:3166`), byte-equivalent to the baseline's absent catch.
- **Premises:** (1) shape 2 for a data commit — `commitChanges` throws mid shared-cache apply —
  leaves durable WAL truth with a partially applied shared cache (the track's own shape-2 comment
  documents this sub-shape, `:3113-3117`); (2) the exception is a RuntimeException family in the
  common case, and `logAndPrepareForRethrow(RuntimeException):7991` does **not** poison; (3) the
  storage therefore stays live, serving reads from the torn cache until something else forces a
  reopen.
- **Counterexample:** a multi-page B-tree split whose cache apply throws between page installs:
  WAL-durable, cache-torn, no error state — subsequent index reads on the live storage can see a
  structurally inconsistent tree.
- **Status:** **not a regression** — Track 7 strictly improved the schema half and left the data
  half exactly as the baseline had it; the track's scope is schema commits. Filed so the
  schema-only scope of the shape-2 poison is a recorded decision rather than an accident of the
  gating condition; a follow-up may extend the poison arm (drop the `schemaContext != null`
  condition for the no-rollback shape) at low cost.
- **Alternative hypothesis checked:** "commitChanges failures always escalate as Errors and poison
  via the outer catch" — rejected: IO-rooted cache failures surface as RuntimeExceptions through
  the component wrappers, and the RuntimeException overload never poisons.

---

## 8. Hypothesis log

| # | Hypothesis | Verdict |
|---|---|---|
| H1 | A failure point exists between the gate-abort family and the Step-1 undo machinery | **Refuted** — the A/B boundary is the freezer entry in `startTxCommit:2802`; `structurePublished = true` is the first statement inside the inner try (§1, N1) |
| H2 | The failure-path undo can park under an unbounded operator freeze with four locks held | **Refuted** — `freeze()` registers under `stateLock.readLock()`, unobtainable during the commit's write window (§6 N4); transient parks bounded |
| H3 | A non-AssertionError Error escaping the endTxCommit dispatcher leaves publication standing on a live, unpoisoned storage | **Refuted** — `commit()`'s `catch (Error)` → `logAndPrepareForRethrow(Error)` → `setInError` (§6 N5) |
| H4 | Shape 1 (`rollbackInProgress`) can coexist with durable bytes, making the undo de-register a durable commit | **Refuted** — both `rollbackInProgress()` sites precede `commitChanges`, which is branch-skipped and assert-pinned on rollback (`AtomicOperationsManager.java:380-393`) |
| H5 | A nested fresh-read scope is reachable (promotion `fromStream` → seam → `copyForTx`) | **Refuted** — `txSchemaState != null` during promotion short-circuits the seed; reload guard covers the reload ripples; always-on nesting throw backstops (§3) |
| H6 | `startToApplyOperations` throwing after the freezer entry leaks the op count and wedges later freezes | **Confirmed reachable in principle, pre-existing and byte-unchanged by the track** (signature-only diff); in-memory throw surface only (idGen/table); logged for orchestrator awareness, no Track 7 finding |
| H7 | The unpaired `EntityImpl.java:4178` pin breaks the single-owner pairing | **Out of the pairing's scope** — pre-existing deserialization-path pin, untouched by the diff; commit-path ownership is unaffected (§3) |
| H8 | `rollback(error, op)` itself throwing skips the undo and strands phantom registrations un-poisoned | **Pre-existing shape** (baseline identical); non-retry-family primaries already poisoned by `moveToErrorStateIfNeeded` inside `endAtomicOperation:317`; retry-family + rollback-throw residual is in-memory-only and bug-triggered — no new exposure from the track |
| H9 | A `freeze()` that throws after registration (index-freeze loop / `doSynch`) strands the operator arm and permanently gates schema commits | **Pre-existing leak shape** (baseline: same registration-then-throw with no unfreeze); Track 7 changes the symptom from silent data-write hang to ALSO loud schema aborts — strictly more diagnosable, not worse; deque LIFO under the strand mirrors the accepted gate-step5 residual |
| H10 | The endTxCommit-catch undo runs outside the write-lock window (locks already released by `endAtomicOperation`) | **Refuted** — `endAtomicOperation` releases only the *component* locks; `stateLock.writeLock`/schema/index-manager locks are held by `commitSchemaCarry`'s enclosing finallys until after the undo completes |

## Null-verdict statement

On the charter's five probes: (1) failure-family composition — **no gap** (two pre-existing
residuals filed as suggestions CS32/CS33, neither introduced nor worsened by the track);
(2) crash sweep — **no defect**, every crash point reduces to a clean atomic unit;
(3) fresh-read scopes + pin/clear ownership — **no defect**, one pin/one clear and one
engage/one release on every path including all four checkpoint throws; (4) reopen contracts —
**no defect**, poisoned/aborted/wedge-healed all mechanically backed and test-pinned;
(5) Track-6 IT fixes — **test-side only, confirmed**. No blocker or should-fix was licensed by
the evidence at the composition level.
