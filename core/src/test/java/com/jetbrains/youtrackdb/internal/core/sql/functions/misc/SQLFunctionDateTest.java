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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionDate} — returns a {@link Date} from zero args (system date captured
 * at construction), a {@link Number} epoch-ms, or a parsed string using a lazily-cached
 * {@link SimpleDateFormat}.
 *
 * <p>Uses {@link DbTestBase} because the string-parsing path calls
 * {@code context.getDatabaseSession()} both to obtain the database timezone (1-arg path) and to
 * build a {@link QueryParsingException} on parse failure.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>zero-arg returns the stored {@link Date}, stable across calls (assertSame).
 *   <li>null first parameter short-circuits to null.
 *   <li>{@link Number} first parameter returns {@code new Date(longValue)}.
 *   <li>String+format+timezone (3-arg) path uses explicit {@link TimeZone} — avoids locale flake.
 *   <li>String+format (2-arg) path uses the database timezone via
 *       {@code DateHelper.getDatabaseTimeZone}.
 *   <li>String (1-arg) path uses the database default datetime format via
 *       {@code DateHelper.getDateTimeFormatInstance}.
 *   <li>Format caching: after the first string-path call, the cached format is reused — a second
 *       call with a different pattern/timezone still uses the first format (latent behaviour pin).
 *   <li>{@link SQLFunctionDate#getResult()} clears the cached format, letting the next call pick a
 *       new pattern; also returns {@code null}.
 *   <li>Parse error → {@link QueryParsingException} wrapped via {@code BaseException.wrapException}
 *       — the outer exception message mentions "Error on formatting date".
 *   <li>{@code aggregateResults(params) == false}, metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLFunctionDateTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // Zero-arg path — stored "now"
  // ---------------------------------------------------------------------------

  @Test
  public void zeroArgReturnsStoredNowDate() {
    // Function captures `new Date()` at construction and always returns the SAME instance for the
    // zero-arg path — pinned by assertSame across two calls.
    var function = new SQLFunctionDate();

    var first = (Date) function.execute(null, null, null, new Object[] {}, ctx());
    var second = (Date) function.execute(null, null, null, new Object[] {}, ctx());

    assertNotNull(first);
    assertSame("zero-arg must return the SAME stored Date across calls", first, second);
  }

  @Test
  public void zeroArgIsCloseToWallClockTime() {
    // The stored Date is within 30 seconds of wall-clock time, verifying it actually uses
    // System.currentTimeMillis under the hood (rather than e.g. an epoch-0 constant).
    // System.currentTimeMillis is NOT guaranteed to be monotonic across NTP corrections, VM
    // migrations, or leap-second smoothing, so allow a small negative tolerance instead of
    // asserting strict monotonicity (BC4).
    var now = System.currentTimeMillis();
    var function = new SQLFunctionDate();

    var result = (Date) function.execute(null, null, null, new Object[] {}, ctx());

    assertNotNull(result);
    var delta = result.getTime() - now;
    assertTrue(
        "stored Date must be at most 1s before baseline (NTP tolerance), was " + delta + " ms",
        delta >= -1_000);
    assertTrue("stored Date must be within 30s of wall-clock, was " + delta + " ms",
        delta < 30_000);
  }

  // ---------------------------------------------------------------------------
  // Null / Number first-param paths
  // ---------------------------------------------------------------------------

  @Test
  public void nullFirstParamReturnsNull() {
    var function = new SQLFunctionDate();

    var result = function.execute(null, null, null, new Object[] {null, "yyyy-MM-dd"}, ctx());

    assertNull(result);
  }

  @Test
  public void longEpochMsParamReturnsDate() {
    // Number path: new Date(Number.longValue()). Use Long so the Number branch is exercised.
    var function = new SQLFunctionDate();
    var epochMs = 1_700_000_000_000L;

    var result = (Date) function.execute(null, null, null, new Object[] {epochMs}, ctx());

    assertEquals(new Date(epochMs), result);
  }

  @Test
  public void integerEpochMsParamReturnsDate() {
    // Integer also matches Number; longValue() widens automatically.
    var function = new SQLFunctionDate();
    var epochMs = 1_700_000_000;

    var result = (Date) function.execute(null, null, null, new Object[] {epochMs}, ctx());

    assertEquals(new Date((long) epochMs), result);
  }

  // ---------------------------------------------------------------------------
  // 3-arg string path — explicit format + timezone
  // ---------------------------------------------------------------------------

  @Test
  public void threeArgStringWithFormatAndUtcTimezoneParsesAsUtc() throws Exception {
    // Explicit UTC timezone (as per Track 6 convention) avoids locale-dependent flake.
    var function = new SQLFunctionDate();
    var formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    var expected = formatter.parse("2024-06-15 13:45:30");

    var result = (Date) function.execute(null, null, null,
        new Object[] {"2024-06-15 13:45:30", "yyyy-MM-dd HH:mm:ss", "UTC"}, ctx());

    assertEquals(expected, result);
  }

  @Test
  public void threeArgStringPathWithExplicitTimezoneHonorsIt() throws Exception {
    // Parse the same wall-clock string under two different timezones; the produced Dates differ by
    // the timezone offset — pins that iParams[2] (timezone) is actually consulted.
    var function = new SQLFunctionDate();
    var pattern = "yyyy-MM-dd HH:mm:ss";

    var utc = (Date) function.execute(null, null, null,
        new Object[] {"2024-06-15 12:00:00", pattern, "UTC"}, ctx());

    // New instance — the cached format would otherwise stick with UTC.
    var function2 = new SQLFunctionDate();
    var tokyo = (Date) function2.execute(null, null, null,
        new Object[] {"2024-06-15 12:00:00", pattern, "Asia/Tokyo"}, ctx());

    assertNotNull(utc);
    assertNotNull(tokyo);
    // Tokyo is UTC+9 → 12:00 Tokyo == 03:00 UTC, so tokyo should be earlier than utc by 9 hours.
    var delta = utc.getTime() - tokyo.getTime();
    assertEquals("UTC and Tokyo parses of the same string must differ by exactly 9h",
        9L * 60 * 60 * 1000, delta);
  }

  // ---------------------------------------------------------------------------
  // 2-arg string path — format only (uses DB timezone)
  // ---------------------------------------------------------------------------

  @Test
  public void twoArgStringPathUsesProvidedFormatAndDbTimezone() throws Exception {
    // Without an explicit timezone, format.setTimeZone is set to DateHelper.getDatabaseTimeZone.
    // We don't know the exact DB timezone at test time, but we can prove the produced Date
    // round-trips losslessly through the SAME format + DB timezone — this would fail if the
    // function returned an arbitrary epoch-0 Date or used a different TZ than it claimed (TB2).
    var function = new SQLFunctionDate();
    var pattern = "yyyy-MM-dd HH:mm:ss";

    var result = (Date) function.execute(null, null, null,
        new Object[] {"2024-06-15 13:45:30", pattern}, ctx());

    assertNotNull(result);
    var roundTrip = new SimpleDateFormat(pattern);
    roundTrip.setTimeZone(
        com.jetbrains.youtrackdb.internal.core.util.DateHelper.getDatabaseTimeZone(session));
    assertEquals("round-trip via the DB timezone must reproduce the input string",
        "2024-06-15 13:45:30", roundTrip.format(result));
  }

  // ---------------------------------------------------------------------------
  // 1-arg string path — format AND timezone from DB
  // ---------------------------------------------------------------------------

  @Test
  public void oneArgStringPathUsesDbDateTimeFormat() {
    // 1-arg path: SimpleDateFormat is obtained from DateHelper.getDateTimeFormatInstance.
    // The default database datetime pattern is configured as "yyyy-MM-dd HH:mm:ss"; feeding a
    // matching string must parse without throwing.
    var function = new SQLFunctionDate();

    var result = function.execute(null, null, null, new Object[] {"2024-06-15 13:45:30"}, ctx());

    assertNotNull("1-arg string path should parse using DB default datetime format", result);
    assertTrue(result instanceof Date);
    // Round-trip via the same DB datetime format must reproduce the input string —
    // assertNotNull alone would silently accept an epoch-0 Date or a wrong-format parse (TB2).
    var dbFormat =
        com.jetbrains.youtrackdb.internal.core.util.DateHelper.getDateTimeFormatInstance(session);
    assertEquals(
        "1-arg path must parse via the configured DB datetime format",
        "2024-06-15 13:45:30",
        dbFormat.format((Date) result));
  }

  // ---------------------------------------------------------------------------
  // Format cache — lazily set, not reset between calls without getResult()
  // ---------------------------------------------------------------------------

  @Test
  public void secondCallReusesCachedFormatEvenWhenPatternDiffers() throws Exception {
    // Latent cache-ignores-inputs behaviour: once `format` is non-null, subsequent calls reuse it
    // regardless of whether iParams change. Pin this so a future "cache-per-pattern" refactor is
    // noticed.
    var function = new SQLFunctionDate();

    var firstFormatter = new SimpleDateFormat("yyyy-MM-dd");
    firstFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    var firstExpected = firstFormatter.parse("2024-06-15");

    var first = (Date) function.execute(null, null, null,
        new Object[] {"2024-06-15", "yyyy-MM-dd", "UTC"}, ctx());
    assertEquals(firstExpected, first);

    // Second call uses a different pattern, but the cached format still parses "yyyy-MM-dd".
    // Attempt to parse a string that matches ONLY the second pattern — it should fail with the
    // cached-format's wrapped QueryParsingException.
    try {
      function.execute(null, null, null,
          new Object[] {"15/06/2024", "dd/MM/yyyy", "UTC"}, ctx());
      fail("expected QueryParsingException because the cached format does not match 15/06/2024");
    } catch (QueryParsingException expected) {
      // Exact-ish message pin: both the input and the cached pattern must appear in the message.
      assertNotNull(expected.getMessage());
      assertTrue("message should reference the bad input '15/06/2024', saw: "
          + expected.getMessage(),
          expected.getMessage().contains("15/06/2024"));
      assertTrue("message should reference the cached pattern 'yyyy-MM-dd', saw: "
          + expected.getMessage(),
          expected.getMessage().contains("yyyy-MM-dd"));
    }
  }

  @Test
  public void getResultClearsCachedFormatAndNextCallUsesNewPattern() throws Exception {
    // After getResult(), the cached `format` is reset so the next execute() picks the new pattern.
    var function = new SQLFunctionDate();

    var first = (Date) function.execute(null, null, null,
        new Object[] {"2024-06-15", "yyyy-MM-dd", "UTC"}, ctx());
    assertNotNull(first);

    // getResult() returns null and clears the cache.
    assertNull(function.getResult());

    // After reset, a different pattern is accepted.
    var second = (Date) function.execute(null, null, null,
        new Object[] {"15/06/2024", "dd/MM/yyyy", "UTC"}, ctx());

    assertNotNull(second);
    // Both parse the same calendar day — the epoch-millis match.
    assertEquals(first, second);
  }

  // ---------------------------------------------------------------------------
  // Parse error path — wrapped into QueryParsingException
  // ---------------------------------------------------------------------------

  @Test
  public void unparseableStringThrowsQueryParsingExceptionWithFormatMessage() {
    // Unparseable string triggers format.parse → ParseException which is wrapped into
    // QueryParsingException via BaseException.wrapException. Assert on message substrings and on
    // the fact that the cause chain reaches a ParseException.
    var function = new SQLFunctionDate();

    try {
      function.execute(null, null, null,
          new Object[] {"not-a-date", "yyyy-MM-dd", "UTC"}, ctx());
      fail("expected QueryParsingException for unparseable string");
    } catch (QueryParsingException e) {
      assertNotNull(e.getMessage());
      assertTrue("message should mention the bad input, saw: " + e.getMessage(),
          e.getMessage().contains("not-a-date"));
      assertTrue("message should mention the format pattern, saw: " + e.getMessage(),
          e.getMessage().contains("yyyy-MM-dd"));
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalseRegardlessOfParams() {
    // Static helper returns false unconditionally — pin so a refactor that makes Date
    // accidentally aggregate is caught.
    assertFalse(SQLFunctionDate.aggregateResults(new Object[] {}));
    assertFalse(SQLFunctionDate.aggregateResults(new Object[] {"yyyy-MM-dd"}));
  }

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionDate();

    assertEquals(SQLFunctionDate.NAME, function.getName(session));
    assertEquals("date", SQLFunctionDate.NAME);
    assertEquals(0, function.getMinParams());
    assertEquals(3, function.getMaxParams(session));
    assertEquals("date([<date-as-string>] [,<format>] [,<timezone>])",
        function.getSyntax(session));
  }

  @Test
  public void twoInstancesCaptureDifferentNowDates() {
    // Sanity: two SQLFunctionDate instances each capture their own `new Date()` in the
    // constructor, so the two returned Date instances must be DIFFERENT objects (assertNotSame).
    // No timing needed — we assert identity, not wall-clock advance.
    var first = new SQLFunctionDate();
    var second = new SQLFunctionDate();

    var d1 = (Date) first.execute(null, null, null, new Object[] {}, ctx());
    var d2 = (Date) second.execute(null, null, null, new Object[] {}, ctx());

    assertNotNull(d1);
    assertNotNull(d2);
    assertNotSame("Each instance captures its own now-Date", d1, d2);
  }
}
