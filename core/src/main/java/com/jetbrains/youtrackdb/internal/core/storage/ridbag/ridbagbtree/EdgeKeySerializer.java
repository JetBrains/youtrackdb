package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public final class EdgeKeySerializer implements BinarySerializer<EdgeKey> {

  static final EdgeKeySerializer INSTANCE = new EdgeKeySerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, EdgeKey object,
      Object... hints) {
    return LongSerializer.getObjectSize(object.ridBagId())
        + IntSerializer.INSTANCE.getObjectSize(serializerFactory, object.targetCollection())
        + LongSerializer.getObjectSize(object.targetPosition());
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doGetObjectSize(stream, startPosition, serializerFactory);
  }

  private int doGetObjectSize(byte[] stream, int startPosition,
      BinarySerializerFactory serializerFactory) {
    var size = LongSerializer.getObjectSize(stream, startPosition);
    size += IntSerializer.INSTANCE.getObjectSize(serializerFactory, stream, startPosition);
    return size + LongSerializer.getObjectSize(stream, startPosition + size);
  }

  @Override
  public void serialize(EdgeKey object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    doSerialize(object, stream, startPosition);
  }

  private void doSerialize(EdgeKey object, byte[] stream, int startPosition) {
    startPosition = LongSerializer.serialize(object.ridBagId(), stream, startPosition);
    startPosition =
        IntSerializer.INSTANCE.serializePrimitive(stream, startPosition, object.targetCollection());
    LongSerializer.serialize(object.targetPosition(), stream, startPosition);
  }

  @Override
  public EdgeKey deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doDeserialize(stream, startPosition, serializerFactory);
  }

  private EdgeKey doDeserialize(byte[] stream, int startPosition,
      BinarySerializerFactory serializerFactory) {
    var ownerId = LongSerializer.deserialize(stream, startPosition);
    var size = LongSerializer.getObjectSize(stream, startPosition);
    startPosition += size;

    final int targetCollection = IntSerializer.INSTANCE.deserialize(serializerFactory, stream,
        startPosition);
    size = IntSerializer.INSTANCE.getObjectSize(serializerFactory, stream, startPosition);
    startPosition += size;

    final var targetPosition = LongSerializer.deserialize(stream, startPosition);
    return new EdgeKey(ownerId, targetCollection, targetPosition);
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
  public void serializeNativeObject(
      EdgeKey object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    doSerialize(object, stream, startPosition);
  }

  @Override
  public EdgeKey deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doDeserialize(stream, startPosition, serializerFactory);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doGetObjectSize(stream, startPosition, serializerFactory);
  }

  @Override
  public EdgeKey preprocess(BinarySerializerFactory serializerFactory, EdgeKey value,
      Object... hints) {
    return value;
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, EdgeKey object,
      ByteBuffer buffer, Object... hints) {
    LongSerializer.serialize(object.ridBagId(), buffer);
    IntSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, object.targetCollection(),
        buffer);
    LongSerializer.serialize(object.targetPosition(), buffer);
  }

  @Override
  public EdgeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var ownerId = LongSerializer.deserialize(buffer);
    final int targetCollection = IntSerializer.INSTANCE.deserializeFromByteBufferObject(
        serializerFactory, buffer);
    final var targetPosition = LongSerializer.deserialize(buffer);

    return new EdgeKey(ownerId, targetCollection, targetPosition);
  }

  @Override
  public EdgeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    var delta = LongSerializer.getObjectSize(buffer, offset);
    final var ownerId = LongSerializer.deserialize(buffer, offset);
    offset += delta;

    delta = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    final int targetCollection =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset, buffer);
    offset += delta;

    final var targetPosition = LongSerializer.deserialize(buffer, offset);
    return new EdgeKey(ownerId, targetCollection, targetPosition);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var position = buffer.position();
    var size = LongSerializer.getObjectSize(buffer);
    buffer.position(position + size);

    size += IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer);
    buffer.position(position + size);

    return size + LongSerializer.getObjectSize(buffer);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final var position = offset;
    var size = LongSerializer.getObjectSize(buffer, offset);
    offset = position + size;

    size += IntSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    offset = position + size;

    return size + LongSerializer.getObjectSize(buffer, offset);
  }

  @Override
  public EdgeKey deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    var ownerId = LongSerializer.deserialize(buffer, walChanges, offset);
    var size = LongSerializer.getObjectSize(buffer, walChanges, offset);
    offset += size;

    size = IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
    final int targetCollection =
        IntSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
            walChanges, offset);
    offset += size;

    final var targetPosition = LongSerializer.deserialize(buffer, walChanges, offset);

    return new EdgeKey(ownerId, targetCollection, targetPosition);
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    var size = LongSerializer.getObjectSize(buffer, walChanges, offset);
    size += IntSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset + size);
    return size + LongSerializer.getObjectSize(buffer, walChanges, offset + size);
  }
}
