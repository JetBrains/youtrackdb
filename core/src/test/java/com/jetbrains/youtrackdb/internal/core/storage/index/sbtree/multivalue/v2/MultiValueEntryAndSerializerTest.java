package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link MultiValueEntry}, {@link MultiValueEntrySerializer},
 * {@link IndexEngineValidatorIncrement}, and {@link IndexEngineValidatorNullIncrement}.
 *
 * <p>These classes belong to the {@code multivalue/v2} WAL-replay-only package.
 * Production engines reject version 2 ({@code BTreeMultiValueIndexEngine.java:73}); only WAL
 * replay against legacy databases reaches this code.
 */
public class MultiValueEntryAndSerializerTest {

  private static final BinarySerializerFactory FACTORY =
      BinarySerializerFactory.create(BinarySerializerFactory.currentBinaryFormatVersion());

  // ─────────────────────────────────────────────────────────────────────────
  // MultiValueEntry — compareTo / field exposure
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * compareTo returns 0 when all three fields (id, collectionId, collectionPosition) are equal.
   */
  @Test
  public void testCompareToEqual() {
    var a = new MultiValueEntry(10L, 5, 200L);
    var b = new MultiValueEntry(10L, 5, 200L);
    Assert.assertEquals("equal entries must compare to zero", 0, a.compareTo(b));
  }

  /**
   * compareTo distinguishes entries by id (most significant field): a.id < b.id → negative.
   */
  @Test
  public void testCompareToById_lessThan() {
    var a = new MultiValueEntry(1L, 5, 200L);
    var b = new MultiValueEntry(2L, 5, 200L);
    Assert.assertTrue("a.id < b.id must yield negative compareTo", a.compareTo(b) < 0);
  }

  /**
   * compareTo distinguishes entries by id: a.id > b.id → positive.
   */
  @Test
  public void testCompareToById_greaterThan() {
    var a = new MultiValueEntry(3L, 5, 200L);
    var b = new MultiValueEntry(2L, 5, 200L);
    Assert.assertTrue("a.id > b.id must yield positive compareTo", a.compareTo(b) > 0);
  }

  /**
   * compareTo falls through to collectionId when id fields are equal.
   */
  @Test
  public void testCompareToByCollectionId() {
    var a = new MultiValueEntry(1L, 3, 100L);
    var b = new MultiValueEntry(1L, 7, 100L);
    Assert.assertTrue("smaller collectionId must compare negative", a.compareTo(b) < 0);
    Assert.assertTrue("larger collectionId must compare positive", b.compareTo(a) > 0);
  }

  /**
   * compareTo falls through to collectionPosition when id and collectionId are equal.
   */
  @Test
  public void testCompareToByCollectionPosition() {
    var a = new MultiValueEntry(1L, 3, 10L);
    var b = new MultiValueEntry(1L, 3, 20L);
    Assert.assertTrue("smaller collectionPosition must compare negative", a.compareTo(b) < 0);
    Assert.assertTrue("larger collectionPosition must compare positive", b.compareTo(a) > 0);
  }

  /**
   * Public fields are accessible directly (no encapsulation, by design).
   */
  @Test
  public void testPublicFields() {
    var entry = new MultiValueEntry(42L, 7, 999L);
    Assert.assertEquals(42L, entry.id);
    Assert.assertEquals(7, entry.collectionId);
    Assert.assertEquals(999L, entry.collectionPosition);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // MultiValueEntrySerializer — byte-array serialize/deserialize round-trip
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * serialize + deserialize via byte array produces an entry with equal field values.
   */
  @Test
  public void testSerializeDeserializeByteArray() {
    var original = new MultiValueEntry(100L, 3, 500L);
    var sz = MultiValueEntrySerializer.INSTANCE.getObjectSize(FACTORY, original);
    var buf = new byte[sz];
    MultiValueEntrySerializer.INSTANCE.serialize(original, FACTORY, buf, 0);

    var result = MultiValueEntrySerializer.INSTANCE.deserialize(FACTORY, buf, 0);
    Assert.assertEquals(original.id, result.id);
    Assert.assertEquals(original.collectionId, result.collectionId);
    Assert.assertEquals(original.collectionPosition, result.collectionPosition);
  }

  /**
   * serializeNativeObject + deserializeNativeObject round-trip.
   */
  @Test
  public void testSerializeNativeRoundTrip() {
    var original = new MultiValueEntry(200L, 9, 888L);
    var sz = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    var buf = new byte[sz];
    MultiValueEntrySerializer.INSTANCE.serializeNativeObject(original, FACTORY, buf, 0);

    var result = MultiValueEntrySerializer.INSTANCE.deserializeNativeObject(FACTORY, buf, 0);
    Assert.assertEquals(original.id, result.id);
    Assert.assertEquals(original.collectionId, result.collectionId);
    Assert.assertEquals(original.collectionPosition, result.collectionPosition);
  }

  /**
   * serializeInByteBufferObject + deserializeFromByteBufferObject round-trip.
   */
  @Test
  public void testSerializeInByteBufferRoundTrip() {
    var original = new MultiValueEntry(300L, 2, 777L);
    var sz = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    var buf = ByteBuffer.allocate(sz);
    MultiValueEntrySerializer.INSTANCE.serializeInByteBufferObject(FACTORY, original, buf);

    buf.flip();
    var result = MultiValueEntrySerializer.INSTANCE.deserializeFromByteBufferObject(FACTORY, buf);
    Assert.assertEquals(original.id, result.id);
    Assert.assertEquals(original.collectionId, result.collectionId);
    Assert.assertEquals(original.collectionPosition, result.collectionPosition);
  }

  /**
   * deserializeFromByteBufferObject with explicit offset round-trip.
   */
  @Test
  public void testSerializeInByteBufferWithOffsetRoundTrip() {
    var original = new MultiValueEntry(400L, 11, 666L);
    var sz = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    // Write to a buffer with 4-byte leading padding to verify offset-based read
    var buf = ByteBuffer.allocate(sz + 4);
    buf.position(4);
    MultiValueEntrySerializer.INSTANCE.serializeInByteBufferObject(FACTORY, original, buf);

    var result =
        MultiValueEntrySerializer.INSTANCE.deserializeFromByteBufferObject(FACTORY, 4, buf);
    Assert.assertEquals(original.id, result.id);
    Assert.assertEquals(original.collectionId, result.collectionId);
    Assert.assertEquals(original.collectionPosition, result.collectionPosition);
  }

  /**
   * getId returns the expected constant 27.
   */
  @Test
  public void testGetId() {
    Assert.assertEquals((byte) 27, MultiValueEntrySerializer.INSTANCE.getId());
    Assert.assertEquals(27, MultiValueEntrySerializer.ID);
  }

  /**
   * isFixedLength returns true and getFixedLength matches the constant size.
   */
  @Test
  public void testFixedLengthProperties() {
    Assert.assertTrue("MultiValueEntrySerializer must report fixed-length",
        MultiValueEntrySerializer.INSTANCE.isFixedLength());
    // 2 longs (id + collectionPosition) + 1 short (collectionId)
    int expected = 2 * Long.BYTES + Short.BYTES;
    Assert.assertEquals("fixed length must be 18 bytes", expected,
        MultiValueEntrySerializer.INSTANCE.getFixedLength());
  }

  /**
   * getObjectSize (from object) and getObjectSizeNative (from byte array) both return the
   * fixed size.
   */
  @Test
  public void testGetObjectSize() {
    var entry = new MultiValueEntry(1L, 1, 1L);
    int fixed = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    var buf = new byte[fixed];
    MultiValueEntrySerializer.INSTANCE.serializeNativeObject(entry, FACTORY, buf, 0);

    Assert.assertEquals(fixed,
        MultiValueEntrySerializer.INSTANCE.getObjectSize(FACTORY, entry));
    Assert.assertEquals(fixed,
        MultiValueEntrySerializer.INSTANCE.getObjectSizeNative(FACTORY, buf, 0));
  }

  /**
   * getObjectSize from byte stream at offset also returns the fixed size.
   */
  @Test
  public void testGetObjectSizeFromStream() {
    var entry = new MultiValueEntry(1L, 1, 1L);
    int fixed = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    var buf = new byte[fixed + 2]; // offset=2 prefix
    MultiValueEntrySerializer.INSTANCE.serialize(entry, FACTORY, buf, 2);

    Assert.assertEquals(fixed,
        MultiValueEntrySerializer.INSTANCE.getObjectSize(FACTORY, buf, 2));
  }

  /**
   * preprocess returns the same object unchanged.
   */
  @Test
  public void testPreprocessReturnsSameObject() {
    var entry = new MultiValueEntry(1L, 1, 1L);
    Assert.assertSame("preprocess must return the argument unchanged",
        entry, MultiValueEntrySerializer.INSTANCE.preprocess(FACTORY, entry));
  }

  /**
   * getObjectSizeInByteBuffer (absolute position) and (relative position) both return the
   * fixed length.
   */
  @Test
  public void testGetObjectSizeInByteBuffer() {
    var buf = ByteBuffer.allocate(64);
    int fixed = MultiValueEntrySerializer.INSTANCE.getFixedLength();
    Assert.assertEquals(fixed,
        MultiValueEntrySerializer.INSTANCE.getObjectSizeInByteBuffer(FACTORY, buf));
    Assert.assertEquals(fixed,
        MultiValueEntrySerializer.INSTANCE.getObjectSizeInByteBuffer(FACTORY, 0, buf));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // IndexEngineValidatorIncrement — validate logic
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * When ov is null (new key), validate must call incrementEntriesCount on the bucket
   * and return the provided value v.
   *
   * <p>Verification strategy: call decrementEntriesCount after the validate; it returns
   * {@code true} when the count drops to 0 (was 1 → not incremented) or {@code false}
   * when it drops to 1 (was 2 → was incremented). Since the entry starts at entriesCount=1,
   * after validate(k, null, v) entriesCount becomes 2, so decrementEntriesCount returns false.
   */
  @Test
  public void testValidatorIncrement_newKey_incrementsAndReturnsValue() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new CellBTreeMultiValueV2Bucket<String>(entry);
      bucket.init(true);
      // Add an entry so entryIndex=0 is valid; mId=1L, rid=(1,10)
      var key = new byte[] {1, 2, 3};
      bucket.createMainLeafEntry(0, key,
          new com.jetbrains.youtrackdb.internal.core.id.RecordId(1, 10L), 1L);

      var validator = new IndexEngineValidatorIncrement<String>(bucket, 0);
      var k = new MultiValueEntry(1L, 2, 3L);
      Byte v = (byte) 1;

      // ov==null → increment entriesCount (1→2) and return v
      var result = validator.validate(k, null, v);
      Assert.assertEquals("new-key path must return the provided value", v, result);

      // Confirm entriesCount was incremented: decrement from 2 → 1 returns false (not at 0)
      boolean droppedToZero = bucket.decrementEntriesCount(0);
      Assert.assertFalse(
          "entriesCount must be 2 after increment (decrement to 1 must not signal zero)",
          droppedToZero);
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * When ov is not null (existing key), validate must return IGNORE and NOT increment.
   *
   * <p>Verification strategy: call decrementEntriesCount after the validate; it returns
   * {@code true} when the count drops to 0. Since the entry starts at entriesCount=1,
   * after validate(k, ov≠null, v) entriesCount remains 1, so decrementEntriesCount returns true.
   */
  @Test
  public void testValidatorIncrement_existingKey_returnsIgnore() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new CellBTreeMultiValueV2Bucket<String>(entry);
      bucket.init(true);
      var key = new byte[] {1, 2, 3};
      bucket.createMainLeafEntry(0, key,
          new com.jetbrains.youtrackdb.internal.core.id.RecordId(1, 10L), 1L);

      var validator = new IndexEngineValidatorIncrement<String>(bucket, 0);
      var k = new MultiValueEntry(1L, 2, 3L);
      Byte ov = (byte) 0; // existing value
      Byte v = (byte) 1;

      var result = validator.validate(k, ov, v);
      Assert.assertSame("existing-key path must return IGNORE",
          com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.IGNORE, result);

      // Confirm entriesCount was NOT incremented: decrement from 1 → 0 returns true
      boolean droppedToZero = bucket.decrementEntriesCount(0);
      Assert.assertTrue(
          "entriesCount must remain 1 after IGNORE (decrement to 0 must signal zero)",
          droppedToZero);
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // IndexEngineValidatorNullIncrement — validate logic
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * When ov is null (new null-key entry), validate must call nullBucket.incrementSize()
   * and return the provided value.
   */
  @Test
  public void testValidatorNullIncrement_newKey_incrementsAndReturnsValue() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var nullBucket = new CellBTreeMultiValueV2NullBucket(entry);
      nullBucket.init(1L);
      // Add a RID to nullBucket so size starts at 1
      nullBucket.addValue(new com.jetbrains.youtrackdb.internal.core.id.RecordId(2, 20L));

      var validator = new IndexEngineValidatorNullIncrement(nullBucket);
      var k = new MultiValueEntry(1L, 0, 0L);
      Byte v = (byte) 1;

      var result = validator.validate(k, null, v);
      Assert.assertEquals("new null-key path must return the provided value", v, result);
      Assert.assertEquals("size must be incremented from 1 to 2", 2, nullBucket.getSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * When ov is not null, validate must return IGNORE and NOT increment null bucket size.
   */
  @Test
  public void testValidatorNullIncrement_existingKey_returnsIgnore() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var nullBucket = new CellBTreeMultiValueV2NullBucket(entry);
      nullBucket.init(1L);
      nullBucket.addValue(new com.jetbrains.youtrackdb.internal.core.id.RecordId(2, 20L));

      var validator = new IndexEngineValidatorNullIncrement(nullBucket);
      var k = new MultiValueEntry(1L, 0, 0L);
      Byte ov = (byte) 0;
      Byte v = (byte) 1;

      var result = validator.validate(k, ov, v);
      Assert.assertSame("existing-key path must return IGNORE",
          com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.IGNORE, result);
      Assert.assertEquals("size must remain 1 after IGNORE", 1, nullBucket.getSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }
}
