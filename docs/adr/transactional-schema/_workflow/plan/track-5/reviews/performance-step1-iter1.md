<!-- MANIFEST
dimension: performance
step: 5.1
iter: 1
range: 608493b718~1..608493b718
findings: 1
flags: CONTRACT_OK
evidence_base: cert
cert_index: [C1]
index:
  - id: PF1
    sev: should-fix
    anchor: "#pf1-should-fix--uncached-snapshot-rebuilt-per-unpinned-read-during-an-active-overlay"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:357
    cert: C1
    basis: "PSI find-usages + read of MetadataDefault snapshot pin, SchemaShared.makeSnapshot cache, ImmutableSchema ctor, SchemaImmutableClass.init, EntityImpl.getImmutableSchemaClass"
-->

# Performance review — Track 5 Step 1 (tx-local index overlay), iteration 1

Overall this step is performance-clean on the axes the focus note flagged, with one
asymmetry worth a should-fix. The overlay resolution is snapshot-init-only (not
per-read), the force-rebuild is genuinely O(1), and the planner guard is a single
int compare on the per-query planning path. The one real cost is that the
session-private snapshot the overlay drives is deliberately uncached, so any
unpinned snapshot read during an active schema/index tx rebuilds the whole
`ImmutableSchema` — and the per-entity read path issues those reads unpinned.

## Findings

### PF1 [should-fix] — uncached snapshot rebuilt per unpinned read during an active overlay

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 345-361, `makeUncachedSnapshot`), reached from `SchemaProxy.makeSnapshot` (`SchemaProxy.java:78`).
- **Issue**: While `hasActiveIndexOverlay()` is true, `SchemaProxy.makeSnapshot()`
  routes to `SchemaShared.makeUncachedSnapshot(session)`, which builds a fresh
  `ImmutableSchema` on every call and — by design, so a session-scoped overlay view
  never poisons the shared cache — never memoizes it. The committed path
  (`SchemaShared.makeSnapshot(session)`) instead double-checks and caches into the
  `volatile snapshot` field, so repeated reads are O(1). The snapshot read tier is
  refcount-pinned per operation through `MetadataDefault.makeThreadLocalSchemaSnapshot`
  (builds only when `immutableCount == 0`, reuses while pinned), which makes the
  cost once-per-operation *when a pin is held*. But the per-entity resolver
  `EntityImpl.getImmutableSchemaClass(session)` (`EntityImpl.java:4181`) calls
  `getImmutableSchemaSnapshot()` with no pin, and `DatabaseSessionEmbedded.executeReadRecord`
  (`:1124`) opens and closes its own pin per record (0→1→0). There is no
  query-level or scan-level outer pin. So a same-tx data operation that touches N
  records/entities while an index overlay is active rebuilds the full snapshot up
  to N times.
- **Evidence** (COST TRACE):
  - OPERATION: `new ImmutableSchema(this, session)` — iterates every class in
    `getClasses(session)`, constructs a `SchemaImmutableClass` per class, runs
    `init(session)` per class (which eagerly materializes each class's index list
    via `getRawIndexes` → the overridden `getClassRawIndexes`), and iterates every
    index in the manager.
  - COMPLEXITY: O(classes × (props + super-chain + per-class indexes) + total indexes)
    per snapshot build.
  - INVOCATION UNIT: per unpinned `getImmutableSchemaSnapshot()` call. The
    per-entity `EntityImpl.getImmutableSchemaClass(session)` has 91 callers across
    35 files (validation, serialization, `ClassIndexManager` index tracking,
    filter/insert/update execution steps) — a per-record/per-entity hot path.
  - CONTRAST: on the committed path the same reads return the cached `snapshot`
    field in O(1); the regression is purely the missing memoization on the
    overlay-active branch.
  - SCALE CHECK: NEGLIGIBLE for a pure schema-only DDL tx (few unpinned reads).
    MATTERS for the supported same-tx DDL-then-DML pattern this track exists to
    enable — create an index mid-tx, then insert/scan rows on the indexed class
    (I-P2, and the `queryInsideCreatingTransactionFallsThroughToScan` test): a
    full-class scan or a multi-row insert rebuilds the whole `ImmutableSchema` once
    per record. At a class count in the hundreds this is a visible per-record CPU
    and allocation (GC) cost for the duration of the schema/index tx. Bounded to
    the schema/index-tx window (the low-schema-change-rate premise), which is why
    this is should-fix, not blocker.
- **Impact**: CPU and young-gen allocation proportional to (rows touched × schema
  size) during a same-tx data operation that runs while an index overlay is
  active; latency of that one transaction, not steady-state throughput.
- **Suggestion**: Memoize the uncached snapshot for the lifetime of the current
  overlay generation instead of rebuilding per call — e.g., cache the
  session-private snapshot on `TxSchemaState` (or on the session) and invalidate it
  in `forceRebuildSchemaSnapshotForIndexOverlay()` alongside the existing
  thread-local clear, so a mid-tx index change still forces the one rebuild the
  correctness contract needs but intervening reads reuse the built snapshot. This
  keeps the "never in the shared cache" invariant (the cache is session-scoped) and
  restores the committed path's O(1)-per-read profile within a stable overlay
  generation. If deferred, note it against Step 3 (D21), which widens the
  uncached-snapshot trigger to every schema/index tx and so broadens this exact
  amplification.

## Evidence base

#### C1 — snapshot build cost and pin scope (CONFIRMED)

PSI-backed (mcp-steroid, project `transactional-schema-b4l1mcdq`):

- `MetadataDefault.makeThreadLocalSchemaSnapshot` builds only when
  `immutableCount == 0` and reuses `immutableSchema` while pinned;
  `getImmutableSchemaSnapshot()` returns the pinned field when non-null but calls
  `schema.makeSnapshot()` on every call when `immutableSchema == null`.
- `SchemaShared.makeSnapshot(session)` caches into the `volatile snapshot` field
  (double-checked); `makeUncachedSnapshot(session)` does not cache — new
  `ImmutableSchema` each call.
- `ImmutableSchema(SchemaShared, session)` iterates all classes, runs
  `SchemaImmutableClass.init(session)` per class (materializes `this.indexes` once
  via `getRawIndexes`), and iterates all indexes.
- Pin call sites for `makeThreadLocalSchemaSnapshot`: only `AbstractStorage.commit`
  (per-commit, once), `EntityImpl.getGlobalPropertyById` reload branch, and
  `DatabaseSessionEmbedded.executeReadRecord` (per record). No query/scan-level
  outer pin exists.
- `EntityImpl.getImmutableSchemaClass(session)` (`:4181`) reads
  `getImmutableSchemaSnapshot()` unpinned; 91 callers across 35 files (per-entity
  hot path).

Refuted claims (Phase-4 refutation, ruled out — not reported):

- *Overlay-resolved index set recomputed per `getClassRawIndexes`/`getClassIndexes`
  read.* Refuted: `SchemaImmutableClass.init` materializes `this.indexes` once at
  snapshot init; `getIndexesInternal()`/`getRawIndexes()` return the cached set. The
  overridden `getClassRawIndexes`/`getClassIndexes` (with the `super` + fresh
  `ArrayList committed` + `overlay.resolveClassRawIndexes` `LinkedHashSet`) run once
  per class per snapshot build, not per read. The `getClassRawIndexes(3-arg)` base
  is referenced only from within `IndexManagerEmbedded` (2 sites, the old body); the
  overridden entry is reached through the snapshot-init `getRawIndexes` chain.
- *Force-rebuild is expensive.* Refuted: `forceClearThreadLocalSchemaSnapshot`
  nulls the pinned field when `immutableCount == 0` (asserts otherwise);
  `SchemaShared.forceSnapshot` nulls the shared field under a lock. Both O(1); the
  rebuild is deferred to the next read. `createIndex`/`dropIndex` call it once per
  mid-tx index change (the drop path guards on `recordedDrop` so an unknown-name
  no-op allocates no overlay churn and skips the rebuild) — a per-tx cost, not
  per-op.
- *Planner guard cost in `findBestIndexFor`.* Refuted: `isIndexBuilt` is a single
  `getIndexId() >= 0` int compare added as a `.filter` on the candidate index
  stream. `findBestIndexFor` is on the per-query planning path (6 call sites), not
  per-record; the filter cost is negligible and it runs whether or not an overlay
  is active.
- *`IndexOverlay` accessor defensive copies (`getTxCreatedNames`, `getRenamed`
  `Map.copyOf`, etc.).* Not on any hot path — the overlay is read at snapshot-init
  and at commit, both per-tx events; accessor copies are a correctness/immutability
  choice with no steady-state cost.
