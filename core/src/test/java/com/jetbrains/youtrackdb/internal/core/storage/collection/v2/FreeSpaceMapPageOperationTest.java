package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for FreeSpaceMapPage logical WAL operations:
 * {@link FreeSpaceMapPageInitOp} and {@link FreeSpaceMapPageUpdateOp}.
 * Covers serialization roundtrip, WALRecordsFactory integration, redo correctness
 * (including segment tree propagation), registration from mutation methods, and
 * redo suppression.
 */
public class FreeSpaceMapPageOperationTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        FreeSpaceMapPageInitOp.RECORD_ID, FreeSpaceMapPageInitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        FreeSpaceMapPageUpdateOp.RECORD_ID, FreeSpaceMapPageUpdateOp.class);
  }

  // --- Record ID tests ---

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.FREE_SPACE_MAP_PAGE_INIT_OP,
        FreeSpaceMapPageInitOp.RECORD_ID);
    Assert.assertEquals(213, FreeSpaceMapPageInitOp.RECORD_ID);
  }

  @Test
  public void testUpdateOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.FREE_SPACE_MAP_PAGE_UPDATE_OP,
        FreeSpaceMapPageUpdateOp.RECORD_ID);
    Assert.assertEquals(214, FreeSpaceMapPageUpdateOp.RECORD_ID);
  }

  // --- Serialization roundtrip tests ---

  /**
   * InitOp: serialize to byte array and deserialize back. No custom fields beyond parent.
   */
  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new FreeSpaceMapPageInitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new FreeSpaceMapPageInitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  /**
   * UpdateOp: serialize to byte array and deserialize back. fsmPageIndex and freeSpace
   * must survive the roundtrip.
   */
  @Test
  public void testUpdateOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new FreeSpaceMapPageUpdateOp(15, 25, 35, initialLsn, 42, 200);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new FreeSpaceMapPageUpdateOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getFsmPageIndex(), deserialized.getFsmPageIndex());
    Assert.assertEquals(original.getFreeSpace(), deserialized.getFreeSpace());
    Assert.assertEquals(original, deserialized);
  }

  // --- WALRecordsFactory roundtrip tests ---

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new FreeSpaceMapPageInitOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof FreeSpaceMapPageInitOp);
    var result = (FreeSpaceMapPageInitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(FreeSpaceMapPageInitOp.RECORD_ID, result.getId());
  }

  @Test
  public void testUpdateOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new FreeSpaceMapPageUpdateOp(11, 22, 33, initialLsn, 100, 255);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof FreeSpaceMapPageUpdateOp);
    var result = (FreeSpaceMapPageUpdateOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(100, result.getFsmPageIndex());
    Assert.assertEquals(255, result.getFreeSpace());
    Assert.assertEquals(FreeSpaceMapPageUpdateOp.RECORD_ID, result.getId());
  }

  // --- Redo correctness tests ---

  /**
   * Redo correctness for init on a dirty (pre-populated) page. Populate both pages
   * with non-zero segment-tree data, then apply init directly on page1 and via redo
   * on page2. Both must be fully zeroed — verifies redo actually clears pre-existing data.
   */
  @Test
  public void testInitRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Populate both pages with non-zero data
      var page1 = new FreeSpaceMapPage(entry1);
      page1.init();
      page1.updatePageMaxFreeSpace(0, 255);
      page1.updatePageMaxFreeSpace(100, 128);
      page1.updatePageMaxFreeSpace(FreeSpaceMapPage.CELLS_PER_PAGE - 1, 200);

      var page2 = new FreeSpaceMapPage(entry2);
      page2.init();
      page2.updatePageMaxFreeSpace(0, 255);
      page2.updatePageMaxFreeSpace(100, 128);
      page2.updatePageMaxFreeSpace(FreeSpaceMapPage.CELLS_PER_PAGE - 1, 200);

      // Sanity: pages have data
      Assert.assertEquals(0, page1.findPage(255));
      Assert.assertEquals(0, page2.findPage(255));

      // Apply init directly on page1
      page1.init();

      // Apply init via redo on page2
      var op = new FreeSpaceMapPageInitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Both pages must be fully zeroed
      Assert.assertEquals(-1, page1.findPage(1));
      Assert.assertEquals(-1, page2.findPage(1));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness for updatePageMaxFreeSpace: apply on page1, redo on page2.
   * Both pages must have the same segment tree state. Asserts concrete expected values
   * (leaf 5 has 120, all others 0, so root = 120) and uses byte-level buffer comparison.
   */
  @Test
  public void testUpdateRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Initialize both pages
      var page1 = new FreeSpaceMapPage(entry1);
      page1.init();
      var page2 = new FreeSpaceMapPage(entry2);
      page2.init();

      // Apply update directly
      var rootValue = page1.updatePageMaxFreeSpace(5, 120);

      // Apply via redo — no second mutation on page2
      var op = new FreeSpaceMapPageUpdateOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 5, 120);
      op.redo(page2);

      // Root must be 120 (only non-zero leaf)
      Assert.assertEquals(120, rootValue);

      // Concrete expected values: leaf 5 has 120, findPage searches leftmost qualifying
      Assert.assertEquals(5, page1.findPage(100));
      Assert.assertEquals(5, page2.findPage(100));
      Assert.assertEquals(5, page1.findPage(120));
      Assert.assertEquals(5, page2.findPage(120));
      Assert.assertEquals(-1, page1.findPage(121));
      Assert.assertEquals(-1, page2.findPage(121));

      // Byte-level buffer comparison for exhaustive segment tree verification
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after redo", 0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness with multiple updates: verify segment tree propagation by applying
   * multiple updates and checking that findPage returns consistent results.
   */
  @Test
  public void testMultipleUpdatesRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new FreeSpaceMapPage(entry1);
      page1.init();
      var page2 = new FreeSpaceMapPage(entry2);
      page2.init();

      // Apply multiple updates on page1
      page1.updatePageMaxFreeSpace(0, 50);
      page1.updatePageMaxFreeSpace(10, 200);
      page1.updatePageMaxFreeSpace(100, 150);

      // Redo the same sequence on page2
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 0, 50)
          .redo(page2);
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 10, 200)
          .redo(page2);
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 100, 150)
          .redo(page2);

      // Concrete expected values: leaf 0=50, leaf 10=200, leaf 100=150
      // findPage(50): leftmost with >= 50 is leaf 0 (value 50)
      Assert.assertEquals(0, page1.findPage(50));
      Assert.assertEquals(0, page2.findPage(50));

      // findPage(100): leaf 0 has 50 < 100, leftmost qualifying is leaf 10 (200)
      Assert.assertEquals(10, page1.findPage(100));
      Assert.assertEquals(10, page2.findPage(100));

      // findPage(150): leaf 10 has 200 >= 150, leftmost qualifying is leaf 10
      Assert.assertEquals(10, page1.findPage(150));
      Assert.assertEquals(10, page2.findPage(150));

      // findPage(200): leaf 10 has 200 >= 200
      Assert.assertEquals(10, page1.findPage(200));
      Assert.assertEquals(10, page2.findPage(200));

      // findPage(201): no leaf has value >= 201
      Assert.assertEquals(-1, page1.findPage(201));
      Assert.assertEquals(-1, page2.findPage(201));

      // Byte-level buffer comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after redo", 0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness at the last valid leaf index (CELLS_PER_PAGE - 1). This is the
   * only index where the sibling is beyond the page boundary, triggering the fallback
   * path in updatePageMaxFreeSpace where siblingValue = nodeValue.
   */
  @Test
  public void testUpdateRedoCorrectnessAtLastLeaf() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var lastLeaf = FreeSpaceMapPage.CELLS_PER_PAGE - 1;

      var page1 = new FreeSpaceMapPage(entry1);
      page1.init();
      var page2 = new FreeSpaceMapPage(entry2);
      page2.init();

      // Apply update at the last leaf directly
      var rootValue = page1.updatePageMaxFreeSpace(lastLeaf, 200);

      // Apply via redo
      var op = new FreeSpaceMapPageUpdateOp(
          0, 0, 0, new LogSequenceNumber(0, 0), lastLeaf, 200);
      op.redo(page2);

      Assert.assertEquals(200, rootValue);

      // Both pages must find the last leaf
      Assert.assertEquals(lastLeaf, page1.findPage(200));
      Assert.assertEquals(lastLeaf, page2.findPage(200));

      // No page should satisfy a request above 200
      Assert.assertEquals(-1, page1.findPage(201));
      Assert.assertEquals(-1, page2.findPage(201));

      // Byte-level buffer comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after redo", 0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness when decreasing a leaf value. The segment tree must propagate
   * the decrease correctly to all ancestor nodes.
   */
  @Test
  public void testUpdateRedoDecreaseValue() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new FreeSpaceMapPage(entry1);
      page1.init();
      var page2 = new FreeSpaceMapPage(entry2);
      page2.init();

      // Set leaf 10 to 200 on both pages
      page1.updatePageMaxFreeSpace(10, 200);
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 10, 200)
          .redo(page2);

      // Now decrease leaf 10 to 50 on both pages
      var rootValue = page1.updatePageMaxFreeSpace(10, 50);
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 10, 50)
          .redo(page2);

      Assert.assertEquals(50, rootValue);

      // Searching for 51 should find nothing (max is now 50)
      Assert.assertEquals(-1, page1.findPage(51));
      Assert.assertEquals(-1, page2.findPage(51));

      // Searching for 50 should find leaf 10
      Assert.assertEquals(10, page1.findPage(50));
      Assert.assertEquals(10, page2.findPage(50));

      // Decrease to 0 — page should be effectively empty
      page1.updatePageMaxFreeSpace(10, 0);
      new FreeSpaceMapPageUpdateOp(0, 0, 0, new LogSequenceNumber(0, 0), 10, 0)
          .redo(page2);

      Assert.assertEquals(-1, page1.findPage(1));
      Assert.assertEquals(-1, page2.findPage(1));

      // Byte-level buffer comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after redo", 0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // --- Registration from mutation methods ---

  /**
   * When init() is called on a page backed by CacheEntryChanges,
   * a FreeSpaceMapPageInitOp must be registered.
   */
  @Test
  public void testInitRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(1, 100));

      var page = new FreeSpaceMapPage(changes);
      page.init();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(
          "Expected FreeSpaceMapPageInitOp but got " + registeredOp.getClass().getName(),
          registeredOp instanceof FreeSpaceMapPageInitOp);

      var initOp = (FreeSpaceMapPageInitOp) registeredOp;
      Assert.assertEquals(7, initOp.getPageIndex());
      Assert.assertEquals(42, initOp.getFileId());
      Assert.assertEquals(new LogSequenceNumber(1, 100), initOp.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * When updatePageMaxFreeSpace() is called on a page backed by CacheEntryChanges,
   * a FreeSpaceMapPageUpdateOp must be registered with the correct fsmPageIndex
   * and freeSpace values.
   */
  @Test
  public void testUpdateRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(2, 200));

      var page = new FreeSpaceMapPage(changes);
      page.init(); // Must initialize first before updating

      // Reset mock to capture only the update registration
      org.mockito.Mockito.reset(atomicOp);

      page.updatePageMaxFreeSpace(42, 180);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(
          "Expected FreeSpaceMapPageUpdateOp but got " + registeredOp.getClass().getName(),
          registeredOp instanceof FreeSpaceMapPageUpdateOp);

      var updateOp = (FreeSpaceMapPageUpdateOp) registeredOp;
      Assert.assertEquals(42, updateOp.getFsmPageIndex());
      Assert.assertEquals(180, updateOp.getFreeSpace());
      Assert.assertEquals(7, updateOp.getPageIndex());
      Assert.assertEquals(42, updateOp.getFileId());
      Assert.assertEquals(new LogSequenceNumber(2, 200), updateOp.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * updatePageMaxFreeSpace registers a PageOperation even when the value is unchanged
   * (short-circuit path). The registration happens unconditionally before the short-circuit.
   */
  @Test
  public void testUpdateRegistersOpEvenWhenValueUnchanged() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(3, 300));

      var page = new FreeSpaceMapPage(changes);
      page.init();

      // First update sets value to 100
      page.updatePageMaxFreeSpace(5, 100);
      org.mockito.Mockito.reset(atomicOp);

      // Second update with the same value — short-circuit path, but should still register
      page.updatePageMaxFreeSpace(5, 100);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof FreeSpaceMapPageUpdateOp);
      var updateOp = (FreeSpaceMapPageUpdateOp) registeredOp;
      Assert.assertEquals(5, updateOp.getFsmPageIndex());
      Assert.assertEquals(100, updateOp.getFreeSpace());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Redo suppression (D4) ---

  /**
   * When mutation methods are called on a page backed by a plain CacheEntry
   * (not CacheEntryChanges), no PageOperation must be registered.
   */
  @Test
  public void testNoRegistrationDuringRedoPath() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new FreeSpaceMapPage(entry);
      // These calls should NOT throw or attempt to register anything
      page.init();
      page.updatePageMaxFreeSpace(0, 100);

      // Verify values were written — find should locate the page
      Assert.assertEquals(0, page.findPage(100));
      Assert.assertEquals(-1, page.findPage(101));
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Equals and hashCode ---

  @Test
  public void testInitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new FreeSpaceMapPageInitOp(5, 10, 15, lsn);
    var op2 = new FreeSpaceMapPageInitOp(5, 10, 15, lsn);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different page index
    var op3 = new FreeSpaceMapPageInitOp(99, 10, 15, lsn);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testUpdateOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new FreeSpaceMapPageUpdateOp(5, 10, 15, lsn, 42, 200);
    var op2 = new FreeSpaceMapPageUpdateOp(5, 10, 15, lsn, 42, 200);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different fsmPageIndex
    var op3 = new FreeSpaceMapPageUpdateOp(5, 10, 15, lsn, 99, 200);
    Assert.assertNotEquals(op1, op3);

    // Different freeSpace
    var op4 = new FreeSpaceMapPageUpdateOp(5, 10, 15, lsn, 42, 100);
    Assert.assertNotEquals(op1, op4);
  }
}
