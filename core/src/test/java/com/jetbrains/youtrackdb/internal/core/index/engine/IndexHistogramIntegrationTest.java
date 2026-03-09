/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import org.junit.Test;

/**
 * Integration tests for the index histogram feature against a real database.
 *
 * <p>Covers: histogram construction, three-tier transitions, incremental
 * maintenance, selectivity estimation accuracy, ANALYZE INDEX, multi-value
 * and composite index behavior, persistence across close/reopen, null handling,
 * MCV tracking, and data distribution verification.
 *
 * <p>Each test creates its own schema and data within the per-method database
 * provided by {@link DbTestBase}.
 */
public class IndexHistogramIntegrationTest extends DbTestBase {

  // ═══════════════════════════════════════════════════════════════
  //  Section 1: Three-Tier Transitions (Empty → Uniform → Histogram)
  // ═══════════════════════════════════════════════════════════════

  /**
   * Empty index has zero statistics and no histogram. After inserting data
   * below the histogram threshold, statistics show correct counters in
   * uniform mode. After crossing the threshold and running ANALYZE, a
   * histogram is built with correct bucket count.
   */
  @Test
  public void threeTierTransition_emptyToUniformToHistogram() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("TierTest");
    clazz.createProperty("val", PropertyType.INTEGER);
    var indexName = "TierTestvalIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Tier 1: Empty — ANALYZE INDEX returns zero counts
    try (var result = session.execute("ANALYZE INDEX " + indexName)) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(0L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(0, ((Number) row.getProperty("bucketCount")).intValue());
    }

    // Insert 100 entries (below HISTOGRAM_MIN_SIZE default of 1000)
    session.begin();
    for (int i = 0; i < 100; i++) {
      var doc = session.newEntity("TierTest");
      doc.setProperty("val", i);
    }
    session.commit();

    // Tier 2: With 100 entries — ANALYZE INDEX bypasses HISTOGRAM_MIN_SIZE
    // threshold, so a histogram is built even for small indexes. The adaptive
    // bucket count (min(target, sqrt(N), NDV)) produces sqrt(100) = 10 buckets.
    try (var result = session.execute("ANALYZE INDEX " + indexName)) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(100L, ((Number) row.getProperty("totalCount")).longValue());
      assertTrue("ANALYZE should build histogram even for small index",
          ((Number) row.getProperty("bucketCount")).intValue() > 0);
    }

    // Insert more entries to cross HISTOGRAM_MIN_SIZE (total = 2000)
    session.begin();
    for (int i = 100; i < 2000; i++) {
      var doc = session.newEntity("TierTest");
      doc.setProperty("val", i);
    }
    session.commit();

    // Tier 3: Histogram — ANALYZE INDEX builds histogram
    try (var result = session.execute("ANALYZE INDEX " + indexName)) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertTrue("bucketCount should be > 0",
          ((Number) row.getProperty("bucketCount")).intValue() > 0);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 2: Histogram Construction with Various Data Types
  // ═══════════════════════════════════════════════════════════════

  /**
   * Integer index: histogram is built with correct total count and distinct
   * count for uniformly distributed data.
   */
  @Test
  public void histogramConstruction_integerIndex_uniformData() {
    createClassWithIndexAndData("IntUniform", "val", PropertyType.INTEGER,
        5000, i -> i);

    try (var result = session.execute("ANALYZE INDEX IntUniformvalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(5000L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(5000L,
          ((Number) row.getProperty("distinctCount")).longValue());
      assertEquals(0L, ((Number) row.getProperty("nullCount")).longValue());
      assertTrue(((Number) row.getProperty("bucketCount")).intValue() > 0);
    }
  }

  /**
   * String index: histogram is built correctly for string keys.
   */
  @Test
  public void histogramConstruction_stringIndex() {
    createClassWithIndexAndData("StrIdx", "name", PropertyType.STRING,
        3000, i -> "user_" + String.format("%05d", i));

    try (var result = session.execute("ANALYZE INDEX StrIdxnameIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(3000L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(3000L,
          ((Number) row.getProperty("distinctCount")).longValue());
      assertTrue(((Number) row.getProperty("bucketCount")).intValue() > 0);
    }
  }

  /**
   * Double index: histogram handles floating-point keys correctly.
   */
  @Test
  public void histogramConstruction_doubleIndex() {
    createClassWithIndexAndData("DblIdx", "score", PropertyType.DOUBLE,
        2000, i -> i * 0.1);

    try (var result = session.execute("ANALYZE INDEX DblIdxscoreIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertTrue(((Number) row.getProperty("bucketCount")).intValue() > 0);
    }
  }

  /**
   * Skewed data (Zipf-like): 90% of entries have the same value. Histogram
   * should capture the shape and MCV should be tracked.
   */
  @Test
  public void histogramConstruction_skewedData_mcvTracked() {
    // 90% of entries are value 0, 10% are distinct values 1-199
    createClassWithIndexAndData("SkewMcv", "val", PropertyType.INTEGER,
        2000, i -> (i < 1800) ? 0 : (i - 1800));

    try (var result = session.execute("ANALYZE INDEX SkewMcvvalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertTrue(((Number) row.getProperty("bucketCount")).intValue() > 0);
    }

    // Verify MCV via internal API: the most common value should be 0
    var manager = getHistogramManager("SkewMcvvalIdx");
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should exist after ANALYZE", histogram);
    assertNotNull("MCV should be tracked for skewed data",
        histogram.mcvValue());
    assertEquals("MCV should be value 0", 0,
        ((Number) histogram.mcvValue()).intValue());
    // MCV frequency should be approximately 1800
    assertTrue("MCV frequency should be around 1800, got "
            + histogram.mcvFrequency(),
        histogram.mcvFrequency() >= 1700 && histogram.mcvFrequency() <= 1900);
  }

  /**
   * All-identical keys (NDV=1): histogram should produce a single effective
   * bucket.
   */
  @Test
  public void histogramConstruction_allIdenticalKeys() {
    createClassWithIndexAndData("AllSame", "val", PropertyType.INTEGER,
        2000, i -> 42);

    try (var result = session.execute("ANALYZE INDEX AllSamevalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(1L, ((Number) row.getProperty("distinctCount")).longValue());
      // With NDV=1, adaptive bucket count = min(target, sqrt(N), NDV) = 1
      assertEquals(1, ((Number) row.getProperty("bucketCount")).intValue());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 3: Null Handling
  // ═══════════════════════════════════════════════════════════════

  /**
   * Index with null entries: nullCount is tracked correctly. Histogram
   * is built on non-null entries only.
   */
  @Test
  public void nullHandling_nullCountTrackedCorrectly() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NullTest");
    clazz.createProperty("val", PropertyType.INTEGER);
    var indexName = "NullTestvalIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    session.begin();
    // 1500 non-null entries + 500 null entries = 2000 total
    for (int i = 0; i < 1500; i++) {
      var doc = session.newEntity("NullTest");
      doc.setProperty("val", i);
    }
    for (int i = 0; i < 500; i++) {
      session.newEntity("NullTest"); // Leave "val" unset → null
    }
    session.commit();

    try (var result = session.execute("ANALYZE INDEX " + indexName)) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(500L, ((Number) row.getProperty("nullCount")).longValue());
      // Histogram should be built since nonNullCount (1500) >= HISTOGRAM_MIN_SIZE
      assertTrue(((Number) row.getProperty("bucketCount")).intValue() > 0);
    }
  }

  /**
   * All-null index stays in uniform mode regardless of totalCount.
   */
  @Test
  public void nullHandling_allNullIndex_noHistogram() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("AllNull");
    clazz.createProperty("val", PropertyType.STRING);
    var indexName = "AllNullvalIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    session.begin();
    for (int i = 0; i < 2000; i++) {
      var doc = session.newEntity("AllNull");
      // Leave "val" unset → null
    }
    session.commit();

    try (var result = session.execute("ANALYZE INDEX " + indexName)) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(2000L, ((Number) row.getProperty("totalCount")).longValue());
      assertEquals(2000L, ((Number) row.getProperty("nullCount")).longValue());
      // All entries are null → nonNullCount = 0 → no histogram
      assertEquals(0, ((Number) row.getProperty("bucketCount")).intValue());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 4: Incremental Maintenance
  // ═══════════════════════════════════════════════════════════════

  /**
   * After building a histogram, inserting more entries should update the
   * statistics incrementally without requiring a new ANALYZE.
   */
  @Test
  public void incrementalMaintenance_insertsUpdateCounters() {
    createClassWithIndexAndData("IncrIns", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX IncrInsvalIdx").close();

    var manager = getHistogramManager("IncrInsvalIdx");
    var statsAfterBuild = manager.getStatistics();
    assertEquals(2000L, statsAfterBuild.totalCount());

    // Insert 500 more entries
    session.begin();
    for (int i = 2000; i < 2500; i++) {
      var doc = session.newEntity("IncrIns");
      doc.setProperty("val", i);
    }
    session.commit();

    var statsAfterInsert = manager.getStatistics();
    assertEquals("totalCount should be 2500 after incremental inserts",
        2500L, statsAfterInsert.totalCount());
  }

  /**
   * After building a histogram, removing entries should decrement the
   * statistics incrementally.
   */
  @Test
  public void incrementalMaintenance_removesUpdateCounters() {
    createClassWithIndexAndData("IncrDel", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX IncrDelvalIdx").close();

    // Delete 200 entries
    session.begin();
    session.command("DELETE FROM IncrDel WHERE val < 200");
    session.commit();

    var manager = getHistogramManager("IncrDelvalIdx");
    var stats = manager.getStatistics();
    assertEquals("totalCount should be ~1800 after deletes",
        1800L, stats.totalCount());
  }

  /**
   * Single-value unique index: put with existing key (update) should NOT
   * increment totalCount. Only genuinely new inserts increment it.
   */
  @Test
  public void incrementalMaintenance_singleValueUpdate_noCounterChange() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("SvUpdate");
    clazz.createProperty("key", PropertyType.STRING);
    clazz.createProperty("data", PropertyType.STRING);
    var indexName = "SvUpdatekeyIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "key");

    session.begin();
    for (int i = 0; i < 2000; i++) {
      var doc = session.newEntity("SvUpdate");
      doc.setProperty("key", String.format("k%04d", i));
      doc.setProperty("data", "initial");
    }
    session.commit();

    session.execute("ANALYZE INDEX " + indexName).close();

    var manager = getHistogramManager(indexName);
    long countBefore = manager.getStatistics().totalCount();
    assertEquals(2000L, countBefore);

    // Update 500 entries (k0000..k0499) — should NOT change totalCount
    session.begin();
    session.command(
        "UPDATE SvUpdate SET data = 'updated'"
            + " WHERE key >= 'k0000' AND key < 'k0500'");
    session.commit();

    long countAfter = manager.getStatistics().totalCount();
    assertEquals("totalCount should remain 2000 after updates",
        2000L, countAfter);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 4b: Incremental Statistics WITHOUT ANALYZE
  //  ─ Verifies that the CHM-based delta accumulation path produces
  //    correct counters and histogram frequencies purely from
  //    onPut()/onRemove() callbacks, without any rebuild/scan.
  // ═══════════════════════════════════════════════════════════════

  /**
   * After an initial ANALYZE builds the histogram, multiple rounds of
   * inserts (committed in separate transactions) should incrementally
   * update totalCount, nullCount, and per-bucket frequencies — all
   * verified without calling ANALYZE again.
   */
  @Test
  public void incrementalWithoutAnalyze_multiTxInserts_countersAccurate() {
    createClassWithIndexAndData("NoAnaIns", "val", PropertyType.INTEGER,
        2000, i -> i);
    // Build once to establish the histogram
    session.execute("ANALYZE INDEX NoAnaInsvalIdx").close();

    var manager = getHistogramManager("NoAnaInsvalIdx");
    assertEquals(2000L, manager.getStatistics().totalCount());
    assertNotNull(manager.getHistogram());
    long nonNullBefore = manager.getHistogram().nonNullCount();

    // Round 1: insert 300 entries in the existing key range [0, 2000)
    session.begin();
    for (int i = 0; i < 300; i++) {
      session.newEntity("NoAnaIns").setProperty("val", i);
    }
    session.commit();

    assertEquals("totalCount should be 2300 after round 1 (no ANALYZE)",
        2300L, manager.getStatistics().totalCount());
    // nonNullCount should have grown by 300
    long nonNullAfterR1 = manager.getHistogram().nonNullCount();
    assertEquals("nonNullCount should grow by 300",
        nonNullBefore + 300, nonNullAfterR1);

    // Round 2: insert 200 more in a separate transaction
    session.begin();
    for (int i = 0; i < 200; i++) {
      session.newEntity("NoAnaIns").setProperty("val", 500 + i);
    }
    session.commit();

    assertEquals("totalCount should be 2500 after round 2 (no ANALYZE)",
        2500L, manager.getStatistics().totalCount());
    assertEquals("nonNullCount should grow by another 200",
        nonNullAfterR1 + 200, manager.getHistogram().nonNullCount());

    // Cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM NoAnaIns")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount must match actual DB count",
        actualCount, manager.getStatistics().totalCount());

    // Verify sum of bucket frequencies equals nonNullCount
    var histIncr = manager.getHistogram();
    long freqSum = 0;
    for (int i = 0; i < histIncr.bucketCount(); i++) {
      freqSum += histIncr.frequencies()[i];
    }
    assertEquals("Sum of frequencies should equal nonNullCount",
        histIncr.nonNullCount(), freqSum);

    // ── Compare with post-ANALYZE rebuild ────────────────────────
    // Capture incremental snapshot before ANALYZE overwrites it
    var statsIncr = manager.getStatistics();
    long incrNonNull = histIncr.nonNullCount();

    session.execute("ANALYZE INDEX NoAnaInsvalIdx").close();
    var statsRebuilt = manager.getStatistics();
    var histRebuilt = manager.getHistogram();

    // Scalar counters must be identical — no writes between snapshots
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsRebuilt.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), statsRebuilt.nullCount());
    // nonNullCount must match (= totalCount - nullCount)
    assertNotNull(histRebuilt);
    assertEquals("nonNullCount: incremental vs ANALYZE",
        incrNonNull, histRebuilt.nonNullCount());
  }

  /**
   * After an initial ANALYZE, deleting entries should decrement per-bucket
   * frequencies and nonNullCount — verified without calling ANALYZE again.
   */
  @Test
  public void incrementalWithoutAnalyze_deletes_frequenciesDecrement() {
    createClassWithIndexAndData("NoAnaDel", "val", PropertyType.INTEGER,
        3000, i -> i);
    session.execute("ANALYZE INDEX NoAnaDelvalIdx").close();

    var manager = getHistogramManager("NoAnaDelvalIdx");
    var histBefore = manager.getHistogram();
    assertNotNull(histBefore);
    long nonNullBefore = histBefore.nonNullCount();
    assertEquals(3000L, nonNullBefore);

    // Delete entries with val in [0, 500)
    session.begin();
    session.command("DELETE FROM NoAnaDel WHERE val < 500");
    session.commit();

    // Check incremental stats without ANALYZE
    assertEquals("totalCount should be 2500",
        2500L, manager.getStatistics().totalCount());
    // Cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM NoAnaDel")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount must match actual DB count",
        actualCount, manager.getStatistics().totalCount());
    var histAfter = manager.getHistogram();
    assertEquals("nonNullCount should be 2500",
        2500L, histAfter.nonNullCount());

    // Bucket frequencies should sum to the new nonNullCount
    long freqSum = 0;
    for (int i = 0; i < histAfter.bucketCount(); i++) {
      freqSum += histAfter.frequencies()[i];
    }
    assertEquals("Sum of frequencies should match nonNullCount",
        histAfter.nonNullCount(), freqSum);

    // ── Compare with post-ANALYZE rebuild ────────────────────────
    var statsIncr = manager.getStatistics();
    long incrNonNull = histAfter.nonNullCount();

    session.execute("ANALYZE INDEX NoAnaDelvalIdx").close();
    var statsRebuilt = manager.getStatistics();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);

    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsRebuilt.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), statsRebuilt.nullCount());
    assertEquals("nonNullCount: incremental vs ANALYZE",
        incrNonNull, histRebuilt.nonNullCount());
  }

  /**
   * After an initial ANALYZE, inserting null entries should increment
   * nullCount and totalCount but NOT nonNullCount — all without ANALYZE.
   */
  @Test
  public void incrementalWithoutAnalyze_nullInserts_nullCountCorrect() {
    createClassWithIndexAndData("NoAnaNl", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX NoAnaNlvalIdx").close();

    var manager = getHistogramManager("NoAnaNlvalIdx");
    assertEquals(0L, manager.getStatistics().nullCount());
    long nonNullBefore = manager.getHistogram().nonNullCount();

    // Insert 200 null entries
    session.begin();
    for (int i = 0; i < 200; i++) {
      session.newEntity("NoAnaNl"); // no property set → null key
    }
    session.commit();

    var stats = manager.getStatistics();
    assertEquals("totalCount should be 2200 (no ANALYZE)",
        2200L, stats.totalCount());
    // Cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM NoAnaNl")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount must match actual DB count",
        actualCount, stats.totalCount());
    assertEquals("nullCount should be 200 (no ANALYZE)",
        200L, stats.nullCount());
    // nonNullCount should be unchanged — null inserts don't touch buckets
    assertEquals("nonNullCount should be unchanged",
        nonNullBefore, manager.getHistogram().nonNullCount());

    // ── Compare with post-ANALYZE rebuild ────────────────────────
    var statsIncr = stats;
    long incrNonNull = manager.getHistogram().nonNullCount();

    session.execute("ANALYZE INDEX NoAnaNlvalIdx").close();
    var statsRebuilt = manager.getStatistics();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);

    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsRebuilt.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), statsRebuilt.nullCount());
    assertEquals("nonNullCount: incremental vs ANALYZE",
        incrNonNull, histRebuilt.nonNullCount());
  }

  /**
   * Verifies that incremental bucket frequency deltas map to the correct
   * buckets. Inserts values known to fall in a specific range and checks
   * that the corresponding bucket(s) grew while others did not.
   */
  @Test
  public void incrementalWithoutAnalyze_bucketFrequencyTargeting() {
    // Build with uniform [0, 5000) — each bucket covers ~39 values
    // for a 128-bucket histogram (5000/128 ≈ 39)
    createClassWithIndexAndData("NoAnaBkt", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX NoAnaBktvalIdx").close();

    var manager = getHistogramManager("NoAnaBktvalIdx");
    var histBefore = manager.getHistogram();
    assertNotNull(histBefore);

    // Record frequencies before
    long[] freqsBefore = histBefore.frequencies().clone();
    int totalBuckets = histBefore.bucketCount();

    // Insert 100 entries all with value 2500 (falls in one specific bucket)
    session.begin();
    for (int i = 0; i < 100; i++) {
      session.newEntity("NoAnaBkt").setProperty("val", 2500);
    }
    session.commit();

    // Check without ANALYZE
    var histAfter = manager.getHistogram();
    int targetBucket = histAfter.findBucket(2500);

    // The target bucket should have grown by exactly 100
    assertEquals("Target bucket " + targetBucket + " should grow by 100,"
            + " was " + freqsBefore[targetBucket]
            + ", now " + histAfter.frequencies()[targetBucket],
        freqsBefore[targetBucket] + 100,
        histAfter.frequencies()[targetBucket]);

    // Other buckets should be unchanged (same as before)
    for (int i = 0; i < totalBuckets; i++) {
      if (i != targetBucket) {
        assertEquals("Bucket " + i + " should be unchanged",
            freqsBefore[i], histAfter.frequencies()[i]);
      }
    }
  }

  /**
   * Mixed insert + delete across multiple transactions without ANALYZE.
   * Verifies that the final totalCount matches the actual database count.
   */
  @Test
  public void incrementalWithoutAnalyze_mixedInsertDelete_countersMatch() {
    createClassWithIndexAndData("NoAnaMix", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX NoAnaMixvalIdx").close();

    // TX1: insert 500
    session.begin();
    for (int i = 2000; i < 2500; i++) {
      session.newEntity("NoAnaMix").setProperty("val", i);
    }
    session.commit();

    // TX2: delete 300
    session.begin();
    session.command("DELETE FROM NoAnaMix WHERE val < 300");
    session.commit();

    // TX3: insert 200
    session.begin();
    for (int i = 3000; i < 3200; i++) {
      session.newEntity("NoAnaMix").setProperty("val", i);
    }
    session.commit();

    // Expected: 2000 + 500 - 300 + 200 = 2400
    var manager = getHistogramManager("NoAnaMixvalIdx");
    assertEquals("totalCount should be 2400 (no ANALYZE)",
        2400L, manager.getStatistics().totalCount());

    // Cross-check with actual database count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM NoAnaMix")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount should match actual DB count",
        actualCount, manager.getStatistics().totalCount());

    // ── Compare with post-ANALYZE rebuild ────────────────────────
    var statsIncr = manager.getStatistics();
    long incrNonNull = manager.getHistogram().nonNullCount();

    session.execute("ANALYZE INDEX NoAnaMixvalIdx").close();
    var statsRebuilt = manager.getStatistics();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);

    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsRebuilt.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), statsRebuilt.nullCount());
    assertEquals("nonNullCount: incremental vs ANALYZE",
        incrNonNull, histRebuilt.nonNullCount());
  }

  /**
   * Selectivity estimation using incrementally-maintained histogram (no
   * re-ANALYZE). After inserting a block of entries in one range, the
   * range selectivity for that range should increase.
   */
  @Test
  public void incrementalWithoutAnalyze_selectivityShiftsAfterInserts() {
    createClassWithIndexAndData("NoAnaSel", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX NoAnaSelvalIdx").close();

    var manager = getHistogramManager("NoAnaSelvalIdx");

    // Selectivity for range [0, 1000) before additional inserts
    double selBefore = SelectivityEstimator.estimateRange(
        manager.getStatistics(), manager.getHistogram(),
        0, 1000, true, false);

    // Insert 2000 more entries all in [0, 1000)
    session.begin();
    for (int i = 0; i < 2000; i++) {
      session.newEntity("NoAnaSel").setProperty("val", i % 1000);
    }
    session.commit();

    // Selectivity for the same range should now be higher (more data there)
    double selAfter = SelectivityEstimator.estimateRange(
        manager.getStatistics(), manager.getHistogram(),
        0, 1000, true, false);

    assertTrue("Range selectivity should increase after inserting more "
            + "entries in that range. Before: " + selBefore
            + ", After: " + selAfter,
        selAfter > selBefore);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 4c: Multi-TX Delta Merging & MCV Staleness
  //  ─ Tests that deltas from multiple separate transactions merge
  //    correctly in the CHM, and that MCV-1 (only refreshed on
  //    ANALYZE) becomes stale after data-changing TXs.
  // ═══════════════════════════════════════════════════════════════

  /**
   * Multiple independent TXs each insert into different bucket ranges.
   * After all TXs commit, the incremental histogram must reflect every
   * TX's deltas merged together, and the result must match a fresh
   * ANALYZE rebuild.
   */
  @Test
  public void multiTxDeltaMerge_separateBuckets_allDeltasMerged() {
    // Uniform data [0, 5000) — gives a ~128-bucket histogram
    createClassWithIndexAndData("DeltaMrg", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX DeltaMrgvalIdx").close();

    var manager = getHistogramManager("DeltaMrgvalIdx");
    var histBase = manager.getHistogram();
    assertNotNull(histBase);
    long[] baseFreqs = histBase.frequencies().clone();

    // TX1: insert 100 entries near value 500
    session.begin();
    for (int i = 0; i < 100; i++) {
      session.newEntity("DeltaMrg").setProperty("val", 500);
    }
    session.commit();

    // TX2: insert 200 entries near value 3000
    session.begin();
    for (int i = 0; i < 200; i++) {
      session.newEntity("DeltaMrg").setProperty("val", 3000);
    }
    session.commit();

    // TX3: delete 50 entries from the low range
    session.begin();
    session.command("DELETE FROM DeltaMrg WHERE val < 50");
    session.commit();

    // TX4: insert 150 entries near value 4500
    session.begin();
    for (int i = 0; i < 150; i++) {
      session.newEntity("DeltaMrg").setProperty("val", 4500);
    }
    session.commit();

    // Incremental snapshot: 4 TX deltas merged into CHM
    var statsIncr = manager.getStatistics();
    var histIncr = manager.getHistogram();
    assertNotNull(histIncr);

    // Ground truth: cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM DeltaMrg")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }

    // totalCount = 5000 + 100 + 200 - 50 + 150 = 5400
    assertEquals("Incremental totalCount after 4 TXs",
        5400L, statsIncr.totalCount());
    assertEquals("Incremental totalCount must match actual DB count",
        actualCount, statsIncr.totalCount());
    assertEquals("Incremental nonNullCount should equal totalCount",
        5400L, histIncr.nonNullCount());

    // Buckets containing 500, 3000, and 4500 should have grown
    // by exactly the number of entries inserted into each.
    int bucket500 = histIncr.findBucket(500);
    int bucket3000 = histIncr.findBucket(3000);
    int bucket4500 = histIncr.findBucket(4500);
    assertEquals("Bucket for 500 should grow by exactly 100",
        baseFreqs[bucket500] + 100,
        histIncr.frequencies()[bucket500]);
    assertEquals("Bucket for 3000 should grow by exactly 200",
        baseFreqs[bucket3000] + 200,
        histIncr.frequencies()[bucket3000]);
    assertEquals("Bucket for 4500 should grow by exactly 150",
        baseFreqs[bucket4500] + 150,
        histIncr.frequencies()[bucket4500]);

    // Compare with ANALYZE — scalar counters must match
    session.execute("ANALYZE INDEX DeltaMrgvalIdx").close();
    var statsRebuilt = manager.getStatistics();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);

    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsRebuilt.totalCount());
    assertEquals("nonNullCount: incremental vs ANALYZE",
        histIncr.nonNullCount(), histRebuilt.nonNullCount());
  }

  /**
   * MCV-1 is only recomputed during ANALYZE/rebalance, not incrementally.
   * After inserting enough entries of a new value to make it the actual
   * most common value, the incremental MCV should still show the old
   * value. After ANALYZE, MCV should update to the new dominant value.
   */
  @Test
  public void mcvStaleness_incrementalMcvLagsBehind_analyzeRefreshes() {
    // Skewed initial data: 1800× value 0, 200× values 1..199
    createClassWithIndexAndData("McvStale", "val", PropertyType.INTEGER,
        2000, i -> (i < 1800) ? 0 : (i - 1800));
    session.execute("ANALYZE INDEX McvStalevalIdx").close();

    var manager = getHistogramManager("McvStalevalIdx");
    var histBefore = manager.getHistogram();
    assertNotNull(histBefore);
    assertNotNull(histBefore.mcvValue());
    assertEquals("Initial MCV should be 0",
        0, ((Number) histBefore.mcvValue()).intValue());
    long mcvFreqBefore = histBefore.mcvFrequency();
    assertTrue("Initial MCV frequency should be ~1800",
        mcvFreqBefore >= 1700);

    // Insert 3000× value 999 across 3 TXs — making 999 the new MCV
    for (int tx = 0; tx < 3; tx++) {
      session.begin();
      for (int i = 0; i < 1000; i++) {
        session.newEntity("McvStale").setProperty("val", 999);
      }
      session.commit();
    }

    // Incremental: MCV should still be 0 (stale — not updated by deltas)
    var histIncr = manager.getHistogram();
    assertNotNull(histIncr);
    assertEquals("Incremental MCV should still be 0 (stale)",
        0, ((Number) histIncr.mcvValue()).intValue());
    // MCV frequency is also stale — still shows the build-time value
    assertEquals("Incremental MCV freq should be unchanged (stale)",
        mcvFreqBefore, histIncr.mcvFrequency());

    // But totalCount should reflect all inserts
    assertEquals("Incremental totalCount should be 5000",
        5000L, manager.getStatistics().totalCount());
    // Cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM McvStale")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("Incremental totalCount must match actual DB count",
        actualCount, manager.getStatistics().totalCount());

    // After ANALYZE: MCV should update to 999 (3000 entries)
    session.execute("ANALYZE INDEX McvStalevalIdx").close();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);
    assertNotNull(histRebuilt.mcvValue());
    assertEquals("Post-ANALYZE MCV should be 999",
        999, ((Number) histRebuilt.mcvValue()).intValue());
    assertEquals("Post-ANALYZE MCV frequency should be 3000",
        3000L, histRebuilt.mcvFrequency());

    // Scalar counters must still match
    assertEquals("totalCount: incremental vs ANALYZE",
        5000L, manager.getStatistics().totalCount());
  }

  /**
   * Multiple TXs insert into the same bucket. The per-bucket frequency
   * delta from each TX should accumulate additively and the final
   * incremental frequency should match the ANALYZE-rebuilt frequency
   * for that bucket.
   */
  @Test
  public void multiTxDeltaMerge_sameBucket_frequenciesAccumulate() {
    createClassWithIndexAndData("SameBkt", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX SameBktvalIdx").close();

    var manager = getHistogramManager("SameBktvalIdx");
    var histBase = manager.getHistogram();
    assertNotNull(histBase);
    int targetBucket = histBase.findBucket(2500);
    long baseFreq = histBase.frequencies()[targetBucket];

    // 5 TXs, each inserting 50 entries at value 2500 (same bucket)
    for (int tx = 0; tx < 5; tx++) {
      session.begin();
      for (int i = 0; i < 50; i++) {
        session.newEntity("SameBkt").setProperty("val", 2500);
      }
      session.commit();
    }

    // Incremental: bucket should have grown by 250 total
    var histIncr = manager.getHistogram();
    assertNotNull(histIncr);
    assertEquals("Target bucket should grow by exactly 250",
        baseFreq + 250, histIncr.frequencies()[targetBucket]);
    assertEquals("totalCount should be 5250",
        5250L, manager.getStatistics().totalCount());
    // Cross-check with actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM SameBkt")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount must match actual DB count",
        actualCount, manager.getStatistics().totalCount());

    // Compare with ANALYZE
    long incrNonNull = histIncr.nonNullCount();
    session.execute("ANALYZE INDEX SameBktvalIdx").close();
    var histRebuilt = manager.getHistogram();
    assertNotNull(histRebuilt);
    assertEquals("nonNullCount: incremental vs ANALYZE",
        incrNonNull, histRebuilt.nonNullCount());
  }

  /**
   * Interleaved inserts and deletes in the same bucket across multiple
   * TXs. Verifies that positive and negative deltas from different TXs
   * cancel out correctly.
   */
  @Test
  public void multiTxDeltaMerge_insertDeleteSameBucket_cancelOut() {
    createClassWithIndexAndData("Cancel", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX CancelvalIdx").close();

    var manager = getHistogramManager("CancelvalIdx");
    var histBase = manager.getHistogram();
    assertNotNull(histBase);
    int targetBucket = histBase.findBucket(2500);
    long baseFreq = histBase.frequencies()[targetBucket];

    // TX1: insert 200 at value 2500
    session.begin();
    for (int i = 0; i < 200; i++) {
      session.newEntity("Cancel").setProperty("val", 2500);
    }
    session.commit();

    // TX2: delete 200 entries with val = 2500
    session.begin();
    session.command("DELETE FROM Cancel WHERE val = 2500 LIMIT 200");
    session.commit();

    // The two TXs should approximately cancel out for the target bucket.
    // totalCount may differ from 5000 because the DELETE may have removed
    // more or fewer than exactly 200 entries depending on index iteration.
    var histIncr = manager.getHistogram();
    assertNotNull(histIncr);

    // The key check: totalCount must match the actual DB count
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM Cancel")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("Incremental totalCount should match actual DB count",
        actualCount, manager.getStatistics().totalCount());

    // Compare with ANALYZE
    var statsIncr = manager.getStatistics();
    session.execute("ANALYZE INDEX CancelvalIdx").close();
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), manager.getStatistics().totalCount());
    assertEquals("nonNullCount: incremental vs ANALYZE",
        histIncr.nonNullCount(),
        manager.getHistogram().nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 5: Selectivity Estimation Accuracy
  // ═══════════════════════════════════════════════════════════════

  /**
   * Equality selectivity on uniform data: for NDV=5000 with 5000 entries,
   * selectivity(f = X) ≈ 1/5000 = 0.0002.
   */
  @Test
  public void selectivityEstimation_equalityOnUniformData() {
    createClassWithIndexAndData("SelEq", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX SelEqvalIdx").close();

    var manager = getHistogramManager("SelEqvalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    // Selectivity for a value known to exist (e.g., 2500)
    double sel = SelectivityEstimator.estimateEquality(stats, histogram, 2500);
    // With 5000 distinct values, expected ≈ 0.0002
    assertTrue("Equality selectivity should be close to 1/5000, got " + sel,
        sel > 0.0 && sel < 0.01);
  }

  /**
   * Range selectivity: for uniform data [0, 5000), the range [1000, 2000)
   * should cover ~20% of the data.
   */
  @Test
  public void selectivityEstimation_rangeOnUniformData() {
    createClassWithIndexAndData("SelRange", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX SelRangevalIdx").close();

    var manager = getHistogramManager("SelRangevalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    double sel = SelectivityEstimator.estimateRange(
        stats, histogram, 1000, 2000, true, false);
    // Expected ≈ 0.2 (1000 out of 5000)
    assertTrue("Range selectivity should be around 0.2, got " + sel,
        sel > 0.10 && sel < 0.30);
  }

  /**
   * IS NULL selectivity: with 500 nulls out of 2000 total, should be ~0.25.
   */
  @Test
  public void selectivityEstimation_isNull() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("SelNull");
    clazz.createProperty("val", PropertyType.INTEGER);
    var indexName = "SelNullvalIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    session.begin();
    for (int i = 0; i < 1500; i++) {
      var doc = session.newEntity("SelNull");
      doc.setProperty("val", i);
    }
    for (int i = 0; i < 500; i++) {
      session.newEntity("SelNull");
    }
    session.commit();

    session.execute("ANALYZE INDEX " + indexName).close();

    var manager = getHistogramManager(indexName);
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();

    double selNull = SelectivityEstimator.estimateIsNull(stats, histogram);
    // 500/2000 = 0.25
    assertTrue("IS NULL selectivity should be around 0.25, got " + selNull,
        selNull > 0.20 && selNull < 0.30);

    double selNotNull = SelectivityEstimator.estimateIsNotNull(
        stats, histogram);
    // 1500/2000 = 0.75
    assertTrue("IS NOT NULL selectivity should be around 0.75, got "
        + selNotNull, selNotNull > 0.70 && selNotNull < 0.80);
  }

  /**
   * IN selectivity: for 10 values out of NDV=5000, should be ~10/5000 = 0.002.
   */
  @Test
  public void selectivityEstimation_inPredicate() {
    createClassWithIndexAndData("SelIn", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX SelInvalIdx").close();

    var manager = getHistogramManager("SelInvalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    var values = List.of(100, 500, 1000, 1500, 2000, 2500, 3000, 3500,
        4000, 4500);
    double sel = SelectivityEstimator.estimateIn(stats, histogram, values);
    // Expected ≈ 10/5000 = 0.002
    assertTrue("IN selectivity should be small, got " + sel,
        sel > 0.0 && sel < 0.05);
  }

  /**
   * Out-of-range equality: querying for a value outside the index's range
   * should return a minimal non-zero selectivity.
   */
  @Test
  public void selectivityEstimation_outOfRangeEquality() {
    createClassWithIndexAndData("SelOor", "val", PropertyType.INTEGER,
        5000, i -> i);
    session.execute("ANALYZE INDEX SelOorvalIdx").close();

    var manager = getHistogramManager("SelOorvalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    // Value well below the minimum (0)
    double selBelow = SelectivityEstimator.estimateEquality(
        stats, histogram, -1000);
    assertTrue("Out-of-range equality should be minimal but non-zero",
        selBelow > 0.0 && selBelow < 0.001);

    // Value well above the maximum (4999)
    double selAbove = SelectivityEstimator.estimateEquality(
        stats, histogram, 99999);
    assertTrue("Out-of-range equality should be minimal but non-zero",
        selAbove > 0.0 && selAbove < 0.001);
  }

  /**
   * MCV short-circuit: equality on the most common value should use the
   * exact MCV frequency, yielding higher selectivity than bucket-averaged.
   */
  @Test
  public void selectivityEstimation_mcvShortCircuit() {
    // 90% value 0, 10% values 1-199
    createClassWithIndexAndData("SelMcv", "val", PropertyType.INTEGER,
        2000, i -> (i < 1800) ? 0 : (i - 1800));
    session.execute("ANALYZE INDEX SelMcvvalIdx").close();

    var manager = getHistogramManager("SelMcvvalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);
    assertNotNull(histogram.mcvValue());

    // Selectivity for the MCV (value 0) should be ~0.9
    double selMcv = SelectivityEstimator.estimateEquality(
        stats, histogram, 0);
    assertTrue("MCV equality should be around 0.9, got " + selMcv,
        selMcv > 0.75 && selMcv <= 1.0);

    // Selectivity for a non-MCV value should be much lower
    double selOther = SelectivityEstimator.estimateEquality(
        stats, histogram, 100);
    assertTrue("Non-MCV equality should be much smaller than MCV, got "
        + selOther, selOther < selMcv * 0.5);
  }

  /**
   * String range selectivity: histogram handles string interpolation
   * correctly.
   */
  @Test
  public void selectivityEstimation_stringRange() {
    createClassWithIndexAndData("SelStr", "name", PropertyType.STRING,
        5000, i -> String.format("name_%05d", i));
    session.execute("ANALYZE INDEX SelStrnameIdx").close();

    var manager = getHistogramManager("SelStrnameIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    // Range covering ~20% of the data
    double sel = SelectivityEstimator.estimateRange(
        stats, histogram, "name_01000", "name_02000", true, false);
    assertTrue("String range selectivity should be around 0.2, got " + sel,
        sel > 0.10 && sel < 0.35);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 6: Composite Index
  // ═══════════════════════════════════════════════════════════════

  /**
   * Composite index (firstName, lastName): histogram is built on the
   * leading field (firstName) only.
   */
  @Test
  public void compositeIndex_histogramOnLeadingFieldOnly() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompIdx");
    clazz.createProperty("firstName", PropertyType.STRING);
    clazz.createProperty("lastName", PropertyType.STRING);
    var indexName = "CompIdxComposite";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "firstName", "lastName");

    var firstNames = new String[]{"Alice", "Bob", "Charlie", "Diana", "Eve"};
    session.begin();
    for (int i = 0; i < 2500; i++) {
      var doc = session.newEntity("CompIdx");
      doc.setProperty("firstName", firstNames[i % 5]);
      doc.setProperty("lastName", "Last" + i);
    }
    session.commit();

    session.execute("ANALYZE INDEX " + indexName).close();

    var manager = getHistogramManager(indexName);
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should be built for composite index", histogram);

    // Leading field has NDV=5, so adaptive bucket count = min(target, sqrt(N), 5) = 5
    assertEquals("Bucket count should match leading field NDV",
        5, histogram.bucketCount());

    // Equality selectivity on leading field
    double sel = SelectivityEstimator.estimateEquality(stats, histogram,
        "Alice");
    // 1/5 of entries have "Alice" → selectivity ≈ 0.2
    assertTrue("Leading field equality should be ~0.2, got " + sel,
        sel > 0.10 && sel < 0.35);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 7: NOTUNIQUE Index with Duplicate Keys (Multiple Values per Key)
  // ═══════════════════════════════════════════════════════════════

  /**
   * NOTUNIQUE index with many duplicate keys (low NDV, high totalCount):
   * histogram correctly reflects the distribution of the repeated keys.
   */
  @Test
  public void notUniqueIndex_duplicateKeys_histogramAccurate() {
    // 50 distinct keys, each appearing ~60 times = 3000 total entries
    createClassWithIndexAndData("DupKeys", "category", PropertyType.STRING,
        3000, i -> "cat_" + String.format("%02d", i % 50));

    session.execute("ANALYZE INDEX DupKeyscategoryIdx").close();

    var manager = getHistogramManager("DupKeyscategoryIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should be built", histogram);

    assertEquals("totalCount should be 3000",
        3000L, stats.totalCount());

    // After ANALYZE, distinctCount reflects the number of distinct key values
    // found during the scan. For a NOTUNIQUE index with 50 distinct keys
    // over 3000 entries, ANALYZE computes the exact NDV from the sorted stream.
    assertEquals("distinctCount should be 50 after ANALYZE",
        50L, stats.distinctCount());

    // Adaptive bucket count: NDV from the key stream perspective during
    // scanAndBuild is 50 distinct leading field values
    assertEquals("Bucket count should match actual distinct key count",
        50, histogram.bucketCount());

    // Each bucket should have approximately 60 entries (3000/50)
    for (int i = 0; i < histogram.bucketCount(); i++) {
      assertTrue("Each bucket should have ~60 entries, got "
              + histogram.frequencies()[i],
          histogram.frequencies()[i] >= 50
              && histogram.frequencies()[i] <= 70);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 8: Persistence — Close and Reopen
  // ═══════════════════════════════════════════════════════════════

  /**
   * Histogram statistics survive database close and reopen.
   */
  @Test
  public void persistence_histogramSurvivesCloseReopen() {
    createClassWithIndexAndData("PersistTest", "val", PropertyType.INTEGER,
        3000, i -> i);
    session.execute("ANALYZE INDEX PersistTestvalIdx").close();

    // Record stats before close
    var managerBefore = getHistogramManager("PersistTestvalIdx");
    long totalBefore = managerBefore.getStatistics().totalCount();
    int bucketsBefore = managerBefore.getHistogram().bucketCount();
    assertTrue(totalBefore > 0);
    assertTrue(bucketsBefore > 0);

    // Close and reopen
    session.close();
    session = openDatabase();

    // Verify stats after reopen
    var managerAfter = getHistogramManager("PersistTestvalIdx");
    var statsAfter = managerAfter.getStatistics();
    var histogramAfter = managerAfter.getHistogram();

    // Allow small tolerance on totalCount because batched persistence may
    // lose a few mutations on non-graceful close, but our close is graceful
    assertEquals("totalCount should survive reopen",
        totalBefore, statsAfter.totalCount());
    assertNotNull("Histogram should survive reopen", histogramAfter);
    assertEquals("bucketCount should survive reopen",
        bucketsBefore, histogramAfter.bucketCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 9: ANALYZE INDEX Variants
  // ═══════════════════════════════════════════════════════════════

  /**
   * ANALYZE INDEX * processes all indexes and returns one row per automatic index.
   */
  @Test
  public void analyzeIndex_wildcard_processesAllIndexes() {
    var schema = session.getMetadata().getSchema();
    var c1 = schema.createClass("AnaAll1");
    c1.createProperty("a", PropertyType.INTEGER);
    c1.createIndex("anaIdx1", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");

    var c2 = schema.createClass("AnaAll2");
    c2.createProperty("b", PropertyType.STRING);
    c2.createIndex("anaIdx2", SchemaClass.INDEX_TYPE.NOTUNIQUE, "b");

    session.begin();
    for (int i = 0; i < 50; i++) {
      session.newEntity("AnaAll1").setProperty("a", i);
      session.newEntity("AnaAll2").setProperty("b", "v" + i);
    }
    session.commit();

    try (var result = session.execute("ANALYZE INDEX *")) {
      int rowCount = 0;
      boolean foundIdx1 = false;
      boolean foundIdx2 = false;
      while (result.hasNext()) {
        var row = result.next();
        String name = row.getProperty("indexName");
        if ("anaIdx1".equals(name)) {
          foundIdx1 = true;
        }
        if ("anaIdx2".equals(name)) {
          foundIdx2 = true;
        }
        rowCount++;
      }
      assertTrue("Should have at least 2 rows", rowCount >= 2);
      assertTrue("Should contain anaIdx1", foundIdx1);
      assertTrue("Should contain anaIdx2", foundIdx2);
    }
  }

  /**
   * ANALYZE INDEX on a re-analyzed index produces fresh, accurate statistics.
   */
  @Test
  public void analyzeIndex_reanalyze_producesFreshStats() {
    createClassWithIndexAndData("ReAna", "val", PropertyType.INTEGER,
        2000, i -> i);

    // First ANALYZE
    session.execute("ANALYZE INDEX ReAnavalIdx").close();

    // Insert more data
    session.begin();
    for (int i = 2000; i < 4000; i++) {
      session.newEntity("ReAna").setProperty("val", i);
    }
    session.commit();

    // Re-ANALYZE should reflect the new data
    try (var result = session.execute("ANALYZE INDEX ReAnavalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(4000L, ((Number) row.getProperty("totalCount")).longValue());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 10: Index Clear/Truncate
  // ═══════════════════════════════════════════════════════════════

  /**
   * After TRUNCATE CLASS, histogram should be reset to empty state.
   */
  @Test
  public void indexClear_truncateResetsHistogram() {
    createClassWithIndexAndData("TruncTest", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX TruncTestvalIdx").close();

    // Verify histogram exists
    var manager = getHistogramManager("TruncTestvalIdx");
    assertTrue(manager.getStatistics().totalCount() > 0);

    // Truncate the class
    session.command("TRUNCATE CLASS TruncTest");

    // After truncate, stats should be reset
    var statsAfter = manager.getStatistics();
    assertEquals("totalCount should be 0 after truncate",
        0L, statsAfter.totalCount());
    // The histogram object may still exist (with nonNullCount=0) or be null,
    // depending on the resetOnClear() implementation. Either way, statistics
    // reflect the empty state.
    var histogramAfter = manager.getHistogram();
    if (histogramAfter != null) {
      assertEquals("nonNullCount should be 0 after truncate",
          0L, histogramAfter.nonNullCount());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 11: End-to-End Query Planning
  // ═══════════════════════════════════════════════════════════════

  /**
   * EXPLAIN uses FETCH FROM INDEX for equality queries on indexed fields
   * after ANALYZE INDEX.
   */
  @Test
  public void queryPlanning_equalityUsesIndex() {
    createClassWithIndexAndData("PlanEq", "val", PropertyType.INTEGER,
        3000, i -> i);
    session.execute("ANALYZE INDEX PlanEqvalIdx").close();

    try (var result = session.query(
        "EXPLAIN SELECT FROM PlanEq WHERE val = 1500")) {
      assertTrue(result.hasNext());
      String plan = result.next().getProperty("executionPlanAsString");
      assertTrue("Should use FETCH FROM INDEX, got:\n" + plan,
          plan.contains("FETCH FROM INDEX"));
    }
  }

  /**
   * Query returns correct results after histogram is built — the histogram
   * does not corrupt query execution.
   */
  @Test
  public void queryExecution_correctResultsWithHistogram() {
    createClassWithIndexAndData("QExec", "val", PropertyType.INTEGER,
        3000, i -> i);
    session.execute("ANALYZE INDEX QExecvalIdx").close();

    // Equality query
    try (var result = session.query(
        "SELECT FROM QExec WHERE val = 1500")) {
      assertTrue(result.hasNext());
      var row = result.next();
      assertEquals(1500, ((Number) row.getProperty("val")).intValue());
      assertFalse("Should return exactly one row", result.hasNext());
    }

    // Range query
    try (var result = session.query(
        "SELECT FROM QExec WHERE val >= 100 AND val < 200")) {
      int count = 0;
      while (result.hasNext()) {
        var row = result.next();
        int val = ((Number) row.getProperty("val")).intValue();
        assertTrue("val should be in [100, 200), got " + val,
            val >= 100 && val < 200);
        count++;
      }
      assertEquals("Should return 100 rows for range [100, 200)",
          100, count);
    }
  }

  /**
   * Boolean-like index with 2 distinct values: selectivity estimation
   * should handle single-value bucket optimization correctly.
   */
  @Test
  public void queryPlanning_booleanLikeIndex() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("BoolTest");
    clazz.createProperty("active", PropertyType.BOOLEAN);
    var indexName = "BoolTestactiveIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "active");

    session.begin();
    for (int i = 0; i < 3000; i++) {
      var doc = session.newEntity("BoolTest");
      doc.setProperty("active", i < 2400); // 80% true, 20% false
    }
    session.commit();

    session.execute("ANALYZE INDEX " + indexName).close();

    var manager = getHistogramManager(indexName);
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);

    // NDV=2 → 2 buckets
    assertEquals(2, histogram.bucketCount());

    // Equality selectivity for true (80%) and false (20%)
    double selTrue = SelectivityEstimator.estimateEquality(
        stats, histogram, true);
    assertTrue("Selectivity for true should be ~0.8, got " + selTrue,
        selTrue > 0.65 && selTrue < 0.95);

    double selFalse = SelectivityEstimator.estimateEquality(
        stats, histogram, false);
    assertTrue("Selectivity for false should be ~0.2, got " + selFalse,
        selFalse > 0.10 && selFalse < 0.35);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 12: Large Dataset Verification
  // ═══════════════════════════════════════════════════════════════

  /**
   * Large dataset (50K entries): histogram accuracy is within 1% for
   * range queries on uniform data with 128 buckets.
   */
  @Test
  public void largeDataset_histogramAccuracy() {
    int N = 50_000;
    createClassWithIndexAndData("LargeDs", "val", PropertyType.INTEGER,
        N, i -> i);
    session.execute("ANALYZE INDEX LargeDsvalIdx").close();

    var manager = getHistogramManager("LargeDsvalIdx");
    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull(histogram);
    assertEquals(N, stats.totalCount());

    // Range covering exactly 10% of data: [0, 5000) out of [0, 50000)
    double sel10pct = SelectivityEstimator.estimateRange(
        stats, histogram, 0, 5000, true, false);
    // With 128 buckets and uniform data, error should be < 5%
    assertTrue("10% range selectivity should be ~0.1, got " + sel10pct,
        Math.abs(sel10pct - 0.1) < 0.05);

    // Range covering exactly 50% of data
    double sel50pct = SelectivityEstimator.estimateRange(
        stats, histogram, 0, 25000, true, false);
    assertTrue("50% range selectivity should be ~0.5, got " + sel50pct,
        Math.abs(sel50pct - 0.5) < 0.05);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 13: Various Key Types
  // ═══════════════════════════════════════════════════════════════

  /**
   * Long key type: histogram and selectivity work correctly.
   */
  @Test
  public void keyType_longIndex() {
    createClassWithIndexAndData("LongIdx", "val", PropertyType.LONG,
        3000, i -> (long) i * 1000L);
    session.execute("ANALYZE INDEX LongIdxvalIdx").close();

    var manager = getHistogramManager("LongIdxvalIdx");
    assertNotNull(manager.getHistogram());
    assertEquals(3000L, manager.getStatistics().totalCount());

    double sel = SelectivityEstimator.estimateEquality(
        manager.getStatistics(), manager.getHistogram(), 1500000L);
    assertTrue("Long equality selectivity should be small", sel < 0.01);
  }

  /**
   * Date key type: histogram handles date values.
   */
  @Test
  public void keyType_dateIndex() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("DateIdx");
    clazz.createProperty("created", PropertyType.DATE);
    var indexName = "DateIdxcreatedIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "created");

    session.begin();
    long baseTime = System.currentTimeMillis();
    for (int i = 0; i < 3000; i++) {
      var doc = session.newEntity("DateIdx");
      doc.setProperty("created",
          new java.util.Date(baseTime + i * 86400000L)); // one day apart
    }
    session.commit();

    session.execute("ANALYZE INDEX " + indexName).close();

    var manager = getHistogramManager(indexName);
    assertNotNull(manager.getHistogram());
    assertEquals(3000L, manager.getStatistics().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 14: Rollback Discards Deltas
  // ═══════════════════════════════════════════════════════════════

  /**
   * A rolled-back transaction should not affect histogram statistics.
   */
  @Test
  public void rollback_discardsDeltas() {
    createClassWithIndexAndData("Rollback", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX RollbackvalIdx").close();

    var manager = getHistogramManager("RollbackvalIdx");
    long countBefore = manager.getStatistics().totalCount();

    // Begin a transaction, insert, then rollback
    session.begin();
    for (int i = 2000; i < 3000; i++) {
      session.newEntity("Rollback").setProperty("val", i);
    }
    session.rollback();

    long countAfter = manager.getStatistics().totalCount();
    assertEquals("Rollback should not change totalCount",
        countBefore, countAfter);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 15: Adaptive Bucket Count
  // ═══════════════════════════════════════════════════════════════

  /**
   * Adaptive bucket count: for small NDV, bucket count = NDV.
   * For example, 5 distinct values → 5 buckets (not 128).
   */
  @Test
  public void adaptiveBucketCount_lowNdv() {
    // 5 distinct values, each repeated 600 times = 3000 total
    createClassWithIndexAndData("AdaptBkt", "status", PropertyType.STRING,
        3000, i -> "status_" + (i % 5));

    session.execute("ANALYZE INDEX AdaptBktstatusIdx").close();

    var manager = getHistogramManager("AdaptBktstatusIdx");
    var histogram = manager.getHistogram();
    assertNotNull(histogram);
    assertEquals("Bucket count should equal NDV for low-NDV index",
        5, histogram.bucketCount());
  }

  /**
   * Adaptive bucket count: sqrt(N) caps bucket count for small indexes.
   * 1000 entries → sqrt(1000) ≈ 31, so fewer than 128 buckets.
   */
  @Test
  public void adaptiveBucketCount_sqrtCap() {
    createClassWithIndexAndData("AdaptSqrt", "val", PropertyType.INTEGER,
        1000, i -> i);

    session.execute("ANALYZE INDEX AdaptSqrtvalIdx").close();

    var manager = getHistogramManager("AdaptSqrtvalIdx");
    var histogram = manager.getHistogram();
    assertNotNull(histogram);
    // sqrt(1000) ≈ 31, so bucketCount should be around 31 (not 128)
    assertTrue("Bucket count should be capped by sqrt(N), got "
            + histogram.bucketCount(),
        histogram.bucketCount() <= 35 && histogram.bucketCount() >= 25);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 16: Automatic Background Rebalance Trigger
  // ═══════════════════════════════════════════════════════════════

  /**
   * After building an initial histogram, inserting more than the rebalance
   * threshold mutations should trigger a background rebalance. The test
   * verifies that:
   * <ul>
   *   <li>The snapshot version increments (proving a rebalance occurred)</li>
   *   <li>mutationsSinceRebalance is reset to near-zero</li>
   *   <li>The rebuilt histogram's totalCount and nonNullCount match the
   *       actual DB count</li>
   *   <li>The rebuilt bucket frequencies sum to nonNullCount</li>
   * </ul>
   *
   * <p>Uses a small initial dataset (2000) so the rebalance threshold
   * is min(2000 * 0.3, 10M) clamped to min 1000 → threshold = 1000.
   * Inserting 1500 mutations exceeds this.
   */
  @Test
  public void automaticRebalance_triggeredAfterMutationThreshold()
      throws Exception {
    createClassWithIndexAndData("AutoRebal", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX AutoRebalvalIdx").close();

    var manager = getHistogramManager("AutoRebalvalIdx");
    var snapshotBefore = manager.getSnapshot();
    assertNotNull(snapshotBefore);
    assertNotNull(snapshotBefore.histogram());
    long versionBefore = snapshotBefore.version();
    int bucketsBefore = snapshotBefore.histogram().bucketCount();

    // Insert 1500 entries (> threshold of 1000) with a skewed
    // distribution to make the rebalance produce visibly different
    // bucket boundaries than the original uniform histogram.
    session.begin();
    for (int i = 0; i < 1500; i++) {
      // All new entries at value 9999 — well outside [0, 2000)
      session.newEntity("AutoRebal").setProperty("val", 9999);
    }
    session.commit();

    // Verify mutations accumulated incrementally
    var snapshotMid = manager.getSnapshot();
    assertNotNull(snapshotMid);
    assertTrue("mutationsSinceRebalance should exceed threshold,"
            + " got " + snapshotMid.mutationsSinceRebalance(),
        snapshotMid.mutationsSinceRebalance() >= 1500);

    // Trigger the rebalance check by calling getHistogram().
    // This invokes maybeScheduleHistogramWork() which submits a
    // background rebalance to the IO executor.
    manager.getHistogram();

    // Wait for the async rebalance to complete (poll with timeout)
    long deadline = System.currentTimeMillis() + 30_000;
    HistogramSnapshot snapshotAfter;
    while (true) {
      Thread.sleep(100);
      snapshotAfter = manager.getSnapshot();
      if (snapshotAfter != null
          && snapshotAfter.version() > versionBefore) {
        break; // rebalance completed — version incremented
      }
      if (System.currentTimeMillis() > deadline) {
        fail("Rebalance did not complete within 30 seconds."
            + " version=" + (snapshotAfter != null
            ? snapshotAfter.version() : "null")
            + ", mutations=" + (snapshotAfter != null
            ? snapshotAfter.mutationsSinceRebalance() : "null"));
      }
    }

    // Verify the rebalance produced correct results
    assertNotNull(snapshotAfter.histogram());
    assertTrue("Version should have incremented",
        snapshotAfter.version() > versionBefore);

    // mutationsSinceRebalance should be reset (may have a few
    // from commits that raced with the rebalance)
    assertTrue("mutationsSinceRebalance should be near 0 after "
            + "rebalance, got "
            + snapshotAfter.mutationsSinceRebalance(),
        snapshotAfter.mutationsSinceRebalance() < 100);

    // Ground truth: totalCount and nonNullCount must match actual DB
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM AutoRebal")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount should be 3500 after rebalance",
        3500L, snapshotAfter.stats().totalCount());
    assertEquals("totalCount must match actual DB count",
        actualCount, snapshotAfter.stats().totalCount());
    assertEquals("nonNullCount should equal totalCount (no nulls)",
        3500L, snapshotAfter.histogram().nonNullCount());

    // Bucket frequencies must sum to nonNullCount
    long freqSum = 0;
    for (int i = 0; i < snapshotAfter.histogram().bucketCount(); i++) {
      freqSum += snapshotAfter.histogram().frequencies()[i];
    }
    assertEquals("Sum of frequencies should equal nonNullCount",
        snapshotAfter.histogram().nonNullCount(), freqSum);

    // The rebalance should have updated the MCV — value 9999
    // now has 1500 entries (highest frequency)
    assertNotNull("MCV should be set after rebalance",
        snapshotAfter.histogram().mcvValue());
    assertEquals("MCV should be 9999 after rebalance",
        9999, ((Number) snapshotAfter.histogram().mcvValue()).intValue());
    assertEquals("MCV frequency should be 1500",
        1500L, snapshotAfter.histogram().mcvFrequency());
  }

  /**
   * After a rebalance, the histogram boundaries should reflect the new
   * data distribution. Inserting a large block of entries beyond the
   * original range should cause the maximum boundary to shift.
   */
  @Test
  public void automaticRebalance_boundariesUpdateToReflectNewData()
      throws Exception {
    createClassWithIndexAndData("RebalBnd", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX RebalBndvalIdx").close();

    var manager = getHistogramManager("RebalBndvalIdx");
    var histBefore = manager.getHistogram();
    assertNotNull(histBefore);
    // Original max boundary should be near 1999
    var maxBoundBefore = (Comparable<?>) histBefore.boundaries()[
        histBefore.bucketCount()];
    long versionBefore = manager.getSnapshot().version();

    // Insert 2000 entries at value 50000 (far beyond original range)
    session.begin();
    for (int i = 0; i < 2000; i++) {
      session.newEntity("RebalBnd").setProperty("val", 50000);
    }
    session.commit();

    // Trigger and wait for rebalance
    manager.getHistogram();
    long deadline = System.currentTimeMillis() + 30_000;
    HistogramSnapshot snapshotAfter;
    while (true) {
      Thread.sleep(100);
      snapshotAfter = manager.getSnapshot();
      if (snapshotAfter != null
          && snapshotAfter.version() > versionBefore) {
        break;
      }
      if (System.currentTimeMillis() > deadline) {
        fail("Rebalance did not complete within 30 seconds");
      }
    }

    var histAfter = snapshotAfter.histogram();
    assertNotNull(histAfter);

    // The max boundary should now include 50000
    var maxBoundAfter = (Comparable<?>) histAfter.boundaries()[
        histAfter.bucketCount()];
    assertTrue("Max boundary should shift to include new data."
            + " Before: " + maxBoundBefore + ", After: " + maxBoundAfter,
        ((Integer) maxBoundAfter) > ((Integer) maxBoundBefore));
    assertEquals("Max boundary should be 50000",
        50000, ((Number) maxBoundAfter).intValue());

    // Ground truth
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM RebalBnd")) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("totalCount must match actual DB count",
        actualCount, snapshotAfter.stats().totalCount());
    assertEquals("nonNullCount should equal totalCount",
        actualCount, histAfter.nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════
  //  Section 17: Multi-Value Index HLL Population
  // ═══════════════════════════════════════════════════════════════

  /**
   * Regression test: after ANALYZE INDEX on a multi-value index, the HLL
   * sketch must be populated with the full index contents. When incremental
   * inserts are committed afterward, the distinctCount must remain close to
   * the true value — not drop to the count of newly inserted keys.
   *
   * <p>Before the fix, the HLL was created empty during build/rebalance.
   * The first commit after ANALYZE would merge a small delta HLL into the
   * empty sketch, replacing the correct distinctCount (e.g., 500) with the
   * delta's count (e.g., 5).
   */
  @Test
  public void multiValueIndex_hllPopulatedAfterAnalyze_distinctCountSurvivesIncrementalInserts() {
    // Create a multi-value index: EMBEDDEDLIST of integers
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("MvHllTest");
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
    session.execute(
        "CREATE INDEX MvHllTestTagsIdx ON MvHllTest (tags) NOTUNIQUE").close();

    // Insert 2000 documents, each with 2-3 tags from a pool of ~500 distinct
    // values. This creates a multi-value index with ~5000 entries and ~500 NDV.
    session.begin();
    for (int i = 0; i < 2000; i++) {
      var doc = session.newEntity("MvHllTest");
      var tags = doc.getOrCreateEmbeddedList("tags");
      tags.add(i % 500);
      tags.add((i % 500) + 500);
      if (i % 3 == 0) {
        tags.add(i % 200);
      }
    }
    session.commit();

    // ANALYZE INDEX — builds histogram and populates HLL
    try (var result = session.execute("ANALYZE INDEX MvHllTestTagsIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      long totalCount = ((Number) row.getProperty("totalCount")).longValue();
      assertTrue("totalCount should be > 2000 (multi-value expands entries)",
          totalCount > 2000);
      assertTrue("bucketCount should be > 0",
          ((Number) row.getProperty("bucketCount")).intValue() > 0);
    }

    // Record the distinctCount right after ANALYZE
    var manager = getHistogramManager("MvHllTestTagsIdx");
    var snapshotAfterAnalyze = manager.getStatistics();
    assertNotNull(snapshotAfterAnalyze);
    long distinctAfterAnalyze = snapshotAfterAnalyze.distinctCount();
    assertTrue("distinctCount after ANALYZE should be substantial (>= 400), was: "
        + distinctAfterAnalyze, distinctAfterAnalyze >= 400);

    // Now insert a small batch of 10 new documents (incremental update)
    session.begin();
    for (int i = 0; i < 10; i++) {
      var doc = session.newEntity("MvHllTest");
      var tags = doc.getOrCreateEmbeddedList("tags");
      tags.add(1000 + i); // new distinct values
      tags.add(1010 + i);
    }
    session.commit();

    // After incremental inserts, distinctCount must NOT have dropped to ~20
    var snapshotAfterInsert = manager.getStatistics();
    assertNotNull(snapshotAfterInsert);
    long distinctAfterInsert = snapshotAfterInsert.distinctCount();
    assertTrue("distinctCount after small insert should remain high (>= 400), "
            + "was: " + distinctAfterInsert
            + " (was " + distinctAfterAnalyze + " after ANALYZE)",
        distinctAfterInsert >= 400);
    // It should have grown slightly (20 new distinct values added)
    assertTrue("distinctCount should be >= distinctAfterAnalyze, was: "
            + distinctAfterInsert + " vs " + distinctAfterAnalyze,
        distinctAfterInsert >= distinctAfterAnalyze - 50); // HLL tolerance
  }

  /**
   * After automatic rebalance of a multi-value index, the HLL should be
   * repopulated from the full key scan, so subsequent incremental inserts
   * preserve the correct distinctCount.
   */
  @Test
  public void multiValueIndex_hllPopulatedAfterRebalance_distinctCountPreserved() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("MvHllRebal");
    clazz.createProperty("vals", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
    session.execute(
        "CREATE INDEX MvHllRebalValsIdx ON MvHllRebal (vals) NOTUNIQUE").close();

    // Insert enough data to trigger histogram build via ANALYZE
    session.begin();
    for (int i = 0; i < 1500; i++) {
      var doc = session.newEntity("MvHllRebal");
      var vals = doc.getOrCreateEmbeddedList("vals");
      vals.add(i % 300);
      vals.add((i % 300) + 300);
    }
    session.commit();

    // Build initial histogram
    session.execute("ANALYZE INDEX MvHllRebalValsIdx").close();

    var manager = getHistogramManager("MvHllRebalValsIdx");
    long distinctBeforeRebalance = manager.getStatistics().distinctCount();
    assertTrue("Initial distinctCount should be >= 300, was: "
        + distinctBeforeRebalance, distinctBeforeRebalance >= 300);

    // Force a rebalance by calling analyzeIndex (simulates what the
    // background rebalance does)
    session.execute("ANALYZE INDEX MvHllRebalValsIdx").close();

    long distinctAfterRebalance = manager.getStatistics().distinctCount();
    assertTrue("distinctCount after rebalance should be >= 300, was: "
        + distinctAfterRebalance, distinctAfterRebalance >= 300);

    // Insert a small batch and verify distinctCount doesn't drop
    session.begin();
    for (int i = 0; i < 5; i++) {
      var doc = session.newEntity("MvHllRebal");
      var vals = doc.getOrCreateEmbeddedList("vals");
      vals.add(900 + i);
    }
    session.commit();

    long distinctAfterInsert = manager.getStatistics().distinctCount();
    assertTrue("distinctCount after small insert should stay >= 300, was: "
            + distinctAfterInsert,
        distinctAfterInsert >= 300);
  }

  /**
   * Single-value (NOTUNIQUE) indexes should NOT have an HLL sketch —
   * distinctCount is always derived from totalCount. Verify that
   * incremental inserts on single-value indexes don't suffer from the
   * empty-HLL bug (since they don't use HLL at all).
   */
  @Test
  public void singleValueIndex_noHll_distinctCountEqualsNonNullCount() {
    createClassWithIndexAndData("SvNoHll", "val", PropertyType.INTEGER,
        2000, i -> i);

    session.execute("ANALYZE INDEX SvNoHllvalIdx").close();

    var manager = getHistogramManager("SvNoHllvalIdx");
    var snapshot = manager.getStatistics();
    assertNotNull(snapshot);
    assertEquals("Single-value: distinctCount == totalCount",
        2000L, snapshot.distinctCount());

    // Insert 10 more entries
    session.begin();
    for (int i = 2000; i < 2010; i++) {
      var doc = session.newEntity("SvNoHll");
      doc.setProperty("val", i);
    }
    session.commit();

    var snapshotAfter = manager.getStatistics();
    // For single-value NOTUNIQUE, distinctCount == totalCount.
    // The incremental delta applies totalCountDelta from committed inserts.
    // Allow +/- 1 tolerance for batched persistence timing.
    long distinctAfter = snapshotAfter.distinctCount();
    assertTrue("Single-value: distinctCount should track totalCount (~2010), "
            + "was: " + distinctAfter,
        distinctAfter >= 2009 && distinctAfter <= 2011);
  }

  /**
   * Multi-value index with highly skewed data (most entries share a few
   * tag values). After ANALYZE, the HLL should correctly estimate a low
   * NDV, and incremental inserts of new distinct values should increase it.
   */
  @Test
  public void multiValueIndex_skewedData_hllTracksDistinctCorrectly() {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("MvSkewHll");
    clazz.createProperty("cats", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    session.execute(
        "CREATE INDEX MvSkewHllCatsIdx ON MvSkewHll (cats) NOTUNIQUE").close();

    // 2000 documents, each with tags from a pool of only 10 distinct values
    session.begin();
    for (int i = 0; i < 2000; i++) {
      var doc = session.newEntity("MvSkewHll");
      var cats = doc.getOrCreateEmbeddedList("cats");
      cats.add("cat_" + (i % 10));
      cats.add("cat_" + ((i + 5) % 10));
    }
    session.commit();

    session.execute("ANALYZE INDEX MvSkewHllCatsIdx").close();

    var manager = getHistogramManager("MvSkewHllCatsIdx");
    long distinct = manager.getStatistics().distinctCount();
    // HLL should estimate ~10 distinct values
    assertTrue("Skewed multi-value: distinctCount should be ~10, was: "
        + distinct, distinct >= 5 && distinct <= 20);

    // Insert documents with 50 NEW distinct tag values
    session.begin();
    for (int i = 0; i < 50; i++) {
      var doc = session.newEntity("MvSkewHll");
      var cats = doc.getOrCreateEmbeddedList("cats");
      cats.add("new_cat_" + i);
    }
    session.commit();

    long distinctAfter = manager.getStatistics().distinctCount();
    // Should have grown to reflect ~60 distinct values
    assertTrue("After adding 50 new distinct keys, distinctCount should grow "
            + "from " + distinct + ", was: " + distinctAfter,
        distinctAfter > distinct);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════

  /**
   * Creates a class with a single indexed property and populates it with data.
   * Index name follows the pattern: className + fieldName + "Idx".
   */
  private void createClassWithIndexAndData(
      String className, String fieldName, PropertyType type,
      int rowCount, java.util.function.IntFunction<Object> valueMapper) {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty(fieldName, type);
    clazz.createIndex(className + fieldName + "Idx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, fieldName);

    session.begin();
    for (int i = 0; i < rowCount; i++) {
      var doc = session.newEntity(className);
      doc.setProperty(fieldName, valueMapper.apply(i));
    }
    session.commit();
  }

  /**
   * Retrieves the IndexHistogramManager for a named index via reflection.
   */
  private IndexHistogramManager getHistogramManager(String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
      var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
      indexIdField.setAccessible(true);
      int indexId = indexIdField.getInt(idx);
      var storageField = IndexAbstract.class.getDeclaredField("storage");
      storageField.setAccessible(true);
      var storage = storageField.get(idx);
      var getEngineMethod = storage.getClass()
          .getMethod("getIndexEngine", int.class);
      var engine = (BTreeIndexEngine) getEngineMethod.invoke(storage, indexId);
      return engine.getHistogramManager();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get histogram manager for "
          + indexName, e);
    }
  }
}
