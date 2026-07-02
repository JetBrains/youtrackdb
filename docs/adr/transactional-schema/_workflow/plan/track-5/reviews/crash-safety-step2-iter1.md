<!--
MANIFEST
dimension: crash-safety
prefix: CS
target: d2b1632652~1..d2b1632652
step: 5.2
iteration: 1
verdict: CHANGES_REQUESTED
blockers: 1
findings_total: 2
evidence_base: 4
cert_index: 2
flags: []
index:
  - id: CS1
    sev: blocker
    anchor: "#cs1-failed-drop-commit-leaves-a-surviving-committed-index-with-an-unregistered-engine"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2710
    cert: C1
    basis: "PSI: deleteIndexEngineInCommitWindow mutates indexEngines/indexEngineNameMap synchronously (lines 3250-3251); failure-path undo (lines 2710-2724) has no drop-restore arm; publishReconciledIndexes (indexes.remove) runs only on success; getRids->callIndexEngine->checkIndexId throws on null slot"
  - id: CS2
    sev: suggestion
    anchor: "#cs2-endtxcommit-failure-after-phase-2-bypasses-the-engine-undo-arms"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2739
    cert: C2
    basis: "PSI: endTxCommit runs in the else (no-error) branch of the finally; a throw from endAtomicOperation propagates uncaught, so undoReconciledIndexEngines / undoAppliedMembership never run; identical pre-existing exposure for undoReconciledCollections (Track 4)"
-->

# Crash Safety & Durability review — Track 5, Step 2 (commit-time engine lifecycle) — iter 1

Verdict CHANGES_REQUESTED, 1 blocker. The durable side of this step is sound: the
tx-created engine's files, the population `doPut` writes, and the drop's engine-file
deletes all land in the transaction's own commit atomic operation (WAL-buffered,
reverted on rollback), the engine build runs strictly before `endTxCommit` (so a crash
before the WAL flush loses everything cleanly), and the shared-map publish is deferred
past `commitChanges` so a crash after durability reloads a consistent index from the
durable link set. The create-side failed-commit cleanliness (in-memory phantom removal
plus the in-memory-profile engine-file revert) faithfully mirrors Track 4's
`undoReconciledCollections`. The one blocker is on the drop side: the drop's synchronous
in-memory engine-registry removal has no failure-path restore arm, so a failed commit
that dropped an index leaves the surviving committed index pointing at a nulled engine
slot, which throws on the next read. A second, pre-existing (Track 4-shared) exposure
around `endTxCommit` failure is recorded as a suggestion.

## Findings

### CS1 [blocker] Failed drop commit leaves a surviving committed index with an unregistered engine

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2710-2724), with the mutation at `AbstractStorage.java:3243-3251` (`deleteIndexEngineInCommitWindow`) and the missing restore acknowledged in the comment at `AbstractStorage.java:2715-2717`.

**Crash scenario**: If a single schema-carrying commit drops two indexes (for example
two indexes on one class, or indexes on two classes in one transaction) and the second
`deleteIndexEngineInCommitWindow` throws after the first has run — or, more generally, if
any step of `buildAndDropReconciledEngines`' `dropped` loop throws after an earlier drop
succeeded — then the failed commit rolls back, but the earlier drop's in-memory engine
registry removal is never restored. The committed `Index` handle survives in the shared
`indexes` map (phase-3 `publishReconciledIndexes`, which does `indexes.remove(...)`, runs
only on the success branch), yet its engine slot is `null`. Any subsequent read through
that index (`getRids` → `getRidsIgnoreTx` → `callIndexEngine(indexId)` → `checkIndexId`)
throws `InvalidIndexEngineIdException` on the null slot. The index is broken in memory
until a reopen re-parses it from the (correctly unchanged) durable state.

**Evidence**: See cert C1. Write-path trace for the drop side:
STEP 1 (phase 1, `enrollReconciledIndexRecords`): unlink the index entity from the
index-manager link set and enrol the index-entity deletion into the transaction; the
shared `indexes` map is untouched; `committed` added to `plan.dropped()`.
STEP 2 (phase 2, `buildAndDropReconciledEngines` → `deleteIndexEngineInCommitWindow`,
`AbstractStorage.java:3243-3251`): `doDeleteIndexEngine(atomicOperation, engine)`
WAL-buffers the file deletes (reverts on rollback — good), then **synchronously**
`indexEngines.set(internalIndexId, null)` and `indexEngineNameMap.remove(engine.getName())`.
These two in-memory mutations are outside the atomic operation's revert scope.
STEP 3 (a later drop, or a later step, throws): the `catch` at
`AbstractStorage.java:2680` sets `error`; `rollback` reverts the atomic operation (engine
files restored); the undo block at `AbstractStorage.java:2710-2724` calls only
`undoReconciledIndexEngines(plan.createdEngineExternalIds())` (created engines) and
`undoAppliedMembership` (membership). No arm re-registers a dropped engine.
STEP 4 (reader): `getIndexId()` on the surviving handle still returns the original
`internalId`; `indexEngines[internalId] == null`; `checkIndexId` throws.

**Recovery impact**: The durable state is correct — the drop's engine files, index
record, and link-set edit all reverted with the WAL, so a reopen (`IndexManagerEmbedded.load`
→ `load(transaction, entity)` over the durable `CONFIG_INDEXES` link set) reconstitutes
the index and reloads its engine intact. The defect is purely an in-memory divergence
that persists for the lifetime of the open storage: the in-memory registry says the
engine is gone while disk says it is present, and the surviving committed index throws on
every query until the next reopen. This is a durability/consistency contract break of the
same class the create-side undo (`undoReconciledIndexEngines`) and Track 4's
`undoReconciledCollections` exist to prevent — the step closes the create-side gap but
leaves the symmetric drop-side gap open.

**Refutation considered**:
(1) "The atomic-operation rollback restores the registry." Refuted — `doDeleteIndexEngine`
touches only WAL-buffered files; the `indexEngines.set(null)` / `indexEngineNameMap.remove`
at lines 3250-3251 are plain in-memory writes with no WAL participation and no undo hook.
(2) "The comment says no drop-restore arm is needed." The comment
(`AbstractStorage.java:2715-2717`) is correct that the *durable* drop is committed-only
(files revert, publish deferred), but it conflates that with the in-memory registry, which
`deleteIndexEngineInCommitWindow` mutated synchronously. The claim "no drop-restore arm is
needed here" is wrong for the in-memory `indexEngines`/`indexEngineNameMap`.
(3) "The scenario is unreachable." Refuted — the phase-2 `dropped` loop iterates every
dropped index; `deleteIndexEngineInCommitWindow` declares `throws InvalidIndexEngineIdException`
and calls `checkIndexId` and `makeStorageDirty`, all of which can throw, so a second drop
(or an I/O fault mid-loop) after a first drop mutated the registry is a concrete trigger.
The `endTxCommit`-after-phase-2 variant (CS2) is an additional, broader trigger.
(4) "Only the disk profile matters." Refuted — the divergence is registry-map state, not
file state, so it manifests identically on both the in-memory and disk profiles.

**Suggestion**: Capture the dropped engines in the plan (their internal id and the engine
object, as `undoReconciledCollections` captures dropped collections) and add a drop-restore
arm to the failure-path undo that re-publishes each dropped engine into `indexEngines` /
`indexEngineNameMap` (mirroring `publishIndexEngine`), so a failed drop commit restores the
in-memory registry to match the reverted durable state. Add a test for the failed
*drop*-commit path (the current `CommitTimeIndexBuildTest` covers only the failed
*create* commit via `failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId`); a
two-index-drop transaction with an injected fault after the first drop, asserting the
surviving committed index still reads its rows, would reproduce and guard the fix.

### CS2 [suggestion] `endTxCommit` failure after phase 2 bypasses the engine undo arms

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2726-2739).

**Crash scenario**: If the try body completes (phase 2 built/dropped engines with no
throw) but `endTxCommit(atomicOperation)` — i.e. `AtomicOperationsManager.endAtomicOperation`,
which persists and applies the WAL — throws, the exception propagates out of the `else`
branch of the finally uncaught. `error` was never set, so neither
`undoReconciledIndexEngines`, `undoAppliedMembership`, nor `undoReconciledCollections`
runs, and the phase-3 publish never runs. The in-memory engine registry keeps the eager
create-publications and the synchronous drop-removals from phase 2 while the WAL apply is
in an uncertain state.

**Evidence**: See cert C2. `endTxCommit` sits at `AbstractStorage.java:2739` inside the
`else` (no-error) branch; the `catch (IOException | RuntimeException | AssertionError)`
that sets `error` wraps only the try body above, not the finally. This is a pre-existing
structure: Track 4's `undoReconciledCollections` has the identical exposure (it too is
gated on `error != null`, so an `endTxCommit` failure bypasses it), and this step's engine
arms simply inherit the same shape.

**Recovery impact**: The durable outcome depends on how far `endAtomicOperation` progressed
before failing; the WAL either flushed (commit durable, in-memory publish still skipped —
same reload-on-reopen recovery as CS1's success-crash) or did not (commit not durable,
in-memory registry now diverged from disk). Either way the divergence self-corrects on the
next reopen, and the failing `endTxCommit` typically drives the storage toward error state.
Because this is pre-existing behavior shared with the collection reconciliation and out of
this step's declared scope, it is a suggestion rather than a blocker; noting it so a future
hardening pass (or the CS1 fix) can decide whether to wrap `endTxCommit` and route its
failure through the same undo arms for both collections and engines.

**Refutation considered**: Confirmed the collection arm has the same `error != null` gate,
so this is not a regression introduced by Step 2; it is an inherited exposure. Not
escalated to blocker because it predates this step and changing the `endTxCommit` failure
contract is a Track-4-owned concern.

## Evidence base

#### C1 — The drop's in-memory engine-registry removal is synchronous and has no failure-path restore

CONFIRMED as an in-memory consistency break on the failed-drop-commit path (survived the
refutation check). `deleteIndexEngineInCommitWindow` (`AbstractStorage.java` diff hunk,
resolved to lines 3243-3251 in the working tree) performs `doDeleteIndexEngine(atomicOperation, engine)`
(WAL-buffered file delete + config-entry delete, reverts on rollback) and then two plain
in-memory mutations: `indexEngines.set(internalIndexId, null)` and
`indexEngineNameMap.remove(engine.getName())` (PSI: both confirmed present at 3250-3251,
outside any atomic-operation callback). The failure-path undo block
(`AbstractStorage.java:2710-2724`) invokes only `undoReconciledIndexEngines(indexPlan.createdEngineExternalIds())`
— which, per its own body (`AbstractStorage.java:876-890`), iterates only the *created*
engine ids — and `undoAppliedMembership`. Neither restores a dropped engine's registry
entry, and there is no captured `dropped`-engine list on `ReconciledIndexPlan` analogous to
Track 4's `DroppedCollection` list. The success-only phase-3 `publishReconciledIndexes`
(`AbstractStorage.java:535-546`) is what removes a dropped index from the shared `indexes`
map, so on the failure path the committed `Index` handle survives in `indexes` with its
`indexId` unchanged (`getIndexId()` returns the field verbatim, PSI). The read path
`IndexMultiValues.getRids` → `getRidsIgnoreTx` → `AbstractStorage.callIndexEngine(indexId)`
→ `doCallIndexEngine` → `checkIndexId` (`AbstractStorage.java:4007-4012`) throws
`InvalidIndexEngineIdException` when `indexEngines.get(indexId) == null`. The trigger is
reachable: the phase-2 `dropped` loop (`buildAndDropReconciledEngines`) processes every
dropped index and `deleteIndexEngineInCommitWindow` can throw (`checkIndexId`,
`makeStorageDirty`, `doDeleteIndexEngine` I/O), so a multi-drop transaction whose later
drop faults after an earlier drop mutated the registry leaves the earlier drop's committed
index engine-less in memory.

#### C2 — `endTxCommit` runs in the no-error branch; its failure bypasses every undo arm

`endTxCommit` (`AbstractStorage.java:5707-5709`) delegates to
`atomicOperationsManager.endAtomicOperation`, the persist+apply gate. It is called at
`AbstractStorage.java:2739`, inside `else { ... }` of the outer `finally`, reached only
when `error == null`. The `catch` that assigns `error` (`AbstractStorage.java:2680-2698`)
wraps the try body (through phase 2) but not the finally, so a throw from `endTxCommit`
escapes without running `undoReconciledCollections` / `undoReconciledIndexEngines` /
`undoAppliedMembership` (all gated on `error != null` at 2700) and without running the
phase-3 publish or schema promotion. Confirmed identical to Track 4's collection handling
(the `undoReconciledCollections` call is under the same `error != null` gate), so this is
an inherited exposure, not a Step-2 regression.

#### C3 — The durable write path is WAL-correct and crash-consistent

The commit uses the transaction's own atomic operation throughout: `AbstractStorage.commit`
binds `final var atomicOperation = frontendTransaction.getAtomicOperation()`
(`AbstractStorage.java:2317`), the same object `IndexMultiValues.doPut` retrieves via
`session.getActiveTransaction().getAtomicOperation()` → `doPutV1(storage, atomicOperation, ...)`
(PSI), and the same object threaded into `buildAndDropReconciledEngines`. So the tx-created
engine's file creates (`createIndexEngineInCommitWindow` → `doAddIndexEngine` →
`engine.create(atomicOperation)`), the population `doPut` entries, and the drop's file
deletes all buffer into one WAL-reverted unit. Phase 2 runs strictly before `endTxCommit`
(the WAL flush/apply), so a crash before `endTxCommit` reverts the whole unit on recovery
(clean loss), and a crash after `endTxCommit` but before phase-3 publish leaves the durable
`CONFIG_INDEXES` link set + per-index records + engine files self-consistent, which
`IndexManagerEmbedded.load` re-parses into a correct in-memory index on reopen (PSI:
`load(transaction, entity)` reads the link set, loads each metadata record, and
`addIndexInternalNoLock` rebuilds the lookup maps). The phase-3 publish is therefore an
in-memory optimization over reload, matching the schema-promotion deferral pattern.

#### C4 — Create-side failed-commit cleanliness correctly mirrors the collection arm

`undoReconciledIndexEngines` (`AbstractStorage.java:876-890`) removes each created engine's
in-memory registration (`indexEngines.set(internalId, null)`, `indexEngineNameMap.remove`)
and calls `revertCreatedIndexEngineStructure`, which — guarded on the engine name still
being present in `configuration.indexEngines(atomicOperation)` — drops the surviving files
in a fresh atomic operation. This is a faithful mirror of `revertCreatedCollectionStructure`
/ `undoReconciledCollections`' create arm (PSI-compared): the guard makes it a no-op on the
disk profile (rollback already reverted the eager `addFile`) and the real cleanup on the
in-memory profile (which does not revert the eager cache install), and it runs best-effort
(logged-and-swallowed) so it never masks the propagating commit exception. The commit-local
`nextFreeIndexEngineId` allocator (first-null-slot) plus this undo lets a failed
create-commit free its engine id for reuse, which the
`failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId` test exercises on the default
in-memory profile. This arm is sound; CS1 is that the symmetric *drop* arm is absent.
