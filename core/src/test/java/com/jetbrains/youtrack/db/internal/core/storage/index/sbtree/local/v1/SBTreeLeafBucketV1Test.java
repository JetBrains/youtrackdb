package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v1;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 09.08.13
 */
public class SBTreeLeafBucketV1Test {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testInitialization() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var treeBucket = new SBTreeBucketV1<Long, Identifiable>(cacheEntry);
    treeBucket.init(true);
    Assert.assertEquals(0, treeBucket.size());
    Assert.assertTrue(treeBucket.isLeaf());

    treeBucket = new SBTreeBucketV1<>(cacheEntry);
    treeBucket.init(true);
    Assert.assertEquals(0, treeBucket.size());
    Assert.assertTrue(treeBucket.isLeaf());
    Assert.assertEquals(-1, treeBucket.getLeftSibling());
    Assert.assertEquals(-1, treeBucket.getRightSibling());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testSearch() {
    var seed = System.currentTimeMillis();
    System.out.println("testSearch seed : " + seed);

    var keys = new TreeSet<Long>();
    var random = new Random(seed);

    while (keys.size() < 2 * SBTreeBucketV1.MAX_PAGE_SIZE_BYTES / LongSerializer.LONG_SIZE) {
      keys.add(random.nextLong());
    }

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    cachePointer.incrementReferrer();

    var treeBucket = new SBTreeBucketV1<Long, Identifiable>(cacheEntry);
    treeBucket.init(true);

    var index = 0;
    Map<Long, Integer> keyIndexMap = new HashMap<>();
    for (var key : keys) {
      if (!treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }
      keyIndexMap.put(key, index);
      index++;
    }

    Assert.assertEquals(treeBucket.size(), keyIndexMap.size());

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var bucketIndex = treeBucket.find(keyIndexEntry.getKey(), LongSerializer.INSTANCE,
          serializerFactory);
      Assert.assertEquals(bucketIndex, (int) keyIndexEntry.getValue());
    }

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testUpdateValue() {
    var seed = System.currentTimeMillis();
    System.out.println("testUpdateValue seed : " + seed);

    var keys = new TreeSet<Long>();
    var random = new Random(seed);

    while (keys.size() < 2 * SBTreeBucketV1.MAX_PAGE_SIZE_BYTES / LongSerializer.LONG_SIZE) {
      keys.add(random.nextLong());
    }

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    cachePointer.incrementReferrer();

    var treeBucket = new SBTreeBucketV1<Long, RID>(cacheEntry);
    treeBucket.init(true);

    Map<Long, Integer> keyIndexMap = new HashMap<>();
    var index = 0;
    for (var key : keys) {
      if (index >= 1
          || !treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }
      keyIndexMap.put(key, index);
      index++;
    }

    Assert.assertEquals(keyIndexMap.size(), treeBucket.size());

    for (var i = 0; i < treeBucket.size(); i++) {
      final var rawValue = new byte[LinkSerializer.RID_SIZE];

      LinkSerializer.INSTANCE.serializeNativeObject(new RecordId(i + 5, i + 5), serializerFactory,
          rawValue, 0);
      treeBucket.updateValue(i, rawValue, LongSerializer.LONG_SIZE);
    }

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var entry =
          treeBucket.getEntry(
              keyIndexEntry.getValue(), LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);

      Assert.assertEquals(
          entry,
          new SBTreeBucketV1.SBTreeEntry<>(
              -1,
              -1,
              keyIndexEntry.getKey(),
              new SBTreeValue<>(
                  false,
                  -1,
                  new RecordId(keyIndexEntry.getValue() + 5, keyIndexEntry.getValue() + 5))));
      Assert.assertEquals(
          keyIndexEntry.getKey(),
          treeBucket.getKey(keyIndexEntry.getValue(), LongSerializer.INSTANCE, serializerFactory));
    }

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testShrink() {
    var seed = System.currentTimeMillis();
    System.out.println("testShrink seed : " + seed);

    var keys = new TreeSet<Long>();
    var random = new Random(seed);

    while (keys.size() < 2 * SBTreeBucketV1.MAX_PAGE_SIZE_BYTES / LongSerializer.LONG_SIZE) {
      keys.add(random.nextLong());
    }

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var treeBucket = new SBTreeBucketV1<Long, RID>(cacheEntry);
    treeBucket.init(true);

    var index = 0;
    for (var key : keys) {
      if (!treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }

      index++;
    }

    var originalSize = treeBucket.size();

    treeBucket.shrink(
        treeBucket.size() / 2, LongSerializer.INSTANCE, LinkSerializer.INSTANCE, serializerFactory);
    Assert.assertEquals(treeBucket.size(), index / 2);

    index = 0;
    final Map<Long, Integer> keyIndexMap = new HashMap<>();

    var keysIterator = keys.iterator();
    while (keysIterator.hasNext() && index < treeBucket.size()) {
      var key = keysIterator.next();
      keyIndexMap.put(key, index);
      index++;
    }

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var bucketIndex = treeBucket.find(keyIndexEntry.getKey(), LongSerializer.INSTANCE,
          serializerFactory);
      Assert.assertEquals(bucketIndex, (int) keyIndexEntry.getValue());
    }

    var keysToAdd = originalSize - treeBucket.size();
    var addedKeys = 0;
    while (keysIterator.hasNext() && index < originalSize) {
      var key = keysIterator.next();

      if (!treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }

      keyIndexMap.put(key, index);
      index++;
      addedKeys++;
    }

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var entry =
          treeBucket.getEntry(
              keyIndexEntry.getValue(), LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);
      Assert.assertEquals(
          entry,
          new SBTreeBucketV1.SBTreeEntry<>(
              -1,
              -1,
              keyIndexEntry.getKey(),
              new SBTreeValue<>(
                  false, -1, new RecordId(keyIndexEntry.getValue(), keyIndexEntry.getValue()))));
    }

    Assert.assertEquals(treeBucket.size(), originalSize);
    Assert.assertEquals(addedKeys, keysToAdd);

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testRemove() {
    var seed = System.currentTimeMillis();
    System.out.println("testRemove seed : " + seed);

    var keys = new TreeSet<Long>();
    var random = new Random(seed);

    while (keys.size() < 2 * SBTreeBucketV1.MAX_PAGE_SIZE_BYTES / LongSerializer.LONG_SIZE) {
      keys.add(random.nextLong());
    }

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var treeBucket = new SBTreeBucketV1<Long, RID>(cacheEntry);
    treeBucket.init(true);

    var index = 0;
    for (var key : keys) {
      if (!treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }

      index++;
    }

    var originalSize = treeBucket.size();

    var itemsToDelete = originalSize / 2;
    for (var i = 0; i < itemsToDelete; i++) {
      final var rawKey = treeBucket.getRawKey(i, false, LongSerializer.INSTANCE, serializerFactory);
      final var rawValue =
          treeBucket.getRawValue(i, LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);

      treeBucket.removeLeafEntry(treeBucket.size() - 1, rawKey, rawValue);
    }

    Assert.assertEquals(treeBucket.size(), originalSize - itemsToDelete);

    final Map<Long, Integer> keyIndexMap = new HashMap<>();
    var keysIterator = keys.iterator();

    index = 0;
    while (keysIterator.hasNext() && index < treeBucket.size()) {
      var key = keysIterator.next();
      keyIndexMap.put(key, index);
      index++;
    }

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var bucketIndex = treeBucket.find(keyIndexEntry.getKey(), LongSerializer.INSTANCE,
          serializerFactory);
      Assert.assertEquals(bucketIndex, (int) keyIndexEntry.getValue());
    }

    var keysToAdd = originalSize - treeBucket.size();
    var addedKeys = 0;
    while (keysIterator.hasNext() && index < originalSize) {
      var key = keysIterator.next();

      if (!treeBucket.addLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          LinkSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory,
              new RecordId(index, index)))) {
        break;
      }

      keyIndexMap.put(key, index);
      index++;
      addedKeys++;
    }

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      final var entry =
          treeBucket.getEntry(
              keyIndexEntry.getValue(), LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);
      Assert.assertEquals(
          entry,
          new SBTreeBucketV1.SBTreeEntry<>(
              -1,
              -1,
              keyIndexEntry.getKey(),
              new SBTreeValue<>(
                  false, -1, new RecordId(keyIndexEntry.getValue(), keyIndexEntry.getValue()))));
    }

    Assert.assertEquals(treeBucket.size(), originalSize);
    Assert.assertEquals(addedKeys, keysToAdd);

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testSetLeftSibling() throws Exception {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var treeBucket = new SBTreeBucketV1<Long, Identifiable>(cacheEntry);
    treeBucket.setLeftSibling(123);
    Assert.assertEquals(123, treeBucket.getLeftSibling());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  @Test
  public void testSetRightSibling() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var treeBucket = new SBTreeBucketV1<Long, Identifiable>(cacheEntry);
    treeBucket.setRightSibling(123);
    Assert.assertEquals(123, treeBucket.getRightSibling());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }
}
