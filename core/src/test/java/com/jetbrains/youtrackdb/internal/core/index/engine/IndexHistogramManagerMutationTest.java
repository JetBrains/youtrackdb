package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link IndexHistogramManager} static methods.
 *
 * <p>Targets survived mutations in scanAndBuild, fitToPage,
 * truncateBoundary, truncateBoundaries, and computeNewSnapshot.
 */
public class IndexHistogramManagerMutationTest {

  private static final BinarySerializerFactory SF =
      BinarySerializerFactory.create(
          BinarySerializerFactory.currentBinaryFormatVersion());

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: bucket split boundary (line 1067)
  // Mutation changes >= to > in the equi-depth split condition.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_uniformData_producesSplitsAtExpectedPositions() {
    // 100 entries, 4 target buckets → 25 per bucket.
    // Keys: 0,0,0,...,0 (25 times), 1,1,...,1 (25 times), etc.
    // Each group of 25 identical values should be in one bucket.
    Stream<Object> keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 25)); // 0,0,...,1,1,...,2,2,...,3,3

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    assertEquals("Should produce 4 buckets", 4, result.actualBucketCount);
    // Verify bucket boundaries
    assertEquals(0, result.boundaries[0]); // min
    assertEquals(1, result.boundaries[1]); // split at 25th entry
    assertEquals(2, result.boundaries[2]); // split at 50th entry
    assertEquals(3, result.boundaries[3]); // split at 75th entry
    assertEquals(3, result.boundaries[4]); // max
  }

  @Test
  public void scanAndBuild_exactlyTargetBuckets_frequenciesCorrect() {
    // 120 entries, 4 target buckets. 30 per bucket.
    // Keys 1-30 in bucket 0, 31-60 in bucket 1, etc.
    Stream<Object> keys = IntStream.rangeClosed(1, 120)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 120, 4);

    assertNotNull(result);
    assertEquals(4, result.actualBucketCount);
    // Each bucket should have ~30 entries
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue("Bucket " + i + " freq should be > 0",
          result.frequencies[i] > 0);
      totalFreq += result.frequencies[i];
    }
    assertEquals("Total frequency should be 120", 120, totalFreq);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: MCV is last value (line 1091)
  // The final MCV check after the loop catches the case where the
  // most common value is the last value in sort order.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_mcvIsLastValue_identified() {
    // Keys: 1 (once), 2 (once), ..., 9 (once), 10 (50 times)
    // MCV should be 10 with frequency 50
    var keys = Stream.concat(
        IntStream.rangeClosed(1, 9).mapToObj(i -> (Object) i),
        IntStream.range(0, 50).mapToObj(i -> (Object) 10));

    var result = IndexHistogramManager.scanAndBuild(keys, 59, 4);

    assertNotNull(result);
    assertEquals("MCV should be 10 (the last run)", 10, result.mcvValue);
    assertEquals("MCV frequency should be 50", 50, result.mcvFrequency);
  }

  @Test
  public void scanAndBuild_mcvIsMiddleValue_identified() {
    // Keys: 1 (once), 5 (100 times), 10 (once)
    // MCV = 5, frequency = 100
    var keys = Stream.concat(
        Stream.of((Object) 1),
        Stream.concat(
            IntStream.range(0, 100).mapToObj(i -> (Object) 5),
            Stream.of((Object) 10)));

    var result = IndexHistogramManager.scanAndBuild(keys, 102, 4);

    assertNotNull(result);
    assertEquals("MCV should be 5", 5, result.mcvValue);
    assertEquals("MCV frequency should be 100", 100, result.mcvFrequency);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: empty stream (guard at line 1086)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_emptyStream_returnsNull() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.empty(), 0, 4);
    assertNull(result);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: single entry
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_singleEntry_oneBucket() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.of((Object) 42), 1, 4);

    assertNotNull(result);
    assertEquals(1, result.actualBucketCount);
    assertEquals(1, result.frequencies[0]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(42, result.boundaries[0]);
    assertEquals(42, result.boundaries[1]);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: with HLL
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_withHll_populatesSketch() {
    var hll = new HyperLogLogSketch();
    var keys = IntStream.rangeClosed(1, 1000)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(
        keys, 1000, 4, hll,
        key -> MurmurHash3.murmurHash3_x64_64(
            key.toString().getBytes(StandardCharsets.UTF_8), 0x9747b28c));

    assertNotNull(result);
    assertTrue("HLL should have non-zero estimate after scan",
        hll.estimate() > 0);
    // HLL estimate should be close to 1000
    double error = Math.abs((double) hll.estimate() - 1000) / 1000;
    assertTrue("HLL estimate should be close to 1000, got " + hll.estimate(),
        error < 0.1);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: totalDistinct tracking
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_tracksDistinctValuesCorrectly() {
    // 10 distinct values, each appearing 10 times = 100 entries
    Stream<Object> keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 10));

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    assertEquals("totalDistinct should be 10", 10, result.totalDistinct);
    // Sum of distinctCounts should equal totalDistinct
    long sumDistinct = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      sumDistinct += result.distinctCounts[i];
    }
    assertEquals("Sum of per-bucket distinct counts should match",
        result.totalDistinct, sumDistinct);
  }

  // ═══════════════════════════════════════════════════════════════
  // fitToPage: boundary at line 1191 (totalBoundaryBytes <= available)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fitToPage_exactlyFits_doesNotReduceBuckets() {
    // Create a BuildResult with known boundary sizes.
    // Mock boundarySizeCalc to return exactly the available space.
    var boundaries = new Comparable<?>[] {0, 25, 50, 75, 100};
    var result = new IndexHistogramManager.BuildResult(
        boundaries,
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        4, 100, null, 0);

    // Compute what available space would be for 4 buckets with no HLL
    int available = IndexHistogramManager.computeMaxBoundarySpace(
        4, 0, 0);

    // Use a boundarySizeCalc that returns exactly 'available'
    var fit = IndexHistogramManager.fitToPage(
        result, 1000, true, 0,
        (b, n) -> available);

    assertNotNull(fit);
    assertEquals("Should not reduce buckets when exactly fitting",
        4, fit.histogram().bucketCount());
  }

  @Test
  public void fitToPage_exceedsByOne_reducesBuckets() {
    // Use 8 buckets (above MINIMUM_BUCKET_COUNT=4) so the reduction loop runs.
    var boundaries = new Comparable<?>[9];
    var freqs = new long[8];
    var distincts = new long[8];
    for (int i = 0; i <= 8; i++) {
      boundaries[i] = i * 10;
    }
    for (int i = 0; i < 8; i++) {
      freqs[i] = 125;
      distincts[i] = 12;
    }
    var result = new IndexHistogramManager.BuildResult(
        boundaries, freqs, distincts,
        8, 96, null, 0);

    int available = IndexHistogramManager.computeMaxBoundarySpace(
        8, 0, 0);

    // Return one byte more than available → should reduce
    var fit = IndexHistogramManager.fitToPage(
        result, 1000, true, 0,
        (b, n) -> available + 1);

    assertNotNull(fit);
    assertTrue("Should reduce bucket count when exceeding by 1",
        fit.histogram().bucketCount() < 8);
  }

  // ═══════════════════════════════════════════════════════════════
  // truncateBoundaries: adjacent boundaries become equal (lines 1396, 1412)
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_adjacentBecomeEqual_mergesBuckets() {
    // String boundaries that differ only after maxBoundaryBytes.
    // After truncation, they become equal → buckets merge.
    // StringSerializer serializes as 4-byte header + 2 bytes per char.
    // maxBoundaryBytes=12 allows 4 chars: (12-4)/2 = 4 chars.
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {"aaaa_x", "aaaa_y", "bbbb_z"},
        new long[] {100, 200},
        new long[] {10, 20},
        2, 30, null, 0);

    // maxBoundaryBytes=12: 4-byte header + 4 chars × 2 bytes = 12 bytes
    // "aaaa_x" truncated to "aaaa", "aaaa_y" truncated to "aaaa" → equal!
    // "bbbb_z" truncated to "bbbb"
    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 12, serializer, SF);

    assertNotNull(truncated);
    // "aaaa" and "aaaa" merged → 1 bucket with freq=300, ndv=30
    // Then "bbbb" → second bucket boundary
    assertEquals("After merging, should have 1 bucket",
        1, truncated.actualBucketCount);
    assertEquals("Merged freq should be 100+200=300",
        300, truncated.frequencies[0]);
    assertEquals("Merged ndv should be 10+20=30",
        30, truncated.distinctCounts[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_allCollapse_singleBucket() {
    // All boundaries truncate to the same value
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {"aaaa_1", "aaaa_2", "aaaa_3", "aaaa_4", "aaaa_5"},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        4, 100, null, 0);

    // All truncate to "aaaa" → newBucketCount = 0 → degenerate single-bucket
    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 12, serializer, SF);

    assertNotNull(truncated);
    assertEquals("All boundaries collapsed → 1 bucket",
        1, truncated.actualBucketCount);
    assertEquals("Total freq should be 100+200+300+400=1000",
        1000, truncated.frequencies[0]);
    assertEquals("Total ndv should be 10+20+30+40=100",
        100, truncated.distinctCounts[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_noTruncationNeeded_returnsOriginal() {
    // All boundaries fit within maxBytes → no truncation needed
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) IntegerSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        4, 100, null, 0);

    // Integer serialized size is 4 bytes. maxBoundaryBytes=256 → no truncation.
    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 256, serializer, SF);

    // Should return the original result (identity)
    assertEquals(4, truncated.actualBucketCount);
    for (int i = 0; i <= 4; i++) {
      assertEquals(result.boundaries[i], truncated.boundaries[i]);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // truncateBoundaries: frequency accumulation (line 1437)
  // and compare params swap (line 1445)
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_mixedMerge_correctAccumulation() {
    // 4 buckets where truncation causes pairs to merge:
    // "aaaa_1", "aaaa_2" → both "aaaa" (merge)
    // "bbbb_1", "bbbb_2" → both "bbbb" (merge)
    // "cccc"             → "cccc" (no merge, 5th boundary = upper)
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {"aaaa_1", "aaaa_2", "bbbb_1", "bbbb_2", "cccc"},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        4, 100, null, 0);

    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 12, serializer, SF);

    assertNotNull(truncated);
    // "aaaa", "aaaa" merge → bucket 0 (freq=300, ndv=30)
    // "bbbb", "bbbb" merge → bucket 1 (freq=700, ndv=70)
    // Actually: "aaaa" → "bbbb" is one boundary, "bbbb" → "cccc" is another
    // So 2 unique pairs = 2 buckets
    assertEquals("Should have 2 buckets after pairwise merge",
        2, truncated.actualBucketCount);
    assertEquals(300, truncated.frequencies[0]); // 100+200
    assertEquals(700, truncated.frequencies[1]); // 300+400
    assertEquals(30, truncated.distinctCounts[0]);
    assertEquals(70, truncated.distinctCounts[1]);
  }

  // ═══════════════════════════════════════════════════════════════
  // computeNewSnapshot: delta application (various lines in 570-600)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_positiveDelta_incrementsCounters() {
    var stats = new IndexStatistics(1000, 1000, 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 1000, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.nullCountDelta = 2;
    delta.mutationCount = 10;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals(1010, result.stats().totalCount());
    assertEquals(52, result.stats().nullCount());
    assertEquals(10, result.mutationsSinceRebalance());
  }

  @Test
  public void computeNewSnapshot_negativeDelta_clampedToZero() {
    var stats = new IndexStatistics(10, 10, 5);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 10, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -100;
    delta.nullCountDelta = -100;
    delta.mutationCount = 100;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals("totalCount clamped to 0", 0, result.stats().totalCount());
    assertEquals("nullCount clamped to 0", 0, result.stats().nullCount());
  }

  @Test
  public void computeNewSnapshot_withHistogram_updatesFrequencies() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500},
        new long[] {50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, h, 0, 1000, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.initFrequencyDeltas(2, 0);
    delta.frequencyDeltas[0] = 3;
    delta.frequencyDeltas[1] = 2;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertNotNull(result.histogram());
    assertEquals(503, result.histogram().frequencies()[0]);
    assertEquals(502, result.histogram().frequencies()[1]);
    assertEquals(1005, result.histogram().nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════
  // computeNewSnapshot: bucket frequency goes negative → clamped + drift flag
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_negativeBucketFreq_clampedAndDrifted() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {100, 100},
        new long[] {10, 10},
        200, null, 0);
    var stats = new IndexStatistics(200, 20, 0);
    var snapshot = new HistogramSnapshot(
        stats, h, 0, 200, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -150;
    delta.mutationCount = 150;
    delta.initFrequencyDeltas(2, 0);
    delta.frequencyDeltas[0] = -150; // bucket 0: 100 - 150 = -50 → clamped to 0

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertNotNull(result.histogram());
    assertEquals("Bucket 0 freq clamped to 0", 0, result.histogram().frequencies()[0]);
    assertEquals("Bucket 1 freq unchanged", 100, result.histogram().frequencies()[1]);
    assertTrue("hasDriftedBuckets should be set", result.hasDriftedBuckets());
    // nonNullCount = max(0, newTotal - newNull) = max(0, 50 - 0) = 50
    // (uses accurate scalar counters, not the sum of clamped frequencies)
    assertEquals("nonNullCount = max(0, 50 - 0) = 50", 50,
        result.histogram().nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: fewer data points than target buckets
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_fewerKeysThanTargetBuckets_trimsBuckets() {
    // 3 distinct entries but 8 target buckets → should trim to fewer
    Stream<Object> keys = IntStream.rangeClosed(1, 3)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 3, 8);

    assertNotNull(result);
    assertTrue("Should use fewer buckets than target",
        result.actualBucketCount <= 3);
    // All frequencies should sum to 3
    long sum = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      sum += result.frequencies[i];
    }
    assertEquals(3, sum);
  }

  // ═══════════════════════════════════════════════════════════════
  // scanAndBuild: all duplicate keys
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_allDuplicateKeys_oneBucket() {
    // 100 entries all with key=42
    Stream<Object> keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) 42);

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    // With all same keys, should be 1 bucket (no splits triggered since
    // key transitions never happen after the first entry)
    assertEquals(1, result.actualBucketCount);
    assertEquals(100, result.frequencies[0]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(1, result.totalDistinct);
    assertEquals(42, result.mcvValue);
    assertEquals(100, result.mcvFrequency);
  }

  // ═══════════════════════════════════════════════════════════════
  // fitToPage with HLL spill
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fitToPage_multiValueWithHllSpill_setsFlag() {
    // Use 5 buckets. Loop: 5 > 4 → enter, doesn't fit → 5/2=2 < 4 → spill!
    // After spill, reset to 5 buckets with no HLL, and recheck.
    var boundaries = new Comparable<?>[6];
    var freqs = new long[5];
    var distincts = new long[5];
    for (int i = 0; i <= 5; i++) {
      boundaries[i] = i * 10;
    }
    for (int i = 0; i < 5; i++) {
      freqs[i] = 200;
      distincts[i] = 20;
    }
    var result = new IndexHistogramManager.BuildResult(
        boundaries, freqs, distincts,
        5, 100, null, 0);

    int hllSize = HyperLogLogSketch.serializedSize();
    // Available space WITH HLL for 5 buckets
    int availableWithHll5 = IndexHistogramManager.computeMaxBoundarySpace(
        5, hllSize, 0);
    // Available space WITHOUT HLL for 5 buckets
    int availableWithoutHll5 = IndexHistogramManager.computeMaxBoundarySpace(
        5, 0, 0);

    // Boundary size that doesn't fit with HLL but fits without HLL
    int boundarySize = availableWithHll5 + 1;
    assertTrue("Test setup: should fit without HLL",
        boundarySize <= availableWithoutHll5);

    var fit = IndexHistogramManager.fitToPage(
        result, 1000, false, 0,
        (b, n) -> boundarySize);

    assertNotNull(fit);
    assertTrue("HLL should be spilled to page 1", fit.hllOnPage1());
  }

  @Test
  public void scanAndBuild_largeData_frequenciesAndDistinctsSumCorrectly() {
    Stream<Object> keys = IntStream.range(0, 10000)
        .mapToObj(i -> (Object) (i / 100));

    var result = IndexHistogramManager.scanAndBuild(keys, 10000, 4);

    assertNotNull(result);
    assertEquals(4, result.actualBucketCount);

    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue("Bucket " + i + " freq > 0", result.frequencies[i] > 0);
      totalFreq += result.frequencies[i];
    }
    assertEquals("Total freq = 10000", 10000, totalFreq);

    long totalDistinct = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue("Bucket " + i + " distinct > 0", result.distinctCounts[i] > 0);
      totalDistinct += result.distinctCounts[i];
    }
    assertEquals("Total distinct = 100", 100, totalDistinct);
  }

  @Test
  public void scanAndBuild_stringKeys_correctMinMaxBoundaries() {
    Stream<Object> keys = IntStream.range(0, 26)
        .mapToObj(i -> (Object) (String.valueOf((char) ('a' + i)).repeat(3)));

    var result = IndexHistogramManager.scanAndBuild(keys, 26, 4);

    assertNotNull(result);
    assertTrue(result.actualBucketCount >= 1);
    assertEquals(26, result.totalDistinct);
    assertEquals("aaa", result.boundaries[0]);
    assertEquals("zzz", result.boundaries[result.actualBucketCount]);
  }

  @Test
  public void computeNewSnapshot_hllMerge_increasesEstimate() {
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 500; i++) {
      hll.add(MurmurHash3.murmurHash3_x64_64(
          String.valueOf(i).getBytes(StandardCharsets.UTF_8), 0x9747b28c));
    }
    long baseEstimate = hll.estimate();

    var stats = new IndexStatistics(500, baseEstimate, 0);
    var snapshot = new HistogramSnapshot(stats, null, 0, 500, 0, false, hll, false);

    var deltaHll = new HyperLogLogSketch();
    for (int i = 500; i < 1000; i++) {
      deltaHll.add(MurmurHash3.murmurHash3_x64_64(
          String.valueOf(i).getBytes(StandardCharsets.UTF_8), 0x9747b28c));
    }
    var delta = new HistogramDelta();
    delta.totalCountDelta = 500;
    delta.mutationCount = 500;
    delta.hllSketch = deltaHll;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertNotNull(result.hllSketch());
    assertTrue("HLL estimate should increase after merge",
        result.hllSketch().estimate() > baseEstimate);
  }

  @Test
  public void computeNewSnapshot_nullDeltaHll_preservesOriginal() {
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 500; i++) {
      hll.add(i * 7919L);
    }
    long baseEstimate = hll.estimate();

    var stats = new IndexStatistics(500, baseEstimate, 0);
    var snapshot = new HistogramSnapshot(stats, null, 0, 500, 0, false, hll, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 10;
    delta.hllSketch = null;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertNotNull(result.hllSketch());
    assertEquals("HLL unchanged", baseEstimate, result.hllSketch().estimate());
  }
}
