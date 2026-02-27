package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests LinkBagValue serialization round-trips through LinkBagValueSerializer.
 * Ensures that counter, secondaryCollectionId, and secondaryPosition survive
 * serialization and deserialization across all three encoding paths: byte arrays,
 * positional ByteBuffers, and streaming ByteBuffers.
 */
public class LinkBagValueTest {

  private BinarySerializerFactory serializerFactory;
  private LinkBagValueSerializer serializer;

  @Before
  public void setUp() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    serializer = LinkBagValueSerializer.INSTANCE;
  }

  /**
   * Serialization to a byte array and deserialization should preserve all three fields
   * of a LinkBagValue with small values.
   */
  @Test
  public void testByteArrayRoundTripWithSmallValues() {
    var original = new LinkBagValue(1, 0, 0);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
  }

  /**
   * Round-trip with large values that exercise multi-byte variable-length encoding
   * for both int and long fields.
   */
  @Test
  public void testByteArrayRoundTripWithLargeValues() {
    var original = new LinkBagValue(Integer.MAX_VALUE, 65535, 1_000_000_000L);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
  }

  /**
   * getObjectSize(byte[], offset) should return the same size as
   * getObjectSize(LinkBagValue) for consistency.
   */
  @Test
  public void testGetObjectSizeFromStreamMatchesComputedSize() {
    var original = new LinkBagValue(42, 100, 999_999L);

    var computedSize = serializer.getObjectSize(serializerFactory, original);
    var stream = serializeToByteArray(original);

    var streamSize = serializer.getObjectSize(serializerFactory, stream, 0);
    assertEquals(computedSize, streamSize);
  }

  /**
   * Serialization to a streaming ByteBuffer (position-advancing) should preserve
   * all fields after deserialization.
   */
  @Test
  public void testByteBufferStreamingRoundTrip() {
    var original = new LinkBagValue(7, 42, 123_456L);

    var size = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(size);

    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);
    buffer.flip();

    var deserialized =
        serializer.deserializeFromByteBufferObject(serializerFactory, buffer);
    assertEquals(original, deserialized);
  }

  /**
   * Deserialization from a ByteBuffer at a specific offset (non-position-advancing)
   * should correctly read all fields.
   */
  @Test
  public void testByteBufferOffsetBasedRoundTrip() {
    var original = new LinkBagValue(99, 200, 500_000L);
    int leadingPadding = 10;

    var size = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(leadingPadding + size);

    // Write padding then serialize at offset
    buffer.position(leadingPadding);
    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);

    var deserialized = serializer.deserializeFromByteBufferObject(
        serializerFactory, leadingPadding, buffer);
    assertEquals(original, deserialized);
  }

  /**
   * getObjectSizeInByteBuffer with streaming (position-advancing) mode should return
   * the correct size.
   */
  @Test
  public void testGetObjectSizeInByteBufferStreamingMode() {
    var original = new LinkBagValue(42, 100, 999_999L);

    var computedSize = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(computedSize);
    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);
    buffer.flip();

    var bufferSize =
        serializer.getObjectSizeInByteBuffer(serializerFactory, buffer);
    assertEquals(computedSize, bufferSize);
  }

  /**
   * getObjectSizeInByteBuffer with explicit offset should return the correct size.
   */
  @Test
  public void testGetObjectSizeInByteBufferOffsetMode() {
    var original = new LinkBagValue(42, 100, 999_999L);
    int offset = 5;

    var computedSize = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(offset + computedSize);
    buffer.position(offset);
    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);

    var bufferSize =
        serializer.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    assertEquals(computedSize, bufferSize);
  }

  /**
   * The native byte-array serialization path should produce results identical to
   * the standard path.
   */
  @Test
  public void testNativeSerializationRoundTrip() {
    var original = new LinkBagValue(15, 300, 777_777L);

    var size = serializer.getObjectSizeNative(
        serializerFactory, serializeToByteArray(original), 0);
    var stream = new byte[size];
    serializer.serializeNativeObject(original, serializerFactory, stream, 0);

    var deserialized =
        serializer.deserializeNativeObject(serializerFactory, stream, 0);
    assertEquals(original, deserialized);
  }

  private LinkBagValue serializeAndDeserializeViaByteArray(LinkBagValue value) {
    var stream = serializeToByteArray(value);
    return serializer.deserialize(serializerFactory, stream, 0);
  }

  private byte[] serializeToByteArray(LinkBagValue value) {
    var size = serializer.getObjectSize(serializerFactory, value);
    var stream = new byte[size];
    serializer.serialize(value, serializerFactory, stream, 0);
    return stream;
  }
}
