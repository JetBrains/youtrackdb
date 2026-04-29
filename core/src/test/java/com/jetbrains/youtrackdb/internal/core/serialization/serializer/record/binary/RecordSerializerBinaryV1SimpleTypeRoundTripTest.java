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
 * <p>Date/datetime tests force the database's TIMEZONE attribute to GMT in {@code @Before}
 * — DbTestBase otherwise leaves the storage timezone unset, in which case
 * {@code DateHelper.getDatabaseTimeZone} falls back to {@code TimeZone.getDefault()} and
 * the encoded DAY-since-epoch shifts by the JVM offset. The override pins the encoding so
 * worker timezone (CET on most CI hosts, GMT on others) does not change the byte shapes.
 *
 * <p><b>Adding a new scalar type:</b> place the canonical byte-encoding pin in the Tier 1
 * block (one {@code @Test} per representative value — zero, smallest positive, smallest
 * negative, MIN, MAX, plus type-specific edge cases like NaN / infinity / multi-byte UTF-8)
 * and add the new type to {@link #allScalarTypesRoundTripInOneRecord} for end-to-end
 * dispatch coverage. Collection / embedded / link types belong in a companion
 * {@code RecordSerializerBinaryV1CollectionRoundTripTest} (added in a later step).
 *
 * <p><b>Latent production gap (carried forward):</b> the {@code BytesContainer}
 * overload of {@code RecordSerializerBinaryV1#deserializeValue} (the one this class's
 * Tier 1 helpers call) does not validate length-prefix fields the way its
 * {@code ReadBytesContainer} sibling does — STRING, BINARY, and DECIMAL all happily
 * read with attacker-supplied lengths up to {@code Integer.MAX_VALUE}. The negative
 * tests live with the {@code ReadBytesContainer} guards in
 * {@code RecordSerializerBinaryV1GuardTest}; this class deliberately only feeds
 * round-trippable encodings to the {@code BytesContainer} path. WHEN-FIXED — when
 * {@code RecordSerializerBinaryV1#deserializeValue(BytesContainer ...)} adopts the
 * same length validation, add the matching tier-1 negative tests here.
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
  public void integerValueNegativeOneZigZagsToOddOne() {
    // Zig-zag(-1) = 1 → varint 0x01.
    pinValueEncoding(PropertyTypeInternal.INTEGER, -1, "01");
  }

  @Test
  public void integerValueSmallPositiveEncodesAsTwoByteVarint() {
    // Zig-zag(1234) = 2468 = 0x9A4 → varint 0xA4 0x13.
    pinValueEncoding(PropertyTypeInternal.INTEGER, 1234, "a413");
  }

  @Test
  public void integerValueAtThreeByteVarintBoundary() {
    // Zig-zag(8192) = 16384 = 2^14 → varint 0x80 0x80 0x01 (transition from 2 to 3 bytes).
    pinValueEncoding(PropertyTypeInternal.INTEGER, 8192, "808001");
  }

  @Test
  public void integerValueAtFourByteVarintBoundary() {
    // Zig-zag(1048576) = 2097152 = 2^21 → varint 0x80 0x80 0x80 0x01.
    pinValueEncoding(PropertyTypeInternal.INTEGER, 1_048_576, "80808001");
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

  @Test
  public void floatPositiveInfinityPinsCanonicalBitPattern() {
    pinValueEncoding(PropertyTypeInternal.FLOAT, Float.POSITIVE_INFINITY, "7f800000");
  }

  @Test
  public void floatNegativeInfinityPinsCanonicalBitPattern() {
    pinValueEncoding(PropertyTypeInternal.FLOAT, Float.NEGATIVE_INFINITY, "ff800000");
  }

  @Test
  public void floatMinSubnormalRoundTripsBitExact() {
    // Float.MIN_VALUE is the smallest positive subnormal, bit pattern 0x00000001.
    pinValueEncoding(PropertyTypeInternal.FLOAT, Float.MIN_VALUE, "00000001");
  }

  @Test
  public void floatMaxValueRoundTripsBitExact() {
    pinValueEncoding(PropertyTypeInternal.FLOAT, Float.MAX_VALUE, "7f7fffff");
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

  @Test
  public void doublePositiveInfinityPinsCanonicalBitPattern() {
    pinValueEncoding(PropertyTypeInternal.DOUBLE, Double.POSITIVE_INFINITY, "7ff0000000000000");
  }

  @Test
  public void doubleNegativeInfinityPinsCanonicalBitPattern() {
    pinValueEncoding(PropertyTypeInternal.DOUBLE, Double.NEGATIVE_INFINITY, "fff0000000000000");
  }

  @Test
  public void doubleMinSubnormalRoundTripsBitExact() {
    // Double.MIN_VALUE is the smallest positive subnormal, bit pattern 0x...0001.
    pinValueEncoding(PropertyTypeInternal.DOUBLE, Double.MIN_VALUE, "0000000000000001");
  }

  @Test
  public void doubleMaxValueRoundTripsBitExact() {
    pinValueEncoding(PropertyTypeInternal.DOUBLE, Double.MAX_VALUE, "7fefffffffffffff");
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
    pinValueEncoding(PropertyTypeInternal.STRING, "a\u0000b", "06" + "61" + "00" + "62");
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
    // pi-like 18-decimal-digit fraction; BigInteger.toByteArray() is spec-deterministic
    // (two's-complement big-endian) so we can pin scale, unscaled length, and unscaled
    // bytes against a fixed hex sentinel. This catches a self-consistent BE→LE flip in
    // the scale/unscaledLen fields that round-trip-only assertions cannot detect.
    var value = new BigDecimal("31415926535897932384.626433832795028841");
    var encoded = serializeValueBytes(PropertyTypeInternal.DECIMAL, value);
    // scale       (4 bytes BE) = 0x00000012 (18)
    // unscaledLen (4 bytes BE) = 0x00000010 (16)
    // unscaled bytes           = 17a27cc3ed6cf7eeaae7b57d8c88bd69
    assertEquals(
        "00000012" + "00000010" + "17a27cc3ed6cf7eeaae7b57d8c88bd69",
        HEX.formatHex(encoded));
    var decoded = (BigDecimal) deserializeValueBytes(PropertyTypeInternal.DECIMAL, encoded);
    assertEquals(value, decoded);
  }

  @Test
  public void decimalLargeNegativeRoundTripsWithSignBit() {
    var value = new BigDecimal("-99999999999999999999999999999999.0");
    var encoded = serializeValueBytes(PropertyTypeInternal.DECIMAL, value);
    // scale       (4 bytes BE) = 0x00000001
    // unscaledLen (4 bytes BE) = 0x0000000e (14)
    // unscaled bytes           = ceb239bb726cc73ea4f60000000a (leading 0xCE >= 0x80
    //                            flags negative two's-complement — the test name's
    //                            "WithSignBit" claim is now actually asserted).
    assertEquals(
        "00000001" + "0000000e" + "ceb239bb726cc73ea4f60000000a",
        HEX.formatHex(encoded));
    var decoded = (BigDecimal) deserializeValueBytes(PropertyTypeInternal.DECIMAL, encoded);
    assertEquals(value, decoded);
  }

  @Test
  public void decimalScaleZeroLengthFourPositiveUnscaledBigEndian() {
    // Pinned sentinel: scale=0, unscaledLen=4, unscaled=0x7FFFFFFF.
    var value = new BigDecimal(BigInteger.valueOf(0x7FFFFFFFL), 0);
    var encoded = serializeValueBytes(PropertyTypeInternal.DECIMAL, value);
    assertEquals(12, encoded.length);
    assertEquals(
        "00000000" + "00000004" + "7fffffff", HEX.formatHex(encoded));
  }

  @Test
  public void decimalScaleOnePointFiveEncodesScaleAndUnscaledBigEndian() {
    // BigDecimal("1.5") → scale=1, unscaled=15 → [0x0F]. Unlike the scale=0 cases
    // above, this asserts the scale field carries a non-zero value through both
    // legs of the encode/decode path.
    pinValueEncoding(
        PropertyTypeInternal.DECIMAL,
        new BigDecimal("1.5"),
        "00000001" + "00000001" + "0f");
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
    // @Before forces the database TIMEZONE to GMT, so convertDayToTimezone(GMT,GMT,0)
    // = 0 → 0 days since epoch → varint(0) = 0x00. Without the override the
    // encoding would shift by the JVM-default offset (e.g. -3_600_000 ms in CET).
    pinValueEncoding(PropertyTypeInternal.DATE, new Date(0L), "00");
  }

  @Test
  public void dateOneDayPastEpochEncodesAsZigZagPositive() {
    // 1 day = 86_400_000 ms → 1 day → varint(zigzag(1)) = 0x02.
    pinValueEncoding(PropertyTypeInternal.DATE, new Date(86_400_000L), "02");
  }

  @Test
  public void dateOneDayBeforeEpochEncodesAsZigZagOdd() {
    // -1 day = -86_400_000 ms → -1 day → varint(zigzag(-1)) = 0x01. Pre-epoch dates
    // exercise the negative branch of zig-zag varint encoding.
    pinValueEncoding(PropertyTypeInternal.DATE, new Date(-86_400_000L), "01");
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
    // Exercises a 2-byte varint length prefix (1024 bytes → varint 0x80 0x10).
    // The cast to char is essential — `'a' + (i % 26)` is `int`, and
    // `StringBuilder.append(int)` would emit decimal-digit characters, not letters.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var sb = new StringBuilder();
    for (var i = 0; i < 1024; i++) {
      sb.append((char) ('a' + (i % 26)));
    }
    var payload = sb.toString();
    assertEquals(1024, payload.length());
    entity.setString("big", payload);

    var serialized = recordSerializer.toStream(session, entity);
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertEquals(payload, extracted.getProperty("big"));
    session.rollback();
  }

  @Test
  public void schemalessRecordWithVeryLongStringCrossesThreeByteVarintBoundary() {
    // Pins the 3-byte varint length prefix (20_000 bytes → varint 0xA0 0x9C 0x01).
    // ASCII payload to keep the wire size equal to the character count.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var payload = "x".repeat(20_000);
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

  @Test
  public void deserializePartialIgnoresUnknownRequestedField() {
    // Exercises findMatchingFieldName returning -1 — the loop must skip the value
    // bytes correctly without setting any property on the entity.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("a", 1);

    var serialized = recordSerializer.toStream(session, entity);
    var partial = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, partial, new String[] {"missing"});

    assertNull(partial.getProperty("missing"));
    assertNull(partial.getProperty("a"));
    assertTrue(partial.getPropertyNames().isEmpty());
    session.rollback();
  }

  @Test
  public void deserializePartialReadsTwoOfThreeFields() {
    // Exercises the early-break-when-unmarshalledFields == iFields.length branch
    // in deserializePartial — the loop must terminate before scanning the third field.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("a", 1);
    entity.setString("b", "value-b");
    entity.setLong("c", 99L);

    var serialized = recordSerializer.toStream(session, entity);
    var partial = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, partial, new String[] {"a", "b"});

    assertEquals(1, (int) partial.getProperty("a"));
    assertEquals("value-b", partial.getProperty("b"));
    assertNull(partial.getProperty("c"));
    session.rollback();
  }

  @Test
  public void deserializePartialOverRequestStopsAtRecordEnd() {
    // Requesting more fields than the record contains — the loop must terminate
    // via bytes.offset >= valuesStart and not via the early-break.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("a", 1);

    var serialized = recordSerializer.toStream(session, entity);
    var partial = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(
        session, serialized, partial, new String[] {"a", "b", "c"});

    assertEquals(1, (int) partial.getProperty("a"));
    assertNull(partial.getProperty("b"));
    assertNull(partial.getProperty("c"));
    session.rollback();
  }

  // -----------------------------------------------------------
  // Null property and 3-byte varint length-prefix corner cases
  // -----------------------------------------------------------

  @Test
  public void nullPropertyRoundTripsAsNullAndPreservesPropertyName() {
    // Exercises the V1 header tombstone branch: a null value writes fieldLength=0
    // in the header and never enters serializeValue. The deserialise side restores
    // the property name with a null value.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("nullable", null, PropertyType.STRING);

    var serialized = recordSerializer.toStream(session, entity);
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});

    assertTrue(extracted.getPropertyNames().contains("nullable"));
    assertNull(extracted.getProperty("nullable"));
    session.rollback();
  }

  @Test
  public void nullPropertyPartialReadReturnsNull() {
    // The deserializePartial null branch (fieldLength == 0 → setDeserializedPropertyInternal
    // with null + null type) must restore the property name with null when explicitly
    // requested by the partial read.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("nullable", null, PropertyType.STRING);
    entity.setInt("present", 42);

    var serialized = recordSerializer.toStream(session, entity);
    var partial = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, partial, new String[] {"nullable"});

    assertTrue(partial.getPropertyNames().contains("nullable"));
    assertNull(partial.getProperty("nullable"));
    assertNull(partial.getProperty("present"));
    session.rollback();
  }

  @Test
  public void binaryLargePayloadCrossesThreeByteVarintBoundary() {
    // 20_000 bytes > 2^13 (zig-zag boundary), so the length prefix is a 3-byte varint.
    // VarIntSerializer.write zig-zag-encodes BEFORE varint encoding, so the prefix is
    // varint(zigzag(20_000)) = varint(40_000) = 0xC0 0xB8 0x02.
    var raw = new byte[20_000];
    for (var i = 0; i < raw.length; i++) {
      raw[i] = (byte) (i & 0xFF);
    }
    var encoded = serializeValueBytes(PropertyTypeInternal.BINARY, raw);
    assertEquals(20_003, encoded.length);
    assertEquals((byte) 0xC0, encoded[0]);
    assertEquals((byte) 0xB8, encoded[1]);
    assertEquals((byte) 0x02, encoded[2]);
    var decoded = (byte[]) deserializeValueBytes(PropertyTypeInternal.BINARY, encoded);
    assertArrayEquals(raw, decoded);
  }

  // ----------------
  // Helper machinery
  // ----------------

  /**
   * Asserts that the canonical byte encoding of {@code value} under {@code type} matches
   * {@code expectedHex}, then deserializes the bytes and confirms the round-tripped value
   * equals the original. The exact-byte assertion is the regression sentinel; the
   * round-trip equality covers the deserialize switch case. Per-type equality semantics:
   *
   * <ul>
   *   <li><b>BINARY</b>: array content equality</li>
   *   <li><b>FLOAT / DOUBLE</b>: bit-pattern equality via {@code floatToIntBits} /
   *       {@code doubleToLongBits} so NaN, negative zero, and infinity are
   *       distinguishable</li>
   *   <li><b>DATE / DATETIME</b>: millis comparison; the deserialise side always returns
   *       a {@link Date} even when the encode side accepted a raw {@link Number}. DATE
   *       callers must pass whole-day millis (the encode path floors to day precision)</li>
   *   <li><b>everything else</b>: {@link Object#equals}</li>
   * </ul>
   *
   * <p>The helper deliberately rejects {@code null} via {@code assertNotNull} — the V1
   * value dispatch is unreachable for null property values, since the schemaless header
   * records null as a tombstone (fieldLength=0) before {@code serializeValue} would ever
   * be called.
   */
  private void pinValueEncoding(
      PropertyTypeInternal type, Object value, String expectedHex) {
    var encoded = serializeValueBytes(type, value);
    assertEquals(expectedHex, HEX.formatHex(encoded));
    var decoded = deserializeValueBytes(type, encoded);
    assertNotNull("round-trip yielded null for type " + type, decoded);
    switch (type) {
      case BINARY -> assertArrayEquals((byte[]) value, (byte[]) decoded);
      case FLOAT -> assertEquals(
          Float.floatToIntBits(((Number) value).floatValue()),
          Float.floatToIntBits(((Number) decoded).floatValue()));
      case DOUBLE -> assertEquals(
          Double.doubleToLongBits(((Number) value).doubleValue()),
          Double.doubleToLongBits(((Number) decoded).doubleValue()));
      case DATE, DATETIME -> {
        var expectedMillis =
            (value instanceof Number n) ? n.longValue() : ((Date) value).getTime();
        assertEquals(expectedMillis, ((Date) decoded).getTime());
      }
      default -> assertEquals(value, decoded);
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
