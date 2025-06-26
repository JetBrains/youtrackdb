/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.internal.common.serialization.types;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 18.01.12
 */
public class ByteSerializerTest {

  private static final int FIELD_SIZE = 1;
  byte[] stream = new byte[FIELD_SIZE];
  private static final Byte OBJECT = 1;
  private static ByteSerializer byteSerializer;
  private static BinarySerializerFactory serializerFactory;


  @BeforeClass
  public static void beforeClass() {
    byteSerializer = new ByteSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE, byteSerializer.getObjectSize(serializerFactory, null));
  }

  @Test
  public void testSerialize() {
    byteSerializer.serialize(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT, byteSerializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void testSerializeNative() {
    byteSerializer.serializeNative(OBJECT, stream, 0);
    Assert.assertEquals(OBJECT,
        byteSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    byteSerializer.serializeNative(OBJECT, stream, 0);

    final var buffer =
        ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.position(0);
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(OBJECT,
        byteSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);
    byteSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        byteSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    final var result = byteSerializer.deserializeFromByteBufferObject(serializerFactory, buffer);

    Assert.assertEquals(OBJECT, result);
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);
    byteSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        byteSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        FIELD_SIZE);
    Assert.assertEquals(0, buffer.position());
    final var result = byteSerializer.deserializeFromByteBufferObject(serializerFactory,
        serializationOffset,
        buffer);

    Assert.assertEquals(0, buffer.position());
    Assert.assertEquals(OBJECT, result);
  }

  @Test
  public void testSerializationInWALChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final WALChanges walChanges = new WALPageChangesPortion();
    final var data = new byte[FIELD_SIZE];
    byteSerializer.serializeNative(OBJECT, data, 0);
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        byteSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT,
        byteSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));

    Assert.assertEquals(0, buffer.position());
  }
}
