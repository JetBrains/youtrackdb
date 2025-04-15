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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * Serializer for {@link BigDecimal} type.
 *
 * @since 03.04.12
 */
public class DecimalSerializer implements BinarySerializer<BigDecimal> {

  public static final DecimalSerializer INSTANCE = new DecimalSerializer();
  public static final byte ID = 18;

  public int getObjectSize(BinarySerializerFactory serializerFactory, BigDecimal object,
      Object... hints) {
    return staticGetObjectSize(object);
  }

  public static int staticGetObjectSize(BigDecimal object) {
    return IntegerSerializer.INT_SIZE
        + BinaryTypeSerializer.getObjectSizeStatic(object.unscaledValue().toByteArray());
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return staticGetObjectSize(stream, startPosition);
  }

  public static int staticGetObjectSize(byte[] stream, int startPosition) {
    return IntegerSerializer.INT_SIZE
        + BinaryTypeSerializer.getObjectSizeStatic(
        stream, startPosition + IntegerSerializer.INT_SIZE);
  }

  public void serialize(BigDecimal object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    staticSerialize(object, stream, startPosition);
  }

  public static void staticSerialize(BigDecimal object, byte[] stream, int startPosition) {
    IntegerSerializer.serializeLiteral(object.scale(), stream, startPosition);
    startPosition += IntegerSerializer.INT_SIZE;
    BinaryTypeSerializer.serializeStatic(object.unscaledValue().toByteArray(), stream,
        startPosition);
  }

  public BigDecimal deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      int startPosition) {
    return staticDeserialize(stream, startPosition);
  }

  public static BigDecimal staticDeserialize(final byte[] stream,
      int startPosition) {
    final var scale = IntegerSerializer.deserializeLiteral(stream, startPosition);
    startPosition += IntegerSerializer.INT_SIZE;

    final var unscaledValue = BinaryTypeSerializer.deserializeStatic(stream,
        startPosition);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return IntegerSerializer.INT_SIZE
        + BinaryTypeSerializer.INSTANCE.getObjectSizeNative(
        serializerFactory, stream, startPosition + IntegerSerializer.INT_SIZE);
  }

  @Override
  public void serializeNativeObject(
      BigDecimal object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    IntegerSerializer.serializeNative(object.scale(), stream, startPosition);
    startPosition += IntegerSerializer.INT_SIZE;
    BinaryTypeSerializer.INSTANCE.serializeNativeObject(
        object.unscaledValue().toByteArray(), serializerFactory, stream, startPosition);
  }

  @Override
  public BigDecimal deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    final var scale = IntegerSerializer.deserializeNative(stream, startPosition);
    startPosition += IntegerSerializer.INT_SIZE;

    final var unscaledValue =
        BinaryTypeSerializer.INSTANCE.deserializeNativeObject(serializerFactory, stream,
            startPosition);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  public boolean isFixedLength() {
    return false;
  }

  public int getFixedLength() {
    return 0;
  }

  @Override
  public BigDecimal preprocess(BinarySerializerFactory serializerFactory, BigDecimal value,
      Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory,
      BigDecimal object, ByteBuffer buffer, Object... hints) {
    buffer.putInt(object.scale());
    BinaryTypeSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory,
        object.unscaledValue().toByteArray(), buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var scale = buffer.getInt();
    final var unscaledValue =
        BinaryTypeSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  @Override
  public BigDecimal deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final var scale = buffer.getInt(offset);
    offset += Integer.BYTES;

    final var unscaledValue =
        BinaryTypeSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset,
            buffer);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    buffer.position(buffer.position() + IntegerSerializer.INT_SIZE);
    return IntegerSerializer.INT_SIZE
        + BinaryTypeSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return Integer.BYTES
        + BinaryTypeSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory,
        offset + Integer.BYTES, buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final var scale = walChanges.getIntValue(buffer, offset);
    offset += IntegerSerializer.INT_SIZE;

    final var unscaledValue =
        BinaryTypeSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
            walChanges, offset);

    return new BigDecimal(new BigInteger(unscaledValue), scale);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return IntegerSerializer.INT_SIZE
        + BinaryTypeSerializer.INSTANCE.getObjectSizeInByteBuffer(
        buffer, walChanges, offset + IntegerSerializer.INT_SIZE);
  }
}
