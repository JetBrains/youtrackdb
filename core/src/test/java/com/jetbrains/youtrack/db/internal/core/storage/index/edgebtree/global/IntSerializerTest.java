package com.jetbrains.youtrack.db.internal.core.storage.index.edgebtree.global;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.IntSerializer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class IntSerializerTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void serializeOneByteTest() {
    final var value = 0xE5;

    serializationTest(value);
  }

  @Test
  public void serializeTwoBytesTest() {
    final var value = 0xE4_E5;

    serializationTest(value);
  }

  @Test
  public void serializeThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    serializationTest(value);
  }

  @Test
  public void serializeFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    serializationTest(value);
  }

  private void serializationTest(int value) {
    final var serializer = new IntSerializer();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var stream = new byte[size + 3];

    serializer.serialize(value, serializerFactory, stream, 3, (Object[]) null);

    final var serializedSize = serializer.getObjectSize(serializerFactory, stream, 3);
    Assert.assertEquals(size, serializedSize);

    final int deserialized = serializer.deserialize(serializerFactory, stream, 3);

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializePrimitiveOneByteTest() {
    final var value = 0xE5;

    primitiveSerializationTest(value);
  }

  @Test
  public void serializePrimitiveTwoBytesTest() {
    final var value = 0xE4_E5;

    primitiveSerializationTest(value);
  }

  @Test
  public void serializePrimitiveThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    primitiveSerializationTest(value);
  }

  @Test
  public void serializePrimitiveFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    primitiveSerializationTest(value);
  }

  private void primitiveSerializationTest(int value) {
    final var serializer = new IntSerializer();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var stream = new byte[size + 3];

    final var position = serializer.serializePrimitive(stream, 3, value);
    Assert.assertEquals(size + 3, position);

    final var serializedSize = serializer.getObjectSize(serializerFactory, stream, 3);
    Assert.assertEquals(size, serializedSize);

    final int deserialized = serializer.deserialize(serializerFactory, stream, 3);

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializeNativeOneByteTest() {
    final var value = 0xE5;

    nativeSerializationTest(value);
  }

  @Test
  public void serializeNativeTwoBytesTest() {
    final var value = 0xE4_E5;

    nativeSerializationTest(value);
  }

  @Test
  public void serializeNativeThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    nativeSerializationTest(value);
  }

  @Test
  public void serializeNativeFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    nativeSerializationTest(value);
  }

  private void nativeSerializationTest(int value) {
    final var serializer = new IntSerializer();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var stream = new byte[size + 3];

    serializer.serializeNativeObject(value, serializerFactory, stream, 3, (Object[]) null);

    final var serializedSize = serializer.getObjectSize(serializerFactory, stream, 3);
    Assert.assertEquals(size, serializedSize);

    final int deserialized = serializer.deserializeNativeObject(serializerFactory, stream, 3);

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializeByteBufferOneByteTest() {
    final var value = 0xE5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeImmutableByteBufferPositionOneByteTest() {
    final var value = 0xE5;

    byteBufferImmutablePositionSerializationTest(value);
  }

  @Test
  public void serializeByteBufferTwoBytesTest() {
    final var value = 0xE4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeImmutableByteBufferPositionTwoBytesTest() {
    final var value = 0xE4_E5;

    byteBufferImmutablePositionSerializationTest(value);
  }

  @Test
  public void serializeByteBufferThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeImmutableByteBufferPositionThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    byteBufferImmutablePositionSerializationTest(value);
  }

  @Test
  public void serializeByteBufferFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeImmutableByteBufferPositionFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    byteBufferImmutablePositionSerializationTest(value);
  }

  private void byteBufferSerializationTest(int value) {
    final var serializer = new IntSerializer();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var byteBuffer = ByteBuffer.allocate(size + 3);

    byteBuffer.position(3);

    serializer.serializeInByteBufferObject(serializerFactory, value, byteBuffer, (Object[]) null);
    Assert.assertEquals(size + 3, byteBuffer.position());

    byteBuffer.position(3);
    final var serializedSize = serializer.getObjectSizeInByteBuffer(serializerFactory, byteBuffer);
    Assert.assertEquals(size, serializedSize);

    byteBuffer.position(3);
    final int deserialized = serializer.deserializeFromByteBufferObject(serializerFactory,
        byteBuffer);

    Assert.assertEquals(value, deserialized);
  }

  private void byteBufferImmutablePositionSerializationTest(int value) {
    final var serializer = new IntSerializer();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var byteBuffer = ByteBuffer.allocate(size + 3);

    byteBuffer.position(3);

    serializer.serializeInByteBufferObject(serializerFactory, value, byteBuffer, (Object[]) null);
    Assert.assertEquals(size + 3, byteBuffer.position());

    byteBuffer.position(0);
    final var serializedSize = serializer.getObjectSizeInByteBuffer(serializerFactory, 3,
        byteBuffer);
    Assert.assertEquals(size, serializedSize);
    Assert.assertEquals(0, byteBuffer.position());

    final int deserialized = serializer.deserializeFromByteBufferObject(serializerFactory, 3,
        byteBuffer);
    Assert.assertEquals(0, byteBuffer.position());

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializeChangesOneByteTest() {
    final var value = 0xE5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeChangesTwoBytesTest() {
    final var value = 0xE4_E5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeChangesThreeBytesTest() {
    final var value = 0xA5_E4_E5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeByteChangesFourBytesTest() {
    final var value = 0xFE_A5_E4_E5;

    changeTrackingSerializationTest(value);
  }

  private void changeTrackingSerializationTest(int value) {
    final var serializer = new IntSerializer();
    final WALChanges walChanges = new WALPageChangesPortion();

    final var size = serializer.getObjectSize(serializerFactory, value);
    final var byteBuffer =
        ByteBuffer.allocate(GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024)
            .order(ByteOrder.nativeOrder());
    final var serializedValue = new byte[size];
    serializer.serializeNativeObject(value, serializerFactory, serializedValue, 0);
    walChanges.setBinaryValue(byteBuffer, serializedValue, 3);

    final var serializedSize = serializer.getObjectSizeInByteBuffer(byteBuffer, walChanges, 3);
    Assert.assertEquals(size, serializedSize);

    final int deserialized = serializer.deserializeFromByteBufferObject(serializerFactory,
        byteBuffer, walChanges,
        3);

    Assert.assertEquals(value, deserialized);

    Assert.assertEquals(0, byteBuffer.position());
  }
}
