/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.common.serialization.types;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Serializer for byte arrays .
 *
 * @since 20.01.12
 */
public class BinaryTypeSerializer implements BinarySerializer<byte[]> {

  public static final BinaryTypeSerializer INSTANCE = new BinaryTypeSerializer();
  public static final byte ID = 17;

  public int getObjectSize(int length) {
    return length + IntegerSerializer.INT_SIZE;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] object,
      Object... hints) {
    return object.length + IntegerSerializer.INT_SIZE;
  }

  public static int getObjectSizeStatic(byte[] object) {
    return object.length + IntegerSerializer.INT_SIZE;
  }

  @Override
  public void serialize(
      final byte[] object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    var len = object.length;
    IntegerSerializer.serializeLiteral(len, stream, startPosition);
    System.arraycopy(object, 0, stream, startPosition + IntegerSerializer.INT_SIZE, len);
  }

  public static void serializeStatic(
      final byte[] object, final byte[] stream, final int startPosition) {
    var len = object.length;
    IntegerSerializer.serializeLiteral(len, stream, startPosition);
    System.arraycopy(object, 0, stream, startPosition + IntegerSerializer.INT_SIZE, len);
  }

  @Override
  public byte[] deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    final var len = IntegerSerializer.deserializeLiteral(stream, startPosition);
    return Arrays.copyOfRange(
        stream,
        startPosition + IntegerSerializer.INT_SIZE,
        startPosition + IntegerSerializer.INT_SIZE + len);
  }

  public static byte[] deserializeStatic(final byte[] stream, final int startPosition) {
    final var len = IntegerSerializer.deserializeLiteral(stream, startPosition);
    return Arrays.copyOfRange(
        stream,
        startPosition + IntegerSerializer.INT_SIZE,
        startPosition + IntegerSerializer.INT_SIZE + len);
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return IntegerSerializer.deserializeLiteral(stream, startPosition)
        + IntegerSerializer.INT_SIZE;
  }

  public static int getObjectSizeStatic(final byte[] stream, final int startPosition) {
    return IntegerSerializer.deserializeLiteral(stream, startPosition)
        + IntegerSerializer.INT_SIZE;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return IntegerSerializer.deserializeNative(stream, startPosition)
        + IntegerSerializer.INT_SIZE;
  }

  @Override
  public void serializeNativeObject(
      byte[] object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    final var len = object.length;
    IntegerSerializer.serializeNative(len, stream, startPosition);
    System.arraycopy(object, 0, stream, startPosition + IntegerSerializer.INT_SIZE, len);
  }

  @Override
  public byte[] deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var len = IntegerSerializer.deserializeNative(stream, startPosition);
    return Arrays.copyOfRange(
        stream,
        startPosition + IntegerSerializer.INT_SIZE,
        startPosition + IntegerSerializer.INT_SIZE + len);
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
  public byte[] preprocess(BinarySerializerFactory serializerFactory, byte[] value,
      Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, byte[] object,
      ByteBuffer buffer, Object... hints) {
    final var len = object.length;
    buffer.putInt(len);
    buffer.put(object);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var len = buffer.getInt();
    final var result = new byte[len];
    buffer.get(result);
    return result;
  }

  @Override
  public byte[] deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final var len = buffer.getInt(offset);
    offset += Integer.BYTES;

    final var result = new byte[len];
    buffer.get(offset, result);

    return result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.getInt() + IntegerSerializer.INT_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.getInt(offset) + IntegerSerializer.INT_SIZE;
  }

  /**
   * Unsigned lexicographic comparison of two serialized byte arrays without deserialization.
   * Format: 4-byte native int length prefix followed by the raw bytes.
   */
  @Override
  public int compareInByteBuffer(
      BinarySerializerFactory serializerFactory,
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    final var pageLen = buffer.getInt(bufferOffset);
    bufferOffset += Integer.BYTES;

    final var searchLen = IntegerSerializer.deserializeNative(serializedKey, keyOffset);
    keyOffset += Integer.BYTES;

    final var minLen = Math.min(pageLen, searchLen);
    for (int i = 0; i < minLen; i++) {
      final int cmp = Byte.compareUnsigned(
          buffer.get(bufferOffset + i),
          serializedKey[keyOffset + i]);
      if (cmp != 0) {
        return cmp;
      }
    }
    return Integer.compare(pageLen, searchLen);
  }

  /**
   * Unsigned lexicographic comparison of a byte[] stored in a page with WAL overlay
   * against a pre-serialized search key. Deserializes both sides since we cannot
   * read directly from the buffer when WAL changes are present.
   */
  @Override
  public int compareInByteBufferWithWALChanges(
      BinarySerializerFactory serializerFactory,
      ByteBuffer buffer, WALChanges walChanges, int pageOffset,
      byte[] serializedKey, int keyOffset) {
    byte[] pageBytes = deserializeFromByteBufferObject(
        serializerFactory, buffer, walChanges, pageOffset);

    final var searchLen = IntegerSerializer.deserializeNative(serializedKey, keyOffset);
    keyOffset += Integer.BYTES;

    final var minLen = Math.min(pageBytes.length, searchLen);
    for (int i = 0; i < minLen; i++) {
      final int cmp = Byte.compareUnsigned(
          pageBytes[i],
          serializedKey[keyOffset + i]);
      if (cmp != 0) {
        return cmp;
      }
    }
    return Integer.compare(pageBytes.length, searchLen);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final var len = walChanges.getIntValue(buffer, offset);
    offset += IntegerSerializer.INT_SIZE;
    return walChanges.getBinaryValue(buffer, offset, len);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return walChanges.getIntValue(buffer, offset) + IntegerSerializer.INT_SIZE;
  }
}
