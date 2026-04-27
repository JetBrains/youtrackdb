/*
 *
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
package com.jetbrains.youtrackdb.internal.core.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Standalone tests for the live surface of {@link CronExpression}: parsing of valid expressions,
 * rejection of malformed expressions, and firing-time computation via {@code
 * getNextValidTimeAfter}.
 *
 * <p>All firing-time tests pin the cron expression to UTC by calling {@link
 * CronExpression#setTimeZone(TimeZone)} before computing — {@code getNextValidTimeAfter} otherwise
 * lazily falls back to {@link TimeZone#getDefault()} (see {@link CronExpression#getTimeZone()}),
 * which would make the assertions environment-dependent.
 *
 * <p>No database session is required: this class is a pure parser/evaluator over a {@link String}
 * input. The tests therefore live outside {@code DbTestBase} and outside the {@code
 * @Category(SequentialTest.class)} group.
 */
public class CronExpressionTest {

  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  private static Date utc(int year, int month, int day, int hour, int minute, int second) {
    var cal = new GregorianCalendar(UTC);
    cal.clear();
    cal.set(year, month - 1, day, hour, minute, second);
    return cal.getTime();
  }

  private static CronExpression cron(String expr) throws ParseException {
    var c = new CronExpression(expr);
    c.setTimeZone(UTC);
    return c;
  }

  // ---------------------------------------------------------------------------
  // Constructor — accepts well-formed expressions across every special token
  // ---------------------------------------------------------------------------

  @Test
  public void constructorParsesEveryMinuteOnSecondZero() throws ParseException {
    // "0 * * * * ?" — fire every minute on the 0th second.
    new CronExpression("0 * * * * ?");
  }

  @Test
  public void constructorParsesCommaList() throws ParseException {
    new CronExpression("0 0,15,30,45 * * * ?");
  }

  @Test
  public void constructorParsesSecondRange() throws ParseException {
    new CronExpression("0-30 * * * * ?");
  }

  @Test
  public void constructorParsesStepValuesInSeconds() throws ParseException {
    new CronExpression("0/15 * * * * ?");
  }

  @Test
  public void constructorParsesWildcardWithSlash() throws ParseException {
    new CronExpression("*/10 * * * * ?");
  }

  @Test
  public void constructorParsesAllWildcards() throws ParseException {
    new CronExpression("* * * * * ?");
  }

  @Test
  public void namedDayOfWeekTokenBindsToCorrectWeekday() throws ParseException {
    // "MON" must bind to Monday — pin the binding by firing, not just by parsing.
    // 2025-01-05 is a Sunday; the next Monday-noon match is 2025-01-06 12:00 UTC.
    var c = cron("0 0 12 ? * MON");
    assertEquals(utc(2025, 1, 6, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 5, 0, 0, 0)));
  }

  @Test
  public void namedDayOfWeekRangeTokenBindsAcrossWeekdays() throws ParseException {
    // "MON-FRI" must include Monday and exclude Saturday/Sunday.
    // 2025-01-04 is a Saturday → next match is Mon 2025-01-06 12:00 UTC.
    var c = cron("0 0 12 ? * MON-FRI");
    assertEquals(utc(2025, 1, 6, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 4, 12, 0, 0)));
  }

  @Test
  public void namedMonthTokenBindsToCorrectMonth() throws ParseException {
    // "JAN" must bind to month 1 — from mid-2025 → next match is Jan 1 2026 noon UTC.
    var c = cron("0 0 12 1 JAN ?");
    assertEquals(utc(2026, 1, 1, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 7, 15, 0, 0, 0)));
  }

  @Test
  public void namedMonthRangeTokenSpansAllMonths() throws ParseException {
    // "JAN-DEC" must include every month — fire on day 1 of every month.
    // From 2025-01-15 → next match is 2025-02-01.
    var c = cron("0 0 12 1 JAN-DEC ?");
    assertEquals(utc(2025, 2, 1, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 15, 0, 0, 0)));
  }

  @Test
  public void constructorParsesLastDayOfMonth() throws ParseException {
    new CronExpression("0 0 12 L * ?");
  }

  @Test
  public void constructorParsesLastDayOfMonthOffset() throws ParseException {
    new CronExpression("0 0 12 L-3 * ?");
  }

  @Test
  public void lastWeekdayOfMonthTokenFiresOnLastWeekday() throws ParseException {
    // "LW" must fire on the last weekday of each month. May 31 2025 is a Saturday →
    // last weekday is Fri May 30. Pin both the parse and the fire.
    var c = cron("0 0 12 LW * ?");
    assertEquals(utc(2025, 5, 30, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 5, 1, 0, 0, 0)));
  }

  @Test
  public void nearestWeekdayTokenFiresOnNearestWeekdayWhenTargetIsSaturday()
      throws ParseException {
    // "15W" — March 15 2025 is a Saturday → nearest weekday is Fri Mar 14.
    var c = cron("0 0 12 15W * ?");
    assertEquals(utc(2025, 3, 14, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 3, 1, 0, 0, 0)));
  }

  @Test
  public void nthDayOfWeekTokenFiresOnNthOccurrence() throws ParseException {
    // "6#3" — 3rd Friday of the month. In Jan 2025 the third Friday is Jan 17.
    var c = cron("0 0 12 ? * 6#3");
    assertEquals(utc(2025, 1, 17, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0)));
  }

  @Test
  public void lastDayOfWeekTokenFiresOnLastOccurrence() throws ParseException {
    // "6L" — last Friday of the month. In Jan 2025 the last Friday is Jan 31.
    var c = cron("0 0 12 ? * 6L");
    assertEquals(utc(2025, 1, 31, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0)));
  }

  @Test
  public void yearFieldRestrictsFiresToConfiguredYear() throws ParseException {
    // The year field must restrict fires. "0 0 12 1 1 ? 2030" must fire on 2030-01-01
    // and never before; from 2025 the next match is exactly that instant.
    var c = cron("0 0 12 1 1 ? 2030");
    assertEquals(utc(2030, 1, 1, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0)));
  }

  @Test
  public void constructorParsesYearRange() throws ParseException {
    new CronExpression("0 0 12 1 1 ? 2030-2032");
  }

  @Test
  public void constructorParsesYearWildcard() throws ParseException {
    // No year field at all — the missing 7th token is treated as "*" by buildExpression.
    new CronExpression("0 0 12 1 1 ?");
  }

  @Test
  public void constructorUpperCasesInputForBothAccessors() throws ParseException {
    // The constructor stores cronExpression.toUpperCase(Locale.US); both accessors
    // (getCronExpression and toString) must echo the upper-cased form, and they
    // must agree (toString delegates to the same field).
    var c = new CronExpression("0 0 12 ? * mon-fri");
    assertEquals("0 0 12 ? * MON-FRI", c.getCronExpression());
    assertEquals(c.getCronExpression(), c.toString());
  }

  // ---------------------------------------------------------------------------
  // Constructor — rejects malformed inputs with ParseException / IAE
  // ---------------------------------------------------------------------------

  @Test
  public void constructorRejectsNullInputWithIAE() {
    // Documented contract: null is rejected eagerly with IllegalArgumentException
    // (not ParseException), to distinguish "no expression supplied" from "malformed".
    assertThrows(IllegalArgumentException.class, () -> new CronExpression((String) null));
  }

  @Test
  public void constructorRejectsEmptyString() {
    // Empty input yields zero tokens; buildExpression's "exprOn <= DAY_OF_WEEK"
    // post-loop guard fires and throws "Unexpected end of expression."
    assertThrows(ParseException.class, () -> new CronExpression(""));
  }

  @Test
  public void constructorRejectsWhitespaceOnly() {
    // Whitespace-only input also yields zero tokens — same rejection path.
    assertThrows(ParseException.class, () -> new CronExpression("   \t  "));
  }

  @Test
  public void constructorRejectsTooFewFields() {
    // Only 4 fields supplied — needs at least sec/min/hr/dom/mon/dow.
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 1"));
  }

  @Test
  public void constructorRejectsUnknownLetterInSeconds() {
    assertThrows(ParseException.class, () -> new CronExpression("X * * * * ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeSecond() {
    // Seconds field accepts 0–59 only.
    assertThrows(ParseException.class, () -> new CronExpression("0-60 * * * * ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeMinute() {
    assertThrows(ParseException.class, () -> new CronExpression("* 0-60 * * * ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeMinuteWithMatchingMessage() {
    // The rejection message identifies which field failed, distinguishing the
    // "Minute and Second values must be between 0 and 59" branch from "Hour values
    // must be between 0 and 23" — pin the message so a refactor that conflates two
    // branches under a single message is detected.
    var ex =
        assertThrows(ParseException.class, () -> new CronExpression("* 0-60 * * * ?"));
    assertNotNull("ParseException must carry a non-null message", ex.getMessage());
    assertEquals(
        "Minute and Second values must be between 0 and 59", ex.getMessage());
  }

  @Test
  public void constructorRejectsOutOfRangeHour() {
    assertThrows(ParseException.class, () -> new CronExpression("* * 0-24 * * ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeDayOfMonth() {
    assertThrows(ParseException.class, () -> new CronExpression("* * * 0-32 * ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeMonth() {
    assertThrows(ParseException.class, () -> new CronExpression("* * * * 0-13 ?"));
  }

  @Test
  public void constructorRejectsOutOfRangeDayOfWeek() {
    assertThrows(ParseException.class, () -> new CronExpression("* * * ? * 0-8"));
  }

  @Test
  public void constructorRejectsBothDayOfMonthAndDayOfWeekSpec() {
    // Without "?" in either field, both day-of-month and day-of-week become
    // restrictive — the implementation explicitly rejects this combination
    // because it is not yet supported (see FUTURE_TODO note in NOTES section).
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 15 * MON"));
  }

  @Test
  public void constructorRejectsLastDayOfMonthCombinedWithList() {
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 L,5 * ?"));
  }

  @Test
  public void constructorRejectsLastDayOfWeekCombinedWithList() {
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 ? * 5L,3"));
  }

  @Test
  public void constructorRejectsMultipleHashSpecifiers() {
    // "#" may appear at most once in the day-of-week field.
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 ? * 6#3#4"));
  }

  @Test
  public void constructorRejectsHashOutOfRange() {
    // The argument after "#" must be 1..5.
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 ? * 6#0"));
  }

  @Test
  public void constructorRejectsLastDayOffsetOverThirty() {
    // "L-31" is rejected — offsets must be ≤ 30.
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 L-31 * ?"));
  }

  @Test
  public void constructorRejectsSecondIncrementOverSixty() {
    // "*/61" — increment > 59 in the seconds field is invalid.
    assertThrows(ParseException.class, () -> new CronExpression("*/61 * * * * ?"));
  }

  @Test
  public void constructorRejectsHourIncrementOverTwentyFour() {
    assertThrows(ParseException.class, () -> new CronExpression("* * */25 * * ?"));
  }

  @Test
  public void constructorRejectsMonthIncrementOverTwelve() {
    assertThrows(ParseException.class, () -> new CronExpression("* * * * */13 ?"));
  }

  @Test
  public void constructorRejectsDayOfWeekIncrementOverSeven() {
    assertThrows(ParseException.class, () -> new CronExpression("* * * ? * */8"));
  }

  @Test
  public void constructorRejectsBadMonthName() {
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 1 FOO ?"));
  }

  @Test
  public void constructorRejectsBadDayOfWeekName() {
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 ? * BAR"));
  }

  @Test
  public void constructorRejectsTrailingSlash() {
    // "/" not followed by an integer.
    assertThrows(ParseException.class, () -> new CronExpression("/ * * * * ?"));
  }

  @Test
  public void constructorRejectsLetterInDayOfMonth() {
    // The day-of-month field rejects unknown letters (only L/W are special).
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 X * ?"));
  }

  @Test
  public void constructorRejectsQuestionMarkInSeconds() {
    // "?" is only allowed for day-of-month / day-of-week.
    assertThrows(ParseException.class, () -> new CronExpression("? * * * * ?"));
  }

  // ---------------------------------------------------------------------------
  // getNextValidTimeAfter — firing-time computation under fixed UTC
  // ---------------------------------------------------------------------------

  @Test
  public void getNextValidTimeAfterEverySecond() throws ParseException {
    var c = cron("* * * * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0));
    assertEquals(utc(2025, 1, 1, 0, 0, 1), next);
  }

  @Test
  public void getNextValidTimeAfterTopOfNextMinuteOnSecondZero() throws ParseException {
    var c = cron("0 * * * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 30));
    assertEquals(utc(2025, 1, 1, 0, 1, 0), next);
  }

  @Test
  public void getNextValidTimeAfterEveryFifteenMinutes() throws ParseException {
    // From 00:07:00 → next quarter-hour is 00:15:00.
    var c = cron("0 0/15 * * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 7, 0));
    assertEquals(utc(2025, 1, 1, 0, 15, 0), next);
  }

  @Test
  public void getNextValidTimeAfterDailyAtNoonRollsToNextDay() throws ParseException {
    // From 13:00 → fires tomorrow at noon, not today.
    var c = cron("0 0 12 * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 13, 0, 0));
    assertEquals(utc(2025, 1, 2, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterMondayThruFriday() throws ParseException {
    // Saturday 2025-01-04 12:00 UTC → next match is Monday 2025-01-06 12:00 UTC.
    var c = cron("0 0 12 ? * MON-FRI");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 4, 12, 0, 0));
    assertEquals(utc(2025, 1, 6, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterMonthlyOnFirst() throws ParseException {
    // Mid-month → fires on the 1st of next month.
    var c = cron("0 0 0 1 * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 3, 15, 12, 0, 0));
    assertEquals(utc(2025, 4, 1, 0, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterYearlyJanFirst() throws ParseException {
    // Mid-year → fires on Jan 1 of next year.
    var c = cron("0 0 0 1 1 ?");
    var next = c.getNextValidTimeAfter(utc(2025, 7, 15, 12, 0, 0));
    assertEquals(utc(2026, 1, 1, 0, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterLeapDay() throws ParseException {
    // "Feb 29 noon" — from 2025 → next match is 2028 (next leap year).
    var c = cron("0 0 12 29 2 ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0));
    assertEquals(utc(2028, 2, 29, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterLastDayOfMonth() throws ParseException {
    // From mid-Feb 2025 → fires on Feb 28 noon (Feb has 28 days in 2025).
    var c = cron("0 0 12 L * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 2, 15, 0, 0, 0));
    assertEquals(utc(2025, 2, 28, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterLastDayOfMonthInLeapYear() throws ParseException {
    var c = cron("0 0 12 L * ?");
    var next = c.getNextValidTimeAfter(utc(2024, 2, 15, 0, 0, 0));
    assertEquals(utc(2024, 2, 29, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterLastDayOfMonthMinusThree() throws ParseException {
    // March has 31 days → L-3 = day 28.
    var c = cron("0 0 12 L-3 * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 3, 1, 0, 0, 0));
    assertEquals(utc(2025, 3, 28, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterCommaListOfHours() throws ParseException {
    // From 09:00 → next match in {08, 12, 18} on the same day is 12:00.
    var c = cron("0 0 8,12,18 * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 9, 0, 0));
    assertEquals(utc(2025, 1, 1, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterStepHour() throws ParseException {
    // Every six hours starting from 0 → from 02:00 → next is 06:00.
    var c = cron("0 0 0/6 * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 2, 0, 0));
    assertEquals(utc(2025, 1, 1, 6, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterDayOfWeekRange() throws ParseException {
    // 2025-01-04 is Saturday → next MON-FRI noon match is Mon 2025-01-06.
    var c = cron("0 0 12 ? * 2-6");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 4, 0, 0, 0));
    assertEquals(utc(2025, 1, 6, 12, 0, 0), next);
  }

  @Test
  public void getNextValidTimeAfterReturnsNullWhenYearListExhausted() throws ParseException {
    // Year-bounded expression — asking for a time after the last allowed year
    // returns null (the internal year set is exhausted).
    var c = cron("0 0 0 1 1 ? 2025-2026");
    var next = c.getNextValidTimeAfter(utc(2030, 1, 1, 0, 0, 0));
    assertNull(next);
  }

  @Test
  public void getNextValidTimeAfterIgnoresMillisecondsOnInput() throws ParseException {
    // Milliseconds on the "after" Date are stripped before computation.
    var c = cron("0 * * * * ?");
    var afterWithMillis = new Date(utc(2025, 1, 1, 0, 0, 30).getTime() + 500);
    var next = c.getNextValidTimeAfter(afterWithMillis);
    assertEquals(utc(2025, 1, 1, 0, 1, 0), next);
  }

  @Test
  public void getNextValidTimeAfterEveryMinuteFromTopOfMinuteAdvancesToNext()
      throws ParseException {
    // The implementation moves "after" forward by 1 second internally, so calling
    // at the exact firing instant returns the next firing instant — never the
    // current one. This pins that "strictly after" behavior.
    var c = cron("0 * * * * ?");
    var next = c.getNextValidTimeAfter(utc(2025, 1, 1, 0, 0, 0));
    assertEquals(utc(2025, 1, 1, 0, 1, 0), next);
  }

  @Test
  public void getNextValidTimeAfterSequentialCallsProduceMonotonicallyIncreasingTimes()
      throws ParseException {
    // Five consecutive fires on "every 5 minutes on second 0" must be strictly increasing
    // and exactly five minutes apart — basic sanity for the iterative driver pattern that
    // ScheduledEvent.schedule uses to compute the next fire time after each invocation.
    var c = cron("0 0/5 * * * ?");
    var t = utc(2025, 1, 1, 12, 1, 0);
    Date prev = null;
    for (var i = 0; i < 5; i++) {
      var next = c.getNextValidTimeAfter(t);
      assertNotNull(next);
      if (prev != null) {
        assertEquals(5L * 60_000L, next.getTime() - prev.getTime());
      }
      prev = next;
      t = next;
    }
  }

  // ---------------------------------------------------------------------------
  // Advanced day selectors — firing-time pins for L / LW / W / #N
  // ---------------------------------------------------------------------------

  @Test
  public void getNextValidTimeAfter15WWhen15thIsSundayShiftsForwardOneDay()
      throws ParseException {
    // June 15 2025 is a Sunday → "15W" must fire on Mon Jun 16. Exercises the
    // (dow == SUNDAY && day != ldom → day += 1) arm of the nearest-weekday logic.
    var c = cron("0 0 12 15W * ?");
    assertEquals(utc(2025, 6, 16, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 6, 1, 0, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfter1WWhen1stIsSaturdayShiftsForwardTwoDays()
      throws ParseException {
    // Feb 1 2025 is a Saturday → "1W" must fire on Mon Feb 3 (NOT roll back into
    // January). Exercises the (dow == SATURDAY && day == 1 → day += 2) arm.
    var c = cron("0 0 12 1W * ?");
    assertEquals(utc(2025, 2, 3, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 15, 0, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfter31WWhenLastDayIsSundayShiftsBackTwoDays()
      throws ParseException {
    // Aug 31 2025 is a Sunday and equals last-day-of-month (ldom=31) → "31W" must
    // fire on Fri Aug 29. Exercises the (dow == SUNDAY && day == ldom → day -= 2) arm.
    var c = cron("0 0 12 31W * ?");
    assertEquals(utc(2025, 8, 29, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 8, 1, 0, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfterFifthWednesdayRollsToMonthThatHasFive()
      throws ParseException {
    // "4#5" = 5th Wednesday. Feb 2025 has only 4 Wednesdays → must skip to a month
    // with 5. April 2025 has 5 Wednesdays (the last is Apr 30). Exercises the
    // nthdayOfWeek "no Nth occurrence this month — roll month" branch.
    var c = cron("0 0 12 ? * 4#5");
    assertEquals(utc(2025, 4, 30, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 2, 1, 0, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfterLastFridayWhenAlreadyMissedRollsToNextMonth()
      throws ParseException {
    // Last Friday of Feb 2025 is Feb 28; from Feb 28 12:00:01 (one second past),
    // the next match is the last Friday of March (Mar 28 2025).
    // Exercises the "did we already miss the last one?" rollover.
    var c = cron("0 0 12 ? * 6L");
    assertEquals(utc(2025, 3, 28, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 2, 28, 12, 0, 1)));
  }

  @Test
  public void getNextValidTimeAfterLastDayOfMonthRollsAcrossYearBoundary()
      throws ParseException {
    // From Dec 31 2025 12:00:01 (one second past the year's final L-fire), "L * ?"
    // must roll to Jan 31 2026. Exercises the mon > 12 → year+1 branch in the
    // lastdayOfMonth path together with the "tmon = 3333" sentinel that prevents
    // an erroneous mon == tmon short-circuit.
    var c = cron("0 0 12 L * ?");
    assertEquals(utc(2026, 1, 31, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 12, 31, 12, 0, 1)));
  }

  @Test
  public void getNextValidTimeAfterDayOfMonth31SkipsShortMonths() throws ParseException {
    // "0 0 12 31 * ?" — fire only on the 31st. From Feb 1 2025 → next match is
    // Mar 31 (Feb has 28 days, so day=31 > lastDay=28 triggers the daysOfMonth
    // overrun cap that resets day to first-in-set and increments month).
    var c = cron("0 0 12 31 * ?");
    assertEquals(utc(2025, 3, 31, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 2, 1, 0, 0, 0)));
  }

  // ---------------------------------------------------------------------------
  // Overflowing ranges (stopAt < startAt) — modulo branch in addToSet
  // ---------------------------------------------------------------------------

  @Test
  public void getNextValidTimeAfterOverflowingHourRangeWrapsAroundMidnight()
      throws ParseException {
    // "22-2" hours = {22, 23, 0, 1, 2} after the modulo wrap (HOUR max=24).
    // From 03:00 → next match is the same day at 22:00.
    var c = cron("0 0 22-2 * * ?");
    assertEquals(utc(2025, 1, 1, 22, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 1, 3, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfterOverflowingMonthRangeWrapsAroundYearEnd()
      throws ParseException {
    // "NOV-FEB" months = {11, 12, 1, 2}. The post-modulo i2==0 → max remap (line
    // 1017-1019) is the path that turns "0" into "12" for 1-indexed types — pin
    // the boundary by firing the schedule across the wrap. From mid-March 2025 →
    // next match is Nov 1 2025.
    var c = cron("0 0 0 1 NOV-FEB ?");
    assertEquals(utc(2025, 11, 1, 0, 0, 0), c.getNextValidTimeAfter(utc(2025, 3, 15, 0, 0, 0)));
  }

  @Test
  public void constructorRejectsOverflowingYearRange() {
    // The YEAR arm of addToSet's overflow switch unconditionally throws
    // IllegalArgumentException, which buildExpression's catch-Exception block
    // wraps in a ParseException — pin the wrapping contract.
    assertThrows(ParseException.class, () -> new CronExpression("0 0 12 1 1 ? 2030-2025"));
  }

  // ---------------------------------------------------------------------------
  // Numeric day-of-week boundaries — DOW=1 (Sunday) and DOW=7 (Saturday)
  // ---------------------------------------------------------------------------

  @Test
  public void getNextValidTimeAfterNumericSundayMatchesSunday() throws ParseException {
    // DOW=1 = Sunday. From Sat Jan 4 2025 → next match is Sun Jan 5.
    var c = cron("0 0 12 ? * 1");
    assertEquals(utc(2025, 1, 5, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 4, 0, 0, 0)));
  }

  @Test
  public void getNextValidTimeAfterNumericSaturdayMatchesSaturday() throws ParseException {
    // DOW=7 = Saturday. From Fri Jan 3 2025 → next match is Sat Jan 4.
    // Exercises the upper boundary of the DOW range validation (val == 7 accepted).
    var c = cron("0 0 12 ? * 7");
    assertEquals(utc(2025, 1, 4, 12, 0, 0), c.getNextValidTimeAfter(utc(2025, 1, 3, 0, 0, 0)));
  }

  // ---------------------------------------------------------------------------
  // Public constants and accessors
  // ---------------------------------------------------------------------------

  @Test
  public void maxYearIsAtLeastOneHundredYearsInTheFuture() {
    // The class advertises a 100-year future window beyond the JVM's current year.
    // Pinning the lower bound (≥ currentYear + 99) absorbs a Dec 31 → Jan 1
    // class-load-vs-test-run year boundary without losing the contract.
    var thisYear = Calendar.getInstance().get(Calendar.YEAR);
    assertNotNull("MAX_YEAR is initialized as a primitive int constant",
        Integer.valueOf(CronExpression.MAX_YEAR));
    assertEquals(
        "MAX_YEAR must be exactly currentYear+100 (or +99 if class loaded in the previous"
            + " calendar year)",
        true,
        CronExpression.MAX_YEAR == thisYear + 100 || CronExpression.MAX_YEAR == thisYear + 99);
  }
}
