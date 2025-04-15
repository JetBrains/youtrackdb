package com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
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
}
