package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

/**
 * The static classification a query receives at its first cache put, decided by {@link
 * ShapeClassifier#classify}. The shape selects which delta-reconciliation path the cache runs when a
 * cached entry is replayed against in-transaction mutations.
 *
 * <p>The shapes:
 *
 * <ul>
 *   <li>{@link #RECORD} — a plain SELECT whose rows are individual records the delta builder can
 *       reconcile one record at a time (add a tx-CREATED row, drop a tx-DELETED row, re-position a
 *       tx-UPDATED row). A single-alias MATCH folds onto this shape.
 *   <li>The {@code AGGREGATE_*} family ({@link #AGGREGATE_COUNT}, {@link #AGGREGATE_SUM}, {@link
 *       #AGGREGATE_AVG}, {@link #AGGREGATE_MIN}, {@link #AGGREGATE_MAX}) — single-aggregate SELECTs
 *       replayed through an {@link AggregateState}. {@link #AGGREGATE_COUNT_DISTINCT} is not produced
 *       by {@link ShapeClassifier#classify} as an entry shape; it is the {@code AggregateState} kind
 *       that backs {@link #DISTINCT_VALUES} (and a reserved future scalar distinct-count shape).
 *   <li>{@link #DISTINCT_VALUES} — {@code SELECT distinct(prop) ... ORDER BY prop}, replayed through
 *       the same per-value buckets as the distinct-count kind but emitting the bucket keys as rows.
 *   <li>{@link #MATCH_TUPLE_MULTI} — a multi-alias MATCH tuple, served verbatim under a class-scoped
 *       version gate.
 *   <li>{@link #K0_NONE} — a query whose result is deterministically reproducible from storage plus
 *       the AST but cannot be reconciled record by record (carries SKIP / LIMIT, GROUP BY, LET, a
 *       subquery, or an expression aggregate). A {@code K0_NONE} entry serves cached reads only while
 *       no mutation has happened since it was populated, and re-executes after any tx-write.
 * </ul>
 *
 */
public enum CacheableShape {

  /** Plain SELECT, record-per-row, delta-reconcilable. */
  RECORD,

  /** {@code COUNT(*)} / {@code COUNT(prop)} single-aggregate SELECT. */
  AGGREGATE_COUNT,

  /** {@code SUM(prop)} single-aggregate SELECT. */
  AGGREGATE_SUM,

  /** {@code AVG(prop)} single-aggregate SELECT. */
  AGGREGATE_AVG,

  /** {@code MIN(prop)} single-aggregate SELECT. */
  AGGREGATE_MIN,

  /** {@code MAX(prop)} single-aggregate SELECT. */
  AGGREGATE_MAX,

  /**
   * The {@link AggregateState} kind for per-value distinct buckets. Not produced by {@link
   * ShapeClassifier#classify} as an entry shape — scalar {@code COUNT(DISTINCT prop)} routes to {@link
   * #K0_NONE} because this engine computes it as a row count, not a true distinct count. It backs the
   * {@link #DISTINCT_VALUES} view and is reserved for a future engine with a native distinct count.
   */
  AGGREGATE_COUNT_DISTINCT,

  /**
   * Multi-alias MATCH whose RETURN projects a tuple across two or more bound aliases (a single-alias
   * MATCH folds onto {@link #RECORD} instead). The projected tuple set is frozen at populate and
   * replayed verbatim: a projected RETURN row carries no bound records, so the per-record delta path
   * cannot rebuild tuples and does not apply here. Validity is governed by a class-scoped version gate
   * (contrast {@link #K0_NONE}, gated on any mutation): the entry is served verbatim while no
   * post-populate mutation has touched a class in the pattern read-class closure (alias classes plus
   * traversal-edge classes), and is invalidated and re-executed once one has. The closure is the only
   * backstop, so a pattern with no statically resolvable read class is run uncached, never cached.
   */
  MATCH_TUPLE_MULTI,

  /**
   * {@code SELECT distinct(prop)} / {@code SELECT DISTINCT prop} — the distinct value set of a single
   * bare property, emitted as one row per distinct value. Reconciled incrementally through the same
   * per-value RID buckets as {@code AGGREGATE_COUNT_DISTINCT} (the entry carries an {@code AggregateState}
   * of that kind); the view emits the bucket keys as rows instead of their count. Distinct projections
   * over a path or expression, multiple columns, or lacking a deterministic ORDER BY on the projected
   * column route to {@link #K0_NONE} instead.
   */
  DISTINCT_VALUES,

  /**
   * Deterministically reproducible but not record-by-record reconcilable. Cached under the
   * mutation-version gate.
   */
  K0_NONE;

  public boolean isAggregate() {
    return this == CacheableShape.AGGREGATE_COUNT
        || this == CacheableShape.AGGREGATE_SUM
        || this == CacheableShape.AGGREGATE_AVG
        || this == CacheableShape.AGGREGATE_MIN
        || this == CacheableShape.AGGREGATE_MAX
        || this == CacheableShape.AGGREGATE_COUNT_DISTINCT;
  }
}
