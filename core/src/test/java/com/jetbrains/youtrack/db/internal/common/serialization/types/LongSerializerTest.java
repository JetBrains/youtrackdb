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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 18.01.12
 */
public class LongSerializerTest {

  private static final int FIELD_SIZE = 8;
  private static final Long OBJECT = 999999999999999999L;
  private static LongSerializer longSerializer;
  static byte[] stream = new byte[FIELD_SIZE];
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    longSerializer = new LongSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE, longSerializer.getObjectSize(serializerFactory, null));
  }

  @Test
  public void testSerialize() {
    longSerializer.serialize(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT, longSerializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void testSerializeNative() {
    longSerializer.serializeNative(OBJECT, stream, 0);
    Assert.assertEquals(OBJECT,
        longSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    longSerializer.serializeNative(OBJECT, stream, 0);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(OBJECT,
        longSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);

    longSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        longSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(OBJECT,
        longSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    Assert.assertEquals(FIELD_SIZE, buffer.position() - serializationOffset);
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);

    longSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        FIELD_SIZE,
        longSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer));
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        OBJECT,
        longSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializationWALChanges() {
    final var serializationOffset = 5;

    final var buffer =
        ByteBuffer.allocateDirect(
            FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES);
    final var data = new byte[FIELD_SIZE];
    longSerializer.serializeNative(OBJECT, data, 0);

    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        longSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT,
        longSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));

    Assert.assertEquals(0, buffer.position());
  }
}
