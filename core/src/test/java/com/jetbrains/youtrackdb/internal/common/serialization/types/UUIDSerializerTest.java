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

package com.jetbrains.youtrackdb.internal.common.serialization.types;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link UUIDSerializer}. The original suite covered ByteBuffer + WALChanges
 * paths but left the heap byte[] paths, the static helpers, the dispatcher metadata
 * (getId / isFixedLength / getFixedLength / preprocess), the native deserialize path,
 * and the canonical byte-shape pin uncovered. This suite fills those gaps and adds
 * boundary fixtures (all-zero UUID, all-ones UUID, a known deterministic value) so
 * that a regression in the canonical 16-byte big-endian wire format fails loudly
 * rather than silently re-pinning into a new shape.
 */
public class UUIDSerializerTest {

  private static final int FIELD_SIZE = 16;
  private static final UUID OBJECT = UUID.randomUUID();
  // Deterministic non-zero MSB/LSB so the canonical byte-shape pin survives any random
  // fixture replay; chosen so MSB and LSB byte sequences are obviously distinct.
  private static final long DETERMINISTIC_MSB = 0x0123456789ABCDEFL;
  private static final long DETERMINISTIC_LSB = 0xFEDCBA9876543210L;
  private static final UUID DETERMINISTIC_UUID =
      new UUID(DETERMINISTIC_MSB, DETERMINISTIC_LSB);
  private static final byte[] DETERMINISTIC_CANONICAL_BYTES = new byte[] {
      (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
      (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
      (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98,
      (byte) 0x76, (byte) 0x54, (byte) 0x32, (byte) 0x10
  };
  private static final UUID ALL_ZERO_UUID = new UUID(0L, 0L);
  private static final UUID ALL_ONES_UUID = new UUID(-1L, -1L);

  private static UUIDSerializer uuidSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    uuidSerializer = new UUIDSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  // --- Dispatcher metadata pins ---

  /**
   * The static {@code INSTANCE} field plus the {@code UUID_SIZE} constant are part of
   * the serializer's API surface — pin them so a renumbering or accidental removal
   * fails the build.
   */
  @Test
  public void instanceAndSizeConstantPin() {
    assertNotNull(UUIDSerializer.INSTANCE);
    assertEquals(16, UUIDSerializer.UUID_SIZE);
  }

  /**
   * {@link UUIDSerializer#getId()} intentionally throws {@link UnsupportedOperationException}
   * because UUID is not registered with {@link BinarySerializerFactory} and has no
   * dispatcher slot. Pinning the throw protects against a silent re-introduction (any
   * future "fix" must update the factory dispatcher in lockstep).
   */
  @Test
  public void getIdThrowsUnsupportedOperation() {
    assertThrows(UnsupportedOperationException.class, () -> uuidSerializer.getId());
  }

  /**
   * {@link UUIDSerializer#isFixedLength()} delegates to {@link LongSerializer}'s
   * fixed-length flag — pin the resulting boolean since UUID is, by encoding, always
   * 16 bytes. A regression that returned false would silently break pre-allocated
   * fixed-length records.
   */
  @Test
  public void isFixedLengthIsTrue() {
    assertTrue(uuidSerializer.isFixedLength());
  }

  /**
   * Sister assertion to {@link #isFixedLengthIsTrue}: pin the fixed length to the
   * canonical 16-byte UUID size.
   */
  @Test
  public void getFixedLengthIsUuidSize() {
    assertEquals(FIELD_SIZE, uuidSerializer.getFixedLength());
  }

  /**
   * {@link UUIDSerializer#getObjectSize(BinarySerializerFactory, byte[], int)} is the
   * byte[]-form invariant used by buffer-driven readers; pin the constant return.
   */
  @Test
  public void getObjectSizeOnByteArrayReturnsUuidSize() {
    final var stream = new byte[FIELD_SIZE];
    assertEquals(FIELD_SIZE, uuidSerializer.getObjectSize(serializerFactory, stream, 0));
  }

  /**
   * {@link UUIDSerializer#getObjectSizeNative(BinarySerializerFactory, byte[], int)}
   * mirrors the byte[] form but is called on native-encoded streams; pin its constant
   * return.
   */
  @Test
  public void getObjectSizeNativeOnByteArrayReturnsUuidSize() {
    final var stream = new byte[FIELD_SIZE];
    assertEquals(FIELD_SIZE, uuidSerializer.getObjectSizeNative(serializerFactory, stream, 0));
  }

  /**
   * {@link UUIDSerializer#preprocess(BinarySerializerFactory, UUID, Object...)} is an
   * identity hook (UUID values are self-canonical); pin the identity contract so any
   * future preprocessing — e.g. variant normalization — must update the test in
   * lockstep with the contract change.
   */
  @Test
  public void preprocessReturnsTheSameInstance() {
    final var preprocessed =
        uuidSerializer.preprocess(serializerFactory, DETERMINISTIC_UUID);
    assertSame(DETERMINISTIC_UUID, preprocessed);
  }

  /**
   * Null-input identity for {@code preprocess}: UUID is unregistered with the
   * dispatcher (see {@link #getIdThrowsUnsupportedOperation}) so the contract is
   * "value in, value out" with no normalization branch. Pinning the null case
   * separately would catch a regression that introduced any UUID-method call
   * (e.g. variant normalization via {@code value.version()}), which would NPE
   * on null input but pass the non-null pin above.
   */
  @Test
  public void preprocessReturnsNullForNullInput() {
    assertEquals(null, uuidSerializer.preprocess(serializerFactory, null));
  }

  // --- Heap byte[] round-trips ---

  /**
   * Basic heap round-trip via the instance methods — covers
   * {@link UUIDSerializer#serialize(UUID, BinarySerializerFactory, byte[], int, Object...)}
   * and {@link UUIDSerializer#deserialize(BinarySerializerFactory, byte[], int)},
   * which were not exercised by the original suite. Uses the deterministic fixture
   * so a failure message reports the same UUID across all runs and architectures —
   * the legacy {@code OBJECT} field's {@code UUID.randomUUID()} value would change
   * on every run, complicating triage.
   */
  @Test
  public void heapByteArrayRoundTripIdentity() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serialize(DETERMINISTIC_UUID, serializerFactory, stream, 0);

    final var deserialized = uuidSerializer.deserialize(serializerFactory, stream, 0);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  /**
   * Heap round-trip at a non-zero start offset — guards against any future regression
   * that hard-codes startPosition to 0 or computes the LSB offset from the wrong base.
   */
  @Test
  public void heapByteArrayRoundTripAtNonZeroOffset() {
    final var offset = 7;
    final var stream = new byte[FIELD_SIZE + offset];
    uuidSerializer.serialize(DETERMINISTIC_UUID, serializerFactory, stream, offset);

    final var deserialized = uuidSerializer.deserialize(serializerFactory, stream, offset);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  /**
   * Static helper round-trip — the {@code staticSerialize} / {@code staticDeserialize}
   * pair is the lower-level API used directly by callers (e.g. WAL) that don't carry
   * a {@code BinarySerializerFactory}. Pin the contract that they round-trip the same
   * canonical byte sequence as the instance methods.
   */
  @Test
  public void staticHelpersRoundTripIdentity() {
    final var stream = new byte[FIELD_SIZE];
    UUIDSerializer.staticSerialize(DETERMINISTIC_UUID, stream, 0);

    final var deserialized = UUIDSerializer.staticDeserialize(stream, 0);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  /**
   * Cross-API equivalence: the instance {@code serialize} and the static
   * {@code staticSerialize} must produce byte-identical streams, since
   * {@code serialize} delegates to {@code staticSerialize}. A regression that
   * introduced any pre/post processing in one API would break wire-format
   * compatibility silently.
   */
  @Test
  public void instanceAndStaticSerializeProduceIdenticalBytes() {
    final var instanceBytes = new byte[FIELD_SIZE];
    final var staticBytes = new byte[FIELD_SIZE];
    uuidSerializer.serialize(DETERMINISTIC_UUID, serializerFactory, instanceBytes, 0);
    UUIDSerializer.staticSerialize(DETERMINISTIC_UUID, staticBytes, 0);
    assertArrayEquals(staticBytes, instanceBytes);
  }

  /**
   * Canonical byte-shape pin paired with the round-trip pin: a regression that
   * silently re-pinned the wire format (e.g. flipping endianness or swapping MSB/LSB
   * order) would round-trip cleanly while corrupting any existing on-disk record, so
   * round-trip identity alone is not enough on its own.
   * The heap path uses {@code LongSerializer.serializeLiteral} which is big-endian
   * MSB-first. The pin asserts the exact 16-byte canonical encoding for a
   * deterministic fixture so any drift to little-endian, swapped MSB/LSB ordering, or
   * stray transformation breaks loudly. Round-trip identity alone cannot catch this:
   * a symmetric encoder/decoder swap would round-trip cleanly while corrupting any
   * existing on-disk record.
   */
  @Test
  public void heapSerializeProducesCanonicalBigEndianBytes() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serialize(DETERMINISTIC_UUID, serializerFactory, stream, 0);

    assertArrayEquals(DETERMINISTIC_CANONICAL_BYTES, stream);
  }

  /**
   * Read side of the byte-shape pin: deserialising the canonical big-endian byte
   * sequence must reconstruct the deterministic fixture. Paired with
   * {@link #heapSerializeProducesCanonicalBigEndianBytes} so that read and write
   * sides stay symmetrical against the canonical reference bytes.
   */
  @Test
  public void heapDeserializeFromCanonicalBigEndianBytes() {
    final var deserialized =
        uuidSerializer.deserialize(serializerFactory, DETERMINISTIC_CANONICAL_BYTES, 0);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  /**
   * Static helper byte-shape pin: {@code staticSerialize} must emit the same
   * canonical bytes as the instance method.
   */
  @Test
  public void staticSerializeProducesCanonicalBigEndianBytes() {
    final var stream = new byte[FIELD_SIZE];
    UUIDSerializer.staticSerialize(DETERMINISTIC_UUID, stream, 0);

    assertArrayEquals(DETERMINISTIC_CANONICAL_BYTES, stream);
  }

  /**
   * Boundary fixture: all-zero UUID. The canonical encoding is 16 zero bytes; a
   * regression to a sentinel-encoded "null UUID" representation would fail loudly.
   */
  @Test
  public void allZeroUuidProducesAllZeroBytesAndRoundTrips() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serialize(ALL_ZERO_UUID, serializerFactory, stream, 0);

    final var expected = new byte[FIELD_SIZE];
    assertArrayEquals(expected, stream);
    assertEquals(ALL_ZERO_UUID, uuidSerializer.deserialize(serializerFactory, stream, 0));
  }

  /**
   * Boundary fixture: all-ones UUID. The canonical encoding is 16 0xFF bytes; pin
   * the negative-MSB / negative-LSB path that uses sign extension correctly.
   */
  @Test
  public void allOnesUuidProducesAllOnesBytesAndRoundTrips() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serialize(ALL_ONES_UUID, serializerFactory, stream, 0);

    final var expected = new byte[FIELD_SIZE];
    Arrays.fill(expected, (byte) 0xFF);
    assertArrayEquals(expected, stream);
    assertEquals(ALL_ONES_UUID, uuidSerializer.deserialize(serializerFactory, stream, 0));
  }

  // --- Native heap round-trip ---

  /**
   * Native heap round-trip — covers
   * {@link UUIDSerializer#serializeNativeObject} and
   * {@link UUIDSerializer#deserializeNativeObject}, which were transitively touched
   * by the WALChanges test on the write side but never exercised on the read side
   * via {@code deserializeNativeObject}. The native path uses platform byte order so
   * we deliberately do not pin canonical bytes here, only round-trip identity.
   */
  @Test
  public void nativeHeapRoundTripIdentity() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serializeNativeObject(DETERMINISTIC_UUID, serializerFactory, stream, 0);

    final var deserialized =
        uuidSerializer.deserializeNativeObject(serializerFactory, stream, 0);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  /**
   * Native heap MSB/LSB-half pin: read the serialised bytes back as two longs in
   * platform byte order and assert each half. Round-trip identity alone cannot
   * catch a regression that swapped the MSB-half / LSB-half slots since both
   * write and read would flip together; reading the halves out via the native
   * order reveals the swap without pinning a specific endianness.
   */
  @Test
  public void nativeHeapPinsLongHalvesViaNativeOrder() {
    final var stream = new byte[FIELD_SIZE];
    uuidSerializer.serializeNativeObject(DETERMINISTIC_UUID, serializerFactory, stream, 0);

    final var bb = ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder());
    assertEquals(DETERMINISTIC_MSB, bb.getLong());
    assertEquals(DETERMINISTIC_LSB, bb.getLong());
  }

  /**
   * Native heap round-trip at a non-zero start offset — exercises the LSB offset
   * computation under the native path.
   */
  @Test
  public void nativeHeapRoundTripAtNonZeroOffset() {
    final var offset = 11;
    final var stream = new byte[FIELD_SIZE + offset];
    uuidSerializer.serializeNativeObject(DETERMINISTIC_UUID, serializerFactory, stream, offset);

    final var deserialized =
        uuidSerializer.deserializeNativeObject(serializerFactory, stream, offset);
    assertEquals(DETERMINISTIC_UUID, deserialized);
  }

  // --- Existing ByteBuffer + WALChanges suites (preserved) ---

  @Test
  public void testFieldSize() {
    Assert.assertEquals(FIELD_SIZE, uuidSerializer.getObjectSize(serializerFactory, OBJECT));
  }

  @Test
  public void testSerializationInByteBuffer() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);

    uuidSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(serializationOffset);
    Assert.assertEquals(FIELD_SIZE,
        uuidSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    Assert.assertEquals(OBJECT,
        uuidSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));

    Assert.assertEquals(FIELD_SIZE, buffer.position() - serializationOffset);
  }

  @Test
  public void testSerializationInImmutableByteBufferPosition() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);
    buffer.position(serializationOffset);

    uuidSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(FIELD_SIZE, binarySize);

    buffer.position(0);
    Assert.assertEquals(
        uuidSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        FIELD_SIZE);
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        OBJECT,
        uuidSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer));
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testsSerializationWALChanges() {
    final var serializationOffset = 5;

    final var buffer =
        ByteBuffer.allocateDirect(
            FIELD_SIZE + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());
    final var data = new byte[FIELD_SIZE];

    uuidSerializer.serializeNativeObject(OBJECT, serializerFactory, data, 0);

    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        FIELD_SIZE,
        uuidSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    Assert.assertEquals(
        OBJECT,
        uuidSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));

    Assert.assertEquals(0, buffer.position());
  }

  /**
   * ByteBuffer round-trip with the canonical fixture, asserting both round-trip
   * identity and the canonical byte sequence. {@link ByteBuffer#allocate(int)} uses
   * big-endian order by default, the same canonical order as the heap path, so the
   * buffer-staged bytes must match {@link #DETERMINISTIC_CANONICAL_BYTES}.
   */
  @Test
  public void byteBufferRoundTripPinsCanonicalBigEndianBytes() {
    final var buffer = ByteBuffer.allocate(FIELD_SIZE);
    uuidSerializer.serializeInByteBufferObject(serializerFactory, DETERMINISTIC_UUID, buffer);

    final var written = new byte[FIELD_SIZE];
    buffer.position(0);
    buffer.get(written);
    assertArrayEquals(DETERMINISTIC_CANONICAL_BYTES, written);

    buffer.position(0);
    assertEquals(
        DETERMINISTIC_UUID,
        uuidSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }
}
