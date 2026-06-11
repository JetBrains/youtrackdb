package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import org.junit.Test;

/**
 * Verifies the per-transaction tx-result-cache counter holder. Each counter starts at zero, each
 * increment advances only its own counter by one, and repeated increments accumulate. The class is
 * a plain single-threaded holder confined to its owning thread, so no concurrency is exercised here.
 *
 * <p>The increment methods also bridge to the engine-global {@code QUERY_CACHE_*_RATE} metrics: a
 * second group of cases injects a recording {@link TimeRate} per counter and asserts each
 * {@code increment*} records exactly one event into its own global sink and none into the others, so
 * the global rate is the rolling rate of the same events the per-tx counter measures.
 */
public class QueryCacheMetricsTest {

  /** A {@link TimeRate} that counts how many times {@code record} was called, for routing assertions. */
  private static final class RecordingRate implements TimeRate {

    private long recordCount;

    @Override
    public void record(long value) {
      recordCount++;
    }

    @Override
    public double getRate() {
      return 0.0;
    }
  }

  /**
   * A freshly constructed holder reports zero for every counter, so a transaction that never
   * touches the cache emits no spurious metric activity.
   */
  @Test
  public void countersStartAtZero() {
    var metrics = new QueryCacheMetrics();
    assertEquals(0L, metrics.getHits());
    assertEquals(0L, metrics.getMisses());
    assertEquals(0L, metrics.getSpliceFailures());
    assertEquals(0L, metrics.getK0Invalidations());
    assertEquals(0L, metrics.getOverflows());
  }

  /**
   * Each increment advances exactly its own counter and leaves the others untouched, so the five
   * metric streams stay independent.
   */
  @Test
  public void eachIncrementAdvancesOnlyItsOwnCounter() {
    var metrics = new QueryCacheMetrics();

    metrics.incrementHits();
    assertEquals(1L, metrics.getHits());
    assertEquals(0L, metrics.getMisses());
    assertEquals(0L, metrics.getSpliceFailures());
    assertEquals(0L, metrics.getK0Invalidations());
    assertEquals(0L, metrics.getOverflows());

    metrics.incrementMisses();
    assertEquals(1L, metrics.getMisses());

    metrics.incrementSpliceFailures();
    assertEquals(1L, metrics.getSpliceFailures());

    metrics.incrementK0Invalidations();
    assertEquals(1L, metrics.getK0Invalidations());

    metrics.incrementOverflows();
    assertEquals(1L, metrics.getOverflows());

    // The earlier increments are unaffected by the later ones.
    assertEquals(1L, metrics.getHits());
  }

  /**
   * Repeated increments accumulate rather than saturate, so the counters track cumulative activity
   * over the transaction's lifetime.
   */
  @Test
  public void repeatedIncrementsAccumulate() {
    var metrics = new QueryCacheMetrics();
    for (var i = 0; i < 5; i++) {
      metrics.incrementHits();
    }
    for (var i = 0; i < 3; i++) {
      metrics.incrementMisses();
    }
    assertEquals(5L, metrics.getHits());
    assertEquals(3L, metrics.getMisses());
  }

  /**
   * Each {@code increment*} call records exactly one event into its own global rate sink and none into
   * the other four, so the five global rate streams stay independent and each tracks its counter
   * one-to-one. Injecting recording sinks via the package-visible constructor makes the bridge routing
   * assertable without standing up an engine or a real metrics registry.
   */
  @Test
  public void eachIncrementRecordsOnlyItsOwnGlobalRate() {
    var hit = new RecordingRate();
    var miss = new RecordingRate();
    var splice = new RecordingRate();
    var k0 = new RecordingRate();
    var overflow = new RecordingRate();
    var metrics = new QueryCacheMetrics(hit, miss, splice, k0, overflow);

    metrics.incrementHits();
    assertEquals(1L, hit.recordCount);
    assertEquals(0L, miss.recordCount);
    assertEquals(0L, splice.recordCount);
    assertEquals(0L, k0.recordCount);
    assertEquals(0L, overflow.recordCount);

    metrics.incrementMisses();
    assertEquals(1L, miss.recordCount);

    metrics.incrementSpliceFailures();
    assertEquals(1L, splice.recordCount);

    metrics.incrementK0Invalidations();
    assertEquals(1L, k0.recordCount);

    metrics.incrementOverflows();
    assertEquals(1L, overflow.recordCount);

    // The hit sink is unaffected by the later increments.
    assertEquals(1L, hit.recordCount);
  }

  /**
   * The global rate sink sees exactly as many events as the per-tx counter advances, confirming the
   * bridge fires once per increment rather than once per holder.
   */
  @Test
  public void repeatedIncrementsRecordOnePerCounterStep() {
    var hit = new RecordingRate();
    var metrics =
        new QueryCacheMetrics(hit, new RecordingRate(), new RecordingRate(), new RecordingRate(),
            new RecordingRate());
    for (var i = 0; i < 4; i++) {
      metrics.incrementHits();
    }
    assertEquals(4L, metrics.getHits());
    assertEquals(4L, hit.recordCount);
  }
}
