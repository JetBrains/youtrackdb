package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v1;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import org.junit.Assert;
import org.junit.Test;

/**
 * @since 4/15/14
 */
public class SBTreeNullBucketV1Test {

  @Test
  public void testEmptyBucket() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var bucket = new SBTreeNullBucketV1<String>(cacheEntry);
    bucket.init();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    Assert.assertNull(bucket.getValue(StringSerializer.INSTANCE, serializerFactory));

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }

  @Test
  public void testAddGetValue() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var bucket = new SBTreeNullBucketV1<String>(cacheEntry);
    bucket.init();

    bucket.setValue(
        StringSerializer.staticSerializeNativeAsWhole("test"), StringSerializer.INSTANCE);
    var treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNotNull(treeValue);
    Assert.assertEquals("test", treeValue.getValue());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }

  @Test
  public void testAddRemoveValue() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var bucket = new SBTreeNullBucketV1<String>(cacheEntry);
    bucket.init();

    bucket.setValue(
        StringSerializer.staticSerializeNativeAsWhole("test"), StringSerializer.INSTANCE);
    bucket.removeValue(StringSerializer.INSTANCE);

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNull(treeValue);

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }

  @Test
  public void testAddRemoveAddValue() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var bucket = new SBTreeNullBucketV1<String>(cacheEntry);
    bucket.init();

    bucket.setValue(
        StringSerializer.staticSerializeNativeAsWhole("test"), StringSerializer.INSTANCE);
    bucket.removeValue(StringSerializer.INSTANCE);

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNull(treeValue);

    bucket.setValue(
        StringSerializer.staticSerializeNativeAsWhole("testOne"), StringSerializer.INSTANCE);

    treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNotNull(treeValue);
    Assert.assertEquals("testOne", treeValue.getValue());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }
}
