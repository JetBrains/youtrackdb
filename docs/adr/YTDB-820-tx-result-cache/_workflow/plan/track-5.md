# Track 5: Aggregate delta — AGGREGATE_* shapes

## Purpose / Big Picture

BLUF: After this track, cached aggregate queries (`COUNT(*)`, `SUM(prop)`, `AVG(prop)`, `MIN(prop)`, `MAX(prop)`, `COUNT(DISTINCT prop)`) reflect intra-tx mutations via per-query `AggregateState` copy-and-replay. Same applyMutation algorithm as eager for COUNT/SUM/AVG/MIN/MAX, driven from delta-build instead of mutation-time dispatch. SUM/AVG accumulate internally in BigDecimal (D19) for cross-subtype-safe arithmetic, replaying through a pinned `scalarReturnType`. `COUNT(DISTINCT prop)` (D20 — extends coverage beyond eager's K0 fallback) uses per-value `distinctBuckets: Map<Object, Set<RID>>` with reverse lookup through `contributingValues`.

Extend `ShapeClassifier` to return `AGGREGATE_COUNT`/`SUM`/`AVG`/`MIN`/`MAX` for single-aggregate SELECT shapes. Add `AggregateCacheTapStep` and splice into the execution plan upstream of `AggregateProjectionCalculationStep` during cache-miss execution. Implement `DeltaBuilder.buildForAggregate` that copies the entry's aggregate state and replays `applyMutation` over relevant tx-mutations. Extend `CachedResultSetView` to carry per-view `deltaAggregateState` and return `deltaState.toResult()` directly.

## Context and Orientation

**Codebase state at track start.** After Track 4: RECORD-shape delta works end-to-end. This track adds aggregate cacheability + delta replay using the same DeltaBuilder pattern.

Existing relevant code:
- `AggregateProjectionCalculationStep.java:121-137` — blocking aggregation loop: `prev.start(ctx)` then `while lastRs.hasNext: aggregate(lastRs.next, ctx, ...)`. Splice target for `AggregateCacheTapStep`.
- `AbstractExecutionStep` — base class for execution steps; provides public `prev` field (`AbstractExecutionStep.java:66`) for upstream-link traversal.
- `SelectExecutionPlan.steps` — concrete `List<ExecutionStepInternal>` field (`SelectExecutionPlan.java:54`); the cache-miss path obtains the plan via `statement.createExecutionPlan(ctx, false)` (rather than `statement.execute(...)`, which immediately wraps the plan in a `LocalResultSet`), downcasts to `SelectExecutionPlan`, mutates the `prev` link of the target step, then runs the plan to produce the result stream. `InternalExecutionPlan.getSteps()` returns `List<ExecutionStep>` (read-only API); writable access requires the concrete type.

**Concrete deliverables.**
- `ShapeClassifier.classify` extended: single-aggregate-projection SELECT returns one of `AGGREGATE_COUNT/SUM/AVG/MIN/MAX/COUNT_DISTINCT`. `COUNT_DISTINCT` accepts only the plain-property form (`COUNT(DISTINCT prop)`); `COUNT(DISTINCT a+b)`, `COUNT(DISTINCT someFunction())`, multi-aggregate, expression-aggregate (`SUM(a+b)`), aggregate-in-where, GROUP BY, HAVING, `MEDIAN`/`MODE`/`PERCENTILE` → K0_NONE.
- `AggregateState` complete with `observe(result)` (called by tap), `applyMutation(rec, status, matchAfter)` (called by DeltaBuilder), `copy()` (called by DeltaBuilder at view ctor), `toResult()` (called by view.next). SUM/AVG variants accumulate in `sumAccumulator: BigDecimal` and replay via the pinned `scalarReturnType: Class<? extends Number>`; AGGREGATE_COUNT_DISTINCT variant carries `distinctBuckets: Map<Object, Set<RID>>` and re-uses `contributingValues: Map<RID, Object>` for reverse lookup on UPDATED transitions.
- `AggregateCacheTapStep extends AbstractExecutionStep` — side-tap step. `internalStart(ctx)` calls `prev.start(ctx)` (reading the public `prev` field from `AbstractExecutionStep`) to get upstream stream, then returns wrapping stream whose `next(ctx)` calls `entry.aggregateState.observe(result)` before forwarding unchanged.
- Plan-rewrite splice in `DatabaseSessionEmbedded.query()` miss path: walk `plan.steps`, find `AggregateProjectionCalculationStep`, rewire its `prev` to a new `AggregateCacheTapStep` whose `prev` is the original upstream. Failure to find expected shape → entry shape = NONE (downgrade).
- `DeltaBuilder.buildForAggregate(entry, tx, ctx) → AggregateState`: take a snapshot `new ArrayList<>(tx.recordOperations.values())` first (T1 fix — same hazard as buildForRecord: UDF in WHERE may save()), copy `entry.aggregateState`, then iterate the snapshot, class filter, WHERE eval, `deltaState.applyMutation(rec, status, match_after)`. Returns the delta-applied copy.
- `CachedResultSetView` extended: for aggregate shape, carry `deltaAggregateState` instead of `TxDeltaCursor`. `next()` returns `deltaAggregateState.toResult()` once; `hasNext()` true exactly once.

## Plan of Work

1. `AggregateState.observe(result)` — for COUNT(*): `contributingRids.add(rec.rid); count++`. For SUM/AVG: capture `scalarReturnType` on first observe (snapshot the property's `Number` subtype for replay); coerce `value` via `BigDecimal coercedValue = toBigDecimal(value)` where `toBigDecimal` is `value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString())` (the only path that preserves cross-subtype mathematical equality); accumulate `sumAccumulator = sumAccumulator.add(coercedValue)`; store `contributingValues.put(rid, value)` (raw value retained for SUM/AVG `delta = new - old` recompute). For MIN/MAX: same + track `extremumRid` by RID identity (the RID currently holding the cached extremum value). RID identity (not `Number.equals`) sidesteps the cross-`Number`-subtype hazard (`Long.valueOf(5L).equals(Integer.valueOf(5))` returns `false` in Java) and gives ties unambiguous semantics — one RID owns the slot at any time; the next ties-recompute picks whichever survives. For COUNT_DISTINCT: capture `scalarReturnType = Long.class` (count always returns Long); compute the bucket key as `bucketKey = (value instanceof Number n) ? toBigDecimal(n) : value` so numeric DISTINCT collapses cross-subtype duplicates (`Long(5)` and `Integer(5)` share a bucket); `distinctBuckets.computeIfAbsent(bucketKey, k -> new HashSet<>()).add(rid); contributingValues.put(rid, bucketKey)`.
2. `AggregateState.applyMutation(rec, status, matchAfter)` — transition matrix per design.md §"Aggregate delta — AGGREGATE_* shapes":
   - COUNT: T→T no-op, F→F no-op, T→F decrement + remove from rids, F→T increment + add.
   - SUM/AVG: same matrix. Read `oldValue = contributingValues.get(rid)`, compute `newValue` via the property extractor when `matchAfter`. For T→T: `delta = toBigDecimal(newValue).subtract(toBigDecimal(oldValue)); sumAccumulator = sumAccumulator.add(delta); contributingValues.put(rid, newValue)`. For F→T (CREATED matching, or UPDATED into WHERE): `sumAccumulator = sumAccumulator.add(toBigDecimal(newValue)); contributingValues.put(rid, newValue); count++` (AVG only). For T→F (DELETED, or UPDATED out of WHERE): `sumAccumulator = sumAccumulator.subtract(toBigDecimal(oldValue)); contributingValues.remove(rid); count--` (AVG only).
   - MIN/MAX: same matrix, with O(n) recompute over `contributingValues` if `was_extremum = rid.equals(extremumRid)` is true and the new state loses the extremum direction. Otherwise O(1): compare new value to `currentScalar` and adopt if it wins (in which case `extremumRid` also flips to the new winner's RID). Bounded by `maxRecordsPerEntry`. The D14 sorted-value index for `O(log n)` consistent performance is v2-deferred per D14 cost-benefit; promotion gated on D13 measurement of extremum-churn frequency.
   - COUNT_DISTINCT: read `oldBucketKey = contributingValues.get(rid)` for reverse lookup. For T→T with value change: compute `newBucketKey` via the bucket-key rule from observe (BigDecimal coercion for numeric); if `oldBucketKey.equals(newBucketKey)` no-op (value didn't change semantically); else remove `rid` from `distinctBuckets.get(oldBucketKey)` (if the resulting set is empty, remove the bucket from the map), add `rid` to `distinctBuckets.computeIfAbsent(newBucketKey, ...)`, update `contributingValues.put(rid, newBucketKey)`. For F→T: add to `distinctBuckets[newBucketKey]` + `contributingValues.put(rid, newBucketKey)`. For T→F: remove `rid` from `distinctBuckets[oldBucketKey]` (cleanup empty bucket), `contributingValues.remove(rid)`. The published scalar `distinctBuckets.size()` is recomputed at `toResult()` time, not maintained as a separate counter (cheaper than the counter+correction approach because each transition already updates the bucket map atomically).
   - **Empty-set semantics (L3 fix)**: if `contributingValues.isEmpty()` (or `contributingRids.isEmpty()` for COUNT, or `distinctBuckets.isEmpty()` for COUNT_DISTINCT) after applyMutation, set `extremumRid = null` and `currentScalar = null` for MIN/MAX; `currentScalar = null, count = 0` and `sumAccumulator = BigDecimal.ZERO` for AVG; `sumAccumulator = BigDecimal.ZERO` for SUM (returns 0); `count = 0` for COUNT and COUNT_DISTINCT (return 0). `toResult()` emits SQL `NULL` for MIN/MAX/AVG of empty set per SQL standard; SUM of empty set is 0; COUNT of empty set is 0; COUNT_DISTINCT of empty set is 0.
3. `AggregateState.copy()` — shallow-deep: new mutable containers (`new HashSet<>(contributingRids)`, `new HashMap<>(contributingValues)`) but reuse underlying RID and Number refs. For COUNT_DISTINCT: also `new HashMap<>()` for `distinctBuckets`, and for each bucket entry `new HashSet<>(originalBucket)`. The `sumAccumulator` BigDecimal is immutable so no copy needed; the field reference is shared.
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
   - T5m (D19 SUM cross-subtype): populate `SELECT SUM(weight) FROM Box` where the entries hold a mix of `Long` and `Integer` and `Double` weights; intra-tx UPDATE switches one record's weight from `Long(5)` to `Double(5.5)`. Verify the cached scalar matches a fresh `SELECT SUM(weight)` after the mutation. The pinned `scalarReturnType` is the type observed at populate time; `toResult()` coerces back. Verify Long → Double precision boundary case (Long value `2^53 + 1` followed by Double mutation maintains precision through BigDecimal).
   - T5m' (D19 AVG cross-subtype): same as T5m but for AVG; verify `MathContext.DECIMAL64` rounding matches fresh execution.
   - T5n (D20 COUNT_DISTINCT classify): `SELECT COUNT(DISTINCT name) FROM User WHERE active=true` classifies AGGREGATE_COUNT_DISTINCT; `SELECT COUNT(DISTINCT a+b) FROM ...` classifies K0_NONE (expression-DISTINCT not supported in v1); `SELECT COUNT(*) FROM ...` classifies AGGREGATE_COUNT (no regression).
   - T5o (D20 COUNT_DISTINCT delta — CREATED): cache with 3 records bearing distinct values {A, B, C} → count=3. Tx CREATEs a record with value A (existing bucket) → count stays 3, bucket A now holds 2 RIDs. Tx CREATEs a record with value D (new bucket) → count goes to 4.
   - T5p (D20 COUNT_DISTINCT delta — DELETED): cache with 3 records {A, A, B} → count=2 (buckets A:2, B:1). Tx DELETEs one A-record → count stays 2 (bucket A:1, bucket B:1 still). Tx DELETEs the last A-record → count drops to 1 (bucket A removed from map).
   - T5q (D20 COUNT_DISTINCT delta — UPDATED bucket move): cache with 2 records {A, B} → count=2. Tx UPDATEs the A-record's property to B → count drops to 1 (bucket A removed; bucket B now holds 2). Tx UPDATEs the second B-record back to A → count returns to 2 (bucket A re-created; bucket B keeps 1).
   - T5q' (D20 COUNT_DISTINCT cross-subtype bucket key): cache with `Long(5)`, `Integer(5)`, `BigDecimal(5)` contributors → count=1 (all three coerce to the same BigDecimal key). Tx CREATEs a `Long(6)` → count goes to 2. Verify the cached scalar matches fresh execution.

**Invariants to preserve.** I4: view output equals fresh execution composed with tx-delta. I7: deltaAggregateState immutable post-construction (cache `entry.aggregateState` is the immutable source; `copy()` produces the view's mutable working copy).

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/AggregateState.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/AggregateCacheTapStep.java` (new, package `internal.core.tx.cache` or sibling)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (aggregate classify rules)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (`buildForAggregate`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (aggregate-shape branch)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (plan-rewrite splice in miss path)

**Out-of-scope files.**
- `MATCH` classify and projector — Tracks 6a (Etap A) and 6b (MATCH_TUPLE_MULTI).
- `AggregateProjectionCalculationStep` itself — splice rewires its `prev` only; the step is unchanged.

**Inter-track dependencies.**
- Depends on: Track 4 (RECORD shape + DeltaBuilder + view sorted-merge).
- Unblocks: Track 6a (MATCH Etap A extends ShapeClassifier and reuses DeltaBuilder), Track 6b (MATCH_TUPLE_MULTI extends ShapeClassifier and adds buildForMatchMulti).

**Library / function signatures.**
- `AggregateState.observe(Result) → void`.
- `AggregateState.applyMutation(RecordAbstract, byte, boolean) → void`.
- `AggregateState.copy() → AggregateState`.
- `AggregateState.toResult() → Result`.
- `AggregateCacheTapStep(CommandContext, AggregateState, boolean profilingEnabled)`.
- `DeltaBuilder.buildForAggregate(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → AggregateState`.
