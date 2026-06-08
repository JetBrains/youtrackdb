<!-- workflow-sha: 8995acfc3b0c50453595911342427c60742617b4 -->
# Track 2: Aggregate + MATCH delta

> Combines former Tracks 5, 6a, 6b (aggregate delta, MATCH Etap A, MATCH partial Etap B), consolidated for footprint sizing. Test IDs (T6a–T6p, …) are unchanged from the former tracks.

## Purpose / Big Picture

BLUF: After this track, cached aggregate queries (`COUNT(*)`, `SUM`, `AVG`, `MIN`, `MAX`, `COUNT(DISTINCT prop)`) and cached MATCH queries (single-alias and multi-alias) reflect intra-tx mutations. Aggregates use per-query `AggregateState` copy-and-replay with bit-for-bit storage parity; single-alias MATCH (Etap A) reuses the RECORD delta-build path with a `returnProjector`; multi-alias MATCH (partial Etap B / `MATCH_TUPLE_MULTI`) survives DELETED + UPDATED via a per-tuple `reverseIndex` and tombstones on CREATED. All three shapes extend `ShapeClassifier` and reuse Track 1's `DeltaBuilder` machinery (Stage 4). Patterns that fail every gate fall through to K0_NONE (cacheable under D18's mutation-version gate).

## Stage 1 — Aggregate delta (AGGREGATE_* incl. COUNT_DISTINCT)

### Purpose / Big Picture

BLUF: After this track, cached aggregate queries (`COUNT(*)`, `SUM(prop)`, `AVG(prop)`, `MIN(prop)`, `MAX(prop)`, `COUNT(DISTINCT prop)`) reflect intra-tx mutations via per-query `AggregateState` copy-and-replay. Same applyMutation algorithm as eager for COUNT/SUM/AVG/MIN/MAX, driven from delta-build instead of mutation-time dispatch. SUM/AVG accumulator delegates to `PropertyTypeInternal.increment` mirroring storage exactly (D19 — bit-for-bit parity with fresh execution, including Long-overflow wrap and Long→Double precision loss at `2^53+1`). `COUNT(DISTINCT prop)` (D20 — extends coverage beyond eager's K0 fallback) uses per-value `distinctBuckets: Map<Object, Set<RID>>` keyed by `Object.equals`/`hashCode` directly (mirrors `SQLFunctionDistinct`'s `LinkedHashSet<Object>` semantics — Long(5) and Integer(5) are distinct, matching storage), with reverse lookup through `contributingValues`.

Extend `ShapeClassifier` to return `AGGREGATE_COUNT`/`SUM`/`AVG`/`MIN`/`MAX` for single-aggregate SELECT shapes. Add `AggregateCacheTapStep` and splice into the execution plan upstream of `AggregateProjectionCalculationStep` during cache-miss execution. Implement `DeltaBuilder.buildForAggregate` that copies the entry's aggregate state and replays `applyMutation` over relevant tx-mutations. Extend `CachedResultSetView` to carry per-view `deltaAggregateState` and return `deltaState.toResult()` directly.

### Context and Orientation

**Codebase state at track start.** After Track 1's RECORD delta core (Stage 4): RECORD-shape delta works end-to-end. This stage adds aggregate cacheability + delta replay using the same DeltaBuilder pattern.

Existing relevant code:
- `AggregateProjectionCalculationStep.java:121-137` — blocking aggregation loop: `prev.start(ctx)` then `while lastRs.hasNext: aggregate(lastRs.next, ctx, ...)`. Splice target for `AggregateCacheTapStep`.
- `AbstractExecutionStep` — base class for execution steps; provides public `prev` field (`AbstractExecutionStep.java:66`) for upstream-link traversal.
- `SelectExecutionPlan.steps` — concrete `List<ExecutionStepInternal>` field (`SelectExecutionPlan.java:54`); the cache-miss path obtains the plan via `statement.createExecutionPlan(ctx, false)` (rather than `statement.execute(...)`, which immediately wraps the plan in a `LocalResultSet`), downcasts to `SelectExecutionPlan`, mutates the `prev` link of the target step, then runs the plan to produce the result stream. `ExecutionPlan.getSteps()` (declared on the parent public interface at `ExecutionPlan.java:13`, inherited by `InternalExecutionPlan`) returns `List<ExecutionStep>` (read-only API); writable access requires the concrete type.

**Concrete deliverables.**
- `ShapeClassifier.classify` extended: single-aggregate-projection SELECT returns one of `AGGREGATE_COUNT/SUM/AVG/MIN/MAX/COUNT_DISTINCT`. `COUNT_DISTINCT` accepts only the plain-property form (`COUNT(DISTINCT prop)`); `COUNT(DISTINCT a+b)`, `COUNT(DISTINCT someFunction())`, multi-aggregate, expression-aggregate (`SUM(a+b)`), aggregate-in-where, GROUP BY, HAVING, `MEDIAN`/`MODE`/`PERCENTILE` → K0_NONE.
- `AggregateState` complete with `observe(result)` (called by tap), `applyMutation(rec, status, matchAfter)` (called by DeltaBuilder), `copy()` (called by DeltaBuilder at view ctor), `toResult()` (called by view.next). SUM/AVG variants carry `sumAccumulator: Number` whose type evolves through `PropertyTypeInternal.increment` matching storage; no pinned-type field — accumulator type IS the result type. AGGREGATE_COUNT_DISTINCT variant carries `distinctBuckets: Map<Object, Set<RID>>` keyed by raw `Object.equals` (storage-mirroring) and re-uses `contributingValues: Map<RID, Object>` for reverse lookup on UPDATED transitions.
- `AggregateCacheTapStep extends AbstractExecutionStep` — side-tap step. `internalStart(ctx)` calls `prev.start(ctx)` (reading the public `prev` field from `AbstractExecutionStep`) to get upstream stream, then returns wrapping stream whose `next(ctx)` calls `entry.aggregateState.observe(result)` before forwarding unchanged.
- Plan-rewrite splice in `DatabaseSessionEmbedded.query()` miss path: walk `plan.steps`, find `AggregateProjectionCalculationStep`, rewire its `prev` to a new `AggregateCacheTapStep` whose `prev` is the original upstream. Failure to find expected shape → entry shape = NONE (downgrade).
- `DeltaBuilder.buildForAggregate(entry, tx, ctx) → AggregateState`: take a snapshot `new ArrayList<>(tx.recordOperations.values())` first (T1 fix — same hazard as buildForRecord: UDF in WHERE may save()), copy `entry.aggregateState`, then iterate the snapshot, class filter, WHERE eval, `deltaState.applyMutation(rec, status, match_after)`. Returns the delta-applied copy.
- `CachedResultSetView` extended: for aggregate shape, carry `deltaAggregateState` instead of `TxDeltaCursor`. `next()` returns `deltaAggregateState.toResult()` once; `hasNext()` true exactly once.

### Plan of Work

1. `AggregateState.observe(result)` — for COUNT(*): `contributingRids.add(rec.rid); count++`. For SUM/AVG: if `sumAccumulator == null` (first observe), set `sumAccumulator = value` directly (mirrors `SQLFunctionSum.sum` lines 68-70 where the `sum == null` branch assigns raw `value`); else `sumAccumulator = PropertyTypeInternal.increment(sumAccumulator, value)` — same call storage makes, evolves the accumulator type by Java widening rules; store `contributingValues.put(rid, value)` (raw value retained for SUM/AVG `delta = new - old` recompute). For MIN/MAX: track `extremumRid` by RID identity (the RID currently holding the cached extremum value). RID identity (not `Number.equals`) sidesteps the cross-`Number`-subtype hazard (`Long.valueOf(5L).equals(Integer.valueOf(5))` returns `false` in Java) and gives ties unambiguous semantics — one RID owns the slot at any time; the next ties-recompute picks whichever survives. For COUNT_DISTINCT: bucket key is the raw value itself, no coercion — mirrors `SQLFunctionDistinct.getResult` which uses `LinkedHashSet<Object>` with default `Object.equals` semantics (Long(5) and Integer(5) ARE distinct in both storage and cache); `distinctBuckets.computeIfAbsent(value, k -> new HashSet<>()).add(rid); contributingValues.put(rid, value)`.
2. `AggregateState.applyMutation(rec, status, matchAfter)` — transition matrix per design.md §"Aggregate delta — AGGREGATE_* shapes":
   - COUNT: T→T no-op, F→F no-op, T→F decrement + remove from rids, F→T increment + add.
   - SUM/AVG: same matrix. Read `oldValue = contributingValues.get(rid)`, compute `newValue` via the property extractor when `matchAfter`. Storage-mirroring delta path: rather than maintain a subtract primitive on `PropertyTypeInternal` (which may not exist), each T→T / T→F / F→T transition that changes `contributingValues` triggers a full re-fold of `contributingValues.values()` through `PropertyTypeInternal.increment` from null — O(N) per transition but guarantees the accumulator matches what fresh `SQLFunctionSum.getResult` would produce for that exact contributor multiset. Hub-typical N=100-1000 × p=1-5 mutations → ~5000 increments per delta-build, ~50 μs at 10 ns/op. AVG additionally tracks `count` (increment for F→T, decrement for T→F, unchanged for T→T) and divides through `SQLFunctionAverage.getResult`'s same coercion. The earlier "delta = newValue - oldValue then add to accumulator" approach was abandoned because PropertyTypeInternal has no `subtract` primitive symmetric to `increment`, and a hand-rolled subtract would diverge from storage on Long-overflow boundaries.
   - MIN/MAX: same matrix, with O(n) recompute over `contributingValues` if `was_extremum = rid.equals(extremumRid)` is true and the new state loses the extremum direction. Otherwise O(1): compare new value to `currentScalar` and adopt if it wins (in which case `extremumRid` also flips to the new winner's RID). Bounded by `maxRecordsPerEntry`. The D14 sorted-value index for `O(log n)` consistent performance is v2-deferred per D14 cost-benefit; promotion gated on D13 measurement of extremum-churn frequency.
   - COUNT_DISTINCT: read `oldValue = contributingValues.get(rid)` for reverse lookup. For T→T with value change: compute `newValue` via property extractor; if `Objects.equals(oldValue, newValue)` no-op (value didn't change); else remove `rid` from `distinctBuckets.get(oldValue)` (if the resulting set is empty, remove the bucket from the map), add `rid` to `distinctBuckets.computeIfAbsent(newValue, ...)`, update `contributingValues.put(rid, newValue)`. For F→T: add to `distinctBuckets[newValue]` + `contributingValues.put(rid, newValue)`. For T→F: remove `rid` from `distinctBuckets[oldValue]` (cleanup empty bucket), `contributingValues.remove(rid)`. Bucket key is raw value (no coercion) — mirrors `SQLFunctionDistinct`'s `Object.equals` semantics. The published scalar `distinctBuckets.size()` is recomputed at `toResult()` time.
   - **Empty-set semantics (L3 fix)**: if `contributingValues.isEmpty()` (or `contributingRids.isEmpty()` for COUNT, or `distinctBuckets.isEmpty()` for COUNT_DISTINCT) after applyMutation, set `extremumRid = null` and `currentScalar = null` for MIN/MAX; `currentScalar = null, count = 0` and `sumAccumulator = null` for AVG; `sumAccumulator = null` for SUM (`toResult()` mirrors `SQLFunctionSum.getResult` empty-input return value, currently null); `count = 0` for COUNT and COUNT_DISTINCT (return 0). `toResult()` emits SQL `NULL` for MIN/MAX/AVG of empty set per SQL standard; SUM of empty set matches storage's `SQLFunctionSum` empty-input behavior; COUNT of empty set is 0; COUNT_DISTINCT of empty set is 0.
3. `AggregateState.copy()` — shallow-deep: new mutable containers (`new HashSet<>(contributingRids)`, `new HashMap<>(contributingValues)`) but reuse underlying RID and Number refs. For COUNT_DISTINCT: also `new HashMap<>()` for `distinctBuckets`, and for each bucket entry `new HashSet<>(originalBucket)`. The `sumAccumulator` `Number` is by Java convention immutable (Long/Integer/Double/BigDecimal are all immutable types) so no copy needed; the field reference is shared.
4. `AggregateCacheTapStep` — implement as transparent side-tap. Tests: tap observes every record that reaches the aggregate step; tap doesn't change downstream result.
5. Plan-rewrite splice — implement in `DatabaseSessionEmbedded.query()` miss path. **Failure fallback (L6 fix)**: if planner emits unexpected shape (no `AggregateProjectionCalculationStep` found after walking `SelectExecutionPlan.steps`), close the constructed plan (best-effort), increment `QueryCacheMetrics.spliceFailures`, fall back by calling `statement.execute(session, args)` to obtain a standard `LocalResultSet`, return that directly to the consumer (no cache entry created, no `CachedResultSetView` wrapping). Log a warning identifying the unexpected step types.
6. **Eager aggregate drive (L8 fix + SO4 exception-safety)**: on cache-miss for AGGREGATE_* shape, the post-splice path drives the plan to completion BEFORE wrapping in `CachedResultSetView`. Sequence: build `entry`, splice the tap, call `plan.start(ctx).next(ctx)` **inside a try block**; on successful drain, the entry's aggregateState is fully populated and immutable, the resulting aggregate Result is buffered, and `cache.put(key, entry)` happens AFTER drain success. On throw (storage IO exception, type coercion error, OOM during drain): the partial entry is NEVER inserted into `cache.entries`; the plan is closed (best-effort) and the exception re-thrown to the consumer. This prevents a stale partial-aggregateState entry from being read by subsequent views. `cache.put` must come after `plan.next()` succeeds — never before.
7. `DeltaBuilder.buildForAggregate` — copy-then-replay.
8. View extension — `CachedResultSetView` handles aggregate shape: single-row read of `deltaAggregateState.toResult()`.
9. Test matrix (T5 set):
   - T5a: COUNT(*) — CREATE matching/non-matching, UPDATE in/out of WHERE, DELETE matching.
   - T5b: SUM(prop) — same matrix + UPDATE changing prop value (T→T with delta).
   - T5c: AVG(prop) — same as SUM + count tracking.
   - T5d: MIN(prop) — UPDATE extremum to non-extremum value triggers O(n) recompute over `contributingValues` to find new extremum. UPDATE non-extremum (no recompute — O(1) `was_extremum` check returns false). Verify both paths produce the correct extremum across the transition matrix.
   - T5e: MAX(prop) — symmetric to T5d.
   - T5f: Aggregate expression (`SUM(a+b)`) — classify returns K0_NONE; cacheable under D18 version gate, not via AGGREGATE delta-build.
   - T5g: GROUP BY — K0_NONE.
   - T5h (L6 fallback): Plan-rewrite splice failure — use a planner-mock that returns a plan WITHOUT `AggregateProjectionCalculationStep`. Verify: (i) consumer receives a working `ResultSet` via `statement.execute(...)` fallback; (ii) no cache entry created; (iii) `QueryCacheMetrics.spliceFailures` incremented; (iv) no NPE, no resource leak.
   - T5i (I4 aggregate): aggregate result equivalent to fresh execution post-mutation.
   - T5j (L3 empty-set semantics): cache `SELECT MIN(age) FROM User WHERE active=true` with 5 matching records; tx deletes all 5; verify view returns SQL NULL (not 0, not stale value). Same for MAX/AVG. SUM returns 0. COUNT returns 0. COUNT_DISTINCT returns 0.
   - T5k (L8 eager drive): construct cache-miss view for COUNT, drop without calling `.next()`, then construct a second view of the same key — second view returns the correct count (not 0, not stale partial state). Asserts the eager-drive step 6 populates `entry.aggregateState` regardless of consumer behavior.
   - T5l (SO4 exception safety): inject a planner-mock that emits an `AggregateProjectionCalculationStep` whose `next(ctx)` throws `StorageIOException` mid-drain. Verify: (i) the exception propagates to the consumer, (ii) `cache.size() == 0` (the partial entry was NOT inserted), (iii) the spliced plan is closed (no stream leak), (iv) a subsequent retry of the same query goes through a fresh miss path without seeing stale partial state.
   - T5m (D19 SUM cross-subtype parity): populate `SELECT SUM(weight) FROM Box` where entries hold a mix of `Long` and `Integer` and `Double` weights; intra-tx UPDATE switches one record's weight from `Long(5)` to `Double(5.5)`. Run **parallel uncached** `SELECT SUM(weight)` after the mutation; assert `cached.equals(fresh) && cached.getClass() == fresh.getClass()` — same type, same value. Cover Long+Integer (Long result, possible overflow-wrap), Long+Double (Double result, precision loss at 2^53+1 — verify SAME loss in both paths via identical bit pattern check), Integer+Float (Float result), BigDecimal+Long (BigDecimal result, exact).
   - T5m' (D19 AVG cross-subtype parity): same as T5m but for AVG; assert cached and fresh `SELECT AVG(...)` return values are equal in type and value across the same mixed-input scenarios.
   - T5n (D20 COUNT_DISTINCT classify): `SELECT COUNT(DISTINCT name) FROM User WHERE active=true` classifies AGGREGATE_COUNT_DISTINCT; `SELECT COUNT(DISTINCT a+b) FROM ...` classifies K0_NONE (expression-DISTINCT not supported in v1); `SELECT COUNT(*) FROM ...` classifies AGGREGATE_COUNT (no regression).
   - T5o (D20 COUNT_DISTINCT delta — CREATED): cache with 3 records bearing distinct values {A, B, C} → count=3. Tx CREATEs a record with value A (existing bucket) → count stays 3, bucket A now holds 2 RIDs. Tx CREATEs a record with value D (new bucket) → count goes to 4.
   - T5p (D20 COUNT_DISTINCT delta — DELETED): cache with 3 records {A, A, B} → count=2 (buckets A:2, B:1). Tx DELETEs one A-record → count stays 2 (bucket A:1, bucket B:1 still). Tx DELETEs the last A-record → count drops to 1 (bucket A removed from map).
   - T5q (D20 COUNT_DISTINCT delta — UPDATED bucket move): cache with 2 records {A, B} → count=2. Tx UPDATEs the A-record's property to B → count drops to 1 (bucket A removed; bucket B now holds 2). Tx UPDATEs the second B-record back to A → count returns to 2 (bucket A re-created; bucket B keeps 1).
   - T5q' (D20 COUNT_DISTINCT storage-parity bucket key): cache with `Long(5)`, `Integer(5)`, `BigDecimal(5)` contributors. Cached count = **3** (Java `Object.equals` returns false across Number subtypes — Long(5), Integer(5), BigDecimal(5) are three distinct buckets). Tx CREATEs a `Long(6)` → count goes to 4. Run **parallel uncached** `SELECT COUNT(DISTINCT prop) FROM ...`; assert `cached.equals(fresh)` — both via storage's `SQLFunctionDistinct` `LinkedHashSet`-Object-equality, both produce 4. The earlier "count=1 via BigDecimal coercion" expectation was a fresh-vs-cached divergence baked into the pre-D19-rewrite design; corrected here.

**Invariants to preserve.** I4: view output equals fresh execution composed with tx-delta. I7: deltaAggregateState immutable post-construction (cache `entry.aggregateState` is the immutable source; `copy()` produces the view's mutable working copy).

**Out-of-scope files.**
- `MATCH` classify and projector — Stage 2 (Etap A) and Stage 3 (MATCH_TUPLE_MULTI).
- `AggregateProjectionCalculationStep` itself — splice rewires its `prev` only; the step is unchanged.

## Stage 2 — MATCH Etap A (single-alias as RECORD)

### Purpose / Big Picture

BLUF: After this track, cached single-alias MATCH queries reflect intra-tx mutations via the RECORD delta-build path with a `returnProjector` applied to each inject-list entry.

Extends `ShapeClassifier` to classify single-alias MATCH `MATCH {as:u, class:X WHERE simple-predicate} RETURN <projection of u>` as RECORD and to build a `returnProjector` closure at entry construction. Reuses Track 1's `DeltaBuilder.buildForRecord` + `OrderByComparator` machinery (Stage 4) — only the projector is new infrastructure. Patterns that fail the Etap A gate fall through to Stage 3's MATCH_TUPLE_MULTI gate; patterns that fail both classify as K0_NONE (cacheable under D18's mutation-version gate).

### Context and Orientation

**Codebase state at track start.** After Stage 1: RECORD and AGGREGATE delta work for SELECT. This stage adds the first MATCH path.

Existing relevant code:
- `SQLMatchStatement` — `matchExpressions: List<SQLMatchExpression>` and `returnItems: List<SQLExpression>`.
- Each `SQLMatchExpression` has `origin: SQLMatchFilter` (the start node) and `items: List<SQLMatchPathItem>` (the edges). Etap A condition: `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()`.
- `SQLMatchFilter` (origin / path-item filter) exposes accessors `getAlias()` / `getClassName(CommandContext)` / `getFilter()` over an internal `items: List<SQLMatchFilterItem>` (the parser breaks one `{as:u, class:X, where: …}` block into one or more items). For Etap A's no-edges single-binding case there is exactly one item; the accessors iterate items and return the first non-null match.

**Concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` Etap A branch — applies the conditions in Plan-of-Work step 1; if pass, returns RECORD and the entry is populated with the projector + alias-derived metadata.
- `returnProjector: Function<RecordAbstract, Result>` — closure built at entry construction. For RETURN clause like `RETURN u, u.name`, projector takes a record and produces `Result{u: rec, name: rec.name}` matching the original execution's output shape.
- Entry construction extension — MATCH-flavored RECORD entries store: `effectiveFromClasses = {origin.clazz} ∪ subclass closure` (D11), `whereClause = origin.filter`, `orderBy = MATCH's ORDER BY`, `returnProjector`.
- `DeltaBuilder.buildForRecord` — extended to call `entry.returnProjector(rec, ctx)` when constructing inject-list entries for MATCH-flavored RECORD entries. SELECT-flavored entries skip the projector (or use identity projector).
- `OrderByComparator` extension — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.

### Plan of Work

1. `ShapeClassifier.classify(SQLMatchStatement)` Etap A — condition check. Etap A iff:
   - `matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()` (single node, no edges)
   - `origin.getClassName(ctx)` is non-null
   - Origin's `where:` clause has no cross-alias-state references (`$current`, `$matched`, `${otherAlias}.…`)
   - No LET / UNWIND
   - No subqueries in pattern WHERE
   - Every `returnItem` resolves to an expression on the single alias (or its modifiers); references to `$matched`, `$current`, another alias → fall through to Stage 3's gate or K0_NONE
   If pass: classify returns RECORD with `entry.returnProjector` populated. Otherwise fall through to the MATCH_TUPLE_MULTI gate (Stage 3).

2. `returnProjector` builder — given `returnItems: List<SQLExpression>` and the alias name, build a closure that, on each invocation `(rec, ctx)`: (a) constructs a `ResultInternal` binding the record under the alias name (e.g., `Result{alias → rec}`); (b) sets `ctx.setVariable(alias, boundResult)` so that `SQLExpression.execute` resolves `alias.field` references correctly; (c) iterates `returnItems` and calls `expr.execute(boundResult, ctx)` for each, accumulating the (alias, value) pairs into the output Result. Without step (b) the binding is missing and `u.someProp + 1` would fail to resolve `u`.

3. `DeltaBuilder.buildForRecord` integration — flag on entry indicates "use returnProjector" path; defaults to identity for SELECT-flavored entries.

4. `OrderByComparator` for MATCH — projected tuples can have ORDER BY on either record properties (`ORDER BY u.name`) or projection aliases (`ORDER BY name`). Build comparator that resolves to the appropriate value in the projected `Result`.

5. Test matrix (T6 set, Etap A subset):
   - **T6a** (Etap A CREATE): single-alias MATCH; new record matching WHERE appears as tuple in view.
   - **T6b** (Etap A UPDATE): record's WHERE-relevant prop changes to/from matching.
   - **T6c** (Etap A DELETE): cached tuple disappears from view.
   - **T6d** (Etap A classify-NONE for cross-alias): cross-alias WHERE (`WHERE name = $matched.u.name`) — classify NONE (K0_NONE under D18, or MATCH_TUPLE_MULTI per Stage 3's gate; verify Etap A branch alone returns NONE for this shape).
   - **T6h** (L5 ctx-binding for Etap A): MATCH `{as:u, class:User WHERE active=true} RETURN u.name, u.age * 2 AS double_age` — Etap A delta CREATE produces a Result with correct `u.name` AND correct `double_age` from the projector closure.

**Invariants to preserve.** I4 for Etap A: view output equivalent to fresh MATCH execution against the (cached + tx-delta) snapshot for CREATED/UPDATED/DELETED on the single-alias class.

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — full Etap B (CREATED constrained-walk discovery) is a separate ADR.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.
- `MatchMultiDelta`, `buildForMatchMulti`, MATCH_TUPLE_MULTI shape — Stage 3.

## Stage 3 — MATCH partial Etap B (MATCH_TUPLE_MULTI)

### Purpose / Big Picture

BLUF: After this track, cached multi-alias MATCH queries (more than one pattern node, or any pattern node with edges, or cross-join with multiple top-level match-expressions) cache as a new shape `MATCH_TUPLE_MULTI` and survive DELETED + UPDATED mutations via a per-tuple `reverseIndex`; CREATED on a pattern class tombstones the entry and forces re-execution at next lookup.

Introduces `CacheableShape.MATCH_TUPLE_MULTI`, `MatchMultiDelta` (immutable per-view delta with `tupleSkipSet` + `ridSkipSet`), and `DeltaBuilder.buildForMatchMulti` (two-pass: tombstone pre-scan + skip-set build). Patterns with classless nodes / cross-alias-state / subqueries in pattern WHEREs classify as `K0_NONE` (cacheable under D18's mutation-version gate; not reachable by MATCH delta-build). Full Etap B (constrained-pattern-walk discovery of new tuples on CREATED) is deferred to a separate ADR — see D8-lazy.

### Context and Orientation

**Codebase state at track start.** After Stages 1-2: RECORD and AGGREGATE delta work, plus Etap A single-alias MATCH. This stage adds the multi-alias MATCH path.

Existing relevant code (same as Stage 2):
- `SQLMatchStatement`, `SQLMatchExpression.{origin, items}`, `SQLMatchFilter.{getAlias, getClassName, getFilter}` (read-only).
- `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` — separate-ADR primitive for full Etap B's CREATED-discovery. NOT used in v1.

**Concrete deliverables.**
- `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch — applies the conditions in step 1 below. Runs after Stage 2's Etap A gate fails.
- `CacheableShape.MATCH_TUPLE_MULTI` enum value.
- Entry construction extension — MATCH_TUPLE_MULTI entries store: `effectiveFromClasses = ⋃ aliasClasses.values()`, `aliasClasses: Map<String, Set<String>>` (per-alias subclass closure), `aliasWheres: Map<String, SQLWhereClause>`, `contributingRids: Map<Integer, Set<RID>>` (populated incrementally during stream-pull), `reverseIndex: Map<RID, Set<Integer>>` (populated incrementally), `tombstoned: boolean` (default false).
- Stream-pull-append walker — for each pulled tuple `r` appended to `entry.results` at tuple-index `i`, iterate aliases in `aliasClasses.keySet()`; for each alias `a`, read `r.getProperty(a).getIdentity()` to get the bound RID; update `contributingRids[i].add(rid)` + `reverseIndex.computeIfAbsent(rid, _ -> new HashSet<>()).add(i)`.
- `DeltaBuilder.buildForMatchMulti(entry, recordOps, ctx)` → `MatchMultiDelta` or TOMBSTONE sentinel — two-pass algorithm with tombstone pre-scan + per-tuple skip set + RID skip set (see step 2).
- `MatchMultiDelta` class — immutable per-view delta: `Set<Integer> tupleSkipSet`, `Set<RID> ridSkipSet`. No injectList (partial Etap B does not discover new tuples).
- Cache lookup tombstone handling — `QueryResultCache.lookup` for MATCH_TUPLE_MULTI entries invokes the DeltaBuilder; if TOMBSTONE sentinel returned, evict entry + return null (miss); else cache the `MatchMultiDelta` per Option C sharing.
- `CachedResultSetView` extension for MATCH_TUPLE_MULTI — `view.next()` iterates `entry.results`, skipping tuples whose index is in `tupleSkipSet`. On stream-pull-append: drop the pulled tuple if any alias's bound RID is in `ridSkipSet` (drop, pull next); else append + populate reverseIndex + contributingRids for the new tuple index.

### Plan of Work

1. `ShapeClassifier.classify(SQLMatchStatement)` MATCH_TUPLE_MULTI branch (runs after Stage 2's Etap A check fails). MATCH_TUPLE_MULTI iff:
   - Etap A conditions failed (multi-alias or pattern-with-edges or cross-join)
   - Every pattern node (origin + every path item's filter) returns a non-null `getClassName(ctx)`
   - No `LET` / `UNWIND` in scope
   - No pattern-node WHERE references cross-alias state (walk each filter's WHERE AST for `$current`, `$matched`, `$parent`, `$depth`, or `${someOtherAlias}.field` references)
   - No subqueries (nested `SQLSelectStatement` descendant) in any pattern-node WHERE
   - **No SKIP, no LIMIT** (presence routes to K0_NONE via the first-gate check in `ShapeClassifier`)
   If pass: classify returns MATCH_TUPLE_MULTI. Else K0_NONE (delta-unreconcilable but D18 still caches under mutation-version gate).

2. `DeltaBuilder.buildForMatchMulti(entry, tx, ctx) → MatchMultiDelta | TOMBSTONE`. Two-pass algorithm:

   **Pass 1 — Tombstone pre-scan**:
   ```
   snapshot = new ArrayList<>(tx.recordOperations.values())
   for op in snapshot:
     if op.type != CREATED: continue
     rec = op.record
     if !(rec instanceof Entity entity): continue  // non-Entity records can't bind into MATCH
     cls = entity.getSchemaClass()
     if cls == null: continue
     if entry.effectiveFromClasses.contains(cls.getName()):
       entry.tombstoned = true
       return TOMBSTONE  // signal lookup to evict + miss
   ```

   **Pass 2 — Build tupleSkipSet + ridSkipSet**:
   ```
   tupleSkipSet = new HashSet<Integer>()
   ridSkipSet = new HashSet<RID>()
   for op in snapshot:
     rec = op.record
     if !(rec instanceof Entity entity): continue
     cls = entity.getSchemaClass()
     if cls == null: continue
     if !entry.effectiveFromClasses.contains(cls.getName()): continue
     rid = entity.getIdentity()
     affectedTuples = entry.reverseIndex.getOrDefault(rid, emptySet())
     if op.type == DELETED:
       tupleSkipSet.addAll(affectedTuples)
       ridSkipSet.add(rid)
     elif op.type == UPDATED:
       for tupleIndex in affectedTuples:
         // find which alias(es) bind this rid in this tuple
         for alias in entry.aliasClasses.keySet():
           classes = entry.aliasClasses.get(alias)
           if classes.contains(cls.getName()):
             // this alias could bind a record of this class
             where = entry.aliasWheres.get(alias)
             if where != null && !where.matchesFilters(entity, ctx):
               tupleSkipSet.add(tupleIndex)
               break  // one failing alias is enough to drop the tuple
       ridSkipSet.add(rid)
   return new MatchMultiDelta(unmodifiableSet(tupleSkipSet), unmodifiableSet(ridSkipSet))
   ```

   Cross-view sharing via `mutationVersion` (Option C) symmetric with RECORD / AGGREGATE: entry caches the latest `(tupleSkipSet, ridSkipSet, mutationVersion)` triple; new views at the same mutationVersion reuse it; new views at a fresher version trigger rebuild.

3. `QueryResultCache.lookup(key)` extension — when `entry.shape == MATCH_TUPLE_MULTI`:
   ```
   delta = DeltaBuilder.buildForMatchMulti(entry, tx, ctx)
   if delta == TOMBSTONE:
     entries.remove(key)
     return null  // miss; caller falls through to statement.execute(...)
   // else delta is a MatchMultiDelta; store on entry for sharing and return entry
   ```

4. `CachedResultSetView` MATCH_TUPLE_MULTI branch. View carries the `MatchMultiDelta` ref. `view.next()`:
   ```
   while true:
     while position < entry.results.size():
       if matchMultiDelta.tupleSkipSet.contains(position):
         position++
         continue
       result = entry.results[position]
       position++
       return result
     // Cache exhausted, try stream-pull
     if entry.exhausted:
       throw NoSuchElementException
     r = entry.stream.next(entry.ctx)
     // For each alias in aliasClasses, check the new tuple's binding RID against ridSkipSet
     newTupleIndex = entry.results.size()
     dropped = false
     for alias in entry.aliasClasses.keySet():
       boundRid = r.getProperty(alias).getIdentity()
       if matchMultiDelta.ridSkipSet.contains(boundRid):
         dropped = true
         break
     if dropped:
       continue  // don't append; pull next
     entry.results.add(r)
     // Populate reverseIndex + contributingRids for the new tuple
     for alias in entry.aliasClasses.keySet():
       boundRid = r.getProperty(alias).getIdentity()
       entry.contributingRids.computeIfAbsent(newTupleIndex, _ -> new HashSet<>()).add(boundRid)
       entry.reverseIndex.computeIfAbsent(boundRid, _ -> new HashSet<>()).add(newTupleIndex)
     return r
   ```
   MATCH_TUPLE_MULTI does not carry SKIP / LIMIT — those route to K0_NONE upstream.

5. Stream-pull-append population — make sure entry.contributingRids and entry.reverseIndex are kept in sync with entry.results at all times. The population pass during initial stream pull runs at view-construction time (cache-miss); the same logic is re-used by step 4's stream-pull-append for late tuples.

6. Test matrix (T6 set, partial Etap B subset):
    - **T6e** (MATCH_TUPLE_MULTI classify pass): pattern `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` — classify returns MATCH_TUPLE_MULTI.
    - **T6f** (MATCH_TUPLE_MULTI classify NONE for classless node): `MATCH {as:any}.out('memberOf'){as:g, class:Group} RETURN any, g` (no `class:` on first alias) → classify NONE.
    - **T6g** (MATCH_TUPLE_MULTI classify NONE for subquery in pattern WHERE): `MATCH {as:u, class:User WHERE id IN (SELECT id FROM Active)} RETURN u` → classify NONE.
    - **T6i** (partial Etap B DELETED): pre-populate `MATCH {as:i, class:Issue}.out('project'){as:p, class:Project} RETURN i, p` with 10 (Issue, Project) tuples. `tx.delete(issue_with_rid_X)`. Re-query → assert: tuples containing issue X are skipped; tuples containing other issues remain. `reverseIndex.get(X)` was consulted; affected tuples in `tupleSkipSet`.
    - **T6j** (partial Etap B UPDATED, WHERE-still-passes): same shape. `tx.save(issue_X with priority changed but no WHERE-fail)`. Re-query → assert: tuples containing X stay (post-update record satisfies the `i` alias's WHERE).
    - **T6k** (partial Etap B UPDATED, WHERE-fails): same shape. `tx.save(issue_X with status changed so it no longer matches origin's WHERE)`. Re-query → assert: tuples containing X are dropped.
    - **T6l** (partial Etap B CREATED tombstone): same shape. `tx.save(new Issue)`. Re-query → assert: entry was removed (cache.size dropped by 1 for this key, then re-populated on miss); subsequent re-query returns fresh tuples including the new issue.
    - **T6m** (multi-alias-same-class self-loop): `MATCH {as:u, class:User}.out('reportsTo'){as:m, class:User} RETURN u, m`. UPDATED user X (bound to both u and m positions in different tuples). Verify each tuple is re-evaluated against BOTH aliases' WHEREs; if either fails, the tuple is dropped.
    - **T6n** (cross-join CREATED tombstone): `MATCH {as:u, class:User}, {as:g, class:Group} RETURN u, g` (cross-join). Pre-populate. `tx.save(new User)` → entry tombstoned (classify=MATCH_TUPLE_MULTI; CREATED on a class in effectiveFromClasses tombstones per partial Etap B).
    - **T6o** (stream-pull-append with RID skip): partial-populated MATCH_TUPLE_MULTI entry. tx.delete(issue_in_uncached_storage_tail). Stream-pull pulls the deleted issue from storage tail (in-memory record-cache may emit it); view's stream-pull-append checks ridSkipSet, drops the tuple, continues. Verify the view never emits a tuple containing the deleted issue.
    - **T6p** (Option C delta sharing): construct view-A on a MATCH_TUPLE_MULTI entry at mutationVersion=5; entry.cachedMatchMultiDelta is shared. View-B at same version reuses by reference. View-C after a new mutation rebuilds (tombstone or fresh skipSet).

**Invariants to preserve.** I4 for partial Etap B: view output equivalent to fresh MATCH execution against the (cached + tx-delta) snapshot for the DELETED + UPDATED subset; CREATED in MATCH_TUPLE_MULTI tombstones the entry so re-execution sees the new tuple via fresh storage scan.

**Out-of-scope files.**
- `MatchExecutionPlanner`, `MatchFirstStep`, `MatchPrefetchStep` — full Etap B (CREATED constrained-walk discovery) is a separate ADR; v1 tombstones on CREATED and re-executes.
- `SQLMatchExpression`, `SQLMatchFilter`, `SQLMatchPathItem` — read-only; no modifications.
- `MatchReturnProjector`, `CachedEntry.returnProjector`, Etap A classify branch — Stage 2.

## Interfaces and Dependencies

**In-scope files (union).** 12 distinct source files across the three stages:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/AggregateState.java` (new — Stage 1)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/AggregateCacheTapStep.java` (new, package `internal.core.tx.cache` or sibling — Stage 1)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (aggregate classify rules — Stage 1; Etap A MATCH classify branch — Stage 2; MATCH_TUPLE_MULTI classify branch — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (`buildForAggregate` — Stage 1; Etap A integration in `buildForRecord` projector application — Stage 2; new `buildForMatchMulti` — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (aggregate-shape branch — Stage 1; MATCH_TUPLE_MULTI shape branch in next() with tupleSkipSet + stream-pull-append ridSkipSet filter + late-tuple reverseIndex maintenance — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (plan-rewrite splice in miss path — Stage 1)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (Etap A `returnProjector` field — Stage 2; MATCH_TUPLE_MULTI fields `aliasClasses`/`aliasWheres`/`contributingRids`/`reverseIndex`/`tombstoned` — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (projected-tuple ORDER BY support — Stage 2)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchReturnProjector.java` (new — Etap A closure builder utility — Stage 2)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheableShape.java` (add `MATCH_TUPLE_MULTI` enum value — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/MatchMultiDelta.java` (new — immutable delta type for MATCH_TUPLE_MULTI — Stage 3)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (lookup tombstone handling for MATCH_TUPLE_MULTI — Stage 3)

**Depends on:** Track 1 (skeleton + read path + pause/resume + RECORD delta core).

**Unblocks:** Track 3.

**Library / function signatures.**
- `AggregateState.observe(Result) → void`.
- `AggregateState.applyMutation(RecordAbstract, byte, boolean) → void`.
- `AggregateState.copy() → AggregateState`.
- `AggregateState.toResult() → Result`.
- `AggregateCacheTapStep(CommandContext, AggregateState, boolean profilingEnabled)`.
- `DeltaBuilder.buildForAggregate(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → AggregateState`.
- `MatchReturnProjector.build(List<SQLExpression>, String alias) → Function<RecordAbstract, Result>`.
- `ShapeClassifier.isMatchEtapA(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.returnProjector` field — `@Nullable Function<RecordAbstract, Result>`.
- `ShapeClassifier.isMatchTupleMulti(SQLMatchStatement) → boolean` (helper).
- `CachedEntry.aliasClasses` / `aliasWheres` / `contributingRids` / `reverseIndex` / `tombstoned` fields — populated only when shape == MATCH_TUPLE_MULTI.
- `DeltaBuilder.buildForMatchMulti(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → MatchMultiDelta` (or TOMBSTONE sentinel object).
- `MatchMultiDelta(Set<Integer> tupleSkipSet, Set<RID> ridSkipSet)` — immutable constructor; getters for both sets.
