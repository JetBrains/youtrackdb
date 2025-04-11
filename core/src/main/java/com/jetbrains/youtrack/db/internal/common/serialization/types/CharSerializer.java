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
 * @since 18.01.12
 */
public class CharSerializer implements BinarySerializer<Character> {

  /**
   * size of char value in bytes
   */
  public static final int CHAR_SIZE = 2;

  public static final byte ID = 3;
  private static final BinaryConverter BINARY_CONVERTER = BinaryConverterFactory.getConverter();
  public static final CharSerializer INSTANCE = new CharSerializer();

  public int getObjectSize(BinarySerializerFactory serializerFactory, final Character object,
      Object... hints) {
    return CHAR_SIZE;
  }

  public void serialize(
      final Character object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    serializeLiteral(object, stream, startPosition);
  }

  public void serializeLiteral(final char value, final byte[] stream, final int startPosition) {
    stream[startPosition] = (byte) (value >>> 8);
    stream[startPosition + 1] = (byte) (value);
  }

  public Character deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return deserializeLiteral(stream, startPosition);
  }

  public char deserializeLiteral(final byte[] stream, final int startPosition) {
    return (char) (((stream[startPosition] & 0xFF) << 8) + (stream[startPosition + 1] & 0xFF));
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return CHAR_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return CHAR_SIZE;
  }

  @Override
  public void serializeNativeObject(
      Character object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    checkBoundaries(stream, startPosition);

    BINARY_CONVERTER.putChar(stream, startPosition, object, ByteOrder.nativeOrder());
  }

  @Override
  public Character deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    return BINARY_CONVERTER.getChar(stream, startPosition, ByteOrder.nativeOrder());
  }

  public void serializeNative(final char object, final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    BINARY_CONVERTER.putChar(stream, startPosition, object, ByteOrder.nativeOrder());
  }

  public char deserializeNative(final byte[] stream, final int startPosition) {
    checkBoundaries(stream, startPosition);

    return BINARY_CONVERTER.getChar(stream, startPosition, ByteOrder.nativeOrder());
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return CHAR_SIZE;
  }

  @Override
  public Character preprocess(BinarySerializerFactory serializerFactory, final Character value,
      final Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory,
      Character object, ByteBuffer buffer, Object... hints) {
    buffer.putChar(object);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Character deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.getChar();
  }

  @Override
  public Character deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return buffer.getChar(offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return CHAR_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return CHAR_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Character deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return (char) walChanges.getShortValue(buffer, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return CHAR_SIZE;
  }

  private static void checkBoundaries(byte[] stream, int startPosition) {
    if (startPosition + CHAR_SIZE > stream.length) {
      throw new IllegalStateException(
          "Requested stream size is "
              + (startPosition + CHAR_SIZE)
              + " but provided stream has size "
              + stream.length);
    }
  }
}
