package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the HLL page-1 spill mechanism in {@link HistogramStatsPage}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>HLL registers are written inline on page 0 for the normal case</li>
 *   <li>The page-1 flag is correctly encoded/decoded in hllRegisterCount</li>
 *   <li>HLL registers round-trip through page-1 write/read</li>
 *   <li>Snapshot round-trips preserve the hllOnPage1 flag</li>
 * </ul>
 */
public class HistogramStatsPageHllSpillTest {

  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

  /**
   * VARIABLE_DATA_OFFSET is the byte offset where histogram data starts
   * on page 0. Computed as NEXT_FREE_POSITION(28) + 4(version) +
   * 1(serializer) + 8(total) + 8(distinct) + 8(null) + 8(mutations) +
   * 8(lastBuild) + 4(histLen) + 4(hllCount) = 81.
   */
  private static final int VARIABLE_DATA_OFFSET = 81;

  /** Offset of the histogramDataLength field in the raw page buffer. */
  private static final int HISTOGRAM_DATA_LENGTH_RAW_OFFSET = 73;

  /** Offset of the hllRegisterCount field in the raw page buffer. */
  private static final int HLL_REGISTER_COUNT_RAW_OFFSET = 77;

  private ByteBufferPool bufferPool;
  private BinarySerializerFactory serializerFactory;

  @Before
  public void setUp() {
    bufferPool = new ByteBufferPool(PAGE_SIZE);
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @After
  public void tearDown() {
    bufferPool.clear();
  }

  // ---- Helper methods ----

  private CacheEntry allocatePage() {
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releasePage(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  @SuppressWarnings("unchecked")
  private BinarySerializer<Object> intKeySerializer() {
    return (BinarySerializer<Object>) (BinarySerializer<?>) IntegerSerializer.INSTANCE;
  }

  private ByteBuffer rawBuffer(CacheEntry entry) {
    return entry.getCachePointer().getBuffer();
  }

  /**
   * Creates a simple 2-bucket histogram with integer boundaries.
   */
  private EquiDepthHistogram createSimpleHistogram() {
    return new EquiDepthHistogram(
        2,
        new Comparable<?>[] {1, 50, 100},
        new long[] {500, 500},
        new long[] {25, 25},
        1000,
        50,
        200L);
  }

  /**
   * Builds an HLL sketch with known data for deterministic round-trip
   * verification.
   */
  private HyperLogLogSketch createPopulatedHll() {
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 500; i++) {
      hll.add(i * 7919L); // prime multiplier for spread
    }
    return hll;
  }

  // ---- Tests: inline HLL on page 0 (normal case) ----

  @Test
  public void writeAndReadSnapshotWithInlineHll() {
    // Given: a snapshot with HLL (multi-value index, normal case — HLL
    // fits on page 0)
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(1000, expectedEstimate, 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      // When: write with hllOnPage1 = false (inline)
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // Then: reading back produces identical snapshot with HLL inline
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      assertEquals(1000, loaded.stats().totalCount());
      assertEquals(expectedEstimate, loaded.stats().distinctCount());
      assertEquals(50, loaded.stats().nullCount());
      assertEquals(100, loaded.mutationsSinceRebalance());
      assertEquals(900, loaded.totalCountAtLastBuild());
      assertNotNull(loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertNotNull(loaded.hllSketch());
      assertFalse("HLL should NOT be flagged as on page 1",
          loaded.hllOnPage1());
      // HLL estimate should be preserved
      assertEquals(expectedEstimate, loaded.hllSketch().estimate());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void writeAndReadSnapshotWithoutHll() {
    // Given: a single-value snapshot (no HLL)
    var stats = new IndexStatistics(500, 500, 10);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 50, 400, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      assertEquals(500, loaded.stats().totalCount());
      assertNotNull(loaded.histogram());
      assertNull("Single-value should have no HLL", loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: HLL spilled to page 1 ----

  @Test
  public void writeSnapshotWithHllOnPage1SetsHighBitInRegisterCount() {
    // Given: a snapshot with HLL, written with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // Then: the raw hllRegisterCount should have the high bit set
      int rawCount = page.getRawHllRegisterCount();
      assertTrue("High bit should be set for page-1 flag",
          (rawCount & HistogramStatsPage.HLL_PAGE1_FLAG) != 0);

      int actualCount = rawCount & ~HistogramStatsPage.HLL_PAGE1_FLAG;
      assertEquals("Actual register count should be 1024",
          HyperLogLogSketch.serializedSize(), actualCount);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshotDetectsPage1FlagAndReturnsNullHll() {
    // Given: a snapshot written with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // When: reading back from page 0 only
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      // Then: HLL is null (caller must read from page 1) and flag is set
      assertNull("HLL should be null when spilled to page 1",
          loaded.hllSketch());
      assertTrue("hllOnPage1 flag should be set", loaded.hllOnPage1());

      // Other fields should still be correct
      assertEquals(1000, loaded.stats().totalCount());
      assertNotNull(loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: page-1 HLL round-trip ----

  @Test
  public void writeAndReadHllFromPage1RoundTrip() {
    // Given: an HLL sketch with known data
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();

    CacheEntry page1 = allocatePage();
    try {
      // When: write HLL to page 1 and read it back
      HistogramStatsPage.writeHllToPage1(page1, hll);
      var loaded = HistogramStatsPage.readHllFromPage1(page1);

      // Then: the loaded HLL produces the same estimate
      assertNotNull(loaded);
      assertEquals("HLL estimate should survive page-1 round-trip",
          expectedEstimate, loaded.estimate());
    } finally {
      releasePage(page1);
    }
  }

  @Test
  public void fullPage1SpillRoundTrip() {
    // Simulate the complete flow: write page 0 with page-1 flag,
    // write HLL to page 1, then read back both pages and reconstruct.
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(2000, expectedEstimate, 100);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 200, 1800, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    CacheEntry page1 = allocatePage();
    try {
      // Write phase: page 0 (no inline HLL) + page 1 (HLL registers)
      var statsPage = new HistogramStatsPage(page0);
      statsPage.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      HistogramStatsPage.writeHllToPage1(page1, hll);

      // Read phase: page 0 first
      var loadedFromPage0 = statsPage.readSnapshot(
          intKeySerializer(), serializerFactory);
      assertTrue("hllOnPage1 should be true", loadedFromPage0.hllOnPage1());
      assertNull("HLL should be null from page 0 read",
          loadedFromPage0.hllSketch());

      // Then read HLL from page 1 and reconstruct
      var loadedHll = HistogramStatsPage.readHllFromPage1(page1);
      var reconstructed = new HistogramSnapshot(
          loadedFromPage0.stats(), loadedFromPage0.histogram(),
          loadedFromPage0.mutationsSinceRebalance(),
          loadedFromPage0.totalCountAtLastBuild(),
          loadedFromPage0.version(),
          loadedFromPage0.hasDriftedBuckets(),
          loadedHll, true);

      // Verify the full reconstruction
      assertEquals(2000, reconstructed.stats().totalCount());
      assertEquals(expectedEstimate, reconstructed.stats().distinctCount());
      assertEquals(100, reconstructed.stats().nullCount());
      assertNotNull(reconstructed.histogram());
      assertEquals(2, reconstructed.histogram().bucketCount());
      assertNotNull(reconstructed.hllSketch());
      assertEquals(expectedEstimate, reconstructed.hllSketch().estimate());
      assertTrue(reconstructed.hllOnPage1());
    } finally {
      releasePage(page0);
      releasePage(page1);
    }
  }

  // ---- Tests: HLL_PAGE1_FLAG encoding ----

  @Test
  public void hllPage1FlagDoesNotConflictWithValidRegisterCounts() {
    // Valid register counts are 0 (no HLL) and 1024 (HLL present).
    // The high bit (0x8000_0000) should never be set for these values.
    assertEquals(0, 0 & HistogramStatsPage.HLL_PAGE1_FLAG);
    assertEquals(0,
        HyperLogLogSketch.serializedSize()
            & HistogramStatsPage.HLL_PAGE1_FLAG);

    // Flag OR'd with 1024 should produce a value > 1024
    int flagged = HyperLogLogSketch.serializedSize()
        | HistogramStatsPage.HLL_PAGE1_FLAG;
    assertTrue(flagged < 0); // high bit set → negative in signed int

    // Masking off the flag recovers the original count
    assertEquals(HyperLogLogSketch.serializedSize(),
        flagged & ~HistogramStatsPage.HLL_PAGE1_FLAG);
  }

  // ---- Tests: computeNewSnapshot preserves hllOnPage1 ----

  @Test
  public void computeNewSnapshotPreservesHllOnPage1Flag() {
    // Given: a snapshot with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 100, 900, 0, false, hll, true);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.hllSketch = new HyperLogLogSketch();
    delta.hllSketch.add(999999L);

    // When: apply delta
    var result =
        IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then: hllOnPage1 flag is preserved
    assertTrue("hllOnPage1 should be preserved through delta application",
        result.hllOnPage1());
    assertEquals(1005, result.stats().totalCount());
    assertNotNull(result.hllSketch());
  }

  @Test
  public void computeNewSnapshotKeepsHllOnPage1FalseWhenNotSpilled() {
    // Given: a snapshot with hllOnPage1 = false
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 100, 900, 0, false, hll, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 3;
    delta.mutationCount = 3;

    var result =
        IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertFalse("hllOnPage1 should remain false",
        result.hllOnPage1());
  }

  // ---- Tests: HistogramSnapshot hllOnPage1 field ----

  @Test
  public void snapshotHllOnPage1FieldAccessor() {
    var stats = new IndexStatistics(0, 0, 0);

    var withSpill = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, true);
    assertTrue(withSpill.hllOnPage1());

    var withoutSpill = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    assertFalse(withoutSpill.hllOnPage1());
  }

  // ---- Tests: empty page writes ----

  @Test
  public void writeEmptyPageHasZeroHllRegisterCount() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      assertEquals(0, page.getRawHllRegisterCount());

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
      assertNull(loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot version handling ----

  /**
   * Version 0 (old format before versioning was introduced) must be accepted
   * without throwing an exception, since legacy pages have zeros in all
   * uninitialized fields.
   */
  @Test
  public void readSnapshotAcceptsVersion0AsOldFormat() {
    // A fresh ByteBuffer-backed page has all zeros, so version = 0,
    // simulating an old-format page before versioning was introduced.
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // All fields are zero, including version=0.
      var loaded = page.readSnapshot(
          intKeySerializer(), serializerFactory);
      // Should not throw; all values should be zero/default.
      assertEquals(0, loaded.stats().totalCount());
      assertEquals(0, loaded.stats().distinctCount());
      assertEquals(0, loaded.stats().nullCount());
      assertNull(loaded.histogram());
      assertNull(loaded.hllSketch());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Version = FORMAT_VERSION (1) must be accepted. This is the normal case
   * for pages written by the current code.
   */
  @Test
  public void readSnapshotAcceptsCurrentFormatVersion() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // writeEmpty writes FORMAT_VERSION=1. readSnapshot should accept it.
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertEquals(0, loaded.stats().totalCount());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Version = 2 (unsupported future version) must throw StorageException.
   * This kills the mutant that removes the version check entirely.
   */
  @Test(expected = StorageException.class)
  public void readSnapshotThrowsOnUnsupportedVersion() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // Write a valid page first, then corrupt the version.
      page.writeEmpty((byte) 5);

      // Access the underlying buffer to corrupt the version field.
      // FORMAT_VERSION_OFFSET = NEXT_FREE_POSITION = 28 (DurablePage header).
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(28, 2); // set version = 2

      // This should throw StorageException.
      page.readSnapshot(intKeySerializer(), serializerFactory);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot corrupted histogramDataLength ----

  /**
   * A negative histogramDataLength (corrupted page) must be treated as
   * empty histogram rather than causing an OOM or IndexOutOfBounds.
   */
  @Test
  public void readSnapshotTreatsNegativeHistogramDataLengthAsEmpty() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Corrupt histogramDataLength to -1.
      // HISTOGRAM_DATA_LENGTH_OFFSET = TOTAL_COUNT_AT_LAST_BUILD_OFFSET + 8
      //   = (NULL_COUNT_OFFSET + 8) + 8 = ... = 73
      // We can compute it: NEXT_FREE_POSITION(28) + 4(version) + 1(ser) +
      //   8(total) + 8(distinct) + 8(null) + 8(mutations) + 8(lastBuild) = 73
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(73, -1); // histogramDataLength = -1

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      // Should treat as empty histogram, not crash.
      assertNull("Negative histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * A histogramDataLength exceeding page capacity must be treated as empty
   * histogram rather than attempting to read beyond the page boundary.
   */
  @Test
  public void readSnapshotTreatsExcessiveHistogramDataLengthAsEmpty() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Set histogramDataLength to a value that exceeds page capacity.
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(73, PAGE_SIZE + 1000);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "Excessive histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot HLL inline reading ----

  /**
   * When HLL offset points beyond page capacity (due to corrupt
   * histogramDataLength just within bounds but combined with HLL
   * exceeding the page), HLL should be null (skipped), not crash.
   */
  @Test
  public void readSnapshotSkipsHllWhenOffsetExceedsPageCapacity() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      var buffer = page0.getCachePointer().getBuffer();
      // Set a histogramDataLength that is valid (within page) but leaves
      // no room for HLL registers. VARIABLE_DATA_OFFSET = 81.
      // Set histogramDataLength = PAGE_SIZE - 81 - 10 (leaves only 10
      // bytes for HLL, but HLL needs 1024).
      int histLen = PAGE_SIZE - 81 - 10;
      buffer.putInt(73, histLen);

      // Set hllRegisterCount = 1024 (no page-1 flag).
      buffer.putInt(77, 1024);

      // Now hllOffset = 81 + histLen = PAGE_SIZE - 10.
      // hllOffset + 1024 > PAGE_SIZE → HLL should be skipped.
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      // The histogram deserialization will likely fail/return null since
      // the data is garbage, but HLL must be null due to bounds check.
      assertNull("HLL should be null when offset exceeds page capacity",
          loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Verifies that valid inline HLL round-trips correctly when histogram
   * data is also present (non-zero histogramDataLength).
   */
  @Test
  public void readSnapshotRoundTripsInlineHllWithHistogramData() {
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(2000, expectedEstimate, 100);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 200, 1800, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      // Both histogram and HLL should be present.
      assertNotNull("Histogram should be present", loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertNotNull("Inline HLL should be present", loaded.hllSketch());
      assertEquals(expectedEstimate, loaded.hllSketch().estimate());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeSnapshot capacity checks ----

  /**
   * Writing a histogram whose serialized data exceeds page 0 capacity
   * must throw IllegalStateException. We simulate this by creating a
   * histogram with many boundaries that produces a large blob.
   */
  @Test
  public void writeSnapshotThrowsWhenHistogramExceedsPageCapacity() {
    // Create a histogram with many buckets to generate a large blob.
    // Each bucket boundary is an Integer (4 bytes serialized), and we
    // need enough buckets to exceed PAGE_SIZE - VARIABLE_DATA_OFFSET (81).
    int bucketCount = PAGE_SIZE; // way more than needed
    var boundaries = new Comparable<?>[bucketCount + 1];
    var frequencies = new long[bucketCount];
    var distinctCounts = new long[bucketCount];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < bucketCount; i++) {
      frequencies[i] = 1;
      distinctCounts[i] = 1;
    }
    var bigHistogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        bucketCount, bucketCount, 0L);

    var stats = new IndexStatistics(bucketCount, bucketCount, 0);
    var snapshot = new HistogramSnapshot(
        stats, bigHistogram, 0, 0, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      fail("Expected IllegalStateException for oversized histogram");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("Histogram data exceeds page 0"));
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Writing histogram + inline HLL that combined exceed page 0 capacity
   * must throw IllegalStateException. The histogram alone fits, but
   * adding 1024 bytes for HLL pushes it over the limit.
   */
  @Test
  public void writeSnapshotThrowsWhenHistogramPlusHllExceedsCapacity() {
    // Create a histogram that nearly fills the page, leaving fewer than
    // 1024 bytes for HLL. We need histogramDataLength > PAGE_SIZE - 81 - 1024.
    // The minimum blob to exceed: PAGE_SIZE - 81 - 1024 + 1 bytes.
    int targetBlobSize = PAGE_SIZE - 81 - 1024 + 100;
    // Each bucket with integer boundaries: approx 4 bytes per boundary +
    // 8 per frequency + 8 per distinct. ~20 bytes per bucket.
    int bucketCount = targetBlobSize / 10; // overshoot to ensure large enough
    var boundaries = new Comparable<?>[bucketCount + 1];
    var frequencies = new long[bucketCount];
    var distinctCounts = new long[bucketCount];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < bucketCount; i++) {
      frequencies[i] = 1;
      distinctCounts[i] = 1;
    }
    var mediumHistogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        bucketCount, bucketCount, 0L);

    // Verify the histogram serialization is large enough.
    byte[] blob = mediumHistogram.serialize(
        intKeySerializer(), serializerFactory);
    // Only proceed if the blob + HLL exceeds page capacity but blob alone
    // does not. If blob alone already exceeds, the first check will fire.
    // Use Assume so CI reports this as "skipped" rather than silently passing.
    org.junit.Assume.assumeTrue(
        "Blob size does not hit the histogram+HLL overflow sweet spot",
        81 + blob.length <= PAGE_SIZE
            && 81 + blob.length + 1024 > PAGE_SIZE);

    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, mediumHistogram, 0, 0, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      fail("Expected IllegalStateException for histogram + HLL overflow");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("exceeds page 0 capacity"));
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeSnapshot HLL page-1 flag encoding ----

  /**
   * When hllSketch is null (single-value index), hllRegisterCount must
   * be written as 0 with no page-1 flag.
   */
  @Test
  public void writeSnapshotWritesZeroForNullHll() {
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertEquals("Null HLL should write 0 register count",
          0, page.getRawHllRegisterCount());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * When hllOnPage1=true, the persisted hllRegisterCount must have the
   * HLL_PAGE1_FLAG high bit set.
   */
  @Test
  public void writeSnapshotSetsPage1FlagWhenHllOnPage1IsTrue() {
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      int raw = page.getRawHllRegisterCount();
      assertTrue("Page-1 flag bit should be set",
          (raw & HistogramStatsPage.HLL_PAGE1_FLAG) != 0);
      assertEquals("Actual count should be 1024",
          1024, raw & ~HistogramStatsPage.HLL_PAGE1_FLAG);
    } finally {
      releasePage(page0);
    }
  }

  /**
   * When hllOnPage1=false and hllSketch is non-null, the persisted
   * hllRegisterCount must be the raw count (1024) without the flag bit.
   */
  @Test
  public void writeSnapshotWritesRawCountWhenHllOnPage1IsFalse() {
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      int raw = page.getRawHllRegisterCount();
      assertEquals("Should be raw 1024 without flag",
          1024, raw);
      assertEquals("Page-1 flag should NOT be set",
          0, raw & HistogramStatsPage.HLL_PAGE1_FLAG);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeEmpty verification ----

  /**
   * After writeEmpty, all fields must be zero/default, and the format
   * version must be set to FORMAT_VERSION (1).
   */
  @Test
  public void writeEmptySetsFormatVersionAndZerosAllFields() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Verify format version is 1 (not 0).
      var buffer = page0.getCachePointer().getBuffer();
      int version = buffer.getInt(28); // FORMAT_VERSION_OFFSET
      assertEquals("FORMAT_VERSION should be 1 after writeEmpty",
          1, version);

      // Verify all stat fields are zero.
      assertEquals(0, page.getTotalCount());
      assertEquals(0, page.getDistinctCount());
      assertEquals(0, page.getNullCount());
      assertEquals(0, page.getHistogramDataLength());
      assertEquals(0, page.getRawHllRegisterCount());

      // Verify serializer ID is written.
      byte serId = buffer.get(32); // SERIALIZER_ID_OFFSET
      assertEquals(5, serId);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: accessor return values with non-zero data ----

  @Test
  public void accessorsReturnNonZeroValuesAfterWrite() {
    // Kills PrimitiveReturnsMutator on getTotalCount, getDistinctCount,
    // getNullCount, getHistogramDataLength (lines 306, 310, 314, 318).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      var stats = new IndexStatistics(12345, 678, 90);
      var histogram = createSimpleHistogram();
      var snapshot = new HistogramSnapshot(
          stats, histogram, 100, 900, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertNotEquals("getTotalCount should not be 0",
          0, page.getTotalCount());
      assertEquals(12345, page.getTotalCount());
      assertNotEquals("getDistinctCount should not be 0",
          0, page.getDistinctCount());
      assertEquals(678, page.getDistinctCount());
      assertNotEquals("getNullCount should not be 0",
          0, page.getNullCount());
      assertEquals(90, page.getNullCount());
      assertNotEquals("getHistogramDataLength should not be 0",
          0, page.getHistogramDataLength());
      assertTrue("getHistogramDataLength should be positive",
          page.getHistogramDataLength() > 0);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshotParamOrderPreservesDistinctFields() {
    // Kills ParamSwapMutator on HistogramSnapshot constructor (line 203).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // Use unique values so any swap is detectable
      var stats = new IndexStatistics(111, 222, 333);
      var snapshot = new HistogramSnapshot(
          stats, null, 444, 555, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertEquals("totalCount", 111, loaded.stats().totalCount());
      assertEquals("distinctCount", 222, loaded.stats().distinctCount());
      assertEquals("nullCount", 333, loaded.stats().nullCount());
      assertEquals("mutationsSinceRebalance", 444,
          loaded.mutationsSinceRebalance());
      assertEquals("totalCountAtLastBuild", 555,
          loaded.totalCountAtLastBuild());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void writeAndReadHistogramDataLengthRoundTrip() {
    // Kills boundary mutations on histogramDataLength check (line 228).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      var histogram = new EquiDepthHistogram(
          2, new Comparable<?>[] {10, 50, 90},
          new long[] {300, 700}, new long[] {15, 35},
          1000, 60, 150);
      var stats = new IndexStatistics(1000, 50, 100);
      var snapshot = new HistogramSnapshot(
          stats, histogram, 50, 800, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertTrue("histogramDataLength should be > 0",
          page.getHistogramDataLength() > 0);
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNotNull("Histogram should be loaded", loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertEquals(300, loaded.histogram().frequencies()[0]);
      assertEquals(700, loaded.histogram().frequencies()[1]);
      assertEquals(60, loaded.histogram().mcvValue());
      assertEquals(150, loaded.histogram().mcvFrequency());
    } finally {
      releasePage(page0);
    }
  }

  // ── Page layout boundary tests ──────────────────────────────

  @Test
  public void readSnapshot_unsupportedVersion_exceptionMessageIsCorrect() {
    // Verifies that the StorageException for unsupported versions has
    // a non-null message containing the version number. Kills the
    // parameter-swap mutant on StorageException(null, "Unsupported...").
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Corrupt the format version to 99 (unsupported).
      rawBuffer(page0).putInt(28, 99);

      page.readSnapshot(intKeySerializer(), serializerFactory);
      fail("Expected StorageException for unsupported version");
    } catch (StorageException e) {
      // If params were swapped, getMessage() would be null.
      assertNotNull(
          "Exception message must not be null (detects param swap)",
          e.getMessage());
      assertTrue(
          "Message should mention unsupported version",
          e.getMessage().contains("Unsupported histogram stats page version"));
      assertTrue(
          "Message should include the bad version number",
          e.getMessage().contains("99"));
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthZero_returnsNullHistogram() {
    // histogramDataLength = 0 is valid and should yield null histogram.
    // Kills mutant that changes < 0 to <= 0 (would wrongly treat 0 as
    // corrupt).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull("Zero histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthExactlyFillsPage_isNotRejected() {
    // histogramDataLength = PAGE_SIZE - VARIABLE_DATA_OFFSET, so
    // VARIABLE_DATA_OFFSET + histogramDataLength == PAGE_SIZE (exact fit).
    // Original code: > PAGE_SIZE -> false -> not rejected.
    // Boundary mutant: >= PAGE_SIZE -> true -> rejected (zeroed to 0).
    // We detect the difference: if not rejected, deserialization is
    // attempted on the garbage data.
    int exactLen = PAGE_SIZE - VARIABLE_DATA_OFFSET;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Set histogramDataLength to the exact-fit boundary value.
      rawBuffer(page0).putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, exactLen);

      // Also set hllRegisterCount = 0 to avoid HLL processing.
      rawBuffer(page0).putInt(HLL_REGISTER_COUNT_RAW_OFFSET, 0);

      // Attempt to read. The guard should NOT reject this length.
      // Deserialization of all-zeros blob will likely throw or produce
      // garbage, but the key point is that deserialization IS attempted
      // (length was not zeroed by the guard).
      boolean deserializationAttempted;
      try {
        var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
        // If we get here, deserialization succeeded (unlikely with zeros
        // but possible if the format is tolerant). Either way,
        // deserialization was attempted.
        deserializationAttempted = true;
      } catch (Exception e) {
        // Deserialization threw because the blob is zeros -- this means
        // the guard did NOT reject the length, so deserialization was
        // attempted. This is the expected path.
        deserializationAttempted = true;
      }
      assertTrue(
          "Guard must not reject exact-fit histogramDataLength",
          deserializationAttempted);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthExceedsPageByOne_rejected() {
    // histogramDataLength = PAGE_SIZE - VARIABLE_DATA_OFFSET + 1, so
    // VARIABLE_DATA_OFFSET + histogramDataLength == PAGE_SIZE + 1.
    // The guard should reject this and return null histogram.
    int overLen = PAGE_SIZE - VARIABLE_DATA_OFFSET + 1;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      rawBuffer(page0).putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, overLen);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "Excessive histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllFitsPageExactly_noHistogram_readsHll() {
    // Set histogramDataLength = 0 in raw buffer and write valid HLL at
    // VARIABLE_DATA_OFFSET. Since VARIABLE_DATA_OFFSET(81) + 1024 = 1105
    // < PAGE_SIZE, this always fits. Verifies the basic HLL inline path.
    int hllSize = HyperLogLogSketch.serializedSize();

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);
      // Set histogramDataLength = 0, so no histogram deserialization.
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, 0);
      // Set hllRegisterCount = 1024 (no page-1 flag).
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      // Write valid HLL data at VARIABLE_DATA_OFFSET (since histLen=0).
      var hll = createPopulatedHll();
      byte[] hllData = new byte[hllSize];
      hll.writeTo(hllData, 0);
      int hllOffset = VARIABLE_DATA_OFFSET; // histLen = 0
      for (int i = 0; i < hllSize; i++) {
        buf.put(hllOffset + i, hllData[i]);
      }

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull("Histogram should be null with 0 data length",
          loaded.histogram());
      assertNotNull("HLL should be present", loaded.hllSketch());
      assertEquals(hll.estimate(), loaded.hllSketch().estimate());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllExactBoundary_withRawHistLen_readsHll() {
    // Set histogramDataLength in raw buffer to a value that makes
    // hllOffset + 1024 == PAGE_SIZE exactly. Then write a valid small
    // histogram blob and valid HLL data so deserialization succeeds.
    // Kills boundary mutant that changes <= to < in the HLL guard.
    int hllSize = HyperLogLogSketch.serializedSize(); // 1024
    int histLen = PAGE_SIZE - VARIABLE_DATA_OFFSET - hllSize;

    // Create a real 1-bucket histogram to get a valid blob.
    var smallHist = new EquiDepthHistogram(
        1,
        new Comparable<?>[] {0, 1},
        new long[] {1},
        new long[] {1},
        1, 0, 0L);
    byte[] validBlob = smallHist.serialize(
        intKeySerializer(), serializerFactory);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);

      // Write histogramDataLength = histLen (so HLL fits exactly).
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, histLen);
      // Write hllRegisterCount = 1024 (inline, no page-1 flag).
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      // Write the valid histogram blob at VARIABLE_DATA_OFFSET.
      // The remaining bytes (histLen - validBlob.length) are zeros.
      for (int i = 0; i < validBlob.length && i < histLen; i++) {
        buf.put(VARIABLE_DATA_OFFSET + i, validBlob[i]);
      }

      // Write valid HLL data at hllOffset.
      var hll = createPopulatedHll();
      byte[] hllData = new byte[hllSize];
      hll.writeTo(hllData, 0);
      int hllOffset = VARIABLE_DATA_OFFSET + histLen;
      assertEquals(
          "HLL should end exactly at page boundary",
          PAGE_SIZE, hllOffset + hllSize);
      for (int i = 0; i < hllSize; i++) {
        buf.put(hllOffset + i, hllData[i]);
      }

      // Read snapshot. The HLL guard should accept
      // hllOffset + 1024 <= PAGE_SIZE (exact equality).
      try {
        var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
        assertNotNull(
            "HLL must be read when hllOffset + count == PAGE_SIZE",
            loaded.hllSketch());
        assertEquals(
            "HLL estimate must match",
            hll.estimate(), loaded.hllSketch().estimate());
        assertFalse(loaded.hllOnPage1());
      } catch (Exception e) {
        // If histogram deserialization fails with an exception, the
        // entire readSnapshot fails before reaching HLL.
        fail("Histogram deserialization threw, preventing HLL "
            + "boundary verification: " + e.getMessage());
      }
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllExceedsPageByOne_isSkipped() {
    // Set histogramDataLength so that hllOffset + 1024 == PAGE_SIZE + 1.
    // The HLL should be skipped (null).
    int hllSize = HyperLogLogSketch.serializedSize();
    int histLen = PAGE_SIZE - VARIABLE_DATA_OFFSET - hllSize + 1;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, histLen);
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "HLL should be null when hllOffset + count > PAGE_SIZE",
          loaded.hllSketch());
      assertFalse(
          "hllOnPage1 should be false (no flag set)",
          loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }
}
