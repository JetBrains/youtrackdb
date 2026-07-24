<!--MANIFEST
dimension: crash-safety
track: 3
iteration: 1
range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
evidence_base: { certs: 4 }
cert_index: [C1, C2, C3, C4]
flags: ["no blocker/should-fix in dimension", "scope: WAL/page-cache/disk durability deferred to Track 4 per dispatch"]
index:
  - id: CS1
    sev: informational
    anchor: "CS1"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java:createCollections (via doCreateClass) + AbstractStorage.addCollection"
    cert: C1
    basis: "PSI: createCollections -> session.addCollection -> AbstractStorage.addCollection runs calculateInsideAtomicOperation (standalone WAL atomic op, not the user tx). txLocal flag does NOT gate createClass collection allocation; only saveInternal/dropClass/dropClassInternal check it. De-guard newly makes in-tx createClass reachable, so a rolled-back in-tx create strands a durable collection. Documented Track-4-owned (D2/D10); dispatch scopes durability to Track 4."
  - id: CS2
    sev: informational
    anchor: "CS2"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:ensureTxSchemaState (engage->seed window) + FrontendTransactionImpl.close"
    cert: C2
    basis: "PSI: close() reached from commit success (commitInternal) and rollback (rollbackInternal at txStartCounter==0) and commitImpl catch-rollback; metadataMutexEngaged is a session field (survives clear() userData wipe); seed-failure catch releases before rethrow; releaseFor is session-keyed idempotent. Mutex permit is not stranded on any normal-or-failed-commit teardown path this track owns."
  - id: CS3
    sev: informational
    anchor: "CS3"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:addCollectionToIndex/removeCollectionFromIndex/createIndex/dropIndex + IndexAbstract.addCollection"
    cert: C3
    basis: "PSI find-usages: Index.addCollection (eager shared collectionsToIndex apply) has exactly ONE production caller (IndexManagerEmbedded.addCollectionToIndex, de-guarded). Every membership-ripple producer (SchemaEmbedded.createClassInternal, SchemaClassEmbedded.addCollectionIdToIndexes, SchemaClassImpl.removeCollectionFromIndexes) routes through the de-guarded seam. I-A7 shared-state leak is fully covered; no uncovered ripple path reaches the shared apply inside a tx."
  - id: CS4
    sev: informational
    anchor: "CS4"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:copyForTx + fromStream"
    cert: C4
    basis: "PSI + test: copyForTx loads the committed root read-only and re-parses via fromStream; fromStream rebuilds the class graph in-copy and never calls addCollectionToIndex, so the seed touches no shared index state. CopyForTxTest pins getEntryCount()==0 after seed and same-RID-object before/after. Seed preserves per-class RIDs without dirtying committed records."
-->

## Findings

### CS1 [informational] In-tx `createClass` still allocates a durable collection in its own WAL atomic operation (Track-4-owned intermediate state)

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java` (`createCollections`, reached from `doCreateClass`) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (`addCollection`)
- **Crash scenario**: A transaction does `begin(); schema.createClass("X"); rollback()` (or crashes before commit). `createClass` against the tx-local copy reaches `createCollections` -> `session.addCollection(name)` -> `AbstractStorage.addCollection`, which runs `atomicOperationsManager.calculateInsideAtomicOperation(...)`. That is a standalone atomic operation that commits to the WAL independently of the user transaction. After the rollback (or after crash + WAL replay), the collection file `x_<n>` exists on disk but no class references it — a stray, recovery-visible collection.
- **Evidence**: PSI trace — `doCreateClass`/`doCreateClass(retry)` call `createCollections(session, className, ...)`; `createCollections` calls `session.addCollection(collectionName)` per collection; `DatabaseSessionEmbedded.addCollection` delegates to `storage.addCollection`, whose body is `makeStorageDirty(); return atomicOperationsManager.calculateInsideAtomicOperation(op -> doAddCollection(...))`. The `txLocal` flag added in this track gates only `SchemaShared.saveInternal`, `SchemaEmbedded.dropClass`, and `dropClassInternal`; it does **not** gate the create-side collection allocation. Before this track the throw-guards forced DDL to be top-level, so the in-tx `createClass` path was unreachable; the de-guard newly makes it reachable.
- **Recovery impact**: WAL replay reconstructs the orphaned collection exactly as written (the atomic op is complete and durable), so recovery is internally consistent — there is no torn page or half-written structure. The only artifact is a logically-orphaned collection that the schema no longer points at. No data loss; a leaked file/id.
- **Refutation considered**: This is explicitly the CS1 item the track itself records (track-3.md, Step 3 episode "Critical context" and `## Idempotence and Recovery` -> "Eager-allocation intermediate state"), scoped to Track 4 (D2 provisional ids: a new collection carries a sentinel negative id resolved only at commit; D10: reconciliation applies structure inside the commit's own atomic operation so a rollback/crash leaves files unchanged). The dispatch states this track "does NOT touch WAL, page cache, or disk durability — those are Track 4." The Track-3 rollback tests deliberately assert only metadata-level cleanup (shared `Index.collectionsToIndex` untouched) and do not assert file-level collection cleanup. So this is a known, documented, deliberately-deferred intermediate state, not a Track-3 defect. Raised here only so the track-level reviewer can confirm the cross-track hand-off (Track 4 / I-A1 / I-A2 / D2 / D10) actually closes it; if Track 4 does not invert this allocation, this becomes a real durability leak.
- **Suggestion**: No Track-3 change. Track-4 reviewers must verify the commit-time reconciliation (D2 provisional ids + D10 structural revertibility) replaces this eager `createCollections` allocation with a deferred, commit-scoped allocation, and that the I-A1/I-A2 crash-recovery tests cover the rolled-back-in-tx-create-leaves-no-stray-collection case.

### CS2 [informational] Mutex-permit release is reached on every normal and failed-commit teardown path this track owns

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (`ensureTxSchemaState`, `releaseMetadataWriteMutexForTx`) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (`close`)
- **Crash scenario**: Considered failure timings: (a) seed throws after the mutex engages but before the custom-data marker is written; (b) the outermost `commit()` body throws after a schema write engaged the mutex; (c) a normal rollback after a schema write.
- **Evidence**: PSI trace of the teardown graph —
  - (a) `ensureTxSchemaState` engages the permit, then in `try { ... copyForTx ... }` catches `RuntimeException | Error`, calls `releaseMetadataWriteMutexForTx()` (clears `metadataMutexEngaged` + `releaseFor(this)`), and rethrows. The custom-data marker is not yet set, so a later same-tx retry would re-enter and engage cleanly; the permit is not stranded.
  - (b) `DatabaseSessionEmbedded.commitImpl` outermost path: on `RuntimeException` from `commitInternal()` it calls `currentTx.rollbackInternal()` then rethrows; `rollbackInternal` reaches `close()` when `txStartCounter == 0`; `close()` ends with `session.releaseMetadataWriteMutexForTx()`.
  - (c) `rollback()` -> `rollbackInternal()` -> `close()` -> release.
  - The release marker `metadataMutexEngaged` is a **`volatile` session field**, not transaction custom data, so `FrontendTransactionImpl.clear()` (which wipes `recordOperations`/`userData` and runs *before* the release line in `close()`) does not erase it. `MetadataWriteMutex.releaseFor` is session-keyed and clears the holder before `permit.release()`, so the normal release and Track 7's future abnormal compare-and-clear cannot double-release.
- **Recovery impact**: None at the storage layer — the mutex is an in-memory, storage-scoped serialization primitive that is not re-created across re-init, holds no durable state, and has no WAL footprint. A stranded permit would be a liveness bug (a wedged schema-writer slot), not a durability/recovery bug, and the cross-thread/pool-shutdown stranding case is explicitly Track 7's `I-handshake-1`.
- **Refutation considered**: `releaseMetadataWriteMutexForTx` does a non-atomic check-then-act on the volatile marker, but both release sites for one tx run sequentially on the owning thread within a single tx lifecycle (the seed-failure catch rethrows before the body sets the marker; `close()` runs once at base nesting). The permit-level `releaseFor` session-key check is the real idempotency guard. Cross-thread reaping is out of Track-3 scope (YTDB-1114 / Track 7). Confirmed safe for the scope this track ships.
- **Suggestion**: None for Track 3. Track 7 reviewers: the `(session, ordinal)` widening must keep `releaseFor` session(+ordinal)-keyed so it never double-releases against this normal `close()` release.

### CS3 [informational] The I-A7 shared-state leak is fully closed — `Index.addCollection` has a single de-guarded production caller

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`addCollectionToIndex`, `removeCollectionFromIndex`, `createIndex`, `dropIndex`) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java` (`addCollection`)
- **Crash scenario / control-flow concern**: The dispatch's central I-A7 question — can a de-guarded schema/index mutation inside a tx still reach the eager shared `Index.collectionsToIndex` apply through a ripple path the de-guard did not cover, leaving shared index membership mutated (visible to other sessions, unreverted on rollback)?
- **Evidence**: PSI find-usages — `Index.addCollection(...)` (the eager shared apply that mutates `collectionsToIndex` under the exclusive lock) has **exactly one** production caller: `IndexManagerEmbedded.addCollectionToIndex`, which is de-guarded (early `return` after `recordMembershipChangeIntoTxLocalView` when a user tx is active). Every membership-ripple producer routes through the de-guarded `addCollectionToIndex` / `removeCollectionFromIndex` seam: `SchemaEmbedded.createClassInternal` (the superclass polymorphic ripple), `SchemaClassEmbedded.addCollectionIdToIndexes`, and `SchemaClassImpl.removeCollectionFromIndexes`. No production path calls `IndexAbstract.addCollection` directly. The `SchemaDeguardTest.membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback` test asserts the shared index membership is byte-for-byte equal before, during, and after a rolled-back in-tx subclass create.
- **Recovery impact**: N/A — the shared `Index.collectionsToIndex` is in-memory shared state, not durable; the concern is isolation/rollback consistency, which holds.
- **Refutation considered**: Checked whether `createIndex`/`dropIndex` de-guards could mutate the shared registry mid-tx — they call `ensureTxSchemaState` + `markClassChanged` and return a deferred/definition-only handle (`markDeferred`, `indexId = -1`, absent from the shared `indexes` map); `IndexMultiValues.size`/`IndexOneValue.size` short-circuit to 0 when `indexId < 0`, so the public SQL `CREATE INDEX` size-probe no longer NPEs. The null-owning-class branch throws a loud `IndexException` rather than falling through to the eager shared apply. Confirmed: no uncovered path mutates shared index state inside a tx.
- **Suggestion**: None. The de-guard is reference-complete for the membership-ripple surface.

### CS4 [informational] `copyForTx` seed preserves per-class RIDs without dirtying committed records or touching shared index state

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (`copyForTx`, `fromStream`)
- **Crash scenario / control-flow concern**: The dispatch's seed concern — does the tx-local `copyForTx` re-parse (`session.load(identity)` + `fromStream`) preserve per-class record RIDs without dirtying committed records, and does it touch shared index state during seeding?
- **Evidence**: PSI + tests — `copyForTx` loads the committed root **read-only** (`session.load(identity)`, no `toStream`), value-copies the root identity (`this.identity.copy()`), sets `copy.txLocal = true` before re-parsing, and calls `copy.fromStream(...)`. `fromStream` rebuilds the class graph from the `"classes"` link set (per-class records), rebinding each class's persistent RID and recomputing inheritance/`polymorphicCollectionIds` in-copy; it does not call `addCollectionToIndex` or any shared-index mutator. `CopyForTxTest` pins: `getEntryCount() == 0` after the seed (no committed record enrolled as dirty), the same RID **object** on a committed class before/after the seed (no rebind), per-class RID equality through the round trip, and `owner == copy` for the copied class with the committed class still `owner == committed`.
- **Recovery impact**: None — the seed is read-only against committed durable state and rides the user transaction's read path; it writes nothing durable.
- **Refutation considered**: Verified the design's literal `committed.toStream(session)` seed (which dirties committed records and can rebind a null/non-persistent class RID) was correctly replaced by the read-only re-parse (Step 1 execution-time decision, design.md frozen, Phase-4 reconciliation noted). The seed's correctness rests on the documented lock invariant: every committed schema change persists the root synchronously under the same `SchemaShared.lock` write lock `copyForTx` holds, so the persisted root equals live committed state under the lock. Confirmed sound for Track-3 scope.
- **Suggestion**: None.

## Evidence base

The four claims below carry the Phase-5 refutation reasoning. Per the YTDB-1069 roster rendering, a claim whose verdict survived as a reportable issue compresses to one line; a refuted-as-unsafe / scoped-out claim is rendered in full. None of the four survived as a Track-3 *defect* (all four resolved to informational — either deliberately deferred or confirmed-safe), so each carries its full refutation trail.

#### C1 — In-tx createClass eager collection allocation (CS1)
- **Claim**: A rolled-back or crashed-before-commit in-tx `createClass` strands a durable collection because `createCollections` -> `AbstractStorage.addCollection` runs a standalone WAL atomic operation not gated by `txLocal`.
- **Refutation trail**:
  - Could a higher-level mechanism revert it on rollback? Checked the `txLocal` gating — it covers `saveInternal`/`dropClass`/`dropClassInternal` only, NOT the create-side allocation. PSI: `doCreateClass` -> `createCollections` -> `session.addCollection` -> `atomicOperationsManager.calculateInsideAtomicOperation`. Not reverted by user-tx rollback. -> allocation IS eager and durable.
  - Could it only execute for the in-memory engine? Checked — `AbstractStorage.addCollection` is the disk path; applies to `EngineLocalPaginated`. -> real on disk.
  - Is it a Track-3 regression or a scoped intermediate state? Checked track-3.md `## Idempotence and Recovery` + Step-3 episode + the dispatch scope note + plan D2/D10/I-A1/I-A2. -> explicitly Track-4-owned; Track-3 tests assert metadata-level cleanup only by design; dispatch scopes durability to Track 4.
  - **Verdict**: SCOPED OUT of Track 3 (informational). Real durability concern only if Track 4 fails to invert the allocation — flagged for the Track-4 cross-track hand-off.

#### C2 — Mutex release reached on all teardown paths (CS2)
- **Claim**: The metadata-write mutex permit could be stranded if a schema commit/seed fails partway.
- **Refutation trail**:
  - Seed-failure window (engage done, marker not yet set)? Checked `ensureTxSchemaState` — `catch (RuntimeException | Error)` calls `releaseMetadataWriteMutexForTx()` then rethrows; permit released, marker cleared. -> not stranded.
  - Throwing outermost commit? Checked `DatabaseSessionEmbedded.commitImpl` — catch path calls `rollbackInternal()` -> `close()` (at `txStartCounter == 0`) -> `releaseMetadataWriteMutexForTx()`. -> released.
  - Marker erased by `clear()`? Checked — marker is a `volatile` session field, not tx custom data; `clear()` wipes `recordOperations`/`userData` only, and runs *before* the release line in `close()`. -> survives.
  - Double-release vs Track 7? Checked `releaseFor` — session-keyed, clears holder before `permit.release()`. -> idempotent.
  - Cross-thread/pool-shutdown stranding? Out of Track-3 scope (YTDB-1114 / Track 7 / I-handshake-1).
  - **Verdict**: CONFIRMED SAFE for Track-3 scope (informational; in-memory primitive, no durability footprint).

#### C3 — I-A7 shared-state leak coverage (CS3)
- **Claim**: A de-guarded in-tx schema/index mutation might reach the eager shared `Index.collectionsToIndex` apply through an uncovered ripple path, mutating shared membership unreverted on rollback.
- **Refutation trail**:
  - Enumerate every caller of the eager apply. PSI find-usages on `Index.addCollection` and `IndexAbstract.addCollection`: exactly ONE production caller — `IndexManagerEmbedded.addCollectionToIndex` (de-guarded). No direct `IndexAbstract.addCollection` production call.
  - Enumerate every ripple producer. PSI: `SchemaEmbedded.createClassInternal`, `SchemaClassEmbedded.addCollectionIdToIndexes`, `SchemaClassImpl.removeCollectionFromIndexes` — all route through the de-guarded `addCollectionToIndex`/`removeCollectionFromIndex` seam keyed on `getTransactionInternal().isActive()`.
  - Could `createIndex`/`dropIndex` mutate the shared registry mid-tx? Checked — they record into tx-local + return a `markDeferred` handle (`indexId = -1`, unregistered); null-class branch throws loud `IndexException` instead of falling through to the eager apply.
  - **Verdict**: REFUTED as a leak — coverage is reference-complete (PSI-backed). Confirmed safe (informational).

#### C4 — copyForTx seed side effects (CS4)
- **Claim**: The tx-local seed might dirty committed records, rebind committed class RIDs, or touch shared index state.
- **Refutation trail**:
  - Does the seed serialize through `toStream` (the dirtying writer)? Checked `copyForTx` — no; it loads the root read-only and re-parses via `fromStream`. -> no dirty enrol (test: `getEntryCount() == 0`).
  - Does it rebind committed class RIDs? Checked — `fromStream` rebuilds into the copy; committed class keeps the same RID object (test: `assertSame` on RID before/after). -> no rebind.
  - Does `fromStream` reach `addCollectionToIndex` (shared index) during seeding? Read `fromStream` — rebuilds the in-copy class graph directly; no membership-ripple call to the shared index manager. -> seed is shared-index-inert.
  - Is the read-equals-live-committed assumption sound? Checked the documented lock invariant — committed schema changes persist the root synchronously under the same `SchemaShared.lock` write lock `copyForTx` holds. -> sound.
  - **Verdict**: REFUTED as unsafe — confirmed safe (informational). Track-2 cross-track RID-preservation contract holds.
