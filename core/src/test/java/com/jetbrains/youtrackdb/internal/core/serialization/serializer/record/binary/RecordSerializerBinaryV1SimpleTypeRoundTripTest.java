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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.HexFormat;
import org.junit.Before;
import org.junit.Test;

/**
 * Tier-1 tests pin the canonical byte encoding of every scalar value supported by
 * {@link RecordSerializerBinaryV1#serializeValue} and round-trip the value through
 * {@link RecordSerializerBinaryV1#deserializeValue}. Tier-2 tests round-trip the same
 * values through the full {@link RecordSerializerBinary} record path
 * ({@code toStream}/{@code fromStream}) to confirm the dispatch and header writing
 * remain consistent end-to-end.
 *
 * <p>Two-tier discipline: the value-level pins protect the on-disk wire format from
 * silent drift independent of the surrounding header layout, while the record-level
 * round-trips exercise {@link EntityImpl} dispatch and the schemaless property header
 * for every type. Both layers must agree, otherwise either the value encoding or the
 * record header has shifted and the test fails loudly with a hex diff.
 *
 * <p>Date/datetime tests use the database's GMT timezone (the default for an in-memory
 * database under {@link DbTestBase}) so that DAY-since-epoch encoding is deterministic;
 * any future change to {@code DateHelper.getDatabaseTimeZone} default will cause these
 * pins to fail until the developer evaluates whether the wire format actually changed.
 */
public class RecordSerializerBinaryV1SimpleTypeRoundTripTest extends DbTestBase {

  private static final HexFormat HEX = HexFormat.of();

  private RecordSerializerBinaryV1 v1;
  private RecordSerializer recordSerializer;

  @Before
  public void initSerializer() {
    v1 = new RecordSerializerBinaryV1();
    recordSerializer = RecordSerializerBinary.INSTANCE;
    // Force a deterministic timezone on the storage so DATE round-trips do not depend on
    // the JVM's default timezone (CET on most CI workers, GMT on others). Setting the
    // attribute on the session writes through to storage; getDatabaseTimeZone(session)
    // will then return GMT and the convertDayToTimezone calls in DATE encode/decode
    // become exact inverses.
    session.set(ATTRIBUTES.TIMEZONE, "GMT");
  }

  // -----------------------------------------------------------------
  // Tier 1: value-level encoding pins (direct serializeValue dispatch)
  // -----------------------------------------------------------------

  // BYTE: 1 raw byte, no varint encoding.

  @Test
  public void byteValueZeroEncodesAsSingleZeroByte() {
    pinValueEncoding(PropertyTypeInternal.BYTE, (byte) 0, "00");
  }

  @Test
  public void byteValueSevenEncodesAsSingleByte() {
    pinValueEncoding(PropertyTypeInternal.BYTE, (byte) 7, "07");
  }

  @Test
  public void byteValueMinValueEncodesAsTwosComplementByte() {
    // Byte.MIN_VALUE (-128) is 0x80 in two's complement.
    pinValueEncoding(PropertyTypeInternal.BYTE, Byte.MIN_VALUE, "80");
  }

  @Test
  public void byteValueMaxValueEncodesAsHigh7BitsByte() {
    pinValueEncoding(PropertyTypeInternal.BYTE, Byte.MAX_VALUE, "7f");
  }

  // BOOLEAN: 1 byte (0 or 1).

  @Test
  public void booleanTrueEncodesAsOneByte() {
    pinValueEncoding(PropertyTypeInternal.BOOLEAN, Boolean.TRUE, "01");
  }

  @Test
  public void booleanFalseEncodesAsZeroByte() {
    pinValueEncoding(PropertyTypeInternal.BOOLEAN, Boolean.FALSE, "00");
  }

  // SHORT: zig-zag varint.

  @Test
  public void shortValueZeroEncodesAsSingleZigZagByte() {
    pinValueEncoding(PropertyTypeInternal.SHORT, (short) 0, "00");
  }

  @Test
  public void shortValueOneEncodesAsZigZagPositive() {
    // Zig-zag(1) = 2 → varint 0x02.
    pinValueEncoding(PropertyTypeInternal.SHORT, (short) 1, "02");
  }

  @Test
  public void shortValueNegativeOneEncodesAsZigZagOdd() {
    // Zig-zag(-1) = 1 → varint 0x01.
    pinValueEncoding(PropertyTypeInternal.SHORT, (short) -1, "01");
  }

  @Test
  public void shortValueMinValueEncodesAsThreeByteVarint() {
    // Zig-zag(Short.MIN_VALUE = -32768) = 65535 = 0xFFFF
    // Varint: 0xFF 0xFF 0x03.
    pinValueEncoding(PropertyTypeInternal.SHORT, Short.MIN_VALUE, "ffff03");
  }

  @Test
  public void shortValueMaxValueEncodesAsThreeByteVarint() {
    // Zig-zag(Short.MAX_VALUE = 32767) = 65534 = 0xFFFE
    // Varint: 0xFE 0xFF 0x03.
    pinValueEncoding(PropertyTypeInternal.SHORT, Short.MAX_VALUE, "feff03");
  }

  // INTEGER: zig-zag varint via long.

  @Test
  public void integerValueZeroEncodesAsSingleZigZagByte() {
    pinValueEncoding(PropertyTypeInternal.INTEGER, 0, "00");
  }

  @Test
  public void integerValueSmallPositiveEncodesAsTwoByteVarint() {
    // Zig-zag(1234) = 2468 = 0x9A4 → varint 0xA4 0x13.
    pinValueEncoding(PropertyTypeInternal.INTEGER, 1234, "a413");
  }

  @Test
  public void integerValueMinValueEncodesAsFiveByteVarint() {
    // Zig-zag(Integer.MIN_VALUE) = (long) 0xFFFFFFFFL = 4294967295 → varint
    // 0xFF 0xFF 0xFF 0xFF 0x0F.
    pinValueEncoding(PropertyTypeInternal.INTEGER, Integer.MIN_VALUE, "ffffffff0f");
  }

  @Test
  public void integerValueMaxValueEncodesAsFiveByteVarint() {
    // Zig-zag(Integer.MAX_VALUE) = 0xFFFFFFFEL = 4294967294 → varint
    // 0xFE 0xFF 0xFF 0xFF 0x0F.
    pinValueEncoding(PropertyTypeInternal.INTEGER, Integer.MAX_VALUE, "feffffff0f");
  }

  // LONG: zig-zag varint.

  @Test
  public void longValueZeroEncodesAsSingleZigZagByte() {
    pinValueEncoding(PropertyTypeInternal.LONG, 0L, "00");
  }

  @Test
  public void longValueMinValueEncodesAsTenByteVarint() {
    // Zig-zag(Long.MIN_VALUE) = -1 unsigned = 0xFFFFFFFFFFFFFFFFL → 10-byte varint.
    pinValueEncoding(
        PropertyTypeInternal.LONG, Long.MIN_VALUE, "ffffffffffffffffff01");
  }

  @Test
  public void longValueMaxValueEncodesAsTenByteVarint() {
    // Zig-zag(Long.MAX_VALUE) = 0xFFFFFFFFFFFFFFFEL → 10-byte varint.
    pinValueEncoding(
        PropertyTypeInternal.LONG, Long.MAX_VALUE, "feffffffffffffffff01");
  }

  // FLOAT: 4 raw bytes from Float.floatToIntBits, big-endian.

  @Test
  public void floatValueZeroEncodesAsAllZeroes() {
    pinValueEncoding(PropertyTypeInternal.FLOAT, 0.0f, "00000000");
  }

  @Test
  public void floatValueOnePointFiveEncodesAsBigEndianBits() {
    // Float.floatToIntBits(1.5f) = 0x3FC00000.
    pinValueEncoding(PropertyTypeInternal.FLOAT, 1.5f, "3fc00000");
  }

  @Test
  public void floatValueNegativeZeroPreservesSignBit() {
    // Float.floatToIntBits(-0.0f) = 0x80000000 (sign bit set).
    pinValueEncoding(PropertyTypeInternal.FLOAT, -0.0f, "80000000");
  }

  @Test
  public void floatValueNanPinsCanonicalBitPattern() {
    // Float.floatToIntBits collapses NaN to 0x7FC00000.
    pinValueEncoding(PropertyTypeInternal.FLOAT, Float.NaN, "7fc00000");
  }

  // DOUBLE: 8 raw bytes from Double.doubleToLongBits, big-endian.

  @Test
  public void doubleValueZeroEncodesAsEightZeroBytes() {
    pinValueEncoding(PropertyTypeInternal.DOUBLE, 0.0d, "0000000000000000");
  }

  @Test
  public void doubleValueOnePointFiveEncodesAsBigEndianBits() {
    // Double.doubleToLongBits(1.5d) = 0x3FF8000000000000L.
    pinValueEncoding(PropertyTypeInternal.DOUBLE, 1.5d, "3ff8000000000000");
  }

  @Test
  public void doubleValueNegativeZeroPreservesSignBit() {
    pinValueEncoding(PropertyTypeInternal.DOUBLE, -0.0d, "8000000000000000");
  }

  @Test
  public void doubleValueNanPinsCanonicalBitPattern() {
    // Double.doubleToLongBits collapses NaN to 0x7FF8000000000000L.
    pinValueEncoding(PropertyTypeInternal.DOUBLE, Double.NaN, "7ff8000000000000");
  }

  // STRING: varint(byteLen) + UTF-8 bytes.

  @Test
  public void stringEmptyEncodesAsSingleZeroByte() {
    pinValueEncoding(PropertyTypeInternal.STRING, "", "00");
  }

  @Test
  public void stringSingleAsciiCharEncodesAsLengthOnePrefix() {
    // varint(1) = 0x02; "x" UTF-8 = 0x78.
    pinValueEncoding(PropertyTypeInternal.STRING, "x", "0278");
  }

  @Test
  public void stringMultibyteCyrillicEncodesAsUtf8() {
    // "д" U+0434 → UTF-8 0xD0 0xB4 (2 bytes); varint(2) = 0x04.
    pinValueEncoding(PropertyTypeInternal.STRING, "д", "04d0b4");
  }

  @Test
  public void stringSurrogatePairEmojiEncodesAsFourUtf8Bytes() {
    // "😀" U+1F600 → UTF-8 0xF0 0x9F 0x98 0x80 (4 bytes); varint(4) = 0x08.
    pinValueEncoding(PropertyTypeInternal.STRING, "😀", "08f09f9880");
  }

  @Test
  public void stringEmbeddedNullByteRoundTrips() {
    // "a b" → 3 UTF-8 bytes, varint(3) = 0x06.
    pinValueEncoding(PropertyTypeInternal.STRING, "a b", "0661" + "00" + "62");
  }

  // BINARY: varint(len) + raw bytes.

  @Test
  public void binaryEmptyEncodesAsSingleZeroByte() {
    pinValueEncoding(PropertyTypeInternal.BINARY, new byte[0], "00");
  }

  @Test
  public void binarySingleByteEncodesAsLengthOnePrefix() {
    // varint(1) = 0x02; payload 0xAB.
    pinValueEncoding(PropertyTypeInternal.BINARY, new byte[] {(byte) 0xAB}, "02ab");
  }

  @Test
  public void binaryAllByteValuesRoundTripsExactly() {
    var raw = new byte[256];
    for (var i = 0; i < 256; i++) {
      raw[i] = (byte) i;
    }
    var encoded = serializeValueBytes(PropertyTypeInternal.BINARY, raw);
    // varint(256) = 0x80 0x04 → 2 header bytes + 256 payload bytes.
    assertEquals(258, encoded.length);
    assertEquals((byte) 0x80, encoded[0]);
    assertEquals((byte) 0x04, encoded[1]);
    for (var i = 0; i < 256; i++) {
      assertEquals("byte " + i, raw[i], encoded[i + 2]);
    }
    var decoded = (byte[]) deserializeValueBytes(PropertyTypeInternal.BINARY, encoded);
    assertArrayEquals(raw, decoded);
  }

  // DECIMAL: 4 bytes scale + 4 bytes unscaledLen + unscaled bytes (BigInteger.toByteArray order).

  @Test
  public void decimalZeroEncodesScaleZeroSingleZeroByte() {
    // BigDecimal.ZERO → scale=0, unscaled=BigInteger.ZERO whose toByteArray is [0].
    // Layout: scale(4) 00000000 + unscaledLen(4) 00000001 + 00.
    pinValueEncoding(
        PropertyTypeInternal.DECIMAL, BigDecimal.ZERO, "00000000" + "00000001" + "00");
  }

  @Test
  public void decimalOneEncodesScaleZeroSingleOneByte() {
    // BigDecimal.ONE → scale=0, unscaled=1 → [0x01].
    pinValueEncoding(
        PropertyTypeInternal.DECIMAL, BigDecimal.ONE, "00000000" + "00000001" + "01");
  }

  @Test
  public void decimalNegativeOneEncodesAsTwosComplementByte() {
    // BigInteger(-1).toByteArray() = [0xFF].
    pinValueEncoding(
        PropertyTypeInternal.DECIMAL,
        BigDecimal.valueOf(-1),
        "00000000" + "00000001" + "ff");
  }

  @Test
  public void decimalHighPrecisionRoundTripsExactly() {
    // 31415926535897932384.626433832795028841 — pin scale only, hex of unscaled
    // BigInteger varies by JDK version of toByteArray.
    var value =
        new BigDecimal("31415926535897932384.626433832795028841");
    var encoded = serializeValueBytes(PropertyTypeInternal.DECIMAL, value);
    var decoded = (BigDecimal) deserializeValueBytes(PropertyTypeInternal.DECIMAL, encoded);
    assertEquals(value, decoded);
    assertEquals(0, value.compareTo(decoded));
    assertEquals(value.scale(), decoded.scale());
  }

  @Test
  public void decimalLargeNegativeRoundTripsWithSignBit() {
    var value = new BigDecimal("-99999999999999999999999999999999.0");
    var roundTripped = (BigDecimal) deserializeValueBytes(
        PropertyTypeInternal.DECIMAL,
        serializeValueBytes(PropertyTypeInternal.DECIMAL, value));
    assertEquals(value, roundTripped);
    assertEquals(value.scale(), roundTripped.scale());
  }

  @Test
  public void decimalSerializationLengthMatchesScaleAndUnscaledBigEndian() {
    // Half-precision sentinel: 0x7FFFFFFF as unscaled BigInteger value.
    var value = new BigDecimal(BigInteger.valueOf(0x7FFFFFFFL), 0);
    var encoded = serializeValueBytes(PropertyTypeInternal.DECIMAL, value);
    // scale (4 bytes BE) = 0x00000000, unscaledLen (4 bytes BE) = 0x00000004,
    // unscaled bytes = 0x7F 0xFF 0xFF 0xFF.
    assertEquals("0000000000000004" + "7fffffff", HEX.formatHex(encoded));
  }

  // DATETIME: zig-zag varint of Date.getTime() millis.

  @Test
  public void datetimeEpochEncodesAsSingleZeroByte() {
    pinValueEncoding(PropertyTypeInternal.DATETIME, new Date(0L), "00");
  }

  @Test
  public void datetimeOneMillisecondEncodesAsZigZagPositive() {
    // varint(zigzag(1)) = 0x02.
    pinValueEncoding(PropertyTypeInternal.DATETIME, new Date(1L), "02");
  }

  @Test
  public void datetimeNegativeOneMillisecondEncodesAsZigZagOdd() {
    // varint(zigzag(-1)) = 0x01.
    pinValueEncoding(PropertyTypeInternal.DATETIME, new Date(-1L), "01");
  }

  @Test
  public void datetimeNumberValueAcceptedAndRoundTripsAsDate() {
    // The serialize switch accepts a raw Number, but deserialization returns a Date.
    var encoded = serializeValueBytes(PropertyTypeInternal.DATETIME, 1_700_000_000_000L);
    var decoded = (Date) deserializeValueBytes(PropertyTypeInternal.DATETIME, encoded);
    assertEquals(new Date(1_700_000_000_000L), decoded);
  }

  // DATE: zig-zag varint of (millis-converted-to-GMT)/MILLISEC_PER_DAY.

  @Test
  public void dateEpochEncodesAsSingleZeroByte() {
    // Database default timezone is GMT for a fresh DbTestBase MEMORY db, so
    // convertDayToTimezone(GMT,GMT,0) = 0 → 0 days since epoch → varint(0) = 0x00.
    pinValueEncoding(PropertyTypeInternal.DATE, new Date(0L), "00");
  }

  @Test
  public void dateOneDayPastEpochEncodesAsZigZagPositive() {
    // 1 day = 86_400_000 ms → 1 day → varint(zigzag(1)) = 0x02.
    pinValueEncoding(PropertyTypeInternal.DATE, new Date(86_400_000L), "02");
  }

  @Test
  public void dateNumberValueAcceptedAndRoundTripsAsDate() {
    // Number value 86_400_000 millis → 1 day post-epoch.
    var encoded = serializeValueBytes(PropertyTypeInternal.DATE, 86_400_000L);
    var decoded = (Date) deserializeValueBytes(PropertyTypeInternal.DATE, encoded);
    assertEquals(new Date(86_400_000L), decoded);
  }

  // ---------------------------------------------------------
  // Tier 2: full-record round-trips via RecordSerializerBinary
  // ---------------------------------------------------------

  @Test
  public void allScalarTypesRoundTripInOneRecord() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setBoolean("flag", true);
    entity.setByte("byteVal", (byte) 7);
    entity.setShort("shortVal", (short) 1234);
    entity.setInt("intVal", 42);
    entity.setLong("longVal", 9_876_543_210L);
    entity.setFloat("floatVal", 1.5f);
    entity.setDouble("doubleVal", 1.5d);
    entity.setString("stringVal", "hello 😀");
    entity.setBinary("binaryVal", new byte[] {(byte) 0xCA, (byte) 0xFE});
    entity.setDate("dateVal", new Date(86_400_000L));
    entity.setDateTime("dateTimeVal", new Date(1_700_000_000_000L));
    entity.setProperty("decimalVal", new BigDecimal("3.14"), PropertyType.DECIMAL);

    var serialized = recordSerializer.toStream(session, entity);

    // First byte is the format-version byte (0x00 for V1).
    assertEquals((byte) 0, serialized[0]);

    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertEquals(Boolean.TRUE, extracted.getProperty("flag"));
    assertEquals((byte) 7, (byte) extracted.getProperty("byteVal"));
    assertEquals((short) 1234, (short) extracted.getProperty("shortVal"));
    assertEquals(42, (int) extracted.getProperty("intVal"));
    assertEquals(9_876_543_210L, (long) extracted.getProperty("longVal"));
    assertEquals(1.5f, (float) extracted.getProperty("floatVal"), 0.0f);
    assertEquals(1.5d, (double) extracted.getProperty("doubleVal"), 0.0d);
    assertEquals("hello 😀", extracted.getProperty("stringVal"));
    assertArrayEquals(
        new byte[] {(byte) 0xCA, (byte) 0xFE}, extracted.getProperty("binaryVal"));
    assertEquals(new Date(86_400_000L), extracted.getProperty("dateVal"));
    assertEquals(new Date(1_700_000_000_000L), extracted.getProperty("dateTimeVal"));
    assertEquals(new BigDecimal("3.14"), extracted.getProperty("decimalVal"));

    session.rollback();
  }

  @Test
  public void emptyRecordRoundTripsToEmptyHeaderAndNoProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();

    var serialized = recordSerializer.toStream(session, entity);
    // Top-level (non-embedded) record layout: version byte (0x00) + header-length
    // varint (0x00 for an empty entity). The class-name varint is only written by
    // serializeWithClassName, which is reserved for embedded entities.
    assertEquals(2, serialized.length);
    assertEquals((byte) 0, serialized[0]);
    assertEquals((byte) 0, serialized[1]);

    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});
    assertTrue(extracted.getPropertyNames().isEmpty());

    session.rollback();
  }

  @Test
  public void schemalessRecordPreservesPropertyNamesOrderAndCount() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("alpha", 1);
    entity.setString("beta", "two");
    entity.setLong("gamma", 3L);

    var serialized = recordSerializer.toStream(session, entity);
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertEquals(entity.getPropertyNames(), extracted.getPropertyNames());
    assertEquals(3, extracted.getPropertyNames().size());
    assertEquals(1, (int) extracted.getProperty("alpha"));
    assertEquals("two", extracted.getProperty("beta"));
    assertEquals(3L, (long) extracted.getProperty("gamma"));

    session.rollback();
  }

  @Test
  public void schemalessRecordWithLongStringRoundTrips() {
    // Exercises multi-byte varint length encoding inside the value region.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var sb = new StringBuilder();
    for (var i = 0; i < 1024; i++) {
      sb.append('a' + (i % 26));
    }
    var payload = sb.toString();
    entity.setString("big", payload);

    var serialized = recordSerializer.toStream(session, entity);
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertEquals(payload, extracted.getProperty("big"));
    session.rollback();
  }

  @Test
  public void schemalessRecordWithUtf8StringPreservesBytes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var s = "д😀 — γρα 한국어";
    entity.setString("text", s);

    var serialized = recordSerializer.toStream(session, entity);
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertEquals(s, extracted.getProperty("text"));
    session.rollback();
  }

  @Test
  public void deserializePartialReadsRequestedFieldsOnly() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("a", 1);
    entity.setString("b", "value-b");
    entity.setLong("c", 99L);

    var serialized = recordSerializer.toStream(session, entity);
    var partial = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, partial, new String[] {"b"});

    assertEquals("value-b", partial.getProperty("b"));
    assertNull(partial.getProperty("a"));
    assertNull(partial.getProperty("c"));
    session.rollback();
  }

  // ----------------
  // Helper machinery
  // ----------------

  /**
   * Asserts that the canonical byte encoding of {@code value} under {@code type} matches
   * {@code expectedHex}, then deserializes the bytes and confirms the round-tripped value
   * equals the original. The exact-byte assertion is the regression sentinel; the round-
   * trip equality covers the deserialize switch case.
   */
  private void pinValueEncoding(
      PropertyTypeInternal type, Object value, String expectedHex) {
    var encoded = serializeValueBytes(type, value);
    assertEquals(expectedHex, HEX.formatHex(encoded));
    var decoded = deserializeValueBytes(type, encoded);
    assertNotNull("round-trip yielded null for type " + type, decoded);
    if (value.getClass().isArray()) {
      // BINARY case — compare arrays by content.
      assertArrayEquals((byte[]) value, (byte[]) decoded);
    } else if (value instanceof Number expectedNum && decoded instanceof Number decodedNum
        && type == PropertyTypeInternal.FLOAT) {
      assertEquals(
          Float.floatToIntBits(expectedNum.floatValue()),
          Float.floatToIntBits(decodedNum.floatValue()));
    } else if (value instanceof Number expectedNum && decoded instanceof Number decodedNum
        && type == PropertyTypeInternal.DOUBLE) {
      assertEquals(
          Double.doubleToLongBits(expectedNum.doubleValue()),
          Double.doubleToLongBits(decodedNum.doubleValue()));
    } else if (type == PropertyTypeInternal.DATETIME || type == PropertyTypeInternal.DATE) {
      // Number values serialise via the Number branch but always deserialise as Date.
      var expectedMillis =
          (value instanceof Number n) ? n.longValue() : ((Date) value).getTime();
      assertEquals(expectedMillis, ((Date) decoded).getTime());
    } else {
      assertEquals(value, decoded);
    }
  }

  /**
   * Serialises {@code value} of the given {@code type} via the package-internal value
   * dispatch on a fresh {@link BytesContainer}; returns the produced byte slice (without
   * any surrounding record header). Schema/encryption parameters are unused for the
   * scalar types covered by this class, hence {@code null}.
   */
  private byte[] serializeValueBytes(PropertyTypeInternal type, Object value) {
    var bytes = new BytesContainer();
    v1.serializeValue(session, bytes, value, type, null, null, null);
    return bytes.fitBytes();
  }

  /**
   * Companion of {@link #serializeValueBytes(PropertyTypeInternal, Object)}: parses
   * {@code encoded} as a single {@code type}-encoded value through the value dispatch.
   */
  private Object deserializeValueBytes(PropertyTypeInternal type, byte[] encoded) {
    var bytes = new BytesContainer(encoded);
    return v1.deserializeValue(session, bytes, type, null, false, null);
  }
}
