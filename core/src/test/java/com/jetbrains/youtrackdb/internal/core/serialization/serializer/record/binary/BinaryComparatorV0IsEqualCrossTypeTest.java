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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

/**
 * Cross-type {@code isEqual} coverage for {@link BinaryComparatorV0}. The pre-existing
 * {@code BinaryComparatorEqualsTest} only drives same-type pairs (INTEGER×INTEGER, LONG×LONG, …)
 * via the {@code testEquals(type, type)} helper — this class drives every numeric source against
 * every numeric destination, plus the cross-arm pairs that have no companion in
 * {@code testCompareNumber}'s sweep.
 *
 * <p>Each test method picks a single source type and walks the destinations supported by its
 * {@code isEqual} arm in {@link BinaryComparatorV0}. Equal-value pairs return {@code true}; pairs
 * differing by ±1 return {@code false}.
 */
public class BinaryComparatorV0IsEqualCrossTypeTest extends DbTestBase {

  private EntitySerializer serializer;
  private BinaryComparator comparator;

  @Before
  public void initSerializerAndPinTimezone() {
    serializer = RecordSerializerBinary.INSTANCE.getCurrentSerializer();
    comparator = serializer.getComparator();
    // Pin GMT so DATE encoding/comparison is reproducible across CI/developer hosts —
    // sibling tests in this package (BinaryComparatorV0DateSourceTest /
    // BinaryComparatorV0EdgeCasesTest) follow the same convention. Without it, the DATE
    // source/destination arms route through convertDayToTimezone with the JVM-default
    // database timezone, which can shift the encoded day by ±1 on hosts west of GMT.
    session.set(ATTRIBUTES.TIMEZONE, "GMT");
  }

  // ===========================================================================
  // INTEGER source — supported destinations: INTEGER, LONG, DATETIME, DATE,
  // SHORT, BYTE, FLOAT, DOUBLE, STRING, DECIMAL.
  // DATE cells use src=0 because the production arm compares
  // {@code value1 (int) == readAsLong(fv2) * MILLISEC_PER_DAY} — the only int
  // value that equals a non-trivial day count is 0.
  // ===========================================================================

  /** INTEGER 10 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void integerCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.INTEGER;
    Object srcVal = 10;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  /**
   * INTEGER × DATE — the production arm compares
   * {@code int value1 == (readAsLong(fv2) * MILLISEC_PER_DAY)}. Source value 0 equals DATE 0L
   * (= 0 ms); DATE 1L (= 86_400_000 ms) and DATE -1L (= -86_400_000 ms) do NOT equal int 0.
   */
  @Test
  public void integerCrossDateIsEqual() {
    var src = PropertyTypeInternal.INTEGER;
    var dst = PropertyTypeInternal.DATE;

    assertTrue(
        "INTEGER 0 == DATE 0 days (= 0 ms)",
        comparator.isEqual(session, field(src, 0), field(dst, 0L)));
    assertFalse(
        "INTEGER 0 != DATE +1 day (= 86_400_000 ms)",
        comparator.isEqual(session, field(src, 0), field(dst, 86_400_000L)));
    assertFalse(
        "INTEGER 0 != DATE -1 day (= -86_400_000 ms)",
        comparator.isEqual(session, field(src, 0), field(dst, -86_400_000L)));
  }

  // ===========================================================================
  // LONG source — supported destinations: same as INTEGER plus DATE.
  // ===========================================================================

  /** LONG 10 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void longCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.LONG;
    Object srcVal = 10L;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  /**
   * LONG × DATE — same shape as INTEGER × DATE: source value 0L equals DATE 0L.
   * LONG can hold values larger than 32-bit DATE arithmetic but the day-multiplied right-hand
   * side and zero left-hand side keep this pin straightforward. Distinct from
   * {@code BinaryComparatorV0DateSourceTest.dateCrossLongUnderGmt} which exercises the inverse
   * source/destination pairing (DATE source × LONG dest).
   */
  @Test
  public void longCrossDateIsEqual() {
    var src = PropertyTypeInternal.LONG;
    var dst = PropertyTypeInternal.DATE;

    assertTrue(
        "LONG 0L == DATE 0 days (= 0 ms)",
        comparator.isEqual(session, field(src, 0L), field(dst, 0L)));
    assertFalse(
        "LONG 0L != DATE +1 day",
        comparator.isEqual(session, field(src, 0L), field(dst, 86_400_000L)));
    // LONG of one full day equals DATE 1 day (86_400_000 == 1 * MILLISEC_PER_DAY).
    assertTrue(
        "LONG 86_400_000L == DATE +1 day",
        comparator.isEqual(session, field(src, 86_400_000L), field(dst, 86_400_000L)));
  }

  // ===========================================================================
  // SHORT source — supported destinations: same as INTEGER.
  // ===========================================================================

  /** SHORT 10 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void shortCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.SHORT;
    Object srcVal = (short) 10;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  /**
   * SHORT × DATE — production arm widens SHORT to long and compares against
   * {@code readAsLong(fv2) * MILLISEC_PER_DAY}. Only SHORT 0 equals DATE 0; SHORT range
   * (−32 768..32 767) is far too small to equal any non-zero day-multiplied value.
   */
  @Test
  public void shortCrossDateIsEqual() {
    var src = PropertyTypeInternal.SHORT;
    var dst = PropertyTypeInternal.DATE;

    assertTrue(
        "SHORT 0 == DATE 0 days",
        comparator.isEqual(session, field(src, (short) 0), field(dst, 0L)));
    assertFalse(
        "SHORT 0 != DATE +1 day (= 86_400_000 ms ≫ Short.MAX_VALUE)",
        comparator.isEqual(session, field(src, (short) 0), field(dst, 86_400_000L)));
  }

  // ===========================================================================
  // BYTE source — supported destinations: INTEGER, LONG, DATETIME, SHORT, BYTE,
  // FLOAT, DOUBLE, STRING, DECIMAL. Note BYTE has NO DATE arm in isEqual.
  // ===========================================================================

  /** BYTE 10 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void byteCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.BYTE;
    Object srcVal = (byte) 10;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  // ===========================================================================
  // FLOAT source — uses bit-exact float comparison; STRING via Float.parseFloat.
  // FLOAT has NO DATE arm in isEqual.
  // ===========================================================================

  /** FLOAT 10.0 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void floatCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.FLOAT;
    Object srcVal = 10f;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    // STRING isEqual uses Float.parseFloat, so "10" parses identically to 10f.
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  // ===========================================================================
  // DOUBLE source — uses bit-exact double comparison; STRING via Double.parseDouble.
  // DOUBLE has NO DATE arm in isEqual.
  // ===========================================================================

  /** DOUBLE 10.0 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void doubleCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.DOUBLE;
    Object srcVal = 10d;

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  // ===========================================================================
  // STRING source — supported destinations: every numeric/temporal/STRING/DECIMAL plus BOOLEAN.
  // STRING parses to the destination type's primitive via Integer.parseInt / Long.parseLong / etc.
  // ===========================================================================

  /** STRING "10" vs each numeric/temporal destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void stringCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.STRING;
    Object srcVal = "10";

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.BYTE, (byte) 10, (byte) 9, (byte) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  /**
   * STRING × DATE — production arm calls
   * {@code Long.parseLong(readString(fv1)) == readAsLong(fv2) * MILLISEC_PER_DAY}. The numeric
   * STRING parses via Long.parseLong and is compared against the day-multiplied DATE side.
   */
  @Test
  public void stringCrossDateIsEqual() {
    var src = PropertyTypeInternal.STRING;
    var dst = PropertyTypeInternal.DATE;

    assertTrue(
        "STRING \"0\" == DATE 0 days (parseLong(\"0\") == 0)",
        comparator.isEqual(session, field(src, "0"), field(dst, 0L)));
    assertTrue(
        "STRING \"86400000\" == DATE +1 day (parseLong matches 1 * MILLISEC_PER_DAY)",
        comparator.isEqual(session, field(src, "86400000"), field(dst, 86_400_000L)));
    assertFalse(
        "STRING \"1\" != DATE 0 days",
        comparator.isEqual(session, field(src, "1"), field(dst, 0L)));
  }

  /** STRING source × BOOLEAN destination: parses STRING via Boolean.parseBoolean and compares
   * against the BOOLEAN byte. */
  @Test
  public void stringCrossBooleanIsEqual() {
    var src = PropertyTypeInternal.STRING;

    // True path — STRING "true" matches BOOLEAN true; lowercase only (Boolean.parseBoolean is
    // case-insensitive, but the canonical wire form is lowercase).
    assertTrue(
        comparator.isEqual(session, field(src, "true"),
            field(PropertyTypeInternal.BOOLEAN, true)));
    assertFalse(
        comparator.isEqual(session, field(src, "true"),
            field(PropertyTypeInternal.BOOLEAN, false)));
    // False path — STRING "false" matches BOOLEAN false; non-canonical strings parse to false.
    assertTrue(
        comparator.isEqual(session, field(src, "false"),
            field(PropertyTypeInternal.BOOLEAN, false)));
    assertFalse(
        comparator.isEqual(session, field(src, "false"),
            field(PropertyTypeInternal.BOOLEAN, true)));
    // Boolean.parseBoolean returns false for any non-"true" string, so any non-canonical
    // string is "equal" to BOOLEAN false but not BOOLEAN true.
    assertTrue(
        comparator.isEqual(session, field(src, "anything-else"),
            field(PropertyTypeInternal.BOOLEAN, false)));
    assertFalse(
        comparator.isEqual(session, field(src, "anything-else"),
            field(PropertyTypeInternal.BOOLEAN, true)));
  }

  // ===========================================================================
  // DECIMAL source — supported destinations: INTEGER, LONG, DATETIME, SHORT,
  // FLOAT, DOUBLE, STRING, DECIMAL. Note: BYTE is NOT in the isEqual decimal arm
  // — only in the compare arm. Verified below.
  // ===========================================================================

  /** DECIMAL 10 vs each numeric destination at 10 returns equal; ±1 returns not-equal. */
  @Test
  public void decimalCrossNumericIsEqualMatrix() {
    var src = PropertyTypeInternal.DECIMAL;
    Object srcVal = new BigDecimal(10);

    assertEqualPair(src, srcVal, PropertyTypeInternal.INTEGER, 10, 9, 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.LONG, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DATETIME, 10L, 9L, 11L);
    assertEqualPair(src, srcVal, PropertyTypeInternal.SHORT, (short) 10, (short) 9, (short) 11);
    assertEqualPair(src, srcVal, PropertyTypeInternal.FLOAT, 10f, 9f, 11f);
    assertEqualPair(src, srcVal, PropertyTypeInternal.DOUBLE, 10d, 9d, 11d);
    assertEqualPair(src, srcVal, PropertyTypeInternal.STRING, "10", "9", "11");
    assertEqualPair(
        src, srcVal, PropertyTypeInternal.DECIMAL, new BigDecimal(10), new BigDecimal(9),
        new BigDecimal(11));
  }

  /** DECIMAL source × BYTE dest is NOT supported by isEqual (only by compare). The fallthrough
   * to the default arm yields {@code return false} regardless of value equality. */
  @Test
  public void decimalIsEqualWithByteIsUnsupported() {
    // Even when the values are nominally equal, isEqual must return false because the BYTE
    // arm is missing from the DECIMAL switch in isEqual. A regression that adds the
    // arm to isEqual without updating this pin will fail loudly here.
    assertFalse(
        comparator.isEqual(
            session,
            field(PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(PropertyTypeInternal.BYTE, (byte) 10)));
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /**
   * Asserts that the comparator's isEqual returns {@code true} for {@code (srcVal, eqVal)} and
   * {@code false} for {@code (srcVal, ltVal)} and {@code (srcVal, gtVal)}. Each call serializes
   * fresh BinaryFields so any in-place offset mutation does not leak between assertions.
   */
  private void assertEqualPair(
      PropertyTypeInternal src, Object srcVal,
      PropertyTypeInternal dst, Object eqVal, Object ltVal, Object gtVal) {
    var label = src + " × " + dst + ": ";
    assertTrue(label + "isEqual(" + srcVal + ", " + eqVal + ") expected true",
        comparator.isEqual(session, field(src, srcVal), field(dst, eqVal)));
    assertFalse(label + "isEqual(" + srcVal + ", " + ltVal + ") expected false",
        comparator.isEqual(session, field(src, srcVal), field(dst, ltVal)));
    assertFalse(label + "isEqual(" + srcVal + ", " + gtVal + ") expected false",
        comparator.isEqual(session, field(src, srcVal), field(dst, gtVal)));
  }

  /** Allocates a fresh BytesContainer, serializes the value at offset 0, and wraps it as a
   * BinaryField with no collation. Container offset is reset to 0 so the comparator reads from
   * the value's first byte. Field name is {@code null} to match {@code AbstractComparatorTest}'s
   * convention; the comparator does not consume the name. */
  private BinaryField field(PropertyTypeInternal type, Object value) {
    var bytes = new BytesContainer();
    bytes.offset = serializer.serializeValue(session, bytes, value, type, null, null, null);
    return new BinaryField(null, type, new BytesContainer(bytes.bytes, 0), null);
  }
}
