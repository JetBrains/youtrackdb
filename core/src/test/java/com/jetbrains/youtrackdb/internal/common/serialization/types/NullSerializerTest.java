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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link NullSerializer}. NullSerializer carries the registered NULL slot
 * (id = 11) in the binary serializer dispatcher. Every behavioral method is a no-op
 * (returns 0 / returns null / writes nothing) by design — the value of these tests
 * is in pinning that contract: a regression that started writing or reading actual
 * bytes for NULL values would silently corrupt every persisted record carrying a
 * NULL field. Pin all dispatcher metadata, both the byte[] and ByteBuffer surfaces
 * (heap + native + WALChanges), and the offset overload variants.
 */
public class NullSerializerTest {

  private static NullSerializer nullSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    nullSerializer = new NullSerializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  // --- Dispatcher metadata pins ---

  /**
   * The static {@code INSTANCE} field plus {@code ID = 11} are part of the
   * dispatcher's wire-format contract. A future refactor that renumbers the slot or
   * removes the singleton would silently break every persisted NULL.
   */
  @Test
  public void instanceAndIdPin() {
    assertNotNull(NullSerializer.INSTANCE);
    assertEquals((byte) 11, NullSerializer.ID);
    assertEquals(NullSerializer.ID, nullSerializer.getId());
  }

  /**
   * NULL has zero serialized size, both as a length-bearing serializer flag and as a
   * fixed-length value. Pin both invariants.
   */
  @Test
  public void isFixedLengthAndZeroFixedLength() {
    assertTrue(nullSerializer.isFixedLength());
    assertEquals(0, nullSerializer.getFixedLength());
  }

  /**
   * Every {@code getObjectSize*} variant must return zero — pin the no-data contract
   * across the object-form, byte[] form, and native byte[] form so a regression on
   * any single overload is caught.
   */
  @Test
  public void getObjectSizeVariantsAllReturnZero() {
    final var stream = new byte[0];
    assertEquals(0, nullSerializer.getObjectSize(serializerFactory, null));
    assertEquals(0, nullSerializer.getObjectSize(serializerFactory, stream, 0));
    assertEquals(0, nullSerializer.getObjectSizeNative(serializerFactory, stream, 0));
  }

  /**
   * {@link NullSerializer#preprocess(BinarySerializerFactory, Object, Object...)}
   * always returns {@code null} regardless of input. Pin the null-coercion contract
   * — even an arbitrary object input must be discarded so downstream callers don't
   * accidentally start storing data for what should be a NULL.
   */
  @Test
  public void preprocessAlwaysReturnsNull() {
    assertNull(nullSerializer.preprocess(serializerFactory, null));
    assertNull(nullSerializer.preprocess(serializerFactory, new Object()));
    assertNull(nullSerializer.preprocess(serializerFactory, "ignored"));
  }

  // --- Heap byte[] surface ---

  /**
   * Heap {@code serialize}/{@code deserialize} must be no-ops: the underlying byte[]
   * stays untouched and {@code deserialize} returns null. We pin both directions on
   * a sentinel-filled stream so an accidental write of a single byte would flip the
   * sentinel and fail the assertion.
   */
  @Test
  public void heapSerializeDeserializeAreNoOps() {
    final var stream = new byte[4];
    Arrays.fill(stream, (byte) 0xAA);

    nullSerializer.serialize(new Object(), serializerFactory, stream, 0);
    for (final var b : stream) {
      assertEquals((byte) 0xAA, b);
    }

    assertNull(nullSerializer.deserialize(serializerFactory, stream, 0));
  }

  /**
   * Native heap {@code serializeNativeObject}/{@code deserializeNativeObject} must
   * also be no-ops with the same byte-untouched contract as the portable heap path.
   */
  @Test
  public void nativeHeapSerializeDeserializeAreNoOps() {
    final var stream = new byte[4];
    Arrays.fill(stream, (byte) 0x55);

    nullSerializer.serializeNativeObject(new Object(), serializerFactory, stream, 0);
    for (final var b : stream) {
      assertEquals((byte) 0x55, b);
    }

    assertNull(nullSerializer.deserializeNativeObject(serializerFactory, stream, 0));
  }

  // --- ByteBuffer surface (no offset, with offset, WALChanges) ---

  /**
   * ByteBuffer round-trip at the buffer's current position: serialize advances the
   * position by zero (it's a no-op write), getObjectSizeInByteBuffer and
   * deserializeFromByteBufferObject both return their NULL contract. Pre-fill the
   * underlying bytes with a sentinel so a regression that wrote bytes through the
   * absolute-index API (e.g. {@code buffer.putLong(0, value)}) — which would not
   * advance position — still fails the assertion.
   */
  @Test
  public void byteBufferAtCurrentPositionAreNoOps() {
    final var serializationOffset = 5;
    final var buffer = ByteBuffer.allocate(serializationOffset + 4);
    final var sentinel = (byte) 0xAA;
    for (var i = 0; i < buffer.capacity(); i++) {
      buffer.put(i, sentinel);
    }
    buffer.position(serializationOffset);

    nullSerializer.serializeInByteBufferObject(serializerFactory, new Object(), buffer);
    assertEquals(serializationOffset, buffer.position());
    for (var i = 0; i < buffer.capacity(); i++) {
      assertEquals("byte " + i + " was mutated by no-op write", sentinel, buffer.get(i));
    }

    buffer.position(serializationOffset);
    assertEquals(0, nullSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer));

    buffer.position(serializationOffset);
    assertNull(nullSerializer.deserializeFromByteBufferObject(serializerFactory, buffer));
  }

  /**
   * ByteBuffer offset overload: the two index-form methods must return zero and null
   * without mutating the buffer position. Pin the position invariance so a future
   * regression to position-mutating semantics fails loudly.
   */
  @Test
  public void byteBufferOffsetOverloadsDoNotMutatePosition() {
    final var buffer = ByteBuffer.allocate(8);
    buffer.position(0);

    assertEquals(
        0,
        nullSerializer.getObjectSizeInByteBuffer(serializerFactory, 3, buffer));
    assertEquals(0, buffer.position());

    assertNull(nullSerializer.deserializeFromByteBufferObject(serializerFactory, 3, buffer));
    assertEquals(0, buffer.position());
  }

  /**
   * WALChanges overload: pin that both the size query and the deserialise return the
   * NULL contract under WAL-driven access. WALChanges is the only non-trivial code
   * path on the read side — it dispatches through {@code WALChanges.getXxxValue} but
   * for NULL must short-circuit to zero/null without touching the WAL state. Pre-fill
   * the underlying buffer with a sentinel so a regression that started staging bytes
   * via {@code walChanges.setBinaryValue} (or any other write through the WAL surface)
   * would corrupt the sentinel and fail the post-call invariance check — the size /
   * deserialise return-value assertions alone would not catch a stray write.
   */
  @Test
  public void walChangesOverloadsAreNoOps() {
    final var serializationOffset = 5;
    final var bufferSize = serializationOffset + WALPageChangesPortion.PORTION_BYTES;
    final var buffer =
        ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder());
    final var sentinel = (byte) 0x77;
    for (var i = 0; i < bufferSize; i++) {
      buffer.put(i, sentinel);
    }
    final WALChanges walChanges = new WALPageChangesPortion();

    assertEquals(
        0,
        nullSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset));
    assertNull(
        nullSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset));

    for (var i = 0; i < bufferSize; i++) {
      assertEquals("byte " + i + " was mutated by no-op WAL access", sentinel, buffer.get(i));
    }
    assertEquals(0, buffer.position());
  }
}
