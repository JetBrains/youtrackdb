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
public class StringSerializerTest {

  static byte[] stream;
  private static int FIELD_SIZE;
  private static String OBJECT;
  private static StringSerializer stringSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    stringSerializer = new StringSerializer();
    var random = new Random();
    var sb = new StringBuilder();
    for (var i = 0; i < random.nextInt(20) + 5; i++) {
      sb.append((char) random.nextInt());
    }
    OBJECT = sb.toString();
    FIELD_SIZE = OBJECT.length() * 2 + 4 + 7;
    stream = new byte[FIELD_SIZE];
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(stringSerializer.getObjectSize(serializerFactory, OBJECT), FIELD_SIZE - 7);
  }

  @Test
  public void testSerialize() {
    stringSerializer.serialize(OBJECT, serializerFactory, stream, 7);
    Assert.assertEquals(stringSerializer.deserialize(serializerFactory, stream, 7), OBJECT);
  }

  @Test
  public void testSerializeNative() {
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 7);
    Assert.assertEquals(stringSerializer.deserializeNativeObject(serializerFactory, stream, 7),
        OBJECT);
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 7);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(7);

    Assert.assertEquals(stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    stringSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, FIELD_SIZE - 7);

    buffer.position(serializationOffset);
    Assert.assertEquals(stringSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer),
        FIELD_SIZE - 7);

    buffer.position(serializationOffset);
    Assert.assertEquals(stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);

    Assert.assertEquals(buffer.position() - serializationOffset, FIELD_SIZE - 7);
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    stringSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, FIELD_SIZE - 7);

    buffer.position(0);
    Assert.assertEquals(
        stringSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        FIELD_SIZE - 7);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        stringSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer), OBJECT);
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializeWALChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE - 7 + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final var data = new byte[FIELD_SIZE - 7];
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, data, 0);

    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        stringSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset),
        FIELD_SIZE - 7);
    Assert.assertEquals(
        stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset),
        OBJECT);
    Assert.assertEquals(0, buffer.position());
  }
}
