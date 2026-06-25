<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: PF1, sev: suggestion, loc: SchemaProxedResource.java:43, anchor: "### PF1 ", cert: C1, basis: "resolve()/resolveForWrite() seam adds one getTxSchemaState() probe (field read + tx.isActive() branch + a HashMap.get returning null when any tx is active) to every read on the three schema proxies; nanosecond-scale, the hottest per-record path uses the untouched snapshot family, negligible but now always-on"}
  - {id: PF2, sev: suggestion, loc: SchemaShared.java:144, anchor: "### PF2 ", cert: C2, basis: "copyForTx holds the committed SchemaShared.lock write lock across ~N per-class record loads + derived-state recompute; corroborated from step-1 PF1 and now production-reachable via the Step-2 seam, but the cached-snapshot read path never takes this lock so the held window blocks only lock-based committed reads during the rare seed — benign under the low-schema-change premise"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C1, verdict: CONFIRMED-NOTE, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED-NOTE, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] The proxy resolve() seam adds a per-read tx-state probe to every schema-proxy read, including the common no-schema-tx case

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java` (lines 43-60)

**Issue**: Step 2 funnels every read method on `SchemaProxy`, `SchemaClassProxy`, and `SchemaPropertyProxy` through `resolve()`, replacing a direct `delegate.foo()` with `session.getTxSchemaState()` followed by either a return of the captured delegate (tier 2) or a by-name rebind (tier 3). This is the single new always-on cost the track introduces on a non-DDL read path, so it is worth recording even though it lands well inside negligible.

`getTxSchemaState()` (`DatabaseSessionEmbedded`) does: `getTransactionInternal()` (a `currentTx` field read plus an assertion-only `assertIfNotActive()`), `tx.isActive()`, and — only when a transaction is active — `tx.getCustomData("txSchemaState")`, which is `userData.get(key)` on an always-allocated `HashMap` (`FrontendTransactionImpl.userData = new HashMap<>()`). So:

- **No transaction open** (pure read outside a tx): field read + branch, returns null. Effectively free.
- **An ordinary data transaction open** (the common state during inserts and many query executions): the same plus one `HashMap.get` on a constant String key that returns null. A single hash lookup, no allocation.

The seam is well-placed: the hottest per-record path does not touch it. The per-insert collection-selection path in `DatabaseSessionEmbedded` reads `entity.getImmutableSchemaClass(this)` — the tier-1 immutable snapshot class — for `isAbstract()` / `getCollectionForNewInstance()`, which never flows through `resolve()` (PSI: `SchemaClassInternal#getCollectionForNewInstance` has 3 refs, both production callers are the snapshot path). The proxy reads that do route through `resolve()` are query-planning and security reads (`getPropertyInternal` in `MatchExecutionPlanner` / `SelectExecutionPlanner` / `SecurityShared`, 62 interface refs), reached per-query during planning, not per-record.

**Evidence**: COST TRACE C1 and SCALE CHECK C1 below. The frequency context is C3 (the snapshot family is the per-record path and is untouched).

**Impact**: Negligible latency and zero allocation per read. A query planner that does dozens of `getPropertyInternal` probes pays dozens of null-returning `HashMap.get`s — nanoseconds against the planning, optimization, and I/O work that dominates a query. No GC pressure (the probe allocates nothing on the read path; tier-3 rebind allocates only during a schema tx, which is rare). The cost is monomorphic and JIT-inlineable.

**Suggestion**: No change. Recorded so a future profiler pass does not rediscover the seam cold. If a schema-proxy read ever shows up hot in a flame graph (it should not, given the snapshot path carries per-record reads), the cheap mitigation is to short-circuit `resolve()` on `tx == null || !tx.isActive()` before the `getCustomData` lookup — but that saves a single hash lookup and is not worth the added branch today.

### PF2 [suggestion] copyForTx holds the committed schema write lock across per-class record I/O — corroborated and now production-reachable

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 144-168)

**Issue**: This corroborates the step-1 review's PF1 at track level and refines two of its premises now that the surrounding steps have landed. The lock window itself is unchanged from step 1: `copyForTx` holds `lock.writeLock()` across `session.load(identity)` for the root record plus, inside `fromStream`, a `session.load(classRid)` per class and the inheritance / `polymorphicCollectionIds` / subclass-set recompute — roughly N per-class record loads plus an O(N + inheritance-edges) recompute under the lock, for N = class count. The step-3 episode notes the de-guarded copy seed reads via the read-only re-parse (no `toStream` write-back on the copy), so the track-final window is the `fromStream` re-load + recompute leg, lighter than the `2 × N` the step-1 review estimated against the design's literal `toStream` seed.

Two cross-step refinements the step-1 review flagged for re-check:

- **C4 of step-1 no longer holds.** Step 1 refuted any runtime-allocation reading because `copyForTx` had no production caller. Step 2 wired the production seam (`resolveForWrite()` → `ensureTxSchemaState()` → `copyForTx`), so the round trip is now production-reachable on a schema tx's first write. The frequency premise (C3 below) still neutralizes it: production-reachable, but only on the rare schema-tx-first-write event.
- **The contention surface is narrower than a write-lock-held-across-I/O finding would imply.** `makeSnapshot()` returns the cached `volatile snapshot` without taking `SchemaShared.lock` once the snapshot exists (it acquires the read lock only to build the first snapshot). So a held `copyForTx` write lock does not block the dominant cached-snapshot read. It blocks only the lock-based, non-snapshot committed reads (the 20 `acquireSchemaReadLock` sites inside `SchemaShared` / `SchemaClassImpl`), and only for the seed window, and only on a concurrent session (the engaging session holds `MetadataWriteMutex` and is the sole schema writer).

**Evidence**: COST TRACE + SCALE CHECK C2 below; the frequency premise C3; the snapshot-read-does-not-take-the-lock refutation C5.

**Impact**: None at the target operating point, matching step-1 PF1. One-time O(N) cache-resident load + recompute per schema-changing transaction's first write, holding a lock contended only by concurrent lock-based committed reads during that window. At dozens-to-low-hundreds of classes this is microseconds-to-low-milliseconds, dwarfed by the surrounding transaction. It becomes measurable only under a large-class-count + high-DDL workload that the plan's low-schema-change-rate premise and the YTDB-1064 populated-schema deferral explicitly set aside.

**Suggestion**: No change. Carry the characterization to Track 4 (commit-time promotion runs under a held lock too) and the YTDB-1064 follow-up, as step-1 PF1 already recommended. The deferred D8 alternative B (immutable committed base + changed-class overlay) remains the escape hatch if a large-schema benchmark later shows the window matters; re-cost it there, not here.

## Evidence base

#### C1 COST TRACE + SCALE CHECK for SchemaProxedResource.resolve / resolveForWrite (SchemaProxedResource.java:43-60) — CONFIRMED as a forward note, not an issue
CONFIRMED: the seam adds a per-read `getTxSchemaState()` probe to every read on the three schema proxies, but the SCALE CHECK verdict is NEGLIGIBLE — a single null-returning `HashMap.get` on the active-tx case, a field read + branch otherwise, no allocation, monomorphic and inlineable. (OPERATION: `resolve()` = `session.getTxSchemaState()` then return delegate (tier 2) or `rebindToTxLocal` (tier 3); `getTxSchemaState` = `currentTx` field read + `tx.isActive()` + conditional `userData.get("txSchemaState")` on an always-allocated `HashMap`. COMPLEXITY: O(1) per proxy read. DATA SCALE: invoked per schema-proxy read; per-query during planning (dozens of `getPropertyInternal` probes), NOT per record — the per-record collection-selection path uses the tier-1 snapshot class and bypasses the seam. ALLOCATIONS: zero on the read path (tier-3 rebind allocates only during a schema tx). I/O: none. LOCK HOLD TIME: none. AT SMALL SCALE (100 records): negligible. AT MEDIUM (100K): negligible. AT PRODUCTION (1M+): negligible — dozens of hash lookups per query plan against the planning + I/O cost. VERDICT: NEGLIGIBLE.)

#### C2 COST TRACE + SCALE CHECK for SchemaShared.copyForTx (SchemaShared.java:144-168) — CONFIRMED as a forward note, not an issue
CONFIRMED: corroborates step-1 PF1. The track-final window holds `lock.writeLock()` across ~N per-class record loads (`fromStream` re-load leg) + an O(N + inheritance-edges) derived-state recompute; lighter than step-1's `2 × N` estimate because the de-guarded copy seed re-parses read-only without the `toStream` write-back. SCALE CHECK verdict NEGLIGIBLE at the target operating point, MATTERS-AT-SCALE only under a large-class-count + high-DDL workload excluded by the premise. (OPERATION: `session.load` root + per-class loads in `fromStream` + inheritance/polymorphic-id recompute, all under `lock.writeLock()`. COMPLEXITY: O(N) loads + O(N + edges) recompute. DATA SCALE: N = class count, dozens–low-hundreds typical. ALLOCATIONS: O(N) transient `EntityImpl`/collection + 1 `SchemaShared`. I/O: ~N record loads riding the caller's tx, cache-resident in steady state, no fsync. LOCK HOLD TIME: includes the record-load round trip, not computation-only. AT SMALL/MEDIUM: negligible. AT thousands-of-classes + high-DDL: noticeable but premise-excluded. VERDICT: NEGLIGIBLE now / MATTERS-AT-SCALE only outside the premise.)

#### C3 PREMISE — both findings are gated by the low schema-change rate; the per-record path is the untouched snapshot family — REFUTES any "hot path" reading
The plan Constraints name the low schema-change rate as the load-bearing premise. `copyForTx` runs once per schema-changing transaction's first write (PF2), and `resolveForWrite` tier-3 rebinds run only during a schema tx; neither runs per record or per data commit. For PF1's read seam, the genuinely hot per-record path — collection selection on insert — reads `entity.getImmutableSchemaClass(session)` (the tier-1 immutable snapshot class), which does not flow through `resolve()` at all (PSI: `getCollectionForNewInstance` production callers are the snapshot path, not a `SchemaClassProxy`). A finding that treated either window as a per-record hot-path cost would be refuted by the design's operating-point assumption and by the snapshot-family routing of the per-record path.

#### C4 REFERENCE-ACCURACY — getTxSchemaState / getCustomData / userData costs are PSI-verified, not grep-inferred — REFUTES an over-stated per-read cost
PSI (project `transactional-schema`, aligned with the working tree) confirms the exact per-read work: `DatabaseSessionEmbedded#getTxSchemaState` body = `assertIfNotActive()` + `getTransactionInternal()` + `tx.isActive()` + `(TxSchemaState) tx.getCustomData(TX_SCHEMA_STATE_KEY)`; `getTransactionInternal` body = `return currentTx` (field read); `FrontendTransactionImpl#getCustomData` body = `return userData.get(iName)`; `userData` field = `private final HashMap<String, Object> userData = new HashMap<>()` (always allocated, so the lookup always runs when a tx is active, but it is a single hash lookup with no lazy-allocation branch). A finding asserting a heavier per-read cost (map allocation, reflection, lock acquisition) would be refuted: the seam is a field read, a branch, and at most one hash lookup.

#### C5 PREMISE — the cached-snapshot read does not take SchemaShared.lock — REFUTES a "write lock held across I/O blocks the hot read path" reading
PSI-read `SchemaShared#makeSnapshot` body: `var snapshot = this.snapshot; if (snapshot == null) { acquireSchemaReadLock(); ... } return snapshot;`. Once the volatile `snapshot` is built, the read returns it with no lock acquisition. The hot schema read path the design routes through snapshots (D19 / I-A6) therefore does not contend with a held `copyForTx` write lock. The lock blocks only the lock-based, non-snapshot committed reads (the `acquireSchemaReadLock` sites in `SchemaShared` / `SchemaClassImpl`), during the seed window, on a concurrent session — a narrow, premise-bounded surface. A lock-contention finding asserting the held write lock stalls hot reads would be refuted. (Reference accuracy: the snapshot-first read conversion D19 is a Track-4 deliverable; the cached-snapshot fast path that makes this refutation hold is present in `makeSnapshot` today, so the refutation rests on code present in this track, not only on the plan contract.)
