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
import java.util.Calendar;
import java.util.Date;

/**
 * Serializer for {@link Date} type.
 *
 * @since 20.01.12
 */
public class DateTimeSerializer implements BinarySerializer<Date> {

  public static final byte ID = 5;
  public static final DateTimeSerializer INSTANCE = new DateTimeSerializer();

  public int getObjectSize(BinarySerializerFactory serializerFactory, Date object,
      Object... hints) {
    return LongSerializer.LONG_SIZE;
  }

  public void serialize(Date object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var calendar = Calendar.getInstance();
    calendar.setTime(object);
    LongSerializer.serializeLiteral(calendar.getTimeInMillis(), stream, startPosition);
  }

  public Date deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var calendar = Calendar.getInstance();
    calendar.setTimeInMillis(LongSerializer.deserializeLiteral(stream, startPosition));
    return calendar.getTime();
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LongSerializer.LONG_SIZE;
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public void serializeNativeObject(
      Date object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    final var calendar = Calendar.getInstance();
    calendar.setTime(object);
    LongSerializer.INSTANCE.serializeNative(calendar.getTimeInMillis(), stream, startPosition);
  }

  @Override
  public Date deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var calendar = Calendar.getInstance();
    calendar.setTimeInMillis(LongSerializer.INSTANCE.deserializeNative(stream, startPosition));
    return calendar.getTime();
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public Date preprocess(BinarySerializerFactory serializerFactory, Date value, Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Date object,
      ByteBuffer buffer, Object... hints) {
    final var calendar = Calendar.getInstance();
    calendar.setTime(object);
    buffer.putLong(calendar.getTimeInMillis());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var calendar = Calendar.getInstance();
    calendar.setTimeInMillis(buffer.getLong());
    return calendar.getTime();
  }

  @Override
  public Date deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final var calendar = Calendar.getInstance();
    calendar.setTimeInMillis(buffer.getLong(offset));

    return calendar.getTime();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return LongSerializer.LONG_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final var calendar = Calendar.getInstance();
    calendar.setTimeInMillis(walChanges.getLongValue(buffer, offset));
    return calendar.getTime();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return LongSerializer.LONG_SIZE;
  }
}
