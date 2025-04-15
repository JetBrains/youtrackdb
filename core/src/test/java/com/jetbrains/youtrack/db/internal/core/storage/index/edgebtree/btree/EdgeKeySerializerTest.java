package com.jetbrains.youtrack.db.internal.core.storage.index.edgebtree.btree;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeKeySerializer;
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
    final var edgeKey = new EdgeKey(42, 24, 67);
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
    final var edgeKey = new EdgeKey(42, 24, 67);
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
    final var edgeKey = new EdgeKey(42, 24, 67);
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
    final var edgeKey = new EdgeKey(42, 24, 67);
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
}
