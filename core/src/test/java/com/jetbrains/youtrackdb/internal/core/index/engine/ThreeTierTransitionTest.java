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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Test;

/**
 * Tests for the three-tier estimation transition lifecycle (Section 10.5).
 *
 * <p>The SelectivityEstimator operates in three tiers:
 * <ol>
 *   <li><b>Empty</b> — totalCount == 0: all estimates return 0</li>
 *   <li><b>Uniform</b> — entries exist but no histogram (below
 *       HISTOGRAM_MIN_SIZE or not yet built): uses summary counters with
 *       uniform-distribution formulas</li>
 *   <li><b>Histogram</b> — equi-depth histogram available: uses
 *       bucket-level interpolation</li>
 * </ol>
 *
 * <p>Covers:
 * <ul>
 *   <li>Empty → Uniform transition on first put</li>
 *   <li>Uniform → Histogram transition on crossing HISTOGRAM_MIN_SIZE</li>
 *   <li>Histogram remains usable after mass deletion below threshold</li>
 *   <li>Uniform formulas produce reasonable estimates</li>
 *   <li>Histogram more accurate than uniform for skewed data</li>
 * </ul>
 */
public class ThreeTierTransitionTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 1 (Empty): all estimate methods return 0
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void emptyTier_equalityReturnsZero() {
    // Given an empty index (totalCount=0, no histogram)
    var stats = new IndexStatistics(0, 0, 0);

    // When estimating equality selectivity
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);

    // Then the result is 0 — no entries exist
    assertEquals(0.0, sel, 0.0);
  }

  @Test
  public void emptyTier_greaterThanReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, null, 42), 0.0);
  }

  @Test
  public void emptyTier_lessThanReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    assertEquals(0.0,
        SelectivityEstimator.estimateLessThan(stats, null, 42), 0.0);
  }

  @Test
  public void emptyTier_rangeReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    assertEquals(0.0,
        SelectivityEstimator.estimateRange(
            stats, null, 10, 90, true, true),
        0.0);
  }

  @Test
  public void emptyTier_isNullReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    assertEquals(0.0,
        SelectivityEstimator.estimateIsNull(stats, null), 0.0);
  }

  @Test
  public void emptyTier_isNotNullReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    assertEquals(0.0,
        SelectivityEstimator.estimateIsNotNull(stats, null), 0.0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 1 → Tier 2: Empty to Uniform on first put
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void emptyToUniform_firstPutTransitionsToUniformTier() {
    // Given an empty manager with an empty snapshot installed
    var fixture = createManagerFixture();
    installEmptySnapshot(fixture);

    // When a single entry is inserted via onPut + applyDelta
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 1, true, true);
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull("Delta should be created for this engine", delta);
    fixture.manager.applyDelta(delta);

    // Then the snapshot transitions to Uniform tier:
    // - totalCount > 0, distinctCount > 0
    // - histogram is still null (below HISTOGRAM_MIN_SIZE)
    var snapshot = fixture.cache.get(fixture.engineId);
    assertNotNull(snapshot);
    assertEquals(1, snapshot.stats().totalCount());
    assertEquals(1, snapshot.stats().distinctCount());
    assertNull("Histogram should not exist below min size",
        snapshot.histogram());
  }

  @Test
  public void emptyToUniform_nullKeyIncrementsNullCount() {
    // Given an empty manager
    var fixture = createManagerFixture();
    installEmptySnapshot(fixture);

    // When a null-key entry is inserted
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, true, true);
    fixture.manager.applyDelta(holder.getDeltas().get(fixture.engineId));

    // Then nullCount is incremented
    var snapshot = fixture.cache.get(fixture.engineId);
    assertEquals(1, snapshot.stats().nullCount());
    assertEquals(1, snapshot.stats().totalCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 2 (Uniform): formulas produce reasonable estimates
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void uniformTier_equalityReturns1OverDistinctCount() {
    // Given an index with 100 entries, 50 distinct values, no histogram
    var stats = new IndexStatistics(100, 50, 0);

    // When estimating equality selectivity
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);

    // Then the result is 1/distinctCount = 1/50 = 0.02
    assertEquals(1.0 / 50.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_greaterThanReturnsOneThird() {
    // Given an index in uniform tier
    var stats = new IndexStatistics(100, 50, 0);

    // When estimating GT selectivity
    double sel = SelectivityEstimator.estimateGreaterThan(stats, null, 42);

    // Then the PostgreSQL default of 1/3 is used
    assertEquals(1.0 / 3.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_lessThanReturnsOneThird() {
    var stats = new IndexStatistics(100, 50, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, null, 42);
    assertEquals(1.0 / 3.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_greaterOrEqualReturnsOneThird() {
    var stats = new IndexStatistics(100, 50, 0);
    double sel =
        SelectivityEstimator.estimateGreaterOrEqual(stats, null, 42);
    assertEquals(1.0 / 3.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_lessOrEqualReturnsOneThird() {
    var stats = new IndexStatistics(100, 50, 0);
    double sel =
        SelectivityEstimator.estimateLessOrEqual(stats, null, 42);
    assertEquals(1.0 / 3.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_rangeReturnsOneThird() {
    var stats = new IndexStatistics(100, 50, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, null, 10, 90, true, true);
    assertEquals(1.0 / 3.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_isNullReturnsNullFraction() {
    // Given an index with 100 entries, 10 of which are null
    var stats = new IndexStatistics(100, 90, 10);

    // When estimating IS NULL
    double sel = SelectivityEstimator.estimateIsNull(stats, null);

    // Then the result is nullCount/totalCount = 10/100 = 0.1
    assertEquals(0.1, sel, 1e-9);
  }

  @Test
  public void uniformTier_isNotNullReturnsNonNullFraction() {
    // Given an index with 100 entries, 10 nulls
    var stats = new IndexStatistics(100, 90, 10);

    // When estimating IS NOT NULL
    double sel = SelectivityEstimator.estimateIsNotNull(stats, null);

    // Then the result is (totalCount - nullCount)/totalCount = 90/100
    assertEquals(0.9, sel, 1e-9);
  }

  @Test
  public void uniformTier_equalityWithSingleDistinctValueReturnsOne() {
    // Given an index where all entries have the same key (distinctCount=1)
    var stats = new IndexStatistics(100, 1, 0);

    // When estimating equality
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);

    // Then the result is 1/1 = 1.0 (clamped)
    assertEquals(1.0, sel, 1e-9);
  }

  @Test
  public void uniformTier_equalityWithZeroDistinctCountReturnsZero() {
    // Given an index with entries but distinctCount=0 (all nulls)
    var stats = new IndexStatistics(100, 0, 100);

    // When estimating equality for a non-null key
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);

    // Then the result is 0 (no distinct non-null keys)
    assertEquals(0.0, sel, 0.0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 2 → Tier 3: Uniform to Histogram on crossing HISTOGRAM_MIN_SIZE
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void uniformToHistogram_buildTriggeredAtMinSize()
      throws Exception {
    // Given a manager in uniform tier with totalCount >= HISTOGRAM_MIN_SIZE
    // but no histogram built yet (totalCountAtLastBuild == 0)
    var fixture = createManagerFixture();
    int count = 2000;
    var stats = new IndexStatistics(count, count, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    fixture.manager.setKeyStreamSupplier(
        atomicOp -> IntStream.range(0, count)
            .mapToObj(i -> (Object) i).sorted());
    setFileId(fixture.manager, 42);

    // When setBackgroundExecutor triggers proactive build
    var executor = Executors.newSingleThreadExecutor();
    try {
      fixture.manager.setBackgroundExecutor(executor);
      executor.shutdown();
      assertTrue("Initial build should complete",
          executor.awaitTermination(10, TimeUnit.SECONDS));

      // awaitTermination provides happens-before with all task actions,
      // and CHM.compute() inside the task is volatile — the cache read
      // below MUST see the updated value. If histogram is null, the
      // rebalance task was never submitted (check scheduleRebalance
      // preconditions: CAS guard, cooldown, keyStreamSupplier, fileId).
      var updated = fixture.cache.get(fixture.engineId);

      // Then the snapshot transitions to Histogram tier
      assertNotNull("Snapshot should exist after rebalance", updated);
      assertNotNull("Histogram should be built — if null, the rebalance "
          + "task was likely never submitted (CAS guard, cooldown, or "
          + "missing keyStreamSupplier/fileId)", updated.histogram());
      assertTrue("Histogram should have buckets",
          updated.histogram().bucketCount() > 0);
      assertEquals("Non-null count should match",
          count, updated.histogram().nonNullCount());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void uniformToHistogram_belowMinSizeDoesNotBuild()
      throws Exception {
    // Given a manager with totalCount below HISTOGRAM_MIN_SIZE (default
    // 1000)
    var fixture = createManagerFixture();
    var stats = new IndexStatistics(500, 500, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    var executor = Executors.newSingleThreadExecutor();
    try {
      // When setBackgroundExecutor is called
      fixture.manager.setBackgroundExecutor(executor);
      executor.shutdown();
      assertTrue("No task should be submitted",
          executor.awaitTermination(1, TimeUnit.SECONDS));

      // Then histogram remains null
      assertNull(fixture.cache.get(fixture.engineId).histogram());
    } finally {
      executor.shutdownNow();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 3: Histogram remains usable after mass deletion below threshold
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramTier_remainsUsableAfterMassDeletion() {
    // Given an index with a histogram built at 2000 entries
    var histogram = createUniformHistogram(2000);
    var stats = new IndexStatistics(2000, 2000, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 2000, 0, false, null, false);

    var fixture = createManagerFixture();
    fixture.cache.put(fixture.engineId, snapshot);

    // When many entries are deleted (simulated via applyDelta with
    // negative totalCountDelta), reducing totalCount below MIN_SIZE
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    for (int i = 0; i < 1500; i++) {
      fixture.manager.onRemove(op, i, true);
    }
    var delta = holder.getDeltas().get(fixture.engineId);
    fixture.manager.applyDelta(delta);

    // Then the histogram is still present (not discarded)
    var updated = fixture.cache.get(fixture.engineId);
    assertNotNull("Histogram should still be present",
        updated.histogram());
    assertEquals("Total count should reflect deletions",
        500, updated.stats().totalCount());

    // And the histogram is still usable for estimation
    double sel = SelectivityEstimator.estimateEquality(
        updated.stats(), updated.histogram(), 500);
    assertTrue("Estimate should be a valid fraction",
        sel >= 0.0 && sel <= 1.0);
  }

  @Test
  public void histogramTier_estimatesStillWorkWithReducedCount() {
    // Given a histogram with stale bucket frequencies after mass deletion
    // (bucket frequencies sum to 2000 but totalCount is now 500)
    var histogram = createUniformHistogram(2000);
    var stats = new IndexStatistics(500, 500, 0);

    // When estimating range selectivity
    double sel = SelectivityEstimator.estimateGreaterThan(
        stats, histogram, 500);

    // Then a non-zero, clamped-to-[0,1] estimate is returned
    assertTrue("Should return valid selectivity",
        sel >= 0.0 && sel <= 1.0);
    assertTrue("Should be non-zero for in-range value", sel > 0.0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tier 3: Histogram is more accurate than Uniform for skewed data
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramMoreAccurateThanUniform_equalityOnHotKey() {
    // Given a heavily skewed index where key=0 has 901 entries out of
    // 1000, and keys 1-99 have 1 entry each.
    int total = 1000;
    int hotKeyFreq = 901;
    // Build a 2-bucket histogram: bucket 0 = [0,0] with freq=901,
    // bucket 1 = [1,99] with freq=99
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 0, 99},
        new long[] {hotKeyFreq, total - hotKeyFreq},
        new long[] {1, 99},
        total,
        0, // mcvValue
        hotKeyFreq // mcvFrequency
    );
    var stats = new IndexStatistics(total, 100, 0);

    // Uniform estimate for key=0: 1/distinctCount = 1/100 = 0.01
    double uniformSel =
        SelectivityEstimator.estimateEquality(stats, null, 0);
    assertEquals("Uniform should be 1/NDV",
        1.0 / 100.0, uniformSel, 1e-9);

    // Histogram estimate for key=0: MCV short-circuit → 901/1000 = 0.901
    double histSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 0);

    // The true selectivity is 901/1000 = 0.901. The histogram estimate
    // should be much closer to the truth than uniform.
    double trueSelectivity = (double) hotKeyFreq / total;
    double uniformError = Math.abs(uniformSel - trueSelectivity);
    double histError = Math.abs(histSel - trueSelectivity);
    assertTrue(
        "Histogram error (" + histError + ") should be much less than "
            + "uniform error (" + uniformError + ")",
        histError < uniformError);
    assertEquals("Histogram should return exact MCV fraction",
        trueSelectivity, histSel, 1e-9);
  }

  @Test
  public void histogramMoreAccurateThanUniform_equalityOnColdKey() {
    // Given the same skewed index, estimating a cold key (key=50)
    int total = 1000;
    int hotKeyFreq = 901;
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 0, 99},
        new long[] {hotKeyFreq, total - hotKeyFreq},
        new long[] {1, 99},
        total,
        0,
        hotKeyFreq);
    var stats = new IndexStatistics(total, 100, 0);

    // Uniform estimate: 1/100 = 0.01 (same for all keys)
    double uniformSel =
        SelectivityEstimator.estimateEquality(stats, null, 50);

    // Histogram estimate: bucket 1 (keys 1-99, freq=99, ndv=99)
    // → (1/99) * (99/1000) ≈ 0.001
    double histSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 50);

    // True selectivity for key=50 is 1/1000 = 0.001
    double trueSelectivity = 1.0 / total;
    double uniformError = Math.abs(uniformSel - trueSelectivity);
    double histError = Math.abs(histSel - trueSelectivity);
    assertTrue(
        "Histogram error (" + histError + ") should be less than "
            + "uniform error (" + uniformError + ")",
        histError < uniformError);
  }

  @Test
  public void histogramMoreAccurateThanUniform_rangeOnSkewedData() {
    // Given a skewed 4-bucket histogram where 80% of entries are in
    // bucket 0
    int total = 2000;
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 100, 500, 900, 1000},
        new long[] {1600, 200, 150, 50}, // heavily skewed
        new long[] {100, 400, 400, 100},
        total,
        null,
        0);
    var stats = new IndexStatistics(total, 1000, 0);

    // Uniform range estimate for [0, 100]: 1/3 ≈ 0.333
    double uniformSel =
        SelectivityEstimator.estimateRange(
            stats, null, 0, 100, true, true);

    // Histogram range estimate for [0, 100]: should reflect that bucket 0
    // (covering [0,100]) contains 1600/2000 = 80% of entries
    double histSel =
        SelectivityEstimator.estimateRange(
            stats, histogram, 0, 100, true, true);

    // The histogram estimate should be closer to the true value (~0.8)
    // than the uniform estimate (0.333)
    assertTrue("Histogram range estimate (" + histSel
        + ") should exceed uniform (" + uniformSel
        + ") for hot range",
        histSel > uniformSel);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Cross-tier: selectivity changes as tier transitions happen
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void crossTier_selectivityEvolvesAsEntriesAccumulate() {
    // Tier 1: Empty — all estimates are 0
    var emptyStats = new IndexStatistics(0, 0, 0);
    assertEquals("Empty tier: equality should be 0", 0.0,
        SelectivityEstimator.estimateEquality(
            emptyStats, null, 42),
        0.0);
    assertEquals("Empty tier: GT should be 0", 0.0,
        SelectivityEstimator.estimateGreaterThan(
            emptyStats, null, 42),
        0.0);

    // Tier 2: Uniform (100 entries, 50 distinct)
    var uniformStats = new IndexStatistics(100, 50, 0);
    double eqUniform =
        SelectivityEstimator.estimateEquality(uniformStats, null, 42);
    assertEquals("Uniform tier: equality = 1/NDV",
        1.0 / 50.0, eqUniform, 1e-9);
    double gtUniform =
        SelectivityEstimator.estimateGreaterThan(
            uniformStats, null, 42);
    assertEquals("Uniform tier: GT = 1/3",
        1.0 / 3.0, gtUniform, 1e-9);

    // Tier 3: Histogram (same 100 entries but with histogram)
    var histogram = createUniformHistogram(100);
    double eqHist = SelectivityEstimator.estimateEquality(
        uniformStats, histogram, 42);
    assertTrue("Histogram equality should be valid",
        eqHist >= 0.0 && eqHist <= 1.0);
    // Histogram estimate need not equal 1/NDV — it uses bucket-level
    // interpolation which can be more or less precise depending on
    // data distribution
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  private static ManagerFixture createManagerFixture() {
    return new ManagerFixture();
  }

  private static void installEmptySnapshot(ManagerFixture fixture) {
    var stats = new IndexStatistics(0, 0, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, null, 0, 0, 0, false, null, false));
  }

  /**
   * Creates a uniform histogram with N entries spread across 4 buckets.
   * Keys are integers [0, N). Each bucket has approximately N/4 entries.
   */
  private static EquiDepthHistogram createUniformHistogram(int n) {
    int q1 = n / 4;
    int q2 = n / 2;
    int q3 = 3 * n / 4;
    long f1 = q1;
    long f2 = q2 - q1;
    long f3 = q3 - q2;
    long f4 = n - q3;
    return new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, q1, q2, q3, n - 1},
        new long[] {f1, f2, f3, f4},
        new long[] {q1, q2 - q1, q3 - q2, n - q3},
        n,
        null,
        0);
  }

  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  private static void setFileId(
      IndexHistogramManager manager, long value) {
    manager.setFileIdForTest(value);
  }

  /**
   * Creates a real IndexHistogramManager with a mock storage.
   */
  private static class ManagerFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    ManagerFixture() {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, true, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }
}
