package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link CollectionDirtyPageBitSet} covering lifecycle (create, open, close,
 * delete), single-bit set/clear, nextSetBit scanning, multi-page growth, and edge cases
 * (empty bit set, clearing untracked pages, scanning across page boundaries).
 */
public class CollectionDirtyPageBitSetTest {

  private static final long FILE_ID = 1L;
  private static final String NAME = "testCollection";
  private static final String LOCK_NAME = "testCollection.dpb";
  private static final String EXTENSION = ".dpb";

  private ByteBufferPool bufferPool;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AbstractStorage mockStorage;
  private AtomicOperation atomicOperation;

  // Tracks the number of pages in the simulated file, mirrors filledUpTo semantics.
  private int pageCount;
  // Holds one CachePointer per page index so the same direct-memory buffer backs every
  // CacheEntry returned for that page, just like the real disk cache.
  private final Map<Integer, CachePointer> pagePointers = new HashMap<>();

  private CollectionDirtyPageBitSet bitSet;

  @Before
  public void setUp() throws IOException {
    bufferPool = ByteBufferPool.instance(null);
    mockReadCache = mock(ReadCache.class);
    mockWriteCache = mock(WriteCache.class);
    mockStorage = mock(AbstractStorage.class);

    var mockAtomicOperationsManager = mock(AtomicOperationsManager.class);
    when(mockStorage.getReadCache()).thenReturn(mockReadCache);
    when(mockStorage.getWriteCache()).thenReturn(mockWriteCache);
    when(mockStorage.getAtomicOperationsManager()).thenReturn(mockAtomicOperationsManager);

    pageCount = 0;
    pagePointers.clear();

    atomicOperation = createAtomicOperation();
    bitSet = new CollectionDirtyPageBitSet(mockStorage, NAME, EXTENSION, LOCK_NAME);
    bitSet.create(atomicOperation);
  }

  @After
  public void tearDown() {
    for (var cp : pagePointers.values()) {
      cp.decrementReferrer();
    }
    pagePointers.clear();
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  // Verifies that create() initialises one page with all bits clear.
  @Test
  public void createInitialisesWithAllBitsClear() throws IOException {
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that open() assigns the file ID so subsequent operations work.
  @Test
  public void openAssignsFileId() throws IOException {
    var bs2 = new CollectionDirtyPageBitSet(mockStorage, "openTest", EXTENSION, LOCK_NAME);
    bs2.open(atomicOperation);
    // If open failed, the next call would throw because fileId wouldn't be set.
    assertThat(bs2.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // Single-bit operations
  // ---------------------------------------------------------------------------

  // Verifies that setting a single bit makes it discoverable via nextSetBit.
  @Test
  public void setAndQuerySingleBit() throws IOException {
    bitSet.set(42, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(42);
    assertThat(bitSet.nextSetBit(42, atomicOperation)).isEqualTo(42);
    assertThat(bitSet.nextSetBit(43, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that clearing a set bit removes it from the scan.
  @Test
  public void clearRemovesBit() throws IOException {
    bitSet.set(100, atomicOperation);
    bitSet.clear(100, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that setting the same bit twice is idempotent.
  @Test
  public void setIsIdempotent() throws IOException {
    bitSet.set(7, atomicOperation);
    bitSet.set(7, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(7);
    bitSet.clear(7, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that clearing a bit that was never set is harmless.
  @Test
  public void clearOnUnsetBitIsNoOp() throws IOException {
    bitSet.clear(50, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // nextSetBit scanning
  // ---------------------------------------------------------------------------

  // Verifies that nextSetBit finds multiple set bits in ascending order.
  @Test
  public void nextSetBitReturnsInAscendingOrder() throws IOException {
    bitSet.set(10, atomicOperation);
    bitSet.set(20, atomicOperation);
    bitSet.set(30, atomicOperation);

    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(10);
    assertThat(bitSet.nextSetBit(11, atomicOperation)).isEqualTo(20);
    assertThat(bitSet.nextSetBit(21, atomicOperation)).isEqualTo(30);
    assertThat(bitSet.nextSetBit(31, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that nextSetBit returns -1 on a completely empty bit set.
  @Test
  public void nextSetBitOnEmptyReturnsMinusOne() throws IOException {
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that nextSetBit with fromIndex exactly on a set bit returns that bit.
  @Test
  public void nextSetBitExactMatch() throws IOException {
    bitSet.set(0, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(0);
  }

  // Verifies correct behavior at the first and last bit positions of a byte boundary.
  @Test
  public void bitsAtByteBoundaries() throws IOException {
    // Last bit of first byte (bit 7) and first bit of second byte (bit 8).
    bitSet.set(7, atomicOperation);
    bitSet.set(8, atomicOperation);

    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(7);
    assertThat(bitSet.nextSetBit(8, atomicOperation)).isEqualTo(8);
    assertThat(bitSet.nextSetBit(9, atomicOperation)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // Multi-page scenarios
  // ---------------------------------------------------------------------------

  // Verifies that setting a bit beyond the initial page capacity triggers
  // automatic page growth, and the bit is correctly stored and retrievable.
  @Test
  public void setBitBeyondFirstPageTriggersGrowth() throws IOException {
    // DirtyPageBitSetPage.BITS_PER_PAGE bits fit on one page. Set a bit on the second page.
    var bitOnSecondPage = DirtyPageBitSetPage.BITS_PER_PAGE + 5;
    bitSet.set(bitOnSecondPage, atomicOperation);

    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(bitOnSecondPage);
    assertThat(bitSet.nextSetBit(bitOnSecondPage, atomicOperation))
        .isEqualTo(bitOnSecondPage);
    assertThat(bitSet.nextSetBit(bitOnSecondPage + 1, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that nextSetBit correctly scans across page boundaries.
  @Test
  public void nextSetBitScansAcrossPages() throws IOException {
    var bitOnPage0 = 100;
    var bitOnPage1 = DirtyPageBitSetPage.BITS_PER_PAGE + 200;
    bitSet.set(bitOnPage0, atomicOperation);
    bitSet.set(bitOnPage1, atomicOperation);

    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(bitOnPage0);
    assertThat(bitSet.nextSetBit(bitOnPage0 + 1, atomicOperation)).isEqualTo(bitOnPage1);
  }

  // Verifies that nextSetBit transitions correctly at the exact page boundary:
  // last bit of page 0 and first bit of page 1.
  @Test
  public void nextSetBitAtExactPageBoundary() throws IOException {
    var lastBitOnPage0 = DirtyPageBitSetPage.BITS_PER_PAGE - 1;
    var firstBitOnPage1 = DirtyPageBitSetPage.BITS_PER_PAGE;
    bitSet.set(lastBitOnPage0, atomicOperation);
    bitSet.set(firstBitOnPage1, atomicOperation);

    assertThat(bitSet.nextSetBit(lastBitOnPage0, atomicOperation))
        .isEqualTo(lastBitOnPage0);
    assertThat(bitSet.nextSetBit(lastBitOnPage0 + 1, atomicOperation))
        .isEqualTo(firstBitOnPage1);
  }

  // Verifies that nextSetBit starting exactly at BITS_PER_PAGE (second page start)
  // correctly computes the initial page index.
  @Test
  public void nextSetBitStartingAtSecondPage() throws IOException {
    var bitOnPage1 = DirtyPageBitSetPage.BITS_PER_PAGE + 42;
    bitSet.set(bitOnPage1, atomicOperation);

    assertThat(bitSet.nextSetBit(DirtyPageBitSetPage.BITS_PER_PAGE, atomicOperation))
        .isEqualTo(bitOnPage1);
  }

  // Verifies that ensureCapacity correctly fills gap pages with zeros when a bit
  // is set far beyond the current file size, so intermediate pages contain no
  // false positives.
  @Test
  public void ensureCapacityFillsGapPagesWithZeros() throws IOException {
    // Set a bit on page 3 of the bit set file (skipping pages 1 and 2).
    var bitOnPage3 = DirtyPageBitSetPage.BITS_PER_PAGE * 3 + 5;
    bitSet.set(bitOnPage3, atomicOperation);

    // The bit set file should have grown to 4 pages (0, 1, 2, 3).
    assertThat(pageCount).isEqualTo(4);

    // nextSetBit from 0 should skip all intermediate pages and find only the
    // bit on page 3 — no false positives on gap pages.
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(bitOnPage3);
  }

  // Verifies that clearing a bit on a page beyond the current file size is a no-op
  // (does not grow the file).
  @Test
  public void clearBeyondFileSizeIsNoOp() throws IOException {
    var initialPageCount = pageCount;
    bitSet.clear(DirtyPageBitSetPage.BITS_PER_PAGE * 10 + 42, atomicOperation);
    // No new pages should have been allocated.
    assertThat(pageCount).isEqualTo(initialPageCount);
  }

  // Verifies that multiple bits can be set and cleared independently on the same byte.
  @Test
  public void multipleBitsInSameByte() throws IOException {
    bitSet.set(0, atomicOperation);
    bitSet.set(3, atomicOperation);
    bitSet.set(7, atomicOperation);

    // Clear the middle one.
    bitSet.clear(3, atomicOperation);

    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(0);
    assertThat(bitSet.nextSetBit(1, atomicOperation)).isEqualTo(7);
    assertThat(bitSet.nextSetBit(8, atomicOperation)).isEqualTo(-1);
  }

  // Verifies that setting bit index 0 works correctly (edge case for bit/byte arithmetic).
  @Test
  public void bitZeroWorksCorrectly() throws IOException {
    bitSet.set(0, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(0);

    bitSet.clear(0, atomicOperation);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle: flush, close, rename delegate to cache
  // ---------------------------------------------------------------------------

  // Verifies that flush() delegates to writeCache.flush(fileId).
  // Targets mutant at CollectionDirtyPageBitSet line 77: "removed call to flush".
  @Test
  public void flushDelegatesToWriteCache() {
    bitSet.flush();
    verify(mockWriteCache).flush(FILE_ID);
  }

  // Verifies that close(flush=true) delegates to readCache.closeFile(fileId, true, writeCache).
  // Targets mutant at CollectionDirtyPageBitSet line 86: "removed call to closeFile".
  @Test
  public void closeDelegatesToReadCache() {
    bitSet.close(true);
    verify(mockReadCache).closeFile(FILE_ID, true, mockWriteCache);
  }

  // Verifies that close(flush=false) passes false to readCache.closeFile.
  @Test
  public void closeWithoutFlushDelegatesToReadCache() {
    bitSet.close(false);
    verify(mockReadCache).closeFile(FILE_ID, false, mockWriteCache);
  }

  // Verifies that rename() delegates to writeCache.renameFile() and updates the internal name.
  // Targets mutants at CollectionDirtyPageBitSet lines 100-101.
  @Test
  public void renameDelegatesToWriteCacheAndUpdatesName() throws IOException {
    bitSet.rename("newCollection");
    verify(mockWriteCache).renameFile(FILE_ID, "newCollection" + EXTENSION);
    assertThat(bitSet.getName()).isEqualTo("newCollection");
  }

  // Verifies that ensureCapacity initialises new pages via init().
  // Targets mutant at CollectionDirtyPageBitSet line 199: "removed call to init".
  // After growth, gap pages must have all bits clear; if init() is skipped and the
  // buffer happens to contain stale data, false positive bits would appear.
  @Test
  public void ensureCapacityInitialisesNewPages() throws IOException {
    // Set a bit on page 2 of the bit set file (skipping page 1).
    var bitOnPage2 = DirtyPageBitSetPage.BITS_PER_PAGE * 2 + 1;
    bitSet.set(bitOnPage2, atomicOperation);

    // Page 1 (the gap page) should have been initialised — no bits set.
    // Scan from the start of page 1: nextSetBit should skip all of page 1.
    var startOfPage1 = DirtyPageBitSetPage.BITS_PER_PAGE;
    assertThat(bitSet.nextSetBit(startOfPage1, atomicOperation))
        .as("gap page should have no bits set after ensureCapacity init()")
        .isEqualTo(bitOnPage2);
  }

  // Verifies boundary condition: clearing a bit where the computed bitSetPageIndex
  // equals filledUpTo should be a no-op and not attempt to load a non-existent page.
  // Targets mutant at CollectionDirtyPageBitSet line 141: boundary change (>= to >).
  @Test
  public void clearAtExactBoundaryIsNoOp() throws IOException {
    // After setUp(), pageCount is 1 (one page, index 0).
    // Bit at BITS_PER_PAGE would be on bitSetPageIndex 1, which equals filledUpTo (1).
    // With >= check: returns early (no-op). With > check: would try to load page 1.
    var initialPageCount = pageCount;
    bitSet.clear(DirtyPageBitSetPage.BITS_PER_PAGE, atomicOperation);

    // No new pages should have been allocated, and no bits should be affected.
    assertThat(pageCount).isEqualTo(initialPageCount);
    assertThat(bitSet.nextSetBit(0, atomicOperation)).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // Test infrastructure
  // ---------------------------------------------------------------------------

  private AtomicOperation createAtomicOperation() throws IOException {
    var op = mock(AtomicOperation.class);

    when(op.addFile(anyString())).thenReturn(FILE_ID);
    when(op.addFile(anyString(), anyBoolean())).thenReturn(FILE_ID);

    when(op.loadFile(anyString())).thenReturn(FILE_ID);

    when(op.filledUpTo(FILE_ID)).thenAnswer(inv -> (long) pageCount);

    when(op.addPage(FILE_ID)).thenAnswer(inv -> {
      var entry = getOrCreatePage(pageCount);
      pageCount++;
      return entry;
    });

    when(op.loadPageForWrite(eq(FILE_ID), anyLong(), anyInt(), anyBoolean()))
        .thenAnswer(inv -> {
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(pIdx);
        });

    when(op.loadPageForRead(eq(FILE_ID), anyLong()))
        .thenAnswer(inv -> {
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(pIdx);
        });

    return op;
  }

  /**
   * Returns a fresh {@link CacheEntry} for the given page index, backed by a persistent
   * direct-memory buffer that survives across calls (so writes are visible to subsequent
   * reads of the same page).
   */
  private CacheEntry getOrCreatePage(int pageIndex) {
    var cachePointer = pagePointers.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, FILE_ID, idx);
      cp.incrementReferrer();
      return cp;
    });

    return new CacheEntryImpl(FILE_ID, pageIndex, cachePointer, false, mockReadCache);
  }
}
