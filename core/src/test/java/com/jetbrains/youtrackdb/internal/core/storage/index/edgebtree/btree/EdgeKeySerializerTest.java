package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKeySerializer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class EdgeKeySerializerTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testSerialization() {
    final var edgeKey = new EdgeKey(42, 24, 67, 0L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var rawKey = new byte[serializedSize + 3];

    edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 3);

    Assert.assertEquals(serializedSize,
        edgeKeySerializer.getObjectSize(serializerFactory, rawKey, 3));

    final var deserializedKey = edgeKeySerializer.deserialize(serializerFactory, rawKey, 3);

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testBufferSerialization() {
    final var edgeKey = new EdgeKey(42, 24, 67, 0L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var buffer = ByteBuffer.allocate(serializedSize + 3);

    buffer.position(3);
    edgeKeySerializer.serializeInByteBufferObject(serializerFactory, edgeKey, buffer);

    Assert.assertEquals(3 + serializedSize, buffer.position());

    buffer.position(3);
    Assert.assertEquals(serializedSize,
        edgeKeySerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(3);
    final var deserializedKey = edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory,
        buffer);

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testImmutableBufferPositionSerialization() {
    final var edgeKey = new EdgeKey(42, 24, 67, 0L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var buffer = ByteBuffer.allocate(serializedSize + 3);

    buffer.position(3);
    edgeKeySerializer.serializeInByteBufferObject(serializerFactory, edgeKey, buffer);

    Assert.assertEquals(3 + serializedSize, buffer.position());

    buffer.position(0);
    Assert.assertEquals(serializedSize, edgeKeySerializer.getObjectSizeInByteBuffer(
        serializerFactory, 3, buffer));

    Assert.assertEquals(0, buffer.position());

    final var deserializedKey = edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory,
        3, buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testChangesSerialization() {
    final var edgeKey = new EdgeKey(42, 24, 67, 0L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);

    final WALChanges walChanges = new WALPageChangesPortion();
    final var buffer =
        ByteBuffer.allocate(GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024)
            .order(ByteOrder.nativeOrder());

    final var rawKey = new byte[serializedSize];

    edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 0);
    walChanges.setBinaryValue(buffer, rawKey, 3);

    Assert.assertEquals(
        serializedSize, edgeKeySerializer.getObjectSizeInByteBuffer(buffer, walChanges, 3));

    final var deserializedKey =
        edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges, 3);

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testSerializationWithLargeTs() {
    // Verifies round-trip for a key with a large ts value, exercising all serializer paths
    // with variable-length encoding for ts.
    final var edgeKey = new EdgeKey(Long.MAX_VALUE / 2, 32000, Long.MAX_VALUE / 3, 987654321L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var rawKey = new byte[serializedSize + 5];

    edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 5);

    Assert.assertEquals(serializedSize,
        edgeKeySerializer.getObjectSize(serializerFactory, rawKey, 5));

    final var deserializedKey = edgeKeySerializer.deserialize(serializerFactory, rawKey, 5);
    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testSerializationWithBoundaryTsValues() {
    // Verifies round-trip for keys at ts boundary values (MIN_VALUE, MAX_VALUE, zero).
    final var edgeKeySerializer = new EdgeKeySerializer();

    var keys = new EdgeKey[] {
        new EdgeKey(1, 1, 1, Long.MIN_VALUE),
        new EdgeKey(1, 1, 1, Long.MAX_VALUE),
        new EdgeKey(1, 1, 1, 0L),
        new EdgeKey(1, 1, 1, 1L),
        new EdgeKey(1, 1, 1, -1L),
    };

    for (var edgeKey : keys) {
      final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
      final var rawKey = new byte[serializedSize];

      edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 0);
      final var deserialized = edgeKeySerializer.deserialize(serializerFactory, rawKey, 0);

      Assert.assertEquals("Round-trip failed for ts=" + edgeKey.ts, edgeKey, deserialized);
    }
  }

  @Test
  public void testBufferSerializationWithLargeTs() {
    // Verifies ByteBuffer round-trip with non-zero ts.
    final var edgeKey = new EdgeKey(100, 200, 300, 999_888_777L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var buffer = ByteBuffer.allocate(serializedSize + 3);

    buffer.position(3);
    edgeKeySerializer.serializeInByteBufferObject(serializerFactory, edgeKey, buffer);

    Assert.assertEquals(3 + serializedSize, buffer.position());

    buffer.position(3);
    Assert.assertEquals(serializedSize,
        edgeKeySerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(3);
    final var deserializedKey =
        edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory, buffer);

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testImmutableBufferPositionSerializationWithTs() {
    // Verifies offset-based ByteBuffer round-trip with non-zero ts.
    final var edgeKey = new EdgeKey(555, 666, 777, 12345L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);
    final var buffer = ByteBuffer.allocate(serializedSize + 7);

    buffer.position(7);
    edgeKeySerializer.serializeInByteBufferObject(serializerFactory, edgeKey, buffer);

    buffer.position(0);
    Assert.assertEquals(serializedSize,
        edgeKeySerializer.getObjectSizeInByteBuffer(serializerFactory, 7, buffer));

    Assert.assertEquals(0, buffer.position());

    final var deserializedKey =
        edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory, 7, buffer);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testChangesSerializationWithTs() {
    // Verifies WALChanges round-trip with non-zero ts.
    final var edgeKey = new EdgeKey(111, 222, 333, 444L);
    final var edgeKeySerializer = new EdgeKeySerializer();

    final var serializedSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);

    final WALChanges walChanges = new WALPageChangesPortion();
    final var buffer =
        ByteBuffer.allocate(GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024)
            .order(ByteOrder.nativeOrder());

    final var rawKey = new byte[serializedSize];

    edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 0);
    walChanges.setBinaryValue(buffer, rawKey, 5);

    Assert.assertEquals(
        serializedSize, edgeKeySerializer.getObjectSizeInByteBuffer(buffer, walChanges, 5));

    final var deserializedKey =
        edgeKeySerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges, 5);

    Assert.assertEquals(edgeKey, deserializedKey);
  }

  @Test
  public void testObjectSizeConsistencyAcrossAllPaths() {
    // Regression test: verifies that object-based size, byte[]-based size, ByteBuffer position-based
    // size, ByteBuffer offset-based size, and WALChanges-based size all agree for the same key.
    // This catches bugs like the pre-existing offset error in doGetObjectSize where the second
    // field was read from startPosition instead of startPosition + size.
    final var edgeKeySerializer = new EdgeKeySerializer();

    var keys = new EdgeKey[] {
        new EdgeKey(0, 0, 0, 0L),
        new EdgeKey(42, 24, 67, 0L),
        new EdgeKey(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        new EdgeKey(1, 1, 1, 123456789L),
        new EdgeKey(99999, 32000, 64000, 5L),
    };

    for (var edgeKey : keys) {
      final var objectSize = edgeKeySerializer.getObjectSize(serializerFactory, edgeKey);

      // Serialize to byte[] and check byte[]-based size
      final var rawKey = new byte[objectSize + 10];
      edgeKeySerializer.serialize(edgeKey, serializerFactory, rawKey, 10);
      final var byteArraySize =
          edgeKeySerializer.getObjectSize(serializerFactory, rawKey, 10);
      Assert.assertEquals("byte[] size mismatch for " + edgeKey, objectSize, byteArraySize);

      // Check ByteBuffer position-based size
      final var buffer = ByteBuffer.allocate(objectSize + 10);
      buffer.position(10);
      edgeKeySerializer.serializeInByteBufferObject(serializerFactory, edgeKey, buffer);
      buffer.position(10);
      final var bufferPosSize =
          edgeKeySerializer.getObjectSizeInByteBuffer(serializerFactory, buffer);
      Assert.assertEquals("ByteBuffer pos size mismatch for " + edgeKey, objectSize,
          bufferPosSize);

      // Check ByteBuffer offset-based size
      buffer.position(0);
      final var bufferOffsetSize =
          edgeKeySerializer.getObjectSizeInByteBuffer(serializerFactory, 10, buffer);
      Assert.assertEquals("ByteBuffer offset size mismatch for " + edgeKey, objectSize,
          bufferOffsetSize);

      // Check WALChanges-based size
      final WALChanges walChanges = new WALPageChangesPortion();
      final var walBuffer =
          ByteBuffer.allocate(GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024)
              .order(ByteOrder.nativeOrder());
      final var walRawKey = new byte[objectSize];
      edgeKeySerializer.serialize(edgeKey, serializerFactory, walRawKey, 0);
      walChanges.setBinaryValue(walBuffer, walRawKey, 10);
      final var walSize =
          edgeKeySerializer.getObjectSizeInByteBuffer(walBuffer, walChanges, 10);
      Assert.assertEquals("WAL size mismatch for " + edgeKey, objectSize, walSize);
    }
  }
}
