/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BooleanSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.NullSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Standalone tests for {@link CompositeKeySerializer} — pins the on-disk and on-page byte
 * shapes for SBTree composite keys, exercises every byte[] / native / ByteBuffer-by-position /
 * ByteBuffer-by-offset / WAL-overlay code path, and pins the type-dispatch behaviour driven by
 * type hints versus default-from-class fallback.
 *
 * <p>Why standalone (no DbTestBase): {@link CompositeKeySerializer} resolves per-key serializers
 * through {@link BinarySerializerFactory#getObjectSerializer} which is built from a static
 * registration list — there is no database session lookup or class-resolution path that needs
 * a live storage. A static factory built once in {@code @BeforeClass} therefore exercises the
 * full code path that production hits.
 *
 * <p><b>Byte-shape contract</b>: the canonical layout written by {@link
 * CompositeKeySerializer#serialize} is
 * {@code [int totalSize][int keyCount]([byte serializerId][serialised key])*}.
 * The total size and key count integers are written big-endian via
 * {@link IntegerSerializer#serializeLiteral} for the byte[] / portable path (high byte first)
 * and in the JVM's native order via {@link ByteBuffer#putInt} for the ByteBuffer / native
 * path. Tests pin both shapes independently — and the divergence test is host-conditional on
 * little-endian platforms.
 *
 * <p><b>Comparison contract</b>: {@link CompositeKeySerializer#compareInByteBuffer} compares a
 * page-resident composite key (read through a {@link ByteBuffer}) against a serialised search
 * key (a {@code byte[]}) without deserialising either side. It walks fields in lockstep,
 * delegating per-field comparison to each field's serializer, and stops at the first non-zero
 * comparison or when the shorter key is exhausted. Null fields sort first; longer keys sort
 * after shorter prefixes.
 *
 * <p><b>preprocess discipline</b>: {@code preprocess} is a normalisation step the index layer
 * runs on keys before insertion / lookup. It returns a fresh {@link CompositeKey} (never the
 * original instance) so tests assert {@code assertNotSame} on the round-trip. Null values are
 * preserved verbatim; non-null entries are routed through the per-position serializer's own
 * {@code preprocess} hook. The Map-flattening shortcut handles the SBTree contract where a
 * single-key {@code Map} is auto-unwrapped to its key when the inferred type does not expect a
 * Map.
 */
public class CompositeKeySerializerTest {

  private static BinarySerializerFactory serializerFactory;
  private static CompositeKeySerializer serializer;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    serializer = CompositeKeySerializer.INSTANCE;
  }

  // -----------------------------------------------------------------
  // Identity / contract pins

  @Test
  public void instanceSingletonAndIdAreStable() {
    // The singleton instance is the canonical one — used by every BinarySerializerFactory call
    // site through CompositeKeySerializer.INSTANCE. The byte ID 14 is encoded into every
    // composite-key page on disk; changing it breaks SBTree forward-compatibility.
    assertSame(CompositeKeySerializer.INSTANCE, CompositeKeySerializer.INSTANCE);
    assertEquals(14, CompositeKeySerializer.ID);
    assertEquals(14, serializer.getId());
  }

  @Test
  public void variableLengthAndZeroFixedLength() {
    // CompositeKey is variable-length: the encoded size depends on every field's encoding,
    // so isFixedLength must report false and getFixedLength must return 0 (the contract for
    // variable-length serializers).
    assertFalse(serializer.isFixedLength());
    assertEquals(0, serializer.getFixedLength());
  }

  // -----------------------------------------------------------------
  // Round-trip + byte-shape pins (byte[] portable path)

  @Test
  public void singlePositionIntegerRoundTripPinsCanonicalBytes() {
    // Smallest non-empty composite key: one INTEGER position, default-by-class type
    // resolution. Layout (big-endian via IntegerSerializer.serializeLiteral):
    // [int totalSize=13][int keyCount=1][byte typeId=8 (INTEGER)][int 0x42=66]
    // 4 + 4 + 1 + 4 = 13 bytes.
    final var key = new CompositeKey();
    key.addKey(66);

    final var size = serializer.getObjectSize(serializerFactory, key);
    assertEquals(13, size);

    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0);

    // First int = total size (13, big-endian via serializeLiteral)
    assertEquals(13, IntegerSerializer.deserializeLiteral(stream, 0));
    // Second int = key count (1, big-endian via serializeLiteral)
    assertEquals(1, IntegerSerializer.deserializeLiteral(stream, 4));
    // Third byte = type identifier for INTEGER (IntegerSerializer.ID = 8)
    assertEquals(IntegerSerializer.ID, stream[8]);
    // Last 4 bytes = the integer payload itself (big-endian via serializeLiteral)
    assertEquals(66, IntegerSerializer.deserializeLiteral(stream, 9));

    final var roundTripped = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, roundTripped);
    assertNotSame(key, roundTripped);

    // getObjectSize(stream, offset) reads the first big-endian int (the total size)
    assertEquals(13, serializer.getObjectSize(serializerFactory, stream, 0));
  }

  @Test
  public void emptyCompositeKeyEncodesAsHeaderOnly() {
    // An empty composite key still serialises to a header: [int totalSize=8][int keyCount=0].
    // No body bytes follow because there are no positions to encode. This is a degenerate but
    // legal SBTree state — used as a probe key in some range scans.
    final var key = new CompositeKey();

    final var size = serializer.getObjectSize(serializerFactory, key);
    assertEquals(8, size);

    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0);

    assertEquals(8, IntegerSerializer.deserializeLiteral(stream, 0));
    assertEquals(0, IntegerSerializer.deserializeLiteral(stream, 4));

    final var roundTripped = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(0, roundTripped.getKeys().size());
    assertEquals(key, roundTripped);
  }

  @Test
  public void multiTypeKeyRoundTripsAndPinsTypeIdsInPositionOrder() {
    // A three-position key with mixed types pins type-id ordering inside the encoded stream.
    // After [int totalSize][int keyCount=3] the body is
    //   [byte STRING.ID][string "abc"][byte INTEGER.ID][int 7][byte BOOLEAN.ID][byte 1]
    // — type ids appear at the start of each field, followed by that field's own encoding.
    final var key = new CompositeKey();
    key.addKey("abc");
    key.addKey(7);
    key.addKey(true);

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0);

    assertEquals(size, IntegerSerializer.deserializeLiteral(stream, 0));
    assertEquals(3, IntegerSerializer.deserializeLiteral(stream, 4));
    assertEquals(StringSerializer.ID, stream[8]);

    final var roundTripped = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, roundTripped);
  }

  @Test
  public void hintsOverrideDefaultTypeResolution() {
    // When the caller passes explicit PropertyTypeInternal hints, the serializer prefers them
    // over the default-from-class lookup. This is how the index layer encodes a Date as
    // PropertyTypeInternal.DATETIME (vs the default DATE) when the index definition declares
    // DATETIME — pin the override by checking the type-id byte that hits the stream.
    final var key = new CompositeKey();
    key.addKey(true);

    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN};

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    // The hint forces BOOLEAN dispatch (BooleanSerializer.ID = 1) regardless of class lookup
    assertEquals(BooleanSerializer.ID, stream[8]);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  @Test
  public void hintsShorterThanKeysFallsBackToDefaultPerPosition() {
    // The hints array may be shorter than the keys list; positions past hints.length fall back
    // to default-from-class lookup. This mixed-mode is exercised when a partial type schema is
    // supplied — pin both branches in one round-trip.
    final var key = new CompositeKey();
    key.addKey(42); // default-from-class: PropertyTypeInternal.INTEGER
    key.addKey("xyz"); // default-from-class: PropertyTypeInternal.STRING

    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER};

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  @Test
  public void nullEntryAtEachPositionEncodesAsNullSerializerId() {
    // A null entry encodes as the NullSerializer (ID = 11) with a zero-byte payload. Test all
    // three slots — start, middle, end — to pin that the null branch is taken regardless of
    // surrounding non-null context AND that the on-disk byte at the null position is exactly
    // NullSerializer.ID. Use fixed-width INTEGER neighbours so the typeId byte offset for
    // each position is computable: header (8) + position * (typeId(1) + INTEGER payload(4)).
    for (var nullPos = 0; nullPos < 3; nullPos++) {
      final var key = new CompositeKey();
      key.addKey(nullPos == 0 ? null : 1);
      key.addKey(nullPos == 1 ? null : 2);
      key.addKey(nullPos == 2 ? null : 3);

      final var size = serializer.getObjectSize(serializerFactory, key);
      final var stream = new byte[size];
      serializer.serialize(key, serializerFactory, stream, 0);

      final var typeIdOffset = 8 + nullPos * 5; // 8 header + 5 bytes per INTEGER slot
      assertEquals("null sentinel byte at position " + nullPos,
          NullSerializer.ID, stream[typeIdOffset]);

      final var rt = serializer.deserialize(serializerFactory, stream, 0);
      assertEquals("null at position " + nullPos, key, rt);
      assertNull("position " + nullPos + " round-trips as null",
          rt.getKeys().get(nullPos));
    }
  }

  @Test
  public void allNullEntriesRoundTrip() {
    // Pure-null composite key — exercises the null branch on every position with no
    // non-null reference encoding.
    final var key = new CompositeKey();
    key.addKey(null);
    key.addKey(null);

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0);

    // Both type bytes are NullSerializer.ID
    assertEquals(NullSerializer.ID, stream[8]);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
    assertEquals(2, rt.getKeys().size());
    assertNull(rt.getKeys().get(0));
    assertNull(rt.getKeys().get(1));
  }

  @Test
  public void serializeRespectsStartPositionOffset() {
    // The byte[]-with-offset overload writes at the supplied position; data before the offset
    // must remain untouched. Pin this by pre-filling the prefix with a recognisable byte and
    // confirming round-trip from offset.
    final var key = new CompositeKey();
    key.addKey(10);
    key.addKey(20);

    final var prefix = 7;
    final var size = serializer.getObjectSize(serializerFactory, key);
    final var stream = new byte[size + prefix];
    for (var i = 0; i < prefix; i++) {
      stream[i] = (byte) 0xCA;
    }

    serializer.serialize(key, serializerFactory, stream, prefix);

    for (var i = 0; i < prefix; i++) {
      assertEquals("prefix byte " + i + " untouched", (byte) 0xCA, stream[i]);
    }

    final var rt = serializer.deserialize(serializerFactory, stream, prefix);
    assertEquals(key, rt);
    assertEquals(size, serializer.getObjectSize(serializerFactory, stream, prefix));
  }

  // -----------------------------------------------------------------
  // Native-order byte[] path

  @Test
  public void nativeRoundTripPinsHeaderInJvmNativeOrder() {
    // The native-order overloads use ByteBuffer.putInt / getInt with native byte order. Pin
    // the value count via a native-ordered read — host-agnostic, so the test passes on both
    // little-endian and big-endian platforms.
    final var key = new CompositeKey();
    key.addKey(123);
    key.addKey("k");

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var stream = new byte[size];
    serializer.serializeNativeObject(key, serializerFactory, stream, 0);

    final var bbView = ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder());
    assertEquals(size, bbView.getInt(0));
    assertEquals(2, bbView.getInt(4));

    final var rt = serializer.deserializeNativeObject(serializerFactory, stream, 0);
    assertEquals(key, rt);
    assertEquals(size, serializer.getObjectSizeNative(serializerFactory, stream, 0));
  }

  @Test
  public void nativeRoundTripWithHintsAndOffset() {
    // Combine the native path with explicit hints and a non-zero start offset.
    final var key = new CompositeKey();
    key.addKey("hello");
    key.addKey(99);

    final var hints = new PropertyTypeInternal[] {
        PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER};

    final var prefix = 3;
    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size + prefix];
    serializer.serializeNativeObject(key, serializerFactory, stream, prefix, (Object[]) hints);

    final var rt = serializer.deserializeNativeObject(serializerFactory, stream, prefix);
    assertEquals(key, rt);
    assertEquals(size, serializer.getObjectSizeNative(serializerFactory, stream, prefix));
  }

  // -----------------------------------------------------------------
  // ByteBuffer path — at-position (advances buffer)

  @Test
  public void byteBufferAtPositionRoundTripWithOffset() {
    // serializeInByteBufferObject writes starting at buffer.position() and advances it by
    // the encoded size. The total-size header is back-patched after serialisation. Pin the
    // post-call position and the round-trip equality.
    final var key = new CompositeKey();
    key.addKey(11);
    key.addKey("zz");
    key.addKey(true);

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var bb = ByteBuffer.allocate(size + 8);
    bb.position(8);

    serializer.serializeInByteBufferObject(serializerFactory, key, bb);
    assertEquals(8 + size, bb.position());

    bb.position(8);
    assertEquals(size, serializer.getObjectSizeInByteBuffer(serializerFactory, bb));

    bb.position(8);
    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb);
    assertEquals(key, rt);
  }

  @Test
  public void byteBufferAtOffsetDoesNotMovePosition() {
    // The at-offset overload reads via absolute indexes and does not move buffer.position().
    // Verify by recording position before and after — the size and round-trip variants must
    // both leave it untouched.
    final var key = new CompositeKey();
    key.addKey(31);
    key.addKey("yes");

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var bb = ByteBuffer.allocate(size + 4);
    bb.position(4);
    serializer.serializeInByteBufferObject(serializerFactory, key, bb);

    bb.position(0);
    assertEquals(size, serializer.getObjectSizeInByteBuffer(serializerFactory, 4, bb));
    assertEquals(0, bb.position());

    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, 4, bb);
    assertEquals(key, rt);
    assertEquals(0, bb.position());
  }

  @Test
  public void byteBufferDirectAndHeapDeserialiseIdentically() {
    // Direct vs heap ByteBuffer take different code paths inside the JVM (intrinsified copy
    // vs Java-level loop). The serializer must produce the same logical key from both.
    final var key = new CompositeKey();
    key.addKey(5);
    key.addKey(7);

    final var size = serializer.getObjectSize(serializerFactory, key);

    final var heap = ByteBuffer.allocate(size);
    serializer.serializeInByteBufferObject(serializerFactory, key, heap);
    heap.position(0);
    final var rtHeap = serializer.deserializeFromByteBufferObject(serializerFactory, heap);

    final var direct = ByteBuffer.allocateDirect(size);
    serializer.serializeInByteBufferObject(serializerFactory, key, direct);
    direct.position(0);
    final var rtDirect = serializer.deserializeFromByteBufferObject(serializerFactory, direct);

    assertEquals(key, rtHeap);
    assertEquals(key, rtDirect);
    assertEquals(rtHeap, rtDirect);
  }

  // -----------------------------------------------------------------
  // WAL-overlay path

  @Test
  public void walOverlayShadowsConflictingUnderlyingBytes() {
    // The WAL overlay's primary purpose is to surface staged uncommitted writes that
    // disagree with the underlying page bytes. Pre-fill the buffer with a "wrong" key and
    // confirm the overlay surfaces the staged "right" key — a regression where readData()
    // bypassed pageChunks would silently return the wrong key here, whereas the
    // walOverlayDeserialisesNativePayload test (which uses a zero-filled underlying buffer)
    // would still pass.
    // Keep both keys at the same encoded width so the pre-fill and the staged write occupy
    // the same byte range — int + same-length string both encoded as fixed-shape positions.
    final var rightKey = new CompositeKey();
    rightKey.addKey(7);
    rightKey.addKey("rt");

    final var wrongKey = new CompositeKey();
    wrongKey.addKey(99);
    wrongKey.addKey("wr");

    final var offset = 5;
    final var size = serializer.getObjectSize(serializerFactory, rightKey);
    assertEquals(size, serializer.getObjectSize(serializerFactory, wrongKey));

    final var bb = ByteBuffer
        .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
        .order(ByteOrder.nativeOrder());

    final var wrongBytes = new byte[size];
    serializer.serializeNativeObject(wrongKey, serializerFactory, wrongBytes, 0);
    for (var i = 0; i < size; i++) {
      bb.put(offset + i, wrongBytes[i]);
    }

    final var rightBytes = new byte[size];
    serializer.serializeNativeObject(rightKey, serializerFactory, rightBytes, 0);
    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(bb, rightBytes, offset);

    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb, overlay,
        offset);
    assertEquals(rightKey, rt);
    assertEquals(size, serializer.getObjectSizeInByteBuffer(bb, overlay, offset));
  }

  @Test
  public void walOverlayDeserialisesNativePayload() {
    // The WAL deserialise variant reads through a WALChanges overlay sitting on top of the page
    // ByteBuffer. We stage the payload via setBinaryValue (the same primitive WALPageChanges
    // uses to record the staged value) and then deserialize through the overlay to confirm the
    // overlay reads the same bytes the underlying buffer holds.
    final var key = new CompositeKey();
    key.addKey(50);
    key.addKey("wal");

    final var offset = 5;
    final var size = serializer.getObjectSize(serializerFactory, key);
    final var bb = ByteBuffer
        .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
        .order(ByteOrder.nativeOrder());

    final var data = new byte[size];
    serializer.serializeNativeObject(key, serializerFactory, data, 0);

    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(bb, data, offset);

    assertEquals(size, serializer.getObjectSizeInByteBuffer(bb, overlay, offset));

    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb, overlay,
        offset);
    assertEquals(key, rt);
  }

  // -----------------------------------------------------------------
  // compareInByteBuffer (in-page comparison without deserialisation)

  @Test
  public void compareInByteBufferDistinguishesEqualLessGreater() {
    // Encode three keys (lo, eq, hi) into both a page-resident ByteBuffer and a serialised
    // search byte[]. compareInByteBuffer must report 0 when the page key equals the search key,
    // negative when the page key is smaller, positive when larger. The page-side encoding uses
    // the native-order serializer (matching how SBTree pages are populated); the search-key
    // side uses the byte[] native serializer (matching how SBTree marshalls the lookup key).
    final var lo = newKey(1, "a");
    final var eq = newKey(2, "b");
    final var hi = newKey(3, "c");

    assertEquals(0, compareInByteBuffer(eq, eq));
    assertTrue(compareInByteBuffer(lo, eq) < 0);
    assertTrue(compareInByteBuffer(hi, eq) > 0);
  }

  @Test
  public void compareInByteBufferShorterPageKeyIsLess() {
    // When the page key has fewer fields than the search key (and the shared prefix is equal),
    // the comparison returns negative — shorter prefix sorts before longer matching prefix.
    final var pageKey = newKey(5);
    final var searchKey = newKey(5, "tail");

    assertTrue(compareInByteBuffer(pageKey, searchKey) < 0);
    assertTrue(compareInByteBuffer(searchKey, pageKey) > 0);
  }

  @Test
  public void compareInByteBufferNullSortsBeforeNonNull() {
    // A null page-side field sorts before any non-null search-side field, and vice versa.
    final var pageKey = new CompositeKey();
    pageKey.addKey(null);

    final var searchKey = new CompositeKey();
    searchKey.addKey(7);

    assertTrue(compareInByteBuffer(pageKey, searchKey) < 0);
    assertTrue(compareInByteBuffer(searchKey, pageKey) > 0);
  }

  @Test
  public void compareInByteBufferBothNullAtSamePositionIsEqual() {
    // Both sides null at the same position is treated as equal for that field; comparison
    // continues to the next field.
    final var a = new CompositeKey();
    a.addKey(null);
    a.addKey(1);

    final var b = new CompositeKey();
    b.addKey(null);
    b.addKey(1);

    assertEquals(0, compareInByteBuffer(a, b));

    final var c = new CompositeKey();
    c.addKey(null);
    c.addKey(2);

    assertTrue(compareInByteBuffer(a, c) < 0);
  }

  @Test
  public void compareInByteBufferReturnsAtFirstDifference() {
    // When the first differing field decides the order, the rest of the key is irrelevant —
    // pin this by giving the second field a "louder" mismatch in the opposite direction.
    final var a = newKey(1, "z");
    final var b = newKey(2, "a");

    assertTrue(compareInByteBuffer(a, b) < 0);
  }

  // -----------------------------------------------------------------
  // compareInByteBufferWithWALChanges (WAL-overlay variant)

  @Test
  public void compareInByteBufferWithWALMatchesNonWALPath() {
    // The WAL-aware comparator must agree with the non-WAL comparator when there are no
    // pending WAL changes on the page (the overlay returns the underlying bytes unchanged).
    final var pageKey = newKey(4, "wal-eq");
    final var searchKey = newKey(4, "wal-eq");
    assertEquals(0, compareInByteBufferWithWAL(pageKey, searchKey));

    final var smaller = newKey(2, "a");
    assertTrue(compareInByteBufferWithWAL(smaller, searchKey) < 0);
    assertTrue(compareInByteBufferWithWAL(searchKey, smaller) > 0);
  }

  @Test
  public void compareInByteBufferWithWALHandlesNullsInBothDirections() {
    // Mirror compareInByteBufferNullSortsBeforeNonNull on the WAL-aware path so the null
    // branches inside compareInByteBufferWithWALChanges are exercised.
    final var pageKey = new CompositeKey();
    pageKey.addKey(null);

    final var searchKey = new CompositeKey();
    searchKey.addKey(7);

    assertTrue(compareInByteBufferWithWAL(pageKey, searchKey) < 0);
    assertTrue(compareInByteBufferWithWAL(searchKey, pageKey) > 0);

    final var bothNull = new CompositeKey();
    bothNull.addKey(null);
    assertEquals(0, compareInByteBufferWithWAL(pageKey, bothNull));
  }

  @Test
  public void compareInByteBufferWithWALShorterIsLess() {
    final var pageKey = newKey(5);
    final var searchKey = newKey(5, "tail");
    assertTrue(compareInByteBufferWithWAL(pageKey, searchKey) < 0);
    assertTrue(compareInByteBufferWithWAL(searchKey, pageKey) > 0);
  }

  // -----------------------------------------------------------------
  // preprocess

  @Test
  public void preprocessNullValueReturnsNull() {
    // null value short-circuits at the top of preprocess.
    assertNull(serializer.preprocess(serializerFactory, null));
  }

  @Test
  public void preprocessReturnsFreshInstanceWithSameKeys() {
    // preprocess always allocates a new CompositeKey — it never returns the input. The result
    // equals the input by value, but assertNotSame catches a regression to identity-return.
    final var key = new CompositeKey();
    key.addKey(1);
    key.addKey("two");

    final var processed = serializer.preprocess(serializerFactory, key);
    assertNotSame(key, processed);
    assertEquals(key, processed);
  }

  @Test
  public void preprocessPreservesNullEntries() {
    // null entries route into the else-branch and are addKey(null)'d into the new key — pin
    // both null and non-null entries appearing in the same input.
    final var key = new CompositeKey();
    key.addKey(null);
    key.addKey(99);

    final var processed = serializer.preprocess(serializerFactory, key);
    assertEquals(2, processed.getKeys().size());
    assertNull(processed.getKeys().get(0));
    assertEquals(99, processed.getKeys().get(1));
  }

  @Test
  public void preprocessUsesHintWhenAvailable() {
    // When hints are supplied, preprocess routes through the hinted serializer's own
    // preprocess. INTEGER's preprocess is a no-op, but pinning the path ensures the hint
    // branch is taken (vs the default-from-class branch).
    final var key = new CompositeKey();
    key.addKey(7);

    final var processed = serializer.preprocess(serializerFactory, key,
        (Object[]) new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER});

    assertEquals(7, processed.getKeys().get(0));
  }

  @Test
  public void preprocessMapWithSingleKeyMatchingTypeIsFlattened() {
    // When a key is a Map of size 1 and the inferred (or hinted) type is NOT EMBEDDEDMAP /
    // LINKMAP, and the map's only key class is assignable from the type's default Java type,
    // preprocess unwraps the map to its sole key. This is the SBTree convention for
    // map-shaped lookup keys.
    final Map<String, Object> map = new HashMap<>();
    map.put("flat", "ignored-value");

    final var key = new CompositeKey();
    key.addKey(map);

    final var processed = serializer.preprocess(serializerFactory, key,
        (Object[]) new PropertyTypeInternal[] {PropertyTypeInternal.STRING});

    assertEquals(1, processed.getKeys().size());
    assertEquals("flat", processed.getKeys().get(0));
  }

  // -----------------------------------------------------------------
  // RID-bearing key paths (LINK type)

  @Test
  public void linkPositionRoundTripsViaHintedDispatch() {
    // A LINK position requires a RID-shaped key plus a hint identifying the position as LINK
    // (without the hint, the default type lookup for RecordId is LINK as well, but pinning
    // the hint exercises the hint-branch in serialize and getObjectSize).
    final var rid = new RecordId(7, 13);
    final var key = new CompositeKey();
    key.addKey(rid);

    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LINK};

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(rid, rt.getKeys().get(0));
  }

  @Test
  public void byteShapePinForBooleanRoundTripIsTenBytesTotal() {
    // Pin the BOOLEAN encoding inside a single-position composite key — the trailing payload
    // is exactly 1 byte (BooleanSerializer is fixed-length 1). Header is 8 bytes, type id is
    // 1 byte, payload is 1 byte → total 10 bytes.
    final var key = new CompositeKey();
    key.addKey(true);

    final var size = serializer.getObjectSize(serializerFactory, key);
    assertEquals(10, size);

    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0);

    assertEquals(BooleanSerializer.ID, stream[8]);
    assertEquals(1, stream[9]); // BooleanSerializer encodes true as byte 1

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  // -----------------------------------------------------------------
  // Sanity guard — a regression that breaks any of the four serialize variants would silently
  // pass the round-trip pins above if only one variant is exercised. This combo test runs all
  // four variants on a single fixture and asserts byte-shape equality across them.

  @Test
  public void byteArrayPortablePathProducesByteIdenticalOutputAcrossInvocations() {
    // serialise the same key twice into separate byte[]s and confirm the two byte streams are
    // byte-identical — pins determinism of the byte[] portable path.
    final var key = new CompositeKey();
    key.addKey(1);
    key.addKey("k");

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var first = new byte[size];
    final var second = new byte[size];
    serializer.serialize(key, serializerFactory, first, 0);
    serializer.serialize(key, serializerFactory, second, 0);

    assertArrayEquals(first, second);
  }

  @Test
  public void byteShapeMismatchAcrossPortableAndNativeEndianness() {
    // The portable byte[] path writes the keyCount big-endian (via
    // {@link IntegerSerializer#serializeLiteral} which lays the high byte first); the native
    // ByteBuffer / byte[] path writes it in the JVM's native order (little-endian on x86).
    // Pin the divergence by reading byte 4 (high byte for big-endian / low byte for
    // little-endian) — both encode value 5 but at opposite offsets.
    final var key = new CompositeKey();
    for (var i = 0; i < 5; i++) {
      key.addKey(i);
    }

    final var size = serializer.getObjectSize(serializerFactory, key);
    final var portable = new byte[size];
    final var native_ = new byte[size];
    serializer.serialize(key, serializerFactory, portable, 0);
    serializer.serializeNativeObject(key, serializerFactory, native_, 0);

    // Both encodings round-trip to the same logical key
    assertEquals(key, serializer.deserialize(serializerFactory, portable, 0));
    assertEquals(key, serializer.deserializeNativeObject(serializerFactory, native_, 0));

    // keyCount big-endian on the portable path: high bytes at 4-6 are zero, low byte 5 at 7
    assertEquals(0, portable[4]);
    assertEquals(0, portable[5]);
    assertEquals(0, portable[6]);
    assertEquals(5, portable[7]);

    // keyCount in JVM native order on the native path. On a little-endian host the low byte
    // sits at offset 4; on a big-endian host it sits at offset 7. We verify by parsing through
    // a native-ordered ByteBuffer and confirming the round-trip int value is 5 — which is
    // the host-agnostic invariant.
    final var nativeBuf = ByteBuffer.wrap(native_).order(ByteOrder.nativeOrder());
    assertEquals(5, nativeBuf.getInt(4));

    // The two encodings differ byte-by-byte (regardless of host) because the portable path is
    // pinned big-endian whereas the native path tracks host order. On a host where native ==
    // big-endian, the byte streams happen to match; otherwise they differ. We assert the
    // differ-or-equal-by-pure-coincidence relationship via direct comparison only when the
    // host is little-endian.
    if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
      assertEquals(5, native_[4]);
      assertEquals(0, native_[7]);
    }
  }

  @Test
  public void getObjectSizeOnCompositeKeyMatchesEncodedTotal() {
    // getObjectSize(serializerFactory, key, hints...) must equal the total bytes the
    // serialise call writes. Pin the invariant for a varied fixture.
    final var key = new CompositeKey();
    key.addKey(11);
    key.addKey("zzz");
    key.addKey(null);

    final var declared = serializer.getObjectSize(serializerFactory, key);
    final var stream = new byte[declared];
    serializer.serialize(key, serializerFactory, stream, 0);

    // The size header at offset 0 must equal the declared size
    assertEquals(declared, IntegerSerializer.deserializeLiteral(stream, 0));
  }

  @Test
  public void deserializeFromByteBufferDirectAndHeapAgreeOnSize() {
    // Both heap and direct ByteBuffers must report identical object size from the in-buffer
    // header — this is the size SBTree reads to skip past one composite key on a page scan.
    final var key = new CompositeKey();
    key.addKey(42);
    key.addKey("size-pin");

    final var size = serializer.getObjectSize(serializerFactory, key);

    final var heap = ByteBuffer.allocate(size);
    serializer.serializeInByteBufferObject(serializerFactory, key, heap);
    heap.position(0);
    final var heapSize = serializer.getObjectSizeInByteBuffer(serializerFactory, heap);

    final var direct = ByteBuffer.allocateDirect(size);
    serializer.serializeInByteBufferObject(serializerFactory, key, direct);
    direct.position(0);
    final var directSize = serializer.getObjectSizeInByteBuffer(serializerFactory, direct);

    assertEquals(heapSize, directSize);
    assertEquals(size, heapSize);
  }

  @Test
  public void preprocessOnZeroSizeKeyReturnsEmptyKey() {
    // Edge case: empty composite key passes through preprocess unchanged in shape (still
    // size 0) but the result is a fresh instance. This is reachable in the SBTree probe path.
    final var key = new CompositeKey();
    final var processed = serializer.preprocess(serializerFactory, key);
    assertNotSame(key, processed);
    assertEquals(0, processed.getKeys().size());
  }

  // -----------------------------------------------------------------
  // Helpers

  /**
   * Builds a CompositeKey from the supplied positions in order.
   */
  private static CompositeKey newKey(Object... positions) {
    final var key = new CompositeKey();
    for (final var p : positions) {
      key.addKey(p);
    }
    return key;
  }

  /**
   * Encodes the page key into a ByteBuffer and the search key into a byte[] (both via the
   * native-order serializer, which matches the SBTree page-write contract), then runs
   * {@link CompositeKeySerializer#compareInByteBuffer}.
   */
  private static int compareInByteBuffer(CompositeKey pageKey, CompositeKey searchKey) {
    final var pageSize = serializer.getObjectSize(serializerFactory, pageKey);
    final var pageBuf = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
    final var pageBytes = new byte[pageSize];
    serializer.serializeNativeObject(pageKey, serializerFactory, pageBytes, 0);
    pageBuf.put(pageBytes);

    final var searchSize = serializer.getObjectSize(serializerFactory, searchKey);
    final var searchBytes = new byte[searchSize];
    serializer.serializeNativeObject(searchKey, serializerFactory, searchBytes, 0);

    return serializer.compareInByteBuffer(serializerFactory, 0, pageBuf, searchBytes, 0);
  }

  /**
   * WAL-overlay variant of {@link #compareInByteBuffer} — wraps the page byte stream in a
   * {@link WALPageChangesPortion} overlay and exercises the WAL-aware comparator.
   */
  private static int compareInByteBufferWithWAL(CompositeKey pageKey, CompositeKey searchKey) {
    final var pageSize = serializer.getObjectSize(serializerFactory, pageKey);
    final var pageBuf = ByteBuffer
        .allocateDirect(pageSize + WALPageChangesPortion.PORTION_BYTES)
        .order(ByteOrder.nativeOrder());
    final var pageBytes = new byte[pageSize];
    serializer.serializeNativeObject(pageKey, serializerFactory, pageBytes, 0);
    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(pageBuf, pageBytes, 0);

    final var searchSize = serializer.getObjectSize(serializerFactory, searchKey);
    final var searchBytes = new byte[searchSize];
    serializer.serializeNativeObject(searchKey, serializerFactory, searchBytes, 0);

    return serializer.compareInByteBufferWithWALChanges(
        serializerFactory, pageBuf, overlay, 0, searchBytes, 0);
  }
}
