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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.stream;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public class StreamSerializerRID implements BinarySerializer<Identifiable> {

  public static final StreamSerializerRID INSTANCE = new StreamSerializerRID();
  public static final byte ID = 16;

  public int getObjectSize(BinarySerializerFactory serializerFactory, Identifiable object,
      Object... hints) {
    return LinkSerializer.INSTANCE.getObjectSize(serializerFactory, object.getIdentity());
  }

  public void serialize(Identifiable object, BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition, Object... hints) {
    LinkSerializer.INSTANCE.serialize(object.getIdentity(), serializerFactory, stream,
        startPosition);
  }

  public RID deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LinkSerializer.INSTANCE.deserialize(serializerFactory, stream, startPosition);
  }

  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LinkSerializer.INSTANCE.getObjectSize(serializerFactory, stream, startPosition);
  }

  public byte getId() {
    return ID;
  }

  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return LinkSerializer.INSTANCE.getObjectSizeNative(serializerFactory, stream, startPosition);
  }

  public void serializeNativeObject(
      Identifiable object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    LinkSerializer.INSTANCE.serializeNativeObject(object.getIdentity(), serializerFactory, stream,
        startPosition);
  }

  public Identifiable deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    return LinkSerializer.INSTANCE.deserializeNativeObject(serializerFactory, stream,
        startPosition);
  }

  public boolean isFixedLength() {
    return true;
  }

  public int getFixedLength() {
    return LinkSerializer.RID_SIZE;
  }

  @Override
  public Identifiable preprocess(BinarySerializerFactory serializerFactory, Identifiable value,
      Object... hints) {
    return value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(
      BinarySerializerFactory serializerFactory, Identifiable object, ByteBuffer buffer,
      Object... hints) {
    LinkSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, object.getIdentity(),
        buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Identifiable deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return LinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);
  }

  @Override
  public Identifiable deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return LinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset,
        buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return LinkSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer);
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return LinkSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Identifiable deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return LinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
        walChanges, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return LinkSerializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
  }
}
