package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class CompactedLinkSerializerTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testSerializeOneByte() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeOneByte() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeTwoBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeThreeBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeOneByteByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeOneByteByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytesByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytesByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytesInByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytesInByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testIdAndVariableLengthContract() {
    // ID 22 is the dispatch byte registered with BinarySerializerFactory. The serializer is
    // variable-length because the encoded payload is 1, 2, 3, ... 8 bytes depending on
    // log-compaction of the collection-position. Pin both contract bits.
    Assert.assertEquals((byte) 22, CompactedLinkSerializer.ID);
    Assert.assertEquals((byte) 22, new CompactedLinkSerializer().getId());
    Assert.assertFalse(new CompactedLinkSerializer().isFixedLength());
    Assert.assertEquals(0, new CompactedLinkSerializer().getFixedLength());
  }

  @Test
  public void testInstanceSingleton() {
    // The INSTANCE singleton is what BinarySerializerFactory registers at startup; SBTree
    // pages reach this serializer through the factory id-lookup, not through `new`.
    Assert.assertNotNull(CompactedLinkSerializer.INSTANCE);
    Assert.assertSame(CompactedLinkSerializer.INSTANCE, CompactedLinkSerializer.INSTANCE);
  }

  @Test
  public void testPreprocessExtractsIdentity() {
    // preprocess returns value.getIdentity() unconditionally — there is no null guard. Pin
    // the identity-extraction behaviour for a RID input (which returns itself as identity).
    final var linkSerializer = new CompactedLinkSerializer();
    final var rid = new RecordId(5, 5);
    Assert.assertEquals(rid, linkSerializer.preprocess(serializerFactory, rid));
  }

  @Test
  public void testWalOverlayDeserialiseRoundTripsCompactedRid() {
    // The WAL deserialise variant reads through a WALPageChangesPortion overlay — pin a
    // round-trip through the overlay so the compacted-byte unmarshalling on the WAL path
    // matches the in-memory decode.
    final var linkSerializer = new CompactedLinkSerializer();
    final var rid = new RecordId(42, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var offset = 4;
    final var buffer = ByteBuffer
        .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
        .order(java.nio.ByteOrder.nativeOrder());
    final var data = new byte[size];
    linkSerializer.serializeNativeObject(rid, serializerFactory, data, 0);

    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(buffer, data, offset);

    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(buffer, overlay, offset));
    Assert.assertEquals(rid,
        linkSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, overlay,
            offset));
  }
}
