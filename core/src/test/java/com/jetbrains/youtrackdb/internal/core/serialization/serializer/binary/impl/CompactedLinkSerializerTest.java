package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class CompactedLinkSerializerTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testSerializeOneByte() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serialize(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSize(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserialize(serializerFactory, serialized, 1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeOneByte() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeTwoBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeNativeThreeBytes() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);
    final var serialized = new byte[size + 1];
    linkSerializer.serializeNativeObject(rid, serializerFactory, serialized, 1);

    Assert.assertEquals(size, linkSerializer.getObjectSizeNative(serializerFactory, serialized, 1));

    final var restoredRid = linkSerializer.deserializeNativeObject(serializerFactory, serialized,
        1);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeOneByteByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeOneByteByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 230);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytesByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeTwoBytesByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 325);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytesInByteBuffer() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(1);
    Assert.assertEquals(size, linkSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(1);
    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);
    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testSerializeThreeBytesInByteImmutableBufferPosition() {
    final var linkSerializer = new CompactedLinkSerializer();

    final var rid = new RecordId(123, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    var buffer = ByteBuffer.allocate(size + 1);
    buffer.position(1);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);

    buffer.position(0);
    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(serializerFactory, 1, buffer));
    Assert.assertEquals(0, buffer.position());

    final var restoredRid = linkSerializer.deserializeFromByteBufferObject(serializerFactory, 1,
        buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(rid, restoredRid);
  }

  @Test
  public void testIdAndVariableLengthContract() {
    // ID 22 is the dispatch byte registered with BinarySerializerFactory. The serializer is
    // variable-length because the encoded payload is 1, 2, 3, ... 8 bytes depending on
    // log-compaction of the collection-position. Pin both contract bits.
    Assert.assertEquals((byte) 22, CompactedLinkSerializer.ID);
    Assert.assertEquals((byte) 22, new CompactedLinkSerializer().getId());
    Assert.assertFalse(new CompactedLinkSerializer().isFixedLength());
    Assert.assertEquals(0, new CompactedLinkSerializer().getFixedLength());
  }

  @Test
  public void testInstanceSingleton() {
    // The INSTANCE singleton is what BinarySerializerFactory registers at startup; SBTree
    // pages reach this serializer through the factory id-lookup, not through `new`. Pin
    // the factory dispatch contract — id ID must resolve to INSTANCE — so a regression
    // that re-registered a fresh instance would round-trip but break SBTree page parsing.
    Assert.assertNotNull(CompactedLinkSerializer.INSTANCE);
    Assert.assertSame(CompactedLinkSerializer.INSTANCE,
        serializerFactory.getObjectSerializer(CompactedLinkSerializer.ID));
  }

  @Test
  public void testPreprocessExtractsIdentity() {
    // preprocess returns value.getIdentity() unconditionally — there is no null guard. Pin
    // the identity-extraction behaviour for a RID input (which returns itself as identity).
    final var linkSerializer = new CompactedLinkSerializer();
    final var rid = new RecordId(5, 5);
    Assert.assertEquals(rid, linkSerializer.preprocess(serializerFactory, rid));
  }

  @Test
  public void testNumberSizeBoundariesZeroThroughEight() {
    // Pin every numberSize bucket from 0 (collectionPosition=0) through 8 (Long.MAX_VALUE)
    // — each bucket exercises a different loop length in encode and decode. Position=0 is
    // the most critical bucket because numberSize=0 means the decode loop never executes
    // and the result depends entirely on the `long position = 0` initialisation; a
    // regression that initialised position to a non-zero default would silently break the
    // first-record-in-cluster path.
    final var linkSerializer = new CompactedLinkSerializer();
    final long[] positions = {
        0L, 0xFFL, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL,
        0xFFFFFFFFFFL, 0xFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFL, Long.MAX_VALUE};
    for (final long pos : positions) {
      final var rid = new RecordId(7, pos);
      final var size = linkSerializer.getObjectSize(serializerFactory, rid);
      final var data = new byte[size];
      linkSerializer.serialize(rid, serializerFactory, data, 0);
      Assert.assertEquals("portable round-trip for position=" + pos,
          rid, linkSerializer.deserialize(serializerFactory, data, 0));

      // Also pin the native-order path through the same boundary set
      final var nativeData = new byte[size];
      linkSerializer.serializeNativeObject(rid, serializerFactory, nativeData, 0);
      Assert.assertEquals("native round-trip for position=" + pos,
          rid, linkSerializer.deserializeNativeObject(serializerFactory, nativeData, 0));
    }
  }

  @Test
  public void testRecordIdMaximumClusterIdRoundTripsThroughCompactedLink() {
    // The on-wire format reserves 16 bits for the clusterId; this serializer casts
    // `(short) r.getCollectionId()` unconditionally in serialize / serializeNativeObject /
    // serializeInByteBufferObject. The cast would silently truncate cluster ids
    // > Short.MAX_VALUE — but RecordId's constructor rejects oversized cluster ids
    // first (LinkSerializerTest pins the constructor rejection), so the cast is never
    // reachable through the public RID API. Pinning a round-trip at the limit
    // (Short.MAX_VALUE = 32767) catches a regression that off-by-one'd the constructor
    // guard or that mis-encoded the top of the unsigned-vs-signed boundary in this
    // serializer. WHEN-FIXED — when the wire format eventually widens beyond 16 bits,
    // both the constructor check and the (short) cast must relax in lockstep.
    final var linkSerializer = new CompactedLinkSerializer();
    final var maxClusterId = (int) Short.MAX_VALUE;
    final var rid = new RecordId(maxClusterId, 12345L);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var portableData = new byte[size];
    linkSerializer.serialize(rid, serializerFactory, portableData, 0);
    Assert.assertEquals(
        "portable round-trip preserves max-cluster id", maxClusterId,
        linkSerializer.deserialize(serializerFactory, portableData, 0).getIdentity()
            .getCollectionId());

    final var nativeData = new byte[size];
    linkSerializer.serializeNativeObject(rid, serializerFactory, nativeData, 0);
    Assert.assertEquals(
        "native round-trip preserves max-cluster id", maxClusterId,
        linkSerializer.deserializeNativeObject(serializerFactory, nativeData, 0).getIdentity()
            .getCollectionId());

    final var buffer = ByteBuffer.allocate(size);
    linkSerializer.serializeInByteBufferObject(serializerFactory, rid, buffer);
    buffer.position(0);
    Assert.assertEquals(
        "byteBuffer round-trip preserves max-cluster id", maxClusterId,
        linkSerializer.deserializeFromByteBufferObject(serializerFactory, buffer).getIdentity()
            .getCollectionId());
  }

  @Test
  public void testWalOverlayDeserialiseRoundTripsCompactedRid() {
    // The WAL deserialise variant reads through a WALPageChangesPortion overlay — pin a
    // round-trip through the overlay so the compacted-byte unmarshalling on the WAL path
    // matches the in-memory decode.
    final var linkSerializer = new CompactedLinkSerializer();
    final var rid = new RecordId(42, 65628);
    final var size = linkSerializer.getObjectSize(serializerFactory, rid);

    final var offset = 4;
    final var buffer = ByteBuffer
        .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
        .order(java.nio.ByteOrder.nativeOrder());
    final var data = new byte[size];
    linkSerializer.serializeNativeObject(rid, serializerFactory, data, 0);

    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(buffer, data, offset);

    Assert.assertEquals(size,
        linkSerializer.getObjectSizeInByteBuffer(buffer, overlay, offset));
    Assert.assertEquals(rid,
        linkSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, overlay,
            offset));
  }
}
