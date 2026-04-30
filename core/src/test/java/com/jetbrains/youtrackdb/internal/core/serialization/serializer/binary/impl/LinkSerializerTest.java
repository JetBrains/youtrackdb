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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl;

import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests binary serialization and deserialization of record link (RID) values.
 *
 * @since 07.02.12
 */
public class LinkSerializerTest {

  private static final int FIELD_SIZE = ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE;
  byte[] stream = new byte[FIELD_SIZE];
  private static final int collectionId = 5;
  private static final long position = 100500L;
  private static RecordIdInternal OBJECT;

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
            buffer),
        OBJECT);
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

  @Test
  public void testIdAndFixedLengthContract() {
    // ID 9 is the byte the BinarySerializerFactory uses to dispatch this serializer at
    // deserialise time. The on-disk byte size is fixed at FIELD_SIZE (10 bytes — short
    // collectionId + long collectionPosition, regardless of value width). Pin both so a
    // regression that breaks SBTree forward-compat or factory dispatch fails loudly.
    Assert.assertEquals((byte) 9, linkSerializer.getId());
    Assert.assertTrue(linkSerializer.isFixedLength());
    Assert.assertEquals(FIELD_SIZE, linkSerializer.getFixedLength());
  }

  @Test
  public void testStaticGetObjectSizeMatchesFieldSize() {
    // staticGetObjectSize() is a static convenience accessor used by callers that do not
    // hold a serializer instance — pin its return value against the canonical FIELD_SIZE.
    Assert.assertEquals(FIELD_SIZE, LinkSerializer.staticGetObjectSize());
  }

  @Test
  public void testPreprocessReturnsIdentityWhenValueIsNonNull() {
    // preprocess delegates to value.getIdentity(). Passing a RID (which is its own identity)
    // pins that the result equals the input — exercises the non-null branch.
    Assert.assertEquals(OBJECT, linkSerializer.preprocess(serializerFactory, OBJECT));
  }

  @Test
  public void testPreprocessReturnsNullWhenValueIsNull() {
    // The null branch short-circuits at the top of preprocess. This pins the contract used by
    // index-layer normalisation when a position is intentionally null.
    Assert.assertNull(linkSerializer.preprocess(serializerFactory, null));
  }

  @Test
  public void testRecordIdRejectsClusterIdAboveShortMax() {
    // The on-wire format reserves 16 bits for the clusterId; this serializer casts
    // `(short) r.getCollectionId()` unconditionally in serialize / serializeNativeObject.
    // The cast would silently truncate cluster ids > Short.MAX_VALUE — but RecordId's
    // constructor rejects oversized cluster ids first via checkCollectionLimits, so the
    // cast is never reachable through the public RID API. Pin the upstream rejection so
    // a regression that loosened the constructor guard would fail here before the
    // silent truncation in the serializer became observable. WHEN-FIXED — when the wire
    // format eventually widens beyond 16 bits, both the constructor check and the
    // serializer cast must relax in lockstep, and this pin gets updated.
    final var oversizedClusterId = Short.MAX_VALUE + 1; // 32768
    Assert.assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.DatabaseException.class,
        () -> new RecordId(oversizedClusterId, 100L));
  }

  @Test
  public void testRecordIdMaximumClusterIdRoundTripsThroughLinkSerializer() {
    // Maximum legitimate cluster id (Short.MAX_VALUE = 32767) is right on the boundary
    // of the on-wire short representation. Pinning a round-trip at the limit catches
    // a regression that off-by-one'd the constructor guard or that mis-encoded the
    // top of the unsigned-vs-signed boundary in serialize/deserialize.
    final var maxClusterId = (int) Short.MAX_VALUE;
    final var rid = new RecordId(maxClusterId, 12345L);

    final var stream = new byte[FIELD_SIZE];
    linkSerializer.serialize(rid, serializerFactory, stream, 0);
    final var roundTripped = linkSerializer.deserialize(serializerFactory, stream, 0);
    Assert.assertEquals(
        "max-cluster portable round-trip preserves cluster id", maxClusterId,
        roundTripped.getCollectionId());

    final var nativeStream = new byte[FIELD_SIZE];
    linkSerializer.serializeNativeObject(rid, serializerFactory, nativeStream, 0);
    final var nativeRoundTripped =
        linkSerializer.deserializeNativeObject(serializerFactory, nativeStream, 0);
    Assert.assertEquals(
        "max-cluster native round-trip preserves cluster id", maxClusterId,
        nativeRoundTripped.getCollectionId());
  }
}
