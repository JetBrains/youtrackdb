package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
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
  private com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<Object>
      intKeySerializer() {
    return (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
            ?>) IntegerSerializer.INSTANCE;
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
}
