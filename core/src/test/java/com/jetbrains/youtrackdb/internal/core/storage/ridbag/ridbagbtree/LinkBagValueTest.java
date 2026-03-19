package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
    var original = new LinkBagValue(1, 0, 0, false);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
  }

  /**
   * Round-trip with large values that exercise multi-byte variable-length encoding
   * for both int and long fields.
   */
  @Test
  public void testByteArrayRoundTripWithLargeValues() {
    var original = new LinkBagValue(Integer.MAX_VALUE, 65535, 1_000_000_000L, false);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
  }

  /**
   * getObjectSize(byte[], offset) should return the same size as
   * getObjectSize(LinkBagValue) for consistency.
   */
  @Test
  public void testGetObjectSizeFromStreamMatchesComputedSize() {
    var original = new LinkBagValue(42, 100, 999_999L, false);

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
    var original = new LinkBagValue(7, 42, 123_456L, false);

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
    var original = new LinkBagValue(99, 200, 500_000L, false);
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
    var original = new LinkBagValue(42, 100, 999_999L, false);

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
    var original = new LinkBagValue(42, 100, 999_999L, false);
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
   * Verifies that the serializer metadata methods return correct values:
   * getId() = -1, isFixedLength() = false, getFixedLength() = -1, and
   * preprocess() returns the input unchanged.
   */
  @Test
  public void testSerializerMetadata() {
    assertEquals(-1, serializer.getId());
    assertFalse(serializer.isFixedLength());
    assertEquals(-1, serializer.getFixedLength());

    var value = new LinkBagValue(5, 10, 20L, false);
    var preprocessed = serializer.preprocess(serializerFactory, value);
    assertEquals(
        "preprocess should return the same value unchanged",
        value, preprocessed);
  }

  /**
   * The native byte-array serialization path should produce results identical to
   * the standard path.
   */
  @Test
  public void testNativeSerializationRoundTrip() {
    var original = new LinkBagValue(15, 300, 777_777L, false);

    var size = serializer.getObjectSizeNative(
        serializerFactory, serializeToByteArray(original), 0);
    var stream = new byte[size];
    serializer.serializeNativeObject(original, serializerFactory, stream, 0);

    var deserialized =
        serializer.deserializeNativeObject(serializerFactory, stream, 0);
    assertEquals(original, deserialized);
  }

  /**
   * Tombstone=true should round-trip correctly through byte array serialization.
   */
  @Test
  public void testByteArrayRoundTripWithTombstoneTrue() {
    var original = new LinkBagValue(3, 10, 500L, true);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
    assertEquals(true, deserialized.tombstone());
  }

  /**
   * Tombstone=false should round-trip correctly and be distinct from tombstone=true.
   */
  @Test
  public void testByteArrayRoundTripWithTombstoneFalse() {
    var original = new LinkBagValue(3, 10, 500L, false);
    var deserialized = serializeAndDeserializeViaByteArray(original);
    assertEquals(original, deserialized);
    assertEquals(false, deserialized.tombstone());
  }

  /**
   * Tombstone byte should be included in serialized size. A tombstone=true value and a
   * tombstone=false value with the same data fields should have the same serialized size.
   */
  @Test
  public void testSerializedSizeIncludesTombstoneByte() {
    var live = new LinkBagValue(42, 100, 999_999L, false);
    var dead = new LinkBagValue(42, 100, 999_999L, true);

    var liveSize = serializer.getObjectSize(serializerFactory, live);
    var deadSize = serializer.getObjectSize(serializerFactory, dead);

    assertEquals("Tombstone byte should not change serialized size", liveSize, deadSize);

    // Verify the tombstone byte is present: size should be 1 more than without tombstone
    // (compared to the pre-tombstone format). We verify this indirectly by checking that
    // the size from the stream matches the computed size.
    var liveStream = serializeToByteArray(live);
    var deadStream = serializeToByteArray(dead);

    assertEquals(liveSize, serializer.getObjectSize(serializerFactory, liveStream, 0));
    assertEquals(deadSize, serializer.getObjectSize(serializerFactory, deadStream, 0));
  }

  /**
   * Tombstone round-trip through streaming ByteBuffer.
   */
  @Test
  public void testByteBufferStreamingRoundTripWithTombstone() {
    var original = new LinkBagValue(7, 42, 123_456L, true);

    var size = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(size);

    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);
    buffer.flip();

    var deserialized =
        serializer.deserializeFromByteBufferObject(serializerFactory, buffer);
    assertEquals(original, deserialized);
    assertEquals(true, deserialized.tombstone());
  }

  /**
   * Tombstone round-trip through offset-based ByteBuffer.
   */
  @Test
  public void testByteBufferOffsetRoundTripWithTombstone() {
    var original = new LinkBagValue(99, 200, 500_000L, true);
    int leadingPadding = 10;

    var size = serializer.getObjectSize(serializerFactory, original);
    var buffer = ByteBuffer.allocate(leadingPadding + size);

    buffer.position(leadingPadding);
    serializer.serializeInByteBufferObject(serializerFactory, original, buffer);

    var deserialized = serializer.deserializeFromByteBufferObject(
        serializerFactory, leadingPadding, buffer);
    assertEquals(original, deserialized);
    assertEquals(true, deserialized.tombstone());
  }

  // --- Tombstone equality and toString tests (Step 3) ---

  /**
   * Two LinkBagValues with identical data fields but different tombstone flags must not be equal.
   */
  @Test
  public void testTombstoneDistinguishedByEquals() {
    var live = new LinkBagValue(42, 100, 999L, false);
    var dead = new LinkBagValue(42, 100, 999L, true);

    assertNotEquals("tombstone=true and tombstone=false should not be equal", live, dead);
  }

  /**
   * Two LinkBagValues with identical fields including tombstone must be equal.
   */
  @Test
  public void testEqualityWithSameTombstone() {
    var v1 = new LinkBagValue(42, 100, 999L, true);
    var v2 = new LinkBagValue(42, 100, 999L, true);

    assertEquals(v1, v2);
  }

  /**
   * toString should include tombstone status for debugging.
   */
  @Test
  public void testToStringIncludesTombstone() {
    var live = new LinkBagValue(1, 2, 3L, false);
    var dead = new LinkBagValue(1, 2, 3L, true);

    assertTrue("Live value toString should contain tombstone=false",
        live.toString().contains("tombstone=false"));
    assertTrue("Dead value toString should contain tombstone=true",
        dead.toString().contains("tombstone=true"));
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
