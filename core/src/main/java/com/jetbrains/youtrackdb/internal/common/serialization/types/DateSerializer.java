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
import java.util.Calendar;
import java.util.Date;
import javax.annotation.Nullable;

/**
 * Serializer for {@link Date} type, it serializes it without time part.
 *
 * @since 20.01.12
 */
public class DateSerializer implements BinarySerializer<Date> {

  public static final byte ID = 4;
  public static final DateSerializer INSTANCE = new DateSerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, Date object,
      Object... hints) {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public void serialize(Date object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    var calendar = Calendar.getInstance();
    calendar.setTime(object);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    dateTimeSerializer.serialize(calendar.getTime(), serializerFactory, stream, startPosition);
  }

  @Override
  public Date deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    return dateTimeSerializer.deserialize(serializerFactory, stream, startPosition);
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LongSerializer.LONG_SIZE;
  }

  @Override
  public void serializeNativeObject(
      final Date object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var calendar = Calendar.getInstance();
    calendar.setTime(object);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    final var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    dateTimeSerializer.serializeNativeObject(calendar.getTime(), serializerFactory, stream,
        startPosition);
  }

  @Override
  public Date deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    return dateTimeSerializer.deserializeNativeObject(serializerFactory, stream, startPosition);
  }

  @Override
  public boolean isFixedLength() {
    return true;
  }

  @Override
  public int getFixedLength() {
    return LongSerializer.LONG_SIZE;
  }

  @Nullable
  @Override
  public Date preprocess(BinarySerializerFactory serializerFactory, Date value, Object... hints) {
    if (value == null) {
      return null;
    }
    final var calendar = Calendar.getInstance();
    calendar.setTime(value);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);

    return calendar.getTime();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Date object,
      ByteBuffer buffer, Object... hints) {
    final var calendar = Calendar.getInstance();
    calendar.setTime(object);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    final var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    dateTimeSerializer.serializeInByteBufferObject(serializerFactory, calendar.getTime(), buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    return dateTimeSerializer.deserializeFromByteBufferObject(serializerFactory, buffer);
  }

  @Override
  public Date deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    return dateTimeSerializer.deserializeFromByteBufferObject(serializerFactory, offset, buffer);
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
    final var dateTimeSerializer = DateTimeSerializer.INSTANCE;
    return dateTimeSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
        offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return LongSerializer.LONG_SIZE;
  }
}
