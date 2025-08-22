package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.record.RecordVersionHelper;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * @since 20.03.13
 */
public class CollectionPageTest {

  @Test
  public void testAddOneRecord() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    try {
      final var localPage = new CollectionPage(cacheEntry);
      localPage.init();
      addOneRecord(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addOneRecord(CollectionPage localPage) {
    var freeSpace = localPage.getFreeSpace();
    Assert.assertEquals(localPage.getRecordsCount(), 0);

    var recordVersion = 1;

    var position =
        localPage.appendRecord(
            recordVersion, new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1}, -1, IntSets.emptySet());
    Assert.assertEquals(localPage.getRecordsCount(), 1);
    Assert.assertEquals(localPage.getRecordSize(0), 11);
    Assert.assertEquals(position, 0);
    Assert.assertEquals(
        localPage.getFreeSpace(), freeSpace - (27 + RecordVersionHelper.SERIALIZED_SIZE));
    Assert.assertFalse(localPage.isDeleted(0));
    Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

    assertThat(localPage.getRecordBinaryValue(0, 0, 11))
        .isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1});
  }

  @Test
  public void testAddThreeRecords() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addThreeRecords(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addThreeRecords(CollectionPage localPage) {
    var freeSpace = localPage.getFreeSpace();

    Assert.assertEquals(localPage.getRecordsCount(), 0);

    var recordVersion = 0;
    recordVersion++;

    var positionOne =
        localPage.appendRecord(
            recordVersion, new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1}, -1, IntSets.emptySet());
    var positionTwo =
        localPage.appendRecord(
            recordVersion, new byte[]{2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2}, -1, IntSets.emptySet());
    var positionThree =
        localPage.appendRecord(
            recordVersion, new byte[]{3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3}, -1, IntSets.emptySet());

    Assert.assertEquals(localPage.getRecordsCount(), 3);
    Assert.assertEquals(positionOne, 0);
    Assert.assertEquals(positionTwo, 1);
    Assert.assertEquals(positionThree, 2);

    Assert.assertEquals(
        localPage.getFreeSpace(), freeSpace - (3 * (27 + RecordVersionHelper.SERIALIZED_SIZE)));
    Assert.assertFalse(localPage.isDeleted(0));
    Assert.assertFalse(localPage.isDeleted(1));
    Assert.assertFalse(localPage.isDeleted(2));

    assertThat(localPage.getRecordBinaryValue(0, 0, 11))
        .isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1});
    Assert.assertEquals(localPage.getRecordSize(0), 11);
    Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

    assertThat(localPage.getRecordBinaryValue(1, 0, 11))
        .isEqualTo(new byte[]{2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2});
    Assert.assertEquals(localPage.getRecordSize(0), 11);
    Assert.assertEquals(localPage.getRecordVersion(1), recordVersion);

    assertThat(localPage.getRecordBinaryValue(2, 0, 11))
        .isEqualTo(new byte[]{3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3});
    Assert.assertEquals(localPage.getRecordSize(0), 11);
    Assert.assertEquals(localPage.getRecordVersion(2), recordVersion);
  }

  @Test
  public void testAddFullPage() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addFullPage(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addFullPage(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;

    List<Integer> positions = new ArrayList<>();
    int lastPosition;
    byte counter = 0;
    var freeSpace = localPage.getFreeSpace();
    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positions.size());
        positions.add(lastPosition);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    Assert.assertEquals(localPage.getRecordsCount(), positions.size());

    counter = 0;
    for (int position : positions) {
      assertThat(localPage.getRecordBinaryValue(position, 0, 3))
          .isEqualTo(new byte[]{counter, counter, counter});
      Assert.assertEquals(localPage.getRecordSize(position), 3);
      Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
      counter++;
    }
  }

  @Test
  public void testAddDeleteAddBookedPositionsOne() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addDeleteAddBookedPositionsOne(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addDeleteAddBookedPositionsOne(final CollectionPage collectionPage) {
    final IntSet bookedPositions = new IntOpenHashSet();

    collectionPage.appendRecord(1, new byte[]{1}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{2}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{3}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{4}, -1, bookedPositions);

    collectionPage.deleteRecord(0, true);
    collectionPage.deleteRecord(1, true);
    collectionPage.deleteRecord(2, true);
    collectionPage.deleteRecord(3, true);

    bookedPositions.add(1);
    bookedPositions.add(2);

    var position = collectionPage.appendRecord(1, new byte[]{5}, -1, bookedPositions);
    Assert.assertEquals(3, position);

    position = collectionPage.appendRecord(1, new byte[]{6}, -1, bookedPositions);
    Assert.assertEquals(0, position);

    position = collectionPage.appendRecord(1, new byte[]{7}, -1, bookedPositions);
    Assert.assertEquals(4, position);

    position = collectionPage.appendRecord(1, new byte[]{8}, 1, bookedPositions);
    Assert.assertEquals(1, position);

    position = collectionPage.appendRecord(1, new byte[]{9}, 2, bookedPositions);
    Assert.assertEquals(2, position);

    position = collectionPage.appendRecord(1, new byte[]{10}, -1, bookedPositions);
    Assert.assertEquals(5, position);

    Assert.assertArrayEquals(new byte[]{6}, collectionPage.getRecordBinaryValue(0, 0, 1));
    Assert.assertArrayEquals(new byte[]{8}, collectionPage.getRecordBinaryValue(1, 0, 1));
    Assert.assertArrayEquals(new byte[]{9}, collectionPage.getRecordBinaryValue(2, 0, 1));
    Assert.assertArrayEquals(new byte[]{5}, collectionPage.getRecordBinaryValue(3, 0, 1));
    Assert.assertArrayEquals(new byte[]{7}, collectionPage.getRecordBinaryValue(4, 0, 1));
    Assert.assertArrayEquals(new byte[]{10}, collectionPage.getRecordBinaryValue(5, 0, 1));
  }

  @Test
  public void testAddDeleteAddBookedPositionsTwo() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addDeleteAddBookedPositionsTwo(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addDeleteAddBookedPositionsTwo(final CollectionPage collectionPage) {
    final IntSet bookedPositions = new IntOpenHashSet();

    collectionPage.appendRecord(1, new byte[]{1}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{2}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{3}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{4}, -1, bookedPositions);

    collectionPage.deleteRecord(0, true);
    collectionPage.deleteRecord(1, true);
    collectionPage.deleteRecord(2, true);
    collectionPage.deleteRecord(3, true);

    bookedPositions.add(1);
    bookedPositions.add(2);

    var position = collectionPage.appendRecord(1, new byte[]{5}, -1, bookedPositions);
    Assert.assertEquals(3, position);

    position = collectionPage.appendRecord(1, new byte[]{6}, -1, bookedPositions);
    Assert.assertEquals(0, position);

    position = collectionPage.appendRecord(1, new byte[]{9}, 2, bookedPositions);
    Assert.assertEquals(2, position);

    position = collectionPage.appendRecord(1, new byte[]{7}, -1, bookedPositions);
    Assert.assertEquals(4, position);

    position = collectionPage.appendRecord(1, new byte[]{8}, 1, bookedPositions);
    Assert.assertEquals(1, position);

    position = collectionPage.appendRecord(1, new byte[]{10}, -1, bookedPositions);
    Assert.assertEquals(5, position);

    Assert.assertArrayEquals(new byte[]{6}, collectionPage.getRecordBinaryValue(0, 0, 1));
    Assert.assertArrayEquals(new byte[]{8}, collectionPage.getRecordBinaryValue(1, 0, 1));
    Assert.assertArrayEquals(new byte[]{9}, collectionPage.getRecordBinaryValue(2, 0, 1));
    Assert.assertArrayEquals(new byte[]{5}, collectionPage.getRecordBinaryValue(3, 0, 1));
    Assert.assertArrayEquals(new byte[]{7}, collectionPage.getRecordBinaryValue(4, 0, 1));
    Assert.assertArrayEquals(new byte[]{10}, collectionPage.getRecordBinaryValue(5, 0, 1));
  }

  @Test
  public void testAddDeleteAddBookedPositionsThree() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addDeleteAddBookedPositionsThree(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addDeleteAddBookedPositionsThree(final CollectionPage collectionPage) {
    final IntSet bookedPositions = new IntOpenHashSet();

    collectionPage.appendRecord(1, new byte[]{1}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{2}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{3}, -1, bookedPositions);
    collectionPage.appendRecord(1, new byte[]{4}, -1, bookedPositions);

    collectionPage.deleteRecord(0, true);
    collectionPage.deleteRecord(1, true);
    collectionPage.deleteRecord(2, true);
    collectionPage.deleteRecord(3, true);

    bookedPositions.add(1);
    bookedPositions.add(2);

    var position = collectionPage.appendRecord(1, new byte[]{9}, 2, bookedPositions);
    Assert.assertEquals(2, position);

    position = collectionPage.appendRecord(1, new byte[]{8}, 1, bookedPositions);
    Assert.assertEquals(1, position);

    position = collectionPage.appendRecord(1, new byte[]{5}, -1, bookedPositions);
    Assert.assertEquals(3, position);

    position = collectionPage.appendRecord(1, new byte[]{6}, -1, bookedPositions);
    Assert.assertEquals(0, position);

    position = collectionPage.appendRecord(1, new byte[]{7}, -1, bookedPositions);
    Assert.assertEquals(4, position);

    position = collectionPage.appendRecord(1, new byte[]{10}, -1, bookedPositions);
    Assert.assertEquals(5, position);

    Assert.assertArrayEquals(new byte[]{6}, collectionPage.getRecordBinaryValue(0, 0, 1));
    Assert.assertArrayEquals(new byte[]{8}, collectionPage.getRecordBinaryValue(1, 0, 1));
    Assert.assertArrayEquals(new byte[]{9}, collectionPage.getRecordBinaryValue(2, 0, 1));
    Assert.assertArrayEquals(new byte[]{5}, collectionPage.getRecordBinaryValue(3, 0, 1));
    Assert.assertArrayEquals(new byte[]{7}, collectionPage.getRecordBinaryValue(4, 0, 1));
    Assert.assertArrayEquals(new byte[]{10}, collectionPage.getRecordBinaryValue(5, 0, 1));
  }

  @Test
  public void testDeleteAddLowerVersion() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddLowerVersion(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddLowerVersion(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, true));

    var newRecordVersion = 0;

    Assert.assertEquals(
        localPage.appendRecord(
            newRecordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), newRecordVersion);

    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddLowerVersionNFL() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddLowerVersionNFL(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddLowerVersionNFL(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, false));

    var newRecordVersion = 0;

    Assert.assertEquals(
        localPage.appendRecord(
            newRecordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), newRecordVersion);

    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddBiggerVersion() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddBiggerVersion(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddBiggerVersion(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, true));

    var newRecordVersion = 0;
    newRecordVersion++;
    newRecordVersion++;
    newRecordVersion++;
    newRecordVersion++;

    Assert.assertEquals(
        localPage.appendRecord(
            newRecordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), newRecordVersion);
    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddBiggerVersionNFL() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddBiggerVersionNFL(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddBiggerVersionNFL(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, false));

    var newRecordVersion = 0;
    newRecordVersion++;
    newRecordVersion++;
    newRecordVersion++;
    newRecordVersion++;

    Assert.assertEquals(
        localPage.appendRecord(
            newRecordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), newRecordVersion);
    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddEqualVersion() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddEqualVersion(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddEqualVersion(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, true));

    Assert.assertEquals(
        localPage.appendRecord(
            recordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddEqualVersionNFL() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddEqualVersionNFL(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddEqualVersionNFL(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, false));

    Assert.assertEquals(
        localPage.appendRecord(
            recordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteAddEqualVersionKeepTombstoneVersion() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteAddEqualVersionKeepTombstoneVersion(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteAddEqualVersionKeepTombstoneVersion(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var position = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());

    Assert.assertArrayEquals(record, localPage.deleteRecord(position, true));

    Assert.assertEquals(
        localPage.appendRecord(
            recordVersion, new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2}, -1, IntSets.emptySet()),
        position);

    var recordSize = localPage.getRecordSize(position);
    Assert.assertEquals(recordSize, 11);

    Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
    assertThat(localPage.getRecordBinaryValue(position, 0, recordSize))
        .isEqualTo(new byte[]{2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2});
  }

  @Test
  public void testDeleteTwoOutOfFour() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      deleteTwoOutOfFour(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void deleteTwoOutOfFour(CollectionPage localPage) {
    var recordVersion = 0;
    recordVersion++;

    final var recordOne = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    final var recordTwo = new byte[]{2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2};
    final var recordThree = new byte[]{3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3};
    final var recordFour = new byte[]{4, 2, 3, 4, 5, 6, 5, 4, 3, 2, 4};

    var positionOne = localPage.appendRecord(recordVersion, recordOne, -1, IntSets.emptySet());
    var positionTwo = localPage.appendRecord(recordVersion, recordTwo, -1, IntSets.emptySet());

    var positionThree = localPage.appendRecord(recordVersion, recordThree, -1, IntSets.emptySet());
    var positionFour = localPage.appendRecord(recordVersion, recordFour, -1, IntSets.emptySet());

    Assert.assertEquals(localPage.getRecordsCount(), 4);
    Assert.assertEquals(positionOne, 0);
    Assert.assertEquals(positionTwo, 1);
    Assert.assertEquals(positionThree, 2);
    Assert.assertEquals(positionFour, 3);

    Assert.assertFalse(localPage.isDeleted(0));
    Assert.assertFalse(localPage.isDeleted(1));
    Assert.assertFalse(localPage.isDeleted(2));
    Assert.assertFalse(localPage.isDeleted(3));

    var freeSpace = localPage.getFreeSpace();

    Assert.assertArrayEquals(recordOne, localPage.deleteRecord(0, true));
    Assert.assertArrayEquals(recordThree, localPage.deleteRecord(2, true));

    Assert.assertNull(localPage.deleteRecord(0, true));
    Assert.assertNull(localPage.deleteRecord(7, true));

    Assert.assertEquals(localPage.findFirstDeletedRecord(0), 0);
    Assert.assertEquals(localPage.findFirstDeletedRecord(1), 2);
    Assert.assertEquals(localPage.findFirstDeletedRecord(3), -1);

    Assert.assertTrue(localPage.isDeleted(0));
    Assert.assertEquals(localPage.getRecordSize(0), -1);
    Assert.assertEquals(localPage.getRecordVersion(0), -1);

    assertThat(localPage.getRecordBinaryValue(1, 0, 11))
        .isEqualTo(new byte[]{2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2});
    Assert.assertEquals(localPage.getRecordSize(1), 11);
    Assert.assertEquals(localPage.getRecordVersion(1), recordVersion);

    Assert.assertTrue(localPage.isDeleted(2));
    Assert.assertEquals(localPage.getRecordSize(2), -1);
    Assert.assertEquals(localPage.getRecordVersion(2), -1);

    assertThat(localPage.getRecordBinaryValue(3, 0, 11))
        .isEqualTo(new byte[]{4, 2, 3, 4, 5, 6, 5, 4, 3, 2, 4});

    Assert.assertEquals(localPage.getRecordSize(3), 11);
    Assert.assertEquals(localPage.getRecordVersion(3), recordVersion);

    Assert.assertEquals(localPage.getRecordsCount(), 2);
    Assert.assertEquals(localPage.getFreeSpace(), freeSpace + 23 * 2);
  }

  @Test
  public void testAddFullPageDeleteAndAddAgain() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addFullPageDeleteAndAddAgain(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addFullPageDeleteAndAddAgain(CollectionPage localPage) {
    Map<Integer, Byte> positionCounter = new HashMap<>();
    Set<Integer> deletedPositions = new HashSet<>();

    int lastPosition;
    byte counter = 0;
    var freeSpace = localPage.getFreeSpace();
    var recordVersion = 0;
    recordVersion++;

    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positionCounter.size());
        positionCounter.put(lastPosition, counter);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    var filledRecordsCount = positionCounter.size();
    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

    for (var i = 0; i < filledRecordsCount; i += 2) {
      localPage.deleteRecord(i, true);
      deletedPositions.add(i);
      positionCounter.remove(i);
    }

    freeSpace = localPage.getFreeSpace();
    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        positionCounter.put(lastPosition, counter);
        counter++;

        Assert.assertEquals(localPage.getFreeSpace(), freeSpace - 15);
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);
    for (var entry : positionCounter.entrySet()) {
      assertThat(localPage.getRecordBinaryValue(entry.getKey(), 0, 3))
          .isEqualTo(new byte[]{entry.getValue(), entry.getValue(), entry.getValue()});

      Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);

      if (deletedPositions.contains(entry.getKey())) {
        Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), recordVersion);
      }
    }
  }

  @Test
  public void testAddFullPageDeleteAndAddAgainNFL() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addFullPageDeleteAndAddAgainNFL(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addFullPageDeleteAndAddAgainNFL(CollectionPage localPage) {
    Map<Integer, Byte> positionCounter = new HashMap<>();

    int lastPosition;
    byte counter = 0;
    var freeSpace = localPage.getFreeSpace();
    var recordVersion = 0;
    recordVersion++;

    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positionCounter.size());
        positionCounter.put(lastPosition, counter);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    var filledRecordsCount = positionCounter.size();
    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

    for (var i = filledRecordsCount; i >= 0; i--) {
      localPage.deleteRecord(i, false);
      positionCounter.remove(i);
    }

    freeSpace = localPage.getFreeSpace();
    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        positionCounter.put(lastPosition, counter);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - 15 - CollectionPage.INDEX_ITEM_SIZE);
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);
    for (var entry : positionCounter.entrySet()) {
      assertThat(localPage.getRecordBinaryValue(entry.getKey(), 0, 3))
          .isEqualTo(new byte[]{entry.getValue(), entry.getValue(), entry.getValue()});

      Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);
    }
  }

  @Test
  public void testAddBigRecordDeleteAndAddSmallRecords() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    try {
      final var seed = System.currentTimeMillis();

      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      addBigRecordDeleteAndAddSmallRecords(seed, localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void addBigRecordDeleteAndAddSmallRecords(long seed, CollectionPage localPage) {
    final var mersenneTwisterFast = new Random(seed);

    var recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final var bigChunk = new byte[CollectionPage.MAX_ENTRY_SIZE / 2];

    mersenneTwisterFast.nextBytes(bigChunk);

    var position = localPage.appendRecord(recordVersion, bigChunk, -1, IntSets.emptySet());
    Assert.assertEquals(position, 0);
    Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

    Assert.assertArrayEquals(bigChunk, localPage.deleteRecord(0, true));

    recordVersion++;
    var freeSpace = localPage.getFreeSpace();
    Map<Integer, Byte> positionCounter = new HashMap<>();
    int lastPosition;
    byte counter = 0;
    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positionCounter.size());
        positionCounter.put(lastPosition, counter);
        counter++;

        if (lastPosition == 0) {
          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - 15);
        } else {
          Assert.assertEquals(
              localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        }

        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    Assert.assertEquals(localPage.getRecordsCount(), positionCounter.size());
    for (var entry : positionCounter.entrySet()) {
      assertThat(localPage.getRecordBinaryValue(entry.getKey(), 0, 3))
          .isEqualTo(new byte[]{entry.getValue(), entry.getValue(), entry.getValue()});
      Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);
      Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), recordVersion);
    }
  }

  @Test
  public void testFindFirstRecord() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    final var seed = System.currentTimeMillis();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      findFirstRecord(seed, localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void findFirstRecord(long seed, CollectionPage localPage) {
    final var mersenneTwister = new Random(seed);
    Set<Integer> positions = new HashSet<>();

    int lastPosition;
    byte counter = 0;
    var freeSpace = localPage.getFreeSpace();

    var recordVersion = 0;
    recordVersion++;

    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positions.size());
        positions.add(lastPosition);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    var filledRecordsCount = positions.size();
    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

    for (var i = 0; i < filledRecordsCount; i++) {
      if (mersenneTwister.nextBoolean()) {
        localPage.deleteRecord(i, true);
        positions.remove(i);
      }
    }

    var recordsIterated = 0;
    var recordPosition = 0;
    var lastRecordPosition = -1;

    do {
      recordPosition = localPage.findFirstRecord(recordPosition);
      if (recordPosition < 0) {
        break;
      }

      Assert.assertTrue(positions.contains(recordPosition));
      Assert.assertTrue(recordPosition > lastRecordPosition);

      lastRecordPosition = recordPosition;

      recordPosition++;
      recordsIterated++;
    } while (recordPosition >= 0);

    Assert.assertEquals(recordsIterated, positions.size());
  }

  @Test
  public void testFindLastRecord() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    final var seed = System.currentTimeMillis();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      findLastRecord(seed, localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void findLastRecord(long seed, CollectionPage localPage) {
    final var mersenneTwister = new Random(seed);
    Set<Integer> positions = new HashSet<>();

    int lastPosition;
    byte counter = 0;
    var freeSpace = localPage.getFreeSpace();

    var recordVersion = 0;
    recordVersion++;

    do {
      lastPosition =
          localPage.appendRecord(
              recordVersion, new byte[]{counter, counter, counter}, -1, IntSets.emptySet());
      if (lastPosition >= 0) {
        Assert.assertEquals(lastPosition, positions.size());
        positions.add(lastPosition);
        counter++;

        Assert.assertEquals(
            localPage.getFreeSpace(), freeSpace - (19 + RecordVersionHelper.SERIALIZED_SIZE));
        freeSpace = localPage.getFreeSpace();
      }
    } while (lastPosition >= 0);

    var filledRecordsCount = positions.size();
    Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

    for (var i = 0; i < filledRecordsCount; i++) {
      if (mersenneTwister.nextBoolean()) {
        localPage.deleteRecord(i, true);
        positions.remove(i);
      }
    }

    var recordsIterated = 0;
    var recordPosition = Integer.MAX_VALUE;
    var lastRecordPosition = Integer.MAX_VALUE;
    do {
      recordPosition = localPage.findLastRecord(recordPosition);
      if (recordPosition < 0) {
        break;
      }

      Assert.assertTrue(positions.contains(recordPosition));
      Assert.assertTrue(recordPosition < lastRecordPosition);

      recordPosition--;
      recordsIterated++;
    } while (recordPosition >= 0);

    Assert.assertEquals(recordsIterated, positions.size());
  }

  @Test
  public void testSetGetNextPage() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      setGetNextPage(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void setGetNextPage(CollectionPage localPage) {
    localPage.setNextPage(1034);
    Assert.assertEquals(localPage.getNextPage(), 1034);
  }

  @Test
  public void testSetGetPrevPage() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();
      setGetPrevPage(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void setGetPrevPage(CollectionPage localPage) {
    localPage.setPrevPage(1034);
    Assert.assertEquals(localPage.getPrevPage(), 1034);
  }

  @Test
  public void testReplaceOneRecordWithEqualSize() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      replaceOneRecordWithEqualSize(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void replaceOneRecordWithEqualSize(CollectionPage localPage) {
    Assert.assertEquals(localPage.getRecordsCount(), 0);

    var recordVersion = 0;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var index = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());
    var freeSpace = localPage.getFreeSpace();

    int newRecordVersion;
    newRecordVersion = recordVersion;
    newRecordVersion++;

    final var oldRecord =
        localPage.replaceRecord(
            index, new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1}, newRecordVersion);
    Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
    Assert.assertArrayEquals(record, oldRecord);

    Assert.assertEquals(localPage.getRecordSize(index), 11);

    assertThat(localPage.getRecordBinaryValue(index, 0, 11))
        .isEqualTo(new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1});
    Assert.assertEquals(localPage.getRecordVersion(index), newRecordVersion);
  }

  @Test
  public void testReplaceOneRecordNoVersionUpdate() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      replaceOneRecordNoVersionUpdate(localPage);

    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void replaceOneRecordNoVersionUpdate(CollectionPage localPage) {
    Assert.assertEquals(localPage.getRecordsCount(), 0);

    var recordVersion = 0;
    recordVersion++;

    var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var index = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());
    var freeSpace = localPage.getFreeSpace();

    var oldRecord =
        localPage.replaceRecord(index, new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1}, -1);
    Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
    Assert.assertArrayEquals(record, oldRecord);

    Assert.assertEquals(localPage.getRecordSize(index), 11);

    assertThat(localPage.getRecordBinaryValue(index, 0, 11))
        .isEqualTo(new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1});
    Assert.assertEquals(localPage.getRecordVersion(index), recordVersion);
  }

  @Test
  public void testReplaceOneRecordLowerVersion() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      var localPage = new CollectionPage(cacheEntry);
      localPage.init();

      replaceOneRecordLowerVersion(localPage);
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  private void replaceOneRecordLowerVersion(CollectionPage localPage) {
    Assert.assertEquals(localPage.getRecordsCount(), 0);

    var recordVersion = 0;
    recordVersion++;

    final var record = new byte[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
    var index = localPage.appendRecord(recordVersion, record, -1, IntSets.emptySet());
    var freeSpace = localPage.getFreeSpace();

    int newRecordVersion;
    newRecordVersion = recordVersion;

    var oldRecord =
        localPage.replaceRecord(
            index, new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1}, newRecordVersion);
    Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
    Assert.assertArrayEquals(record, oldRecord);

    Assert.assertEquals(localPage.getRecordSize(index), 11);

    assertThat(localPage.getRecordBinaryValue(index, 0, 11))
        .isEqualTo(new byte[]{5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1});
    Assert.assertEquals(localPage.getRecordVersion(index), recordVersion);
  }
}
