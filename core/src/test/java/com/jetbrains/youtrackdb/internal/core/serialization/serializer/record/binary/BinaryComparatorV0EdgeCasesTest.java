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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

/**
 * Targeted edge-case coverage for {@link BinaryComparatorV0} branches not exercised by the
 * pre-existing {@code BinaryComparatorEqualsTest} / {@code BinaryComparatorCompareTest}, by
 * {@code BinaryComparatorV0IsEqualCrossTypeTest}, or by {@code BinaryComparatorV0DateSourceTest}.
 *
 * <p>Each test pins a single arm of the comparator at branch granularity so that a regression to
 * its return-value or short-circuit conditions is caught loudly.
 *
 * <p>The pinned branches are:
 * <ul>
 *   <li>LINK × LINK same-cluster, different-position — exercises the inner else branch
 * where collection IDs match and only positions differ.</li>
 *   <li>BINARY × BINARY length-difference paths — same-prefix arrays of
 *       differing length, plus the empty-vs-single-byte boundary.</li>
 *   <li>DATETIME source × DATE destination for both isEqual and compare
 * — production multiplies the DATE side by MILLISEC_PER_DAY.</li>
 *   <li>BOOLEAN × non-canonical / uppercase STRING — pins case-insensitivity of
 *       {@code Boolean.parseBoolean} and the compare arm's three-way ternary.
 *       </li>
 *   <li>DECIMAL × BYTE compare-only positive pin — the {@code BinaryComparator}
 *       supports DECIMAL × BYTE in {@code compare} but not in {@code isEqual}; pre-existing pin
 *       only covers the isEqual gap.</li>
 * </ul>
 *
 * <p>Tests pin the database TIMEZONE attribute to GMT so the DATETIME × DATE cross-type pin is
 * reproducible regardless of CI worker timezone (the {@code MILLISEC_PER_DAY} multiplication is
 * timezone-agnostic, but downstream paths in production read the DATE's encoded days back through
 * {@code convertDayToTimezone}).
 */
public class BinaryComparatorV0EdgeCasesTest extends DbTestBase {

  private EntitySerializer serializer;
  private BinaryComparator comparator;

  @Before
  public void initSerializerAndPinTimezone() {
    serializer = RecordSerializerBinary.INSTANCE.getCurrentSerializer();
    comparator = serializer.getComparator();
    session.set(ATTRIBUTES.TIMEZONE, "GMT");
  }

  // ===========================================================================
  // LINK × LINK same-cluster, different-position. Exercises the inner else
  // branch at BinaryComparatorV0.java-1263 — collection IDs equal, so the
  // outer compare returns 0 from the cluster branch and falls to the position
  // disambiguation. Pre-existing tests only feed differing-cluster pairs, so
  // this branch is uncovered without these pins.
  // ===========================================================================

  /**
   * LINK #5:10 vs LINK #5:11 — same cluster ID, position1 (10) less than position2 (11). Pins the
   * {@code collectionPos1 < collectionPos2 -> -1} arm of the position-disambiguation branch.
   */
  @Test
  public void linkSameClusterPositionLessReturnsNegative() {
    var left = field(PropertyTypeInternal.LINK, new RecordId(5, 10));
    var right = field(PropertyTypeInternal.LINK, new RecordId(5, 11));
    assertTrue(
        "LINK #5:10 compare LINK #5:11 → < 0 (same cluster, position1 < position2)",
        comparator.compare(session, left, right) < 0);
  }

  /**
   * LINK #5:10 vs LINK #5:9 — same cluster, position1 greater than position2. Pins the
   * {@code collectionPos1 > collectionPos2 -> 1} arm.
   */
  @Test
  public void linkSameClusterPositionGreaterReturnsPositive() {
    var left = field(PropertyTypeInternal.LINK, new RecordId(5, 10));
    var right = field(PropertyTypeInternal.LINK, new RecordId(5, 9));
    assertTrue(
        "LINK #5:10 compare LINK #5:9 → > 0 (same cluster, position1 > position2)",
        comparator.compare(session, left, right) > 0);
  }

  /**
   * LINK #5:10 vs LINK #5:10 — same cluster, same position. Pins the position-equal arm
   * returning 0.
   */
  @Test
  public void linkSameClusterPositionEqualReturnsZero() {
    var left = field(PropertyTypeInternal.LINK, new RecordId(5, 10));
    var right = field(PropertyTypeInternal.LINK, new RecordId(5, 10));
    assertEquals(
        "LINK #5:10 compare LINK #5:10 → 0 (same cluster, same position)",
        0, comparator.compare(session, left, right));
    assertTrue(
        "LINK #5:10 isEqual LINK #5:10 → true",
        comparator.isEqual(session, left, right));
  }

  /**
   * LINK #5:10 vs LINK #5:11 — same cluster, different position. Pins the isEqual same-cluster /
   * different-position branch, which falls through (no `return false` on the position mismatch
   * inside the inner switch) to the outer default returning false.
   */
  @Test
  public void linkSameClusterPositionDifferentIsEqualFalse() {
    var left = field(PropertyTypeInternal.LINK, new RecordId(5, 10));
    var right = field(PropertyTypeInternal.LINK, new RecordId(5, 11));
    assertFalse(
        "LINK #5:10 isEqual LINK #5:11 → false (same cluster, position mismatch)",
        comparator.isEqual(session, left, right));
  }

  // ===========================================================================
  // BINARY × BINARY length-difference paths. Existing tests
  // only use same-length arrays, so the length tiebreak is uncovered.
  // ===========================================================================

  /**
   * BINARY {0,1,2} vs BINARY {0,1,2,3} — same prefix, left shorter. Pins the
   * {@code length2 > length1 -> -1} arm of the length tiebreak.
   */
  @Test
  public void binaryLeftShorterSamePrefixReturnsNegative() {
    var left = field(PropertyTypeInternal.BINARY, new byte[] {0, 1, 2});
    var right = field(PropertyTypeInternal.BINARY, new byte[] {0, 1, 2, 3});
    assertTrue(
        "BINARY length 3 (subset prefix) compares < BINARY length 4 (longer)",
        comparator.compare(session, left, right) < 0);
  }

  /**
   * BINARY {0,1,2,3} vs BINARY {0,1,2} — same prefix, left longer. Pins the
   * {@code length1 > length2 -> 1} arm.
   */
  @Test
  public void binaryLeftLongerSamePrefixReturnsPositive() {
    var left = field(PropertyTypeInternal.BINARY, new byte[] {0, 1, 2, 3});
    var right = field(PropertyTypeInternal.BINARY, new byte[] {0, 1, 2});
    assertTrue(
        "BINARY length 4 (superset prefix) compares > BINARY length 3 (shorter)",
        comparator.compare(session, left, right) > 0);
  }

  /**
   * BINARY empty vs BINARY single-byte — boundary length difference. The byte loop runs zero
   * iterations, so the result is decided exclusively by the length tiebreak.
   */
  @Test
  public void binaryEmptyVsSingleByteReturnsLengthOrdering() {
    var empty = field(PropertyTypeInternal.BINARY, new byte[0]);
    var oneByte = field(PropertyTypeInternal.BINARY, new byte[] {0x42});
    assertTrue(
        "BINARY empty compares < BINARY {0x42} (length tiebreak with no shared bytes)",
        comparator.compare(session, empty, oneByte) < 0);
    assertTrue(
        "BINARY {0x42} compares > BINARY empty (symmetric length tiebreak)",
        comparator.compare(session, oneByte, empty) > 0);
  }

  /**
   * BINARY same content same length — pins the equality return path with no length tiebreak so a
   * regression that returns the length tiebreak unconditionally fails.
   */
  @Test
  public void binarySameContentSameLengthReturnsZero() {
    var left = field(PropertyTypeInternal.BINARY, new byte[] {1, 2, 3});
    var right = field(PropertyTypeInternal.BINARY, new byte[] {1, 2, 3});
    assertEquals(
        "BINARY {1,2,3} compare BINARY {1,2,3} → 0 (no length tiebreak invoked)",
        0, comparator.compare(session, left, right));
  }

  // ===========================================================================
  // DATETIME source × DATE destination — production multiplies the DATE side
  // by MILLISEC_PER_DAY for both isEqual and compare
  //. Pre-existing tests cover DATE source × DATETIME dest;
  // these pin the inverse direction.
  // ===========================================================================

  /**
   * DATETIME 0 ms vs DATE 0 days — same instant (start-of-epoch), so isEqual returns true and
   * compare returns 0 in GMT.
   */
  @Test
  public void datetimeCrossDateZero() {
    var datetime0 = field(PropertyTypeInternal.DATETIME, 0L);
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertTrue(
        "DATETIME 0 ms isEqual DATE 0 days (= 0 ms after MILLISEC_PER_DAY scale)",
        comparator.isEqual(session, datetime0, date0));
    assertEquals(
        "DATETIME 0 ms compare DATE 0 days → 0",
        0, comparator.compare(session, datetime0, date0));
  }

  /**
   * DATETIME +86_400_000 ms (= +1 full day) vs DATE +1 day. Both encode the same wall-clock
   * instant under the day-multiplication, so isEqual returns true and compare returns 0.
   */
  @Test
  public void datetimeCrossDateOneDay() {
    var datetimeOneDayMs = field(PropertyTypeInternal.DATETIME, 86_400_000L);
    var dateOneDay = field(PropertyTypeInternal.DATE, 86_400_000L);
    assertTrue(
        "DATETIME 86_400_000 ms isEqual DATE +1 day",
        comparator.isEqual(session, datetimeOneDayMs, dateOneDay));
    assertEquals(
        "DATETIME 86_400_000 ms compare DATE +1 day → 0",
        0, comparator.compare(session, datetimeOneDayMs, dateOneDay));
  }

  /**
   * DATETIME compare DATE arm uses Long.compare against the day-multiplied right side. ±1 day on
   * the DATE side around DATETIME 0 produces the symmetric ordering pins.
   */
  @Test
  public void datetimeCrossDateCompareOrdering() {
    var datetime0 = field(PropertyTypeInternal.DATETIME, 0L);
    assertTrue(
        "DATETIME 0 compare DATE +1 day → < 0 (right is 86_400_000 ms after multiplication)",
        comparator.compare(session, datetime0, field(PropertyTypeInternal.DATE, 86_400_000L)) < 0);
    assertTrue(
        "DATETIME 0 compare DATE -1 day → > 0",
        comparator.compare(session, datetime0, field(PropertyTypeInternal.DATE, -86_400_000L))
            > 0);
  }

  /**
   * DATETIME 1 ms (intra-day, sub-day-floor) vs DATE 0 days. The isEqual arm
   * does NOT floor the DATETIME side, so 1 != 0; this pins the asymmetry where the DATE side gets
   * day-multiplied but the DATETIME side passes through as raw millis.
   */
  @Test
  public void datetimeCrossDateIntradayDifference() {
    var datetimeOneMs = field(PropertyTypeInternal.DATETIME, 1L);
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertFalse(
        "DATETIME 1 ms NOT isEqual DATE 0 days (left=1 vs right=0 after day-multiply)",
        comparator.isEqual(session, datetimeOneMs, date0));
    assertTrue(
        "DATETIME 1 ms compare DATE 0 days → > 0",
        comparator.compare(session, datetimeOneMs, date0) > 0);
  }

  // ===========================================================================
  // BOOLEAN source × non-canonical / uppercase STRING — pins case-insensitivity
  // of Boolean.parseBoolean for both isEqual and compare arms, and the three-way
  // ternary at compare.
  // ===========================================================================

  /**
   * BOOLEAN(true) × STRING uppercase variants — Boolean.parseBoolean is case-insensitive, so
   * "TRUE", "True", "tRuE" all match BOOLEAN(true) on isEqual.
   */
  @Test
  public void booleanCrossUppercaseTrueStringIsEqual() {
    var bTrue = field(PropertyTypeInternal.BOOLEAN, true);
    assertTrue(
        "BOOLEAN(true) isEqual STRING(\"TRUE\") (Boolean.parseBoolean is case-insensitive)",
        comparator.isEqual(session, bTrue, field(PropertyTypeInternal.STRING, "TRUE")));
    assertTrue(
        "BOOLEAN(true) isEqual STRING(\"True\")",
        comparator.isEqual(session, bTrue, field(PropertyTypeInternal.STRING, "True")));
    assertTrue(
        "BOOLEAN(true) isEqual STRING(\"tRuE\") (mixed case)",
        comparator.isEqual(session, bTrue, field(PropertyTypeInternal.STRING, "tRuE")));
  }

  /**
   * BOOLEAN(false) × STRING — any non-"true" string parses to false via Boolean.parseBoolean.
   * Pins the false-arm of isEqual including non-canonical inputs that are NOT "false".
   */
  @Test
  public void booleanCrossNonCanonicalFalseStringIsEqual() {
    var bFalse = field(PropertyTypeInternal.BOOLEAN, false);
    assertTrue(
        "BOOLEAN(false) isEqual STRING(\"FALSE\")",
        comparator.isEqual(session, bFalse, field(PropertyTypeInternal.STRING, "FALSE")));
    assertTrue(
        "BOOLEAN(false) isEqual STRING(\"junk\") (parseBoolean returns false on non-\"true\")",
        comparator.isEqual(session, bFalse, field(PropertyTypeInternal.STRING, "junk")));
    assertTrue(
        "BOOLEAN(false) isEqual STRING(\"\") (empty string parses to false)",
        comparator.isEqual(session, bFalse, field(PropertyTypeInternal.STRING, "")));
    assertTrue(
        "BOOLEAN(false) isEqual STRING(\"0\") (\"0\" is not \"true\")",
        comparator.isEqual(session, bFalse, field(PropertyTypeInternal.STRING, "0")));
  }

  /**
   * Compare arm three-way ternary at:
   * {@code (value1 == value2) ? 0 : value1 ? 1 : -1}. value1 (BOOLEAN) is true and value2
   * (parsed STRING) is false → returns 1.
   */
  @Test
  public void booleanCompareTrueAgainstStringFalseReturnsPositive() {
    var bTrue = field(PropertyTypeInternal.BOOLEAN, true);
    assertEquals(
        "BOOLEAN(true) compare STRING(\"false\") → 1 (value1 true, value2 parses false)",
        1, comparator.compare(session, bTrue, field(PropertyTypeInternal.STRING, "false")));
    assertEquals(
        "BOOLEAN(true) compare STRING(\"FALSE\") → 1 (case-insensitive)",
        1, comparator.compare(session, bTrue, field(PropertyTypeInternal.STRING, "FALSE")));
    assertEquals(
        "BOOLEAN(true) compare STRING(\"junk\") → 1 (parseBoolean returns false)",
        1, comparator.compare(session, bTrue, field(PropertyTypeInternal.STRING, "junk")));
  }

  /**
   * Compare arm three-way ternary, value1 (BOOLEAN) false vs value2 (STRING) true → returns -1.
   */
  @Test
  public void booleanCompareFalseAgainstStringTrueReturnsNegative() {
    var bFalse = field(PropertyTypeInternal.BOOLEAN, false);
    assertEquals(
        "BOOLEAN(false) compare STRING(\"true\") → -1",
        -1, comparator.compare(session, bFalse, field(PropertyTypeInternal.STRING, "true")));
    assertEquals(
        "BOOLEAN(false) compare STRING(\"TRUE\") → -1 (case-insensitive)",
        -1, comparator.compare(session, bFalse, field(PropertyTypeInternal.STRING, "TRUE")));
    assertEquals(
        "BOOLEAN(false) compare STRING(\"True\") → -1",
        -1, comparator.compare(session, bFalse, field(PropertyTypeInternal.STRING, "True")));
  }

  /**
   * Compare arm three-way ternary equality arm — both sides decode to the same boolean, returns 0.
   */
  @Test
  public void booleanCompareSameValueReturnsZero() {
    assertEquals(
        "BOOLEAN(true) compare STRING(\"TRUE\") → 0 (both parse to true)",
        0,
        comparator.compare(
            session, field(PropertyTypeInternal.BOOLEAN, true),
            field(PropertyTypeInternal.STRING, "TRUE")));
    assertEquals(
        "BOOLEAN(false) compare STRING(\"junk\") → 0 (both parse to false)",
        0,
        comparator.compare(
            session, field(PropertyTypeInternal.BOOLEAN, false),
            field(PropertyTypeInternal.STRING, "junk")));
  }

  // ===========================================================================
  // DECIMAL × BYTE compare-only positive pin. isEqual has no
  // BYTE arm in the DECIMAL switch; compare DOES, returning a sign-of-compare
  // via BigDecimal.compareTo(new BigDecimal(byteValue)).
  // ===========================================================================

  /** DECIMAL(10) compare BYTE(10) → 0 (compare arm exists; isEqual still returns false). */
  @Test
  public void decimalCompareByteEqualReturnsZero() {
    var dec10 = field(PropertyTypeInternal.DECIMAL, new BigDecimal(10));
    var byte10 = field(PropertyTypeInternal.BYTE, (byte) 10);
    assertEquals(
        "DECIMAL(10) compare BYTE(10) → 0 (compare arm widens BYTE to BigDecimal)",
        0, comparator.compare(session, dec10, byte10));
    // isEqual continues to return false because the DECIMAL × BYTE arm is absent from isEqual —
    // pinned in BinaryComparatorV0IsEqualCrossTypeTest.decimalIsEqualWithByteIsUnsupported. Repeat
    // the assertion here so a regression that adds the arm to isEqual without updating the
    // companion pin fails this file's local expectations too.
    assertFalse(
        "DECIMAL(10) isEqual BYTE(10) → false (no isEqual arm; compare arm is independent)",
        comparator.isEqual(session, dec10, byte10));
  }

  /** DECIMAL(10) compare BYTE(11) → < 0. */
  @Test
  public void decimalCompareByteRightGreaterReturnsNegative() {
    var dec10 = field(PropertyTypeInternal.DECIMAL, new BigDecimal(10));
    var byte11 = field(PropertyTypeInternal.BYTE, (byte) 11);
    assertTrue(
        "DECIMAL(10) compare BYTE(11) → < 0",
        comparator.compare(session, dec10, byte11) < 0);
  }

  /** DECIMAL(10) compare BYTE(9) → > 0. */
  @Test
  public void decimalCompareByteRightLessReturnsPositive() {
    var dec10 = field(PropertyTypeInternal.DECIMAL, new BigDecimal(10));
    var byte9 = field(PropertyTypeInternal.BYTE, (byte) 9);
    assertTrue(
        "DECIMAL(10) compare BYTE(9) → > 0",
        comparator.compare(session, dec10, byte9) > 0);
  }

  /**
   * DECIMAL fractional value vs BYTE — compare uses BigDecimal.compareTo(new BigDecimal(byte)),
   * so DECIMAL 9.5 against BYTE 10 returns < 0; against BYTE 9 returns > 0. Pins that the
   * widening preserves the BigDecimal scale rather than truncating.
   */
  @Test
  public void decimalCompareByteFractionalDecimalPreservesOrdering() {
    var dec95 = field(PropertyTypeInternal.DECIMAL, new BigDecimal("9.5"));
    assertTrue(
        "DECIMAL(9.5) compare BYTE(10) → < 0",
        comparator.compare(session, dec95, field(PropertyTypeInternal.BYTE, (byte) 10)) < 0);
    assertTrue(
        "DECIMAL(9.5) compare BYTE(9) → > 0",
        comparator.compare(session, dec95, field(PropertyTypeInternal.BYTE, (byte) 9)) > 0);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private BinaryField field(PropertyTypeInternal type, Object value) {
    return BinaryComparatorV0TestFixture.field(serializer, session, type, value);
  }
}
