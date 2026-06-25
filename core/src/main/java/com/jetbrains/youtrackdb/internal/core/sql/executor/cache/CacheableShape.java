package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

/**
 * The static classification a query receives at its first cache put, decided by {@link
 * ShapeClassifier#classify}. The shape selects which delta-reconciliation path the cache runs when a
 * cached entry is replayed against in-transaction mutations.
 *
 * <p>Two shapes:
 *
 * <ul>
 *   <li>{@link #RECORD} — a plain SELECT whose rows are individual records the delta builder can
 *       reconcile one record at a time (add a tx-CREATED row, drop a tx-DELETED row, re-position a
 *       tx-UPDATED row).
 *   <li>{@link #K0_NONE} — a query whose result is deterministically reproducible from storage plus
 *       the AST but cannot be reconciled record by record (carries SKIP / LIMIT, GROUP BY, LET, a
 *       subquery, or an expression aggregate). A {@code K0_NONE} entry serves cached reads only while
 *       no mutation has happened since it was populated, and re-executes after any tx-write.
 * </ul>
 *
 * <p>The remaining values — the {@code AGGREGATE_*} family and {@link #MATCH_TUPLE_MULTI} — are the
 * * <p>All shapes defined below — including the {@code AGGREGATE_*} family,
 *  * {@link #DISTINCT_VALUES}, and {@link #MATCH_TUPLE_MULTI} — are fully supported by the transaction
 *  * cache layer, routing through their respective incremental replay or version-gated views.
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

  /** {@code COUNT(DISTINCT prop)} single-aggregate SELECT. */
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
   * over a path or expression, multiple columns, or carrying ORDER BY route to {@link #K0_NONE} instead.
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
