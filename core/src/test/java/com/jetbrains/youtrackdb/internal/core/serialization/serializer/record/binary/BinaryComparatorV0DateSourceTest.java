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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

/**
 * Coverage for {@link BinaryComparatorV0} with DATE as the source type. The pre-existing
 * {@code BinaryComparatorCompareTest} drives the source via {@code testCompareNumber} only for
 * INTEGER, LONG, SHORT, BYTE, FLOAT, DOUBLE, DATETIME — DATE is not in that list, so its source
 * arms (the {@code isEqual} DATE-source switch and the {@code compare} DATE-source switch)
 * are largely uncovered.
 *
 * <p>Tests use a fixed GMT database timezone so DATE's {@code value1 = days × MILLISEC_PER_DAY}
 * conversion is reproducible, and the {@code DATE × LONG/DATETIME} {@code convertDayToTimezone}
 * branch becomes a no-op (GMT→GMT). DATE 0 days is the universal "equal" sample because
 * {@code 0 × MILLISEC_PER_DAY = 0}, which equals numeric 0 in any companion type.
 *
 * <p>Cross-type pairs that the comparator does NOT support (DATE × BOOLEAN, BINARY, LINK, BYTE)
 * are also pinned: {@code isEqual} returns {@code false} and {@code compare} returns {@code 1}
 * (the "no compare supported" sentinel) regardless of value equality.
 */
public class BinaryComparatorV0DateSourceTest extends DbTestBase {

  private EntitySerializer serializer;
  private BinaryComparator comparator;

  @Before
  public void initSerializerAndPinTimezone() {
    serializer = RecordSerializerBinary.INSTANCE.getCurrentSerializer();
    comparator = serializer.getComparator();
    // Pin the database timezone so DATE encodings and DATE × LONG/DATETIME conversion are
    // reproducible across CI/developer machines.
    session.set(ATTRIBUTES.TIMEZONE, "GMT");
  }

  // ===========================================================================
  // DATE × DATE — same-type ordering at day granularity.
  // ===========================================================================

  /**
   * DATE 0 ms vs DATE 0 ms → equal; DATE 0 vs DATE ∓one-day → ordered. DATE serialization
   * accepts millis (not days) and divides by MILLISEC_PER_DAY internally — see
   * {@link RecordSerializerBinaryV1}. Using day-aligned millis as test fixtures keeps
   * the encoded {@code days} differing across the three samples.
   */
  @Test
  public void dateSameTypeEqualityAndOrdering() {
    // Each call to field(...) allocates a fresh BytesContainer at offset 0; the comparator's
    // try/finally restores offsets so a single instance per side is sufficient — but using
    // distinct allocations per assertion keeps the test self-contained.
    assertTrue(
        "DATE 0 ms isEqual DATE 0 ms",
        comparator.isEqual(
            session, field(PropertyTypeInternal.DATE, 0L), field(PropertyTypeInternal.DATE, 0L)));
    assertFalse(
        "DATE 0 ms isEqual DATE -86_400_000 ms (-1 day)",
        comparator.isEqual(
            session,
            field(PropertyTypeInternal.DATE, 0L),
            field(PropertyTypeInternal.DATE, -86_400_000L)));
    assertFalse(
        "DATE 0 ms isEqual DATE +86_400_000 ms (+1 day)",
        comparator.isEqual(
            session,
            field(PropertyTypeInternal.DATE, 0L),
            field(PropertyTypeInternal.DATE, 86_400_000L)));

    assertEquals(
        0,
        comparator.compare(
            session, field(PropertyTypeInternal.DATE, 0L), field(PropertyTypeInternal.DATE, 0L)));
    assertTrue(
        comparator.compare(
            session,
            field(PropertyTypeInternal.DATE, 0L),
            field(PropertyTypeInternal.DATE, -86_400_000L))
            > 0);
    assertTrue(
        comparator.compare(
            session,
            field(PropertyTypeInternal.DATE, 0L),
            field(PropertyTypeInternal.DATE, 86_400_000L))
            < 0);
  }

  // ===========================================================================
  // DATE × INTEGER — value1 (DATE in millis) compared to value2 (INT).
  // ===========================================================================

  /** DATE 0 days = 0 ms equals INTEGER 0; ±1 around 0 produces ordered comparisons. */
  @Test
  public void dateCrossInteger() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(comparator.isEqual(session, date0, field(PropertyTypeInternal.INTEGER, 0)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.INTEGER, -1)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.INTEGER, 1)));

    assertEquals(0, comparator.compare(session, date0, field(PropertyTypeInternal.INTEGER, 0)));
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.INTEGER, -1)) > 0);
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.INTEGER, 1)) < 0);
  }

  // ===========================================================================
  // DATE × LONG — drives the convertDayToTimezone arm; under a GMT-pinned session
  // the conversion is a no-op so direct equality holds.
  // ===========================================================================

  /**
   * DATE × LONG under GMT: 0 ms vs 0L equals; ∓1 day produces ordering by signed long compare.
   *
   * <p>Subtle: the {@code isEqual} arm calls
   * {@code convertDayToTimezone(databaseTZ, GMT, value2)} which FLOORS the LONG value to the
   * start of its day. Therefore intra-day ±1 ms variations on the LONG side compare equal to
   * DATE 0. The test fixtures use ∓one-full-day to produce distinct day-aligned values.
   * The {@code compare} arm does NOT apply this flooring (it just calls
   * {@code Long.compare(value1, value2)}), so compare with intra-day ±1 ms still produces a
   * non-zero ordering; the matching {@link #dateCompareLongIntradaySignsAreLiteralLongCompare}
   * test below pins that distinction.
   */
  @Test
  public void dateCrossLongUnderGmt() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(comparator.isEqual(session, date0, field(PropertyTypeInternal.LONG, 0L)));
    assertFalse(
        "DATE 0 ms is not equal to LONG -86_400_000 (-1 day) under day-flooring",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.LONG, -86_400_000L)));
    assertFalse(
        "DATE 0 ms is not equal to LONG 86_400_000 (+1 day) under day-flooring",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.LONG, 86_400_000L)));

    assertEquals(0, comparator.compare(session, date0, field(PropertyTypeInternal.LONG, 0L)));
    assertTrue(
        comparator.compare(session, date0, field(PropertyTypeInternal.LONG, -86_400_000L)) > 0);
    assertTrue(
        comparator.compare(session, date0, field(PropertyTypeInternal.LONG, 86_400_000L)) < 0);
  }

  /**
   * DATE × LONG compare with intra-day ±1 ms on the LONG side — pins the asymmetry between the
   * isEqual arm (which floors via convertDayToTimezone) and the compare arm (which does literal
   * Long.compare). A regression that adds flooring to compare would round both ±1 ms variants to
   * 0 and break this test.
   */
  @Test
  public void dateCompareLongIntradaySignsAreLiteralLongCompare() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    // compare uses literal Long.compare(0, -1) = 1 (positive — right is less).
    assertTrue(
        "compare(DATE 0, LONG -1) > 0 — compare arm does literal Long.compare, no day flooring",
        comparator.compare(session, date0, field(PropertyTypeInternal.LONG, -1L)) > 0);
    // compare uses literal Long.compare(0, 1) = -1 (negative — right is greater).
    assertTrue(
        "compare(DATE 0, LONG 1) < 0 — compare arm does literal Long.compare, no day flooring",
        comparator.compare(session, date0, field(PropertyTypeInternal.LONG, 1L)) < 0);

    // isEqual: positive intra-day LONG values floor to 0 (start of 1970-01-01 GMT) so DATE 0
    // erroneously equals LONG 1 ms. NEGATIVE intra-day values floor to the previous day's
    // start (-86_400_000), so they do NOT spuriously match. This asymmetry pins the latent
    // semantics — pinning current behavior so a future fix that drops the flooring fails
    // these assertions loudly. WHEN-FIXED: deferred-cleanup track.
    assertTrue(
        "isEqual(DATE 0, LONG 1) returns TRUE due to day-flooring of LONG side in"
            + " BinaryComparatorV0.isEqualSwitch DATE×LONG arm — positive intra-day flooring"
            + " rounds to 0. WHEN-FIXED: deferred-cleanup track.",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.LONG, 1L)));
    assertFalse(
        "isEqual(DATE 0, LONG -1) returns FALSE because the day-flooring of -1 ms yields"
            + " -86_400_000 (start of 1969-12-31), not 0 — pinning the asymmetry between"
            + " positive and negative intra-day flooring.",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.LONG, -1L)));
  }

  // ===========================================================================
  // DATE × DATETIME — DATETIME holds millis; under GMT-pinned session no offset.
  // ===========================================================================

  /**
   * DATE × DATETIME under GMT: 0 ms vs 0L equals; ∓one-full-day produces day-distinct
   * comparisons. Like LONG, DATETIME goes through the {@code case LONG, DATETIME} arm at
   * which floors via {@code convertDayToTimezone} — so intra-day ms variations on
   * the DATETIME side cannot distinguish from DATE 0 in {@code isEqual}.
   */
  @Test
  public void dateCrossDatetime() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    var dateOneDay = field(PropertyTypeInternal.DATE, 86_400_000L);

    assertTrue(comparator.isEqual(session, date0, field(PropertyTypeInternal.DATETIME, 0L)));
    assertFalse(
        "DATE 0 != DATETIME -86_400_000 (-1 full day)",
        comparator.isEqual(session, date0,
            field(PropertyTypeInternal.DATETIME, -86_400_000L)));
    assertTrue(
        "DATE +1 day equals DATETIME 86_400_000 ms (same day)",
        comparator.isEqual(
            session, dateOneDay, field(PropertyTypeInternal.DATETIME, 86_400_000L)));

    // compare arm does literal Long.compare without flooring, so intra-day ±1 ms
    // produces a non-zero ordering.
    assertEquals(0, comparator.compare(session, date0, field(PropertyTypeInternal.DATETIME, 0L)));
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.DATETIME, -1L)) > 0);
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.DATETIME, 1L)) < 0);
  }

  // ===========================================================================
  // DATE × SHORT — SHORT range [-32768, 32767]; only small DATE×SHORT values pin.
  // ===========================================================================

  /** DATE 0 = 0 ms equals SHORT 0; off-by-one ordering is signed at 16-bit range. */
  @Test
  public void dateCrossShort() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(comparator.isEqual(session, date0,
        field(PropertyTypeInternal.SHORT, (short) 0)));
    assertFalse(comparator.isEqual(session, date0,
        field(PropertyTypeInternal.SHORT, (short) -1)));
    assertFalse(comparator.isEqual(session, date0,
        field(PropertyTypeInternal.SHORT, (short) 1)));

    assertEquals(
        0, comparator.compare(session, date0, field(PropertyTypeInternal.SHORT, (short) 0)));
    assertTrue(
        comparator.compare(session, date0, field(PropertyTypeInternal.SHORT, (short) -1)) > 0);
    assertTrue(
        comparator.compare(session, date0, field(PropertyTypeInternal.SHORT, (short) 1)) < 0);
  }

  // ===========================================================================
  // DATE × FLOAT — DATE millis converted via Float.intBitsToFloat from FLOAT bytes.
  // ===========================================================================

  /** DATE 0 = 0 ms equals FLOAT 0.0f; ±1 around 0 produces ordered comparisons. */
  @Test
  public void dateCrossFloat() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(comparator.isEqual(session, date0, field(PropertyTypeInternal.FLOAT, 0f)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.FLOAT, -1f)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.FLOAT, 1f)));

    assertEquals(0, comparator.compare(session, date0, field(PropertyTypeInternal.FLOAT, 0f)));
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.FLOAT, -1f)) > 0);
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.FLOAT, 1f)) < 0);
  }

  // ===========================================================================
  // DATE × DOUBLE — DATE millis converted via Double.longBitsToDouble.
  // ===========================================================================

  /** DATE 0 = 0 ms equals DOUBLE 0.0d; ±1 around 0 produces ordered comparisons. */
  @Test
  public void dateCrossDouble() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(comparator.isEqual(session, date0, field(PropertyTypeInternal.DOUBLE, 0d)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.DOUBLE, -1d)));
    assertFalse(comparator.isEqual(session, date0, field(PropertyTypeInternal.DOUBLE, 1d)));

    assertEquals(0, comparator.compare(session, date0, field(PropertyTypeInternal.DOUBLE, 0d)));
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.DOUBLE, -1d)) > 0);
    assertTrue(comparator.compare(session, date0, field(PropertyTypeInternal.DOUBLE, 1d)) < 0);
  }

  // ===========================================================================
  // DATE × DECIMAL — DATE millis compared to BigDecimal.longValue() in isEqual,
  // and to BigDecimal directly via Long.compare in compare.
  // ===========================================================================

  /** DATE 0 vs BigDecimal(0) equal; ±1 produces ordered comparisons. */
  @Test
  public void dateCrossDecimal() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertTrue(
        "DATE 0 ms isEqual DECIMAL 0",
        comparator.isEqual(
            session, date0, field(PropertyTypeInternal.DECIMAL, new BigDecimal(0))));
    assertFalse(
        "DATE 0 ms NOT isEqual DECIMAL -1",
        comparator.isEqual(
            session, date0, field(PropertyTypeInternal.DECIMAL, new BigDecimal(-1))));
    // +1 case pins the value1 < value2.longValue() arm of the BigDecimal comparison; without
    // it, a stub that returned `value1 == value2.longValue() || value1 < value2.longValue()` for
    // small inputs could pass the -1 case alone.
    assertFalse(
        "DATE 0 ms NOT isEqual DECIMAL +1",
        comparator.isEqual(
            session, date0, field(PropertyTypeInternal.DECIMAL, new BigDecimal(1))));

    assertEquals(0,
        comparator.compare(session, date0,
            field(PropertyTypeInternal.DECIMAL, new BigDecimal(0))));
    assertTrue(comparator.compare(session, date0,
        field(PropertyTypeInternal.DECIMAL, new BigDecimal(-1))) > 0);
    assertTrue(comparator.compare(session, date0,
        field(PropertyTypeInternal.DECIMAL, new BigDecimal(1))) < 0);
  }

  // ===========================================================================
  // DATE × STRING — compare uses readString + (Long.parseLong | DateFormat.parse | toString).
  // The numeric-string fast path is exercised here.
  // ===========================================================================

  /**
   * DATE × STRING compare with a numeric string: enters the {@code IOUtils.isLong} fast path and
   * compares via {@code Long.compare(value1, parseLong(value2))}.
   */
  @Test
  public void dateCompareStringNumericFastPath() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    assertEquals(
        "DATE 0 ms compares equal to STRING \"0\" (numeric fast path)",
        0, comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "0")));
    assertTrue(
        "DATE 0 ms is greater than STRING \"-1\"",
        comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "-1")) > 0);
    assertTrue(
        "DATE 0 ms is less than STRING \"1\"",
        comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "1")) < 0);
  }

  /**
   * DATE × STRING compare with a date-format string: enters the {@code DateFormat.parse} path
   *. The string parses as a date in the database timezone; the result is the
   * day-aligned millis comparison. Pins both equality and the two ordering directions so a
   * stub `return 0` regression in the date-format branch is caught.
   */
  @Test
  public void dateCompareStringDateFormatPath() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    // "1970-01-01" parses to 0 ms in GMT; equals DATE 0 days.
    assertEquals(
        "DATE 0 ms equals STRING \"1970-01-01\" (date-format fast path under GMT)",
        0, comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "1970-01-01")));
    // "1969-12-31" parses to -86_400_000 ms — strictly less than 0 → DATE 0 is greater (>0).
    assertTrue(
        "DATE 0 ms compares > 0 against STRING \"1969-12-31\" (right-side parses as -1 day)",
        comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "1969-12-31")) > 0);
    // "1970-01-02" parses to +86_400_000 ms — strictly greater than 0 → DATE 0 is less (<0).
    assertTrue(
        "DATE 0 ms compares < 0 against STRING \"1970-01-02\" (right-side parses as +1 day)",
        comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "1970-01-02")) < 0);
  }

  /**
   * DATE × STRING compare with a malformed (non-numeric, non-date) string: falls through to the
   * {@code new Date(value1).toString().compareTo(value2AsString)} fallback. The
   * production behavior is locked here so a regression that, e.g., throws instead of falling
   * through is caught.
   */
  @Test
  public void dateCompareStringMalformedFallsThroughToToString() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);

    // Both DateFormat.parse calls (datetime-format then date-format) raise ParseException, so
    // production falls through to: new Date(value1).toString().compareTo(value2AsString).
    // The expected sign is whatever new Date(0L).toString() compares to "junk-string". We assert
    // a non-zero result with a falsifiable shape — a future regression that returns 0 (silent
    // accept) would fail.
    var result =
        comparator.compare(session, date0, field(PropertyTypeInternal.STRING, "junk-string"));
    assertTrue(
        "DATE 0 ms compared to malformed STRING returns non-zero ordering",
        result != 0);
    // The sign is deterministic: Date(0).toString() starts with "Thu Jan 01..." which
    // lexicographically begins with 'T' (0x54). "junk-string" begins with 'j' (0x6A). Therefore
    // the toString comparison returns a negative value (T < j).
    assertTrue(
        "Date(0).toString() lexicographically precedes \"junk-string\" → compare returns < 0",
        result < 0);
  }

  /**
   * DATE × STRING isEqual is a LATENT BUG: production routes STRING and DECIMAL through the same
   * arm of {@link BinaryComparatorV0}, calling
   * {@code DecimalSerializer.deserialize} on the STRING bytes. The STRING wire format is
   * {@code varint(length) + UTF-8 bytes}, NOT the {@code DecimalSerializer} format
   * ({@code int scale + int unscaledLen + unscaled bytes}), so the deserializer interprets
   * the leading varint+UTF8 bytes as scale+length+payload — the result is either a garbage
   * long, an out-of-bounds read, or a {@link NumberFormatException} like the one pinned below.
   *
   * <p>For the canonical numeric-string {@code "0"} (encoded as 2 bytes: zigzag varint 1 + UTF-8
   * '0' = {@code 0x02 0x30}), {@code DecimalSerializer.deserialize} reads the first 4 bytes as
   * scale, then the next 4 as unscaledLen — both reach past the actual buffer end. The
   * production behavior is to THROW {@link NumberFormatException} from {@code new BigInteger}
   * with a {@code "Zero length BigInteger"} message.
   *
   * <p>This is a DoS / crash-on-bad-input risk: a server fed an attacker-controlled STRING
   * field value would crash the comparator inside any DATE-vs-STRING isEqual check. Pinned
   * here so the future fix (separating the STRING arm to use {@code Long.parseLong(readString)})
   * fails this assertion loudly. WHEN-FIXED: deferred-cleanup track.
   */
  @Test
  public void dateIsEqualStringThrowsBecauseOfDecimalDeserializerMisuse() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    // Production currently throws NumberFormatException with the BigInteger constructor's
    // diagnostic when DecimalSerializer.deserialize is invoked on STRING-encoded bytes. The
    // message starts with "Zero length BigInteger" — assert it contains the BigInteger marker so
    // a regression that throws a DIFFERENT NumberFormatException (e.g. from Long.parseLong on
    // the STRING bytes after a partial cleanup) does not silently pass. WHEN-FIXED: the proper
    // fix is to separate the STRING arm so STRING bytes round-trip via Long.parseLong; at that
    // point this test should be flipped to assert isEqual returns false.
    var nfe = assertThrows(
        NumberFormatException.class,
        () -> comparator.isEqual(session, date0, field(PropertyTypeInternal.STRING, "0")));
    assertTrue(
        "Expected NumberFormatException raised by BigInteger ctor inside"
            + " DecimalSerializer.deserialize; actual message: " + nfe.getMessage(),
        nfe.getMessage() != null && nfe.getMessage().contains("BigInteger"));
  }

  // ===========================================================================
  // Unsupported DATE pairs — the inner switch has no arms for BYTE,
  // BOOLEAN, BINARY, or LINK; isEqual returns false and compare returns 1.
  // ===========================================================================

  /**
   * DATE × BYTE: BYTE is not in the DATE source's isEqual arm — returns false. The
   * compare arm returns 1 as a sentinel meaning "unsupported pair". The naked {@code 1} value is
   * indistinguishable from "left strictly greater" if a future arm is added; the paired
   * {@code isEqual=false} on equivalent values disambiguates: a real arm that returned 0 for
   * equal values would not match {@code isEqual=false}, so this pin requires both signals to
   * shift in lockstep when the sentinel is replaced by a real arm. WHEN-FIXED: future addition
   * of DATE × BYTE arms.
   */
  @Test
  public void dateIsEqualWithByteIsUnsupported() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertFalse(
        "DATE × BYTE isEqual: unsupported pair → false (no DATE source × BYTE dest arm)",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.BYTE, (byte) 0)));
    assertEquals(
        "DATE × BYTE compare: unsupported pair → 1 sentinel; paired with isEqual=false above to"
            + " disambiguate from a hypothetical real arm returning 1 for left>right",
        1, comparator.compare(session, date0, field(PropertyTypeInternal.BYTE, (byte) 0)));
  }

  /**
   * DATE × BOOLEAN: unsupported. Pinned with paired isEqual=false on both true and false BOOLEAN
   * inputs, plus the compare-sentinel 1 — a future arm cannot return 1 for both BOOLEAN(true) and
   * BOOLEAN(false) while also keeping isEqual false on both, so the multi-input pin is robust to
   * a regression that adds a partial arm.
   */
  @Test
  public void dateIsEqualWithBooleanIsUnsupported() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertFalse(
        "DATE × BOOLEAN(true) isEqual: unsupported pair → false",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.BOOLEAN, true)));
    assertFalse(
        "DATE × BOOLEAN(false) isEqual: unsupported pair → false",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.BOOLEAN, false)));
    assertEquals(
        "DATE × BOOLEAN(true) compare: unsupported pair → 1 sentinel",
        1, comparator.compare(session, date0, field(PropertyTypeInternal.BOOLEAN, true)));
    assertEquals(
        "DATE × BOOLEAN(false) compare: unsupported pair → 1 sentinel"
            + " (a real arm could not return 1 for both true and false against the same DATE)",
        1, comparator.compare(session, date0, field(PropertyTypeInternal.BOOLEAN, false)));
  }

  /**
   * DATE × BINARY: unsupported. Empty-binary input is paired with single-byte input — a real arm
   * could not return 1 for both length-0 and length-1 BINARYs against DATE 0, so the multi-input
   * pin disambiguates from "unsupported" vs a future "real arm returning 1 for left>right".
   */
  @Test
  public void dateIsEqualWithBinaryIsUnsupported() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertFalse(
        "DATE × BINARY{0} isEqual: unsupported pair → false",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.BINARY, new byte[] {0})));
    assertFalse(
        "DATE × BINARY{} (empty) isEqual: unsupported pair → false",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.BINARY, new byte[0])));
    assertEquals(
        "DATE × BINARY{0} compare: unsupported pair → 1 sentinel",
        1, comparator.compare(session, date0, field(PropertyTypeInternal.BINARY, new byte[] {0})));
    assertEquals(
        "DATE × BINARY{} (empty) compare: unsupported pair → 1 sentinel"
            + " (a real arm could not return 1 for both empty and non-empty BINARY)",
        1, comparator.compare(session, date0, field(PropertyTypeInternal.BINARY, new byte[0])));
  }

  /**
   * DATE × LINK: unsupported. Two distinct RecordIds are pinned — a real arm could not return 1
   * for both #1:2 and #99:99 against DATE 0 while also keeping isEqual false on both, so the
   * multi-input pin disambiguates from a future real-arm return value of 1.
   */
  @Test
  public void dateIsEqualWithLinkIsUnsupported() {
    var date0 = field(PropertyTypeInternal.DATE, 0L);
    assertFalse(
        "DATE × LINK(#1:2) isEqual: unsupported pair → false",
        comparator.isEqual(session, date0, field(PropertyTypeInternal.LINK, new RecordId(1, 2))));
    assertFalse(
        "DATE × LINK(#99:99) isEqual: unsupported pair → false",
        comparator.isEqual(
            session, date0, field(PropertyTypeInternal.LINK, new RecordId(99, 99))));
    assertEquals(
        "DATE × LINK(#1:2) compare: unsupported pair → 1 sentinel",
        1,
        comparator.compare(session, date0, field(PropertyTypeInternal.LINK, new RecordId(1, 2))));
    assertEquals(
        "DATE × LINK(#99:99) compare: unsupported pair → 1 sentinel"
            + " (a real arm could not return 1 for two unrelated LINK values against the"
            + " same DATE)",
        1,
        comparator.compare(
            session, date0, field(PropertyTypeInternal.LINK, new RecordId(99, 99))));
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private BinaryField field(PropertyTypeInternal type, Object value) {
    return BinaryComparatorV0TestFixture.field(serializer, session, type, value);
  }
}
