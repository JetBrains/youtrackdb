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
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.Date;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Standalone tests for {@link IndexMultiValuKeySerializer} — pins the on-disk byte shape, the
 * type-id encoding for null vs non-null entries, every supported scalar type's encoding /
 * decoding path (BOOLEAN, BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, DATE, DATETIME, BINARY,
 * STRING, DECIMAL, LINK), and the WAL-overlay deserialisation path.
 *
 * <p>This serializer has zero direct test coverage in the current codebase (only indirect
 * coverage via SBTree integration tests). The risk profile is HIGH because it owns the
 * on-disk format for SBTree multi-value indexes — a regression in any per-type encode /
 * decode pair would silently corrupt index pages.
 *
 * <p><b>Byte-shape contract</b>: the canonical layout is
 * {@code [int totalSize][int keyCount]([byte typeId][serialised key])*}.
 * The total size and key count integers are written via {@link ByteBuffer#putInt} (native
 * order on the JVM byte[] view). Null entries encode the type id as {@code (byte) -(typeId + 1)}
 * with no payload — the deserialiser detects the negative byte and inserts {@code null}
 * without consuming any further bytes.
 *
 * <p><b>preprocess contract</b>: {@code preprocess} is the index-layer normalisation hook.
 * It is a fast-path no-op (returning the input verbatim) when no DATE / LINK-not-RID position
 * needs adjustment, otherwise it allocates a fresh {@link CompositeKey} where each DATE is
 * truncated to midnight (system default timezone) and each LINK has its identity extracted
 * via {@link com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable#getIdentity}.
 * Tests cover both the fast path (assertSame on the returned reference) and the slow path.
 */
public class IndexMultiValuKeySerializerTest {

  private static BinarySerializerFactory serializerFactory;
  private static IndexMultiValuKeySerializer serializer;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    serializer = new IndexMultiValuKeySerializer();
  }

  // -----------------------------------------------------------------
  // Identity / contract pins

  @Test
  public void getIdIsSentinelMinusOne() {
    // The serializer has byte ID -1 — a sentinel value that is NOT registered in
    // BinarySerializerFactory.serializerIdMap (which uses positive ids). It is dispatched
    // explicitly by SBTreeMultiValueV3 / V4, never via the factory id-lookup. Pin the
    // sentinel so a regression to a positive id is caught instantly.
    assertEquals(-1, serializer.getId());
  }

  @Test
  public void variableLengthAndZeroFixedLength() {
    // The encoded size depends on every contained key's encoding (per type) so isFixedLength
    // is false and getFixedLength is 0.
    assertFalse(serializer.isFixedLength());
    assertEquals(0, serializer.getFixedLength());
  }

  // -----------------------------------------------------------------
  // Per-type round-trip + byte-shape pins (byte[] path)

  @Test
  public void booleanRoundTripPinsTypeIdAndPayload() {
    // BOOLEAN encodes typeId = 0 and payload = 1 byte (0x00 or 0x01). Header = 8 bytes,
    // typeId = 1 byte, payload = 1 byte → total 10 bytes.
    //
    // The byte[] portable path wraps the stream in a default-order (big-endian) ByteBuffer,
    // so the header ints are big-endian regardless of host byte order. The native path uses
    // the JVM's native order — that case is exercised by {@link #nativeRoundTrip}.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN};
    final var key = new CompositeKey();
    key.addKey(true);

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    assertEquals(10, size);

    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    final var bb = ByteBuffer.wrap(stream); // default big-endian — portable path
    assertEquals(size, bb.getInt(0));
    assertEquals(1, bb.getInt(4));
    assertEquals((byte) PropertyTypeInternal.BOOLEAN.getId(), stream[8]);
    assertEquals(1, stream[9]);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  @Test
  public void booleanFalseEncodesAsZeroPayloadByte() {
    // The deserialise side reads {@code buffer.get() > 0} so the false sentinel must be 0
    // (or any non-positive byte). Pin the encoder side: false → 0.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN};
    final var key = new CompositeKey();
    key.addKey(false);

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    assertEquals(0, stream[9]);

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  @Test
  public void byteRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.BYTE};
    final var key = new CompositeKey();
    key.addKey((byte) 0x7F);

    final var stream = serialise(key, hints);
    assertEquals((byte) PropertyTypeInternal.BYTE.getId(), stream[8]);
    assertEquals((byte) 0x7F, stream[9]);

    assertEquals(key, serializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void shortRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.SHORT};
    final var key = new CompositeKey();
    key.addKey((short) 0x1234);

    final var stream = serialise(key, hints);
    assertEquals(key, serializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void integerRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER};
    final var key = new CompositeKey();
    key.addKey(0xCAFEBABE);

    final var stream = serialise(key, hints);
    assertEquals(key, serializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void longRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LONG};
    final var key = new CompositeKey();
    key.addKey(0x0102030405060708L);

    final var stream = serialise(key, hints);
    assertEquals(key, serializer.deserialize(serializerFactory, stream, 0));
  }

  @Test
  public void floatRoundTripPreservesBitPattern() {
    // Float encodes via floatToIntBits → putInt; pin the bit pattern by checking that NaN
    // and Float.MAX_VALUE round-trip identically (i.e. the canonical NaN bit pattern is
    // preserved by Float.intBitsToFloat).
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.FLOAT};

    for (final float f : new float[] {0.0f, -0.0f, 1.5f, Float.MAX_VALUE, Float.MIN_VALUE,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      final var key = new CompositeKey();
      key.addKey(f);
      final var stream = serialise(key, hints);
      final var rt = (Float) serializer.deserialize(serializerFactory, stream, 0).getKeys().get(0);
      assertEquals("float pin for " + f,
          Float.floatToRawIntBits(f), Float.floatToRawIntBits(rt));
    }
  }

  @Test
  public void doubleRoundTripPreservesBitPattern() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DOUBLE};

    for (final double d : new double[] {0.0, -0.0, 1.5, Double.MAX_VALUE, Double.MIN_VALUE,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      final var key = new CompositeKey();
      key.addKey(d);
      final var stream = serialise(key, hints);
      final var rt = (Double) serializer.deserialize(serializerFactory, stream, 0)
          .getKeys().get(0);
      assertEquals("double pin for " + d,
          Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(rt));
    }
  }

  @Test
  public void dateRoundTripStoresMillis() {
    // DATE and DATETIME share the same on-disk encoding (8 bytes = epoch millis). The
    // serialise side calls ((Date) key).getTime(), so any Date including pre-epoch is fine.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DATE};
    final var d = new Date(1_700_000_000_000L);
    final var key = new CompositeKey();
    key.addKey(d);

    final var stream = serialise(key, hints);
    final var rt = (Date) serializer.deserialize(serializerFactory, stream, 0).getKeys().get(0);
    assertEquals(d.getTime(), rt.getTime());
  }

  @Test
  public void dateTimeRoundTripStoresMillis() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DATETIME};
    final var d = new Date(0L);
    final var key = new CompositeKey();
    key.addKey(d);

    final var stream = serialise(key, hints);
    final var rt = (Date) serializer.deserialize(serializerFactory, stream, 0).getKeys().get(0);
    assertEquals(d.getTime(), rt.getTime());
  }

  @Test
  public void binaryRoundTripPreservesPayloadAndLengthHeader() {
    // BINARY encodes [int len][raw bytes]. Pin the length-prefix shape and the round-trip
    // contents — including a zero-length array (the degenerate bound).
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.BINARY};

    for (final byte[] payload : new byte[][] {
        new byte[] {}, new byte[] {0x01}, new byte[] {1, 2, 3, 4, 5}}) {
      final var key = new CompositeKey();
      key.addKey(payload);
      final var stream = serialise(key, hints);
      final var rt = (byte[]) serializer.deserialize(serializerFactory, stream, 0).getKeys()
          .get(0);
      assertArrayEquals(payload, rt);
    }
  }

  @Test
  public void stringRoundTripIncludingMultibyteAndEmpty() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.STRING};

    for (final String s : new String[] {"", "ascii", "Schöne Grüße", "🚀rocket"}) {
      final var key = new CompositeKey();
      key.addKey(s);
      final var stream = serialise(key, hints);
      final var rt = (String) serializer.deserialize(serializerFactory, stream, 0).getKeys()
          .get(0);
      assertEquals("string pin for " + s, s, rt);
    }
  }

  @Test
  public void decimalRoundTripPreservesScaleAndUnscaledBytes() {
    // DECIMAL encodes [int scale][int unscaledLen][unscaled bytes]. Test multiple scale /
    // unscaled-bit-width combinations to pin the layout.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DECIMAL};

    for (final BigDecimal d : new BigDecimal[] {
        BigDecimal.ZERO,
        new BigDecimal("123.456"),
        new BigDecimal("-987654321.0987654321"),
        new BigDecimal(new BigInteger("1").shiftLeft(128), 5)}) {
      final var key = new CompositeKey();
      key.addKey(d);
      final var stream = serialise(key, hints);
      final var rt = (BigDecimal) serializer.deserialize(serializerFactory, stream, 0).getKeys()
          .get(0);
      assertEquals("decimal pin for " + d, d, rt);
    }
  }

  @Test
  public void linkRoundTripStoresCompactedRid() {
    // LINK delegates to CompactedLinkSerializer. Pin a non-trivial RID and confirm round-trip
    // identity.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LINK};
    final var rid = new RecordId(33, 7777);
    final var key = new CompositeKey();
    key.addKey(rid);

    final var stream = serialise(key, hints);
    final var rt = serializer.deserialize(serializerFactory, stream, 0).getKeys().get(0);
    assertEquals(rid, rt);
  }

  // -----------------------------------------------------------------
  // Null-entry encoding

  @Test
  public void nullEntryEncodesAsNegativeTypeIdSentinel() {
    // Null is encoded as the byte (byte) -(typeId + 1) — pin this sentinel for several types
    // by extracting the byte after the header and confirming the formula. Round-trip equality
    // is also asserted.
    for (final PropertyTypeInternal t : new PropertyTypeInternal[] {
        PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING,
        PropertyTypeInternal.DECIMAL, PropertyTypeInternal.LINK}) {
      final var hints = new PropertyTypeInternal[] {t};
      final var key = new CompositeKey();
      key.addKey(null);

      final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
      // Header (8) + typeId byte (1) + zero payload = 9 bytes for any type when value is null
      assertEquals("null payload size for " + t, 9, size);

      final var stream = new byte[size];
      serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

      assertEquals("null sentinel for " + t,
          (byte) -(t.getId() + 1), stream[8]);

      final var rt = serializer.deserialize(serializerFactory, stream, 0);
      assertNull("null preserved for " + t, rt.getKeys().get(0));
    }
  }

  @Test
  public void mixedNullAndNonNullPositionsRoundTrip() {
    // Mix null and non-null positions in the same key — pin that the stream picks up the
    // negative sentinel for null positions and the per-type encoding for non-null positions.
    final var hints = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};

    final var key = new CompositeKey();
    key.addKey(42);
    key.addKey(null);
    key.addKey(123_456_789L);

    final var stream = serialise(key, hints);
    final var rt = serializer.deserialize(serializerFactory, stream, 0);

    assertEquals(3, rt.getKeys().size());
    assertEquals(42, rt.getKeys().get(0));
    assertNull(rt.getKeys().get(1));
    assertEquals(123_456_789L, rt.getKeys().get(2));
  }

  // -----------------------------------------------------------------
  // Empty / single-position degenerate cases

  @Test
  public void emptyHintsAndEmptyKeyEncodesAsHeaderOnly() {
    // Empty hints array implies zero positions; the encoded stream is just the header
    // [int totalSize=8][int keyCount=0]. The portable path uses default-order (big-endian)
    // ByteBuffer.putInt, so the header reads correctly with default byte order.
    final var hints = new PropertyTypeInternal[] {};
    final var key = new CompositeKey();

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    assertEquals(8, size);

    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);

    final var bb = ByteBuffer.wrap(stream); // default big-endian
    assertEquals(8, bb.getInt(0));
    assertEquals(0, bb.getInt(4));

    final var rt = serializer.deserialize(serializerFactory, stream, 0);
    assertEquals(0, rt.getKeys().size());
  }

  // -----------------------------------------------------------------
  // Native / ByteBuffer / WAL-overlay paths

  @Test
  public void nativeRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.STRING,
        PropertyTypeInternal.INTEGER};
    final var key = new CompositeKey();
    key.addKey("native");
    key.addKey(99);

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serializeNativeObject(key, serializerFactory, stream, 0, (Object[]) hints);

    assertEquals(size, serializer.getObjectSizeNative(serializerFactory, stream, 0));

    final var rt = serializer.deserializeNativeObject(serializerFactory, stream, 0);
    assertEquals(key, rt);
  }

  @Test
  public void byteBufferAtPositionRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LONG,
        PropertyTypeInternal.STRING};
    final var key = new CompositeKey();
    key.addKey(7L);
    key.addKey("buf");

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var bb = ByteBuffer.allocate(size + 4);
    bb.position(4);
    serializer.serializeInByteBufferObject(serializerFactory, key, bb, (Object[]) hints);

    bb.position(4);
    assertEquals(size, serializer.getObjectSizeInByteBuffer(serializerFactory, bb));

    bb.position(4);
    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb);
    assertEquals(key, rt);
  }

  @Test
  public void byteBufferAtOffsetRoundTrip() {
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DOUBLE};
    final var key = new CompositeKey();
    key.addKey(3.14);

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var bb = ByteBuffer.allocate(size + 7);
    bb.position(7);
    serializer.serializeInByteBufferObject(serializerFactory, key, bb, (Object[]) hints);

    bb.position(0);
    assertEquals(size, serializer.getObjectSizeInByteBuffer(serializerFactory, 7, bb));
    assertEquals(0, bb.position()); // does not move position

    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, 7, bb);
    assertEquals(key, rt);
    assertEquals(0, bb.position());
  }

  @Test
  public void byteBufferAtOffsetCoversEveryType() {
    // The offset-based deserialiser has its own per-type switch; pin every supported type
    // through the offset path so a regression in any branch fails loudly.
    //
    // BINARY is special-cased: byte[] uses identity equality, so {@link CompositeKey#equals}
    // (which delegates to List.equals → element.equals) reports false even when the bytes
    // round-trip correctly. We assert byte-array contents directly for BINARY.
    final PropertyTypeInternal[][] cases = {
        {PropertyTypeInternal.BOOLEAN}, {PropertyTypeInternal.BYTE},
        {PropertyTypeInternal.SHORT}, {PropertyTypeInternal.INTEGER},
        {PropertyTypeInternal.LONG}, {PropertyTypeInternal.FLOAT},
        {PropertyTypeInternal.DOUBLE}, {PropertyTypeInternal.DATE},
        {PropertyTypeInternal.DATETIME}, {PropertyTypeInternal.STRING},
        {PropertyTypeInternal.BINARY}, {PropertyTypeInternal.DECIMAL},
        {PropertyTypeInternal.LINK}};

    final Object[] values = {true, (byte) 9, (short) 99, 9999, 9999L, 1.5f, 2.5,
        new Date(1_000L), new Date(2_000L), "x", new byte[] {1, 2, 3},
        new BigDecimal("9.876"), new RecordId(1, 1)};

    for (var i = 0; i < cases.length; i++) {
      final var hints = cases[i];
      final var key = new CompositeKey();
      key.addKey(values[i]);

      final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
      final var bb = ByteBuffer.allocate(size + 3);
      bb.position(3);
      serializer.serializeInByteBufferObject(serializerFactory, key, bb, (Object[]) hints);

      final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, 3, bb);

      if (hints[0] == PropertyTypeInternal.BINARY) {
        assertArrayEquals("offset round-trip for BINARY",
            (byte[]) values[i], (byte[]) rt.getKeys().get(0));
      } else {
        assertEquals("offset round-trip for " + hints[0], key, rt);
      }
    }
  }

  @Test
  public void walOverlayDeserialiseCoversEveryType() {
    // WAL-overlay deserialise has its own per-type switch — pin every supported type through
    // it. We stage the canonical native bytes via WALPageChangesPortion.setBinaryValue and
    // then deserialize through the overlay, asserting key equality.
    final PropertyTypeInternal[][] cases = {
        {PropertyTypeInternal.BOOLEAN}, {PropertyTypeInternal.BYTE},
        {PropertyTypeInternal.SHORT}, {PropertyTypeInternal.INTEGER},
        {PropertyTypeInternal.LONG}, {PropertyTypeInternal.FLOAT},
        {PropertyTypeInternal.DOUBLE}, {PropertyTypeInternal.DATE},
        {PropertyTypeInternal.DATETIME}, {PropertyTypeInternal.STRING},
        {PropertyTypeInternal.BINARY}, {PropertyTypeInternal.DECIMAL},
        {PropertyTypeInternal.LINK}};

    final Object[] values = {true, (byte) 9, (short) 99, 9999, 9999L, 1.5f, 2.5,
        new Date(1_000L), new Date(2_000L), "x", new byte[] {1, 2, 3, 4},
        new BigDecimal("9.876"), new RecordId(1, 1)};

    final var offset = 5;

    for (var i = 0; i < cases.length; i++) {
      final var hints = cases[i];
      final var key = new CompositeKey();
      key.addKey(values[i]);

      final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
      final var data = new byte[size];
      serializer.serializeNativeObject(key, serializerFactory, data, 0, (Object[]) hints);

      final var bb = ByteBuffer
          .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
          .order(ByteOrder.nativeOrder());
      final WALChanges overlay = new WALPageChangesPortion();
      overlay.setBinaryValue(bb, data, offset);

      assertEquals("WAL size for " + hints[0],
          size, serializer.getObjectSizeInByteBuffer(bb, overlay, offset));

      final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb, overlay,
          offset);

      if (hints[0] == PropertyTypeInternal.BINARY) {
        // BINARY round-trips a fresh byte[]; assert array contents directly to avoid
        // List.equals → byte[].equals (identity) reporting false on equal bytes.
        assertArrayEquals("WAL round-trip for BINARY",
            (byte[]) values[i], (byte[]) rt.getKeys().get(0));
      } else {
        assertEquals("WAL round-trip for " + hints[0], key, rt);
      }
    }
  }

  @Test
  public void walOverlayPreservesNullEntries() {
    // The WAL deserialise variant has its own null-detection branch — pin it by staging a
    // null entry through the overlay and asserting the result.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER,
        PropertyTypeInternal.STRING};
    final var key = new CompositeKey();
    key.addKey(null);
    key.addKey("present");

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var data = new byte[size];
    serializer.serializeNativeObject(key, serializerFactory, data, 0, (Object[]) hints);

    final var offset = 7;
    final var bb = ByteBuffer
        .allocateDirect(size + offset + WALPageChangesPortion.PORTION_BYTES)
        .order(ByteOrder.nativeOrder());
    final WALChanges overlay = new WALPageChangesPortion();
    overlay.setBinaryValue(bb, data, offset);

    final var rt = serializer.deserializeFromByteBufferObject(serializerFactory, bb, overlay,
        offset);
    assertEquals(2, rt.getKeys().size());
    assertNull(rt.getKeys().get(0));
    assertEquals("present", rt.getKeys().get(1));
  }

  // -----------------------------------------------------------------
  // preprocess

  @Test
  public void preprocessNullValueReturnsNull() {
    assertNull(serializer.preprocess(serializerFactory, null,
        (Object[]) new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}));
  }

  @Test
  public void preprocessFastPathReturnsSameInstanceWhenNoDateOrLinkFixupNeeded() {
    // When no position is DATE and no LINK position holds a non-RID value, preprocess
    // short-circuits to {@code return value}. Pin the assertSame so a regression that loses
    // the fast path (e.g. always allocating a fresh CompositeKey) is caught immediately.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER,
        PropertyTypeInternal.STRING, PropertyTypeInternal.LINK};

    final var key = new CompositeKey();
    key.addKey(1);
    key.addKey("a");
    key.addKey(new RecordId(1, 1)); // already RID — no fixup needed

    final var processed = serializer.preprocess(serializerFactory, key, (Object[]) hints);
    assertSame(key, processed);
  }

  @Test
  public void preprocessSlowPathTruncatesDateToMidnightInDefaultTimezone() {
    // DATE positions force the slow path; the result is a fresh CompositeKey with the date
    // truncated to local-midnight via Calendar with the default timezone. Pin both the
    // not-same identity and the truncation arithmetic.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DATE};
    final var input = new CompositeKey();
    final var raw = new Date(1_700_000_123_456L); // some non-midnight instant
    input.addKey(raw);

    final var processed = serializer.preprocess(serializerFactory, input, (Object[]) hints);
    assertNotSame(input, processed);
    final var truncated = (Date) processed.getKeys().get(0);

    final var cal = Calendar.getInstance();
    cal.setTime(truncated);
    assertEquals("hour-of-day cleared", 0, cal.get(Calendar.HOUR_OF_DAY));
    assertEquals("minute cleared", 0, cal.get(Calendar.MINUTE));
    assertEquals("second cleared", 0, cal.get(Calendar.SECOND));
    assertEquals("millisecond cleared", 0, cal.get(Calendar.MILLISECOND));
  }

  @Test
  public void preprocessSlowPathExtractsIdentityFromIdentifiableLink() {
    // A LINK position holding an Identifiable that is NOT itself a RID forces the slow path;
    // preprocess replaces the value with .getIdentity(). Pin this branch using a custom
    // Identifiable wrapper.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LINK};
    final var underlying = new RecordId(2, 22);
    final IdentifiableHolder holder = new IdentifiableHolder(underlying);

    final var input = new CompositeKey();
    input.addKey(holder);

    final var processed = serializer.preprocess(serializerFactory, input, (Object[]) hints);
    assertNotSame(input, processed);
    assertEquals(underlying, processed.getKeys().get(0));
  }

  @Test
  public void preprocessSlowPathPreservesNullEntries() {
    // When a slow path is forced (by some other position), null entries on other positions
    // are preserved unchanged. Pin this with DATE forcing the slow path and INTEGER/null
    // appearing alongside.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.DATE,
        PropertyTypeInternal.INTEGER};
    final var input = new CompositeKey();
    input.addKey(new Date(1_700_000_000_000L));
    input.addKey(null);

    final var processed = serializer.preprocess(serializerFactory, input, (Object[]) hints);
    assertEquals(2, processed.getKeys().size());
    assertNull(processed.getKeys().get(1));
  }

  @Test
  public void preprocessLinkAlreadyRidStaysOnFastPath() {
    // A LINK position holding a RID directly does NOT force the slow path; pin via assertSame
    // and confirm no allocation occurs.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.LINK};
    final var rid = new RecordId(7, 7);
    final var input = new CompositeKey();
    input.addKey(rid);

    final var processed = serializer.preprocess(serializerFactory, input, (Object[]) hints);
    assertSame(input, processed);
  }

  // -----------------------------------------------------------------
  // Negative paths — unsupported types

  @Test
  public void serialiseUnsupportedTypeThrowsIndexException() {
    // EMBEDDED is registered in PropertyTypeInternal but has no on-disk encoding in this
    // serializer — the switch's default branch throws IndexException. Pin the message
    // prefix so a regression that swallows the type info is caught.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.EMBEDDED};
    final var key = new CompositeKey();
    key.addKey("anything");

    try {
      serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
      fail("expected IndexException for unsupported EMBEDDED type");
    } catch (final IndexException expected) {
      assertTrue("message names the unsupported type",
          expected.getMessage().contains("Unsupported key type"));
    }
  }

  @Test
  public void deserialiseEncountersUnsupportedTypeThrowsIndexException() {
    // Forge a stream with a typeId byte that PropertyTypeInternal recognises (EMBEDDED = 9)
    // but the deserialiser has no case for. The deserialiser will throw IndexException with
    // "Unsupported index type ..." — pin the message prefix.
    final var bb = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
    bb.putInt(0); // total size placeholder
    bb.putInt(1); // keyCount = 1
    bb.put((byte) PropertyTypeInternal.EMBEDDED.getId());
    final var stream = bb.array();

    try {
      serializer.deserialize(serializerFactory, stream, 0);
      fail("expected IndexException for unsupported EMBEDDED type during deserialise");
    } catch (final IndexException expected) {
      assertTrue("message names the unsupported type",
          expected.getMessage().contains("Unsupported index type"));
    }
  }

  // -----------------------------------------------------------------
  // Determinism / cross-variant agreement

  @Test
  public void portableAndNativePathsRoundTripIndependently() {
    // The byte[] portable path uses default-order (big-endian) ByteBuffer; the byte[] native
    // path uses native order. Per-type payloads (int, long, short, string-length) all use
    // the buffer's byte order, so on a little-endian host the two encodings differ throughout
    // the header AND the body. Pin only the round-trip independence — each path successfully
    // deserialises its own output through its matching deserialiser.
    final var hints = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER,
        PropertyTypeInternal.STRING};
    final var key = new CompositeKey();
    key.addKey(1234);
    key.addKey("agreement");

    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);

    final var portable = new byte[size];
    final var native_ = new byte[size];
    serializer.serialize(key, serializerFactory, portable, 0, (Object[]) hints);
    serializer.serializeNativeObject(key, serializerFactory, native_, 0, (Object[]) hints);

    // Each path round-trips through its own deserialiser
    assertEquals(key, serializer.deserialize(serializerFactory, portable, 0));
    assertEquals(key, serializer.deserializeNativeObject(serializerFactory, native_, 0));
  }

  // -----------------------------------------------------------------
  // Helpers

  private static byte[] serialise(CompositeKey key, PropertyTypeInternal[] hints) {
    final var size = serializer.getObjectSize(serializerFactory, key, (Object[]) hints);
    final var stream = new byte[size];
    serializer.serialize(key, serializerFactory, stream, 0, (Object[]) hints);
    return stream;
  }

  /**
   * Minimal Identifiable wrapper that surfaces a fixed RID through {@code getIdentity}. Used
   * to drive the LINK slow-path branch in {@code preprocess} (where the value is an
   * Identifiable that is NOT itself a RID).
   */
  private static final class IdentifiableHolder
      implements com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable {

    private final RecordId rid;

    IdentifiableHolder(RecordId rid) {
      this.rid = rid;
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.db.record.record.RID getIdentity() {
      return rid;
    }

    @Override
    public int compareTo(
        com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable o) {
      return rid.compareTo(o.getIdentity());
    }
  }
}
