package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CollectionPositionMapException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionPositionMapV2.CollectionPositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link CollectionPositionMapV2} covering all public operations: lifecycle
 * (create, open, close, flush, truncate, delete, rename), position allocation, CRUD on
 * individual positions, range queries (ceiling/higher/floor/lower), iteration, and boundary
 * conditions (empty map, out-of-range positions, bucket-full overflow). Each test verifies
 * a concrete behavioral scenario rather than targeting coverage metrics.
 */
public class CollectionPositionMapV2Test {

  private static final long FILE_ID = 1L;
  private static final String MAP_NAME = "testCluster";
  private static final String LOCK_NAME = "testCluster.cpm";
  private static final String EXTENSION = ".cpm";

  private ByteBufferPool bufferPool;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AbstractStorage mockStorage;
  private AtomicOperation atomicOperation;

  // Tracks the number of pages in the simulated file, mirrors filledUpTo semantics.
  // Intentionally mutable: the AtomicOperation mock's filledUpTo stub reads this field
  // at invocation time via a lambda closure, so mutations are immediately visible.
  private int pageCount;
  // Holds one CachePointer per page index so the same direct-memory buffer backs every
  // CacheEntry returned for that page, just like the real disk cache.
  private final Map<Integer, CachePointer> pagePointers = new HashMap<>();

  private CollectionPositionMapV2 positionMap;

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
    when(mockStorage.getName()).thenReturn("test-storage");

    pageCount = 0;
    pagePointers.clear();

    atomicOperation = createAtomicOperation();
    positionMap = new CollectionPositionMapV2(mockStorage, MAP_NAME, LOCK_NAME, EXTENSION);
    positionMap.create(atomicOperation);
  }

  @After
  public void tearDown() {
    for (var cp : pagePointers.values()) {
      cp.decrementReferrer();
    }
    pagePointers.clear();
  }

  // ---------------------------------------------------------------------------
  // Lifecycle operations
  // ---------------------------------------------------------------------------

  // Verifies that create() initialises the entry-point page with file size 0 when the
  // underlying file is new (filledUpTo == 0 at creation time). The entry-point page is
  // dirtied first to prove create() actually writes 0, not just relying on zero-init.
  @Test
  public void createOnNewFileInitialisesEntryPointToZero() throws IOException {
    // Dirty the entry-point page so we can verify create() actually writes 0.
    try (var entry = atomicOperation.loadPageForWrite(FILE_ID, 0, 1, false)) {
      new MapEntryPoint(entry).setFileSize(42);
    }
    // Re-create should reset to 0.
    positionMap.create(atomicOperation);
    assertThat(readLastPageFromEntryPoint()).isEqualTo(0);
  }

  // Verifies that create() re-initialises the entry-point page even when the file already
  // has pages (e.g. recovery scenario where filledUpTo > 0 before create is called).
  @Test
  public void createOnExistingFileReInitialisesEntryPoint() throws IOException {
    // Simulate a file that already has 2 pages before create() is called.
    // This new AtomicOperation shares the same pagePointers/pageCount as the primary
    // test infrastructure, which is intentional: we need the pre-created pages to be
    // visible through this operation's stubs.
    var op = createAtomicOperation();

    // Pre-add two pages so filledUpTo returns 2.
    makePage(0);
    makePage(1);
    pageCount = 2;

    // Write a non-zero file size into the entry-point to prove create resets it.
    try (var entry = op.loadPageForWrite(FILE_ID, 0, 1, false)) {
      new MapEntryPoint(entry).setFileSize(5);
    }

    var map2 = new CollectionPositionMapV2(mockStorage, "reinitMap", LOCK_NAME, EXTENSION);
    map2.create(op);

    // After create(), the file size stored in the entry point should be 0.
    try (var entry = op.loadPageForRead(FILE_ID, 0)) {
      assertThat(new MapEntryPoint(entry).getFileSize()).isEqualTo(0);
    }
  }

  // Verifies that open() assigns the file ID so subsequent operations can find the file.
  @Test
  public void openAssignsFileId() throws IOException {
    var map2 = new CollectionPositionMapV2(mockStorage, "openTest", LOCK_NAME, EXTENSION);
    map2.open(atomicOperation);
    assertThat(map2.getFileId()).isEqualTo(FILE_ID);
  }

  // Verifies that flush() delegates to the write-cache.
  @Test
  public void flushDelegatesToWriteCache() {
    positionMap.flush();
    verify(mockWriteCache).flush(FILE_ID);
  }

  // Verifies that close() delegates to the read-cache.
  @Test
  public void closeDelegatesToReadCache() {
    positionMap.close(true);
    verify(mockReadCache).closeFile(FILE_ID, true, mockWriteCache);
  }

  // Verifies that close(false) passes flush=false.
  @Test
  public void closeWithoutFlush() {
    positionMap.close(false);
    verify(mockReadCache).closeFile(FILE_ID, false, mockWriteCache);
  }

  // Verifies that truncate() resets the entry-point file size to 0.
  @Test
  public void truncateResetsEntryPoint() throws IOException {
    // Allocate several positions to grow the map.
    allocatePositions(3);

    positionMap.truncate(atomicOperation);

    assertThat(readLastPageFromEntryPoint()).isEqualTo(0);
  }

  // Verifies that delete() delegates to the atomic operation.
  @Test
  public void deleteDelegatesToAtomicOperation() throws IOException {
    positionMap.delete(atomicOperation);
    verify(atomicOperation).deleteFile(FILE_ID);
  }

  // Verifies that rename() updates both the write-cache file name and the component name.
  @Test
  public void renameUpdatesFileNameAndComponentName() throws IOException {
    positionMap.rename("newCluster");
    verify(mockWriteCache).renameFile(FILE_ID, "newCluster" + EXTENSION);
    assertThat(positionMap.getName()).isEqualTo("newCluster");
  }

  // Verifies that getFileId() returns the file ID assigned during create().
  @Test
  public void getFileIdReturnsAssignedId() {
    assertThat(positionMap.getFileId()).isEqualTo(FILE_ID);
  }

  // ---------------------------------------------------------------------------
  // Allocation
  // ---------------------------------------------------------------------------

  // First allocation on an empty map should create a new bucket page and return position 0.
  @Test
  public void firstAllocationReturnsPositionZero() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    assertThat(pos).isEqualTo(0L);
  }

  // Sequential allocations should return consecutive positions.
  @Test
  public void sequentialAllocationsReturnConsecutivePositions() throws IOException {
    var positions = allocatePositions(5);
    assertThat(positions).containsExactly(0L, 1L, 2L, 3L, 4L);
  }

  // When the first bucket page becomes full, the next allocation should spill over to a
  // new bucket page and return position MAX_ENTRIES.
  @Test
  public void allocationSpillsToNewPageWhenBucketFull() throws IOException {
    // Fill the first bucket page completely.
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;
    for (var i = 0; i < maxEntries; i++) {
      positionMap.allocate(atomicOperation);
    }

    // The next allocation must land on a new bucket page.
    var overflowPos = positionMap.allocate(atomicOperation);
    assertThat(overflowPos).isEqualTo((long) maxEntries);
  }

  // When the bucket is full and a pre-existing (but unused) page follows in the file,
  // that page should be reused instead of adding a brand-new page. This covers the
  // allocate() branch where lastPage < filledUpTo-1 after bucket overflow.
  @Test
  public void allocationReusesExistingPageAfterBucketOverflow() throws IOException {
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;

    // Fill the first bucket page.
    for (var i = 0; i < maxEntries; i++) {
      positionMap.allocate(atomicOperation);
    }

    // Pre-create an extra page so filledUpTo > lastPage+1, simulating a truncated but
    // not physically reclaimed file.
    makePage(pageCount);
    pageCount++;

    // Next allocation should reuse the pre-existing page rather than calling addPage.
    var overflowPos = positionMap.allocate(atomicOperation);
    assertThat(overflowPos).isEqualTo((long) maxEntries);
  }

  // When lastPage == 0 and there is already a pre-existing page at index 1 (but the
  // entry-point hasn't tracked it yet), allocate should load that page and reuse it.
  @Test
  public void firstAllocationReusesExistingPageWhenFilledUpToIsAhead() throws IOException {
    // The setUp() create() left pageCount == 1 (just the entry-point).
    // Pre-create page index 1 so filledUpTo will return 2, but lastPage is still 0.
    makePage(1);
    pageCount = 2;

    var pos = positionMap.allocate(atomicOperation);
    assertThat(pos).isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------------------

  // Updating an allocated position with a PositionEntry should make it retrievable.
  @Test
  public void updateMakesPositionRetrievable() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    var entry = new CollectionPositionMapBucket.PositionEntry(10, 42, 1);
    positionMap.update(pos, entry, atomicOperation);

    var result = positionMap.get(pos, atomicOperation);
    assertThat(result).isNotNull();
    assertThat(result.getPageIndex()).isEqualTo(10);
    assertThat(result.getRecordPosition()).isEqualTo(42);
    assertThat(result.getRecordVersion()).isEqualTo(1);
  }

  // Updating a position that is outside the range of the map should throw.
  @Test
  public void updateOutOfRangeThrows() {
    assertThatThrownBy(
        () -> positionMap.update(
            999, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation))
        .isInstanceOf(CollectionPositionMapException.class)
        .hasMessageContaining("outside of range");
  }

  // Updating a position that has been removed should throw, because the bucket rejects
  // writes to entries whose status is neither ALLOCATED nor FILLED.
  @Test
  public void updateRemovedPositionThrows() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);
    positionMap.remove(pos, 10, atomicOperation);

    assertThatThrownBy(
        () -> positionMap.update(
            pos, new CollectionPositionMapBucket.PositionEntry(2, 2, 2), atomicOperation))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("removed entry");
  }

  // ---------------------------------------------------------------------------
  // Update version
  // ---------------------------------------------------------------------------

  // Updating the version of a filled position should reflect in subsequent reads.
  @Test
  public void updateVersionChangesRecordVersion() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(10, 42, 1), atomicOperation);

    positionMap.updateVersion(pos, 99, atomicOperation);

    var result = positionMap.get(pos, atomicOperation);
    assertThat(result).isNotNull();
    assertThat(result.getRecordVersion()).isEqualTo(99);
  }

  // Updating the version of a position outside the map range should throw.
  @Test
  public void updateVersionOutOfRangeThrows() {
    assertThatThrownBy(
        () -> positionMap.updateVersion(999, 1, atomicOperation))
        .isInstanceOf(CollectionPositionMapException.class)
        .hasMessageContaining("outside of range");
  }

  // Updating the version of an ALLOCATED (not yet filled) position should succeed
  // without throwing, because updateVersion does not check the entry status.
  @Test
  public void updateVersionOnAllocatedPositionSucceeds() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.updateVersion(pos, 42, atomicOperation);

    // The position is still ALLOCATED (not FILLED), so get() returns null.
    assertThat(positionMap.get(pos, atomicOperation)).isNull();
    // But getStatus confirms it is still ALLOCATED.
    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.ALLOCATED);
  }

  // ---------------------------------------------------------------------------
  // Get
  // ---------------------------------------------------------------------------

  // Getting a position that has never been allocated should return null.
  @Test
  public void getOutOfRangeReturnsNull() throws IOException {
    assertThat(positionMap.get(999, atomicOperation)).isNull();
  }

  // Getting a position that was allocated but never filled should return null
  // (bucket.get returns null for ALLOCATED status).
  @Test
  public void getAllocatedButNotFilledReturnsNull() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    assertThat(positionMap.get(pos, atomicOperation)).isNull();
  }

  // Getting a filled position should return the stored entry.
  @Test
  public void getFilledPositionReturnsEntry() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(5, 20, 3), atomicOperation);

    var result = positionMap.get(pos, atomicOperation);
    assertThat(result).isNotNull();
    assertThat(result.getPageIndex()).isEqualTo(5);
    assertThat(result.getRecordPosition()).isEqualTo(20);
    assertThat(result.getRecordVersion()).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // Get with status
  // ---------------------------------------------------------------------------

  // Getting status for a position outside the map returns NOT_EXISTENT.
  @Test
  public void getWithStatusOutOfRangeReturnsNotExistent() throws IOException {
    var result = positionMap.getWithStatus(999, atomicOperation);
    assertThat(result.status()).isEqualTo(CollectionPositionMapBucket.NOT_EXISTENT);
    assertThat(result.entry()).isNull();
  }

  // Getting status for an ALLOCATED position returns ALLOCATED with no entry.
  @Test
  public void getWithStatusAllocatedReturnsAllocatedNoEntry() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    var result = positionMap.getWithStatus(pos, atomicOperation);
    assertThat(result.status()).isEqualTo(CollectionPositionMapBucket.ALLOCATED);
    assertThat(result.entry()).isNull();
  }

  // Getting status for a FILLED position returns FILLED with the entry data.
  @Test
  public void getWithStatusFilledReturnsFilledWithEntry() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(7, 14, 2), atomicOperation);

    var result = positionMap.getWithStatus(pos, atomicOperation);
    assertThat(result.status()).isEqualTo(CollectionPositionMapBucket.FILLED);
    assertThat(result.entry()).isNotNull();
    assertThat(result.entry().getPageIndex()).isEqualTo(7);
  }

  // Getting status for a REMOVED position returns REMOVED with the entry data.
  @Test
  public void getWithStatusRemovedReturnsRemovedWithEntry() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(7, 14, 2), atomicOperation);
    positionMap.remove(pos, 10, atomicOperation);

    var result = positionMap.getWithStatus(pos, atomicOperation);
    assertThat(result.status()).isEqualTo(CollectionPositionMapBucket.REMOVED);
    assertThat(result.entry()).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // Remove
  // ---------------------------------------------------------------------------

  // Removing a filled position should mark it as REMOVED and update its version.
  @Test
  public void removeMarksFilledPositionAsRemoved() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(3, 9, 1), atomicOperation);

    positionMap.remove(pos, 42, atomicOperation);

    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.REMOVED);
    // After removal, get() should return null (only FILLED entries are returned).
    assertThat(positionMap.get(pos, atomicOperation)).isNull();
  }

  // Removing an ALLOCATED (not yet filled) position is a no-op: the bucket's remove()
  // only operates on FILLED entries.
  @Test
  public void removeAllocatedPositionIsNoOp() throws IOException {
    var pos = positionMap.allocate(atomicOperation);

    positionMap.remove(pos, 10, atomicOperation);

    // Status should still be ALLOCATED, not REMOVED.
    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.ALLOCATED);
  }

  // Removing an already-REMOVED position is a no-op: a second remove does not change
  // the status or the deletion version.
  @Test
  public void removeAlreadyRemovedPositionIsNoOp() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(3, 9, 1), atomicOperation);
    positionMap.remove(pos, 42, atomicOperation);

    // Second remove with a different version should be a no-op.
    positionMap.remove(pos, 99, atomicOperation);

    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.REMOVED);
    // The deletion version should still be 42 from the first remove, because the
    // bucket's remove() skips entries that are not FILLED.
    var entry = positionMap.getWithStatus(pos, atomicOperation);
    assertThat(entry.entry().getRecordVersion()).isEqualTo(42);
  }

  // ---------------------------------------------------------------------------
  // getStatus
  // ---------------------------------------------------------------------------

  // Status for an out-of-range position should return NOT_EXISTENT.
  @Test
  public void getStatusOutOfRangeReturnsNotExistent() throws IOException {
    assertThat(positionMap.getStatus(999, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.NOT_EXISTENT);
  }

  // Status for an allocated-then-filled position should return FILLED.
  @Test
  public void getStatusForFilledPositionReturnsFilled() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);
    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.FILLED);
  }

  // Status for an allocated-but-unfilled position should return ALLOCATED.
  @Test
  public void getStatusForAllocatedPositionReturnsAllocated() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    assertThat(positionMap.getStatus(pos, atomicOperation))
        .isEqualTo(CollectionPositionMapBucket.ALLOCATED);
  }

  // ---------------------------------------------------------------------------
  // getFirstPosition / getLastPosition
  // ---------------------------------------------------------------------------

  // On an empty map, getFirstPosition should return COLLECTION_POS_INVALID.
  @Test
  public void getFirstPositionOnEmptyMapReturnsInvalid() throws IOException {
    assertThat(positionMap.getFirstPosition(atomicOperation))
        .isEqualTo(RID.COLLECTION_POS_INVALID);
  }

  // After filling a single position, getFirstPosition should return it.
  @Test
  public void getFirstPositionReturnsSingleFilledPosition() throws IOException {
    var pos = positionMap.allocate(atomicOperation);
    positionMap.update(
        pos, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);

    assertThat(positionMap.getFirstPosition(atomicOperation)).isEqualTo(pos);
  }

  // When positions 0 and 2 are filled but position 1 is only allocated,
  // getFirstPosition should return 0.
  @Test
  public void getFirstPositionSkipsAllocatedEntries() throws IOException {
    var p0 = positionMap.allocate(atomicOperation);
    positionMap.allocate(atomicOperation); // position 1, allocated only
    var p2 = positionMap.allocate(atomicOperation);

    positionMap.update(
        p2, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);
    positionMap.update(
        p0, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);

    assertThat(positionMap.getFirstPosition(atomicOperation)).isEqualTo(p0);
  }

  // On an empty map, getLastPosition should return COLLECTION_POS_INVALID.
  @Test
  public void getLastPositionOnEmptyMapReturnsInvalid() throws IOException {
    assertThat(positionMap.getLastPosition(atomicOperation))
        .isEqualTo(RID.COLLECTION_POS_INVALID);
  }

  // After filling multiple positions, getLastPosition should return the highest one.
  @Test
  public void getLastPositionReturnsHighestFilledPosition() throws IOException {
    for (var i = 0; i < 3; i++) {
      var pos = positionMap.allocate(atomicOperation);
      positionMap.update(
          pos, new CollectionPositionMapBucket.PositionEntry(1, i, 1), atomicOperation);
    }

    assertThat(positionMap.getLastPosition(atomicOperation)).isEqualTo(2);
  }

  // When the last allocated positions are not filled, getLastPosition should skip them
  // and return the highest filled one.
  @Test
  public void getLastPositionSkipsAllocatedAndRemovedEntries() throws IOException {
    var p0 = positionMap.allocate(atomicOperation);
    positionMap.update(
        p0, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);

    var p1 = positionMap.allocate(atomicOperation);
    positionMap.update(
        p1, new CollectionPositionMapBucket.PositionEntry(1, 1, 1), atomicOperation);
    positionMap.remove(p1, 5, atomicOperation);

    positionMap.allocate(atomicOperation); // position 2, allocated only

    // Only p0 is FILLED, so getLastPosition should skip p1 (REMOVED) and p2 (ALLOCATED).
    assertThat(positionMap.getLastPosition(atomicOperation)).isEqualTo(p0);
  }

  // ---------------------------------------------------------------------------
  // Ceiling positions
  // ---------------------------------------------------------------------------

  // ceilingPositions on an empty map should return an empty array.
  @Test
  public void ceilingPositionsOnEmptyMapReturnsEmpty() throws IOException {
    var result = positionMap.ceilingPositions(0, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // ceilingPositions with a position before the first filled entry should return entries
  // starting from the first filled entry.
  @Test
  public void ceilingPositionsReturnsFromFirstFilled() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.ceilingPositions(0, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // ceilingPositions starting at a specific position should return that position and above.
  @Test
  public void ceilingPositionsStartsAtGivenPosition() throws IOException {
    fillPositions(0, 1, 2, 3, 4);

    var result = positionMap.ceilingPositions(2, atomicOperation, 10);
    assertThat(result).containsExactly(2L, 3L, 4L);
  }

  // ceilingPositions should respect the limit parameter.
  @Test
  public void ceilingPositionsRespectsLimit() throws IOException {
    fillPositions(0, 1, 2, 3, 4);

    var result = positionMap.ceilingPositions(0, atomicOperation, 2);
    assertThat(result).hasSize(2);
    assertThat(result).containsExactly(0L, 1L);
  }

  // ceilingPositions with a negative position should clamp to 0 and return from the start.
  @Test
  public void ceilingPositionsWithNegativePositionClampsToZero() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.ceilingPositions(-5, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // ceilingPositions with a position past the last page should return empty.
  @Test
  public void ceilingPositionsPastLastPageReturnsEmpty() throws IOException {
    fillPositions(0, 1, 2);

    var farAway = CollectionPositionMapBucket.MAX_ENTRIES * 10L;
    var result = positionMap.ceilingPositions(farAway, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // ceilingPositions with limit <= 0 should treat it as unlimited.
  @Test
  public void ceilingPositionsWithZeroLimitIsUnlimited() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.ceilingPositions(0, atomicOperation, 0);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // ceilingPositions should skip allocated (non-filled) entries.
  @Test
  public void ceilingPositionsSkipsAllocatedEntries() throws IOException {
    positionMap.allocate(atomicOperation); // pos 0, allocated only
    fillPositions(1, 2);

    var result = positionMap.ceilingPositions(0, atomicOperation, 10);
    assertThat(result).containsExactly(1L, 2L);
  }

  // When all entries on the current page are non-existent, ceiling should continue
  // scanning to the next page.
  @Test
  public void ceilingPositionsAdvancesToNextPageWhenCurrentIsEmpty() throws IOException {
    // Fill positions beyond the first bucket to force a multi-page scan.
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;

    // Allocate maxEntries positions on page 1 (all ALLOCATED, not FILLED).
    for (var i = 0; i < maxEntries; i++) {
      positionMap.allocate(atomicOperation);
    }

    // Position maxEntries will be on page 2; fill it.
    var pageTwo = positionMap.allocate(atomicOperation);
    positionMap.update(
        pageTwo,
        new CollectionPositionMapBucket.PositionEntry(1, 1, 1),
        atomicOperation);

    // Ceiling from 0 should skip all ALLOCATED entries on page 1 and find pageTwo.
    var result = positionMap.ceilingPositions(0, atomicOperation, 10);
    assertThat(result).containsExactly(pageTwo);
  }

  // ceilingPositions starting past the bucket size on a page should move to next page.
  @Test
  public void ceilingPositionsMovesToNextPageWhenStartIndexBeyondBucketSize() throws IOException {
    // Allocate only 3 entries on page 1 and fill them.
    fillPositions(0, 1, 2);

    // Request ceiling starting at position 5 (within page 1 but past bucket size of 3).
    // resultSize = bucketSize(3) - index(5) = -2, which is <= 0, so it should advance.
    var result = positionMap.ceilingPositions(5, atomicOperation, 10);
    // No more pages, so result should be empty.
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Higher positions
  // ---------------------------------------------------------------------------

  // higherPositions returns positions strictly greater than the given position.
  @Test
  public void higherPositionsReturnsStrictlyGreater() throws IOException {
    fillPositions(0, 1, 2, 3);

    var result = positionMap.higherPositions(1, atomicOperation, 10);
    assertThat(result).containsExactly(2L, 3L);
  }

  // higherPositions with Long.MAX_VALUE should return an empty array to avoid overflow.
  @Test
  public void higherPositionsWithMaxValueReturnsEmpty() throws IOException {
    fillPositions(0, 1);

    var result = positionMap.higherPositions(Long.MAX_VALUE, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // higherPositions on an empty map should return an empty array.
  @Test
  public void higherPositionsOnEmptyMapReturnsEmpty() throws IOException {
    var result = positionMap.higherPositions(0, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Higher position entries
  // ---------------------------------------------------------------------------

  // higherPositionsEntries should return CollectionPositionEntry objects for positions
  // strictly greater than the given position, with correct entry data.
  @Test
  public void higherPositionsEntriesReturnsEntriesAbovePosition() throws IOException {
    // Use explicit values so the test is self-documenting about what it expects.
    for (var i = 0; i < 3; i++) {
      var pos = positionMap.allocate(atomicOperation);
      positionMap.update(
          pos,
          new CollectionPositionMapBucket.PositionEntry(100 + i, 200 + i, 300 + i),
          atomicOperation);
    }

    var result = positionMap.higherPositionsEntries(0, atomicOperation);
    assertThat(result).hasSize(2);
    assertThat(result[0].getPosition()).isEqualTo(1L);
    assertThat(result[0].getPage()).isEqualTo(101);
    assertThat(result[0].getOffset()).isEqualTo(201);
    assertThat(result[0].getRecordVersion()).isEqualTo(301);
    assertThat(result[1].getPosition()).isEqualTo(2L);
    assertThat(result[1].getPage()).isEqualTo(102);
    assertThat(result[1].getOffset()).isEqualTo(202);
    assertThat(result[1].getRecordVersion()).isEqualTo(302);
  }

  // higherPositionsEntries with Long.MAX_VALUE should return an empty array.
  @Test
  public void higherPositionsEntriesWithMaxValueReturnsEmpty() throws IOException {
    fillPositions(0, 1);

    var result = positionMap.higherPositionsEntries(Long.MAX_VALUE, atomicOperation);
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Floor positions
  // ---------------------------------------------------------------------------

  // floorPositions on an empty map should return an empty array.
  @Test
  public void floorPositionsOnEmptyMapReturnsEmpty() throws IOException {
    var result = positionMap.floorPositions(0, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // floorPositions should return positions less than or equal to the given position
  // in ascending order.
  @Test
  public void floorPositionsReturnsUpToGivenPositionAscending() throws IOException {
    fillPositions(0, 1, 2, 3, 4);

    var result = positionMap.floorPositions(3, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L, 3L);
  }

  // floorPositions should respect the limit parameter, returning the highest entries first
  // (internally), then reversing to ascending order.
  @Test
  public void floorPositionsRespectsLimit() throws IOException {
    fillPositions(0, 1, 2, 3, 4);

    var result = positionMap.floorPositions(4, atomicOperation, 2);
    // The implementation collects from the highest first, limits, then reverses.
    assertThat(result).hasSize(2);
    assertThat(result).containsExactly(3L, 4L);
  }

  // floorPositions with a negative position should return an empty array.
  @Test
  public void floorPositionsWithNegativePositionReturnsEmpty() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.floorPositions(-1, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // floorPositions with a position past the last page should clamp to the last page
  // and return all entries.
  @Test
  public void floorPositionsPastLastPageClampsAndReturnsAllEntries() throws IOException {
    fillPositions(0, 1, 2);

    var farAway = CollectionPositionMapBucket.MAX_ENTRIES * 10L;
    var result = positionMap.floorPositions(farAway, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // floorPositions with limit <= 0 should treat it as unlimited.
  @Test
  public void floorPositionsWithZeroLimitIsUnlimited() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.floorPositions(2, atomicOperation, 0);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // floorPositions should skip allocated-but-unfilled entries and advance to previous
  // pages when needed.
  @Test
  public void floorPositionsSkipsAllocatedEntries() throws IOException {
    fillPositions(0, 1);
    positionMap.allocate(atomicOperation); // position 2, allocated only

    var result = positionMap.floorPositions(2, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L);
  }

  // When all entries on the current page during a floor scan are non-filled, the scan
  // should move to the previous page.
  @Test
  public void floorPositionsAdvancesToPreviousPageWhenCurrentIsEmpty() throws IOException {
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;

    // Fill entries on page 1.
    fillPositions(0, 1, 2);

    // Allocate the rest of page 1 and all of page 2 (ALLOCATED only).
    for (var i = 3; i < maxEntries + 3; i++) {
      positionMap.allocate(atomicOperation);
    }

    // Floor from a position on page 2 should skip all ALLOCATED entries on page 2
    // and find filled entries on page 1.
    var result = positionMap.floorPositions(maxEntries + 2, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // ---------------------------------------------------------------------------
  // Lower positions
  // ---------------------------------------------------------------------------

  // lowerPositions returns positions strictly less than the given position.
  @Test
  public void lowerPositionsReturnsStrictlyLess() throws IOException {
    fillPositions(0, 1, 2, 3);

    var result = positionMap.lowerPositions(3, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // lowerPositions with position 0 should return an empty array (nothing is less than 0).
  @Test
  public void lowerPositionsAtZeroReturnsEmpty() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.lowerPositions(0, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // lowerPositions with Long.MAX_VALUE should return all filled positions (the
  // implementation subtracts 1 before searching, so MAX_VALUE - 1 is a valid position).
  @Test
  public void lowerPositionsWithMaxValueReturnsAllPositions() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.lowerPositions(Long.MAX_VALUE, atomicOperation, 10);
    assertThat(result).containsExactly(0L, 1L, 2L);
  }

  // ---------------------------------------------------------------------------
  // Lower position entries reversed
  // ---------------------------------------------------------------------------

  // lowerPositionsEntriesReversed returns CollectionPositionEntry objects strictly less
  // than the given position in descending order.
  @Test
  public void lowerPositionsEntriesReversedReturnsDescending() throws IOException {
    fillPositions(0, 1, 2, 3);

    var result = positionMap.lowerPositionsEntriesReversed(3, atomicOperation);
    assertThat(result).hasSize(3);
    // The "reversed" flag means the natural reversed scan order is preserved, so the
    // result should be in descending order (highest first).
    assertThat(result[0].getPosition()).isEqualTo(2L);
    assertThat(result[1].getPosition()).isEqualTo(1L);
    assertThat(result[2].getPosition()).isEqualTo(0L);
  }

  // lowerPositionsEntriesReversed with position 0 should return an empty array.
  @Test
  public void lowerPositionsEntriesReversedAtZeroReturnsEmpty() throws IOException {
    fillPositions(0, 1, 2);

    var result = positionMap.lowerPositionsEntriesReversed(0, atomicOperation);
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // forEachEntry
  // ---------------------------------------------------------------------------

  // forEachEntry on an empty map should not call the visitor.
  @Test
  public void forEachEntryOnEmptyMapDoesNotInvokeVisitor() throws IOException {
    var entries = new ArrayList<Long>();
    positionMap.forEachEntry(atomicOperation, (pos, status, version) -> entries.add(pos));
    assertThat(entries).isEmpty();
  }

  // forEachEntry should visit FILLED entries with the correct position, status, and version.
  @Test
  public void forEachEntryVisitsFilledEntries() throws IOException {
    fillPositions(0, 1, 2);

    var visited = new ArrayList<long[]>();
    positionMap.forEachEntry(atomicOperation, (pos, status, version) ->
        visited.add(new long[]{pos, status, version}));

    assertThat(visited).hasSize(3);
    for (var i = 0; i < 3; i++) {
      assertThat(visited.get(i)[0]).isEqualTo(i);
      assertThat(visited.get(i)[1]).isEqualTo(CollectionPositionMapBucket.FILLED);
      assertThat(visited.get(i)[2]).isEqualTo(1L);
    }
  }

  // forEachEntry should visit REMOVED entries as well.
  @Test
  public void forEachEntryVisitsRemovedEntries() throws IOException {
    fillPositions(0, 1);
    positionMap.remove(1, 77, atomicOperation);

    var visited = new ArrayList<long[]>();
    positionMap.forEachEntry(atomicOperation, (pos, status, version) ->
        visited.add(new long[]{pos, status, version}));

    assertThat(visited).hasSize(2);
    assertThat(visited.get(0)[1]).isEqualTo(CollectionPositionMapBucket.FILLED);
    assertThat(visited.get(1)[1]).isEqualTo(CollectionPositionMapBucket.REMOVED);
    assertThat(visited.get(1)[2]).isEqualTo(77L);
  }

  // forEachEntry should skip ALLOCATED entries.
  @Test
  public void forEachEntrySkipsAllocatedEntries() throws IOException {
    positionMap.allocate(atomicOperation); // pos 0, ALLOCATED
    fillPositions(1);

    var visited = new ArrayList<Long>();
    positionMap.forEachEntry(atomicOperation, (pos, status, version) -> visited.add(pos));

    assertThat(visited).hasSize(1);
    assertThat(visited.get(0)).isEqualTo(1L);
  }

  // forEachEntry should correctly iterate over entries that span multiple bucket pages,
  // visiting every FILLED entry across all pages.
  @Test
  public void forEachEntrySpansMultipleBucketPages() throws IOException {
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;
    var totalEntries = maxEntries + 3;

    // Fill MAX_ENTRIES + 3 entries across two bucket pages.
    for (var i = 0; i < totalEntries; i++) {
      var pos = positionMap.allocate(atomicOperation);
      positionMap.update(
          pos,
          new CollectionPositionMapBucket.PositionEntry(1, i, i + 1),
          atomicOperation);
    }

    var visited = new ArrayList<Long>();
    positionMap.forEachEntry(atomicOperation, (pos, status, version) -> visited.add(pos));

    assertThat(visited).hasSize(totalEntries);
    // Positions should be visited in order across pages.
    for (var i = 0; i < totalEntries; i++) {
      assertThat(visited.get(i)).isEqualTo((long) i);
    }
  }

  // ---------------------------------------------------------------------------
  // CollectionPositionEntry
  // ---------------------------------------------------------------------------

  // Verify all getter methods on CollectionPositionEntry return the values set in the
  // constructor.
  @Test
  public void collectionPositionEntryGettersReturnConstructorValues() {
    var entry = new CollectionPositionEntry(10, 20, 30, 40);
    assertThat(entry.getPosition()).isEqualTo(10);
    assertThat(entry.getPage()).isEqualTo(20);
    assertThat(entry.getOffset()).isEqualTo(30);
    assertThat(entry.getRecordVersion()).isEqualTo(40);
  }

  // ---------------------------------------------------------------------------
  // Edge cases: pageIndex calculation for positions near MAX_ENTRIES boundaries
  // ---------------------------------------------------------------------------

  // Positions at exactly the MAX_ENTRIES boundary should be correctly mapped to the
  // second bucket page.
  @Test
  public void getPositionAtBucketBoundaryUsesCorrectPage() throws IOException {
    var maxEntries = CollectionPositionMapBucket.MAX_ENTRIES;

    // Allocate all of page 1 and the first entry of page 2.
    for (var i = 0; i <= maxEntries; i++) {
      var pos = positionMap.allocate(atomicOperation);
      positionMap.update(
          pos,
          new CollectionPositionMapBucket.PositionEntry(1, i, 1),
          atomicOperation);
    }

    // Position maxEntries should be on page 2 (index 0 within that page).
    var result = positionMap.get(maxEntries, atomicOperation);
    assertThat(result).isNotNull();
    assertThat(result.getRecordPosition()).isEqualTo(maxEntries);
  }

  // floorPositions at exactly position 0 with no entries: the pageIndex for lastPage=0
  // falls through and the result is empty.
  @Test
  public void floorPositionsAtZeroOnMapWithOnlyAllocatedEntries() throws IOException {
    positionMap.allocate(atomicOperation); // pos 0, ALLOCATED

    var result = positionMap.floorPositions(0, atomicOperation, 10);
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Integration-style: full lifecycle of allocate -> fill -> query -> remove
  // ---------------------------------------------------------------------------

  // Allocate, fill, range-query, remove, and verify that removed entries are no longer
  // returned by range queries or get().
  @Test
  public void fullLifecycleOfPositionEntries() throws IOException {
    // Allocate and fill 5 positions.
    for (var i = 0; i < 5; i++) {
      var pos = positionMap.allocate(atomicOperation);
      positionMap.update(
          pos,
          new CollectionPositionMapBucket.PositionEntry(i + 1, i, 1),
          atomicOperation);
    }

    // Verify all 5 positions are visible.
    assertThat(positionMap.ceilingPositions(0, atomicOperation, 10))
        .containsExactly(0L, 1L, 2L, 3L, 4L);

    // Remove positions 1 and 3.
    positionMap.remove(1, 10, atomicOperation);
    positionMap.remove(3, 11, atomicOperation);

    // Ceiling from 0 should now skip removed entries.
    var ceiling = positionMap.ceilingPositions(0, atomicOperation, 10);
    assertThat(ceiling).containsExactly(0L, 2L, 4L);

    // Floor from 4 should also skip removed entries.
    var floor = positionMap.floorPositions(4, atomicOperation, 10);
    assertThat(floor).containsExactly(0L, 2L, 4L);

    // get() should return null for removed entries.
    assertThat(positionMap.get(1, atomicOperation)).isNull();
    assertThat(positionMap.get(3, atomicOperation)).isNull();

    // First and last positions should be 0 and 4 respectively.
    assertThat(positionMap.getFirstPosition(atomicOperation)).isEqualTo(0L);
    assertThat(positionMap.getLastPosition(atomicOperation)).isEqualTo(4L);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Allocates the given number of positions and returns their collection positions.
   */
  private List<Long> allocatePositions(int count) throws IOException {
    var positions = new ArrayList<Long>(count);
    for (var i = 0; i < count; i++) {
      positions.add(positionMap.allocate(atomicOperation));
    }
    return positions;
  }

  /**
   * Allocates positions 0..max(indices) and fills only the specified indices with a
   * PositionEntry. Positions not in the list remain ALLOCATED (not filled).
   */
  private void fillPositions(long... indices) throws IOException {
    long maxIndex = -1;
    for (var idx : indices) {
      if (idx > maxIndex) {
        maxIndex = idx;
      }
    }

    // Ensure enough positions are allocated.
    long currentAllocated = readLastPageFromEntryPoint() == 0
        ? 0
        : estimateAllocatedCount();
    for (var i = currentAllocated; i <= maxIndex; i++) {
      positionMap.allocate(atomicOperation);
    }

    // Fill the requested positions.
    for (var idx : indices) {
      positionMap.update(
          idx,
          new CollectionPositionMapBucket.PositionEntry(1, (int) idx, 1),
          atomicOperation);
    }
  }

  /**
   * Reads the lastPage (file size) value from the entry-point page (page 0).
   */
  private int readLastPageFromEntryPoint() throws IOException {
    try (var entry = atomicOperation.loadPageForRead(FILE_ID, 0)) {
      return new MapEntryPoint(entry).getFileSize();
    }
  }

  /**
   * Estimates the total number of allocated positions based on the file size and the
   * last bucket's size.
   */
  private long estimateAllocatedCount() throws IOException {
    var lastPage = readLastPageFromEntryPoint();
    if (lastPage == 0) {
      return 0;
    }

    long count = 0;
    for (var pageIndex = 1; pageIndex <= lastPage; pageIndex++) {
      try (var entry = atomicOperation.loadPageForRead(FILE_ID, pageIndex)) {
        count += new CollectionPositionMapBucket(entry).getSize();
      }
    }
    return count;
  }

  /**
   * Pre-creates a page at the given index in the simulated file's page map without
   * incrementing pageCount. Used to simulate pre-existing pages for reuse scenarios.
   */
  private void makePage(int pageIndex) {
    pagePointers.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, FILE_ID, idx);
      cp.incrementReferrer();
      return cp;
    });
  }

  /**
   * Creates a mock {@link AtomicOperation} that simulates a file with an in-memory page
   * store backed by real direct-memory buffers. Each page is a real 8 KB buffer so that
   * {@link CollectionPositionMapBucket} and {@link MapEntryPoint} operate correctly.
   */
  private AtomicOperation createAtomicOperation() throws IOException {
    var op = mock(AtomicOperation.class);

    when(op.addFile(anyString())).thenReturn(FILE_ID);

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

    // Each CacheEntry is a lightweight wrapper over the shared CachePointer; its close()
    // delegates to mockReadCache.releaseFromRead() which is a no-op.
    return new CacheEntryImpl(FILE_ID, pageIndex, cachePointer, false, mockReadCache);
  }
}
