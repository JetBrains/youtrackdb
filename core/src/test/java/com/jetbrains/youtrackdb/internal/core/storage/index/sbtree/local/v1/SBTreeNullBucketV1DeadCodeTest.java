package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1;

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
 * Dead-code shape pin for {@link SBTreeNullBucketV1}.
 *
 * <p>Phase A PSI {@code ReferencesSearch} confirmed 0 main references and only 4 test
 * references (all in the legacy {@code SBTreeNullBucketV1Test}). The v1 SBTree null bucket
 * is unreachable from any production code path; it is transitively dead once
 * {@code SBTreeBucketV1} is deleted in Track 22.
 *
 * <p>These tests pin falsifiable behavioural observables (getRawValue null and non-null paths)
 * that are not yet covered by the legacy test file, so that a deletion commit in Track 22
 * either removes this file in lockstep or fails at compile time.
 *
 * <p>WHEN-FIXED: delete this file together with the entire {@code sbtree/local/v1} package
 * and the legacy test files ({@code SBTreeLeafBucketV1Test}, {@code SBTreeNonLeafBucketV1Test},
 * {@code SBTreeNullBucketV1Test}) in a single coordinated commit when the Track 22 deletion
 * sweep runs.
 */
public class SBTreeNullBucketV1DeadCodeTest {

  private static final BinarySerializerFactory FACTORY =
      BinarySerializerFactory.create(BinarySerializerFactory.currentBinaryFormatVersion());

  /**
   * getRawValue returns null when the bucket is empty (no value was set).
   */
  @Test
  public void getRawValue_emptyBucket_returnsNull() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new SBTreeNullBucketV1<String>(entry);
      bucket.init();

      Assert.assertNull("getRawValue must return null for an empty bucket",
          bucket.getRawValue(StringSerializer.INSTANCE, FACTORY));
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * getRawValue returns the raw serialized bytes matching the value stored via setValue.
   * Deserializing the raw bytes must reproduce the original value.
   */
  @Test
  public void getRawValue_afterSetValue_returnsDeserializableBytes() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new SBTreeNullBucketV1<String>(entry);
      bucket.init();

      byte[] serialized = StringSerializer.staticSerializeNativeAsWhole("rawPinTest");
      bucket.setValue(serialized, StringSerializer.INSTANCE);

      byte[] raw = bucket.getRawValue(StringSerializer.INSTANCE, FACTORY);
      Assert.assertNotNull("getRawValue must return non-null after setValue", raw);

      String deserialized = StringSerializer.INSTANCE.deserializeNativeObject(FACTORY, raw, 0);
      Assert.assertEquals("deserialized raw bytes must reproduce the original value",
          "rawPinTest", deserialized);
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }
}
