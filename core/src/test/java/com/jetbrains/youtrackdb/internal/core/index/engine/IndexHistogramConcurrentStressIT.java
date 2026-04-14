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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Long-running randomized stress tests for index histogram correctness under
 * concurrent parallel transactions.
 *
 * <p>These are integration tests (IT suffix) that run via failsafe during
 * {@code mvn verify -P ci-integration-tests}. Each test spawns multiple writer
 * threads that perform random inserts, updates, and deletes over a configurable
 * duration. After all writers finish, the test verifies that:
 * <ul>
 *   <li>Histogram totalCount matches the actual index size (via full scan count)</li>
 *   <li>After ANALYZE INDEX, the histogram is accurate against the ground truth</li>
 *   <li>HLL-based distinctCount is within acceptable error for multi-value indexes</li>
 *   <li>MCV-1 tracking correctly identifies the most common value</li>
 *   <li>Selectivity estimates are reasonable given the actual data distribution</li>
 * </ul>
 */
@Category(SequentialTest.class)
public class IndexHistogramConcurrentStressIT extends DbTestBase {

  /** Duration of each stress phase in seconds. */
  private static final int DURATION_SECONDS = 120;
  /** Number of concurrent writer threads per test. */
  private static final int NUM_WRITERS = 4;

  /** Duration of the CI smoke test in seconds. */
  private static final int SMOKE_DURATION_SECONDS = 10;
  /** Number of writer threads for the CI smoke test. */
  private static final int SMOKE_NUM_WRITERS = 2;

  // ═══════════════════════════════════════════════════════════════
  //  Smoke test: short concurrent histogram exercise (runs in CI)
  // ═══════════════════════════════════════════════════════════════

  /**
   * Quick concurrent histogram smoke test that runs in CI. Verifies
   * basic correctness of concurrent inserts + ANALYZE without the
   * full 2-minute stress duration. Catches deadlocks, NPEs, and
   * gross consistency violations.
   */
  @Test
  public void concurrentHistogram_smokeTest() throws Exception {
    final int KEY_RANGE = 10_000;
    final String className = "SmokeInt";
    final String indexName = className + "valIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex(
        indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Seed initial data so histogram can be built
    session.begin();
    for (int i = 0; i < 2000; i++) {
      session.newEntity(className).setProperty("val", i);
    }
    session.commit();
    session.execute("ANALYZE INDEX " + indexName).close();

    // Stress phase: concurrent inserts and deletes
    var errors = new AtomicReference<Exception>();
    var stop = new AtomicBoolean(false);
    var opsCount = new AtomicLong(0);
    var latch = new CountDownLatch(SMOKE_NUM_WRITERS);

    for (int w = 0; w < SMOKE_NUM_WRITERS; w++) {
      final int writerId = w;
      new Thread(() -> {
        var rng = new Random(
            writerId * 31L + System.nanoTime());
        DatabaseSessionEmbedded localSession = null;
        try {
          localSession = openDatabase();
          while (!stop.get()) {
            try {
              localSession.begin();
              int batchSize = 10 + rng.nextInt(40);
              for (int j = 0; j < batchSize; j++) {
                if (rng.nextDouble() < 0.7) {
                  var doc = localSession.newEntity(className);
                  doc.setProperty("val", rng.nextInt(KEY_RANGE));
                } else {
                  int targetVal = rng.nextInt(KEY_RANGE);
                  localSession.command(
                      "DELETE FROM " + className
                          + " WHERE val = ? LIMIT 1",
                      targetVal);
                }
              }
              localSession.commit();
              opsCount.addAndGet(batchSize);
            } catch (Exception e) {
              try {
                localSession.rollback();
              } catch (Exception ignored) {
              }
            }
          }
        } catch (Exception e) {
          errors.compareAndSet(null, e);
        } finally {
          if (localSession != null && !localSession.isClosed()) {
            localSession.close();
          }
          latch.countDown();
        }
      }, "smoke-writer-" + writerId).start();
    }

    Thread.sleep(SMOKE_DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue(
        "All writers should finish within 30s after stop signal",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println(
        "[Smoke] Total operations: " + opsCount.get());

    // Verify totalCount matches actual count
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var stats = manager.getStatistics();

    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualCount =
          ((Number) result.next().getProperty("cnt")).longValue();
    }

    assertEquals(
        "totalCount should match actual entry count",
        actualCount, stats.totalCount());

    // ANALYZE and verify histogram is accurate
    session.execute("ANALYZE INDEX " + indexName).close();

    var statsAnalyzed = manager.getStatistics();
    var histogramAnalyzed = manager.getHistogram();

    assertEquals("totalCount after ANALYZE should match actual",
        actualCount, statsAnalyzed.totalCount());

    // After ANALYZE with 200 entries, a histogram should exist
    assertNotNull(
        "Histogram should be built after ANALYZE with 200 entries",
        histogramAnalyzed);

    assertTrue("bucketCount should be > 0 after ANALYZE",
        histogramAnalyzed.bucketCount() > 0);
    assertTrue("distinctCount should be > 0 after ANALYZE",
        statsAnalyzed.distinctCount() > 0);

    long freqSum = 0;
    for (int i = 0; i < histogramAnalyzed.bucketCount(); i++) {
      freqSum += histogramAnalyzed.frequencies()[i];
    }
    assertEquals(
        "Sum of bucket frequencies should equal nonNullCount",
        histogramAnalyzed.nonNullCount(), freqSum);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 1: Single-Value Integer Index — Concurrent Inserts +
  //  Deletes
  // ═══════════════════════════════════════════════════════════════

  /**
   * Multiple threads concurrently insert and delete integer keys
   * for 2 minutes. After the stress phase, the histogram's
   * totalCount and bucket frequencies are verified against a
   * ground-truth full scan — first incrementally (no ANALYZE),
   * then after a fresh ANALYZE INDEX rebuild.
   */
  @Test(timeout = 300_000)
  public void singleValueIntegerIndex_concurrentInsertsAndDeletes()
      throws Exception {
    // JUnit 4 timeout runs the test on a different thread than @Before,
    // so we must re-activate the session on this thread.
    session.activateOnCurrentThread();
    final int KEY_RANGE = 100_000;
    final String className = "StressInt";
    final String indexName = className + "valIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Seed initial data so histogram can be built
    session.begin();
    for (int i = 0; i < 5000; i++) {
      session.newEntity(className).setProperty("val", i);
    }
    session.commit();
    session.execute("ANALYZE INDEX " + indexName).close();

    // Stress phase: concurrent inserts and deletes
    var errors = new AtomicReference<Exception>();
    var stop = new AtomicBoolean(false);
    var opsCount = new AtomicLong(0);
    var latch = new CountDownLatch(NUM_WRITERS);

    for (int w = 0; w < NUM_WRITERS; w++) {
      final int writerId = w;
      new Thread(() -> {
        var rng = new Random(writerId * 31L + System.nanoTime());
        DatabaseSessionEmbedded localSession = null;
        try {
          localSession = openDatabase();
          while (!stop.get()) {
            try {
              localSession.begin();
              int batchSize = 10 + rng.nextInt(40);
              for (int j = 0; j < batchSize; j++) {
                if (rng.nextDouble() < 0.7) {
                  // Insert
                  var doc = localSession.newEntity(className);
                  doc.setProperty("val", rng.nextInt(KEY_RANGE));
                } else {
                  // Delete a random record
                  int targetVal = rng.nextInt(KEY_RANGE);
                  localSession.command(
                      "DELETE FROM " + className
                          + " WHERE val = ? LIMIT 1",
                      targetVal);
                }
              }
              localSession.commit();
              opsCount.addAndGet(batchSize);
            } catch (Exception e) {
              // Concurrent modification or other transient errors are expected
              try {
                localSession.rollback();
              } catch (Exception ignored) {
              }
            }
          }
        } catch (Exception e) {
          errors.compareAndSet(null, e);
        } finally {
          if (localSession != null && !localSession.isClosed()) {
            localSession.close();
          }
          latch.countDown();
        }
      }, "stress-writer-" + writerId).start();
    }

    // Let the stress run for the configured duration
    Thread.sleep(DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue("All writers should finish within 30s after stop signal",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println("[StressInt] Total operations: " + opsCount.get());

    // ── Phase 1: verify INCREMENTAL stats (no ANALYZE) ──────────
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var statsIncremental = manager.getStatistics();
    var histogramIncremental = manager.getHistogram();

    // Ground truth: count actual entries via full scan
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }

    // Incremental totalCount should match the actual DB count.
    // The CHM-based delta path updates totalCount on every committed TX,
    // so after all writers are stopped and their TXs committed/rolled back,
    // the counter should be exact.
    assertEquals(
        "Incremental totalCount should match actual entry count "
            + "(no ANALYZE)",
        actualCount, statsIncremental.totalCount());

    if (histogramIncremental != null) {
      // nonNullCount is derived from scalar counters (totalCount - nullCount),
      // while bucket frequencies are independently clamped to zero when deletes
      // target pre-histogram entries. When drift occurs, freqSum <= nonNullCount.
      long freqSumIncr = 0;
      for (int i = 0; i < histogramIncremental.bucketCount(); i++) {
        freqSumIncr += histogramIncremental.frequencies()[i];
      }
      assertTrue(
          "Incremental: freqSum (" + freqSumIncr
              + ") should be <= nonNullCount ("
              + histogramIncremental.nonNullCount()
              + ") — clamping can reduce sum",
          freqSumIncr <= histogramIncremental.nonNullCount());

      System.out.println("[StressInt] Incremental nonNullCount: "
          + histogramIncremental.nonNullCount()
          + ", buckets: " + histogramIncremental.bucketCount());
    }

    // ── Phase 2: ANALYZE and compare with incremental ─────────
    session.execute("ANALYZE INDEX " + indexName).close();

    var statsAnalyzed = manager.getStatistics();
    var histogramAnalyzed = manager.getHistogram();

    // Scalar counters must be identical — no writes between snapshots
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncremental.totalCount(), statsAnalyzed.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncremental.nullCount(), statsAnalyzed.nullCount());

    if (histogramIncremental != null && histogramAnalyzed != null) {
      // nonNullCount can drift slightly between incremental and ANALYZE:
      // the incremental nonNullCount is derived from scalar counters
      // (totalCount - nullCount) updated atomically per TX commit, while
      // the ANALYZE nonNullCount is the sum of bucket frequencies from a
      // raw B-tree scan (skipping SI visibility filtering, filtering only
      // TombstoneRID entries). Under concurrent writes, the raw scan may
      // include entries from in-progress transactions or miss entries that
      // committed after the scan started. Allow up to 2% relative drift —
      // ~2x headroom over the worst observed drift (~1% on CPX42 with
      // 4 writers over 2 minutes).
      long incrNnc = histogramIncremental.nonNullCount();
      long analyzeNnc = histogramAnalyzed.nonNullCount();
      double nncRelDev = Math.abs((double) incrNnc - analyzeNnc)
          / Math.max(analyzeNnc, 1);
      assertTrue("nonNullCount drift too large: incremental="
          + incrNnc + " ANALYZE=" + analyzeNnc
          + " relDev=" + String.format(Locale.US, "%.4f", nncRelDev),
          nncRelDev <= 0.02);

      // Sum of bucket frequencies should equal nonNullCount
      long freqSum = 0;
      for (int i = 0; i < histogramAnalyzed.bucketCount(); i++) {
        freqSum += histogramAnalyzed.frequencies()[i];
      }
      assertEquals("Sum of bucket frequencies should equal nonNullCount",
          histogramAnalyzed.nonNullCount(), freqSum);

      // ── Frequency deviation: incremental vs ANALYZE ───────────
      // Insert+delete workload with range expansion: initial histogram
      // covers [0, 4999] but stress inserts span [0, 100K). All values
      // beyond the initial max land in the last bucket until background
      // rebalance fires. During rebalance transitions, in-flight deltas
      // sized for the old bucket layout are discarded (version mismatch),
      // causing residual drift concentrated in the last bucket. Allow up
      // to 300% max deviation for the last bucket only (~22% headroom
      // above worst observed ~245% under concurrent insert/delete load
      // with raw histogram scans), 50% for other buckets, and 10% mean
      // deviation.
      assertFrequencyDeviation("StressInt",
          histogramIncremental, histogramAnalyzed, 0.50, 3.00, 0.10);

      // ── Distribution check: histogram estimates vs actual counts ──
      // Random inserts in [0, 100K) → each quarter should hold ~25%.
      // Compare histogram range estimates with actual DB counts.
      int[] rangeBounds = {0, 25_000, 50_000, 75_000, 100_000};
      for (int q = 0; q < 4; q++) {
        long rangeActual;
        try (var result = session.query(
            "SELECT count(*) as cnt FROM " + className
                + " WHERE val >= ? AND val < ?",
            rangeBounds[q], rangeBounds[q + 1])) {
          rangeActual =
              ((Number) result.next().getProperty("cnt")).longValue();
        }
        double actualFraction = (double) rangeActual / actualCount;
        double estFraction = SelectivityEstimator.estimateRange(
            statsAnalyzed, histogramAnalyzed,
            rangeBounds[q], rangeBounds[q + 1], true, false);
        // Tolerance: actual and estimated fractions should be within
        // 0.15 of each other (generous, because inserts/deletes are
        // random and the distribution may drift from uniform).
        assertTrue("Quarter [" + rangeBounds[q] + ", "
            + rangeBounds[q + 1] + "): actual="
            + String.format("%.3f", actualFraction)
            + " est=" + String.format("%.3f", estFraction),
            Math.abs(actualFraction - estFraction) < 0.15);
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 2: String Index — Concurrent Mixed Workload with Skewed
  //  Keys
  // ═══════════════════════════════════════════════════════════════

  /**
   * Multiple threads insert string keys following a Zipf-like distribution
   * for 2 minutes. Verifies that MCV-1 is correctly identified after the
   * stress phase.
   */
  @Test(timeout = 300_000)
  public void stringIndex_concurrentSkewedInserts_mcvCorrect()
      throws Exception {
    session.activateOnCurrentThread();
    final String className = "StressStr";
    final String indexName = className + "nameIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Zipf distribution: key "hot_0" appears ~50% of the time,
    // key "hot_1" ~25%, etc. (power-law with exponent 1)
    final int NUM_DISTINCT_KEYS = 50;
    final String[] keys = new String[NUM_DISTINCT_KEYS];
    for (int i = 0; i < NUM_DISTINCT_KEYS; i++) {
      keys[i] = "key_" + String.format("%04d", i);
    }

    // Precompute Zipf CDF for fast sampling
    double[] cdf = new double[NUM_DISTINCT_KEYS];
    double harmonicSum = 0;
    for (int i = 1; i <= NUM_DISTINCT_KEYS; i++) {
      harmonicSum += 1.0 / i;
    }
    double cdfAcc = 0;
    for (int i = 0; i < NUM_DISTINCT_KEYS; i++) {
      cdfAcc += (1.0 / (i + 1)) / harmonicSum;
      cdf[i] = cdfAcc;
    }

    // Seed initial data
    session.begin();
    for (int i = 0; i < 3000; i++) {
      session.newEntity(className).setProperty("name", keys[zipfSample(cdf, new Random(i))]);
    }
    session.commit();
    session.execute("ANALYZE INDEX " + indexName).close();

    // Stress phase
    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Exception>();
    var totalInserts = new AtomicLong(0);
    var latch = new CountDownLatch(NUM_WRITERS);

    for (int w = 0; w < NUM_WRITERS; w++) {
      final int writerId = w;
      new Thread(() -> {
        var rng = new Random(writerId * 37L + System.nanoTime());
        DatabaseSessionEmbedded localSession = null;
        try {
          localSession = openDatabase();
          while (!stop.get()) {
            try {
              localSession.begin();
              int batchSize = 20 + rng.nextInt(30);
              for (int j = 0; j < batchSize; j++) {
                localSession.newEntity(className)
                    .setProperty("name", keys[zipfSample(cdf, rng)]);
              }
              localSession.commit();
              totalInserts.addAndGet(batchSize);
            } catch (Exception e) {
              try {
                localSession.rollback();
              } catch (Exception ignored) {
              }
            }
          }
        } catch (Exception e) {
          errors.compareAndSet(null, e);
        } finally {
          if (localSession != null && !localSession.isClosed()) {
            localSession.close();
          }
          latch.countDown();
        }
      }, "stress-str-writer-" + writerId).start();
    }

    Thread.sleep(DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue("All writers should finish",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println("[StressStr] Total inserts: " + totalInserts.get());

    // ── Phase 1: verify INCREMENTAL stats (no ANALYZE) ──────────
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var statsIncr = manager.getStatistics();

    // Ground truth
    long actualTotal;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualTotal = ((Number) result.next().getProperty("cnt")).longValue();
    }

    assertEquals(
        "Incremental totalCount should match actual DB count (no ANALYZE)",
        actualTotal, statsIncr.totalCount());

    var histIncr = manager.getHistogram();
    if (histIncr != null) {
      // The MCV value from the initial ANALYZE (before the stress phase)
      // should still be present — MCV is not updated incrementally, but
      // the value persists in the snapshot.
      System.out.println("[StressStr] Incremental MCV: " + histIncr.mcvValue()
          + " (freq=" + histIncr.mcvFrequency() + ")");

      // nonNullCount is derived from scalar counters (totalCount - nullCount),
      // while bucket frequencies are independently clamped to zero when deletes
      // target pre-histogram entries. When drift occurs, freqSum <= nonNullCount.
      long freqSumIncr = 0;
      for (int i = 0; i < histIncr.bucketCount(); i++) {
        freqSumIncr += histIncr.frequencies()[i];
      }
      assertTrue(
          "Incremental: freqSum (" + freqSumIncr
              + ") should be <= nonNullCount ("
              + histIncr.nonNullCount() + ") — clamping can reduce sum",
          freqSumIncr <= histIncr.nonNullCount());
    }

    // ── Phase 2: ANALYZE and compare with incremental ─────────
    session.execute("ANALYZE INDEX " + indexName).close();

    var statsAnalyzed = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should exist after ANALYZE", histogram);

    // Scalar counters must be identical — no writes between snapshots
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), statsAnalyzed.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), statsAnalyzed.nullCount());
    if (histIncr != null) {
      assertEquals("nonNullCount: incremental vs ANALYZE",
          histIncr.nonNullCount(), histogram.nonNullCount());

      // ── Frequency deviation: incremental vs ANALYZE ───────────
      // Insert-only Zipf workload → boundaries unchanged, so
      // per-bucket incremental deltas should be very accurate.
      // Allow 30% max per-bucket (Zipf tail buckets are small,
      // so small absolute errors produce large relative errors)
      // and 5% mean deviation.
      assertFrequencyDeviation("StressStr",
          histIncr, histogram, 0.30, 0.30, 0.05);
    }

    assertNotNull("MCV should be tracked", histogram.mcvValue());

    // The most common key should be "key_0000" (rank 1 in Zipf)
    assertEquals("MCV should be the most frequent Zipf key",
        "key_0000", histogram.mcvValue());

    // Ground truth: count actual frequency of the MCV key
    long mcvActualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className
            + " WHERE name = ?",
        "key_0000")) {
      mcvActualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }

    // MCV frequency should match the actual count (exact at ANALYZE time)
    assertEquals("MCV frequency should match actual count after ANALYZE",
        mcvActualCount, histogram.mcvFrequency());

    // Verify selectivity for MCV is reasonable
    double selMcv = SelectivityEstimator.estimateEquality(
        manager.getStatistics(), histogram, "key_0000");
    double expectedSelMcv = (double) mcvActualCount
        / manager.getStatistics().totalCount();
    assertTrue(
        "MCV selectivity should be close to actual fraction ("
            + expectedSelMcv + "), got " + selMcv,
        Math.abs(selMcv - expectedSelMcv) < 0.05);

    // ── Distribution check: Zipf shape preserved in histogram ───
    // The top-ranked key (key_0000) should have much higher estimated
    // selectivity than a low-ranked key (key_0049, rank 50).
    double selTop = SelectivityEstimator.estimateEquality(
        manager.getStatistics(), histogram, "key_0000");
    double selBottom = SelectivityEstimator.estimateEquality(
        manager.getStatistics(), histogram, "key_0049");
    assertTrue("Zipf: top key selectivity (" + selTop
        + ") should be > 3x bottom key (" + selBottom + ")",
        selTop > selBottom * 3);

    // Verify a few actual counts match histogram estimates within 5%
    for (String probeKey : new String[] {"key_0000", "key_0004",
        "key_0019"}) {
      long probeActual;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM " + className
              + " WHERE name = ?",
          probeKey)) {
        probeActual =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      double actualFrac =
          (double) probeActual / manager.getStatistics().totalCount();
      double estFrac = SelectivityEstimator.estimateEquality(
          manager.getStatistics(), histogram, probeKey);
      assertTrue("Key " + probeKey + ": actual="
          + String.format("%.4f", actualFrac)
          + " est=" + String.format("%.4f", estFrac),
          Math.abs(actualFrac - estFrac) < 0.05);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 3: NOTUNIQUE Index — Concurrent Inserts with Low NDV
  // ═══════════════════════════════════════════════════════════════

  /**
   * Multiple threads concurrently insert records with a NOTUNIQUE index on
   * a field with limited distinct values (low NDV, high totalCount) for
   * 2 minutes. Verifies that the histogram correctly captures the
   * distribution and bucket frequencies are accurate.
   */
  @Test(timeout = 300_000)
  public void notUniqueIndex_concurrentLowNdv_histogramAccurate()
      throws Exception {
    session.activateOnCurrentThread();
    final int NUM_CATEGORIES = 50;
    final String className = "StressLowNdv";
    final String indexName = className + "catIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("cat", PropertyType.STRING);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "cat");

    // Category pool
    var categories = new String[NUM_CATEGORIES];
    for (int i = 0; i < NUM_CATEGORIES; i++) {
      categories[i] = "category_" + String.format("%03d", i);
    }

    // Seed initial data
    session.begin();
    for (int i = 0; i < 3000; i++) {
      session.newEntity(className).setProperty("cat",
          categories[i % NUM_CATEGORIES]);
    }
    session.commit();
    session.execute("ANALYZE INDEX " + indexName).close();

    // Stress phase
    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Exception>();
    var totalInserts = new AtomicLong(0);
    var latch = new CountDownLatch(NUM_WRITERS);

    for (int w = 0; w < NUM_WRITERS; w++) {
      final int writerId = w;
      new Thread(() -> {
        var localRng = new Random(writerId * 41L + System.nanoTime());
        DatabaseSessionEmbedded localSession = null;
        try {
          localSession = openDatabase();
          while (!stop.get()) {
            try {
              localSession.begin();
              int batchSize = 10 + localRng.nextInt(30);
              for (int j = 0; j < batchSize; j++) {
                localSession.newEntity(className)
                    .setProperty("cat",
                        categories[localRng.nextInt(NUM_CATEGORIES)]);
              }
              localSession.commit();
              totalInserts.addAndGet(batchSize);
            } catch (Exception e) {
              try {
                localSession.rollback();
              } catch (Exception ignored) {
              }
            }
          }
        } catch (Exception e) {
          errors.compareAndSet(null, e);
        } finally {
          if (localSession != null && !localSession.isClosed()) {
            localSession.close();
          }
          latch.countDown();
        }
      }, "stress-lowndv-writer-" + writerId).start();
    }

    Thread.sleep(DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue("All writers should finish",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println("[StressLowNdv] Total inserts: "
        + totalInserts.get());

    // ── Phase 1: verify INCREMENTAL stats (no ANALYZE) ──────────
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var statsIncr = manager.getStatistics();

    // Ground truth
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals(
        "Incremental totalCount should match actual (no ANALYZE)",
        actualCount, statsIncr.totalCount());

    var histIncr = manager.getHistogram();
    if (histIncr != null) {
      long freqSumIncr = 0;
      for (int i = 0; i < histIncr.bucketCount(); i++) {
        freqSumIncr += histIncr.frequencies()[i];
      }
      assertTrue(
          "Incremental: freqSum (" + freqSumIncr
              + ") should be <= nonNullCount ("
              + histIncr.nonNullCount() + ") — clamping can reduce sum",
          freqSumIncr <= histIncr.nonNullCount());
      System.out.println("[StressLowNdv] Incremental nonNullCount: "
          + histIncr.nonNullCount());
    }

    // ── Phase 2: ANALYZE and compare with incremental ─────────
    session.execute("ANALYZE INDEX " + indexName).close();

    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should exist after ANALYZE", histogram);

    // Scalar counters must be identical — no writes between snapshots
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), stats.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), stats.nullCount());
    if (histIncr != null) {
      assertEquals("nonNullCount: incremental vs ANALYZE",
          histIncr.nonNullCount(), histogram.nonNullCount());

      // ── Frequency deviation: incremental vs ANALYZE ───────────
      // Insert-only uniform workload with low NDV → each bucket
      // gets many entries, so relative deviation should be small.
      // Allow 10% max per-bucket, 3% mean.
      assertFrequencyDeviation("StressLowNdv",
          histIncr, histogram, 0.10, 0.10, 0.03);
    }

    // Bucket count should be in a reasonable range. Equi-depth construction
    // may merge buckets with identical/adjacent string boundaries (e.g.,
    // "category_005" and "category_006" share a bucket), so the count may
    // be less than NUM_CATEGORIES.
    assertTrue("Bucket count (" + histogram.bucketCount()
        + ") should be > 0 and <= " + NUM_CATEGORIES,
        histogram.bucketCount() > 0
            && histogram.bucketCount() <= NUM_CATEGORIES);

    // Sum of bucket frequencies must equal nonNullCount (structural invariant)
    long freqSum = 0;
    for (int i = 0; i < histogram.bucketCount(); i++) {
      assertTrue("Bucket " + i + " frequency should be >= 0",
          histogram.frequencies()[i] >= 0);
      freqSum += histogram.frequencies()[i];
    }
    assertEquals("Sum of frequencies should equal nonNullCount after ANALYZE",
        histogram.nonNullCount(), freqSum);

    // All frequencies should be positive (uniform categories, all present)
    for (int i = 0; i < histogram.bucketCount(); i++) {
      assertTrue("Every bucket should have entries after uniform inserts,"
          + " bucket " + i + " has " + histogram.frequencies()[i],
          histogram.frequencies()[i] > 0);
    }

    // ── Distribution check: spot-check category counts vs estimates ─
    // Uniform random selection → each category should hold ~1/50 of
    // total. Check a few specific categories.
    long total = stats.totalCount();
    double expectedFrac = 1.0 / NUM_CATEGORIES;
    for (int probe : new int[] {0, 10, 25, 49}) {
      String cat = categories[probe];
      long catActual;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM " + className
              + " WHERE cat = ?",
          cat)) {
        catActual =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      double actualFrac = (double) catActual / total;
      double estFrac = SelectivityEstimator.estimateEquality(
          stats, histogram, cat);
      // Uniform distribution: actual and estimated fractions should
      // both be near 1/50 = 0.02. Allow 0.015 absolute tolerance.
      assertTrue("Category " + cat + ": actual="
          + String.format("%.4f", actualFrac) + " expected~"
          + String.format("%.4f", expectedFrac),
          Math.abs(actualFrac - expectedFrac) < 0.015);
      assertTrue("Category " + cat + ": est="
          + String.format("%.4f", estFrac) + " actual="
          + String.format("%.4f", actualFrac),
          Math.abs(estFrac - actualFrac) < 0.015);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 4: Concurrent Inserts + ANALYZE INDEX — No Corruption
  // ═══════════════════════════════════════════════════════════════

  /**
   * One thread continuously inserts data while another thread periodically
   * runs ANALYZE INDEX. Verifies no corruption or deadlock occurs.
   */
  @Test(timeout = 300_000)
  public void concurrentInsertsWithAnalyze_noCorruption() throws Exception {
    session.activateOnCurrentThread();
    final String className = "StressAnalyze";
    final String indexName = className + "valIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Seed initial data
    session.begin();
    for (int i = 0; i < 2000; i++) {
      session.newEntity(className).setProperty("val", i);
    }
    session.commit();

    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Exception>();
    var insertCount = new AtomicLong(0);
    var analyzeCount = new AtomicInteger(0);
    var latch = new CountDownLatch(2);

    // Writer thread
    new Thread(() -> {
      var rng = new Random(System.nanoTime());
      DatabaseSessionEmbedded localSession = null;
      try {
        localSession = openDatabase();
        while (!stop.get()) {
          try {
            localSession.begin();
            int batchSize = 10 + rng.nextInt(40);
            for (int j = 0; j < batchSize; j++) {
              localSession.newEntity(className)
                  .setProperty("val", rng.nextInt(1_000_000));
            }
            localSession.commit();
            insertCount.addAndGet(batchSize);
          } catch (Exception e) {
            try {
              localSession.rollback();
            } catch (Exception ignored) {
            }
          }
        }
      } catch (Exception e) {
        errors.compareAndSet(null, e);
      } finally {
        if (localSession != null && !localSession.isClosed()) {
          localSession.close();
        }
        latch.countDown();
      }
    }, "stress-inserter").start();

    // Analyzer thread — periodically runs ANALYZE INDEX
    new Thread(() -> {
      DatabaseSessionEmbedded localSession = null;
      try {
        localSession = openDatabase();
        while (!stop.get()) {
          try {
            Thread.sleep(5000); // ANALYZE every 5 seconds
            localSession.execute("ANALYZE INDEX " + indexName).close();
            analyzeCount.incrementAndGet();
          } catch (InterruptedException e) {
            break;
          } catch (Exception e) {
            // Log but continue — transient errors under load are expected
            System.err.println("[ANALYZE] Error: " + e.getMessage());
          }
        }
      } catch (Exception e) {
        errors.compareAndSet(null, e);
      } finally {
        if (localSession != null && !localSession.isClosed()) {
          localSession.close();
        }
        latch.countDown();
      }
    }, "stress-analyzer").start();

    Thread.sleep(DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue("Both threads should finish",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println("[StressAnalyze] Total inserts: " + insertCount.get()
        + ", ANALYZE runs: " + analyzeCount.get());

    // ── Phase 1: verify incremental stats (no final ANALYZE) ────
    // Note: the ANALYZE thread ran periodically during the stress phase,
    // so the histogram has been rebuilt multiple times. But the last few
    // transactions after the last ANALYZE are purely incremental.
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var statsIncr = manager.getStatistics();
    assertTrue("totalCount should be > 2000 (incremental)",
        statsIncr.totalCount() > 2000);

    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals(
        "Incremental totalCount should match actual (no final ANALYZE)",
        actualCount, statsIncr.totalCount());

    var histIncr = manager.getHistogram();
    if (histIncr != null) {
      long freqSumIncr = 0;
      for (int i = 0; i < histIncr.bucketCount(); i++) {
        freqSumIncr += histIncr.frequencies()[i];
      }
      assertTrue(
          "Incremental: freqSum (" + freqSumIncr
              + ") should be <= nonNullCount ("
              + histIncr.nonNullCount() + ") — clamping can reduce sum",
          freqSumIncr <= histIncr.nonNullCount());
    }

    // ── Phase 2: ANALYZE and compare with incremental ─────────
    session.execute("ANALYZE INDEX " + indexName).close();

    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should exist after final ANALYZE", histogram);

    // Scalar counters should be preserved across ANALYZE (no writers active)
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), stats.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), stats.nullCount());

    // After ANALYZE, histogram.nonNullCount must equal sum(frequencies)
    // (structural invariant — always holds by construction).
    long freqSum = 0;
    for (int i = 0; i < histogram.bucketCount(); i++) {
      freqSum += histogram.frequencies()[i];
    }
    assertEquals("Sum of frequencies should equal nonNullCount",
        histogram.nonNullCount(), freqSum);

    // ANALYZE scans the actual B-tree, so nonNullCount should match the
    // ground-truth entry count (all writers have stopped).
    assertEquals("ANALYZE nonNullCount should match actual entry count",
        actualCount - statsIncr.nullCount(), histogram.nonNullCount());

    // Per-bucket frequency deviation between incremental and ANALYZE is
    // not checked here. This test runs concurrent inserts with periodic
    // ANALYZE, so the incremental frequencies can drift significantly
    // (especially in boundary buckets) between the last periodic ANALYZE
    // and the final ANALYZE. The structural invariants (nonNullCount ==
    // sum(frequencies), totalCount == ground truth) are the important
    // checks and are already verified above.
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 5: Distribution Verification — Histogram Captures Shape
  // ═══════════════════════════════════════════════════════════════

  /**
   * Inserts data following a known bimodal distribution for 2 minutes across
   * multiple threads. Verifies that histogram selectivity estimates are
   * significantly more accurate than uniform estimates for range queries.
   */
  @Test(timeout = 300_000)
  public void bimodalDistribution_histogramMoreAccurateThanUniform()
      throws Exception {
    session.activateOnCurrentThread();
    final String className = "StressBimodal";
    final String indexName = className + "valIdx";

    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Bimodal: ~50% of values in [0, 100), ~50% in [900, 1000)
    // Very few in [100, 900)

    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Exception>();
    var totalInserts = new AtomicLong(0);
    var latch = new CountDownLatch(NUM_WRITERS);

    for (int w = 0; w < NUM_WRITERS; w++) {
      final int writerId = w;
      new Thread(() -> {
        var localRng = new Random(writerId * 53L + System.nanoTime());
        DatabaseSessionEmbedded localSession = null;
        try {
          localSession = openDatabase();
          while (!stop.get()) {
            try {
              localSession.begin();
              int batchSize = 20 + localRng.nextInt(30);
              for (int j = 0; j < batchSize; j++) {
                int val;
                double r = localRng.nextDouble();
                if (r < 0.48) {
                  val = localRng.nextInt(100); // mode 1: [0, 100)
                } else if (r < 0.96) {
                  val = 900 + localRng.nextInt(100); // mode 2: [900, 1000)
                } else {
                  val = 100 + localRng.nextInt(800); // sparse middle
                }
                localSession.newEntity(className).setProperty("val", val);
              }
              localSession.commit();
              totalInserts.addAndGet(batchSize);
            } catch (Exception e) {
              try {
                localSession.rollback();
              } catch (Exception ignored) {
              }
            }
          }
        } catch (Exception e) {
          errors.compareAndSet(null, e);
        } finally {
          if (localSession != null && !localSession.isClosed()) {
            localSession.close();
          }
          latch.countDown();
        }
      }, "stress-bimodal-" + writerId).start();
    }

    Thread.sleep(DURATION_SECONDS * 1000L);
    stop.set(true);
    assertTrue("All writers should finish",
        latch.await(30, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw errors.get();
    }

    System.out.println("[StressBimodal] Total inserts: "
        + totalInserts.get());

    // ── Phase 1: verify incremental totalCount (no ANALYZE) ─────
    // No initial ANALYZE was run, so there is no histogram yet — only
    // the scalar counters (totalCount, nullCount) are maintained via
    // incremental deltas.
    session.activateOnCurrentThread();
    var manager = getHistogramManager(indexName);
    var statsIncr = manager.getStatistics();

    long actualCountBefore;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className)) {
      actualCountBefore =
          ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals(
        "Incremental totalCount should match actual (no ANALYZE, no "
            + "histogram)",
        actualCountBefore, statsIncr.totalCount());
    System.out.println("[StressBimodal] Incremental totalCount: "
        + statsIncr.totalCount());

    // ── Phase 2: ANALYZE and compare with incremental ─────────
    session.execute("ANALYZE INDEX " + indexName).close();

    var stats = manager.getStatistics();
    var histogram = manager.getHistogram();
    assertNotNull("Histogram should exist after ANALYZE", histogram);

    // Scalar counters must be identical — no writes between snapshots
    assertEquals("totalCount: incremental vs ANALYZE",
        statsIncr.totalCount(), stats.totalCount());
    assertEquals("nullCount: incremental vs ANALYZE",
        statsIncr.nullCount(), stats.nullCount());

    long total = stats.totalCount();
    assertTrue("Should have substantial data", total > 5000);

    // Ground truth: count entries in the sparse middle range [200, 800)
    long middleCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM " + className
            + " WHERE val >= 200 AND val < 800")) {
      middleCount = ((Number) result.next().getProperty("cnt")).longValue();
    }
    double actualMiddleFraction = (double) middleCount / total;

    // Histogram estimate for the middle range
    double histogramEst = SelectivityEstimator.estimateRange(
        stats, histogram, 200, 800, true, false);

    // Uniform estimate would be (800-200)/1000 ≈ 0.6 (very wrong!)
    // Actual fraction should be ~4% (from the sparse middle)
    // Histogram should be much closer to actual than 0.6

    double histogramError = Math.abs(histogramEst - actualMiddleFraction);
    double uniformError = Math.abs(0.6 - actualMiddleFraction);

    System.out.println("[StressBimodal] Middle range actual fraction: "
        + actualMiddleFraction);
    System.out.println("[StressBimodal] Histogram estimate: " + histogramEst);
    System.out.println("[StressBimodal] Uniform estimate: 0.6");
    System.out.println("[StressBimodal] Histogram error: " + histogramError);
    System.out.println("[StressBimodal] Uniform error: " + uniformError);

    assertTrue("Histogram error (" + histogramError
        + ") should be significantly less than uniform error ("
        + uniformError + ")",
        histogramError < uniformError * 0.5);

    // ── Distribution check: all three regions ───────────────────
    // Mode 1: [0, 100) should hold ~48% of entries
    // Mode 2: [900, 1000) should hold ~48% of entries
    // Middle: [100, 900) should hold ~4% of entries
    int[][] regions = {{0, 100}, {100, 900}, {900, 1000}};
    double[] expectedFracs = {0.48, 0.04, 0.48};
    for (int r = 0; r < regions.length; r++) {
      long regionCount;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM " + className
              + " WHERE val >= ? AND val < ?",
          regions[r][0], regions[r][1])) {
        regionCount =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      double actualFrac = (double) regionCount / total;
      double estFrac = SelectivityEstimator.estimateRange(
          stats, histogram,
          regions[r][0], regions[r][1], true, false);

      // Actual fraction should be close to the expected probability
      assertTrue("Region [" + regions[r][0] + ", " + regions[r][1]
          + "): actual=" + String.format("%.3f", actualFrac)
          + " expected~" + String.format("%.3f", expectedFracs[r]),
          Math.abs(actualFrac - expectedFracs[r]) < 0.10);

      // Histogram estimate should be close to the actual fraction
      assertTrue("Region [" + regions[r][0] + ", " + regions[r][1]
          + "): est=" + String.format("%.3f", estFrac)
          + " actual=" + String.format("%.3f", actualFrac),
          Math.abs(estFrac - actualFrac) < 0.10);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════

  /**
   * Compares per-bucket frequency deviation between an incremental
   * histogram and a freshly ANALYZE-rebuilt histogram. When both have
   * the same bucket count (same boundaries), computes max and mean
   * relative deviation across all buckets. Fails if either exceeds
   * the given thresholds.
   *
   * <p>When bucket counts differ (rebalance changed boundaries),
   * per-bucket comparison is not meaningful — the method logs a
   * warning and skips.
   *
   * @param label             test name for diagnostic output
   * @param incremental       histogram from incremental maintenance
   * @param analyzed          histogram from fresh ANALYZE rebuild
   * @param maxRelDeviation   max allowed relative deviation for any
   *                          single bucket (e.g., 0.20 = 20%)
   * @param meanRelDeviation  max allowed mean relative deviation
   *                          across all buckets (e.g., 0.05 = 5%)
   */
  private static void assertFrequencyDeviation(
      String label,
      EquiDepthHistogram incremental,
      EquiDepthHistogram analyzed,
      double maxRelDeviation,
      double lastBucketMaxRelDeviation,
      double meanRelDeviation) {
    if (incremental.bucketCount() != analyzed.bucketCount()) {
      System.out.println("[" + label + "] Bucket count changed ("
          + incremental.bucketCount() + " → "
          + analyzed.bucketCount()
          + "); skipping per-bucket deviation check");
      return;
    }

    int n = analyzed.bucketCount();
    double sumRelDev = 0;
    double worstRelDev = 0;
    int worstBucket = -1;
    for (int i = 0; i < n; i++) {
      long incr = incremental.frequencies()[i];
      long exact = analyzed.frequencies()[i];
      // Relative deviation: |incr - exact| / max(exact, 1)
      double relDev = Math.abs(incr - exact)
          / (double) Math.max(exact, 1);
      sumRelDev += relDev;

      double threshold = (i == n - 1)
          ? lastBucketMaxRelDeviation : maxRelDeviation;
      assertTrue("[" + label + "] Per-bucket relative deviation "
          + String.format("%.4f", relDev) + " (bucket "
          + i + ") exceeds threshold " + threshold,
          relDev <= threshold);

      if (relDev > worstRelDev) {
        worstRelDev = relDev;
        worstBucket = i;
      }
    }
    double meanDev = sumRelDev / n;

    System.out.println("[" + label + "] Frequency deviation: "
        + "mean=" + String.format("%.4f", meanDev)
        + " max=" + String.format("%.4f", worstRelDev)
        + " (bucket " + worstBucket + ")");

    assertTrue("[" + label + "] Mean relative deviation "
        + String.format("%.4f", meanDev)
        + " exceeds threshold " + meanRelDeviation,
        meanDev <= meanRelDeviation);
  }

  /**
   * Sample from a Zipf distribution using the precomputed CDF.
   */
  private static int zipfSample(double[] cdf, Random rng) {
    double u = rng.nextDouble();
    for (int i = 0; i < cdf.length; i++) {
      if (u <= cdf[i]) {
        return i;
      }
    }
    return cdf.length - 1;
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
      throw new RuntimeException(
          "Failed to get histogram manager for " + indexName, e);
    }
  }
}
