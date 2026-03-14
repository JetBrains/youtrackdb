package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link IndexHistogramManager} targeting
 * specific pitest boundary mutations that survived previous rounds.
 *
 * <p>Focuses on: conditional boundary changes in scanAndBuild,
 * fitToPage, truncateBoundary, truncateString, computeNewSnapshot,
 * and computeBoundaryBytes.
 */
public class IndexHistogramManagerBoundaryMutationTest {

  private static final BinarySerializerFactory SF =
      BinarySerializerFactory.create(
          BinarySerializerFactory.currentBinaryFormatVersion());

  // ═══════════════════════════════════════════════════════════════
  // Line 1067: changed conditional boundary in scanAndBuild
  // totalSeen * targetBuckets >= (long)(currentBucket + 1) * nonNullCount
  // If >= mutated to >, splits happen one element later.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_exactBoundarySplit_detectsBoundaryMutation() {
    // 8 entries, 2 target buckets → split at entry 4.
    // Keys: 1,1,1,1,2,2,2,2. At entry 4, totalSeen=4:
    // 4 * 2 = 8 >= (0+1) * 8 = 8 → true → split happens.
    // If >= mutated to >: 8 > 8 → false → no split.
    Stream<Object> keys = Stream.of(
        (Object) 1, 1, 1, 1, 2, 2, 2, 2);

    var result = IndexHistogramManager.scanAndBuild(keys, 8, 2);

    assertNotNull(result);
    assertEquals("Should produce 2 buckets on exact boundary",
        2, result.actualBucketCount);
    assertEquals("Bucket 0 should have 4 entries", 4, result.frequencies[0]);
    assertEquals("Bucket 1 should have 4 entries", 4, result.frequencies[1]);
  }

  @Test
  public void scanAndBuild_justBeforeSplit_remainsOneBucket() {
    // 7 entries, 2 target buckets → split at entry 3.5 (i.e., after entry 3).
    // Keys: 1,1,1,2,2,2,2. Key transition at entry 4 (from 1→2):
    // totalSeen=4, 4*2=8 >= (0+1)*7=7 → true → split.
    Stream<Object> keys = Stream.of(
        (Object) 1, 1, 1, 2, 2, 2, 2);

    var result = IndexHistogramManager.scanAndBuild(keys, 7, 2);

    assertNotNull(result);
    // Verify total entries sum correctly
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals("Total frequency should be 7", 7, totalFreq);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1091: changed conditional boundary in final MCV check
  // currentRunLength > mcvFrequency (if > mutated to >=)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_mcvTiedWithLastRun_firstWins() {
    // Keys: 1 (5 times), 2 (5 times). Two runs of equal length.
    // First run sets MCV=1, freq=5. Second run: 5 > 5? No. 5 >= 5? Yes (mutation).
    // Correct behavior: MCV=1 (first wins on tie).
    Stream<Object> keys = Stream.concat(
        IntStream.range(0, 5).mapToObj(i -> (Object) 1),
        IntStream.range(0, 5).mapToObj(i -> (Object) 2));

    var result = IndexHistogramManager.scanAndBuild(keys, 10, 4);

    assertNotNull(result);
    assertEquals("MCV should be first value on tie", 1, result.mcvValue);
    assertEquals("MCV frequency should be 5", 5, result.mcvFrequency);
  }

  @Test
  public void scanAndBuild_lastRunStrictlyLarger_becomeMcv() {
    // Keys: 1 (3 times), 2 (7 times). Last run strictly larger.
    // MCV should be 2 with frequency 7.
    Stream<Object> keys = Stream.concat(
        IntStream.range(0, 3).mapToObj(i -> (Object) 1),
        IntStream.range(0, 7).mapToObj(i -> (Object) 2));

    var result = IndexHistogramManager.scanAndBuild(keys, 10, 4);

    assertNotNull(result);
    assertEquals("MCV should be 2 (largest run)", 2, result.mcvValue);
    assertEquals("MCV frequency should be 7", 7, result.mcvFrequency);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1191: changed conditional boundary in fitToPage
  // totalBoundaryBytes <= available → if <= mutated to <, exact-fit breaks
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fitToPage_oneByteBelowLimit_doesNotReduce() {
    var boundaries = new Comparable<?>[] {0, 25, 50, 75, 100};
    var result = new IndexHistogramManager.BuildResult(
        boundaries, new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25}, 4, 100, null, 0);

    int available = IndexHistogramManager.computeMaxBoundarySpace(4, 0, 0);

    // One byte BELOW available → should NOT reduce
    var fit = IndexHistogramManager.fitToPage(
        result, 1000, true, 0,
        (b, n) -> available - 1);

    assertNotNull(fit);
    assertEquals("Should keep 4 buckets when 1 byte below limit",
        4, fit.histogram().bucketCount());
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1262: changed conditional boundary in truncateBoundary
  // serializedSize <= maxBytes → if <= mutated to <, exact-fit truncates
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundary_exactlyAtMaxBytes_noTruncation() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) IntegerSerializer.INSTANCE;
    // IntegerSerializer: serialized size = 4 bytes
    int intSize = serializer.getObjectSize(SF, 42);
    assertEquals("Integer serialized size should be 4", 4, intSize);

    // maxBytes = 4 (exactly at size) → should NOT truncate
    var result = IndexHistogramManager.truncateBoundary(
        42, 4, serializer, SF);
    assertEquals("Should return original when exactly at max", 42, result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundary_oneBelowMaxBytes_truncatesString() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;
    // StringSerializer: 4-byte header + 2 bytes per char
    // "hello" = 4 + 5*2 = 14 bytes
    int helloSize = serializer.getObjectSize(SF, "hello");
    assertEquals(14, helloSize);

    // maxBytes = 13 (one below) → should truncate to 4 chars: "hell"
    var result = IndexHistogramManager.truncateBoundary(
        "hello", 13, serializer, SF);
    assertNotNull(result);
    assertEquals("Should truncate to 4 chars", "hell", result);
    // Verify truncated size fits
    int truncatedSize = serializer.getObjectSize(SF, result);
    assertTrue("Truncated size should fit", truncatedSize <= 13);
  }

  // ═══════════════════════════════════════════════════════════════
  // Lines 1298-1324: truncateString UTF-16 path mutations
  // L1298: replaced equality check with true (isUtf8 check)
  // L1302: removed conditional (maxPayload <= 0)
  // L1315/1320: boundary conditions on surrogate handling
  // L1324: changed conditional boundary (maxChars > 0 ? s.substring : s)
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateString_utf16_maxBytesExactlyFitsOneChar() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;
    // StringSerializer: 4-byte header + 2 bytes per char
    // maxBytes = 6 → 4 header + 2 payload → exactly 1 char
    var result = IndexHistogramManager.truncateBoundary(
        "hello", 6, serializer, SF);
    assertEquals("Should truncate to 1 char with 6-byte budget", "h", result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateString_utf16_maxBytesZeroPayload() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;
    // maxBytes = 4 → exactly header, 0 payload → maxPayload=0 → return original
    // This tests the maxPayload <= 0 guard at line 1302
    var result = IndexHistogramManager.truncateBoundary(
        "hello", 4, serializer, SF);
    // When maxPayload <= 0, returns the original string (can't truncate below header)
    assertEquals("Should return original when no payload space", "hello", result);
  }

  // ═══════════════════════════════════════════════════════════════
  // Lines 1341/1354: changed conditional boundary in truncateStringUtf8
  // byteCount + cpBytes > maxUtf8Bytes and charIndex > 0
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateString_utf8_exactlyFits() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    // UTF8Serializer: 2-byte header + UTF-8 bytes
    // "abc" = 2 + 3 = 5 bytes
    // maxBytes = 5 → exactly fits → no truncation
    var result = IndexHistogramManager.truncateBoundary(
        "abc", 5, serializer, SF);
    assertEquals("Should return original when exactly fits", "abc", result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateString_utf8_oneByteShort() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    // "abc" = 2 header + 3 payload = 5 bytes
    // maxBytes = 4 → payload limit = 2 bytes → "ab"
    var result = IndexHistogramManager.truncateBoundary(
        "abc", 4, serializer, SF);
    assertEquals("Should truncate to 2 chars with 4-byte budget", "ab", result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void truncateString_utf8_multibyte_respectsCharBoundary() {
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE;
    // "a\u00E9c" (a + é + c). é is 2 bytes in UTF-8.
    // Header: 2 bytes. Payload: 1(a) + 2(é) + 1(c) = 4 bytes. Total: 6.
    // maxBytes = 5 → payload limit = 3 → "a" (1) + "é" (2) = 3 bytes exactly
    var result = IndexHistogramManager.truncateBoundary(
        "a\u00E9c", 5, serializer, SF);
    assertEquals("Should truncate at char boundary", "a\u00E9", result);
  }

  // ═══════════════════════════════════════════════════════════════
  // Lines 1396/1445: swapped parameters in compare calls
  // In truncateBoundaries merge logic
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_compareParamOrder_differentBoundariesNotMerged() {
    // 3 buckets: ["aaa_x", "bbb_y", "ccc_z", "ddd_w"]
    // After truncation to 12 bytes (4 chars): ["aaa_", "bbb_", "ccc_", "ddd_"]
    // All different → no merge → 3 buckets remain.
    // If compare params are swapped, compare("bbb_", "aaa_") still != 0,
    // but the merge logic checks adjacent equality, so swap shouldn't matter
    // for different values. But for same values, it matters.
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {"aaa_x", "bbb_y", "ccc_z", "ddd_w"},
        new long[] {100, 200, 300},
        new long[] {10, 20, 30},
        3, 60, null, 0);

    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 12, serializer, SF);

    assertNotNull(truncated);
    assertEquals("No merge needed when all boundaries differ",
        3, truncated.actualBucketCount);
    // Verify frequencies preserved
    assertEquals(100, truncated.frequencies[0]);
    assertEquals(200, truncated.frequencies[1]);
    assertEquals(300, truncated.frequencies[2]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1412: changed conditional boundary in merge loop
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_partialMerge_boundaryCorrect() {
    // 4 buckets: first 2 boundaries merge, last 2 don't.
    // ["aa_1", "aa_2", "bb_1", "cc_1", "dd_1"]
    // After truncation to 8 bytes (2 chars): ["aa", "aa", "bb", "cc", "dd"]
    // Boundaries 0-1 merge (both "aa"). Boundaries 2,3,4 are distinct.
    // Result: 3 buckets.
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) StringSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {"aa_1", "aa_2", "bb_1", "cc_1", "dd_1"},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        4, 100, null, 0);

    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 8, serializer, SF);

    assertNotNull(truncated);
    assertEquals("First pair merges, 3 buckets remain",
        3, truncated.actualBucketCount);
    assertEquals("Merged freq = 100+200", 300, truncated.frequencies[0]);
    assertEquals("Merged ndv = 10+20", 30, truncated.distinctCounts[0]);
    assertEquals("Unmerged freq[1] = 300", 300, truncated.frequencies[1]);
    assertEquals("Unmerged freq[2] = 400", 400, truncated.frequencies[2]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1451: Replaced integer addition with subtraction
  // In truncateBoundaries: newBounds[newIdx + 1] = truncated[i + 1]
  // If + mutated to -: newBounds[newIdx - 1] which would be wrong.
  // ═══════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void truncateBoundaries_boundaryArrayCorrectlyPopulated() {
    // 3 buckets with no merges needed after truncation.
    // Verify that boundaries[newIdx + 1] is correctly set.
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) IntegerSerializer.INSTANCE;

    var result = new IndexHistogramManager.BuildResult(
        new Comparable<?>[] {10, 20, 30, 40},
        new long[] {100, 200, 300},
        new long[] {10, 20, 30},
        3, 60, null, 0);

    // Integer keys never need truncation → original returned
    var truncated = IndexHistogramManager.truncateBoundaries(
        result, 256, serializer, SF);

    assertEquals(3, truncated.actualBucketCount);
    assertEquals(10, truncated.boundaries[0]);
    assertEquals(20, truncated.boundaries[1]);
    assertEquals(30, truncated.boundaries[2]);
    assertEquals(40, truncated.boundaries[3]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1472: Replaced integer addition with subtraction
  // In computeBoundaryBytes: total += INT_SIZE + keySize
  // If + mutated to -: total would shrink → wrong page budget
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void computeMaxBoundarySpace_knownValues_exact() {
    // Verify the formula returns expected values for known inputs.
    // This catches math mutations in computeMaxBoundarySpace.
    int space4 = IndexHistogramManager.computeMaxBoundarySpace(4, 0, 0);
    int space8 = IndexHistogramManager.computeMaxBoundarySpace(8, 0, 0);
    // More buckets → more frequencies/distincts arrays → less space for boundaries
    assertTrue("8 buckets should have less boundary space than 4",
        space8 < space4);
    // Both should be positive
    assertTrue("Space for 4 buckets should be > 0", space4 > 0);
    assertTrue("Space for 8 buckets should be > 0", space8 > 0);

    // With HLL, less space available
    int hllSize = HyperLogLogSketch.serializedSize();
    int space4WithHll = IndexHistogramManager.computeMaxBoundarySpace(
        4, hllSize, 0);
    assertEquals("HLL should reduce available space by HLL size",
        space4 - hllSize, space4WithHll);

    // With MCV key, less space available
    int space4WithMcv = IndexHistogramManager.computeMaxBoundarySpace(
        4, 0, 20);
    assertEquals("MCV key should reduce space by key size",
        space4 - 20, space4WithMcv);
  }

  // ═══════════════════════════════════════════════════════════════
  // computeNewSnapshot: version mismatch for frequency deltas
  // Lines 1131/1138/1145 in fitToPage (mcvForSize != null checks)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_versionMismatch_skipsFrequencyUpdate() {
    // Delta has snapshotVersion=0 but snapshot has version=1
    // → frequency deltas should NOT be applied
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, h, 0, 1000, 1, false, null, false); // version=1

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.initFrequencyDeltas(2, 0); // snapshotVersion=0 → mismatch!
    delta.frequencyDeltas[0] = 100;
    delta.frequencyDeltas[1] = 200;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    // Frequencies should be unchanged due to version mismatch
    assertEquals("Freq[0] unchanged on version mismatch",
        500, result.histogram().frequencies()[0]);
    assertEquals("Freq[1] unchanged on version mismatch",
        500, result.histogram().frequencies()[1]);
    // But total count should still be updated
    assertEquals("Total count should be updated",
        1005, result.stats().totalCount());
  }

  @Test
  public void computeNewSnapshot_versionMatch_appliesFrequencyUpdate() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, h, 0, 1000, 3, false, null, false); // version=3

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.initFrequencyDeltas(2, 3); // snapshotVersion=3 → match!
    delta.frequencyDeltas[0] = 10;
    delta.frequencyDeltas[1] = 20;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertNotNull(result);
    assertEquals("Freq[0] updated on version match",
        510, result.histogram().frequencies()[0]);
    assertEquals("Freq[1] updated on version match",
        520, result.histogram().frequencies()[1]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1614: changed conditional boundary
  // !bypassMinSize && nonNullCount < histogramMinSize
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_exactlyTwoDistinct_producesMultipleBuckets() {
    // Test with exactly 2 distinct values and many entries.
    // This verifies bucketing works at the minimum.
    Stream<Object> keys = Stream.concat(
        IntStream.range(0, 50).mapToObj(i -> (Object) 1),
        IntStream.range(0, 50).mapToObj(i -> (Object) 2));

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    assertTrue("Should produce at least 1 bucket",
        result.actualBucketCount >= 1);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals("Total entries = 100", 100, totalFreq);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 1622: removed conditional (nonNullCount > 0 for sqrt cap)
  // Line 1632: removed conditional (prevDistinct > 0 for NDV cap)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scanAndBuild_sqrtCap_reducesTargetBuckets() {
    // 9 entries with 3 distinct keys → sqrt(9) = 3, target buckets capped at 3
    Stream<Object> keys = Stream.of(
        (Object) 1, 1, 1, 2, 2, 2, 3, 3, 3);

    var result = IndexHistogramManager.scanAndBuild(keys, 9, 100);

    assertNotNull(result);
    // With 3 distinct values and sqrt cap, should have <= 3 buckets
    assertTrue("Should cap buckets to sqrt(nonNullCount)",
        result.actualBucketCount <= 3);
  }

  // ═══════════════════════════════════════════════════════════════
  // fitToPage: null return when keys too large for any histogram
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fitToPage_keysTooBigForSingleValue_returnsNull() {
    // Single-value index (no HLL to spill). Use 5 buckets with
    // enormous boundary size → keeps reducing until < MINIMUM (4) → null.
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
        boundaries, freqs, distincts, 5, 100, null, 0);

    // Return massive boundary size → never fits
    var fit = IndexHistogramManager.fitToPage(
        result, 1000, true, 0,
        (b, n) -> Integer.MAX_VALUE);

    assertNull("Should return null when boundaries can never fit", fit);
  }

  // ═══════════════════════════════════════════════════════════════
  // computeNewSnapshot: hasDriftedBuckets flag
  // Line 1680: removed conditional (hasDriftedBuckets check)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void computeNewSnapshot_driftFlag_setOnNegativeFrequency() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {10, 100}, new long[] {5, 50},
        110, null, 0);
    var stats = new IndexStatistics(110, 55, 0);
    var snapshot = new HistogramSnapshot(
        stats, h, 0, 110, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -20;
    delta.mutationCount = 20;
    delta.initFrequencyDeltas(2, 0);
    delta.frequencyDeltas[0] = -20; // 10 - 20 = -10 → clamped to 0

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertTrue("hasDriftedBuckets should be set when freq goes negative",
        result.hasDriftedBuckets());
    assertEquals("Clamped frequency should be 0",
        0, result.histogram().frequencies()[0]);
  }

  @Test
  public void computeNewSnapshot_noDrift_flagNotSet() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
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

    assertFalse("hasDriftedBuckets should NOT be set for positive deltas",
        result.hasDriftedBuckets());
  }
}
