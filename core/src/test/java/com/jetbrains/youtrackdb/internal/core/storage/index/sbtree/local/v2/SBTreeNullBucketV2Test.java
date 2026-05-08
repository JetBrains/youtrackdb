package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for SB-tree v2 null bucket operations for handling null key entries.
 *
 * @since 4/15/14
 */
public class SBTreeNullBucketV2Test {

  @Test
  public void testEmptyBucket() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();
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

    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    bucket.setValue(
        StringSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, "test"),
        StringSerializer.INSTANCE);
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

    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    bucket.setValue(
        StringSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, "test"),
        StringSerializer.INSTANCE);
    bucket.removeValue(StringSerializer.INSTANCE);

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

    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    bucket.setValue(
        StringSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, "test"),
        StringSerializer.INSTANCE);
    bucket.removeValue(StringSerializer.INSTANCE);

    var treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNull(treeValue);

    bucket.setValue(
        StringSerializer.INSTANCE.serializeNativeAsWhole(serializerFactory, "testOne"),
        StringSerializer.INSTANCE);

    treeValue = bucket.getValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNotNull(treeValue);
    Assert.assertEquals("testOne", treeValue.getValue());

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }

  /**
   * getRawValue returns null when the bucket is empty (no value set).
   */
  @Test
  public void testGetRawValueEmptyBucket() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();

    Assert.assertNull(bucket.getRawValue(StringSerializer.INSTANCE, serializerFactory));

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }

  /**
   * getRawValue returns the raw serialized bytes that were stored via setValue, matching
   * the bytes that StringSerializer would produce for the same value.
   */
  @Test
  public void testGetRawValueMatchesSetValue() {
    var bufferPool = new ByteBufferPool(1024);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var bucket = new SBTreeNullBucketV2<String>(cacheEntry);
    bucket.init();

    byte[] serialized = StringSerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, "rawTest");
    bucket.setValue(serialized, StringSerializer.INSTANCE);

    byte[] raw = bucket.getRawValue(StringSerializer.INSTANCE, serializerFactory);
    Assert.assertNotNull("getRawValue must return non-null after setValue", raw);

    // Deserialize the raw bytes and verify they produce the original value
    String deserialized = StringSerializer.INSTANCE.deserializeNativeObject(
        serializerFactory, raw, 0);
    Assert.assertEquals("deserialized raw value must match original", "rawTest", deserialized);

    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
    bufferPool.clear();
  }
}
