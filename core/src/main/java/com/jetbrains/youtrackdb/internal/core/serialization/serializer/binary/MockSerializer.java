/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 *
 */
public class MockSerializer implements BinarySerializer<EntityImpl> {

  public static MockSerializer INSTANCE = new MockSerializer();

  protected MockSerializer() {
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, EntityImpl object,
      Object... hints) {
    return 0;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return 0;
  }

  @Override
  public void serialize(EntityImpl object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
  }

  @Nullable
  @Override
  public EntityImpl deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return null;
  }

  @Override
  public byte getId() {
    return -10;
  }

  @Override
  public boolean isFixedLength() {
    return false;
  }

  @Override
  public int getFixedLength() {
    return 0;
  }

  @Override
  public void serializeNativeObject(
      EntityImpl object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
  }

  @Nullable
  @Override
  public EntityImpl deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    return null;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return 0;
  }

  @Nullable
  @Override
  public EntityImpl preprocess(BinarySerializerFactory serializerFactory, EntityImpl value,
      Object... hints) {
    return null;
  }

  @Override
  public void serializeInByteBufferObject(BinarySerializerFactory serializerFactory,
      EntityImpl object, ByteBuffer buffer, Object... hints) {
  }

  @Nullable
  @Override
  public EntityImpl deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return null;
  }

  @Nullable
  @Override
  public EntityImpl deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return null;
  }

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

  @Nullable
  @Override
  public EntityImpl deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    return null;
  }

  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return 0;
  }
}
