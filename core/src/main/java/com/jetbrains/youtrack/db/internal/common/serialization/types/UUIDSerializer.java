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
import java.util.UUID;

/**
 *
 */
public class UUIDSerializer implements BinarySerializer<UUID> {

  public static final UUIDSerializer INSTANCE = new UUIDSerializer();
  public static final int UUID_SIZE = 2 * LongSerializer.LONG_SIZE;

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, UUID object,
      Object... hints) {
    return UUID_SIZE;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return UUID_SIZE;
  }

  @Override
  public void serialize(
      final UUID object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    staticSerialize(object, stream, startPosition);
  }


  public static void staticSerialize(final UUID object, final byte[] stream,
      final int startPosition) {
    LongSerializer.serializeLiteral(
        object.getMostSignificantBits(), stream, startPosition);
    LongSerializer.serializeLiteral(
        object.getLeastSignificantBits(), stream, startPosition + LongSerializer.LONG_SIZE);
  }

  @Override
  public UUID deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return staticDeserialize(stream, startPosition);
  }

  public static UUID staticDeserialize(byte[] stream, int startPosition) {
    final var mostSignificantBits =
        LongSerializer.deserializeLiteral(stream, startPosition);
    final var leastSignificantBits =
        LongSerializer.deserializeLiteral(
            stream, startPosition + LongSerializer.LONG_SIZE);
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  @Override
  public byte getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isFixedLength() {
    return LongSerializer.INSTANCE.isFixedLength();
  }

  @Override
  public int getFixedLength() {
    return UUID_SIZE;
  }

  @Override
  public void serializeNativeObject(
      final UUID object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, final Object... hints) {
    LongSerializer.INSTANCE.serializeNative(
        object.getMostSignificantBits(), stream, startPosition);
    LongSerializer.INSTANCE.serializeNative(
        object.getLeastSignificantBits(), stream, startPosition + LongSerializer.LONG_SIZE);
  }

  @Override
  public UUID deserializeNativeObject(BinarySerializerFactory serializerFactory,
      final byte[] stream, final int startPosition) {
    final var mostSignificantBits =
        LongSerializer.INSTANCE.deserializeNative(stream, startPosition);
    final var leastSignificantBits =
        LongSerializer.INSTANCE.deserializeNative(
            stream, startPosition + LongSerializer.LONG_SIZE);
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return UUID_SIZE;
  }

  @Override
  public UUID preprocess(BinarySerializerFactory serializerFactory, UUID value, Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, UUID object,
      ByteBuffer buffer, Object... hints) {
    buffer.putLong(object.getMostSignificantBits());
    buffer.putLong(object.getLeastSignificantBits());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public UUID deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var mostSignificantBits = buffer.getLong();
    final var leastSignificantBits = buffer.getLong();
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  @Override
  public UUID deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final var mostSignificantBits = buffer.getLong(offset);
    offset += Long.BYTES;

    final var leastSignificantBits = buffer.getLong(offset);

    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return UUID_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return UUID_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public UUID deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final var mostSignificantBits = walChanges.getLongValue(buffer, offset);
    final var leastSignificantBits =
        walChanges.getLongValue(buffer, offset + LongSerializer.LONG_SIZE);
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return UUID_SIZE;
  }
}
