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
import java.util.Random;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 19.01.12
 */
public class CharSerializerTest {

  private static final int FIELD_SIZE = 2;
  private static final Character OBJECT = (char) (new Random()).nextInt();
  byte[] stream = new byte[FIELD_SIZE];

  private static CharSerializer charSerializer;
  private static BinarySerializerFactory serializerFactory;


  @BeforeClass
  public static void beforeClass() {
    charSerializer = new CharSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE, charSerializer.getObjectSize(serializerFactory, null));
  }

  @Test
  public void testSerialize() {
    charSerializer.serialize(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT, charSerializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void testSerializeNative() {
    charSerializer.serializeNative(OBJECT, stream, 0);
    Assert.assertEquals(OBJECT,
        charSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    charSerializer.serializeNative(OBJECT, stream, 0);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.position(0);
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(OBJECT,
        charSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);
    charSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        charSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(OBJECT,
        charSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);
    charSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        charSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        FIELD_SIZE);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        OBJECT,
        charSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializeInWALChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final var data = new byte[FIELD_SIZE];
    charSerializer.serializeNative(OBJECT, data, 0);

    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        charSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT,
        charSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));
    Assert.assertEquals(0, buffer.position());
  }
}
