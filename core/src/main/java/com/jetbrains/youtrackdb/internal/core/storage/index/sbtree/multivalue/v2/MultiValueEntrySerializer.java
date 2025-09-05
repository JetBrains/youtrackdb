package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public final class MultiValueEntrySerializer implements BinarySerializer<MultiValueEntry> {

  public static final int ID = 27;
  public static final MultiValueEntrySerializer INSTANCE = new MultiValueEntrySerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final MultiValueEntry object,
      final Object... hints) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public void serialize(
      final MultiValueEntry object,
      BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition,
      final Object... hints) {
    var pos = startPosition;
    LongSerializer.INSTANCE.serialize(object.id, serializerFactory, stream, pos);
    pos += LongSerializer.LONG_SIZE;

    ShortSerializer.INSTANCE.serialize((short) object.collectionId, serializerFactory, stream, pos);
    pos += ShortSerializer.SHORT_SIZE;

    LongSerializer.INSTANCE.serialize(object.collectionPosition, serializerFactory, stream, pos);
  }

  @Override
  public MultiValueEntry deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    var pos = startPosition;
    final long id = LongSerializer.INSTANCE.deserialize(serializerFactory, stream, pos);
    pos += LongSerializer.LONG_SIZE;

    final int collectionId = ShortSerializer.INSTANCE.deserialize(serializerFactory, stream, pos);
    pos += ShortSerializer.SHORT_SIZE;

    final long collectionPosition = LongSerializer.INSTANCE.deserialize(serializerFactory, stream,
        pos);
    return new MultiValueEntry(id, collectionId, collectionPosition);
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public boolean isFixedLength() {
    return true;
  }

  @Override
  public int getFixedLength() {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public void serializeNativeObject(
      final MultiValueEntry object,
      BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition,
      final Object... hints) {
    var pos = startPosition;
    LongSerializer.INSTANCE.serializeNative(object.id, stream, pos);
    pos += LongSerializer.LONG_SIZE;

    ShortSerializer.INSTANCE.serializeNative((short) object.collectionId, stream, pos);
    pos += ShortSerializer.SHORT_SIZE;

    LongSerializer.INSTANCE.serializeNative(object.collectionPosition, stream, pos);
  }

  @Override
  public MultiValueEntry deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    var pos = startPosition;
    final var id = LongSerializer.INSTANCE.deserializeNative(stream, pos);
    pos += LongSerializer.LONG_SIZE;

    final int collectionId = ShortSerializer.INSTANCE.deserializeNative(stream, pos);
    pos += ShortSerializer.SHORT_SIZE;

    final var collectionPosition = LongSerializer.INSTANCE.deserializeNative(stream, pos);
    return new MultiValueEntry(id, collectionId, collectionPosition);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public MultiValueEntry preprocess(BinarySerializerFactory serializerFactory,
      final MultiValueEntry value, final Object... hints) {
    return value;
  }

  @Override
  public void serializeInByteBufferObject(
      BinarySerializerFactory serializerFactory, final MultiValueEntry object,
      final ByteBuffer buffer, final Object... hints) {
    buffer.putLong(object.id);
    buffer.putShort((short) object.collectionId);
    buffer.putLong(object.collectionPosition);
  }

  @Override
  public MultiValueEntry deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      final ByteBuffer buffer) {
    final var id = buffer.getLong();
    final int collectionId = buffer.getShort();
    final var collectionPosition = buffer.getLong();

    return new MultiValueEntry(id, collectionId, collectionPosition);
  }

  @Override
  public MultiValueEntry deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final var id = buffer.getLong(offset);
    offset += Long.BYTES;

    final int collectionId = buffer.getShort(offset);
    offset += Short.BYTES;

    final var collectionPosition = buffer.getLong(offset);

    return new MultiValueEntry(id, collectionId, collectionPosition);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      final ByteBuffer buffer) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public MultiValueEntry deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, final ByteBuffer buffer,
      final WALChanges walChanges, final int offset) {
    var position = offset;

    final var id = walChanges.getLongValue(buffer, position);
    position += LongSerializer.LONG_SIZE;

    final int collectionId = walChanges.getShortValue(buffer, position);
    position += ShortSerializer.SHORT_SIZE;

    final var collectionPosition = walChanges.getLongValue(buffer, position);

    return new MultiValueEntry(id, collectionId, collectionPosition);
  }

  @Override
  public int getObjectSizeInByteBuffer(
      final ByteBuffer buffer, final WALChanges walChanges, final int offset) {
    return 2 * LongSerializer.LONG_SIZE + ShortSerializer.SHORT_SIZE;
  }
}
