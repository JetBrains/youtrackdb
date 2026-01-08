package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl;

import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.bytes2short;
import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.short2bytes;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public class CompactedLinkSerializer implements BinarySerializer<Identifiable> {

  public static final byte ID = 22;
  public static final CompactedLinkSerializer INSTANCE = new CompactedLinkSerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, Identifiable rid,
      Object... hints) {
    final var r = rid.getIdentity();

    var size = ShortSerializer.SHORT_SIZE + ByteSerializer.BYTE_SIZE;

    final var zeroBits = Long.numberOfLeadingZeros(r.getCollectionPosition());
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;
    size += numberSize;

    return size;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return stream[startPosition + ShortSerializer.SHORT_SIZE]
        + ByteSerializer.BYTE_SIZE
        + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public void serialize(Identifiable rid, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var r = rid.getIdentity();

    final var zeroBits = Long.numberOfLeadingZeros(r.getCollectionPosition());
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;

    short2bytes((short) r.getCollectionId(), stream, startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    stream[startPosition] = (byte) numberSize;
    startPosition++;

    var collectionPosition = r.getCollectionPosition();
    for (var i = 0; i < numberSize; i++) {
      stream[startPosition + i] = (byte) ((0xFF) & collectionPosition);
      collectionPosition = collectionPosition >>> 8;
    }
  }

  @Override
  public Identifiable deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final int collection = bytes2short(stream, startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    final int numberSize = stream[startPosition];
    startPosition++;

    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & stream[startPosition + i]) << (i * 8));
    }

    return new RecordId(collection, position);
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public boolean isFixedLength() {
    return false;
  }

  @Override
  public int getFixedLength() {
    return 0;
  }

  @Override
  public void serializeNativeObject(
      Identifiable rid, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    final var r = rid.getIdentity();

    ShortSerializer.INSTANCE.serializeNative((short) r.getCollectionId(), stream, startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    final var zeroBits = Long.numberOfLeadingZeros(r.getCollectionPosition());
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;

    stream[startPosition] = (byte) numberSize;
    startPosition++;

    var collectionPosition = r.getCollectionPosition();
    for (var i = 0; i < numberSize; i++) {
      stream[startPosition + i] = (byte) ((0xFF) & collectionPosition);
      collectionPosition = collectionPosition >>> 8;
    }
  }

  @Override
  public Identifiable deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    final int collection = ShortSerializer.INSTANCE.deserializeNativeObject(serializerFactory, stream,
        startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    final int numberSize = stream[startPosition];
    startPosition++;

    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & stream[startPosition + i]) << (i * 8));
    }

    return new RecordId(collection, position);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return stream[startPosition + ShortSerializer.SHORT_SIZE]
        + ByteSerializer.BYTE_SIZE
        + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public Identifiable preprocess(BinarySerializerFactory serializerFactory, Identifiable value,
      Object... hints) {
    return value.getIdentity();
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory,
      Identifiable rid, ByteBuffer buffer, Object... hints) {
    final var r = rid.getIdentity();
    buffer.putShort((short) r.getCollectionId());

    final var zeroBits = Long.numberOfLeadingZeros(r.getCollectionPosition());
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;

    buffer.put((byte) numberSize);

    final var number = new byte[numberSize];

    var collectionPosition = r.getCollectionPosition();
    for (var i = 0; i < numberSize; i++) {
      number[i] = (byte) ((0xFF) & collectionPosition);
      collectionPosition = collectionPosition >>> 8;
    }

    buffer.put(number);
  }

  @Override
  public Identifiable deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final int collection = buffer.getShort();

    final int numberSize = buffer.get();
    final var number = new byte[numberSize];
    buffer.get(number);

    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & number[i]) << (i * 8));
    }

    return new RecordId(collection, position);
  }

  @Override
  public Identifiable deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final int collection = buffer.getShort(offset);
    offset += Short.BYTES;

    final int numberSize = buffer.get(offset);
    offset += Byte.BYTES;

    final var number = new byte[numberSize];
    buffer.get(offset, number);

    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & number[i]) << (i * 8));
    }

    return new RecordId(collection, position);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.get(buffer.position() + ShortSerializer.SHORT_SIZE)
        + ByteSerializer.BYTE_SIZE
        + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.get(offset + ShortSerializer.SHORT_SIZE)
        + ByteSerializer.BYTE_SIZE
        + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public Identifiable deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final int collection = walChanges.getShortValue(buffer, offset);
    offset += ShortSerializer.SHORT_SIZE;

    final int numberSize = walChanges.getByteValue(buffer, offset);
    offset++;

    final var number = walChanges.getBinaryValue(buffer, offset, numberSize);

    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & number[i]) << (i * 8));
    }

    return new RecordId(collection, position);
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return walChanges.getByteValue(buffer, offset + ShortSerializer.SHORT_SIZE)
        + ByteSerializer.BYTE_SIZE
        + ShortSerializer.SHORT_SIZE;
  }
}
