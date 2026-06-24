package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricDefinition;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricScope.Global;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;

/**
 * Per-transaction counter holder for the tx-result cache. Lives on the cache instance owned by a
 * single {@code FrontendTransactionImpl}, so it observes only the owning thread and needs no
 * synchronisation: plain {@code long} fields suffice.
 *
 * <p>The counters are cumulative for the lifetime of the transaction and are discarded with it. Each
 * {@code increment*} call also feeds the matching engine-global {@link CoreMetrics} rate, so the
 * per-transaction counters and the cross-transaction rate-per-second metrics stay derived from the
 * same events — the global metric is the rolling rate of the per-tx increments, not a separate
 * counting path. The global sinks are resolved once at construction from the {@link MetricsRegistry};
 * when the registry is unavailable (for example before the profiler is initialised, or in a unit test
 * with no engine running) each sink falls back to {@link TimeRate#NOOP} and only the per-tx counter
 * advances.
 *
 * <ul>
 *   <li>{@code hits} — lookups that returned a usable cached entry.
 *   <li>{@code misses} — lookups that found no entry and drove a fresh execution.
 *   <li>{@code spliceFailures} — aggregate splices that fell back to uncached execution because the
 *       planner emitted an unexpected execution-plan shape.
 *   <li>{@code k0Invalidations} — K0_NONE entries invalidated because an intervening mutation
 *       advanced the mutation version past the entry's populate version.
 *   <li>{@code matchMultiInvalidations} — MULTI_MATCH entries invalidated.
 *   <li>{@code overflows} — entries removed by a cache bound: either LRU eviction at the per-tx entry
 *       count ({@code maxEntries}) or a populate crossing the per-entry record cap
 *       ({@code maxRecordsPerEntry}). Both route the key to the non-cacheable set.
 * </ul>
 */
public final class QueryCacheMetrics {

  private long hits;
  private long misses;
  private long spliceFailures;
  private long k0Invalidations;
  private long matchMultiInvalidations;
  private long overflows;

  /**
   * Engine-global rate sinks, one per counter, resolved once from the {@link MetricsRegistry}. Each is
   * {@link TimeRate#NOOP} when no registry is available, so an increment is always safe to record.
   */
  private final TimeRate hitRate;
  private final TimeRate missRate;
  private final TimeRate spliceFailureRate;
  private final TimeRate k0InvalidationRate;
  private final TimeRate multiInvalidationRate;
  private final TimeRate overflowRate;

  public QueryCacheMetrics() {
    this(YouTrackDBEnginesManager.instance().getMetricsRegistry());
  }

  /**
   * Resolves the six global rate sinks from {@code registry} (or {@link TimeRate#NOOP} when it is
   * null). Package-visible so a test can pass a registry built on a deterministic ticker and assert the
   * bridge records into the global metric, without standing up a full engine.
   */
  QueryCacheMetrics(MetricsRegistry registry) {
    this(
        globalRate(registry, CoreMetrics.QUERY_CACHE_HIT_RATE),
        globalRate(registry, CoreMetrics.QUERY_CACHE_MISS_RATE),
        globalRate(registry, CoreMetrics.QUERY_CACHE_SPLICE_FAILURE_RATE),
        globalRate(registry, CoreMetrics.QUERY_CACHE_K0_INVALIDATION_RATE),
        globalRate(registry, CoreMetrics.QUERY_CACHE_MULTI_INVALIDATION_RATE),
        globalRate(registry, CoreMetrics.QUERY_CACHE_OVERFLOW_RATE));
  }

  /**
   * Direct-injection constructor for the six global rate sinks. Package-visible so a test can pass
   * recording stubs and assert each {@code increment*} call feeds the matching sink exactly once.
   */
  QueryCacheMetrics(
      TimeRate hitRate,
      TimeRate missRate,
      TimeRate spliceFailureRate,
      TimeRate k0InvalidationRate,
      TimeRate multiInvalidationRate,
      TimeRate overflowRate) {
    this.hitRate = hitRate;
    this.missRate = missRate;
    this.spliceFailureRate = spliceFailureRate;
    this.k0InvalidationRate = k0InvalidationRate;
    this.multiInvalidationRate = multiInvalidationRate;
    this.overflowRate = overflowRate;
  }

  /**
   * Resolves the global {@link TimeRate} for {@code definition}, or {@link TimeRate#NOOP} when the
   * registry is null (profiler not initialised, or a test with no running engine). Mirrors the
   * registry-null guard the other global-metric consumers (e.g. {@code EdgeTraversal}) use.
   */
  private static TimeRate globalRate(
      MetricsRegistry registry, MetricDefinition<Global, TimeRate> definition) {
    return registry != null ? registry.globalMetric(definition) : TimeRate.NOOP;
  }

  public void incrementHits() {
    hits++;
    hitRate.record();
  }

  public void incrementMisses() {
    misses++;
    missRate.record();
  }

  public void incrementSpliceFailures() {
    spliceFailures++;
    spliceFailureRate.record();
  }

  public void incrementK0Invalidations() {
    k0Invalidations++;
    k0InvalidationRate.record();
  }

  public void incrementMultiInvalidations() {
    matchMultiInvalidations++;
    multiInvalidationRate.record();
  }

  public void incrementOverflows() {
    overflows++;
    overflowRate.record();
  }

  public long getHits() {
    return hits;
  }

  public long getMisses() {
    return misses;
  }

  public long getSpliceFailures() {
    return spliceFailures;
  }

  public long getK0Invalidations() {
    return k0Invalidations;
  }

  public long getMatchMultiInvalidations() {
    return matchMultiInvalidations;
  }

  public long getOverflows() {
    return overflows;
  }
}
