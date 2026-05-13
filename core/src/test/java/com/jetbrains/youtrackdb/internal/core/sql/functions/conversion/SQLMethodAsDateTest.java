/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.conversion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodAsDate} — converts input to a {@link Date} with the AM-PM hour,
 * minute, second, and millisecond zeroed via {@link GregorianCalendar}. Three branches: Date,
 * Number, String (via DB-configured date format).
 *
 * <p>Uses {@link DbTestBase} because the String path invokes
 * {@link DateHelper#getDateFormatInstance(com.jetbrains.youtrackdb.internal.core.db
 * .DatabaseSessionEmbedded)}, which reads the session's configured format.
 *
 * <p><strong>Latent behaviour:</strong> production uses {@link Calendar#HOUR} (AM/PM,
 * 0–11), not {@link Calendar#HOUR_OF_DAY} (0–23). For PM inputs, the resulting Date is noon
 * (12:00) rather than midnight (00:00). This is pinned by
 * {@link #numericInputInPmWindowLandsOnNoonNotMidnightPerCalendarHourBug()} with a WHEN-FIXED
 * marker — a future fix that switches to {@code HOUR_OF_DAY} will flip the assertion.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>Date input → Date with AM/PM HOUR, MIN, SEC, MS all zeroed.
 *   <li>Number input (Long, Integer) → {@code new Date(n.longValue())} then zeroed.
 *   <li>String input → parsed via DB's default DateFormat. Track 6 convention: round-trip via
 *       {@code DateHelper.getDateFormatInstance(session)} so the input/output encoding matches.
 *   <li>Unparseable string → null (ParseException is swallowed and logged).
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLMethodAsDateTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var c = new BasicCommandContext();
    c.setDatabaseSession(session);
    return c;
  }

  private SQLMethodAsDate method() {
    return new SQLMethodAsDate();
  }

  private static Date zeroHms(Date d) {
    Calendar cal = new GregorianCalendar();
    cal.setTime(d);
    cal.set(Calendar.HOUR, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTime();
  }

  // ---------------------------------------------------------------------------
  // Null
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    assertNull(method().execute(null, null, ctx(), null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // Date branch
  // ---------------------------------------------------------------------------

  @Test
  public void dateInputReturnsSameDateWithHmsZeroed() {
    // Use a Date far from midnight to make the HOUR/MIN/SEC/MS zeroing observable.
    var input = new Date(1_700_050_350_777L); // arbitrary non-midnight instant
    var expected = zeroHms(input);

    var result = (Date) method().execute(input, null, ctx(), null, new Object[] {});

    assertEquals(expected, result);
  }

  @Test
  public void dateInputAlreadyAtMidnightIsReturnedEqual() {
    // A Date already at midnight (in default TZ) should equal itself after zeroing — pin that
    // the branch is idempotent.
    var cal = new GregorianCalendar();
    cal.set(2024, Calendar.JUNE, 15, 0, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    var midnight = cal.getTime();

    var result = (Date) method().execute(midnight, null, ctx(), null, new Object[] {});

    assertEquals(midnight, result);
  }

  // ---------------------------------------------------------------------------
  // Number branch
  // ---------------------------------------------------------------------------

  @Test
  public void longInputReturnsZeroedDateFromEpochMs() {
    var epochMs = 1_700_050_350_777L;
    var expected = zeroHms(new Date(epochMs));

    var result = (Date) method().execute(Long.valueOf(epochMs), null, ctx(), null, new Object[] {});

    assertEquals(expected, result);
  }

  @Test
  public void integerInputReturnsZeroedDateFromEpochMs() {
    // Integer also satisfies `iThis instanceof Number`.
    var epochMs = 1_700_000_000;
    var expected = zeroHms(new Date(epochMs));

    var result = (Date) method().execute(Integer.valueOf(epochMs), null, ctx(), null,
        new Object[] {});

    assertEquals(expected, result);
  }

  // ---------------------------------------------------------------------------
  // String branch
  // ---------------------------------------------------------------------------

  @Test
  public void stringInputUsesDbDateFormatToParse() throws Exception {
    // Retrieve the DB's default DateFormat (clone) and use it to build our input — guarantees
    // round-trip without depending on locale-specific defaults. Track 6 convention: when a test
    // exercises date parsing, parse+format via the same SimpleDateFormat.
    var format = DateHelper.getDateFormatInstance(session);
    var expectedDate = zeroHms(new Date(1_700_000_000_000L));
    var inputText = format.format(expectedDate);

    var result = (Date) method().execute(inputText, null, ctx(), null, new Object[] {});

    assertNotNull("parsed String should yield a Date", result);
    // Compare via the same format (DB format discards the time-of-day portion per default).
    assertEquals(format.format(expectedDate), format.format(result));
  }

  @Test
  public void unparseableStringReturnsNullViaSwallowedParseException() {
    // format.parse("not-a-date") throws ParseException; the method logs and returns null.
    var result = method().execute("not-a-date", null, ctx(), null, new Object[] {});

    assertNull(result);
  }

  @Test
  public void nonStringObjectIThisIsCoercedViaToStringAndParsed() throws Exception {
    // iThis is not Date nor Number → the String branch runs via iThis.toString(). To make the
    // assertion falsifiable, build the stubbed text with the DB's OWN DateFormat and compare the
    // returned Date against the same format (locale/timezone-independent round-trip).
    var format = DateHelper.getDateFormatInstance(session);
    var expectedDate = zeroHms(new Date(1_700_000_000_000L));
    var expectedText = format.format(expectedDate);
    var stubbed = new Object() {
      @Override
      public String toString() {
        return expectedText;
      }
    };

    var result = (Date) method().execute(stubbed, null, ctx(), null, new Object[] {});

    assertNotNull("toString-coerced input must reach the String branch", result);
    assertEquals("round-trip via the DB format must be lossless",
        expectedText, format.format(result));
  }

  @Test
  public void numericInputInPmWindowLandsOnNoonNotMidnightPerCalendarHourBug() {
    // WHEN-FIXED: production calls Calendar.set(Calendar.HOUR, 0) — AM/PM hour, 0-11 — instead
    // of Calendar.HOUR_OF_DAY, 0-23. For any Date whose local time is after noon, the AM_PM
    // field stays PM, so the resulting Calendar reads 12:00 PM (noon) rather than 00:00. A fix
    // that switches to HOUR_OF_DAY will flip this PM-window assertion visibly.
    //
    // Pick an epoch-ms whose UTC wall-clock is firmly in the PM window (13:45:30 UTC on
    // 2023-11-15). The bug is observable only when the JVM default TZ also reads PM at this
    // instant — true for UTC and every western timezone. On TZs east of UTC+10.5 (e.g.
    // Pacific/Apia) the local wall clock falls into AM and the pin cannot fire; in that case
    // SKIP the test (Assume.assumeTrue) rather than silently passing, so the WHEN-FIXED contract
    // is never falsely "green" on incompatible runners.
    //
    // The assumption MUST gate on the INPUT instant, not on the production-mutated result:
    // once the bug is fixed, production rewrites HOUR_OF_DAY=0 so the result's AM_PM reads AM
    // on every runner. Gating on the result's AM_PM would make the test SKIP (not FAIL) once
    // the bug is fixed — defeating the WHEN-FIXED contract.
    var pm = 1_700_056_730_000L;

    var inputCal = new GregorianCalendar();
    inputCal.setTimeInMillis(pm);
    assumeTrue(
        "TZ-dependent: this bug pin only fires when default TZ reads PM at 2023-11-15 13:45 UTC",
        inputCal.get(Calendar.AM_PM) == Calendar.PM);

    var result = (Date) method().execute(Long.valueOf(pm), null, ctx(), null, new Object[] {});

    var cal = new GregorianCalendar();
    cal.setTime(result);
    assertEquals(
        "WHEN-FIXED: production uses HOUR (AM/PM) → PM input lands on 12:00, not 00:00",
        12,
        cal.get(Calendar.HOUR_OF_DAY));
    // Minute/second/ms should always be zero regardless of the HOUR vs HOUR_OF_DAY issue.
    assertEquals(0, cal.get(Calendar.MINUTE));
    assertEquals(0, cal.get(Calendar.SECOND));
    assertEquals(0, cal.get(Calendar.MILLISECOND));
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("asdate", SQLMethodAsDate.NAME);
    assertEquals("asdate", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(0, m.getMaxParams(null));
    assertEquals("asDate()", m.getSyntax());
  }
}
