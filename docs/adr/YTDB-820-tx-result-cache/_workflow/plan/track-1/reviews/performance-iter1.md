<!--
MANIFEST
dimension: performance
track: 1
iteration: 1
verdict: pass-with-suggestions
findings_total: 4
blockers: 0
should_fix: 1
suggestions: 3
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags:
  psi_unavailable: true
  reference_accuracy_caveat: "mcp-steroid PSI (steroid_execute_code) timed out twice this session, matching the track file's documented pattern. Caller-frequency claims below rest on the implementation plan / track-file hot-path documentation and direct source reads, not PSI find-usages. Any finding whose severity depends on an exact caller count is annotated inline."
index:
  - id: PF1
    sev: should-fix
    anchor: "#pf1-should-fix-disabled-path-re-reads-the-config-flag-on-every-query"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java:2867 (getQueryResultCache); DatabaseSessionEmbedded.java serveThroughCache"
    cert: C1
    basis: "direct-read"
  - id: PF2
    sev: suggestion
    anchor: "#pf2-suggestion-delta-build-walks-the-entire-mutation-log-across-all-classes"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java:1580 (snapshot loop)"
    cert: C2
    basis: "direct-read"
  - id: PF3
    sev: suggestion
    anchor: "#pf3-suggestion-where-re-eval-is-odelta-but-allocates-a-resultinternal-per-injected-row"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java:1621-1635"
    cert: C3
    basis: "direct-read"
  - id: PF4
    sev: suggestion
    anchor: "#pf4-suggestion-cachekey-builds-a-hashmap-and-boxes-indices-on-every-positional-query-including-hits"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CacheKey.java:624-633 (forArgs)"
    cert: C4
    basis: "direct-read"
-->

## Findings

### PF1 [should-fix] Disabled-path re-reads the config flag on every query

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (`getQueryResultCache`, lines ~2867-2875 in the diff); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (`serveThroughCache`).

**Issue**: When the feature is disabled, the `queryResultCache` field stays `null` for the entire transaction. `getQueryResultCache()` guards on `queryResultCache == null`, so the null branch is taken on every `query()` call, and each call re-invokes `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean()`. The performance contract for this feature (review brief: "no allocation, no map lookup, ideally a single boolean check") wants the disabled decision derived once. The implementation instead re-derives it per query: a method call plus a config read.

`getValueAsBoolean()` (GlobalConfiguration.java:1624) is `value != null && value != nullValue ? value : defValue` followed by an `instanceof Boolean` test — no allocation, no map lookup, but repeated on the hottest path in the system (every `query()`), defeating the "field is null forever, decision made" intent.

**Evidence**: See cert C1. COST TRACE per disabled `query()`: 1 `instanceof FrontendTransactionImpl`, 1 virtual call to `getQueryResultCache()`, 1 enum-field read + `instanceof Boolean` in `getValueAsBoolean()`, 1 short-circuit on `cache == null`, then `executeUncached` (1 `instanceof Map`, 1 dispatch). No allocations. SCALE CHECK — at the Hub/DNQ rate of hundreds-to-thousands of `query()` per request, this is hundreds-to-thousands of redundant config reads per request whenever the feature is off (the default-shipped state). Absolute cost is single-digit nanoseconds per call, so wall-clock impact is negligible against storage and HTTP latency. VERDICT: MATTERS AT SCALE only as repeated work, not as latency. The reason this is should-fix rather than suggestion is the explicit contract mismatch, not the nanoseconds.

**Suggestion**: Cache the enabled decision once per transaction. Read `QUERY_TX_RESULT_CACHE_ENABLED` at the first `getQueryResultCache()` call (or at tx begin) into a `Boolean cacheEnabled` field, and on the disabled path return on a field read rather than re-reading the config. A three-state field (`null` = not yet decided, `FALSE` = disabled this tx, the cache instance = enabled) collapses the steady-state disabled check to one reference-null comparison. It also makes the documented "flag read once at creation; toggling mid-transaction does not retroactively create or drop the cache" contract literally true for the disabled case — today a mid-tx enable would silently start caching because the config is re-read every call while the field is null.

---

### PF2 [suggestion] Delta build walks the entire mutation log across all classes

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java` (snapshot loop, lines ~1580-1584 in the diff).

**Issue**: On a cache hit whose `mutationVersion` differs from the entry's cached delta-pair version, `buildForRecord` iterates `tx.getRecordOperationsInternal()` — the full per-transaction mutation log, every staged op across every class — allocating a transient `ArrayList<RecordOperation>` of the version-filtered subset, then dispatches each survivor. The work is O(total tx mutations), not O(mutations touching the query's classes). The class filter (`effectiveFromClasses.contains`) is applied inside the loop, so the scan still visits every op. The loop comment names a per-class mutation index as the deferred v2 lever.

**Evidence**: See cert C2. COST TRACE: `getRecordOperationsInternal()` returns the `HashMap<RecordIdInternal, RecordOperation>` values view (FrontendTransactionImpl.java:1438, field at line 86), so iteration is O(M) where M = total staged mutations. Per fresh delta build: O(M) snapshot scan + O(M) transient-list allocation + O(d) dispatch (d = ops surviving the class+version filter). The cross-view delta-pair cache (`cachedDeltaVersion`/`cachedSkipSet`/`cachedInjectList` keyed on `mutationVersion`, diff ~1566) makes a second view at the same version O(1), so a pure read-only repeat after a hit pays nothing; the re-walk fires once per (entry, distinct mutation version observed) — i.e. once after each intervening write. SCALE CHECK — AT SMALL SCALE (M≈10): negligible. AT MEDIUM SCALE (M≈10K staged ops, K cacheable shapes repeated after writes): up to K×M scans, each sub-ms but multiplying. AT PRODUCTION SCALE: a write-heavy tx with a large `recordOperations` and many repeated shapes pays O(M) per shape per mutation-version-advance. VERDICT: MATTERS AT SCALE for write-heavy transactions, but is the explicitly-accepted v1 cost per Decision D1 ("~10-20x more raw ops than eager … sub-ms against HTTP latency"; ">5% D13 regression promotes a v2 per-class index"). Suggestion, not should-fix, because the design knowingly defers it behind the D13 measurement gate; reported so the gate has a named target.

**Suggestion**: No change for v1. If the D13 Hub-replay shows a write-heavy regression, the per-class mutation index (a `Map<className, List<RecordOperation>>` maintained on `addRecordOperation`, queried by `effectiveFromClasses`) turns the O(M) scan into O(d). Keep the existing loop comment pointing at this lever.

---

### PF3 [suggestion] WHERE re-eval is O(delta) but allocates a ResultInternal per injected row

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilder.java` (lines ~1621-1651 in the diff).

**Issue**: For each surviving CREATED/UPDATED op that matches WHERE, the build allocates `new ResultInternal(session, record)` and adds it to `injectList`; the list is then sorted with `orderBy.compare` (O(items) per comparison, SQLOrderBy.java:69). The WHERE re-eval itself is correctly O(delta) — one `matchesFilters` per surviving op, not O(cached x delta) — so the brief's worst-case concern (quadratic re-eval) does not occur. The remaining cost is the per-row allocation and the sort, both proportional to the post-populate delta size d, not the cached result size.

**Evidence**: See cert C3. COST TRACE per fresh delta build (d = surviving matched ops): d `ResultInternal` allocations; O(d log d) comparisons x O(orderByItems) per compare in the sort; d `matchesFilters` calls (each one predicate-tree walk over one record). No O(cached) or O(cached x delta) term — cached rows are skip-set-filtered by RID membership (`isSkipped` -> `delta.shouldSkip` -> HashSet `contains`, O(1)), never re-run through WHERE. SCALE CHECK: d is bounded by post-populate mutations touching the query's classes, small in the read-mostly Hub pattern this feature targets. AT PRODUCTION SCALE with small d this is trivial; only a tx that mutates thousands of in-class records between two identical queries makes the d-proportional allocation visible — the same write-heavy corner as PF2. VERDICT: NEGLIGIBLE in the target workload; MATTERS AT SCALE only in the PF2 corner. Suggestion.

**Suggestion**: No change for v1. The `injectList.size() > 1` guard already skips the comparator allocation for the common 0/1-row delta. If a future profile shows the `ResultInternal` allocation dominating, the rows could be wrapped lazily in the cursor rather than eagerly in the builder, but that complicates the cross-view sharing contract and is not worth it absent data.

---

### PF4 [suggestion] CacheKey builds a HashMap and boxes indices on every positional query, including hits

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CacheKey.java` (`forArgs`, lines ~624-633 in the diff).

**Issue**: `serveThroughCache` builds the `CacheKey` before the lookup, so the key is allocated on every cacheable query whether it hits or misses. For a positional query with N args, `forArgs` allocates a `HashMap` and `put`s N entries with autoboxed `Integer` index keys (`normalized.put(i, args[i])` boxes `i`). The key, the map, and the index boxes are allocated per query even on a hit, where the design's value proposition is "skip re-execution." The no-arg path is already optimal (shared `Collections.emptyMap()`, no allocation), and the precomputed `hash` field avoids `Objects.hash` varargs — both good.

**Evidence**: See cert C4. COST TRACE per positional-arg cacheable query: 1 `CacheKey`, 1 `HashMap` (default 16-bucket table), N `Integer` boxes for i = 0..N-1 (the JDK small-int cache covers i < 128, so for typical N these box to shared instances, not fresh allocations), plus the arg-value references. The `HashMap` is the only guaranteed-fresh allocation. SCALE CHECK: at hundreds-to-thousands of queries per request, hundreds-to-thousands of short-lived `HashMap` allocations per request on the hit path — young-gen garbage, collected cheaply. AT PRODUCTION SCALE this is minor GC pressure, not a latency cliff. VERDICT: MATTERS AT SCALE marginally (GC pressure), NEGLIGIBLE for latency. Suggestion.

**Suggestion**: Two options, both optional for v1: (a) for the common small-N positional case, key on a value-based wrapper over the raw `Object[]` (array-backed key with `Arrays.equals`/`Arrays.hashCode`) instead of normalizing into a `HashMap`, eliminating the map allocation; or (b) accept it as the inherent cost of `(AST, params)` keying. The map normalization exists so a positional call and an index-keyed map call collide on one entry (tested by `positionalArgsAndIndexKeyedMapNormaliseToSameKey`); option (a) must preserve that equivalence, so it is only worth doing if a profile shows key allocation matters.

## Evidence base

#### C1 — Disabled path re-reads config every query [CONFIRMED-as-issue]

CONFIRMED. `queryResultCache` field stays null when disabled (FrontendTransactionImpl.java diff ~2753, `@Nullable private QueryResultCache queryResultCache;` never assigned on the disabled path). `getQueryResultCache()` (diff ~2867) guards `if (queryResultCache == null) { if (!...getValueAsBoolean()) return null; ... }`, so the null branch is taken every call and `getValueAsBoolean()` (GlobalConfiguration.java:1624, direct read: field-read + `instanceof Boolean`, no allocation) fires per query. `serveThroughCache` calls `tx.getQueryResultCache()` then short-circuits on `cache == null`. The missed contract ("single boolean check") is quoted in the review brief. Severity should-fix on contract grounds; wall-clock is negligible.

#### C2 — Delta build O(total mutations) [CONFIRMED-as-issue, accepted-v1-cost]

CONFIRMED but design-accepted. `DeltaBuilder.buildForRecord` (diff ~1580) loops `tx.getRecordOperationsInternal()`. Direct read: returns `Collection<RecordOperation>` (FrontendTransactionImpl.java:1438) backed by `recordOperations` `HashMap` (field line 86), so iteration is O(M total staged mutations) across all classes; the class filter is applied inside the loop, so the scan visits every op. Cross-view fast path (diff ~1566) makes repeat views at the same version O(1). The loop comment names the per-class index as the deferred v2 lever, consistent with plan D1 and the D13 gate. Reported as suggestion so D13 has a named target.

#### C3 — WHERE re-eval is O(delta), not O(cached x delta) [CONFIRMED-not-a-defect]

Verified the brief's worst-case concern does NOT occur. Cached rows are filtered by RID skip-set membership (`isSkipped` -> `delta.shouldSkip` -> `skipSet.contains`, HashSet O(1)), never re-run through `matchesFilters`. `matchesFilters` runs once per surviving delta op (diff ~1608), so WHERE re-eval is O(d). Remaining cost is d `ResultInternal` allocations + O(d log d x orderByItems) sort (SQLOrderBy.compare is O(items), SQLOrderBy.java:69, verified). All d-proportional; d is small in the target read-mostly workload. Suggestion, overlaps PF2's write-heavy corner.

#### C4 — Per-query CacheKey allocation [CONFIRMED-as-issue, minor]

CONFIRMED. `forArgs` (CacheKey.java diff ~624) allocates a `HashMap` and `put`s boxed indices on the non-empty positional path; runs before lookup so it fires on hits too. No-arg path uses shared `emptyMap()` (diff ~610/626, no allocation). `hash` precomputed without varargs (diff ~617). Small-int index boxes hit the `Integer` cache for typical N. Net guaranteed fresh allocation per positional query = one `HashMap`. Young-gen GC pressure at high query rates; not a latency issue. Suggestion.

#### Reference-accuracy note

mcp-steroid PSI (`steroid_execute_code` find-usages on `addRecordOperation` and `getRecordOperationsInternal`) timed out twice this session, matching the documented pattern in track-1.md (Surprises: "PSI timed out this session"). The hot-path classification of `addRecordOperation` (every CREATE/UPDATE/DELETE) and the `query()` overloads (every read) rests on the implementation plan's Integration Points section and the track file's Context-and-Orientation, plus direct source reads of the cited line ranges — not PSI. No finding above depends on an exact caller count: PF1/PF4 are per-`query()` (the entry method itself), PF2/PF3 are per-delta-build inside the cache. The `mutationVersion` bump in `addRecordOperation` (diff ~2779, ~2793) is a plain `++mutationVersion` long increment — verified by direct read to be a single increment, not a heavier operation — so it adds no measurable mutation-path cost and is not a finding.
