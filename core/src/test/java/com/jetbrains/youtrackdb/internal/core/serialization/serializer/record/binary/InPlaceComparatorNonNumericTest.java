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
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.OptionalInt;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Tests for {@link InPlaceComparator} non-numeric type comparison (STRING, BOOLEAN, DATETIME, DATE,
 * DECIMAL, BINARY, LINK). Covers type-specific edge cases, excluded types, and the isEqual
 * specialization for LINK.
 */
public class InPlaceComparatorNonNumericTest {

  // ---- Helper methods to create BinaryFields with serialized values ----

  private static BinaryField stringField(String value) {
    var bytes = new BytesContainer();
    var encoded = value.getBytes(StandardCharsets.UTF_8);
    VarIntSerializer.write(bytes, encoded.length);
    var pos = bytes.alloc(encoded.length);
    System.arraycopy(encoded, 0, bytes.bytes, pos, encoded.length);
    return new BinaryField(
        "test", PropertyTypeInternal.STRING, new BytesContainer(bytes.bytes, 0), null);
  }

  private static BinaryField booleanField(boolean value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(1);
    bytes.bytes[pos] = value ? (byte) 1 : (byte) 0;
    return new BinaryField(
        "test", PropertyTypeInternal.BOOLEAN, new BytesContainer(bytes.bytes, 0), null);
  }

  private static BinaryField datetimeField(long millis) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, millis);
    return new BinaryField(
        "test", PropertyTypeInternal.DATETIME, new BytesContainer(bytes.bytes, 0), null);
  }

  private static BinaryField dateField(long days) {
    // Stores days since epoch (not millis) — same as RecordSerializerBinaryV1
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, days);
    return new BinaryField(
        "test", PropertyTypeInternal.DATE, new BytesContainer(bytes.bytes, 0), null);
  }

  private static BinaryField decimalField(BigDecimal value) {
    var size = DecimalSerializer.staticGetObjectSize(value);
    var bytes = new byte[size];
    DecimalSerializer.staticSerialize(value, bytes, 0);
    return new BinaryField(
        "test", PropertyTypeInternal.DECIMAL, new BytesContainer(bytes, 0), null);
  }

  private static BinaryField binaryField(byte[] value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value.length);
    var pos = bytes.alloc(value.length);
    System.arraycopy(value, 0, bytes.bytes, pos, value.length);
    return new BinaryField(
        "test", PropertyTypeInternal.BINARY, new BytesContainer(bytes.bytes, 0), null);
  }

  private static BinaryField linkField(int collectionId, long collectionPos) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, collectionId);
    VarIntSerializer.write(bytes, collectionPos);
    return new BinaryField(
        "test", PropertyTypeInternal.LINK, new BytesContainer(bytes.bytes, 0), null);
  }

  // ===========================================================================
  // STRING tests
  // ===========================================================================

  /** STRING equality — same value. */
  @Test
  public void testStringEqual() {
    var result = InPlaceComparator.compare(stringField("hello"), "hello");
    assertEquals(OptionalInt.of(0), result);
  }

  /** STRING less than — "abc" < "def". */
  @Test
  public void testStringLessThan() {
    var result = InPlaceComparator.compare(stringField("abc"), "def");
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** STRING greater than — "xyz" > "abc". */
  @Test
  public void testStringGreaterThan() {
    var result = InPlaceComparator.compare(stringField("xyz"), "abc");
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** STRING empty string comparison. */
  @Test
  public void testStringEmpty() {
    var result = InPlaceComparator.compare(stringField(""), "");
    assertEquals(OptionalInt.of(0), result);
  }

  /** STRING with non-String value — should fall back. */
  @Test
  public void testStringWithNonStringFallback() {
    var result = InPlaceComparator.compare(stringField("42"), 42);
    assertTrue(result.isEmpty());
  }

  /** STRING with Unicode characters. */
  @Test
  public void testStringUnicode() {
    var result = InPlaceComparator.compare(stringField("привет"), "привет");
    assertEquals(OptionalInt.of(0), result);
  }

  /** STRING with Unicode ordering. */
  @Test
  public void testStringUnicodeOrdering() {
    var result = InPlaceComparator.compare(stringField("a"), "б");
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  // ===========================================================================
  // BOOLEAN tests
  // ===========================================================================

  /** BOOLEAN true == true. */
  @Test
  public void testBooleanTrueEqual() {
    var result = InPlaceComparator.compare(booleanField(true), true);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BOOLEAN false == false. */
  @Test
  public void testBooleanFalseEqual() {
    var result = InPlaceComparator.compare(booleanField(false), false);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BOOLEAN true > false (Boolean.compare semantics). */
  @Test
  public void testBooleanTrueGreaterThanFalse() {
    var result = InPlaceComparator.compare(booleanField(true), false);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** BOOLEAN false < true. */
  @Test
  public void testBooleanFalseLessThanTrue() {
    var result = InPlaceComparator.compare(booleanField(false), true);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** BOOLEAN with non-Boolean value — should fall back. */
  @Test
  public void testBooleanWithNonBooleanFallback() {
    var result = InPlaceComparator.compare(booleanField(true), 1);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // DATETIME tests
  // ===========================================================================

  /** DATETIME with matching Date. */
  @Test
  public void testDatetimeEqualDate() {
    long millis = 1700000000000L;
    var result = InPlaceComparator.compare(datetimeField(millis), new Date(millis));
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATETIME less than. */
  @Test
  public void testDatetimeLessThan() {
    var result = InPlaceComparator.compare(datetimeField(1000L), new Date(2000L));
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DATETIME greater than. */
  @Test
  public void testDatetimeGreaterThan() {
    var result = InPlaceComparator.compare(datetimeField(2000L), new Date(1000L));
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** DATETIME with Number value (millis as long). */
  @Test
  public void testDatetimeWithNumber() {
    long millis = 1700000000000L;
    var result = InPlaceComparator.compare(datetimeField(millis), millis);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATETIME with non-Date/Number — should fall back. */
  @Test
  public void testDatetimeWithStringFallback() {
    var result = InPlaceComparator.compare(datetimeField(1000L), "2024-01-01");
    assertTrue(result.isEmpty());
  }

  /** DATETIME epoch zero. */
  @Test
  public void testDatetimeEpochZero() {
    var result = InPlaceComparator.compare(datetimeField(0L), new Date(0L));
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // DATE tests
  // ===========================================================================

  /** DATE with GMT timezone — stored days * MILLISEC_PER_DAY should match. */
  @Test
  public void testDateEqualGmt() {
    // Day 0 = epoch, 1 day = 86400000 millis
    long days = 1;
    long expectedMillis = 86400000L;
    var gmtTz = TimeZone.getTimeZone("GMT");
    var result = InPlaceComparator.compare(dateField(days), new Date(expectedMillis), gmtTz);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATE without timezone — should fall back. */
  @Test
  public void testDateWithoutTimezoneFallback() {
    var result = InPlaceComparator.compare(dateField(1), new Date(86400000L));
    assertTrue(result.isEmpty());
  }

  /** DATE ordering. */
  @Test
  public void testDateOrdering() {
    var gmtTz = TimeZone.getTimeZone("GMT");
    var result = InPlaceComparator.compare(dateField(1), new Date(2 * 86400000L), gmtTz);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DATE with Number value. */
  @Test
  public void testDateWithNumber() {
    var gmtTz = TimeZone.getTimeZone("GMT");
    long days = 5;
    long expectedMillis = 5 * 86400000L;
    var result = InPlaceComparator.compare(dateField(days), expectedMillis, gmtTz);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATE with non-Date/Number — should fall back. */
  @Test
  public void testDateWithStringFallback() {
    var gmtTz = TimeZone.getTimeZone("GMT");
    var result = InPlaceComparator.compare(dateField(1), "2024-01-01", gmtTz);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // DECIMAL tests
  // ===========================================================================

  /** DECIMAL equality — same value. */
  @Test
  public void testDecimalEqual() {
    var bd = new BigDecimal("123.456");
    var result = InPlaceComparator.compare(decimalField(bd), bd);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with different scale but same value — compareTo ignores scale. */
  @Test
  public void testDecimalDifferentScale() {
    var result =
        InPlaceComparator.compare(decimalField(new BigDecimal("1.0")), new BigDecimal("1"));
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL less than. */
  @Test
  public void testDecimalLessThan() {
    var result =
        InPlaceComparator.compare(
            decimalField(new BigDecimal("1.5")), new BigDecimal("2.5"));
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DECIMAL greater than. */
  @Test
  public void testDecimalGreaterThan() {
    var result =
        InPlaceComparator.compare(
            decimalField(new BigDecimal("2.5")), new BigDecimal("1.5"));
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** DECIMAL with Double value — converts via BigDecimal.valueOf(). */
  @Test
  public void testDecimalWithDouble() {
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42.0")), 42.0);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with Integer value — converts via BigDecimal.valueOf(long). */
  @Test
  public void testDecimalWithInteger() {
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42")), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with Long value. */
  @Test
  public void testDecimalWithLong() {
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42")), 42L);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with non-Number — should fall back. */
  @Test
  public void testDecimalWithStringFallback() {
    var result =
        InPlaceComparator.compare(decimalField(new BigDecimal("42")), "42");
    assertTrue(result.isEmpty());
  }

  /** DECIMAL negative value. */
  @Test
  public void testDecimalNegative() {
    var result =
        InPlaceComparator.compare(
            decimalField(new BigDecimal("-99.99")), new BigDecimal("-99.99"));
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL zero. */
  @Test
  public void testDecimalZero() {
    var result =
        InPlaceComparator.compare(decimalField(BigDecimal.ZERO), BigDecimal.ZERO);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // BINARY tests
  // ===========================================================================

  /** BINARY equality. */
  @Test
  public void testBinaryEqual() {
    var data = new byte[] {1, 2, 3, 4, 5};
    var result = InPlaceComparator.compare(binaryField(data), data);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BINARY less than (lexicographic). */
  @Test
  public void testBinaryLessThan() {
    var result = InPlaceComparator.compare(
        binaryField(new byte[] {1, 2, 3}), new byte[] {1, 2, 4});
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** BINARY greater than. */
  @Test
  public void testBinaryGreaterThan() {
    var result = InPlaceComparator.compare(
        binaryField(new byte[] {1, 2, 4}), new byte[] {1, 2, 3});
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** BINARY different lengths — shorter array is less. */
  @Test
  public void testBinaryDifferentLengths() {
    var result = InPlaceComparator.compare(
        binaryField(new byte[] {1, 2}), new byte[] {1, 2, 3});
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** BINARY empty arrays. */
  @Test
  public void testBinaryEmpty() {
    var result = InPlaceComparator.compare(
        binaryField(new byte[0]), new byte[0]);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BINARY with non-byte-array — should fall back. */
  @Test
  public void testBinaryWithNonByteArrayFallback() {
    var result = InPlaceComparator.compare(binaryField(new byte[] {1}), "data");
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // LINK tests
  // ===========================================================================

  /** LINK compare returns empty (ordering undefined). */
  @Test
  public void testLinkCompareReturnsEmpty() {
    var result = InPlaceComparator.compare(linkField(5, 10L), new RecordId(5, 10L));
    assertTrue(result.isEmpty());
  }

  /** LINK isEqual returns 1 for matching RID. */
  @Test
  public void testLinkIsEqualTrue() {
    var result = InPlaceComparator.isEqual(linkField(5, 10L), new RecordId(5, 10L));
    assertEquals(OptionalInt.of(1), result);
  }

  /** LINK isEqual returns 0 for different collectionId. */
  @Test
  public void testLinkIsEqualDifferentCollectionId() {
    var result = InPlaceComparator.isEqual(linkField(5, 10L), new RecordId(6, 10L));
    assertEquals(OptionalInt.of(0), result);
  }

  /** LINK isEqual returns 0 for different collectionPosition. */
  @Test
  public void testLinkIsEqualDifferentCollectionPos() {
    var result = InPlaceComparator.isEqual(linkField(5, 10L), new RecordId(5, 11L));
    assertEquals(OptionalInt.of(0), result);
  }

  /** LINK isEqual with non-RID value — should fall back. */
  @Test
  public void testLinkIsEqualWithNonRidFallback() {
    var result = InPlaceComparator.isEqual(linkField(5, 10L), "5:10");
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // Excluded types fallback tests
  // ===========================================================================

  /** EMBEDDED type returns empty (not binary-comparable). */
  @Test
  public void testEmbeddedFallback() {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, 42);
    var field = new BinaryField(
        "test", PropertyTypeInternal.EMBEDDED, new BytesContainer(bytes.bytes, 0), null);
    var result = InPlaceComparator.compare(field, "value");
    assertTrue(result.isEmpty());
  }

  /** EMBEDDEDLIST type returns empty. */
  @Test
  public void testEmbeddedListFallback() {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, 42);
    var field = new BinaryField(
        "test", PropertyTypeInternal.EMBEDDEDLIST, new BytesContainer(bytes.bytes, 0), null);
    var result = InPlaceComparator.compare(field, "value");
    assertTrue(result.isEmpty());
  }

  /** LINKLIST type returns empty. */
  @Test
  public void testLinkListFallback() {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, 42);
    var field = new BinaryField(
        "test", PropertyTypeInternal.LINKLIST, new BytesContainer(bytes.bytes, 0), null);
    var result = InPlaceComparator.compare(field, "value");
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // isEqual for non-numeric types (derived from compare)
  // ===========================================================================

  /** isEqual for STRING — equal. */
  @Test
  public void testIsEqualStringTrue() {
    var result = InPlaceComparator.isEqual(stringField("hello"), "hello");
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual for STRING — not equal. */
  @Test
  public void testIsEqualStringFalse() {
    var result = InPlaceComparator.isEqual(stringField("hello"), "world");
    assertEquals(OptionalInt.of(0), result);
  }

  /** isEqual for BOOLEAN — equal. */
  @Test
  public void testIsEqualBooleanTrue() {
    var result = InPlaceComparator.isEqual(booleanField(true), true);
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual for BOOLEAN — not equal. */
  @Test
  public void testIsEqualBooleanFalse() {
    var result = InPlaceComparator.isEqual(booleanField(true), false);
    assertEquals(OptionalInt.of(0), result);
  }

  /** isEqual for DECIMAL with different scale — still equal via compareTo. */
  @Test
  public void testIsEqualDecimalDifferentScale() {
    var result =
        InPlaceComparator.isEqual(decimalField(new BigDecimal("1.0")), new BigDecimal("1"));
    assertEquals(OptionalInt.of(1), result);
  }

  // ===========================================================================
  // Review fix: non-GMT DATE timezone tests
  // ===========================================================================

  /** DATE with non-GMT timezone — timezone offset is applied to the serialized day value. */
  @Test
  public void testDateWithNonGmtTimezone() {
    var eastern = TimeZone.getTimeZone("US/Eastern");
    long days = 1;
    // Convert using the same helper the production code uses
    long convertedMillis =
        HelperClasses.convertDayToTimezone(
            TimeZone.getTimeZone("GMT"), eastern, days * 86400000L);
    var result = InPlaceComparator.compare(dateField(days), new Date(convertedMillis), eastern);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATE with positive-offset timezone (Asia/Tokyo = UTC+9). */
  @Test
  public void testDateWithPositiveOffsetTimezone() {
    var tokyo = TimeZone.getTimeZone("Asia/Tokyo");
    long days = 0;
    long convertedMillis =
        HelperClasses.convertDayToTimezone(TimeZone.getTimeZone("GMT"), tokyo, 0L);
    var result = InPlaceComparator.compare(dateField(days), new Date(convertedMillis), tokyo);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATE epoch day 0 with GMT — should equal Date(0). */
  @Test
  public void testDateEpochDayZeroGmt() {
    var gmtTz = TimeZone.getTimeZone("GMT");
    var result = InPlaceComparator.compare(dateField(0), new Date(0L), gmtTz);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // Review fix: LINK Identifiable path + boundary values
  // ===========================================================================

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
    var result = InPlaceComparator.isEqual(linkField(5, 10L), identifiable);
    assertEquals(OptionalInt.of(1), result);
  }

  /** LINK isEqual with non-matching Identifiable. */
  @Test
  public void testLinkIsEqualWithIdentifiableNotEqual() {
    Identifiable identifiable = new Identifiable() {
      @Override
      public RID getIdentity() {
        return new RecordId(99, 99L);
      }

      @Override
      public int compareTo(Identifiable o) {
        return getIdentity().compareTo(o.getIdentity());
      }
    };
    var result = InPlaceComparator.isEqual(linkField(5, 10L), identifiable);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LINK isEqual with cluster 0, position 0. */
  @Test
  public void testLinkIsEqualZeroRid() {
    var result = InPlaceComparator.isEqual(linkField(0, 0L), new RecordId(0, 0L));
    assertEquals(OptionalInt.of(1), result);
  }

  // ===========================================================================
  // Review fix: DECIMAL with Float, additional fallback tests
  // ===========================================================================

  /** DECIMAL with Float value — converts via BigDecimal.valueOf(doubleValue()). */
  @Test
  public void testDecimalWithFloat() {
    // 42.5f converts exactly to double, so this should match
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42.5")), 42.5f);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DECIMAL with Float that has representation noise (0.1f != 0.1d). */
  @Test
  public void testDecimalWithFloatRepresentationDifference() {
    // 0.1f widens to ~0.10000000149011612d, not 0.1d
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("0.1")), 0.1f);
    assertTrue(result.isPresent());
    // Should NOT be equal due to float representation noise
    assertTrue(result.getAsInt() != 0);
  }

  /** EMBEDDEDSET type returns empty. */
  @Test
  public void testEmbeddedSetFallback() {
    var field = new BinaryField(
        "test", PropertyTypeInternal.EMBEDDEDSET, new BytesContainer(new byte[4], 0), null);
    assertTrue(InPlaceComparator.compare(field, "value").isEmpty());
  }

  /** LINKMAP type returns empty. */
  @Test
  public void testLinkMapFallback() {
    var field = new BinaryField(
        "test", PropertyTypeInternal.LINKMAP, new BytesContainer(new byte[4], 0), null);
    assertTrue(InPlaceComparator.compare(field, "value").isEmpty());
  }

  /** LINKBAG type returns empty. */
  @Test
  public void testLinkBagFallback() {
    var field = new BinaryField(
        "test", PropertyTypeInternal.LINKBAG, new BytesContainer(new byte[4], 0), null);
    assertTrue(InPlaceComparator.compare(field, "value").isEmpty());
  }

  /** DATETIME with negative millis — pre-epoch date. */
  @Test
  public void testDatetimeNegativeMillis() {
    long millis = -86400000L; // 1969-12-31
    var result = InPlaceComparator.compare(datetimeField(millis), new Date(millis));
    assertEquals(OptionalInt.of(0), result);
  }

  /** DATETIME with Integer value (small millis within int range). */
  @Test
  public void testDatetimeWithInteger() {
    var result = InPlaceComparator.compare(datetimeField(5000L), 5000);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // DECIMAL with Double.NaN and Infinity — must fall back, not throw
  // ===========================================================================

  /** DECIMAL compared with Double.NaN must return empty (fallback), not throw. */
  @Test
  public void testDecimalWithDoubleNaN() {
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42")), Double.NaN);
    assertTrue("DECIMAL vs Double.NaN should fall back", result.isEmpty());
  }

  /** DECIMAL compared with Double.POSITIVE_INFINITY must return empty (fallback), not throw. */
  @Test
  public void testDecimalWithDoublePositiveInfinity() {
    var result = InPlaceComparator.compare(
        decimalField(new BigDecimal("42")), Double.POSITIVE_INFINITY);
    assertTrue("DECIMAL vs +Infinity should fall back", result.isEmpty());
  }

  /** DECIMAL compared with Double.NEGATIVE_INFINITY must return empty (fallback), not throw. */
  @Test
  public void testDecimalWithDoubleNegativeInfinity() {
    var result = InPlaceComparator.compare(
        decimalField(new BigDecimal("42")), Double.NEGATIVE_INFINITY);
    assertTrue("DECIMAL vs -Infinity should fall back", result.isEmpty());
  }

  /** DECIMAL compared with Float.NaN must return empty (fallback), not throw. */
  @Test
  public void testDecimalWithFloatNaN() {
    var result = InPlaceComparator.compare(decimalField(new BigDecimal("42")), Float.NaN);
    assertTrue("DECIMAL vs Float.NaN should fall back", result.isEmpty());
  }
}
