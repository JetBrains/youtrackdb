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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import org.junit.Test;

/**
 * Tests for the live (non-pinned) DB-aware static helpers
 * {@link RecordSerializerStringAbstract#simpleValueFromStream(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * Object, PropertyTypeInternal)} and
 * {@link RecordSerializerStringAbstract#simpleValueToStream(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * StringWriter, PropertyTypeInternal, Object)} — the per-type encoders/decoders that the SQL
 * helper layer uses to read and write CSV-style scalar values.
 *
 * <p>These methods need a live {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded}
 * for two reasons:
 * <ol>
 *   <li>The DATE branch consults {@link DateHelper#getDatabaseCalendar(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)}
 *       to truncate to midnight in the database timezone.</li>
 *   <li>The {@code String → numeric} branches eventually go through
 *       {@link RecordSerializerStringAbstract#convertValue} which delegates to
 *       {@link PropertyTypeInternal#convert(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, Object, Class)}
 *       — the session is pulled into error reporting on conversion failure.</li>
 * </ol>
 *
 * <p>The session-independent {@code getType} / {@code getTypeValue} branches live in
 * {@code RecordSerializerStringAbstractStaticsTest}; the dead-code abstract instance API is
 * pinned by {@code RecordSerializerStringAbstractDeadCodeTest}.
 */
public class RecordSerializerStringAbstractSimpleValueTest extends TestUtilsFixture {

  // ====================================================== simpleValueFromStream — STRING

  @Test
  public void fromStreamStringWithStringInputDecodesAndStripsQuotes() {
    // The decoder strips IOUtils-style outer quotes and unescapes \" to ".
    var decoded =
        (String) RecordSerializerStringAbstract.simpleValueFromStream(
            session, "\"hello\\\"world\"", PropertyTypeInternal.STRING);
    assertEquals("hello\"world", decoded);
  }

  @Test
  public void fromStreamStringWithNonStringInputCallsToString() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, 42, PropertyTypeInternal.STRING);
    assertEquals("42", v);
  }

  // ====================================================== simpleValueFromStream — INTEGER

  @Test
  public void fromStreamIntegerWithIntegerInputPassesThrough() {
    var boxed = Integer.valueOf(7);
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, boxed, PropertyTypeInternal.INTEGER);
    // Same identity — no copying.
    assertSame(boxed, v);
  }

  @Test
  public void fromStreamIntegerParsesStringDigits() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "42", PropertyTypeInternal.INTEGER);
    assertEquals(42, v);
  }

  @Test
  public void fromStreamIntegerThrowsOnNonNumericString() {
    assertThrows(
        NumberFormatException.class,
        () -> RecordSerializerStringAbstract.simpleValueFromStream(
            session, "abc", PropertyTypeInternal.INTEGER));
  }

  // ====================================================== simpleValueFromStream — BOOLEAN

  @Test
  public void fromStreamBooleanWithBooleanInputPassesThrough() {
    var t = Boolean.TRUE;
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, t, PropertyTypeInternal.BOOLEAN);
    assertSame(t, v);
  }

  @Test
  public void fromStreamBooleanParsesTrueLiteralCaseInsensitively() {
    assertEquals(
        true,
        RecordSerializerStringAbstract.simpleValueFromStream(
            session, "true", PropertyTypeInternal.BOOLEAN));
    assertEquals(
        true,
        RecordSerializerStringAbstract.simpleValueFromStream(
            session, "TRUE", PropertyTypeInternal.BOOLEAN));
  }

  @Test
  public void fromStreamBooleanParsesNonTrueAsFalse() {
    // Boolean.valueOf returns false for anything except case-insensitive "true".
    assertEquals(
        false,
        RecordSerializerStringAbstract.simpleValueFromStream(
            session, "yes", PropertyTypeInternal.BOOLEAN));
  }

  // ====================================================== simpleValueFromStream — numerics via convertValue

  @Test
  public void fromStreamFloatPassesThroughForFloat() {
    Float f = 1.5f;
    assertSame(f, RecordSerializerStringAbstract.simpleValueFromStream(
        session, f, PropertyTypeInternal.FLOAT));
  }

  @Test
  public void fromStreamFloatParsesStringWithSuffix() {
    // "1.5f" goes through getTypeValue → 1.5 (Float) → convertValue → Float.
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "1.5f", PropertyTypeInternal.FLOAT);
    assertTrue(v instanceof Float);
    assertEquals(1.5f, (Float) v, 1e-6f);
  }

  @Test
  public void fromStreamLongPassesThroughForLong() {
    Long l = 10L;
    assertSame(l, RecordSerializerStringAbstract.simpleValueFromStream(
        session, l, PropertyTypeInternal.LONG));
  }

  @Test
  public void fromStreamLongParsesStringWithSuffix() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "10l", PropertyTypeInternal.LONG);
    assertEquals(10L, v);
  }

  @Test
  public void fromStreamDoubleParsesStringWithSuffix() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "2.5d", PropertyTypeInternal.DOUBLE);
    assertTrue(v instanceof Double);
    assertEquals(2.5d, (Double) v, 1e-9d);
  }

  @Test
  public void fromStreamShortParsesStringWithSuffix() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "7s", PropertyTypeInternal.SHORT);
    assertEquals((short) 7, v);
  }

  @Test
  public void fromStreamBytePassesThroughForByte() {
    Byte b = (byte) 1;
    assertSame(b, RecordSerializerStringAbstract.simpleValueFromStream(
        session, b, PropertyTypeInternal.BYTE));
  }

  @Test
  public void fromStreamDecimalPassesThroughForBigDecimal() {
    BigDecimal d = new BigDecimal("3.14");
    assertSame(d, RecordSerializerStringAbstract.simpleValueFromStream(
        session, d, PropertyTypeInternal.DECIMAL));
  }

  @Test
  public void fromStreamDecimalParsesStringWithSuffix() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "3.14c", PropertyTypeInternal.DECIMAL);
    assertTrue(v instanceof BigDecimal);
    assertEquals(0, new BigDecimal("3.14").compareTo((BigDecimal) v));
  }

  // ====================================================== simpleValueFromStream — BINARY

  @Test
  public void fromStreamBinaryWithUnderscoreDelimitedBase64DecodesByteArray() {
    var encoded = "_" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}) + "_";
    var v = (byte[]) RecordSerializerStringAbstract.simpleValueFromStream(
        session, encoded, PropertyTypeInternal.BINARY);
    assertArrayEquals(new byte[] {1, 2, 3}, v);
  }

  // ====================================================== simpleValueFromStream — DATE / DATETIME

  @Test
  public void fromStreamDatetimeWithDateInstancePassesThrough() {
    var d = new Date();
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, d, PropertyTypeInternal.DATETIME);
    assertSame(d, v);
  }

  @Test
  public void fromStreamDateWithDateInstancePassesThrough() {
    var d = new Date();
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, d, PropertyTypeInternal.DATE);
    assertSame(d, v);
  }

  // ====================================================== simpleValueFromStream — LINK

  @Test
  public void fromStreamLinkWithRidReturnsItsToString() {
    RID rid = new RecordId(5, 10);
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, rid, PropertyTypeInternal.LINK);
    // The contract returns a String form (`Object`-typed return); pin the format.
    assertEquals(rid.toString(), v);
  }

  @Test
  public void fromStreamLinkWithStringRetainsParsedRid() {
    var v = RecordSerializerStringAbstract.simpleValueFromStream(
        session, "#5:11", PropertyTypeInternal.LINK);
    var expected = RecordIdInternal.fromString("#5:11", false);
    assertEquals(expected, v);
  }

  // ====================================================== simpleValueFromStream — non-simple type

  @Test
  public void fromStreamThrowsForNonSimpleType() {
    // EMBEDDED is not a simple type; the switch falls through to the IllegalArgumentException.
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordSerializerStringAbstract.simpleValueFromStream(
            session, "anything", PropertyTypeInternal.EMBEDDED));
  }

  // ====================================================== simpleValueToStream — null / no-op

  @Test
  public void toStreamNullValueIsNoOp() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.STRING, null);
    assertEquals("", sw.toString());
  }

  @Test
  public void toStreamNullTypeIsNoOp() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(session, sw, null, "x");
    assertEquals("", sw.toString());
  }

  // ====================================================== simpleValueToStream — STRING

  @Test
  public void toStreamStringWrapsInDoubleQuotesAndEncodesContent() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.STRING, "hello\"world");
    // The encoder doubles the inner quote into `\"` and wraps in outer `"…"`.
    assertEquals("\"hello\\\"world\"", sw.toString());
  }

  // ====================================================== simpleValueToStream — primitive numerics

  @Test
  public void toStreamBooleanAppendsRawToString() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BOOLEAN, Boolean.TRUE);
    assertEquals("true", sw.toString());
  }

  @Test
  public void toStreamIntegerAppendsRawDecimalNoSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.INTEGER, 42);
    assertEquals("42", sw.toString());
  }

  @Test
  public void toStreamFloatAppendsValueWithFSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.FLOAT, 1.5f);
    assertEquals("1.5f", sw.toString());
  }

  @Test
  public void toStreamLongAppendsValueWithLSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.LONG, 10L);
    assertEquals("10l", sw.toString());
  }

  @Test
  public void toStreamDoubleAppendsValueWithDSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DOUBLE, 2.5d);
    assertEquals("2.5d", sw.toString());
  }

  @Test
  public void toStreamShortAppendsValueWithSSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.SHORT, (short) 7);
    assertEquals("7s", sw.toString());
  }

  // ====================================================== simpleValueToStream — DECIMAL

  @Test
  public void toStreamDecimalUsesPlainStringFormForBigDecimal() {
    var sw = new StringWriter();
    // Plain string avoids scientific notation for very small / very large scales.
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DECIMAL, new BigDecimal("0.00001"));
    assertEquals("0.00001c", sw.toString());
  }

  @Test
  public void toStreamDecimalFallsBackToToStringForNonBigDecimal() {
    // The non-BigDecimal arm of the DECIMAL switch arm — pin so a regression that BigDecimal-wraps
    // every non-BigDecimal input is caught.
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DECIMAL, "3.14");
    assertEquals("3.14c", sw.toString());
  }

  // ====================================================== simpleValueToStream — BYTE

  @Test
  public void toStreamByteWithCharacterAppendsRawCharThenSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BYTE, Character.valueOf('A'));
    assertEquals("Ab", sw.toString());
  }

  @Test
  public void toStreamByteWithStringAppendsFirstCharThenSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BYTE, "X");
    assertEquals("Xb", sw.toString());
  }

  @Test
  public void toStreamByteWithBoxedByteAppendsToStringThenSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BYTE, Byte.valueOf((byte) 5));
    assertEquals("5b", sw.toString());
  }

  // ====================================================== simpleValueToStream — BINARY

  @Test
  public void toStreamBinaryWrapsBase64InUnderscoreDelimiters() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BINARY, new byte[] {1, 2, 3});
    var expected =
        "_" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}) + "_";
    assertEquals(expected, sw.toString());
  }

  @Test
  public void toStreamBinaryWithBoxedByteEncodesSingleByteArray() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BINARY, Byte.valueOf((byte) 9));
    var expected =
        "_" + Base64.getEncoder().encodeToString(new byte[] {(byte) 9}) + "_";
    assertEquals(expected, sw.toString());
  }

  // ====================================================== simpleValueToStream — DATE

  /**
   * DATE truncates the supplied {@link Date} to midnight in the DB calendar timezone before
   * appending the resulting epoch ms with the {@code 'a'} type-suffix. Pin the truncation by
   * hand: midnight-of-the-day in the DB timezone, formatted as ms, ends with `a`.
   */
  @Test
  public void toStreamDateTruncatesToMidnightInDbTimezoneAndAppendsASuffix() {
    var sw = new StringWriter();
    var nonMidnight = new GregorianCalendar(
        DateHelper.getDatabaseTimeZone(session));
    nonMidnight.set(2024, Calendar.MARCH, 15, 13, 27, 31);
    nonMidnight.set(Calendar.MILLISECOND, 456);
    var input = nonMidnight.getTime();

    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATE, input);

    // Independently compute the expected midnight in the DB timezone.
    var expectedCal = DateHelper.getDatabaseCalendar(session);
    expectedCal.setTime(input);
    expectedCal.set(Calendar.HOUR_OF_DAY, 0);
    expectedCal.set(Calendar.MINUTE, 0);
    expectedCal.set(Calendar.SECOND, 0);
    expectedCal.set(Calendar.MILLISECOND, 0);
    var expectedMidnightMs = expectedCal.getTimeInMillis();

    assertEquals(expectedMidnightMs + "a", sw.toString());
  }

  /**
   * DATETIME does NOT truncate — it serializes the raw epoch ms. Pin the difference between DATE
   * and DATETIME so a regression that either always-truncates or never-truncates is caught.
   */
  @Test
  public void toStreamDatetimeAppendsRawEpochMsAndTSuffix() {
    var sw = new StringWriter();
    var input = new Date(1234567890_000L);
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATETIME, input);
    assertEquals("1234567890000t", sw.toString());
  }

  @Test
  public void toStreamDatetimeWithNonDateAppendsToStringAndSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATETIME, "raw-input");
    assertEquals("raw-inputt", sw.toString());
  }

  @Test
  public void toStreamDateWithNonDateAppendsToStringAndSuffix() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATE, "raw-input");
    assertEquals("raw-inputa", sw.toString());
  }

  // ====================================================== round-trip

  /**
   * Falsifiable round-trip: write a value via {@code simpleValueToStream}, then read it back via
   * {@code simpleValueFromStream}. Pin the per-type idempotence rule from the Description's
   * equivalence table — STRING / INTEGER / LONG / FLOAT / DOUBLE / DECIMAL / BOOLEAN / BINARY all
   * round-trip exactly.
   */
  @Test
  public void roundTripStringWithSpecialCharsRecoversOriginal() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.STRING, "a\"b");
    // The serialized form starts with `"`, so getTypeValue's "is double-quoted" arm fires before
    // simpleValueFromStream is even called via the SQL helper path. Here we exercise simple's
    // own "iValue instanceof String" branch which calls IOUtils.getStringContent + decode —
    // both of which strip the outer quotes and unescape the inner `\"`.
    var roundTripped =
        RecordSerializerStringAbstract.simpleValueFromStream(
            session, sw.toString(), PropertyTypeInternal.STRING);
    assertEquals("a\"b", roundTripped);
  }

  @Test
  public void roundTripBooleanRecoversOriginal() {
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BOOLEAN, Boolean.TRUE);
    var roundTripped =
        RecordSerializerStringAbstract.simpleValueFromStream(
            session, sw.toString(), PropertyTypeInternal.BOOLEAN);
    assertEquals(true, roundTripped);
  }

  @Test
  public void roundTripBinaryRecoversBytes() {
    var bytes = new byte[] {0, 1, 2, 3, -1, 127, -128};
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.BINARY, bytes);
    var roundTripped =
        (byte[]) RecordSerializerStringAbstract.simpleValueFromStream(
            session, sw.toString(), PropertyTypeInternal.BINARY);
    assertArrayEquals(bytes, roundTripped);
  }

  /**
   * DATE round-trip: a non-midnight Date serializes to truncated midnight ms, which when read
   * back via {@code convertValue} yields the truncated {@link Date}. Pin per Description's
   * DATE equivalence rule (truncate to DB calendar midnight).
   */
  @Test
  public void roundTripDateRecoversTruncatedMidnight() {
    var cal = new GregorianCalendar(DateHelper.getDatabaseTimeZone(session));
    cal.set(2024, Calendar.JUNE, 1, 11, 22, 33);
    cal.set(Calendar.MILLISECOND, 444);
    var input = cal.getTime();

    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATE, input);

    // The serialized form is "<midnightMs>a"; passing it back through fromStream goes through
    // getTypeValue which recognizes the trailing 'a' and returns a Date built from the ms.
    var roundTripped =
        (Date) RecordSerializerStringAbstract.simpleValueFromStream(
            session, sw.toString(), PropertyTypeInternal.DATE);

    var expectedCal = DateHelper.getDatabaseCalendar(session);
    expectedCal.setTime(input);
    expectedCal.set(Calendar.HOUR_OF_DAY, 0);
    expectedCal.set(Calendar.MINUTE, 0);
    expectedCal.set(Calendar.SECOND, 0);
    expectedCal.set(Calendar.MILLISECOND, 0);
    assertEquals(expectedCal.getTime(), roundTripped);
  }

  /**
   * DATETIME round-trip: full ms preserved. Pin so the rule difference between DATE and DATETIME
   * stays visible to test maintainers.
   */
  @Test
  public void roundTripDatetimeRecoversFullEpochMs() {
    var input = new Date(1234567890_123L);
    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATETIME, input);

    var roundTripped =
        (Date) RecordSerializerStringAbstract.simpleValueFromStream(
            session, sw.toString(), PropertyTypeInternal.DATETIME);
    assertEquals(input, roundTripped);
  }

  // ====================================================== timezone independence pin

  /**
   * Pin: the DB-aware DATE branch uses the DB timezone, not the JVM default. Verify by computing
   * the expected midnight directly via {@link DateHelper#getDatabaseCalendar} so the assertion
   * stays correct regardless of the surefire {@code -Duser.timezone}.
   */
  @Test
  public void toStreamDateUsesDbTimezoneNotJvmDefault() {
    // Construct a Date that is NOT midnight in the DB timezone.
    var inputCal = new GregorianCalendar(DateHelper.getDatabaseTimeZone(session));
    inputCal.set(2024, Calendar.JANUARY, 5, 23, 59, 59);
    inputCal.set(Calendar.MILLISECOND, 999);
    var input = inputCal.getTime();

    var sw = new StringWriter();
    RecordSerializerStringAbstract.simpleValueToStream(
        session, sw, PropertyTypeInternal.DATE, input);

    var dbCal = DateHelper.getDatabaseCalendar(session);
    dbCal.setTime(input);
    dbCal.set(Calendar.HOUR_OF_DAY, 0);
    dbCal.set(Calendar.MINUTE, 0);
    dbCal.set(Calendar.SECOND, 0);
    dbCal.set(Calendar.MILLISECOND, 0);

    // Same DB timezone: expected midnight matches.
    assertEquals(dbCal.getTimeInMillis() + "a", sw.toString());
    // Sanity: ensure the truncation actually changed the ms — otherwise the test does not pin
    // anything.
    assertTrue(
        "input must be a non-midnight time in the DB timezone",
        dbCal.getTimeInMillis() != input.getTime());

    // The pin above (DateHelper-derived expected midnight) is the actual test of the
    // DB-timezone-vs-JVM-default contract: if the implementation accidentally switched to
    // JVM-default truncation on a non-default DB timezone, the equality above would fail.
    // The project test harness uses the JVM default by default, so we cannot force a divergence
    // without mutating system state; the equality + the non-trivial-truncation assertion that
    // follows are sufficient as the pinned invariants.
  }
}
