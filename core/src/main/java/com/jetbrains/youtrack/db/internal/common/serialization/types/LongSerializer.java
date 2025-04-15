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

package com.jetbrains.youtrack.db.internal.common.serialization.types;

import com.jetbrains.youtrack.db.internal.common.serialization.BinaryConverter;
import com.jetbrains.youtrack.db.internal.common.serialization.BinaryConverterFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serializer for {@link Long} type.
 *
 * @since 18.01.12
 */
public class LongSerializer implements BinarySerializer<Long> {

  public static final byte ID = 10;

  /**
   * size of long value in bytes
   */
  public static final int LONG_SIZE = 8;

  private static final BinaryConverter CONVERTER = BinaryConverterFactory.getConverter();
  public static final LongSerializer INSTANCE = new LongSerializer();

  public int getObjectSize(BinarySerializerFactory serializerFactory, final Long object,
      final Object... hints) {
    return LONG_SIZE;
  }

  public void serialize(
      final Long object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    serializeLiteral(object, stream, startPosition);
  }

  public static void serializeLiteral(final long value, final byte[] stream,
      final int startPosition) {
    stream[startPosition] = (byte) ((value >>> 56) & 0xFF);
    stream[startPosition + 1] = (byte) ((value >>> 48) & 0xFF);
    stream[startPosition + 2] = (byte) ((value >>> 40) & 0xFF);
    stream[startPosition + 3] = (byte) ((value >>> 32) & 0xFF);
    stream[startPosition + 4] = (byte) ((value >>> 24) & 0xFF);
    stream[startPosition + 5] = (byte) ((value >>> 16) & 0xFF);
    stream[startPosition + 6] = (byte) ((value >>> 8) & 0xFF);
    stream[startPosition + 7] = (byte) ((value) & 0xFF);
  }

  public Long deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return deserializeLiteral(stream, startPosition);
  }

  public static long deserializeLiteral(final byte[] stream, final int startPosition) {
    return ((0xff & stream[startPosition + 7])
        | (0xff & stream[startPosition + 6]) << 8
        | (0xff & stream[startPosition + 5]) << 16
        | (long) (0xff & stream[startPosition + 4]) << 24
        | (long) (0xff & stream[startPosition + 3]) << 32
        | (long) (0xff & stream[startPosition + 2]) << 40
        | (long) (0xff & stream[startPosition + 1]) << 48
        | (long) (0xff & stream[startPosition]) << 56);
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return LONG_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return LONG_SIZE;
  }

  @Override
  public void serializeNativeObject(
      final Long object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    checkBoundaries(stream, startPosition);

    CONVERTER.putLong(stream, startPosition, object, ByteOrder.nativeOrder());
  }

  @Override
  public Long deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    return CONVERTER.getLong(stream, startPosition, ByteOrder.nativeOrder());
  }

  public void serializeNative(final long object, final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    CONVERTER.putLong(stream, startPosition, object, ByteOrder.nativeOrder());
  }

  public long deserializeNative(final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    return CONVERTER.getLong(stream, startPosition, ByteOrder.nativeOrder());
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return LONG_SIZE;
  }

  @Override
  public Long preprocess(BinarySerializerFactory serializerFactory, final Long value,
      final Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Long object,
      ByteBuffer buffer, Object... hints) {
    buffer.putLong(object);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Long deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.getLong();
  }

  @Override
  public Long deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.getLong(offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return LONG_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return LONG_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Long deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return walChanges.getLongValue(buffer, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return LONG_SIZE;
  }

  private static void checkBoundaries(byte[] stream, int startPosition) {
    if (startPosition + LONG_SIZE > stream.length) {
      throw new IllegalStateException(
          "Requested stream size is "
              + (startPosition + LONG_SIZE)
              + " but provided stream has size "
              + stream.length);
    }
  }
}
