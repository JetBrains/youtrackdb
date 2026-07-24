<!--
MANIFEST
review:
  dimension: performance
  step: 3.2
  iteration: 1
  commit_range: b5ecae97066703380356aa37e3241218d6cb5b73~1..b5ecae97066703380356aa37e3241218d6cb5b73
  verdict: pass
  findings_total: 1
  blocker: 0
  should_fix: 0
  suggestion: 1
  evidence_base: "#evidence-base"
  cert_index: [C1, C2, C3]
  flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-suggestion-tier-3-rebindtotxlocal-does-a-by-name-getclass-per-read"
    loc: "core/.../schema/SchemaClassProxy.java:99-111; SchemaPropertyProxy.java:62-88"
    cert: C3
    basis: psi
-->

## Findings

### PF1 [suggestion] Tier-3 `rebindToTxLocal` does a by-name `getClass` per read

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassProxy.java` (line 99-111); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaPropertyProxy.java` (line 62-88)
- **Issue**: Inside a schema transaction (tier 3) every proxy read re-resolves the captured delegate by name: `SchemaClassProxy.rebindToTxLocal` calls `txLocalSchema.getClass(delegate.getName())` and `SchemaPropertyProxy.rebindToTxLocal` does `txLocalSchema.getClass(owner.getName())` then `resolvedOwner.getPropertyInternal(name)`. A single explicit-API expression such as `cls.getName(); cls.isAbstract(); cls.getCustom(...)` pays one name-keyed map lookup per call rather than caching the rebound impl for the duration of the proxy.
- **Evidence**: See `#c3-tier-3-rebind-cost` in the Evidence base. The lookup is `O(1)` (hash map keyed by class name) but repeats per read. The class/property name is identity-stable for a proxy except across an in-tx rename, which already zeroes `hashCode`, so a cache would need the same invalidation hook.
- **Impact**: Tier 3 is reached only during a schema transaction, which the plan's load-bearing premise holds to a low rate (plan `### Constraints`, "The low schema-change rate is the load-bearing premise"). At that frequency the extra map lookups are not measurable. No production latency or throughput effect.
- **Suggestion**: Leave as-is for this step. The correctness-first name-rebind is the right default and a per-proxy rebind cache would add an invalidation surface (rename, drop) for no measurable gain at the premised schema-change rate. Recorded only so a future high-DDL workload (the deferred YTDB-1064 populated-schema case) has the spot flagged.

## Evidence base

The Phase-4 scale-validation roster. A claim that survived refutation (confirmed as a real, non-negligible issue) is compressed to one line; a refuted or negligible claim is rendered in full so the reasoning that retired it is auditable.

#### C1 — Tier-2 read fast path (no schema tx): negligible — REFUTED as an issue

The review brief's primary concern was that `resolve()` now sits on every proxy access and might tax the common non-schema-tx read. Traced against source (PSI):

- `SchemaProxedResource.resolve()` (diff lines 1225-1231) calls `session.getTxSchemaState()`; if `null`, returns the captured `delegate` — identical to the pre-seam direct `delegate.x()` plus one non-virtual call frame.
- `DatabaseSessionEmbedded.getTxSchemaState()` (diff 32-39): `assert assertIfNotActive()` is `assert`-guarded, so it is a no-op when assertions are disabled (production). It reads the `currentTx` field, calls `tx.isActive()`, and only then `tx.getCustomData(TX_SCHEMA_STATE_KEY)`.
- **Outside any transaction** `currentTx` is `FrontendTransactionNoTx`, whose `isActive()` returns `false` (constant), so `getTxSchemaState()` returns `null` before any map touch. The read is a field read + a constant-false branch + a returned reference. Zero allocation, zero map lookup.
- **Inside a data transaction** (the dominant proxy-read shape during work) `FrontendTransactionImpl.isActive()` is true (3 enum `!=` compares), then `getCustomData("txSchemaState")` is `userData.get(name)` where `userData` is a `private final HashMap<String,Object>` allocated once per transaction (PSI-confirmed: `new HashMap<>()`, eager, not lazy, not synchronized). That is one `HashMap.get` on an interned String constant, returning `null` for tier 2. No per-call allocation; no per-call map creation.

`COST TRACE resolve() tier-2`: OPERATION = field reads + `isActive()` enum compares + at most one `HashMap.get`; COMPLEXITY = `O(1)` per call; ALLOCATIONS = 0; I/O = none; LOCK HOLD = none. `SCALE CHECK`: AT SMALL/MEDIUM/PRODUCTION SCALE = negligible (a hash lookup of a constant string per explicit-schema-API read). VERDICT: NEGLIGIBLE. No finding.

#### C2 — The per-record CRUD hot path does not route through the seam at all — CONFIRMED (scope-narrowing fact)

Per-record insert/validation reads the schema through the immutable snapshot, not the proxy: `DatabaseSessionEmbedded.getCollectionName` and `assignAndCheckCollection` obtain the class via `entity.getImmutableSchemaClass(this)`, whose return type is `SchemaImmutableClass` (PSI). `SchemaImmutableClass` does NOT extend `SchemaProxedResource` (PSI-confirmed), so `getCollectionForNewInstance` / `isAbstract` on the per-record path never call `resolve()`. The seam cost lands only on the explicit `getMetadata().getSchema()...` API path (DDL, schema introspection), not on the per-record data hot path. This narrows the "resolve() on a hot path" premise sharply and is why C1 is negligible at production scale.

#### C3 — Tier-3 rebind cost (PF1) — confirmed as a real but premise-bounded per-read cost

Tier 3 (`SchemaProxedResource.resolve()` → `rebindToTxLocal`): `SchemaClassProxy.rebindToTxLocal` short-circuits when `delegate.getOwner() == txLocalSchema` (already a tx-local object), else `txLocalSchema.getClass(delegate.getName())` — one name-keyed map lookup. `SchemaPropertyProxy.rebindToTxLocal` does up to two (`getClass(owner.getName())` then `getPropertyInternal(name)`). `COST TRACE`: OPERATION = 1-2 `HashMap.get` by name per read; COMPLEXITY = `O(1)` per call, but repeated per proxy access rather than cached; ALLOCATIONS = 0; I/O = none; LOCK HOLD = none. `SCALE CHECK`: tier 3 is reached only during a schema transaction; the plan's `### Constraints` make the low schema-change rate load-bearing, so the call frequency is bounded to rare DDL. AT PRODUCTION SCALE = not measurable. VERDICT: MATTERS AT SCALE only under the explicitly-deferred high-DDL workload (YTDB-1064); reported as a suggestion, not actionable in this step.
