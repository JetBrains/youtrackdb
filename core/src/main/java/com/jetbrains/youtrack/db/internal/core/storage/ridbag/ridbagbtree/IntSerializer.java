package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public final class IntSerializer implements BinarySerializer<Integer> {

  public static final IntSerializer INSTANCE = new IntSerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, Integer object,
      Object... hints) {
    int value = object;

    final var zeroBits = Integer.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 4 - (zeroBits - zerosTillFullByte) / 8;

    return numberSize + 1;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return stream[startPosition] + 1;
  }

  @Override
  public void serialize(Integer object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    serializePrimitive(stream, startPosition, object);
  }

  @Override
  public Integer deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doDeserialize(stream, startPosition);
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
      Integer object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    serializePrimitive(stream, startPosition, object);
  }

  @Override
  public Integer deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return doDeserialize(stream, startPosition);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return stream[startPosition] + 1;
  }

  @Override
  public Integer preprocess(BinarySerializerFactory serializerFactory, final Integer value,
      Object... hints) {
    return value;
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Integer object,
      ByteBuffer buffer, Object... hints) {
    int value = object;

    final var zeroBits = Integer.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 4 - (zeroBits - zerosTillFullByte) / 8;

    buffer.put((byte) numberSize);

    for (var i = 0; i < numberSize; i++) {
      buffer.put((byte) ((0xFF) & value));
      value = value >>> 8;
    }
  }

  @Override
  public Integer deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final int numberSize = buffer.get();

    var value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFF & buffer.get()) << (i * 8));
    }

    return value;
  }

  @Override
  public Integer deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final int numberSize = buffer.get(offset);
    offset++;

    var value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFF & buffer.get(offset)) << (i * 8));
      offset++;
    }

    return value;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.get() + 1;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.get(offset) + 1;
  }

  @Override
  public Integer deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final int numberSize = walChanges.getByteValue(buffer, offset);
    offset++;

    var value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFF & walChanges.getByteValue(buffer, offset)) << (i * 8));
      offset++;
    }

    return value;
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return walChanges.getByteValue(buffer, offset) + 1;
  }

  public int serializePrimitive(final byte[] stream, int startPosition, int value) {
    final var zeroBits = Integer.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 4 - (zeroBits - zerosTillFullByte) / 8;

    stream[startPosition] = (byte) numberSize;
    startPosition++;

    for (var i = 0; i < numberSize; i++) {
      stream[startPosition + i] = (byte) ((0xFF) & value);
      value = value >>> 8;
    }

    return startPosition + numberSize;
  }

  int doDeserialize(final byte[] stream, int startPosition) {
    final int numberSize = stream[startPosition];
    startPosition++;

    var value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFF & stream[startPosition + i]) << (i * 8));
    }

    return value;
  }
}
