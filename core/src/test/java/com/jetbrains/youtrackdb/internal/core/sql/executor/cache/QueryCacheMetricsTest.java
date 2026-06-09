package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Verifies the per-transaction tx-result-cache counter holder. Each counter starts at zero, each
 * increment advances only its own counter by one, and repeated increments accumulate. The class is
 * a plain single-threaded holder confined to its owning thread, so no concurrency is exercised here;
 * the increments are wired into the cache lookup/eviction paths in later steps.
 */
public class QueryCacheMetricsTest {

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
}
