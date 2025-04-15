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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 04.04.12
 */
public class DecimalSerializerTest {

  private static final int FIELD_SIZE = 9;
  private static final byte[] stream = new byte[FIELD_SIZE];
  private static final BigDecimal OBJECT = new BigDecimal(new BigInteger("20"), 2);
  private static DecimalSerializer decimalSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    decimalSerializer = new DecimalSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE, decimalSerializer.getObjectSize(serializerFactory, OBJECT));
  }

  @Test
  public void testSerialize() {
    decimalSerializer.serialize(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT, decimalSerializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void testSerializeNative() {
    decimalSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(OBJECT,
        decimalSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    decimalSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 0);

    final var buffer =
        ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(
        OBJECT, decimalSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    decimalSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        decimalSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(
        OBJECT, decimalSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    Assert.assertEquals(FIELD_SIZE, buffer.position() - serializationOffset);
  }

  @Test
  public void testSerializeInByteBufferImmutablePosition() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    decimalSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        FIELD_SIZE,
        decimalSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        OBJECT,
        decimalSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializeWALChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final var data = new byte[FIELD_SIZE];
    decimalSerializer.serializeNativeObject(OBJECT, serializerFactory, data, 0);
    final WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        decimalSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT,
        decimalSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));
  }
}
