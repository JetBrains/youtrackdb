<!-- conventions-execution.md §2.5 review-file schema; file-when-handed-a-path mode -->
<!--REVIEW_MANIFEST_START
role: reviewer-technical
phase: 3A
track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
iteration: 1
verdict: changes-requested
findings: 6
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: "track-2.md §Plan of Work step 5 / §Purpose; ShapeClassifier.java:116-162"
    cert: "Premise: ShapeClassifier already classifies aggregate shapes"
    basis: read
  - id: T2
    sev: should-fix
    anchor: "T2"
    loc: "track-2.md §Plan of Work steps 1-5; SQLFunctionAverage.java:91-115"
    cert: "Premise: AVG via PropertyTypeInternal.increment (D19)"
    basis: read
  - id: T3
    sev: should-fix
    anchor: "T3"
    loc: "track-2.md §Carried-from-Track-1 note; CoreMetrics.java:39-46, QueryResultCache.java:107-142, QueryCacheMetrics.java"
    cert: "Premise: QUERY_CACHE_*_RATE metrics defined but never incremented"
    basis: read
  - id: T4
    sev: should-fix
    anchor: "T4"
    loc: "track-2.md §Carried-from-Track-1 note + §Interfaces; CachedEntry.java:258-287"
    cert: "Edge case: maxRecordsPerEntry cap routing for aggregate material"
    basis: read
  - id: T5
    sev: suggestion
    anchor: "T5"
    loc: "track-2.md §Context line 'first value stored as-is'; SQLFunctionSum.java:66-76"
    cert: "Premise: SUM seeds first value verbatim, not via increment"
    basis: read
  - id: T6
    sev: suggestion
    anchor: "T6"
    loc: "track-2.md §Plan of Work step 3-4; DatabaseSessionEmbedded.java:842-885"
    cert: "Integration: aggregate miss path vs populateAndBuildView stream-lift contract"
    basis: read
evidence_base:
  premises: 9
  edge_cases: 2
  integrations: 2
  tooling: "mcp-steroid execute_code non-functional this session (every call incl. trivial ping timed out at the ~60s MCP HTTP cap despite list_projects/list_windows succeeding and indexingInProgress=false). Fell back to find + Read per the prompt's unreachable-IDE clause. All class-existence premises confirmed via unambiguous single-match find (non-worktree path, package matches reconstructed FQN); reference-accuracy caveat applies to caller/usage claims (T3, T6)."
REVIEW_MANIFEST_END-->

# Track 2 Technical Review — Iteration 1

One-line summary: Track is feasible against the as-built Track 1 foundation; all named classes and the D19/D20/D21 primitives resolve, but three should-fix items correct stale carry-over instructions and an over-broad classifier slot that becomes load-bearing once aggregates cache, plus two AVG/SUM parity nuances the Plan of Work understates.

## Findings

### T1 [should-fix]
**Certificate**: Premise — ShapeClassifier already classifies aggregate shapes
**Location**: track-2.md §Plan of Work step 5 ("Wire `ShapeClassifier` aggregate gates (single aggregate, no GROUP BY/HAVING, no expression arg, no SKIP/LIMIT; `COUNT(DISTINCT prop)` → AGGREGATE_COUNT_DISTINCT, expression-DISTINCT → K0_NONE)"); `ShapeClassifier.java:67-198`.
**Issue**: Track 1 already implemented every aggregate classifier gate this step describes. `classifySelect` routes SKIP/LIMIT and GROUP BY/LET/UNWIND/subquery to K0_NONE (lines 71-83), `singleAggregateShape` returns the `AGGREGATE_*` shape for a single recognised aggregate (lines 116-129), `aggregateShapeForCall` maps `count(distinct(...))` → `AGGREGATE_COUNT_DISTINCT` and SUM/AVG/MIN/MAX directly (lines 170-185), and `projectionContainsAggregate` falls a mixed/expression aggregate to K0_NONE (lines 93-95). The Plan of Work overstates the classifier work as if it is greenfield; the decomposer should not re-implement it. The genuine remaining classifier gap is the over-broad `firstFunctionCall` match (see below), not the gates listed.

Sub-issue (the real classifier work): `topLevelFunctionCall`/`firstFunctionCall` (lines 139-162) return the *first* function call in the subtree even when buried under arithmetic — the Javadoc at lines 131-137 explicitly admits `count(*) + 1` is returned and notes "such a shape is bypassed (uncached) in this foundation regardless, so the looser match is harmless here." Track 2 wires the AGGREGATE_* path, so that looseness stops being harmless: `SELECT count(*) + 1 FROM C` would now classify as `AGGREGATE_COUNT` and cache a scalar that is not what the projection returns. The classifier must additionally verify the aggregate call is the *root* of the single projection item (not nested under arithmetic) before returning an `AGGREGATE_*` shape, routing `count(*) + 1` to K0_NONE.
**Proposed fix**: Trim Plan of Work step 5 to state the classifier gates already exist (Track 1) and that the only classifier change is tightening `singleAggregateShape` to reject an aggregate that is not the top-level node of the projection item's expression (so expression-wrapped aggregates fall to K0_NONE). Add an I4 test for `count(*) + 1` / `sum(a) * 2` asserting K0_NONE (cache-on ≡ cache-off).

### T2 [should-fix]
**Certificate**: Premise — AVG via PropertyTypeInternal.increment (D19)
**Location**: track-2.md §Purpose ("SUM/AVG fold through the same `PropertyTypeInternal.increment`"), §Plan of Work step 1 ("SUM/AVG via `PropertyTypeInternal.increment`"); `SQLFunctionAverage.java:39-115`.
**Issue**: AVG storage parity needs more than `increment`. `SQLFunctionAverage` keeps two pieces of state — `Number sum` *and* `int total` — and `computeAverage` (lines 101-115) does **type-dependent division**: `Integer`/`Long` sums use integer division (`iSum.intValue() / iTotal`, `iSum.longValue() / iTotal`), `Float`/`Double` use floating division, `BigDecimal` uses `divide(…, HALF_UP)`. An `AggregateState` that only re-folds the running `sum` through `increment` and divides by a count will diverge from storage whenever the input is integral (e.g. AVG over Long column truncates toward zero in storage but a naive `sum.doubleValue()/n` would not). The Plan of Work step 1 mentions `total` for MIN/MAX recompute but not for AVG, and does not call out the integer-division and HALF_UP-rounding semantics that bit-for-bit parity (D19) requires.
**Proposed fix**: In Plan of Work step 1, state that `AggregateState` for AVG must hold both `sum` (folded via `increment`, first value seeded verbatim per T5) and an `int total`, and must reproduce `SQLFunctionAverage.computeAverage` exactly — including integer division for Integer/Long sums and `BigDecimal.divide(total, RoundingMode.HALF_UP)`. Add an I4 case: AVG over an all-Long column with a non-evenly-divisible total, asserting the cached scalar equals the truncating-division fresh result.

### T3 [should-fix]
**Certificate**: Premise — QUERY_CACHE_*_RATE metrics defined but never incremented
**Location**: track-2.md §"Carried from Track-1 Phase C review" ("Track 1 defines and registers them in `CoreMetrics` but never increments them, and the 'wiring lands in later steps' comment is now stale"); `CoreMetrics.java:39-46`, `QueryCacheMetrics.java`, `QueryResultCache.java:107-142`.
**Issue**: The carry-over instruction is partly stale and conflates two metric layers. There are two distinct surfaces: (a) the **per-tx** `QueryCacheMetrics` holder (`incrementHits/Misses/SpliceFailures/K0Invalidations/Overflows`), and (b) the **global** `CoreMetrics.QUERY_CACHE_HIT_RATE` / aggregate-rate `MetricDefinition`s. Contrary to the note, the per-tx counters ARE already incremented in Track 1: `QueryResultCache.lookup` calls `incrementMisses` (line 123), `incrementK0Invalidations` (132), `incrementHits` (139), and eviction calls `incrementOverflows` (180, 206). What is genuinely unwired is (i) the bridge from per-tx counters to the global `CoreMetrics.QUERY_CACHE_*_RATE` rates (the "increment/record wiring lands in later steps" comment at `CoreMetrics.java:40-41`), and (ii) `incrementSpliceFailures` — which has no call site because no splice exists yet (genuinely Track 2 work, tied to step 3's fallback). The carry-over as written would send the implementer hunting for missing hit/miss increments that already exist.
**Proposed fix**: Reword the carry-over note to: per-tx hit/miss/k0/overflow increments already exist in Track 1's `QueryResultCache`; Track 2 owns (1) calling `incrementSpliceFailures()` on the aggregate-splice fallback path, and (2) bridging the per-tx `QueryCacheMetrics` counters to the global `CoreMetrics.QUERY_CACHE_*_RATE` rates where the tx records its outcome (decide the recording site — likely tx-end or per-lookup). Keep the `recordPulledRow` / `inFlightLookup` / `exitCacheCodeUnchecked` cautions unchanged; they are accurate.

### T4 [should-fix]
**Certificate**: Edge case — maxRecordsPerEntry cap routing for aggregate material
**Location**: track-2.md §"Carried from Track-1 Phase C review" ("Route every per-RID/row append through `CachedEntry.recordPulledRow` so the `maxRecordsPerEntry` cap … applies to aggregate material"); `CachedEntry.java:155-157, 252-287`.
**Issue**: `recordPulledRow` (lines 268-287) appends to `results` (`List<Result>`) and `cachedRids`, then checks `results.size() > maxRecordsPerEntry`. Aggregate material is **not** rows in `results` — it is `contributingValues` / `contributingRids` / `distinctBuckets` on the new `AggregateState`. Routing aggregate per-RID material through `recordPulledRow` would (a) pollute `results`/`cachedRids` with per-contributor entries that the aggregate view never replays (its view returns a single `toResult()` row), and (b) size-bound on the wrong collection if `AggregateState` keeps its own structures. The carry-over instruction, taken literally, is unsound for the aggregate shape. The cap intent (bound per-entry memory for the contributing-RID material the tap accumulates) is valid, but the mechanism must be an aggregate-specific cap on the `AggregateState` structures, not `recordPulledRow`.
**Proposed fix**: Add a Decision-Log note or amend Plan of Work step 4: the `maxRecordsPerEntry` cap for aggregate entries is enforced on the size of `AggregateState`'s contributing structures (`contributingRids` / `distinctBuckets` total), not by funnelling contributors through `recordPulledRow`. On overflow, fire the same `onOverflow` callback the RECORD path uses (evict + route key non-cacheable) so the bound and the non-cacheable routing stay uniform. Keep `recordPulledRow` for the single scalar row only (or document that the aggregate view's lone `toResult()` row is the one `results` entry). Reword the carry-over so it does not say "route every per-RID append through `recordPulledRow`" for aggregates.

### T5 [suggestion]
**Certificate**: Premise — SUM seeds first value verbatim, not via increment
**Location**: track-2.md §Context line 48-52, §Plan of Work step 1; `SQLFunctionSum.java:66-76`, `SQLFunctionAverage.java:72-83`.
**Issue**: Both `SQLFunctionSum.sum` and `SQLFunctionAverage.sum` store the **first** non-null value verbatim (`sum = value`) and only call `PropertyTypeInternal.increment(sum, value)` from the second value onward. This matters for bit-for-bit parity: if `AggregateState.observe` folds the first value through `increment(seed, value)` against a typed zero seed, the result type can differ from storage (e.g. `increment(Integer 0, Long 5)` promotes to Long, but storage keeps the first value's own type until a wider value arrives). The Plan of Work says "SUM/AVG via `PropertyTypeInternal.increment`" without noting the verbatim-first-value rule. Minor because the track already commits to "the identical primitive," but the seeding asymmetry is the subtle part most likely to be missed.
**Proposed fix**: Add one sentence to Plan of Work step 1: `AggregateState` SUM/AVG must seed the first observed value verbatim (matching `SQLFunctionSum.sum`'s `sum == null` branch) and fold subsequent values through `increment`, never starting from a typed zero. Cover with the existing mixed-input I4 case plus a single-value SUM over a non-default-typed column.

### T6 [suggestion]
**Certificate**: Integration — aggregate miss path vs populateAndBuildView stream-lift contract
**Location**: track-2.md §Plan of Work steps 3-4, §Compatibility; `DatabaseSessionEmbedded.java:733-885`.
**Issue (reference-accuracy caveat: caller analysis via Read, not PSI — IDE execute_code was non-functional this session)**: Track 1's miss path (`populateAndBuildView`, lines 842-885) lifts the live stream off a `LocalResultSet` produced by `statement.execute(...)` and stores it for lazy pull. The aggregate path described in steps 3-4 diverges fundamentally: it builds the plan via `statement.createExecutionPlan(ctx, false)`, splices the tap, eagerly drives, and caches a scalar + `AggregateState` — there is no `LocalResultSet` to lift a stream from, and the entry holds no live stream (the `CachedEntry` constructor at `CachedEntry.java:113-131` accepts `@Nullable ExecutionStream stream`, so a null-stream aggregate entry is structurally allowed). The track does not state *where* in `serveThroughCache` the aggregate branch attaches. Currently line 791 (`shape != RECORD && shape != K0_NONE`) routes all AGGREGATE_* to `executeUncached`; Track 2 must split that gate so AGGREGATE_* shapes go to a new aggregate populate path instead of bypassing, while preserving the two-guard bracket (`enterCacheCode` / `viewOwnsGuard` / `exitCacheCode` finally at lines 774-814) and the `viewOwnsGuard = result instanceof CachedResultSetView` ownership transfer. The eager-drive note ("`plan.start(ctx).next(ctx)`") is correct in effect because `AggregateProjectionCalculationStep` is blocking (`executeAggregation` at lines 121-153 drains all upstream during `start`, so the tap observes every contributor before the single result row is produced) — but the track should state that `plan.start(ctx)` itself triggers the full drain, not the subsequent `.next()`.
**Proposed fix**: In Plan of Work step 3, specify the integration point precisely: split the `serveThroughCache` shape gate (currently `DatabaseSessionEmbedded.java:791`) so AGGREGATE_* shapes route to the new aggregate-populate method (not `executeUncached`), and confirm the new path returns a `CachedResultSetView` so the existing `viewOwnsGuard` transfer and the two-guard finally remain correct. On splice fallback, return the plain `LocalResultSet` AND release the guard the same way the existing populate fallback does (`viewOwnsGuard` stays false → `exitCacheCode` in finally). In step 4, clarify that the blocking aggregate step drains during `plan.start(ctx)`, so the tap sees all contributors there; `.next()` only retrieves the already-computed scalar row.

## Evidence base

#### Premise: AggregateProjectionCalculationStep exists and is the splice point
- **Track claim**: "`AggregateProjectionCalculationStep` (`internal/core/sql/executor/`, ≈121-137) runs the blocking aggregation loop … The side-tap splices immediately upstream of it."
- **Search performed**: `find -name AggregateProjectionCalculationStep.java` (PSI execute_code non-functional this session); Read of the file.
- **Code location**: `core/src/main/java/.../sql/executor/AggregateProjectionCalculationStep.java:58, 121-153`
- **Actual behavior**: `public class AggregateProjectionCalculationStep extends ProjectionCalculationStep` (NOT `extends AbstractExecutionStep` directly, but `ProjectionCalculationStep extends AbstractExecutionStep`, confirmed `ProjectionCalculationStep.java:43`). `executeAggregation` (121-153) calls `prev.start(ctx)` then drains `while (lastRs.hasNext(ctx))` — a blocking step. `prev` is the inherited public field on `AbstractExecutionStep` (`AbstractExecutionStep.java:66`, `setPrevious` at 85-86), so rewiring its `prev` to a tap is mechanically valid.
- **Verdict**: CONFIRMED
- **Detail**: Splice feasible; the cited "≈121-137" maps to the `executeAggregation` body. The blocking-drain semantics underpin the eager-drive correctness (T6).

#### Premise: AggregateCacheTapStep extends AbstractExecutionStep (new)
- **Track claim**: "`AggregateCacheTapStep extends AbstractExecutionStep` — `internalStart(ctx): ExecutionStream`."
- **Search performed**: `find -name AggregateCacheTapStep.java` → no match (expected, new); Read of `AbstractExecutionStep.java`.
- **Code location**: NOT FOUND (planned by this track); `AbstractExecutionStep.java:59, 130-149`
- **Actual behavior**: `public abstract class AbstractExecutionStep implements ExecutionStepInternal`; concrete steps implement `internalStart(CommandContext): ExecutionStream` (abstract; `start()` wraps it with profiling at 130-149). A tap wrapping `prev.start(ctx)` and forwarding-after-observe is a standard decorator over the upstream `ExecutionStream`.
- **Verdict**: CONFIRMED (planned by this track)
- **Detail**: Key signature `internalStart(ctx): ExecutionStream` correct.

#### Premise: AggregateState (new)
- **Track claim**: New class with `observe`/`applyMutation(RecordAbstract, byte status, boolean matchAfter)`/`copy`/`toResult`.
- **Search performed**: `find -name AggregateState.java` → no match (expected, new).
- **Code location**: NOT FOUND (planned by this track)
- **Actual behavior**: n/a; the `applyMutation` parameter types are validated against the real `RecordOperation` below.
- **Verdict**: CONFIRMED (planned by this track)

#### Premise: PropertyTypeInternal.increment(Number, Number): Number exists
- **Track claim**: "`PropertyTypeInternal#increment(Number current, Number value): Number` (existing, reused)."
- **Search performed**: grep + Read of `PropertyTypeInternal.java`.
- **Code location**: `core/src/main/java/.../metadata/schema/PropertyTypeInternal.java:1782`
- **Actual behavior**: `public static Number increment(final Number a, final Number b)`; throws `IllegalArgumentException` on null arg; switch over types with documented overflow-promotion (Integer+Integer overflow → Long), BigDecimal widening, etc.
- **Verdict**: CONFIRMED
- **Detail**: Signature exact. Used by `SQLFunctionSum.sum` (line 73) and `SQLFunctionAverage.sum` (line 80) — the storage path the track must mirror.

#### Premise: SUM/AVG fold semantics (D19)
- **Track claim**: "`AggregateState.observe` calls the identical primitive so cache replay matches storage."
- **Search performed**: Read `SQLFunctionSum.java`, `SQLFunctionAverage.java`.
- **Code location**: `SQLFunctionSum.java:66-76`, `SQLFunctionAverage.java:72-115`
- **Actual behavior**: First non-null value stored verbatim (`sum == null → sum = value`); subsequent via `increment`. AVG additionally keeps `int total` and `computeAverage` does type-dependent division (integer division for Integer/Long, HALF_UP for BigDecimal).
- **Verdict**: PARTIAL
- **Detail**: Produced T2 (AVG total + integer-division parity) and T5 (verbatim-first-value seeding). The "identical primitive" claim is necessary but not sufficient.

#### Premise: COUNT(DISTINCT prop) parses as count(distinct(prop)); SQLFunctionDistinct uses raw-equals Set (D20)
- **Track claim**: "`SQLFunctionDistinct.getResult` uses `LinkedHashSet<Object>` with raw `Object.equals`/`hashCode` … `distinctBuckets: Map<Object, Set<RID>>` mirrors that (D20)."
- **Search performed**: Read `SQLFunctionDistinct.java`; grep YouTrackDBSql.jj for DISTINCT (lines 3318, 3835); Read `ShapeClassifier.isDistinct` (188-198).
- **Code location**: `SQLFunctionDistinct.java:37, 51-59`; `ShapeClassifier.java:177-198`
- **Actual behavior**: `private final Set<Object> context = new LinkedHashSet<Object>()`; `execute` adds via `!context.contains(value)` — raw `Object.equals`/`hashCode`, so `Long(5)` ≠ `Integer(5)`. Grammar turns bare `<DISTINCT>` into a `SQLFunctionCall` named "distinct" (line 3835), so `COUNT(DISTINCT prop)` is the nested call `count(distinct(prop))`, which `ShapeClassifier.isDistinct` already detects.
- **Verdict**: CONFIRMED
- **Detail**: D20's raw-equals bucket model matches storage. Note `SQLFunctionDistinct` is the collection `distinct()`; for `COUNT(DISTINCT)` the count counts the distinct-filtered stream — the track's per-value RID-bucket model is the right replay primitive.

#### Premise: RecordOperation carries version + byte type + RecordAbstract record (D5/D21)
- **Track claim**: `applyMutation(RecordAbstract record, byte status, boolean matchAfter)`; membership-based dispatch over collapsed ops; `op.version` filter.
- **Search performed**: grep `import RecordOperation` in FrontendTransactionImpl + DeltaBuilder; Read both `tx.RecordOperation` and `db.record.RecordOperation`.
- **Code location**: `core/src/main/java/.../db/record/RecordOperation.java:29-54` (the active class); `tx/RecordOperation.java` (an unrelated 2-arg record, NOT used by the cache).
- **Actual behavior**: `db.record.RecordOperation` is `final class` with `public byte type` (DELETED=1/UPDATED=2/CREATED=3), `public final RecordAbstract record`, `public long version` (Javadoc: "version > populateMutationVersion to skip changes a cached entry already observed"). `FrontendTransactionImpl` and `DeltaBuilder` both import this one; `getRecordOperationsInternal(): Collection<RecordOperation>`.
- **Verdict**: CONFIRMED
- **Detail**: `applyMutation(RecordAbstract, byte status, …)` parameter types match exactly (record is RecordAbstract, status is byte). The collapse/membership-dispatch premise holds: `addRecordOperation` collapses CREATE+UPDATE and re-stamps `version`, so deriving before-state from `contributingValues.containsKey(rid)` rather than `op.type` (which may read CREATED post-collapse) is the correct D21 safety. Two `RecordOperation` classes exist — a name-collision trap; the cache uses `db.record.*`, not `tx.*`.

#### Premise: ShapeClassifier already classifies all aggregate shapes
- **Track claim**: Plan of Work step 5 "wire `ShapeClassifier` aggregate gates."
- **Search performed**: Read `ShapeClassifier.java` in full.
- **Code location**: `ShapeClassifier.java:67-198`
- **Actual behavior**: `classifySelect` already routes SKIP/LIMIT, GROUP BY/LET/UNWIND/subquery → K0_NONE; `singleAggregateShape` + `aggregateShapeForCall` already return AGGREGATE_COUNT/SUM/AVG/MIN/MAX/COUNT_DISTINCT; `projectionContainsAggregate` routes mixed/expression aggregates → K0_NONE. `CacheableShape` enum already declares all AGGREGATE_* constants.
- **Verdict**: WRONG (track overstates the work as greenfield)
- **Detail**: Produced T1. Remaining classifier work is narrow: tighten `firstFunctionCall`'s admitted over-broad match so an aggregate buried under arithmetic (`count(*) + 1`) falls to K0_NONE rather than caching a wrong scalar.

#### Premise: CachedEntry has no aggregateState field; constructor accepts null stream/plan
- **Track claim**: §Interfaces "`CachedEntry` (aggregateState field, if not already added in Track 1)."
- **Search performed**: grep + Read `CachedEntry.java:55-131, 252-287`.
- **Code location**: `CachedEntry.java:55-131`
- **Actual behavior**: Fields are shape/results/cachedRids/effectiveFromClasses/whereClause/orderBy/populateMutationVersion/stream/plan/ctx — no `aggregateState`. Constructor (113-131) takes `@Nullable ExecutionStream stream`, `@Nullable InternalExecutionPlan plan`, `@Nullable CommandContext ctx` — a null-stream aggregate entry is structurally allowed. `recordPulledRow` (268-287) appends to `results`/`cachedRids` and enforces the cap there.
- **Verdict**: CONFIRMED (aggregateState is this track's addition)
- **Detail**: Produced T4 (cap-routing mismatch) and informs T6 (null-stream aggregate entry is allowed).

#### Edge case: maxRecordsPerEntry overflow for aggregate contributing material
- **Trigger**: An aggregate over a large class accumulates more contributing RIDs/values than `maxRecordsPerEntry`.
- **Code path trace**:
  1. `CachedEntry.recordPulledRow(r)` @ `CachedEntry.java:268` — appends to `results`, checks `results.size() > maxRecordsPerEntry`.
  2. Aggregate material lives in `AggregateState.contributingValues/Rids/buckets`, not `results` — so routing it through `recordPulledRow` mis-sizes and pollutes `results`/`cachedRids`.
- **Outcome**: Cap silently ineffective (or wrong-collection eviction) for aggregate material if the carry-over is followed literally.
- **Track coverage**: no — the carry-over assumes RECORD-shaped `results`.

#### Edge case: aggregate splice fallback leaks the cache-code guard
- **Trigger**: Planner emits a shape with no `AggregateProjectionCalculationStep`; the track falls back to uncached `LocalResultSet`.
- **Code path trace**:
  1. `serveThroughCache` @ `DatabaseSessionEmbedded.java:774` — `enterCacheCode()` bumps depth.
  2. Aggregate populate path (new) must set `viewOwnsGuard = false` on fallback (no `CachedResultSetView`), so the finally at 805-813 calls `exitCacheCode()` exactly once.
  3. If the new path forgets the ownership rule, the depth bump leaks for the rest of the tx (cache disabled).
- **Outcome**: Correct iff the new path mirrors the existing populate fallback's `viewOwnsGuard = result instanceof CachedResultSetView` (line 803).
- **Track coverage**: partial — fallback is described (step 3) but the guard-release contract is not stated. Folded into T6.

#### Integration: serveThroughCache shape gate
- **Plan claim**: Steps 3-4 build the plan via `createExecutionPlan`, splice, eager-drive; fallback to `LocalResultSet`.
- **Actual entry point**: `DatabaseSessionEmbedded.serveThroughCache:733-815`; shape gate at line 791 currently bypasses all non-RECORD/non-K0_NONE shapes to `executeUncached`. `populateAndBuildView:842-885` is the RECORD/K0_NONE populate path (lifts stream off `LocalResultSet`).
- **Caller analysis (reference-accuracy caveat — Read, not PSI)**: `serveThroughCache` is the shared entry for both `query()` overloads; `createExecutionPlan(ctx, boolean)` confirmed at `SQLSelectStatement.java:335` and `SQLStatement.java:111`; `LocalResultSet.getInternalExecutionPlan()/getStream()/setStream()` confirmed at `LocalResultSet.java:65-79`.
- **Breaking change risk**: Low for existing callers (aggregates currently bypass; the change only redirects a currently-uncached branch). Risk is internal: the new aggregate branch must preserve the two-guard bracket and `viewOwnsGuard` transfer.
- **Verdict**: MATCHES (with the integration-point specificity gap in T6)
