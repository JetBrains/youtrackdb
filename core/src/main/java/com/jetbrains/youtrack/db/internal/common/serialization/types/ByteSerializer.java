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
 * Serializer for byte type .
 *
 * @since 18.01.12
 */
public class ByteSerializer implements BinarySerializer<Byte> {

  /**
   * size of byte value in bytes
   */
  public static final int BYTE_SIZE = 1;

  public static final byte ID = 2;
  public static final ByteSerializer INSTANCE = new ByteSerializer();

  public int getObjectSize(BinarySerializerFactory serializerFactory, Byte object,
      Object... hints) {
    return BYTE_SIZE;
  }

  public void serialize(
      final Byte object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    stream[startPosition] = object;
  }

  public void serializeLiteral(final byte value, final byte[] stream, final int startPosition) {
    stream[startPosition] = value;
  }

  public Byte deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return stream[startPosition];
  }

  public byte deserializeLiteral(final byte[] stream, final int startPosition) {
    return stream[startPosition];
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return BYTE_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return getObjectSize(serializerFactory, stream, startPosition);
  }

  @Override
  public void serializeNativeObject(
      final Byte object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    serialize(object, serializerFactory, stream, startPosition);
  }

  public void serializeNative(byte object, byte[] stream, int startPosition) {
    serializeLiteral(object, stream, startPosition);
  }

  @Override
  public Byte deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    return stream[startPosition];
  }

  public byte deserializeNative(final byte[] stream, final int startPosition) {
    return stream[startPosition];
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return BYTE_SIZE;
  }

  @Override
  public Byte preprocess(BinarySerializerFactory serializerFactory, Byte value, Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Byte object,
      ByteBuffer buffer, Object... hints) {
    buffer.put(object);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Byte deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.get();
  }

  @Override
  public Byte deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.get(offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return BYTE_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return BYTE_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Byte deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return walChanges.getByteValue(buffer, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return BYTE_SIZE;
  }
}
