package com.jetbrains.youtrackdb.internal.common.serialization.types;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class UTF8Serializer implements BinarySerializer<String> {

  private static final int INT_MASK = 0xFFFF;

  public static final UTF8Serializer INSTANCE = new UTF8Serializer();
  public static final byte ID = 25;

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, String object,
      Object... hints) {
    final var encoded = object.getBytes(StandardCharsets.UTF_8);
    return ShortSerializer.SHORT_SIZE + encoded.length;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return
        (ShortSerializer.INSTANCE.deserialize(serializerFactory, stream, startPosition) & INT_MASK)
            + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public void serialize(String object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var encoded = object.getBytes(StandardCharsets.UTF_8);
    ShortSerializer.INSTANCE.serialize((short) encoded.length, serializerFactory, stream,
        startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    System.arraycopy(encoded, 0, stream, startPosition, encoded.length);
  }

  @Override
  public String deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var encodedSize =
        ShortSerializer.INSTANCE.deserialize(serializerFactory, stream, startPosition) & INT_MASK;
    startPosition += ShortSerializer.SHORT_SIZE;

    final var encoded = new byte[encodedSize];
    System.arraycopy(stream, startPosition, encoded, 0, encodedSize);
    return new String(encoded, StandardCharsets.UTF_8);
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
      String object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    final var encoded = object.getBytes(StandardCharsets.UTF_8);
    ShortSerializer.INSTANCE.serializeNative((short) encoded.length, stream, startPosition);
    startPosition += ShortSerializer.SHORT_SIZE;

    System.arraycopy(encoded, 0, stream, startPosition, encoded.length);
  }

  @Override
  public String deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var encodedSize =
        ShortSerializer.INSTANCE.deserializeNative(stream, startPosition) & INT_MASK;
    startPosition += ShortSerializer.SHORT_SIZE;

    final var encoded = new byte[encodedSize];
    System.arraycopy(stream, startPosition, encoded, 0, encodedSize);
    return new String(encoded, StandardCharsets.UTF_8);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return (ShortSerializer.INSTANCE.deserializeNative(stream, startPosition) & INT_MASK)
        + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public String preprocess(BinarySerializerFactory serializerFactory, String value,
      Object... hints) {
    return value;
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, String object,
      ByteBuffer buffer, Object... hints) {
    final var encoded = object.getBytes(StandardCharsets.UTF_8);
    buffer.putShort((short) encoded.length);

    buffer.put(encoded);
  }

  @Override
  public String deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var encodedSize = buffer.getShort() & INT_MASK;

    final var encoded = new byte[encodedSize];
    buffer.get(encoded);
    return new String(encoded, StandardCharsets.UTF_8);
  }

  @Override
  public String deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final var encodedSize = buffer.getShort(offset) & INT_MASK;
    offset += Short.BYTES;

    final var encoded = new byte[encodedSize];
    buffer.get(offset, encoded);
    return new String(encoded, StandardCharsets.UTF_8);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return (buffer.getShort() & INT_MASK) + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return (buffer.getShort(offset) & INT_MASK) + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public String deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final var encodedSize = walChanges.getShortValue(buffer, offset) & INT_MASK;
    offset += ShortSerializer.SHORT_SIZE;

    final var encoded = walChanges.getBinaryValue(buffer, offset, encodedSize);
    return new String(encoded, StandardCharsets.UTF_8);
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return (walChanges.getShortValue(buffer, offset) & INT_MASK) + ShortSerializer.SHORT_SIZE;
  }

  @Override
  public int compareInByteBuffer(
      BinarySerializerFactory serializerFactory,
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    // Read UTF-8 byte lengths from both sources
    final var pageByteLen = buffer.getShort(bufferOffset) & INT_MASK;
    final var searchByteLen =
        ShortSerializer.INSTANCE.deserializeNative(serializedKey, keyOffset) & INT_MASK;

    var pagePos = bufferOffset + ShortSerializer.SHORT_SIZE;
    var searchPos = keyOffset + ShortSerializer.SHORT_SIZE;

    final var pageEnd = pagePos + pageByteLen;
    final var searchEnd = searchPos + searchByteLen;

    // Decode one UTF-8 char at a time from each side and compare as UTF-16 code units.
    // This preserves String.compareTo() semantics.
    while (pagePos < pageEnd && searchPos < searchEnd) {
      final var pb0 = buffer.get(pagePos) & 0xFF;
      final var sb0 = serializedKey[searchPos] & 0xFF;

      final char pageChar;
      if (pb0 < 0x80) {
        pageChar = (char) pb0;
        pagePos++;
      } else if ((pb0 >> 5) == 0x06) {
        pageChar = (char) (((pb0 & 0x1F) << 6) | (buffer.get(pagePos + 1) & 0x3F));
        pagePos += 2;
      } else {
        pageChar = (char) (((pb0 & 0x0F) << 12)
            | ((buffer.get(pagePos + 1) & 0x3F) << 6)
            | (buffer.get(pagePos + 2) & 0x3F));
        pagePos += 3;
      }

      final char searchChar;
      if (sb0 < 0x80) {
        searchChar = (char) sb0;
        searchPos++;
      } else if ((sb0 >> 5) == 0x06) {
        searchChar = (char) (((sb0 & 0x1F) << 6) | (serializedKey[searchPos + 1] & 0x3F));
        searchPos += 2;
      } else {
        searchChar = (char) (((sb0 & 0x0F) << 12)
            | ((serializedKey[searchPos + 1] & 0x3F) << 6)
            | (serializedKey[searchPos + 2] & 0x3F));
        searchPos += 3;
      }

      if (pageChar != searchChar) {
        return Character.compare(pageChar, searchChar);
      }
    }

    // If one side has remaining chars, that side is greater
    final var pageRemaining = pageEnd - pagePos;
    final var searchRemaining = searchEnd - searchPos;
    return Integer.compare(pageRemaining, searchRemaining);
  }

  @Override
  public byte[] serializeNativeAsWhole(BinarySerializerFactory serializerFactory, String object,
      Object... hints) {
    final var encoded = object.getBytes(StandardCharsets.UTF_8);
    final var result = new byte[encoded.length + ShortSerializer.SHORT_SIZE];

    ShortSerializer.INSTANCE.serializeNative((short) encoded.length, result, 0);
    System.arraycopy(encoded, 0, result, ShortSerializer.SHORT_SIZE, encoded.length);
    return result;
  }
}
