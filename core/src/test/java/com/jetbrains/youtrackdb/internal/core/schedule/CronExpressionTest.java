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
  public void constructorParsesNamedDayOfWeek() throws ParseException {
    new CronExpression("0 0 12 ? * MON");
  }

  @Test
  public void constructorParsesNamedDayOfWeekRange() throws ParseException {
    new CronExpression("0 0 12 ? * MON-FRI");
  }

  @Test
  public void constructorParsesNamedMonth() throws ParseException {
    new CronExpression("0 0 12 1 JAN ?");
  }

  @Test
  public void constructorParsesNamedMonthRange() throws ParseException {
    new CronExpression("0 0 12 1 JAN-DEC ?");
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
  public void constructorParsesLastWeekdayOfMonth() throws ParseException {
    new CronExpression("0 0 12 LW * ?");
  }

  @Test
  public void constructorParsesNearestWeekday() throws ParseException {
    new CronExpression("0 0 12 15W * ?");
  }

  @Test
  public void constructorParsesNthDayOfWeek() throws ParseException {
    new CronExpression("0 0 12 ? * 6#3");
  }

  @Test
  public void constructorParsesLastDayOfWeek() throws ParseException {
    new CronExpression("0 0 12 ? * 6L");
  }

  @Test
  public void constructorParsesYearField() throws ParseException {
    new CronExpression("0 0 12 1 1 ? 2030");
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
  public void constructorIsCaseInsensitive() throws ParseException {
    // Lowercase tokens are upper-cased before parsing.
    var c = new CronExpression("0 0 12 ? * mon");
    assertEquals("0 0 12 ? * MON", c.getCronExpression());
  }

  @Test
  public void constructorPreservesUpperCasedInputViaToString() throws ParseException {
    var c = new CronExpression("0 0 12 ? * mon");
    assertEquals("0 0 12 ? * MON", c.toString());
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
  // Public constants and accessors
  // ---------------------------------------------------------------------------

  @Test
  public void maxYearIsCurrentYearPlusOneHundred() {
    // The class advertises a 100-year future window beyond the JVM's current year.
    var thisYear = Calendar.getInstance().get(Calendar.YEAR);
    assertEquals(thisYear + 100, CronExpression.MAX_YEAR);
  }

  @Test
  public void getCronExpressionEchoesUpperCasedInput() throws ParseException {
    var c = new CronExpression("0 0 12 ? * mon-fri");
    assertEquals("0 0 12 ? * MON-FRI", c.getCronExpression());
  }
}
