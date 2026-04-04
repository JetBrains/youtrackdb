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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CorruptedRecordException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.OptionalInt;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Tests for {@link InPlaceComparator} ReadBinaryField non-numeric type comparison (STRING, BOOLEAN,
 * DATETIME, DATE, DECIMAL, BINARY, LINK). Mirrors {@link InPlaceComparatorNonNumericTest} but uses
 * ReadBinaryField / ReadBytesContainer (ByteBuffer-backed). Includes targeted edge cases for
 * DECIMAL (divergent deserialization from DecimalSerializer) and LINK equality.
 */
public class InPlaceComparatorRbcNonNumericTest {

  // ---- Helper methods to create ReadBinaryFields with serialized values ----

  private static ReadBinaryField stringFieldRbc(String value) {
    var bytes = new BytesContainer();
    var encoded = value.getBytes(StandardCharsets.UTF_8);
    VarIntSerializer.write(bytes, encoded.length);
    var pos = bytes.alloc(encoded.length);
    System.arraycopy(encoded, 0, bytes.bytes, pos, encoded.length);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.STRING, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField booleanFieldRbc(boolean value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(1);
    bytes.bytes[pos] = value ? (byte) 1 : (byte) 0;
    return new ReadBinaryField(
        "test", PropertyTypeInternal.BOOLEAN, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField datetimeFieldRbc(long millis) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, millis);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.DATETIME, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField dateFieldRbc(long days) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, days);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.DATE, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  /**
   * Creates a DECIMAL ReadBinaryField using the same binary format as DecimalSerializer:
   * 4-byte scale (big-endian int) + 4-byte unscaled length (big-endian int) + unscaled bytes.
   */
  private static ReadBinaryField decimalFieldRbc(BigDecimal value) {
    var size = DecimalSerializer.staticGetObjectSize(value);
    var bytes = new byte[size];
    DecimalSerializer.staticSerialize(value, bytes, 0);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.DECIMAL, new ReadBytesContainer(bytes, 0), null);
  }

  private static ReadBinaryField binaryFieldRbc(byte[] value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value.length);
    var pos = bytes.alloc(value.length);
    System.arraycopy(value, 0, bytes.bytes, pos, value.length);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.BINARY, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField linkFieldRbc(int collectionId, long collectionPos) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, collectionId);
    VarIntSerializer.write(bytes, collectionPos);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.LINK, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  // ===========================================================================
  // STRING tests
  // ===========================================================================

  /** STRING equality — same value. */
  @Test
  public void testStringEqual() {
    var result = InPlaceComparator.compare(stringFieldRbc("hello"), "hello", null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** STRING less than — "abc" < "def". */
  @Test
  public void testStringLessThan() {
    var result = InPlaceComparator.compare(stringFieldRbc("abc"), "def", null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** STRING greater than — "xyz" > "abc". */
  @Test
  public void testStringGreaterThan() {
    var result = InPlaceComparator.compare(stringFieldRbc("xyz"), "abc", null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** STRING empty string comparison. */
  @Test
  public void testStringEmpty() {
    var result = InPlaceComparator.compare(stringFieldRbc(""), "", null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** STRING with non-String value — should fall back. */
  @Test
  public void testStringWithNonStringFallback() {
    var result = InPlaceComparator.compare(stringFieldRbc("42"), 42, null);
    assertTrue(result.isEmpty());
  }

  /** STRING with Unicode characters. */
  @Test
  public void testStringUnicode() {
    var result = InPlaceComparator.compare(stringFieldRbc("привет"), "привет", null);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // BOOLEAN tests
  // ===========================================================================

  /** BOOLEAN true == true. */
  @Test
  public void testBooleanTrueEqual() {
    var result = InPlaceComparator.compare(booleanFieldRbc(true), true, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BOOLEAN false == false. */
  @Test
  public void testBooleanFalseEqual() {
    var result = InPlaceComparator.compare(booleanFieldRbc(false), false, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BOOLEAN true > false. */
  @Test
  public void testBooleanTrueGreaterThanFalse() {
    var result = InPlaceComparator.compare(booleanFieldRbc(true), false, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** BOOLEAN with non-Boolean — should fall back. */
  @Test
  public void testBooleanWithNonBooleanFallback() {
    var result = InPlaceComparator.compare(booleanFieldRbc(true), 1, null);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // DATETIME tests
  // ===========================================================================

  /** DATETIME with matching Date. */
  @Test
  public void testDatetimeEqualDate() {
    long millis = 1700000000000L;
    var result = InPlaceComparator.compare(datetimeFieldRbc(millis), new Date(millis), null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATETIME less than. */
  @Test
  public void testDatetimeLessThan() {
    var result = InPlaceComparator.compare(datetimeFieldRbc(1000L), new Date(2000L), null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DATETIME with Number value (millis as long). */
  @Test
  public void testDatetimeWithNumber() {
    long millis = 1700000000000L;
    var result = InPlaceComparator.compare(datetimeFieldRbc(millis), millis, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATETIME with non-Date/Number — should fall back. */
  @Test
  public void testDatetimeWithStringFallback() {
    var result = InPlaceComparator.compare(datetimeFieldRbc(1000L), "2024-01-01", null);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // DATE tests
  // ===========================================================================

  /** DATE with GMT timezone. */
  @Test
  public void testDateEqualGmt() {
    long days = 1;
    long expectedMillis = 86400000L;
    var gmtTz = TimeZone.getTimeZone("GMT");
    var result = InPlaceComparator.compare(dateFieldRbc(days), new Date(expectedMillis), gmtTz);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATE without timezone — should fall back. */
  @Test
  public void testDateWithoutTimezoneFallback() {
    var result = InPlaceComparator.compare(dateFieldRbc(1), new Date(86400000L), null);
    assertTrue(result.isEmpty());
  }

  /** DATE with non-GMT timezone. */
  @Test
  public void testDateWithNonGmtTimezone() {
    var eastern = TimeZone.getTimeZone("US/Eastern");
    long days = 1;
    long convertedMillis =
        HelperClasses.convertDayToTimezone(
            TimeZone.getTimeZone("GMT"), eastern, days * 86400000L);
    var result =
        InPlaceComparator.compare(dateFieldRbc(days), new Date(convertedMillis), eastern);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // DECIMAL tests — key focus area: divergent deserialization from DecimalSerializer
  // ===========================================================================

  /** DECIMAL equality — same value. */
  @Test
  public void testDecimalEqual() {
    var bd = new BigDecimal("123.456");
    var result = InPlaceComparator.compare(decimalFieldRbc(bd), bd, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with different scale but same value — compareTo ignores scale. */
  @Test
  public void testDecimalDifferentScale() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("1.0")), new BigDecimal("1"), null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL less than. */
  @Test
  public void testDecimalLessThan() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("1.5")), new BigDecimal("2.5"), null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DECIMAL greater than. */
  @Test
  public void testDecimalGreaterThan() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("2.5")), new BigDecimal("1.5"), null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** DECIMAL with Double value. */
  @Test
  public void testDecimalWithDouble() {
    var result = InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42.0")), 42.0, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with Integer value. */
  @Test
  public void testDecimalWithInteger() {
    var result = InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42")), 42, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with Long value. */
  @Test
  public void testDecimalWithLong() {
    var result = InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42")), 42L, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL negative value. */
  @Test
  public void testDecimalNegative() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("-99.99")), new BigDecimal("-99.99"), null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL zero. */
  @Test
  public void testDecimalZero() {
    var result =
        InPlaceComparator.compare(decimalFieldRbc(BigDecimal.ZERO), BigDecimal.ZERO, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with non-Number — should fall back. */
  @Test
  public void testDecimalWithStringFallback() {
    var result = InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42")), "42", null);
    assertTrue(result.isEmpty());
  }

  /** DECIMAL with Double.NaN — should fall back, not throw. */
  @Test
  public void testDecimalWithDoubleNaN() {
    var result =
        InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42")), Double.NaN, null);
    assertTrue("DECIMAL vs Double.NaN should fall back", result.isEmpty());
  }

  /** DECIMAL with Double.POSITIVE_INFINITY — should fall back. */
  @Test
  public void testDecimalWithDoublePositiveInfinity() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("42")), Double.POSITIVE_INFINITY, null);
    assertTrue("DECIMAL vs +Infinity should fall back", result.isEmpty());
  }

  /** DECIMAL with Double.NEGATIVE_INFINITY — should fall back. */
  @Test
  public void testDecimalWithDoubleNegativeInfinity() {
    var result =
        InPlaceComparator.compare(
            decimalFieldRbc(new BigDecimal("42")), Double.NEGATIVE_INFINITY, null);
    assertTrue("DECIMAL vs -Infinity should fall back", result.isEmpty());
  }

  /** DECIMAL with Float.NaN — should fall back. */
  @Test
  public void testDecimalWithFloatNaN() {
    var result =
        InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42")), Float.NaN, null);
    assertTrue("DECIMAL vs Float.NaN should fall back", result.isEmpty());
  }

  /** DECIMAL with Float value. */
  @Test
  public void testDecimalWithFloat() {
    var result =
        InPlaceComparator.compare(decimalFieldRbc(new BigDecimal("42.5")), 42.5f, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * DECIMAL with corrupted unscaled length (exceeds remaining buffer) — should throw
   * CorruptedRecordException. In the PageFrame comparison path this would be caught by the
   * RuntimeException handler and cause a fallback.
   */
  @Test(expected = CorruptedRecordException.class)
  public void testDecimalCorruptedUnscaledLength() {
    // Craft bytes: valid scale (4 bytes), but unscaled length = 999 (exceeds remaining)
    var buf = ByteBuffer.allocate(12);
    buf.putInt(2); // scale
    buf.putInt(999); // corrupt unscaled length
    buf.putInt(0); // some padding
    buf.flip();
    var field = new ReadBinaryField(
        "test", PropertyTypeInternal.DECIMAL, new ReadBytesContainer(buf), null);
    InPlaceComparator.compare(field, new BigDecimal("1"), null);
  }

  /**
   * DECIMAL with very large value — verifies that the manual BigInteger deserialization
   * in compareDecimalRbc matches what DecimalSerializer would produce.
   */
  @Test
  public void testDecimalVeryLargeValue() {
    var large = new BigDecimal("99999999999999999999999999999.123456789");
    var result = InPlaceComparator.compare(decimalFieldRbc(large), large, null);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // BINARY tests
  // ===========================================================================

  /** BINARY equality. */
  @Test
  public void testBinaryEqual() {
    var data = new byte[] {1, 2, 3, 4, 5};
    var result = InPlaceComparator.compare(binaryFieldRbc(data), data, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BINARY less than (lexicographic). */
  @Test
  public void testBinaryLessThan() {
    var result =
        InPlaceComparator.compare(
            binaryFieldRbc(new byte[] {1, 2, 3}), new byte[] {1, 2, 4}, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** BINARY empty arrays. */
  @Test
  public void testBinaryEmpty() {
    var result =
        InPlaceComparator.compare(binaryFieldRbc(new byte[0]), new byte[0], null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BINARY with non-byte-array — should fall back. */
  @Test
  public void testBinaryWithNonByteArrayFallback() {
    var result = InPlaceComparator.compare(binaryFieldRbc(new byte[] {1}), "data", null);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // LINK tests — key focus area: equality via RID components
  // ===========================================================================

  /** LINK compare returns empty (ordering undefined). */
  @Test
  public void testLinkCompareReturnsEmpty() {
    var result =
        InPlaceComparator.compare(linkFieldRbc(5, 10L), new RecordId(5, 10L), null);
    assertTrue(result.isEmpty());
  }

  /** LINK isEqual returns 1 for matching RID. */
  @Test
  public void testLinkIsEqualTrue() {
    var result =
        InPlaceComparator.isEqual(linkFieldRbc(5, 10L), new RecordId(5, 10L), null);
    assertEquals(OptionalInt.of(1), result);
  }

  /** LINK isEqual returns 0 for different collectionId. */
  @Test
  public void testLinkIsEqualDifferentCollectionId() {
    var result =
        InPlaceComparator.isEqual(linkFieldRbc(5, 10L), new RecordId(6, 10L), null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LINK isEqual returns 0 for different collectionPosition. */
  @Test
  public void testLinkIsEqualDifferentCollectionPos() {
    var result =
        InPlaceComparator.isEqual(linkFieldRbc(5, 10L), new RecordId(5, 11L), null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LINK isEqual with non-RID value — should fall back. */
  @Test
  public void testLinkIsEqualWithNonRidFallback() {
    var result = InPlaceComparator.isEqual(linkFieldRbc(5, 10L), "5:10", null);
    assertTrue(result.isEmpty());
  }

  /** LINK isEqual with Identifiable (non-RID) value — exercises the Identifiable branch. */
  @Test
  public void testLinkIsEqualWithIdentifiable() {
    Identifiable identifiable = new Identifiable() {
      @Override
      public RID getIdentity() {
        return new RecordId(5, 10L);
      }

      @Override
      public int compareTo(Identifiable o) {
        return getIdentity().compareTo(o.getIdentity());
      }
    };
    var result = InPlaceComparator.isEqual(linkFieldRbc(5, 10L), identifiable, null);
    assertEquals(OptionalInt.of(1), result);
  }

  /** LINK isEqual with cluster 0, position 0 — boundary test. */
  @Test
  public void testLinkIsEqualZeroRid() {
    var result = InPlaceComparator.isEqual(linkFieldRbc(0, 0L), new RecordId(0, 0L), null);
    assertEquals(OptionalInt.of(1), result);
  }

  // ===========================================================================
  // Excluded types fallback tests
  // ===========================================================================

  /** EMBEDDED type returns empty (not binary-comparable). */
  @Test
  public void testEmbeddedFallback() {
    var field = new ReadBinaryField(
        "test", PropertyTypeInternal.EMBEDDED, new ReadBytesContainer(new byte[4], 0), null);
    assertTrue(InPlaceComparator.compare(field, "value", null).isEmpty());
  }

  /** EMBEDDEDLIST type returns empty. */
  @Test
  public void testEmbeddedListFallback() {
    var field = new ReadBinaryField(
        "test", PropertyTypeInternal.EMBEDDEDLIST,
        new ReadBytesContainer(new byte[4], 0), null);
    assertTrue(InPlaceComparator.compare(field, "value", null).isEmpty());
  }

  // ===========================================================================
  // isEqual for non-numeric types
  // ===========================================================================

  /** isEqual for STRING — equal. */
  @Test
  public void testIsEqualStringTrue() {
    var result = InPlaceComparator.isEqual(stringFieldRbc("hello"), "hello", null);
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual for STRING — not equal. */
  @Test
  public void testIsEqualStringFalse() {
    var result = InPlaceComparator.isEqual(stringFieldRbc("hello"), "world", null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** isEqual for BOOLEAN — equal. */
  @Test
  public void testIsEqualBooleanTrue() {
    var result = InPlaceComparator.isEqual(booleanFieldRbc(true), true, null);
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual for DECIMAL with different scale — still equal via compareTo. */
  @Test
  public void testIsEqualDecimalDifferentScale() {
    var result =
        InPlaceComparator.isEqual(
            decimalFieldRbc(new BigDecimal("1.0")), new BigDecimal("1"), null);
    assertEquals(OptionalInt.of(1), result);
  }
}
