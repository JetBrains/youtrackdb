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
import javax.annotation.Nullable;

/**
 * Serializes and deserializes null values.
 */
public class NullSerializer implements BinarySerializer<Object> {

  public static final byte ID = 11;
  public static final NullSerializer INSTANCE = new NullSerializer();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, final Object object,
      Object... hints) {
    return 0;
  }

  @Override
  public void serialize(
      final Object object, BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition, Object... hints) {
    // nothing to serialize
  }

  @Override
  @Nullable
  public Object deserialize(BinarySerializerFactory serializerFactory, final byte[] stream,
      final int startPosition) {
    // nothing to deserialize
    return null;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return 0;
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return 0;
  }

  @Override
  public void serializeNativeObject(
      Object object, BinarySerializerFactory serializerFactory, byte[] stream, int startPosition,
      Object... hints) {
  }

  @Override
  @Nullable
  public Object deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return null;
  }

  @Override
  public boolean isFixedLength() {
    return true;
  }

  @Override
  public int getFixedLength() {
    return 0;
  }

  @Nullable
  @Override
  public Object preprocess(BinarySerializerFactory serializerFactory, Object value,
      Object... hints) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, Object object,
      ByteBuffer buffer, Object... hints) {
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  @Override
  public Object deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  @Override
  public Object deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return 0;
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  @Override
  public Object deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return 0;
  }
}
