package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

/**
 * The static classification a query receives at its first cache put, decided by {@link
 * ShapeClassifier#classify}. The shape selects which delta-reconciliation path the cache runs when a
 * cached entry is replayed against in-transaction mutations.
 *
 * <p>Two shapes are wired in this foundation:
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
 * final enum constants the classifier returns, but their delta-build and view paths land in later
 * tracks. Until then a query the classifier maps to one of those shapes is executed uncached: the
 * session routes only {@code RECORD} and {@code K0_NONE} through the cache.
 */
public enum CacheableShape {

  /** Plain SELECT, record-per-row, delta-reconcilable. Wired in this foundation. */
  RECORD,

  /** {@code COUNT(*)} / {@code COUNT(prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_COUNT,

  /** {@code SUM(prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_SUM,

  /** {@code AVG(prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_AVG,

  /** {@code MIN(prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_MIN,

  /** {@code MAX(prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_MAX,

  /** {@code COUNT(DISTINCT prop)} single-aggregate SELECT. Delta path lands later. */
  AGGREGATE_COUNT_DISTINCT,

  /** Multi-alias MATCH producing tuples. Delta path lands later. */
  MATCH_TUPLE_MULTI,

  /**
   * {@code SELECT distinct(prop)} / {@code SELECT DISTINCT prop} — the distinct value set of a single
   * bare property, emitted as one row per distinct value. Reconciled incrementally through the same
   * per-value RID buckets as {@code AGGREGATE_COUNT_DISTINCT} (the entry carries an {@code AggregateState}
   * of that kind); the view emits the bucket keys as rows instead of their count. Distinct projections
   * over a path or expression, multiple columns, or carrying ORDER BY route to {@link #K0_NONE} instead.
   * Wired in this track.
   */
  DISTINCT_VALUES,

  /**
   * Deterministically reproducible but not record-by-record reconcilable. Cached under the
   * mutation-version gate. Wired in this foundation.
   */
  K0_NONE
}
