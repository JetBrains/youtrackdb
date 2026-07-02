<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 5, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. Every changed path is either O(1) on a hot read/write
path or one-time work on the rare schema-DDL and schema-carrying-commit paths.
The three load-bearing D21 constraints (makeSnapshot strict no-op outside a
schema/index tx, the `resolveForWrite` rebuild hook, the fetch-step provisional
skip) are met. The five candidate concerns are traced and refuted in the
Evidence base.

## Evidence base

#### C1 makeSnapshot strict-no-op fast path (D21 risk 4) — REFUTED
Constraint: `SchemaProxy.makeSnapshot()` tx-awareness must leave the committed
fast path untouched for pure-data commits and reads.

The post-change fast path (`SchemaProxy.java:78-92`) is
`session.getTxSchemaState()` + a null check + `delegate.makeSnapshot(session)`.
The pre-change Step-1 code branched on `session.hasActiveIndexOverlay()`, which
itself called `getTxSchemaState()` once and short-circuited on null. So the fast
path does the same one `getTxSchemaState()` call as before, then the identical
`delegate.makeSnapshot(session)` shared-cache read; the tx branch now calls
`getTxSchemaState()` once instead of twice. `getTxSchemaState()`
(`DatabaseSessionEmbedded.java:2441-2448`) returns null before any custom-data
map lookup when no transaction is active (`tx.isActive()` false), and for an
active pure-data tx it does the same custom-data lookup the pre-change path did.
`makeSnapshot()` is genuinely hot (pinned per read operation via
`MetadataDefault.makeThreadLocalSchemaSnapshot`, and per unpinned read via
`getImmutableSchemaSnapshot` on a null memo), but the per-call work is unchanged.

Verdict: fast path preserved, no regression. Not a finding.

#### C2 forceRebuildTxSchemaSnapshot on every routed schema write — REFUTED
Concern: `resolveForWrite` now calls `session.forceRebuildTxSchemaSnapshot()`
unconditionally on every routed write (`SchemaProxedResource.java:122`), widening
Step 1's invalidation (index create/drop only) to every class and property write.
Candidate regression: repeated full `ImmutableSchema` rebuilds during a schema tx.

Frequency (PSI find-usages of `SchemaProxedResource.resolveForWrite`): all callers
are schema DDL methods on `SchemaProxy` / `SchemaClassProxy` / `SchemaPropertyProxy`
(`createClass`, `dropClass`, `createProperty`, `setStrictMode`, `setRegexp`,
`createIndex`, the property/class setters, ...). Zero callers on a per-record DML
write path. So the hook fires only on schema/index DDL, whose low rate is the
plan's load-bearing premise (Constraints, D5/D12/D19).

Cost per call is O(1): `forceClearThreadLocalSchemaSnapshot()` +
`invalidateOverlaySnapshot()`, both field writes (`DatabaseSessionEmbedded.java:2539-2545`,
`MetadataDefault.java:95-103`). The rebuild it triggers is deferred and amortized:
the `TxSchemaState` overlay-snapshot memo (`SchemaProxy.java:88-98`) means the next
snapshot read rebuilds once per invalidation generation, and intervening unpinned
reads reuse the memo.

Scale check:
- SMALL (single DDL statement): a handful of O(1) invalidations, one lazy
  O(classes) rebuild on the next read. Negligible.
- MEDIUM/PRODUCTION (a bulk migration of many classes in one tx): pure-DDL runs
  keep the memo null and rebuild once at the next read or at commit. A tx that
  interleaves snapshot reads (validate/serialize/query) between writes rebuilds up
  to once per write-then-read cycle, each rebuild O(classes-so-far). Worst case is
  bounded by the DDL statement count and confined to the rare, offline-ish schema
  path, and is dwarfed by the commit's own exclusive-lock in-commit index build
  (D12) and record I/O.

The extra invalidations are required for D21/I-P5 correctness (a class/property
write must invalidate the snapshot or `EntityImpl.validate()` reads stale schema).
The design already uses the cheapest correct mechanism — lazy O(1) invalidate plus
a per-generation memo. No cheaper correct alternative exists.

Verdict: NEGLIGIBLE at realistic scale, required for correctness. Not a finding.

#### C3 FetchFromClassExecutionStep provisional-collection skip — REFUTED
Constraint: the provisional-id skip sits on the query scan-setup path.

The added `SchemaShared.isProvisionalCollectionId(collectionId)` check
(`FetchFromClassExecutionStep.java:110-117`) is a single `id <= ceiling`
comparison (`SchemaShared.java:114-116`), O(1), inside the pre-existing loop over
`clazz.getPolymorphicCollectionIds()`. The loop runs once per `FETCH FROM CLASS`
step in the query planner's scan setup, not per record, and its trip count is a
class's collection count (small, bounded). It runs before the more expensive
`getCollectionNameById` call it now guards, so on the provisional arm it saves a
lookup rather than adding one.

Verdict: negligible. Not a finding.

#### C4 assignAndCheckCollection provisional check — REFUTED
The record-save path gained one `SchemaShared.isProvisionalCollectionId(collectionForNew)`
comparison after `getCollectionForNewInstance` (`DatabaseSessionEmbedded.java`
around line 3024). This is per-new-record (a hot path), but the added work is a
single int comparison; the surrounding `getCollectionForNewInstance` call already
dominates. No allocation, no lock, no I/O added.

Verdict: negligible per-record delta. Not a finding.

#### C5 commit-path snapshot rebuild and version bump — REFUTED
The commit-side additions —
`MetadataDefault.rebuildThreadLocalSchemaSnapshot()` (`MetadataDefault.java:117-125`,
one O(classes) `schema.makeSnapshot()`), the `AbstractStorage.commit`
invalidate-then-rebuild block (guarded by
`getResolvedCollectionIds().isEmpty()` being false), and the single `version++` in
`SchemaShared.resolveProvisionalCollectionIds` (`SchemaShared.java:570`) — all run
once per schema-carrying commit, under the write lock the commit already holds
(D19). The one snapshot rebuild is O(classes) via `new ImmutableSchema(this, session)`
(`SchemaShared.java:360-367`), the same build cost the shared committed
`makeSnapshot` already pays on a cache miss, and is dominated by the commit's own
reconciliation, in-commit index build, and record I/O. Pure-data commits never
enter this block (`getResolvedCollectionIds()` empty).

Verdict: one-time per rare schema commit, dominated by surrounding commit work.
Not a finding.
