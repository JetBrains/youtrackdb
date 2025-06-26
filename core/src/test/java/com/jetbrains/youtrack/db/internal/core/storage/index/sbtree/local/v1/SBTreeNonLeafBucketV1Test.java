package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v1;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
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
import org.junit.Test;

/**
 * @since 12.08.13
 */
public class SBTreeNonLeafBucketV1Test {

  @Test
  public void testInitialization() {
    final var bufferPool = ByteBufferPool.instance(null);
    final var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    cachePointer.incrementReferrer();

    var treeBucket = new SBTreeBucketV1<Long, Identifiable>(cacheEntry);
    treeBucket.init(false);

    Assert.assertEquals(0, treeBucket.size());
    Assert.assertFalse(treeBucket.isLeaf());

    treeBucket = new SBTreeBucketV1<>(cacheEntry);
    treeBucket.init(false);

    Assert.assertEquals(0, treeBucket.size());
    Assert.assertFalse(treeBucket.isLeaf());
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

    final var bufferPool = ByteBufferPool.instance(null);
    final var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    cachePointer.incrementReferrer();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());

    var treeBucket = new SBTreeBucketV1<Long, RID>(cacheEntry);
    treeBucket.init(false);

    var index = 0;
    Map<Long, Integer> keyIndexMap = new HashMap<>();
    for (var key : keys) {
      if (!treeBucket.addNonLeafEntry(
          index,
          LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key),
          random.nextInt(Integer.MAX_VALUE),
          random.nextInt(Integer.MAX_VALUE),
          true)) {
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

    long prevRight = -1;
    for (var i = 0; i < treeBucket.size(); i++) {
      var entry =
          treeBucket.getEntry(i, LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);

      if (prevRight > 0) {
        Assert.assertEquals(entry.leftChild, prevRight);
      }

      prevRight = entry.rightChild;
    }

    long prevLeft = -1;
    for (var i = treeBucket.size() - 1; i >= 0; i--) {
      var entry =
          treeBucket.getEntry(i, LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);

      if (prevLeft > 0) {
        Assert.assertEquals(entry.rightChild, prevLeft);
      }

      prevLeft = entry.leftChild;
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

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    while (keys.size() < 2 * SBTreeBucketV1.MAX_PAGE_SIZE_BYTES / LongSerializer.LONG_SIZE) {
      keys.add(random.nextLong());
    }

    final var bufferPool = ByteBufferPool.instance(null);
    final var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    cachePointer.incrementReferrer();

    var treeBucket = new SBTreeBucketV1<Long, RID>(cacheEntry);

    var index = 0;
    for (var key : keys) {
      if (!treeBucket.addNonLeafEntry(
          index, LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key), index,
          index + 1, true)) {
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

    for (var keyIndexEntry : keyIndexMap.entrySet()) {
      var entry =
          treeBucket.getEntry(
              keyIndexEntry.getValue(), LongSerializer.INSTANCE, LinkSerializer.INSTANCE,
              serializerFactory);

      Assert.assertEquals(
          entry,
          new SBTreeBucketV1.SBTreeEntry<Long, Identifiable>(
              keyIndexEntry.getValue(),
              keyIndexEntry.getValue() + 1,
              keyIndexEntry.getKey(),
              null));
    }

    var keysToAdd = originalSize - treeBucket.size();
    var addedKeys = 0;
    while (keysIterator.hasNext() && index < originalSize) {
      var key = keysIterator.next();

      if (!treeBucket.addNonLeafEntry(
          index, LongSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, key), index,
          index + 1, true)) {
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
          new SBTreeBucketV1.SBTreeEntry<Long, Identifiable>(
              keyIndexEntry.getValue(),
              keyIndexEntry.getValue() + 1,
              keyIndexEntry.getKey(),
              null));
    }

    Assert.assertEquals(treeBucket.size(), originalSize);
    Assert.assertEquals(addedKeys, keysToAdd);

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }
}
