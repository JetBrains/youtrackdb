package com.jetbrains.youtrackdb.internal.core.index.engine;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link IndexStatistics}.
 *
 * <p>Covers: record field access, equality, and basic invariant verification.
 * IndexStatistics is a simple record; most of its behavior is exercised
 * indirectly through IndexHistogramManager tests (Step 2). These tests verify
 * the record's direct API surface.
 */
public class IndexStatisticsTest {

  @Test
  public void testRecordFieldAccess() {
    var stats = new IndexStatistics(1000, 500, 10);
    Assert.assertEquals(1000, stats.totalCount());
    Assert.assertEquals(500, stats.distinctCount());
    Assert.assertEquals(10, stats.nullCount());
  }

  @Test
  public void testRecordEquality() {
    var a = new IndexStatistics(100, 50, 5);
    var b = new IndexStatistics(100, 50, 5);
    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void testRecordInequality() {
    var a = new IndexStatistics(100, 50, 5);
    var b = new IndexStatistics(100, 50, 6);
    Assert.assertNotEquals(a, b);
  }

  @Test
  public void testZeroCounts() {
    var stats = new IndexStatistics(0, 0, 0);
    Assert.assertEquals(0, stats.totalCount());
    Assert.assertEquals(0, stats.distinctCount());
    Assert.assertEquals(0, stats.nullCount());
  }

  @Test
  public void testLargeValues() {
    // Verify no overflow for large index sizes
    long large = Long.MAX_VALUE / 2;
    var stats = new IndexStatistics(large, large - 100, 100);
    Assert.assertEquals(large, stats.totalCount());
    Assert.assertEquals(large - 100, stats.distinctCount());
    Assert.assertEquals(100, stats.nullCount());
  }

  @Test
  public void testToStringContainsFields() {
    var stats = new IndexStatistics(42, 30, 2);
    String str = stats.toString();
    Assert.assertTrue(
        "toString should contain totalCount",
        str.contains("42"));
    Assert.assertTrue(
        "toString should contain distinctCount",
        str.contains("30"));
    Assert.assertTrue(
        "toString should contain nullCount",
        str.contains("2"));
  }
}
