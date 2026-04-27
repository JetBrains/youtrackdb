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
import static org.junit.Assert.assertSame;
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
 * java.util.TimeZone#getDefault()} fallback inside {@link CronExpression#getTimeZone()}. The
 * {@link CronExpression#setTimeZone(TimeZone)} setter is exercised by the live test fixtures here
 * and in {@link CronExpressionTest} to pin computations to UTC, so it stays — only the implicit
 * {@code TimeZone.getDefault()} fallback path is dead and should be replaced by an explicit
 * caller-supplied zone (or a constructor argument) when this class is deleted.
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
  @SuppressWarnings("deprecation") // CronExpression#clone is part of the dead surface pinned here
  public void cloneReturnsADistinctInstanceWithEquivalentBehavior() throws ParseException {
    // clone() delegates to the copy constructor. Pin three observables: distinct
    // identity, identical cron string, and behavioral equivalence (the next fire
    // time matches), so that a refactor dropping buildExpression in the copy
    // constructor is detected at this site rather than only via the firing-time
    // test below.
    var c = cronUtc("0 0 12 ? * MON-FRI");
    var copy = (CronExpression) c.clone();
    assertNotSame(c, copy);
    assertEquals(c.getCronExpression(), copy.getCronExpression());
    var origin = utc(2025, 1, 1, 0, 0, 0);
    assertEquals(c.getNextValidTimeAfter(origin), copy.getNextValidTimeAfter(origin));
  }

  @Test
  @SuppressWarnings("deprecation") // CronExpression#clone is part of the dead surface pinned here
  public void cloneCopiesTimeZoneSoFiringTimesMatchUnderNonDefaultZone() throws ParseException {
    // Setting an explicit non-default zone on the original must propagate to the
    // clone — otherwise the clone's getTimeZone() would lazy-fallback to the JVM
    // default and produce different fire times. Pin via firing-time equality
    // rather than TimeZone.equals: TimeZone.equals is subclass-defined (ZoneInfo
    // / SimpleTimeZone) and brittle across JDK refactors, but firing-time equality
    // is the actual contract callers depend on.
    var c = new CronExpression("0 0 12 * * ?");
    c.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    var copy = (CronExpression) c.clone();
    assertEquals(
        "TimeZone IDs must match so the clone reproduces the original's wall-clock fires",
        c.getTimeZone().getID(),
        copy.getTimeZone().getID());
    var origin = utc(2025, 1, 1, 0, 0, 0);
    assertEquals(c.getNextValidTimeAfter(origin), copy.getNextValidTimeAfter(origin));
  }

  @Test
  public void cloneIsIndependentOfPostCloneMutationsToOriginalsTimeZone() throws ParseException {
    // The copy constructor explicitly clones the source's TimeZone instance —
    // pin the deep-copy contract by mutating the original AFTER the clone and
    // verifying the clone is unaffected. A regression that swapped the
    // explicit (TimeZone) expression.getTimeZone().clone() for a shared
    // reference (`timeZone = expression.getTimeZone();`) would corrupt this
    // contract silently.
    var original = new CronExpression("0 0 12 * * ?");
    original.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    @SuppressWarnings("deprecation")
    var copy = (CronExpression) original.clone();
    original.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    assertEquals("America/New_York", copy.getTimeZone().getID());
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

  @Test
  public void isSatisfiedByReturnsFalseWhenNoFiringExistsAfterInputDate()
      throws ParseException {
    // The implementation is `return timeAfter != null && timeAfter.equals(originalDate);`.
    // The null-short-circuit branch fires only when getTimeAfter exhausts its search and
    // returns null — reachable with a year-bounded expression queried past its only
    // allowed year. A regression that swapped `&&` for `||`, or that dropped the
    // null-guard, would NPE on `equals` rather than returning false.
    var c = cronUtc("0 0 12 1 1 ? 2025");
    assertFalse("year-bounded cron must yield false (not NPE) past the bounded year",
        c.isSatisfiedBy(utc(2030, 1, 1, 0, 0, 0)));
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
  public void getNextInvalidTimeAfterAdvancesPastContiguousFireBlock() throws ParseException {
    // Contract from the inner loop body: while consecutive fires are exactly 1
    // second apart the loop walks forward, updating lastDate on each step. Pin
    // the advance branch with a dense schedule whose fires are contiguous:
    // "0-30 * * * * ?" fires every second of seconds 0..30 of every minute.
    // From 12:00:00 the loop visits 12:00:01, 12:00:02, ... 12:00:30, then
    // observes the next fire is 12:01:00 (gap > 1 second), exits, and returns
    // lastDate (12:00:30) + 1 second = 12:00:31. A regression that dropped the
    // `lastDate = newDate` assignment would return 12:00:01 instead of 12:00:31.
    var c = cronUtc("0-30 * * * * ?");
    var next = c.getNextInvalidTimeAfter(utc(2025, 1, 1, 12, 0, 0));
    assertEquals(utc(2025, 1, 1, 12, 0, 31), next);
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
  public void getExpressionSummaryRendersExactCanonicalForm() throws ParseException {
    // The summary is a fixed 11-line string with a precise label/value layout.
    // Pin full-string equality so a refactor that reorders lines, drops a
    // separator, or rephrases a label trips the test loudly. Substring-only
    // checks would let any of those mutations slip through.
    var c = cronUtc("0 0 12 1 1 ?");
    var expected =
        "seconds: 0\n"
            + "minutes: 0\n"
            + "hours: 12\n"
            + "daysOfMonth: 1\n"
            + "months: 1\n"
            + "daysOfWeek: ?\n"
            + "lastdayOfWeek: false\n"
            + "nearestWeekday: false\n"
            + "NthDayOfWeek: 0\n"
            + "lastdayOfMonth: false\n"
            + "years: *\n";
    assertEquals(expected, c.getExpressionSummary());
  }

  @Test
  public void getExpressionSummaryEmitsCommaSeparatedListForExplicitValues()
      throws ParseException {
    // Branch coverage: the comma-list path of getExpressionSetSummary fires only
    // when the set lacks both NO_SPEC and ALL_SPEC. "0,15,30,45" hits that path
    // with a deterministic enumeration order. Pin the exact resulting line.
    var c = cronUtc("0,15,30,45 * * * * ?");
    var summary = c.getExpressionSummary();
    assertTrue(
        "seconds line must enumerate 0,15,30,45 in ascending order: " + summary,
        summary.contains("seconds: 0,15,30,45\n"));
  }

  @Test
  public void getExpressionSummaryReflectsBooleanFlagsForLastDayAndNearestWeekday()
      throws ParseException {
    // Both boolean flags are normally false; "L" must flip lastdayOfMonth and
    // "15W" must flip nearestWeekday. Pin both flips in one test to keep the
    // dead-code surface compact.
    var l = cronUtc("0 0 12 L * ?");
    assertTrue(
        "L token must set lastdayOfMonth flag",
        l.getExpressionSummary().contains("lastdayOfMonth: true\n"));
    var w = cronUtc("0 0 12 15W * ?");
    assertTrue(
        "W token must set nearestWeekday flag",
        w.getExpressionSummary().contains("nearestWeekday: true\n"));
  }

  // ---------------------------------------------------------------------------
  // getTimeZone / setTimeZone — lazy default fallback + the unused setter
  // ---------------------------------------------------------------------------

  @Test
  public void getTimeZoneLazilyFallsBackToJvmDefaultWhenUnset() throws ParseException {
    // The constructor leaves timeZone == null. The first call to getTimeZone()
    // must (a) populate the field with the JVM default zone, (b) return that exact
    // instance on subsequent calls (i.e. memoize, not re-fetch). Pin both via
    // assertSame on two consecutive accessors. assertEquals is too weak — two
    // distinct TimeZone clones of the same zone compare equal but would let a
    // refactor that drops the field caching slip through.
    var c = new CronExpression("0 0 12 * * ?");
    var first = c.getTimeZone();
    assertNotNull(first);
    assertEquals(
        "first call must populate the field with TimeZone.getDefault()",
        TimeZone.getDefault(), first);
    var second = c.getTimeZone();
    assertSame("subsequent calls must return the cached instance, not re-resolve", first, second);
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
