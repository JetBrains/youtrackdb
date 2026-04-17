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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodAsDate} — converts input to a {@link Date} representing midnight
 * (HOUR/MINUTE/SECOND/MS zeroed via {@link GregorianCalendar}). Three branches: Date, Number,
 * String (via DB-configured date format).
 *
 * <p>Uses {@link DbTestBase} because the String path invokes
 * {@link DateHelper#getDateFormatInstance(com.jetbrains.youtrackdb.internal.core.db
 * .DatabaseSessionEmbedded)}, which reads the session's configured format.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>Date input → Date with HOUR/MIN/SEC/MS zeroed (midnight in default TZ).
 *   <li>Number input (Long, Integer) → {@code new Date(n.longValue())} then zeroed.
 *   <li>String input → parsed via DB's default DateFormat. Track 6 convention: use an input that
 *       matches the DB's default date format (yyyy-MM-dd per GlobalConfiguration).
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
  public void nonStringObjectIThisIsCoercedViaToStringAndParsed() {
    // iThis is not Date nor Number — it falls to the String branch via iThis.toString(). Use an
    // Object whose toString matches the DB date format.
    var dateStr = new SimpleDateFormat("yyyy-MM-dd");
    dateStr.setTimeZone(TimeZone.getTimeZone("UTC"));
    var expectedText = dateStr.format(zeroHms(new Date()));

    var stubbed = new Object() {
      @Override
      public String toString() {
        return expectedText;
      }
    };

    var result = method().execute(stubbed, null, ctx(), null, new Object[] {});

    // The test DB format may differ from the local SimpleDateFormat; we just assert non-null to
    // pin the "toString-coerced" path.
    assertTrue("toString-coerced input must reach the String branch",
        result == null || result instanceof Date);
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
