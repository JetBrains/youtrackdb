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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Multi-value index histogram tests (Section 10.7 of the ADR).
 *
 * <p>Exercises multi-value-specific histogram behavior: HLL sketch lifecycle,
 * NDV tracking, HLL merge on commit, HLL persistence, single-value no-HLL,
 * lazy HLL initialization, and onPut/onRemove delta handling.
 *
 * <p>Tests use scanAndBuild + computeNewSnapshot to test histogram logic
 * without page I/O. Actual page-level persistence round-trips (close/re-open)
 * are covered by {@code HistogramStatsPageHllSpillTest} and integration tests.
 */
public class MultiValueIndexHistogramTest {

  // ═════════════════════════════════════════════════════════════════
  // Multi-value with multiple values per key — histogram on original keys
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_multipleValuesPerKey_histogramOnOriginalKeys() {
    // Given a multi-value key stream where key 1 appears 5x, key 2 appears
    // 3x, key 3 appears 2x (10 entries, 3 distinct keys). In multi-value
    // indexes, the same original key maps to multiple RIDs.
    Stream<Object> sortedKeys = Stream.of(1, 1, 1, 1, 1, 2, 2, 2, 3, 3);

    // When we build a histogram via scanAndBuild
    var result = IndexHistogramManager.scanAndBuild(sortedKeys, 10, 4);

    // Then the histogram operates on original keys (not CompositeKeys)
    assertNotNull(result);
    assertEquals(3, result.totalDistinct);
    // Frequencies should sum to 10
    long freqSum = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      freqSum += result.frequencies[i];
    }
    assertEquals(10, freqSum);
  }

  @Test
  public void multiValue_ndvCorrectDuringBuild() {
    // Given 100 entries with only 5 distinct keys (each appearing 20 times).
    // Stream is already sorted by construction (i/20 produces 0,0,...,1,...).
    Stream<Object> sortedKeys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 20));

    // When we build a histogram
    var result = IndexHistogramManager.scanAndBuild(sortedKeys, 100, 8);

    // Then NDV = 5, not 100
    assertNotNull(result);
    assertEquals(5, result.totalDistinct);
  }

  // ═════════════════════════════════════════════════════════════════
  // onPut / onRemove with original keys
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_onPut_tracksFrequencyDelta() {
    // Given a multi-value manager with an established histogram and HLL
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    installSnapshotWithHll(fixture, 100, 80, 0,
        create3BucketHistogram(), hll, false);

    // When onPut is called with original key=15 (bucket 1: [10..20))
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 15, false, true);

    // Then delta has totalCountDelta=1 and frequency delta in correct bucket
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(1, delta.totalCountDelta);
    assertEquals(1, delta.mutationCount);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(1, delta.frequencyDeltas[1]);
  }

  @Test
  public void multiValue_onPut_updatesHllInDelta() {
    // Given a multi-value manager with HLL in snapshot
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    installSnapshotWithHll(fixture, 100, 80, 0, null, hll, false);

    // When onPut is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, false, true);

    // Then the delta has an HLL sketch with the key hash
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull("Delta HLL should be non-null", delta.hllSketch);
  }

  @Test
  public void multiValue_onRemove_updatesFrequencyButNotHll() {
    // Given a multi-value manager with histogram and HLL
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    installSnapshotWithHll(fixture, 100, 80, 0,
        create3BucketHistogram(), hll, false);

    // When onRemove is called with key=5 (bucket 0: [0..10))
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, 5, false);

    // Then totalCountDelta=-1, frequency delta decremented, but no HLL update
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(-1, delta.totalCountDelta);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(-1, delta.frequencyDeltas[0]);
    // HLL is insert-only — not updated on remove
    assertNull("HLL should not be updated on remove", delta.hllSketch);
  }

  @Test
  public void multiValue_onPut_multipleKeysInSameTransaction() {
    // Given a multi-value manager with histogram and HLL
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    installSnapshotWithHll(fixture, 100, 80, 0,
        create3BucketHistogram(), hll, false);

    // When multiple puts in the same transaction
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 5, false, true);   // bucket 0
    fixture.manager.onPut(op, 15, false, true);  // bucket 1
    fixture.manager.onPut(op, 15, false, true);  // bucket 1 again
    fixture.manager.onPut(op, 25, false, true);  // bucket 2

    // Then deltas accumulate correctly
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(4, delta.totalCountDelta);
    assertEquals(4, delta.mutationCount);
    assertEquals(1, delta.frequencyDeltas[0]);
    assertEquals(2, delta.frequencyDeltas[1]);
    assertEquals(1, delta.frequencyDeltas[2]);
    // HLL should have entries for all 3 distinct keys
    assertNotNull(delta.hllSketch);
  }

  // ═════════════════════════════════════════════════════════════════
  // HLL sketch persistence round-trip (via computeNewSnapshot)
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hll_persistenceRoundTrip_distinctCountPreserved() {
    // Given a multi-value snapshot with HLL populated from 50 known keys.
    // This tests the in-memory round-trip; page-level serialization is
    // covered by HistogramStatsPageHllSpillTest.
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 50; i++) {
      hll.add(fixture.manager.hashKey(i));
    }
    long expectedDistinct = hll.estimate();
    assertTrue("HLL should estimate ~50 for sanity",
        expectedDistinct > 40 && expectedDistinct < 60);

    var stats = new IndexStatistics(200, expectedDistinct, 0);
    var snapshot = new HistogramSnapshot(
        stats, create3BucketHistogram(), 0, 200, 0, false, hll, false);

    // When we round-trip through computeNewSnapshot with a no-op delta
    var delta = new HistogramDelta();
    delta.totalCountDelta = 0;
    delta.mutationCount = 0;
    var roundTripped =
        IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then distinctCount is preserved (not reset to 0)
    assertEquals(expectedDistinct, roundTripped.stats().distinctCount());
    assertNotNull(roundTripped.hllSketch());
    assertEquals(expectedDistinct, roundTripped.hllSketch().estimate());
  }

  // ═════════════════════════════════════════════════════════════════
  // HLL merge on commit
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hll_mergeOnCommit_reflectsAllInserts() {
    // Given a multi-value manager with HLL tracking 10 distinct keys
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 10; i++) {
      hll.add(fixture.manager.hashKey(i));
    }
    installSnapshotWithHll(fixture, 100, hll.estimate(), 0,
        create3BucketHistogram(), hll, false);

    // When Tx1 inserts keys 10..19 and commits
    var holder1 = new HistogramDeltaHolder();
    var op1 = mockOp(holder1);
    for (int i = 10; i < 20; i++) {
      fixture.manager.onPut(op1, i, false, true);
    }
    fixture.manager.applyDelta(
        holder1.getDeltas().get(fixture.engineId));

    // Then Tx2 inserts keys 20..29 and commits
    var holder2 = new HistogramDeltaHolder();
    var op2 = mockOp(holder2);
    for (int i = 20; i < 30; i++) {
      fixture.manager.onPut(op2, i, false, true);
    }
    fixture.manager.applyDelta(
        holder2.getDeltas().get(fixture.engineId));

    // Then the snapshot's HLL reflects all 30 distinct keys
    var snapshot = fixture.cache.get(fixture.engineId);
    assertNotNull(snapshot.hllSketch());
    long estimate = snapshot.hllSketch().estimate();
    // HLL with 1024 registers has ~3.25% standard error
    assertTrue("HLL should estimate ~30 but got " + estimate,
        estimate >= 25 && estimate <= 35);
  }

  @Test
  public void hll_mergeOnCommit_duplicateKeysNotDoubleCountedInHll() {
    // Given a multi-value manager with HLL tracking keys 0..9
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 10; i++) {
      hll.add(fixture.manager.hashKey(i));
    }
    long baseEstimate = hll.estimate();
    installSnapshotWithHll(fixture, 100, baseEstimate, 0,
        create3BucketHistogram(), hll, false);

    // When another transaction inserts the same keys 0..9 again
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    for (int i = 0; i < 10; i++) {
      fixture.manager.onPut(op, i, false, true);
    }
    fixture.manager.applyDelta(
        holder.getDeltas().get(fixture.engineId));

    // Then HLL estimate is still ~10 (idempotent for same keys)
    var snapshot = fixture.cache.get(fixture.engineId);
    long estimate = snapshot.hllSketch().estimate();
    assertTrue("HLL should still estimate ~10 but got " + estimate,
        estimate >= 8 && estimate <= 13);
  }

  // ═════════════════════════════════════════════════════════════════
  // HLL distinctCount update on commit
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hll_distinctCountUpdatedOnCommit() {
    // Given a snapshot with HLL containing 1 key
    var fixture = new MultiValueFixture();
    var hll = new HyperLogLogSketch();
    hll.add(fixture.manager.hashKey(1));
    var stats = new IndexStatistics(1, hll.estimate(), 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 1, 0, false, hll, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When inserting 49 more distinct keys and committing
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    for (int i = 2; i <= 50; i++) {
      fixture.manager.onPut(op, i, false, true);
    }
    fixture.manager.applyDelta(
        holder.getDeltas().get(fixture.engineId));

    // Then distinctCount equals hll.estimate() after commit
    var result = fixture.cache.get(fixture.engineId);
    assertNotNull(result.hllSketch());
    assertEquals("distinctCount should equal HLL estimate",
        result.hllSketch().estimate(), result.stats().distinctCount());
  }

  // ═════════════════════════════════════════════════════════════════
  // HLL page overflow (via fitToPage with synthetic boundary sizes)
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hll_pageOverflow_fitToPageSpillsHllToPage1() {
    // Given a scan result with 5 buckets. The spill path triggers when
    // bucketCount/2 < MINIMUM_BUCKET_COUNT (5/2=2 < 4), which causes HLL
    // to be spilled to page 1 and the original bucket count to be restored.
    Stream<Object> sortedKeys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) i);
    var result = IndexHistogramManager.scanAndBuild(sortedKeys, 100, 5);
    assertNotNull(result);
    assertEquals("Need 5 buckets for this test",
        5, result.actualBucketCount);

    // Compute the budget for 5 buckets with HLL. The synthetic boundary
    // size must exceed this budget (triggering reduction) but fit after
    // HLL spill (budget without HLL is larger by HLL_SIZE=1024 bytes).
    int hllSize = HyperLogLogSketch.serializedSize();
    int budgetWith5BucketsAndHll =
        IndexHistogramManager.computeMaxBoundarySpace(5, hllSize, 0);
    int budgetWith5BucketsNoHll =
        IndexHistogramManager.computeMaxBoundarySpace(5, 0, 0);

    // Boundary exceeds budget WITH HLL but fits WITHOUT HLL
    int syntheticBoundarySize = budgetWith5BucketsAndHll + 1;
    assertTrue("Boundary must fit without HLL for spill to succeed",
        syntheticBoundarySize <= budgetWith5BucketsNoHll);

    // When fitToPage is called with this boundary size
    var fitResult = IndexHistogramManager.fitToPage(
        result, 100, false, 0,
        (boundaries, count) -> syntheticBoundarySize);

    // Then HLL is spilled to page 1 and histogram is preserved
    assertNotNull("fitResult should not be null", fitResult);
    assertTrue("HLL should be spilled to page 1",
        fitResult.hllOnPage1());
    assertNotNull("Histogram should still exist after HLL spill",
        fitResult.histogram());
  }

  @Test
  public void hll_noOverflow_hllStaysOnPage0() {
    // Given a scan result with small boundaries that fit on page 0
    Stream<Object> sortedKeys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) i);
    var result = IndexHistogramManager.scanAndBuild(sortedKeys, 100, 4);
    assertNotNull(result);

    // When fitToPage is called with small boundary sizes (easily fits)
    var fitResult = IndexHistogramManager.fitToPage(
        result, 100, false, 0,
        (boundaries, count) -> (count + 1) * 4);

    // Then HLL stays on page 0
    assertNotNull(fitResult);
    assertFalse("HLL should stay on page 0 when boundaries fit",
        fitResult.hllOnPage1());
  }

  // ═════════════════════════════════════════════════════════════════
  // Single-value index has no HLL
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_noHllInSnapshot() {
    // Given a single-value snapshot (no HLL)
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, create3BucketHistogram(), 0, 100, 0, false, null, false);

    // Then no HLL
    assertNull("Single-value should have no HLL sketch",
        snapshot.hllSketch());
    assertFalse("Single-value should not have hllOnPage1",
        snapshot.hllOnPage1());
  }

  @Test
  public void singleValue_computeNewSnapshot_distinctEqualsTotal() {
    // Given a single-value snapshot with no HLL
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);

    // When applying a delta with +5 inserts
    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then distinctCount == totalCount (no HLL involved)
    assertEquals(105, result.stats().totalCount());
    assertEquals(105, result.stats().distinctCount());
    assertNull(result.hllSketch());
  }

  @Test
  public void singleValue_fitToPage_hllOnPage1IsFalse() {
    // Given: fitToPage for single-value index (singleValue=true).
    // Single-value indexes have hllSize=0, so HLL never spills.
    Stream<Object> sortedKeys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) i);
    var result = IndexHistogramManager.scanAndBuild(sortedKeys, 100, 8);
    assertNotNull(result);

    var sfFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);

    var fitResult = IndexHistogramManager.fitToPage(
        result, 100, true, 0,
        (boundaries, count) -> {
          int total = 0;
          for (int i = 0; i <= count; i++) {
            byte[] bytes = IntegerSerializer.INSTANCE.serializeNativeAsWhole(
                sfFactory, (Integer) boundaries[i]);
            total += bytes.length;
          }
          return total;
        });

    // Then HLL never spills for single-value
    assertNotNull(fitResult);
    assertFalse("Single-value should never have hllOnPage1",
        fitResult.hllOnPage1());
  }

  // ═════════════════════════════════════════════════════════════════
  // Lazy HLL init
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void lazyHllInit_belowMinSize_noHllInSnapshot() {
    // Given a multi-value snapshot below HISTOGRAM_MIN_SIZE (no histogram).
    // Below threshold, buildHistogram creates a counters-only snapshot with
    // hllSketch=null (lazy init).
    var stats = new IndexStatistics(3, 3, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 3, 0, false, null, false);

    // Then no HLL (lazy init: below min size)
    assertNull("HLL should be null below min size", snapshot.hllSketch());
    assertNull("Histogram should be null below min size",
        snapshot.histogram());
  }

  @Test
  public void lazyHllInit_onPutWithNullHll_noHllInDelta() {
    // Given a multi-value manager with snapshot that has no HLL
    // (below threshold, lazy init)
    var fixture = new MultiValueFixture();
    var snapshot = new HistogramSnapshot(
        new IndexStatistics(3, 3, 0), null,
        0, 3, 0, false, null, false);
    fixture.cache.put(fixture.engineId, snapshot);

    // When onPut is called
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, 42, false, true);

    // Then no HLL in delta (snapshot has no HLL to merge with)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNull("No HLL should be created when snapshot has no HLL",
        delta.hllSketch);
  }

  @Test
  public void lazyHllInit_afterThresholdCrossing_hllMergeWorks() {
    // Given a multi-value snapshot that has crossed the threshold and now
    // has a histogram and HLL (simulating post-buildHistogram state)
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 100; i++) {
      hll.add(i * 97L);
    }
    var stats = new IndexStatistics(100, hll.estimate(), 0);
    var snapshot = new HistogramSnapshot(
        stats, create3BucketHistogram(), 0, 100, 0, false, hll, false);

    // When a delta with new HLL entries is applied (incremental updates
    // start once the snapshot has an HLL)
    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.hllSketch = new HyperLogLogSketch();
    for (int i = 100; i < 105; i++) {
      delta.hllSketch.add(i * 97L);
    }

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then HLL is present and distinctCount reflects the merge
    assertNotNull("HLL should be present after threshold crossing",
        result.hllSketch());
    long estimate = result.stats().distinctCount();
    assertTrue("distinctCount should be ~105 but was " + estimate,
        estimate >= 95 && estimate <= 115);
  }

  // ═════════════════════════════════════════════════════════════════
  // HLL merge: multi-value with no HLL updates in delta
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hll_noHllInDelta_distinctCountPreserved() {
    // Given a multi-value snapshot with HLL (distinctCount=80)
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 80; i++) {
      hll.add(i * 1000L + 7);
    }
    var stats = new IndexStatistics(100, 80, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, hll, false);

    // When a delta with no HLL sketch is applied (e.g., a remove-only tx)
    var delta = new HistogramDelta();
    delta.totalCountDelta = -1;
    delta.mutationCount = 1;
    // delta.hllSketch remains null

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then distinctCount is preserved (not changed)
    assertEquals(80, result.stats().distinctCount());
    assertNotNull(result.hllSketch());
  }

  // ═════════════════════════════════════════════════════════════════
  // Multi-value: NDV and null handling
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_withNullKeys_correctCountsInSnapshot() {
    // Given a multi-value snapshot with nulls
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 30; i++) {
      hll.add(i * 13L);
    }
    var stats = new IndexStatistics(100, hll.estimate(), 10);
    var snapshot = new HistogramSnapshot(
        stats, create3BucketHistogram(), 0, 100, 0, false, hll, false);
    var fixture = new MultiValueFixture();
    fixture.cache.put(fixture.engineId, snapshot);

    // When onPut with null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, false, true);

    // Then nullCountDelta=1, totalCountDelta=1, no frequency delta
    var delta = holder.getDeltas().get(fixture.engineId);
    assertEquals(1, delta.totalCountDelta);
    assertEquals(1, delta.nullCountDelta);
    assertEquals(1, delta.mutationCount);
    // No frequency delta for null keys (they don't go in any bucket)
    assertNull("Null key should not create frequency deltas",
        delta.frequencyDeltas);
    // No HLL update for null keys
    assertNull("Null key should not create HLL delta", delta.hllSketch);
  }

  // ═════════════════════════════════════════════════════════════════
  // computeNewSnapshot: HLL merge produces correct distinctCount
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_hllMerge_updatesDistinctCount() {
    // Given a snapshot with HLL containing keys 1..10
    var fixture = new MultiValueFixture();
    var snapshotHll = new HyperLogLogSketch();
    for (int i = 1; i <= 10; i++) {
      snapshotHll.add(fixture.manager.hashKey(i));
    }
    var stats = new IndexStatistics(10, snapshotHll.estimate(), 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 10, 0, false, snapshotHll, false);

    // When a delta adds keys 11..20 via HLL
    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 10;
    delta.hllSketch = new HyperLogLogSketch();
    for (int i = 11; i <= 20; i++) {
      delta.hllSketch.add(fixture.manager.hashKey(i));
    }

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then distinctCount is updated from merged HLL (should be ~20)
    long estimate = result.stats().distinctCount();
    assertTrue("distinctCount should be ~20 but was " + estimate,
        estimate >= 17 && estimate <= 23);
    assertEquals("distinctCount should equal HLL estimate",
        result.hllSketch().estimate(), result.stats().distinctCount());
  }

  @Test
  public void computeNewSnapshot_hllMerge_clonesSnapshotHll() {
    // Given a snapshot with HLL
    var snapshotHll = new HyperLogLogSketch();
    snapshotHll.add(12345L);
    long originalEstimate = snapshotHll.estimate();

    var stats = new IndexStatistics(1, originalEstimate, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 1, 0, false, snapshotHll, false);

    // When a delta with HLL is applied
    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;
    delta.hllSketch = new HyperLogLogSketch();
    delta.hllSketch.add(67890L);

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then the original snapshot's HLL is unchanged (clone independence)
    assertEquals(originalEstimate, snapshotHll.estimate());
    // But the result's HLL reflects the merge
    assertNotNull(result.hllSketch());
    assertTrue(result.hllSketch().estimate() >= originalEstimate);
  }

  // ═════════════════════════════════════════════════════════════════
  // Multi-value: HLL hashKey works correctly
  // ═════════════════════════════════════════════════════════════════

  @Test
  public void hashKey_deterministicForSameKey() {
    // Given a multi-value manager
    var fixture = new MultiValueFixture();

    // When hashing the same key twice
    long hash1 = fixture.manager.hashKey(42);
    long hash2 = fixture.manager.hashKey(42);

    // Then hashes are equal
    assertEquals(hash1, hash2);
  }

  @Test
  public void hashKey_differentKeysProduceDifferentHashes() {
    // Given a multi-value manager
    var fixture = new MultiValueFixture();

    // When hashing different keys
    long hash1 = fixture.manager.hashKey(1);
    long hash2 = fixture.manager.hashKey(2);

    // Then hashes are different (with overwhelmingly high probability)
    assertTrue("Different keys should produce different hashes",
        hash1 != hash2);
  }

  // ═════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═════════════════════════════════════════════════════════════════

  /**
   * Creates a 3-bucket histogram over integer keys [0..30).
   * Bucket 0: [0, 10), Bucket 1: [10, 20), Bucket 2: [20, 29].
   */
  private static EquiDepthHistogram create3BucketHistogram() {
    return new EquiDepthHistogram(
        3,
        new Comparable<?>[]{0, 10, 20, 29},
        new long[]{34, 33, 33},
        new long[]{10, 10, 10},
        100,
        null, 0
    );
  }

  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  private static void installSnapshotWithHll(
      MultiValueFixture fixture, long totalCount, long distinctCount,
      long nullCount, EquiDepthHistogram histogram,
      HyperLogLogSketch hll, boolean hllOnPage1) {
    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(
            stats, histogram, 0, totalCount, 0, false,
            hll, hllOnPage1));
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

  /**
   * Multi-value fixture using integer keys (isSingleValue=false).
   */
  private static class MultiValueFixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    MultiValueFixture() {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-mv-idx", engineId, false, cache,
          IntegerSerializer.INSTANCE, serializerFactory,
          IntegerSerializer.ID);
    }
  }
}
