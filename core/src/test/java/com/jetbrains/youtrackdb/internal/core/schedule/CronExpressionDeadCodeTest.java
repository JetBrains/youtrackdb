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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.text.ParseException;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Dead-code pin tests for the secondary surface of {@link CronExpression}. Cross-module grep
 * (performed during this track's review phase) confirmed that the methods exercised here have zero
 * production callers in {@code core/main} (apart from {@code clone()} → copy constructor, which is
 * itself dead), {@code server/}, {@code driver/}, {@code embedded/}, {@code gremlin-annotations/},
 * and {@code tests/}. The only live entry point on {@code CronExpression} from production code is
 * {@code getNextValidTimeAfter}, called from {@link
 * com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent}; everything pinned here is
 * legacy surface inherited from the upstream Quartz fork that {@code core/schedule} drew from.
 *
 * <p>Each test pins a falsifiable observable (return value, identity, exception) so that a
 * mutation to the underlying branch is detectable. A future deletion that removes one of these
 * methods will fail compilation here, which is precisely the "loud" signal we want for the final
 * sweep.
 *
 * <p>WHEN-FIXED: delete the following from {@link CronExpression} (zero production callers
 * confirmed): {@code getTimeBefore}, {@code getFinalFireTime}, {@code clone}, {@code
 * CronExpression(CronExpression)} copy constructor, {@code isSatisfiedBy}, {@code
 * getNextInvalidTimeAfter}, {@code getExpressionSummary}, and the lazy {@link
 * java.util.TimeZone#getDefault()} fallback inside {@link CronExpression#getTimeZone()} together
 * with the {@link CronExpression#setTimeZone(TimeZone)} setter (the live caller on {@code
 * getNextValidTimeAfter} should pass the storage time zone explicitly).
 */
public class CronExpressionDeadCodeTest {

  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  private static Date utc(int year, int month, int day, int hour, int minute, int second) {
    var cal = new GregorianCalendar(UTC);
    cal.clear();
    cal.set(year, month - 1, day, hour, minute, second);
    return cal.getTime();
  }

  private static CronExpression cronUtc(String expr) throws ParseException {
    var c = new CronExpression(expr);
    c.setTimeZone(UTC);
    return c;
  }

  // ---------------------------------------------------------------------------
  // getTimeBefore — TODO stub; pin the always-null return
  // ---------------------------------------------------------------------------

  @Test
  public void getTimeBeforeIsNotImplementedAndReturnsNullForAnyInput() throws ParseException {
    // The method is documented as "NOT YET IMPLEMENTED" and has been so since the
    // upstream Quartz fork landed in core/schedule. It must keep returning null until
    // someone either implements it or deletes it. Either change makes this test fail.
    var c = cronUtc("0 0 12 * * ?");
    assertNull(c.getTimeBefore(utc(2025, 6, 15, 12, 0, 0)));
    // Null input behaves the same — the body returns null unconditionally.
    assertNull(c.getTimeBefore(null));
  }

  // ---------------------------------------------------------------------------
  // getFinalFireTime — TODO stub; pin the always-null return
  // ---------------------------------------------------------------------------

  @Test
  public void getFinalFireTimeIsNotImplementedAndReturnsNull() throws ParseException {
    // Even for a year-bounded expression that has a well-defined final fire time
    // (Jan 1 2025 00:00 UTC), the method returns null because the body is a stub.
    var c = cronUtc("0 0 0 1 1 ? 2025");
    assertNull(c.getFinalFireTime());
  }

  // ---------------------------------------------------------------------------
  // clone() and CronExpression(CronExpression) — both dead, but related
  // ---------------------------------------------------------------------------

  @Test
  public void cloneReturnsADistinctInstanceWithIdenticalExpression() throws ParseException {
    // clone() delegates to the copy constructor; pin both at once.
    var c = cronUtc("0 0 12 ? * MON-FRI");
    var copy = (CronExpression) c.clone();
    assertNotSame(c, copy);
    assertEquals(c.getCronExpression(), copy.getCronExpression());
  }

  @Test
  public void cloneCopiesTimeZoneSoFiringTimesMatch() throws ParseException {
    // Setting an explicit non-default zone on the original must propagate to the
    // clone — otherwise the clone's getTimeZone() would lazy-fallback to the JVM
    // default and produce different fire times. This pins the TimeZone-clone branch
    // inside the copy constructor.
    var c = new CronExpression("0 0 12 * * ?");
    c.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    var copy = (CronExpression) c.clone();
    assertEquals(c.getTimeZone(), copy.getTimeZone());
    var origin = utc(2025, 1, 1, 0, 0, 0);
    assertEquals(c.getNextValidTimeAfter(origin), copy.getNextValidTimeAfter(origin));
  }

  @Test
  public void copyConstructorPropagatesLazyInitializedTimeZoneToCopy() throws ParseException {
    // The copy ctor reads the source via expression.getTimeZone() (the accessor),
    // not the raw field — so even if the caller never called setTimeZone, the
    // accessor lazy-initializes the source's field to TimeZone.getDefault() and
    // returns it. The conditional `if (expression.getTimeZone() != null)` is
    // therefore never false in practice, and the copy always inherits a non-null
    // zone (the lazily-defaulted one). Pin both the cron-string copy and the
    // non-null TZ propagation so a future refactor that changes the accessor's
    // contract is detected.
    var c = new CronExpression("0 0 12 * * ?");
    var copy = new CronExpression(c);
    assertEquals(c.getCronExpression(), copy.getCronExpression());
    assertNotNull(copy.getTimeZone());
  }

  // ---------------------------------------------------------------------------
  // isSatisfiedBy — true / false branches
  // ---------------------------------------------------------------------------

  @Test
  public void isSatisfiedByReturnsTrueWhenDateMatchesFiringInstant() throws ParseException {
    // Noon UTC matches "0 0 12 * * ?"; the predicate must say so.
    var c = cronUtc("0 0 12 * * ?");
    assertTrue(c.isSatisfiedBy(utc(2025, 1, 1, 12, 0, 0)));
  }

  @Test
  public void isSatisfiedByReturnsFalseWhenDateMissesFiringInstant() throws ParseException {
    // 12:00:30 doesn't match "0 0 12 * * ?" (seconds must be 0).
    var c = cronUtc("0 0 12 * * ?");
    assertFalse(c.isSatisfiedBy(utc(2025, 1, 1, 12, 0, 30)));
  }

  @Test
  public void isSatisfiedByDiscardsSubSecondPrecision() throws ParseException {
    // Milliseconds within the matching second are stripped before comparison.
    var c = cronUtc("0 0 12 * * ?");
    var withMillis = new Date(utc(2025, 1, 1, 12, 0, 0).getTime() + 500);
    assertTrue(c.isSatisfiedBy(withMillis));
  }

  // ---------------------------------------------------------------------------
  // getNextInvalidTimeAfter — pin the "fire + 1 sec" contract
  // ---------------------------------------------------------------------------

  @Test
  public void getNextInvalidTimeAfterReturnsInputPlusOneSecondForSparseSchedule()
      throws ParseException {
    // Implementation contract: the loop walks forward only while consecutive fires
    // are exactly 1 second apart. For a sparse schedule (yearly Jan 1) the first
    // call to getTimeAfter returns a fire many months later, so the difference
    // exceeds 1000 immediately, the loop exits without updating lastDate, and the
    // method returns lastDate + 1 second — i.e., the input second + 1, regardless
    // of the actual next fire. This is the documented behavior in the body's
    // comment ("the second immediately following the last valid fire time").
    var c = cronUtc("0 0 0 1 1 ?");
    var next = c.getNextInvalidTimeAfter(utc(2025, 6, 1, 0, 0, 0));
    assertEquals(utc(2025, 6, 1, 0, 0, 1), next);
  }

  @Test
  public void getNextInvalidTimeAfterDiscardsSubSecondPrecisionOnInput() throws ParseException {
    // Milliseconds on the input Date are stripped before the +1s addition, so an
    // input of 12:00:00.500 yields 12:00:01.000 (not 12:00:01.500).
    var c = cronUtc("0 0 0 1 1 ?");
    var withMillis = new Date(utc(2025, 6, 1, 0, 0, 0).getTime() + 500);
    var next = c.getNextInvalidTimeAfter(withMillis);
    assertEquals(utc(2025, 6, 1, 0, 0, 1), next);
  }

  // ---------------------------------------------------------------------------
  // getExpressionSummary — pin the labelled multi-line format
  // ---------------------------------------------------------------------------

  @Test
  public void getExpressionSummaryRendersAllFieldLabels() throws ParseException {
    // The summary is a fixed 10-line string keyed by field name. Pin the contract
    // so renaming any internal field (or dropping a label) trips the test.
    var c = cronUtc("0 0 12 1 1 ?");
    var summary = c.getExpressionSummary();
    assertTrue(summary.contains("seconds: "));
    assertTrue(summary.contains("minutes: "));
    assertTrue(summary.contains("hours: "));
    assertTrue(summary.contains("daysOfMonth: "));
    assertTrue(summary.contains("months: "));
    assertTrue(summary.contains("daysOfWeek: "));
    assertTrue(summary.contains("lastdayOfWeek: false"));
    assertTrue(summary.contains("nearestWeekday: false"));
    assertTrue(summary.contains("NthDayOfWeek: 0"));
    assertTrue(summary.contains("lastdayOfMonth: false"));
    assertTrue(summary.contains("years: "));
  }

  @Test
  public void getExpressionSummaryEmitsQuestionMarkForNoSpecField() throws ParseException {
    // The day-of-week field carries "?" → summary renders "?" verbatim, not
    // the underlying NO_SPEC integer marker.
    var c = cronUtc("0 0 12 1 1 ?");
    var summary = c.getExpressionSummary();
    assertTrue(
        "daysOfWeek line must read '?'", summary.contains("daysOfWeek: ?"));
  }

  @Test
  public void getExpressionSummaryEmitsStarForAllSpecField() throws ParseException {
    // The seconds field is "*" → summary renders "*" rather than enumerating 0..59.
    var c = cronUtc("* * * 1 1 ?");
    var summary = c.getExpressionSummary();
    assertTrue(
        "seconds line must read '*'", summary.contains("seconds: *"));
  }

  @Test
  public void getExpressionSummaryEmitsCommaSeparatedListForExplicitValues()
      throws ParseException {
    // "0,15,30,45" in seconds → summary lists those exact values comma-separated.
    var c = cronUtc("0,15,30,45 * * * * ?");
    var summary = c.getExpressionSummary();
    assertTrue(
        "seconds line must enumerate 0,15,30,45",
        summary.contains("seconds: 0,15,30,45"));
  }

  @Test
  public void getExpressionSummaryReflectsLastDayOfMonthFlag() throws ParseException {
    // The "L" token in day-of-month sets lastdayOfMonth = true; pin the rendered flag.
    var c = cronUtc("0 0 12 L * ?");
    var summary = c.getExpressionSummary();
    assertTrue(summary.contains("lastdayOfMonth: true"));
  }

  @Test
  public void getExpressionSummaryReflectsNearestWeekdayFlag() throws ParseException {
    // "15W" sets nearestWeekday = true.
    var c = cronUtc("0 0 12 15W * ?");
    var summary = c.getExpressionSummary();
    assertTrue(summary.contains("nearestWeekday: true"));
  }

  // ---------------------------------------------------------------------------
  // getTimeZone / setTimeZone — lazy default fallback + the unused setter
  // ---------------------------------------------------------------------------

  @Test
  public void getTimeZoneLazilyFallsBackToJvmDefaultWhenUnset() throws ParseException {
    // The constructor leaves timeZone == null. The first call to getTimeZone()
    // populates the field with TimeZone.getDefault() and returns it. Subsequent
    // calls return the same instance — pin both via two consecutive accessors.
    var c = new CronExpression("0 0 12 * * ?");
    var first = c.getTimeZone();
    assertNotNull(first);
    var second = c.getTimeZone();
    // Same reference — fallback writes the field once, then short-circuits.
    assertEquals(first, second);
  }

  @Test
  public void setTimeZoneOverridesLazyFallback() throws ParseException {
    // Explicit setTimeZone disables the lazy fallback. After setting, the
    // accessor returns the supplied zone, not TimeZone.getDefault().
    var c = new CronExpression("0 0 12 * * ?");
    c.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    assertEquals(TimeZone.getTimeZone("America/New_York"), c.getTimeZone());
  }
}
