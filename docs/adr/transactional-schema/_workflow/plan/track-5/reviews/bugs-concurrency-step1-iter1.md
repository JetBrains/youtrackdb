<!-- MANIFEST
review: bugs-concurrency
track: 5
step: 1
commit_range: 608493b718~1..608493b718
flags: CONTRACT_OK
findings: 3
evidence_base: 4
cert_index: [C1, C2, C3, C4]
index:
  - id: BC1
    sev: should-fix
    anchor: "### BC1 "
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:519
    cert: C1
    basis: "PSI: EntityImpl.getImmutableSchemaClass version-keyed cache + ClassIndexManager.processIndexOn* read path + SchemaShared.version not bumped on mid-tx index create"
  - id: BC2
    sev: suggestion
    anchor: "### BC2 "
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:2549
    cert: C2
    basis: "PSI: makeThreadLocalSchemaSnapshot callers/clearers asymmetry (getGlobalPropertyById pins, no paired clear) vs forceClearThreadLocalSchemaSnapshot pin-zero assertion"
  - id: BC3
    sev: suggestion
    anchor: "### BC3 "
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:44
    cert: C3
    basis: "PSI: executeReadRecord holds the pin across beforeReadOperations/afterReadOperations hooks; force-clear pin-zero assumption undefended against a hook-issued DDL"
-->

## Findings

### BC1 [should-fix] Force-rebuild does not refresh an entity's version-keyed `immutableClazz` cache, so the stated same-tx index-tracking guarantee is not achieved by Step 1 alone

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 517-522), and the mirror comment at `SchemaProxy`/`DatabaseSessionEmbedded.forceRebuildSchemaSnapshotForIndexOverlay`

**Issue.** The `createIndex` deferred path comments (IndexManagerEmbedded.java:517-520) assert: "force-rebuild the snapshot so the next read re-materializes that set. Without the rebuild a same-transaction insert into the indexed class reads the stale cached set and silently misses the new index." The force-rebuild (`forceRebuildSchemaSnapshotForIndexOverlay` → `MetadataDefault.forceClearThreadLocalSchemaSnapshot`) only nulls the session-pinned `MetadataDefault.immutableSchema`. It does **not** advance `SchemaShared.version` (a mid-tx index create touches only the overlay + `markClassChanged`; nothing bumps `version`). But `EntityImpl.getImmutableSchemaClass(session, snapshot)` caches its resolved `SchemaImmutableClass` in `immutableClazz` and re-resolves it **only** when `immutableSchemaVersion != immutableSchema.getVersion()`. `ClassIndexManager.processIndexOnCreate/Update/Delete` read the per-class index set through `entity.getImmutableSchemaClass(...).getRawIndexes()` — the set materialized once on that `SchemaImmutableClass` at snapshot init.

**Failure scenario.** Top-level: create class C + property. Begin tx; touch an entity `e` of C so its index-tracking path resolves `e.immutableClazz` at `SchemaShared.version == V` (index set = empty). Mid-tx `createIndex` on C: overlay records the new index, force-rebuild nulls the pinned snapshot. Later in the same tx, update `e`: `processIndexOnUpdate` calls `e.getImmutableSchemaClass(session)`; the freshly rebuilt snapshot still carries `version == V`, so `immutableSchemaVersion == V` matches and the **stale** `immutableClazz` is returned. Its `getRawIndexes()` omits the new index, so the update's index entries for the new index are silently skipped — the same F32 silent-untracking shape the comment claims the rebuild prevents.

**Mitigation / scope note.** End-to-end correctness of the *committed* index is preserved by Step 2's commit-time full population scan + final-state re-derivation (which rebuilds the tx-created index from the final collection state regardless of per-op tracking), and the plan explicitly assigns the version-bump fix to Step 3 (D21 risk (3): "invalidate ... the snapshot version, because `EntityImpl.getImmutableSchemaClass` re-resolves only when `getVersion()` advances") and the I-P2 insert-tracking acceptance to Step 2. So this is a plan-acknowledged cross-step gap, not a Step-1-only regression. The defect is that the in-code comment overstates what the Step-1 mechanism delivers: the force-rebuild refreshes the *snapshot* but not a *cached entity's* class, so per-op same-tx tracking is not actually restored here.

**Suggestion.** Either (a) soften the `createIndex`/`dropIndex` comments to state that per-op refresh depends on the snapshot-version bump added in Step 3 (and that same-tx tracking is only guaranteed end-to-end via the Step-2 commit rebuild), or (b) if Step 1 is meant to stand alone, bump the snapshot version in the force-rebuild so cached entities re-resolve. Given the plan's staging, (a) is the lower-risk fix for this step; leave the version-bump to Step 3 where the whole read chain is invalidated together.

### BC2 [suggestion] Unpaired pin in `getGlobalPropertyById` can leave `immutableCount > 0`, turning the new force-clear assertion into a hard `IllegalStateException`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 2544-2549, `forceRebuildSchemaSnapshotForIndexOverlay`)

**Issue.** `forceRebuildSchemaSnapshotForIndexOverlay` delegates to `MetadataDefault.forceClearThreadLocalSchemaSnapshot`, which throws `IllegalStateException` when `immutableCount != 0`. The three pin acquirers are `executeReadRecord` (released in `finally`), `AbstractStorage.commit` (released via `applyCommitOperations`), and `EntityImpl.getGlobalPropertyById`. The `getGlobalPropertyById` reload-fallback branch calls `metadata.makeThreadLocalSchemaSnapshot()` with **no paired `clearThreadLocalSchemaSnapshot()`** (PSI: `clearThreadLocalSchemaSnapshot` has only two callers — `executeReadRecord` and `applyCommitOperations`). This is a pre-existing pin leak, not introduced by this diff.

**Failure scenario.** During a schema/index tx, some path resolves a global property whose id is absent from the committed snapshot (the reload-fallback fires), incrementing `immutableCount` without release. A subsequent mid-tx `createIndex`/`dropIndex` calls `forceClearThreadLocalSchemaSnapshot` with `immutableCount >= 1` → `IllegalStateException("... snapshot usage count is not zero")`, aborting the DDL. The step's Javadoc (`DatabaseSessionEmbedded.forceRebuildSchemaSnapshotForIndexOverlay`) states the zero-count invariant "holds because an index DDL change is not issued from inside a pinned read-record operation" — but this pin leaks *outside* a read-record window and would survive to the DDL.

**Suggestion.** This is low-probability (the fallback is a rare unknown-global-property recovery path) and pre-existing, so it need not block this step. Worth a tracked follow-up: pair `getGlobalPropertyById`'s `makeThreadLocalSchemaSnapshot()` with a `clearThreadLocalSchemaSnapshot()` in a `finally`, so the new force-clear assertion cannot be tripped by a latent pin leak.

### BC3 [suggestion] Force-clear pin-zero assumption is undefended against a DDL issued from a read hook inside the pinned `executeReadRecord` window

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 26-46, the `forceRebuildSchemaSnapshotForIndexOverlay` Javadoc), interaction with `executeReadRecord`

**Issue.** `executeReadRecord` holds the snapshot pin from `makeThreadLocalSchemaSnapshot()` through the `finally { clearThreadLocalSchemaSnapshot() }`, and inside that window it runs `beforeReadOperations`/`afterReadOperations` (user/registered hooks) and `checkClass`. The Step-1 Javadoc asserts the pin count is zero at index-DDL time because "an index DDL change is not issued from inside a pinned read-record operation." That is an assumption about hook behaviour, not an enforced invariant: a hook or registered listener that triggers an index `createIndex`/`dropIndex` (directly or via a cascade) would run the force-clear with `immutableCount >= 1` and throw `IllegalStateException`.

**Failure scenario.** A `RecordHook`/listener fires from within `afterReadOperations` during a schema/index tx and performs an index DDL on some class. The force-rebuild's `forceClearThreadLocalSchemaSnapshot` sees a non-zero pin (held by the enclosing `executeReadRecord`) and throws, converting a read into a spurious failure.

**Suggestion.** Documentation/robustness only — no evidence any built-in hook does this today. Consider either explicitly documenting that index DDL from a read hook is unsupported, or having the force-rebuild tolerate a held pin by rebuilding lazily without asserting zero (e.g., invalidate on pin release) rather than throwing. Not a blocker for Step 1.

## Evidence base

#### C1 — Entity-cache staleness across the force-rebuild (BC1): CONFIRMED

`MetadataDefault.forceClearThreadLocalSchemaSnapshot` only nulls `immutableSchema`; it does not touch `SchemaShared.version`. The mid-tx `createIndex` deferred path (IndexManagerEmbedded.java:496-523) records the overlay entry, calls `markClassChanged`, and force-rebuilds — none of which advances `version` (PSI: `SchemaShared.getVersion()` returns the plain `version` field; no writer on the tx-created path). `EntityImpl.getImmutableSchemaClass(session, immutableSchema)` (PSI dump) caches `immutableClazz` and re-resolves only on `immutableSchemaVersion != immutableSchema.getVersion()`. `ClassIndexManager.processIndexOnCreate/Update/Delete` (PSI dump) read `entity.getImmutableSchemaClass(...).getRawIndexes()` — the set materialized once in `SchemaImmutableClass.init` via `getRawIndexes(session, indexes)` → the overridden `getClassRawIndexes` seam. Chain is airtight: a cached entity resolved at version V keeps its stale per-class index set after a same-version rebuild. **Refutation attempts:** (1) "Queries read fresh, not cached entities" — true, the planner takes a fresh snapshot, so *query* correctness inside the tx is unaffected; the defect is confined to per-op DML index tracking via a re-touched entity. (2) "Commit rebuild masks it" — confirmed via the Step-2 plan text (population scan + final-state re-derivation), so committed-index correctness survives; this is why the finding is should-fix (claim accuracy + a real but masked per-op miss), not a blocker. (3) "Plan already owns the fix" — confirmed: D21 risk (3) names the snapshot-version invalidation as Step 3 scope and I-P2 as Step 2, so the surviving defect is the overstated Step-1 comment, which a reviewer would mis-model. Survives as should-fix.

#### C2 — Unpaired pin vs force-clear assertion (BC2): CONFIRMED (pre-existing, low-probability)

PSI reference search: `makeThreadLocalSchemaSnapshot` callers = {`executeReadRecord`, `AbstractStorage.commit`, `EntityImpl.getGlobalPropertyById`}; `clearThreadLocalSchemaSnapshot` callers = {`executeReadRecord`, `AbstractStorage.applyCommitOperations`}. `getGlobalPropertyById` (PSI dump) pins in its reload-fallback with no paired clear. `forceClearThreadLocalSchemaSnapshot` (PSI dump) throws when `immutableCount != 0`. So a leaked pin from the fallback survives to a later mid-tx DDL force-clear and throws. **Refutation:** the fallback is a rare recovery path (global-property id missing from the committed snapshot then reload), and the leak predates this diff — hence suggestion severity. But the new force-clear assertion is what converts the latent leak into a user-visible failure, so it is worth recording against this step.

#### C3 — Hook-issued DDL inside the pinned read window (BC3): PLAUSIBLE

`executeReadRecord` (PSI dump) pins for the whole method body, including `beforeReadOperations`/`afterReadOperations`. No project evidence that any built-in hook issues an index DDL, so I could not construct a concrete reachable interleaving from shipped code — hence suggestion, not should-fix. Recorded because the Step-1 Javadoc states the pin-zero invariant as a fact about hook behaviour rather than an enforced guard, and a plugin/listener could violate it.

#### C4 — Non-findings verified (cross-session isolation, override semantics, flat-map, create-then-drop): NO ISSUE

Checked and cleared, each a candidate that did not survive:
- **Cross-session leak of the overlay snapshot** — REFUTED. `SchemaProxy.makeSnapshot` routes to `SchemaShared.makeUncachedSnapshot(session)` when `hasActiveIndexOverlay()`; `makeUncachedSnapshot` never assigns `this.snapshot`, so the process-shared cache is never poisoned. The overlay-aware snapshot lives only in the per-session `MetadataDefault.immutableSchema` (PSI: `metadata` is a `private MetadataDefault` field on `DatabaseSessionEmbedded`, one per session). The `overlayDoesNotLeakToConcurrentSessionSnapshot` test opens a distinct session, so its assertion is valid. Cross-session isolation holds.
- **`getClassIndexes` override collapsing a distinction** — REFUTED. In the base `IndexManagerAbstract`, `getClassIndexes(session,className,coll)` and `getClassRawIndexes(session,className,coll)` have byte-identical bodies (both iterate `getIndexOnProperty(className).values()`), so routing both through `overlay.resolveClassRawIndexes` drops no semantics. The `Set`-returning `getClassIndexes(session,className)` calls the void overload virtually and lands on the `IndexManagerEmbedded` override (PSI OverridingMethodsSearch confirms the override).
- **Flat `ImmutableSchema.indexes` map is not overlay-aware** — REFUTED as a production defect. The ctor builds it from `indexManager.getIndexes()` (committed registry only), so `getIndexes()`/`indexExists`/`getIndexDefinition` ignore the overlay. PSI reference search shows all production-relevant callers are tests (`ImmutableSchemaShapeTest`, `CaseSensitiveClassNameTest`, `SchemaClassProxyBoundaryTest`); no production correctness path consults the flat map during a tx. `getClassInvolvedIndexes` (also not overlay-aware) surfaces only built, committed indexes, which is harmless because a tx-created index is unbuilt and D13-skipped anyway, and index *tracking* uses `getRawIndexes()` (overlay-aware), not involved-indexes.
- **`dropIndex` overlay-first / null-safety** — NO ISSUE. The tx-drop path checks `existingOverlay.isTxCreated(iIndexName)` first (canceling a same-tx create via `recordDropped`, which removes the `txCreated` entry and records no spurious drop — matches `createThenDropSameNameCancels`), else takes the shared lock, null-guards `idx` and `idx.getDefinition()`, and only force-rebuilds when `recordedDrop`. An unknown-name drop is a clean no-op with no overlay churn. `recordDropped`/`recordCreated` net-cancellation rules are unit-covered in `IndexOverlayTest`.
- **`IndexOverlay` thread-safety** — NO ISSUE. Plain `HashMap`/`HashSet` with no synchronization, but the overlay is session-confined (lives on `TxSchemaState`, one per session/tx) and every read/write runs on the session thread (snapshot build via `makeUncachedSnapshot` runs on the session thread under the committed `SchemaShared` read lock). No cross-thread publication of the overlay maps.
- **Rollback cleanliness** — NO ISSUE. The overlay is dropped with `TxSchemaState` on rollback (never promoted to the shared manager); the per-op pin is released in each pinner's `finally`, so `immutableCount` returns to 0 and `immutableSchema` is nulled between ops, leaving no lingering overlay-aware snapshot. The `txDroppedIndexIsHiddenFromClassRawIndexSetThroughOverlay` test asserts the committed index reappears after rollback.
