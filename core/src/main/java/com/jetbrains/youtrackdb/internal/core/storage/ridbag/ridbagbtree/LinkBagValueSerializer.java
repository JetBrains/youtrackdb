package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public final class LinkBagValueSerializer implements BinarySerializer<LinkBagValue> {

  public static final LinkBagValueSerializer INSTANCE = new LinkBagValueSerializer();

  // Tombstone is serialized as a single byte: 0 = live, 1 = tombstone
  private static final int TOMBSTONE_BYTE_SIZE = 1;

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, LinkBagValue object,
      Object... hints) {
    return IntSerializer.INSTANCE.getObjectSize(serializerFactory, object.counter())
        + IntSerializer.INSTANCE.getObjectSize(serializerFactory, object.secondaryCollectionId())
        + LongSerializer.getObjectSize(object.secondaryPosition())
        + TOMBSTONE_BYTE_SIZE;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doGetObjectSize(stream, startPosition, serializerFactory);
  }

  private int doGetObjectSize(byte[] stream, int startPosition,
      BinarySerializerFactory serializerFactory) {
    var size = IntSerializer.INSTANCE.getObjectSizeNative(serializerFactory, stream, startPosition);
    size +=
        IntSerializer.INSTANCE.getObjectSizeNative(serializerFactory, stream,
            startPosition + size);
    size += LongSerializer.getObjectSize(stream, startPosition + size);
    return size + TOMBSTONE_BYTE_SIZE;
  }

  @Override
  public void serialize(LinkBagValue object, BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition, Object... hints) {
    doSerialize(object, stream, startPosition);
  }

  private void doSerialize(LinkBagValue object, byte[] stream, int startPosition) {
    startPosition =
        IntSerializer.INSTANCE.serializePrimitive(stream, startPosition, object.counter());
    startPosition =
        IntSerializer.INSTANCE.serializePrimitive(stream, startPosition,
            object.secondaryCollectionId());
    startPosition =
        LongSerializer.serialize(object.secondaryPosition(), stream, startPosition);
    stream[startPosition] = object.tombstone() ? (byte) 1 : (byte) 0;
  }

  @Override
  public LinkBagValue deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doDeserialize(stream, startPosition, serializerFactory);
  }

  private LinkBagValue doDeserialize(byte[] stream, int startPosition,
      BinarySerializerFactory serializerFactory) {
    final int counter = IntSerializer.INSTANCE.doDeserialize(stream, startPosition);
    var size = IntSerializer.INSTANCE.getObjectSizeNative(serializerFactory, stream, startPosition);
    startPosition += size;

    final int secondaryCollectionId = IntSerializer.INSTANCE.doDeserialize(stream, startPosition);
    size = IntSerializer.INSTANCE.getObjectSizeNative(serializerFactory, stream, startPosition);
    startPosition += size;

    final long secondaryPosition = LongSerializer.deserialize(stream, startPosition);
    size = LongSerializer.getObjectSize(stream, startPosition);
    startPosition += size;

    final boolean tombstone = stream[startPosition] != 0;

    return new LinkBagValue(counter, secondaryCollectionId, secondaryPosition, tombstone);
  }

  @Override
  public byte getId() {
    return -1;
  }

  @Override
  public boolean isFixedLength() {
    return false;
  }

  @Override
  public int getFixedLength() {
    return -1;
  }

  @Override
  public void serializeNativeObject(LinkBagValue object, BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition, Object... hints) {
    doSerialize(object, stream, startPosition);
  }

  @Override
  public LinkBagValue deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    return doDeserialize(stream, startPosition, serializerFactory);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doGetObjectSize(stream, startPosition, serializerFactory);
  }

  @Override
  public LinkBagValue preprocess(BinarySerializerFactory serializerFactory, LinkBagValue value,
      Object... hints) {
    return value;
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory,
      LinkBagValue object, ByteBuffer buffer, Object... hints) {
    IntSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, object.counter(),
        buffer);
    IntSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory,
        object.secondaryCollectionId(), buffer);
    LongSerializer.serialize(object.secondaryPosition(), buffer);
    buffer.put(object.tombstone() ? (byte) 1 : (byte) 0);
  }

  @Override
  public LinkBagValue deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final int counter =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);
    final int secondaryCollectionId =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);
    final long secondaryPosition = LongSerializer.deserialize(buffer);
    final boolean tombstone = buffer.get() != 0;

    return new LinkBagValue(counter, secondaryCollectionId, secondaryPosition, tombstone);
  }

  @Override
  public LinkBagValue deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    var delta =
        IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    final int counter =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset, buffer);
    offset += delta;

    delta = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    final int secondaryCollectionId =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset, buffer);
    offset += delta;

    delta = LongSerializer.getObjectSize(buffer, offset);
    final long secondaryPosition = LongSerializer.deserialize(buffer, offset);
    offset += delta;

    final boolean tombstone = buffer.get(offset) != 0;

    return new LinkBagValue(counter, secondaryCollectionId, secondaryPosition, tombstone);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var position = buffer.position();
    var size = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer);
    buffer.position(position + size);

    size += IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer);
    buffer.position(position + size);

    size += LongSerializer.getObjectSize(buffer);

    return size + TOMBSTONE_BYTE_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final var position = offset;
    var size =
        IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    offset = position + size;

    size += IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    offset = position + size;

    size += LongSerializer.getObjectSize(buffer, offset);

    return size + TOMBSTONE_BYTE_SIZE;
  }

  @Override
  public LinkBagValue deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    var size = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
    final int counter =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
            walChanges, offset);
    offset += size;

    size = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
    final int secondaryCollectionId =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
            walChanges, offset);
    offset += size;

    size = LongSerializer.getObjectSize(buffer, walChanges, offset);
    final long secondaryPosition = LongSerializer.deserialize(buffer, walChanges, offset);
    offset += size;

    final boolean tombstone = walChanges.getByteValue(buffer, offset) != 0;

    return new LinkBagValue(counter, secondaryCollectionId, secondaryPosition, tombstone);
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    var size = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
    size +=
        IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset + size);
    size += LongSerializer.getObjectSize(buffer, walChanges, offset + size);
    return size + TOMBSTONE_BYTE_SIZE;
  }
}
