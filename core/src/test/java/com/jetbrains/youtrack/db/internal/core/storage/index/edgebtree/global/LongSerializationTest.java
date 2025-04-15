package com.jetbrains.youtrack.db.internal.core.storage.index.edgebtree.global;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.LongSerializer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.Test;

public class LongSerializationTest {

  @Test
  public void serializeOneByteTest() {
    final long value = 0xE5;

    serializationTest(value);
  }

  @Test
  public void serializeTwoBytesTest() {
    final long value = 0xE4_E5;

    serializationTest(value);
  }

  @Test
  public void serializeThreeBytesTest() {
    final long value = 0xA5_E4_E5;

    serializationTest(value);
  }

  @Test
  public void serializeFourBytesTest() {
    final long value = 0xFE_A5_E4_E5;

    serializationTest(value);
  }

  @Test
  public void serializeFiveBytesTest() {
    final var value = 0xA3_FE_A5_E4_E5L;

    serializationTest(value);
  }

  @Test
  public void serializeSixBytesTest() {
    final var value = 0xE6_A3_FE_A5_E4_E5L;

    serializationTest(value);
  }

  @Test
  public void serializeSevenBytesTest() {
    final var value = 0xAA_E6_A3_FE_A5_E4_E5L;

    serializationTest(value);
  }

  @Test
  public void serializeEightBytesTest() {
    final var value = 0xFF_AA_E6_A3_FE_A5_E4_E5L;

    serializationTest(value);
  }

  private void serializationTest(long value) {
    final var size = LongSerializer.getObjectSize(value);
    final var stream = new byte[size + 3];

    final var pos = LongSerializer.serialize(value, stream, 3);
    Assert.assertEquals(size + 3, pos);

    final var serializedSize = LongSerializer.getObjectSize(stream, 3);
    Assert.assertEquals(size, serializedSize);

    final var deserialized = LongSerializer.deserialize(stream, 3);

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializeByteBufferOneByteTest() {
    final long value = 0xE5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferTwoBytesTest() {
    final long value = 0xE4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferThreeBytesTest() {
    final long value = 0xA5_E4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferFourBytesTest() {
    final long value = 0xFE_A5_E4_E5;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferFiveBytesTest() {
    final var value = 0xA3_FE_A5_E4_E5L;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferSixBytesTest() {
    final var value = 0xE6_A3_FE_A5_E4_E5L;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferSevenBytesTest() {
    final var value = 0xAA_E6_A3_FE_A5_E4_E5L;

    byteBufferSerializationTest(value);
  }

  @Test
  public void serializeByteBufferEightBytesTest() {
    final var value = 0xFF_AA_E6_A3_FE_A5_E4_E5L;

    byteBufferSerializationTest(value);
  }

  private void byteBufferSerializationTest(long value) {
    final var size = LongSerializer.getObjectSize(value);
    final var byteBuffer = ByteBuffer.allocate(size + 3);

    byteBuffer.position(3);

    LongSerializer.serialize(value, byteBuffer);

    byteBuffer.position(3);
    final var serializedSize = LongSerializer.getObjectSize(byteBuffer);
    Assert.assertEquals(size, serializedSize);

    byteBuffer.position(3);
    final var deserialized = LongSerializer.deserialize(byteBuffer);

    Assert.assertEquals(value, deserialized);
  }

  @Test
  public void serializeTrackingOneByteTest() {
    final long value = 0xE5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingTwoBytesTest() {
    final long value = 0xE4_E5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingThreeBytesTest() {
    final long value = 0xA5_E4_E5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingFourBytesTest() {
    final long value = 0xFE_A5_E4_E5;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingFiveBytesTest() {
    final var value = 0xA3_FE_A5_E4_E5L;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingSixBytesTest() {
    final var value = 0xE6_A3_FE_A5_E4_E5L;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingSevenBytesTest() {
    final var value = 0xAA_E6_A3_FE_A5_E4_E5L;

    changeTrackingSerializationTest(value);
  }

  @Test
  public void serializeTrackingEightBytesTest() {
    final var value = 0xFF_AA_E6_A3_FE_A5_E4_E5L;

    changeTrackingSerializationTest(value);
  }

  private void changeTrackingSerializationTest(long value) {
    final WALChanges walChanges = new WALPageChangesPortion();

    final var size = LongSerializer.getObjectSize(value);
    final var byteBuffer =
        ByteBuffer.allocate(GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024)
            .order(ByteOrder.nativeOrder());
    final var serializedValue = new byte[size];
    LongSerializer.serialize(value, serializedValue, 0);
    walChanges.setBinaryValue(byteBuffer, serializedValue, 3);

    final var serializedSize = LongSerializer.getObjectSize(byteBuffer, walChanges, 3);
    Assert.assertEquals(size, serializedSize);

    final var deserialized = LongSerializer.deserialize(byteBuffer, walChanges, 3);

    Assert.assertEquals(value, deserialized);
  }
}
