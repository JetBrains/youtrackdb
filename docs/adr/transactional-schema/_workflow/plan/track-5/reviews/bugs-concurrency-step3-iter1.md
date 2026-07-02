<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: BC1, sev: should-fix, loc: SchemaShared.java:207, anchor: "### BC1 ", cert: C1, basis: "tx-local snapshot version counter is not disjoint from the committed counter; a numeric collision defeats EntityImpl re-resolution and silently skips a same-tx constraint (I-P5)"}
  - {id: BC2, sev: suggestion, loc: SchemaProxedResource.java:122, anchor: "### BC2 ", cert: C2, basis: "resolveForWrite now force-clears the pinned snapshot on every class/property DDL, widening the deferred Step-1 BC2 pin-leak trigger from index DDL to all schema DDL"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### BC1 [should-fix] Tx-local snapshot version can collide with the committed version, silently defeating `EntityImpl` re-resolution (I-P5)

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 207, `copyForTx`; version field line 132) — failure observed at `EntityImpl.java:4207`.

**Issue.** D21 makes the in-transaction immutable snapshot's version come from the tx-local `SchemaShared` copy (`SchemaProxy.makeSnapshot` line 100 builds from `txState.getTxLocalSchema().makeUncachedSnapshot`), whereas outside a transaction it comes from the committed instance. These are two independent `version` counters. `copyForTx` (line 207) builds the tx-local copy as a fresh instance (version 0) and re-parses it with `fromStream`, which bumps the version to 1; it does **not** seed the copy's version from the committed instance. So the tx-local version space starts at 1 and walks up one per mid-tx DDL, while the committed version space is a separate monotonic counter. `EntityImpl.getImmutableSchemaClass` re-resolves its cached `immutableClazz` only when `immutableSchemaVersion != immutableSchema.getVersion()` (line 4207) — a single cached int compared across both spaces. When the two spaces produce the same number, the `!=` check is false and the entity keeps a stale class, so a same-tx schema change is silently not enforced — exactly the I-P5 gap D21 exists to close.

**Evidence.** Trace, single session thread T:
- T loads entity `e` of committed class `C` and validates it with no tx active → `getImmutableSchemaClass` caches `immutableSchemaVersion = V` where `V` is the committed snapshot version (`EntityImpl.java:4204`). `immutableClazz` is reset only on a class-name change (`EntityImpl.java:3834/3880`), never at a tx boundary, so it survives `begin()`.
- T `begin()`s and issues DDL that seeds and mutates the tx-local copy. The copy started at version 1 (`copyForTx`, line 207); each routed write bumps it (`releaseSchemaWriteLock` line 695 / `fromStream` line 947). After `V-1` bumps the tx-local version equals `V`. One of those DDLs adds a constraint to `C` (e.g. `setStrictMode(true)` or a stricter property type).
- T validates `e` again. The tx-aware snapshot version is `V` (tx-local space); `e`'s cached `immutableSchemaVersion` is `V` (committed space). `V == V` → line 4207 does not re-resolve → `e` keeps the pre-constraint committed `C` → the same-tx constraint is skipped. Silent I-P5 violation.

A more reachable variant needs no coincidence with a large committed number: two back-to-back schema transactions in one session both start their tx-local version at 1 and stay in the low single digits, so an entity resolved in Tx1 at tx-local version `k` and re-resolved in Tx2 at tx-local version `k` (both txs having issued the same DDL count before the resolution, and no committed-space read in between) collides on `k` and serves Tx1's stale class inside Tx2.

**Refutation considered.** The commit-path re-resolution at `AbstractStorage.java:2625` is *not* affected — it advances the same tx-local counter by exactly 1 (`version++` at `SchemaShared.java:573`), so the entity's cached version and the rebuilt version always differ there (see C4). The bug is confined to mid-tx validation/serialization of an entity whose cached version came from a different version space (committed, or a prior tx-local generation). The existing D21 tests never exercise it: every test creates its entity with `newEntity` *inside* the schema tx, so the first resolution is already in tx-local space and subsequent resolutions advance by 1 within the same space — the cross-space collision path is untested.

**Suggestion.** Make the tx-local version space disjoint from the committed space. The minimal fix is to seed the copy's version above the committed value in `copyForTx` (e.g. set `copy.version = this.version` before/after the `fromStream` bump, so the tx-local generation starts at `committedVersion + 1` and only grows). Because a committed schema change bumps the committed version at promotion, each successive schema tx then seeds from a strictly higher base and no two version spaces overlap. Alternatively, key the `EntityImpl` cache on a process-wide monotonic snapshot-generation token rather than a per-instance counter. Add a regression test that resolves an entity's class with no tx active, then begins a schema tx that adds a constraint to that class and re-validates the same entity, arranged so the tx-local version reaches the entity's cached committed version.

### BC2 [suggestion] `resolveForWrite` now force-clears the pinned snapshot on every class/property DDL, widening the deferred pin-leak exposure

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java` (line 122).

**Issue.** `resolveForWrite` now calls `session.forceRebuildTxSchemaSnapshot()` on every routed schema write. That method calls `MetadataDefault.forceClearThreadLocalSchemaSnapshot()`, which throws `IllegalStateException` when a snapshot pin is held (`immutableCount != 0`, `MetadataDefault.java:99`). Before this step the force-clear fired only for index DDL (`IndexManagerEmbedded`); now it also fires for every class/property DDL. The Step-1 review already recorded a real, deferred pin leak (`EntityImpl.getGlobalPropertyById`'s reload-fallback pins the snapshot with no paired clear — Surprises log "BC2"). This step widens that leak's blast radius: a class or property DDL issued after such a leaked pin now trips the zero-pin assertion and aborts the DDL with a loud `IllegalStateException`, where previously only an index DDL would.

**Evidence.** `resolveForWrite` (line 108-124) is the choke point for all class/property proxy writes; line 122 unconditionally invokes `forceRebuildTxSchemaSnapshot`. `forceClearThreadLocalSchemaSnapshot` (`MetadataDefault.java:95-103`) throws unless `immutableCount == 0`. The comment at line 119-121 asserts "no thread-local snapshot pin is held" for in-tx user DDL, which is the same assumption the deferred Step-1 BC2 leak already violates for the index path.

**Refutation considered.** The root cause (the leaked pin in `getGlobalPropertyById`) is pre-existing and already tracked for follow-up; this step introduces no new leak, only a broader trigger surface. The tests pass because they exercise DDL through the Java API with no leaked pin outstanding. Ranked suggestion rather than should-fix because it depends on the pre-existing, separately-tracked leak precondition.

**Suggestion.** When the deferred `getGlobalPropertyById` pin leak is fixed, confirm no schema-DDL path can run under a held snapshot pin; until then, note in the track's Surprises log that the Step-1 BC2 exposure now covers all schema DDL, not only index DDL, so the follow-up's urgency reflects the wider surface.

## Evidence base

#### C1 Version-space collision (BC1) — CONFIRMED
Confirmed: `copyForTx` (`SchemaShared.java:207`) seeds the tx-local copy's version at 1 (fresh instance + `fromStream` bump, line 947), not from the committed counter; D21's `SchemaProxy.makeSnapshot` (line 100) sources the in-tx snapshot version from that independent counter; `EntityImpl.java:4207` compares the single cached `immutableSchemaVersion` across both spaces with `!=`, so an equal-number collision serves a stale class and skips a same-tx constraint.

#### C2 Widened pin-clear trigger (BC2) — CONFIRMED
Confirmed: `SchemaProxedResource.resolveForWrite` line 122 fires `forceRebuildTxSchemaSnapshot` → `forceClearThreadLocalSchemaSnapshot` (throws on a held pin) for every class/property DDL, extending the deferred Step-1 `getGlobalPropertyById` pin-leak exposure from index DDL to all schema DDL.

#### C3 Commit-path snapshot rebuild deadlock at `AbstractStorage.java:2625` — REFUTED
Hypothesis: `rebuildThreadLocalSchemaSnapshot()` at line 2625 runs while the commit holds `committedSchema.writeLock` (2898), `indexManager.acquireExclusiveLockForCommit` (2900), and `stateLock.writeLock` (2902); building an `ImmutableSchema` re-acquires locks and could self-deadlock on the non-reentrant `stateLock`.
Checked: (a) the rebuild builds from the **tx-local** copy (`SchemaProxy.makeSnapshot` line 100), whose own lock is free — its write lock was released at `AbstractStorage.java:2580` before line 2625, so `makeUncachedSnapshot`'s `acquireSchemaReadLock` (`SchemaShared.java:361`) succeeds; the held `committedSchema` write lock is a different instance's lock. (b) `ImmutableSchema` reads the shared index manager (`getIndexes`, no lock, `IndexManagerAbstract.java:179-181`) and the per-class index list via the routing seam; the index-manager lock is a `ReentrantReadWriteLock` (`IndexManagerEmbedded.java:69`), so acquiring its read lock while the commit thread holds its write lock is a legal downgrade, not a deadlock. (c) any record read inside the build is covered by the open commit window (`enterCommitWindow` at 2908) which skips the `stateLock` read lock. No deadlock.

#### C4 `rebuildThreadLocalSchemaSnapshot` returns a stale (provisional) snapshot at commit — REFUTED
Hypothesis: the pinned snapshot rebuilt at line 2625 could still resolve provisional collection ids into the working set.
Checked: line 2624 calls `invalidateOverlaySnapshot()` before `rebuildThreadLocalSchemaSnapshot()`, so `makeSnapshot` misses the memo and rebuilds from the tx-local copy after `resolveProvisionalCollectionIds` (line 2543-2545) has patched every class to its real id and bumped the tx-local version (`SchemaShared.java:573`, `version++`). `computeCommitWorkingSet` (line 2630) then reads the rebuilt pin and re-resolves `getCollectionForNewInstance` to the real id; the entity's cached version (pre-resolution) differs from the post-`version++` snapshot version by ≥1 in the same counter, so `EntityImpl.java:4207` always re-resolves here. Working-set resolution is correct.

#### C5 `rebuildThreadLocalSchemaSnapshot` throws `IllegalStateException` (no pin) on the commit path — REFUTED
Hypothesis: `rebuildThreadLocalSchemaSnapshot` (`MetadataDefault.java:117`) throws when `immutableCount == 0`; a schema-carry commit reaching line 2625 without a pin would fail the commit.
Checked: `commit` pins unconditionally at `AbstractStorage.java:2339` (`makeThreadLocalSchemaSnapshot`) for both branches, before `commitSchemaCarry`, and the paired `clearThreadLocalSchemaSnapshot` runs only in `applyCommitOperations`'s finally (line 2865), after line 2625. So `immutableCount >= 1` throughout, and the `!getResolvedCollectionIds().isEmpty()` guard means the call fires only when provisional ids existed. The pin is always held at 2625.

#### C6 Non-atomic `version++` on the volatile field races (SchemaShared.java:573) — REFUTED
Hypothesis: `version++` on a `volatile int` (line 573) is a non-atomic read-modify-write that could lose updates.
Checked: it runs under `lock.writeLock()` (line 542) on the **tx-local** copy, which is session-private (one committing thread), and every other `version++` site (lines 695, 947) is also under the same write lock. The volatile qualifier only provides read visibility; all writers are serialized by the write lock, so the non-atomic increment cannot race. The `@SuppressWarnings("NonAtomicOperationOnVolatileField")` is justified.

#### C7 `makeSnapshot` NPE / stale for an index-only transaction (txState non-null, tx-local schema unseeded) — REFUTED
Hypothesis: broadening the branch from `hasActiveIndexOverlay()` to `txState != null` (`SchemaProxy.java:80`) could NPE at `txState.getTxLocalSchema().makeUncachedSnapshot` if an index-only tx set `txState` without seeding the tx-local schema, or could serve committed structure incorrectly.
Checked: `TxSchemaState` requires a `@Nonnull` `txLocalSchema` at construction (`TxSchemaState.java:140-147`) and `getTxLocalSchema()` is `@Nonnull` (line 153-156), so the copy is always present — no NPE. A pure-data tx leaves `getTxSchemaState()` null (asserted by the new test `snapshotOutsideASchemaTransactionStaysOnTheSharedCommittedCache`), so the fast path is preserved. For an index-only tx the tx-local copy is a `fromStream` re-parse of committed structure, so `makeUncachedSnapshot` yields the committed classes plus the overlaid index list — functionally equivalent to the pre-D21 `delegate.makeUncachedSnapshot`.
