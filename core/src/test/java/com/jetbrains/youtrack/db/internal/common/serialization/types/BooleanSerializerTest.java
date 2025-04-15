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
public class BooleanSerializerTest {

  private static final int FIELD_SIZE = 1;
  private static final Boolean OBJECT_TRUE = true;
  private static final Boolean OBJECT_FALSE = false;
  byte[] stream = new byte[FIELD_SIZE];
  private static BooleanSerializer booleanSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    booleanSerializer = new BooleanSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE,
        booleanSerializer.getObjectSize(serializerFactory, null));
  }

  @Test
  public void testSerialize() {
    booleanSerializer.serialize(OBJECT_TRUE, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT_TRUE, booleanSerializer.deserialize(serializerFactory, stream, 0));
    booleanSerializer.serialize(OBJECT_FALSE, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT_FALSE, booleanSerializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void testSerializeNative() {
    booleanSerializer.serializeNative(OBJECT_TRUE, stream, 0);
    Assert.assertEquals(OBJECT_TRUE,
        booleanSerializer.deserializeNativeObject(serializerFactory, stream, 0));
    booleanSerializer.serializeNative(OBJECT_FALSE, stream, 0);
    Assert.assertEquals(OBJECT_FALSE,
        booleanSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    booleanSerializer.serializeNative(OBJECT_TRUE, stream, 0);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.position(0);
    buffer.put(stream);

    buffer.position(0);
    Assert.assertEquals(
        OBJECT_TRUE, booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    booleanSerializer.serializeNative(OBJECT_FALSE, stream, 0);
    buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    Assert.assertEquals(
        OBJECT_FALSE, booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializationByteBuffer() {
    final var serializationOffset = 5;

    var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    booleanSerializer.serializeInByteBufferObject(serializerFactory, OBJECT_TRUE, buffer);

    var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(
        OBJECT_TRUE, booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    booleanSerializer.serializeInByteBufferObject(serializerFactory, OBJECT_FALSE, buffer);

    binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(
        OBJECT_FALSE, booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializationImmutableByteBufferPosition() {
    final var serializationOffset = 5;

    var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    booleanSerializer.serializeInByteBufferObject(serializerFactory, OBJECT_TRUE, buffer);

    var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(
        OBJECT_TRUE, booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    booleanSerializer.serializeInByteBufferObject(serializerFactory, OBJECT_FALSE, buffer);

    binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        OBJECT_FALSE,
        booleanSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializationWalChanges() {
    final var serializationOffset = 5;

    var data = new byte[FIELD_SIZE];
    var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    booleanSerializer.serializeNative(OBJECT_TRUE, data, 0);
    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT_TRUE,
        booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));

    booleanSerializer.serializeNative(OBJECT_FALSE, data, 0);
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        booleanSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT_FALSE,
        booleanSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));
  }
}
