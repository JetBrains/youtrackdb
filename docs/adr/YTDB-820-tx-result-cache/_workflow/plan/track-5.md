# Track 5: Aggregate delta — AGGREGATE_* shapes

## Purpose / Big Picture

BLUF: After this track, cached aggregate queries (`COUNT(*)`, `SUM(prop)`, `AVG(prop)`, `MIN(prop)`, `MAX(prop)`) reflect intra-tx mutations via per-query `AggregateState` copy-and-replay. Same applyMutation algorithm as eager, driven from delta-build instead of mutation-time dispatch.

Extend `ShapeClassifier` to return `AGGREGATE_COUNT`/`SUM`/`AVG`/`MIN`/`MAX` for single-aggregate SELECT shapes. Add `AggregateCacheTapStep` and splice into the execution plan upstream of `AggregateProjectionCalculationStep` during cache-miss execution. Implement `DeltaBuilder.buildForAggregate` that copies the entry's aggregate state and replays `applyMutation` over relevant tx-mutations. Extend `CachedResultSetView` to carry per-view `deltaAggregateState` and return `deltaState.toResult()` directly.

## Context and Orientation

**Codebase state at track start.** After Track 4: RECORD-shape delta works end-to-end. This track adds aggregate cacheability + delta replay using the same DeltaBuilder pattern.

Existing relevant code:
- `AggregateProjectionCalculationStep.java:121-137` — blocking aggregation loop: `prev.start(ctx)` then `while lastRs.hasNext: aggregate(lastRs.next, ctx, ...)`. Splice target for `AggregateCacheTapStep`.
- `AbstractExecutionStep` — base class for execution steps; provides public `prev` field (`AbstractExecutionStep.java:66`) for upstream-link traversal.
- `SelectExecutionPlan.steps` — concrete `List<ExecutionStepInternal>` field (`SelectExecutionPlan.java:54`); the cache-miss path obtains the plan via `statement.createExecutionPlan(ctx, false)` (rather than `statement.execute(...)`, which immediately wraps the plan in a `LocalResultSet`), downcasts to `SelectExecutionPlan`, mutates the `prev` link of the target step, then runs the plan to produce the result stream. `InternalExecutionPlan.getSteps()` returns `List<ExecutionStep>` (read-only API); writable access requires the concrete type.

**Concrete deliverables.**
- `ShapeClassifier.classify` extended: single-aggregate-projection SELECT returns one of `AGGREGATE_COUNT/SUM/AVG/MIN/MAX`. Multi-aggregate, expression-aggregate (`SUM(a+b)`), aggregate-in-where, GROUP BY, HAVING, `COUNT DISTINCT`, `MEDIAN`/`MODE`/`PERCENTILE` → NONE.
- `AggregateState` complete with `observe(result)` (called by tap), `applyMutation(rec, status, matchAfter)` (called by DeltaBuilder), `copy()` (called by DeltaBuilder at view ctor), `toResult()` (called by view.next).
- `AggregateCacheTapStep extends AbstractExecutionStep` — side-tap step. `internalStart(ctx)` calls `prev.start(ctx)` (reading the public `prev` field from `AbstractExecutionStep`) to get upstream stream, then returns wrapping stream whose `next(ctx)` calls `entry.aggregateState.observe(result)` before forwarding unchanged.
- Plan-rewrite splice in `DatabaseSessionEmbedded.query()` miss path: walk `plan.steps`, find `AggregateProjectionCalculationStep`, rewire its `prev` to a new `AggregateCacheTapStep` whose `prev` is the original upstream. Failure to find expected shape → entry shape = NONE (downgrade).
- `DeltaBuilder.buildForAggregate(entry, recordOps, ctx) → AggregateState`: `entry.aggregateState.copy()` then iterate recordOps, class filter, WHERE eval, `deltaState.applyMutation(rec, status, match_after)`. Returns the delta-applied copy.
- `CachedResultSetView` extended: for aggregate shape, carry `deltaAggregateState` instead of `TxDeltaCursor`. `next()` returns `deltaAggregateState.toResult()` once; `hasNext()` true exactly once.

## Plan of Work

1. `AggregateState.observe(result)` — for COUNT(*): `contributingRids.add(rec.rid); count++`. For SUM/AVG: same + `contributingValues.put(rid, value); currentScalar += value`. For MIN/MAX: same + track `extremumRid` by RID identity. RID identity (not `Number.equals`) sidesteps the cross-Number-subtype hazard (`Long.valueOf(5L).equals(Integer.valueOf(5))` returns `false` in Java), and gives ties unambiguous semantics — one RID owns the slot at any time; the next ties-recompute picks whichever survives.
2. `AggregateState.applyMutation(rec, status, matchAfter)` — transition matrix per design.md §"Aggregate delta — AGGREGATE_* shapes":
   - COUNT: T→T no-op, F→F no-op, T→F decrement + remove from rids, F→T increment + add.
   - SUM/AVG: same matrix, with `delta = new_value - old_value` for T→T and full add/subtract for F→T / T→F.
   - MIN/MAX: same matrix, with O(n) recompute over `contributingValues` if `was_extremum` and new state loses the extremum direction. Where `was_extremum = rid.equals(extremumRid)` — RID identity.
   - **Empty-set semantics (L3 fix)**: if `contributingValues.isEmpty()` (or `contributingRids.isEmpty()` for COUNT) after applyMutation, set `extremumRid = null` and `currentScalar = null` for MIN/MAX; `currentScalar = null, count = 0` for AVG; `currentScalar = 0` for SUM; `count = 0` for COUNT. `toResult()` emits SQL `NULL` when `currentScalar == null` (MIN/MAX/AVG of empty set is NULL per SQL standard; SUM of empty set is 0; COUNT of empty set is 0).
3. `AggregateState.copy()` — shallow-deep: new mutable containers (`new HashSet<>(contributingRids)`, `new HashMap<>(contributingValues)`) but reuse underlying RID and Number refs.
4. `AggregateCacheTapStep` — implement as transparent side-tap. Tests: tap observes every record that reaches the aggregate step; tap doesn't change downstream result.
5. Plan-rewrite splice — implement in `DatabaseSessionEmbedded.query()` miss path. **Failure fallback (L6 fix)**: if planner emits unexpected shape (no `AggregateProjectionCalculationStep` found after walking `SelectExecutionPlan.steps`), close the constructed plan (best-effort), increment `QueryCacheMetrics.spliceFailures`, fall back by calling `statement.execute(session, args)` to obtain a standard `LocalResultSet`, return that directly to the consumer (no cache entry created, no `CachedResultSetView` wrapping). Log a warning identifying the unexpected step types.
6. **Eager aggregate drive (L8 fix + SO4 exception-safety)**: on cache-miss for AGGREGATE_* shape, the post-splice path drives the plan to completion BEFORE wrapping in `CachedResultSetView`. Sequence: build `entry`, splice the tap, call `plan.start(ctx).next(ctx)` **inside a try block**; on successful drain, the entry's aggregateState is fully populated and immutable, the resulting aggregate Result is buffered, and `cache.put(key, entry)` happens AFTER drain success. On throw (storage IO exception, type coercion error, OOM during drain): the partial entry is NEVER inserted into `cache.entries`; the plan is closed (best-effort) and the exception re-thrown to the consumer. This prevents a stale partial-aggregateState entry from being read by subsequent views. `cache.put` must come after `plan.next()` succeeds — never before.
7. `DeltaBuilder.buildForAggregate` — copy-then-replay.
8. View extension — `CachedResultSetView` handles aggregate shape: single-row read of `deltaAggregateState.toResult()`.
8. Test matrix (T5 set):
   - T5a: COUNT(*) — CREATE matching/non-matching, UPDATE in/out of WHERE, DELETE matching.
   - T5b: SUM(prop) — same matrix + UPDATE changing prop value (T→T with delta).
   - T5c: AVG(prop) — same as SUM + count tracking.
   - T5d: MIN(prop) — UPDATE extremum to non-extremum value triggers O(n) recompute. UPDATE non-extremum (no recompute).
   - T5e: MAX(prop) — symmetric to MIN.
   - T5f: Aggregate expression (`SUM(a+b)`) — classify returns NONE; not cached.
   - T5g: GROUP BY — NONE.
   - T5h (L6 fallback): Plan-rewrite splice failure — use a planner-mock that returns a plan WITHOUT `AggregateProjectionCalculationStep`. Verify: (i) consumer receives a working `ResultSet` via `statement.execute(...)` fallback; (ii) no cache entry created; (iii) `QueryCacheMetrics.spliceFailures` incremented; (iv) no NPE, no resource leak.
   - T5i (I4 aggregate): aggregate result equivalent to fresh execution post-mutation.
   - T5j (L3 empty-set semantics): cache `SELECT MIN(age) FROM User WHERE active=true` with 5 matching records; tx deletes all 5; verify view returns SQL NULL (not 0, not stale value). Same for MAX/AVG. SUM returns 0. COUNT returns 0.
   - T5k (L8 eager drive): construct cache-miss view for COUNT, drop without calling `.next()`, then construct a second view of the same key — second view returns the correct count (not 0, not stale partial state). Asserts the eager-drive step 6 populates `entry.aggregateState` regardless of consumer behavior.
   - T5l (SO4 exception safety): inject a planner-mock that emits an `AggregateProjectionCalculationStep` whose `next(ctx)` throws `StorageIOException` mid-drain. Verify: (i) the exception propagates to the consumer, (ii) `cache.size() == 0` (the partial entry was NOT inserted), (iii) the spliced plan is closed (no stream leak), (iv) a subsequent retry of the same query goes through a fresh miss path without seeing stale partial state.

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
- `MATCH` classify and projector — Track 6.
- `AggregateProjectionCalculationStep` itself — splice rewires its `prev` only; the step is unchanged.

**Inter-track dependencies.**
- Depends on: Track 4 (RECORD shape + DeltaBuilder + view sorted-merge).
- Unblocks: Track 6 (MATCH Etap A also extends ShapeClassifier and uses DeltaBuilder).

**Library / function signatures.**
- `AggregateState.observe(Result) → void`.
- `AggregateState.applyMutation(RecordAbstract, byte, boolean) → void`.
- `AggregateState.copy() → AggregateState`.
- `AggregateState.toResult() → Result`.
- `AggregateCacheTapStep(CommandContext, AggregateState, boolean profilingEnabled)`.
- `DeltaBuilder.buildForAggregate(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → AggregateState`.
