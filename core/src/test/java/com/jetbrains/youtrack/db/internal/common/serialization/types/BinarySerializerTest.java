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

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 20.01.12
 */
public class BinarySerializerTest extends DbTestBase {

  static byte[] stream;
  private static int FIELD_SIZE;
  private static byte[] OBJECT;

  private static BinaryTypeSerializer binarySerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    binarySerializer = new BinaryTypeSerializer();
    OBJECT = new byte[]{1, 2, 3, 4, 5, 6};
    FIELD_SIZE = OBJECT.length + IntegerSerializer.INT_SIZE;
    stream = new byte[FIELD_SIZE];
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(binarySerializer.getObjectSize(session.getSerializerFactory(), OBJECT),
        FIELD_SIZE);
  }

  @Test
  public void testSerialize() {
    binarySerializer.serialize(OBJECT, session.getSerializerFactory(), stream, 0);
    Assert.assertArrayEquals(
        binarySerializer.deserialize(session.getSerializerFactory(), stream, 0), OBJECT);
  }

  @Test
  public void testSerializeNative() {
    binarySerializer.serializeNativeObject(OBJECT, session.getSerializerFactory(), stream, 0);
    Assert.assertArrayEquals(
        binarySerializer.deserializeNativeObject(session.getSerializerFactory(), stream, 0),
        OBJECT);
  }

  @Test
  public void testNativeByteBufferCompatibility() {
    binarySerializer.serializeNativeObject(OBJECT, session.getSerializerFactory(), stream, 0);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.position(0);
    buffer.put(stream);

    buffer.position(0);
    Assert.assertArrayEquals(
        binarySerializer.deserializeFromByteBufferObject(session.getSerializerFactory(), buffer),
        OBJECT);
  }

  @Test
  public void testSerializeByteBuffer() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);

    binarySerializer.serializeInByteBufferObject(session.getSerializerFactory(), OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    buffer.position(serializationOffset);
    Assert.assertEquals(binarySerializer.getObjectSizeInByteBuffer(serializerFactory, buffer),
        binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(binarySerializer.getObjectSizeInByteBuffer(serializerFactory, buffer),
        FIELD_SIZE);

    buffer.position(serializationOffset);
    final var result = binarySerializer.deserializeFromByteBufferObject(
        session.getSerializerFactory(), buffer);

    Assert.assertArrayEquals(result, OBJECT);
  }

  @Test
  public void testSerializeInWalChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final var data = new byte[FIELD_SIZE];
    final var walChanges = new WALPageChangesPortion();
    binarySerializer.serializeNativeObject(OBJECT, session.getSerializerFactory(), data, 0);
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        binarySerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset),
        FIELD_SIZE);
    Assert.assertArrayEquals(
        binarySerializer.deserializeFromByteBufferObject(session.getSerializerFactory(), buffer,
            walChanges, serializationOffset),
        OBJECT);
  }
}
