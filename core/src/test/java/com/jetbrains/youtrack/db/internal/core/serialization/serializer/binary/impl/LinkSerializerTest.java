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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl;

import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 07.02.12
 */
public class LinkSerializerTest {

  private static final int FIELD_SIZE = ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE;
  byte[] stream = new byte[FIELD_SIZE];
  private static final int collectionId = 5;
  private static final long position = 100500L;
  private static RecordId OBJECT;

  private static LinkSerializer linkSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    OBJECT = new RecordId(collectionId, position);
    linkSerializer = new LinkSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(linkSerializer.getObjectSize(serializerFactory, null), FIELD_SIZE);
  }

  @Test
  public void testSerialize() {
    linkSerializer.serialize(OBJECT, serializerFactory, stream, 0);
    Assert.assertEquals(linkSerializer.deserialize(serializerFactory, stream, 0), OBJECT);
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    linkSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, FIELD_SIZE);

    buffer.position(serializationOffset);
    Assert.assertEquals(linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer),
        FIELD_SIZE);

    buffer.position(serializationOffset);
    Assert.assertEquals(linkSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);

    Assert.assertEquals(buffer.position() - serializationOffset, FIELD_SIZE);
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;

    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    linkSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, FIELD_SIZE);

    buffer.position(0);
    Assert.assertEquals(
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        FIELD_SIZE);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        linkSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer), OBJECT);
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializeWALChanges() {
    final var serializationOffset = 5;

    final var buffer =
        ByteBuffer.allocateDirect(
            FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES);
    final var data = new byte[FIELD_SIZE];
    linkSerializer.serializeNativeObject(OBJECT, serializerFactory, data, 0);

    final WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        linkSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset),
        FIELD_SIZE);
    Assert.assertEquals(
        linkSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset),
        OBJECT);

    Assert.assertEquals(0, buffer.position());
  }
}
