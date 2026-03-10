package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link HistogramSnapshot} — immutable point-in-time snapshot of
 * index statistics and histogram.
 */
public class HistogramSnapshotTest {

  @Test
  public void emptySnapshotHasZeroCounters() {
    var stats = new IndexStatistics(0, 0, 0);
    var snapshot = new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false);

    assertEquals(0, snapshot.stats().totalCount());
    assertEquals(0, snapshot.stats().distinctCount());
    assertEquals(0, snapshot.stats().nullCount());
    assertNull(snapshot.histogram());
    assertEquals(0, snapshot.mutationsSinceRebalance());
    assertEquals(0, snapshot.totalCountAtLastBuild());
    assertEquals(0, snapshot.version());
    assertFalse(snapshot.hasDriftedBuckets());
    assertNull(snapshot.hllSketch());
  }

  @Test
  public void snapshotPreservesAllFields() {
    var stats = new IndexStatistics(1000, 800, 50);
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {1, 50, 100},
        new long[] {500, 450},
        new long[] {40, 35},
        950,
        50,
        100L);
    var hll = new HyperLogLogSketch();
    hll.add(42L);

    var snapshot = new HistogramSnapshot(
        stats, histogram, 200, 900, 3, true, hll, false);

    assertEquals(1000, snapshot.stats().totalCount());
    assertEquals(800, snapshot.stats().distinctCount());
    assertEquals(50, snapshot.stats().nullCount());
    assertNotNull(snapshot.histogram());
    assertEquals(2, snapshot.histogram().bucketCount());
    assertEquals(200, snapshot.mutationsSinceRebalance());
    assertEquals(900, snapshot.totalCountAtLastBuild());
    assertEquals(3, snapshot.version());
    assertTrue(snapshot.hasDriftedBuckets());
    assertNotNull(snapshot.hllSketch());
    assertFalse(snapshot.hllOnPage1());
  }

  @Test
  public void snapshotPreservesHllOnPage1Flag() {
    var stats = new IndexStatistics(500, 400, 10);
    var hll = new HyperLogLogSketch();
    hll.add(12345L);

    var snapshot = new HistogramSnapshot(
        stats, null, 0, 500, 0, false, hll, true);

    assertTrue(snapshot.hllOnPage1());
    assertNotNull(snapshot.hllSketch());
  }

  @Test
  public void testSnapshotEquality_identicalFields_areEqual() {
    var stats = new IndexStatistics(100, 100, 5);
    var s1 = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    var s2 = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    assertEquals(s1, s2);
    assertEquals(s1.hashCode(), s2.hashCode());
  }

  @Test
  public void testSnapshotEquality_differentVersion_notEqual() {
    var stats = new IndexStatistics(100, 100, 5);
    var s1 = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    var s2 = new HistogramSnapshot(
        stats, null, 0, 100, 1, false, null, false);
    assertNotEquals(s1, s2);
  }

  @Test
  public void snapshotWithZeroFrequencyHistogram() {
    // Degenerate state after heavy deletions: all frequencies are 0
    var stats = new IndexStatistics(0, 0, 0);
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0},
        new long[] {0, 0},
        0,
        null, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 500, 1000, 3, true, null, false);

    assertNotNull(snapshot.histogram());
    assertEquals(0, snapshot.histogram().nonNullCount());
    assertTrue(snapshot.hasDriftedBuckets());
  }
}
