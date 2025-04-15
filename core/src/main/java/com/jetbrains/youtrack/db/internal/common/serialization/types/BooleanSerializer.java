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

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

/**
 * Serializer for boolean type .
 *
 * @since 18.01.12
 */
public class BooleanSerializer implements BinarySerializer<Boolean> {

  /**
   * size of boolean value in bytes
   */
  public static final int BOOLEAN_SIZE = 1;

  public static final byte ID = 1;
  public static final BooleanSerializer INSTANCE = new BooleanSerializer();

  public int getObjectSize(BinarySerializerFactory serializerFactory, Boolean object,
      Object... hints) {
    return BOOLEAN_SIZE;
  }

  public void serialize(
      final Boolean object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    stream[startPosition] = object ? (byte) 1 : (byte) 0;
  }

  public void serializeLiteral(final boolean value, final byte[] stream, final int startPosition) {
    stream[startPosition] = value ? (byte) 1 : (byte) 0;
  }

  public Boolean deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return stream[startPosition] == 1;
  }

  public boolean deserializeLiteral(final byte[] stream, final int startPosition) {
    return stream[startPosition] == 1;
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return BOOLEAN_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return BOOLEAN_SIZE;
  }

  @Override
  public void serializeNativeObject(
      final Boolean object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    serialize(object, serializerFactory, stream, startPosition);
  }

  public void serializeNative(final boolean object, final byte[] stream, final int startPosition) {
    serializeLiteral(object, stream, startPosition);
  }

  @Override
  public Boolean deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    return deserialize(serializerFactory, stream, startPosition);
  }

  public boolean deserializeNative(final byte[] stream, final int startPosition) {
    return deserializeLiteral(stream, startPosition);
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return BOOLEAN_SIZE;
  }

  @Override
  public Boolean preprocess(BinarySerializerFactory serializerFactory, final Boolean value,
      final Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Boolean object,
      ByteBuffer buffer, Object... hints) {
    buffer.put(object ? (byte) 1 : (byte) 0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Boolean deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.get() > 0;
  }

  @Override
  public Boolean deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return buffer.get(offset) > 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return BOOLEAN_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return BOOLEAN_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Boolean deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return walChanges.getByteValue(buffer, offset) > 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return BOOLEAN_SIZE;
  }
}
