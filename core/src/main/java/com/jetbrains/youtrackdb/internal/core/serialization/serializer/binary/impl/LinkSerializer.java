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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl;

import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.bytes2long;
import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.bytes2short;
import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.long2bytes;
import static com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol.short2bytes;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * Serializer for {@link PropertyTypeInternal#LINK}
 *
 * @since 07.02.12
 */
public class LinkSerializer implements BinarySerializer<RID> {

  public static final byte ID = 9;
  private static final int COLLECTION_POS_SIZE = LongSerializer.LONG_SIZE;
  public static final int RID_SIZE = ShortSerializer.SHORT_SIZE + COLLECTION_POS_SIZE;
  public static final LinkSerializer INSTANCE = new LinkSerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final RID rid,
      Object... hints) {
    return staticGetObjectSize();
  }

  public static int staticGetObjectSize() {
    return RID_SIZE;
  }

  @Override
  public void serialize(
      final RID r, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, Object... hints) {
    staticSerialize(r, stream, startPosition);
  }

  public static void staticSerialize(final RID r, final byte[] stream, final int startPosition) {
    short2bytes((short) r.getCollectionId(), stream, startPosition);
    long2bytes(r.getCollectionPosition(), stream, startPosition + ShortSerializer.SHORT_SIZE);
  }

  @Override
  public RecordIdInternal deserialize(BinarySerializerFactory serializerFactory,
      final byte[] stream,
      final int startPosition) {
    return staticDeserialize(stream, startPosition);
  }

  public static RecordIdInternal staticDeserialize(final byte[] stream,
      final int startPosition) {
    return new RecordId(
        bytes2short(stream, startPosition),
        bytes2long(stream, startPosition + ShortSerializer.SHORT_SIZE));
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    return RID_SIZE;
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return RID_SIZE;
  }

  @Override
  public void serializeNativeObject(
      RID r, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
    ShortSerializer.INSTANCE.serializeNative((short) r.getCollectionId(), stream, startPosition);
    // Wrong implementation but needed for binary compatibility should be used serializeNative
    LongSerializer.INSTANCE.serialize(
        r.getCollectionPosition(), serializerFactory, stream,
        startPosition + ShortSerializer.SHORT_SIZE);
  }

  @Override
  public RecordIdInternal deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream,
      int startPosition) {
    final int collectionId = ShortSerializer.INSTANCE.deserializeNative(stream, startPosition);
    // Wrong implementation but needed for binary compatibility should be used deserializeNative
    final long collectionPosition =
        LongSerializer.INSTANCE.deserialize(serializerFactory, stream,
            startPosition + ShortSerializer.SHORT_SIZE);
    return new RecordId(collectionId, collectionPosition);
  }

  @Override
  public boolean isFixedLength() {
    return true;
  }

  @Override
  public int getFixedLength() {
    return RID_SIZE;
  }

  @Nullable
  @Override
  public RID preprocess(BinarySerializerFactory serializerFactory, RID value, Object... hints) {
    if (value == null) {
      return null;
    } else {
      return value.getIdentity();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(
      BinarySerializerFactory serializerFactory, RID r, ByteBuffer buffer, Object... hints) {
    buffer.putShort((short) r.getCollectionId());
    // Wrong implementation but needed for binary compatibility
    var stream = new byte[LongSerializer.LONG_SIZE];
    LongSerializer.INSTANCE.serialize(r.getCollectionPosition(), serializerFactory, stream, 0);
    buffer.put(stream);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RID deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final int collectionId = buffer.getShort();

    final var stream = new byte[LongSerializer.LONG_SIZE];
    buffer.get(stream);
    // Wrong implementation but needed for binary compatibility
    final long collectionPosition = LongSerializer.INSTANCE.deserialize(serializerFactory, stream,
        0);

    return new RecordId(collectionId, collectionPosition);
  }

  @Override
  public RID deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    final int collectionId = buffer.getShort(offset);
    offset += Short.BYTES;

    final var stream = new byte[LongSerializer.LONG_SIZE];
    buffer.get(offset, stream);
    // Wrong implementation but needed for binary compatibility
    final long collectionPosition = LongSerializer.INSTANCE.deserialize(serializerFactory, stream,
        0);

    return new RecordId(collectionId, collectionPosition);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return RID_SIZE;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return RID_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RID deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    final int collectionId = walChanges.getShortValue(buffer, offset);

    // Wrong implementation but needed for binary compatibility
    final long collectionPosition =
        LongSerializer.INSTANCE.deserialize(serializerFactory,
            walChanges.getBinaryValue(
                buffer, offset + ShortSerializer.SHORT_SIZE, LongSerializer.LONG_SIZE), 0);

    return new RecordId(collectionId, collectionPosition);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return RID_SIZE;
  }
}
