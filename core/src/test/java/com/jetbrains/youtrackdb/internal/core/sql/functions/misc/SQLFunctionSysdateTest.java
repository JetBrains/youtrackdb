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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionSysdate} — returns the construction-time {@link Date} or a
 * formatted string based on {@link SimpleDateFormat} + timezone.
 *
 * <p>Standalone (no DB). The 1-argument path requires a {@code DatabaseSessionEmbedded} for the
 * default database timezone, so those branches are deferred to Step 6 (DB-required misc tests).
 * This class covers:
 *
 * <ul>
 *   <li>Zero-arg: returns the stored {@code now} Date; two calls return the SAME Date instance
 *       (this is the "same date for all iterations" contract from the class Javadoc).
 *   <li>Two-arg: explicit format + timezone string → SimpleDateFormat-formatted string.
 *   <li>format cache: after the first 2-arg call, subsequent calls keep the original format and
 *       timezone even if iParams change (documents the latent cache-ignores-inputs behaviour).
 *   <li>aggregateResults(params) == false, getResult() == null, metadata.
 * </ul>
 */
public class SQLFunctionSysdateTest {

  @Test
  public void zeroArgReturnsStoredNowDateAndIsStableAcrossCalls() {
    final var fn = new SQLFunctionSysdate();
    final var ctx = new BasicCommandContext();

    final var d1 = fn.execute(null, null, null, new Object[] {}, ctx);
    final var d2 = fn.execute(null, null, null, new Object[] {}, ctx);

    assertTrue("expected Date, got " + d1.getClass(), d1 instanceof Date);
    // Same instance — the stored `now` field is reused across calls ("same date for all
    // iterations" per the class Javadoc).
    assertSame(d1, d2);
  }

  @Test
  public void twoArgFormatAndTimezoneProducesFormattedString() {
    final var fn = new SQLFunctionSysdate();
    final var ctx = new BasicCommandContext();

    // Use a deterministic format we can verify against an equivalent SimpleDateFormat instance.
    final var expected =
        buildExpected((Date) fn.execute(null, null, null, new Object[] {}, ctx),
            "yyyy-MM-dd HH:mm:ss", "UTC");

    // Build a fresh function so the format cache is in its initial state.
    final var fn2 = new SQLFunctionSysdate();
    final var result = fn2.execute(null, null, null,
        new Object[] {"yyyy-MM-dd HH:mm:ss", "UTC"}, ctx);

    assertTrue("expected String, got " + result.getClass(), result instanceof String);
    // Rebuild expected against fn2's construction-time Date.
    final var expected2 = buildExpected((Date) fn2.execute(null, null, null, new Object[] {}, ctx),
        "yyyy-MM-dd HH:mm:ss", "UTC");
    // The returned string must match BOTH the expected we computed for fn2 AND be a legal
    // SimpleDateFormat rendering (sanity). Equal comparisons isolate the format from the Date.
    assertEquals(expected2, result);
    // Sanity — expected for fn should at least parse through the same pattern.
    assertEquals(expected.length(), ((String) result).length());
  }

  @Test
  public void formatIsCachedOnFirstTwoArgCall() {
    final var fn = new SQLFunctionSysdate();
    final var ctx = new BasicCommandContext();

    final var firstCall = (String) fn.execute(null, null, null,
        new Object[] {"yyyy-MM-dd", "UTC"}, ctx);

    // Second call with a DIFFERENT pattern/timezone — the implementation caches the first
    // SimpleDateFormat and never re-reads iParams[0]/[1] afterwards. So the output uses the
    // original pattern "yyyy-MM-dd", not the new "HH:mm:ss".
    final var secondCall = (String) fn.execute(null, null, null,
        new Object[] {"HH:mm:ss", "America/New_York"}, ctx);

    assertEquals("cached format must ignore new iParams[0]",
        firstCall.length(), secondCall.length());
    // Drift guard: a refactor that recreates SimpleDateFormat per call would break this
    // length equality (the two patterns render very differently).
  }

  @Test
  public void aggregateResultsIsFalse() {
    assertFalse(new SQLFunctionSysdate().aggregateResults(new Object[] {}));
  }

  @Test
  public void getResultIsAlwaysNull() {
    final var fn = new SQLFunctionSysdate();
    fn.execute(null, null, null, new Object[] {}, new BasicCommandContext());
    assertNull(fn.getResult());
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionSysdate();
    assertEquals("sysdate", fn.getName(null));
    assertEquals(0, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
    assertEquals("sysdate([<format>] [,<timezone>])", fn.getSyntax(null));
  }

  private static String buildExpected(Date d, String pattern, String tz) {
    final var fmt = new SimpleDateFormat(pattern);
    fmt.setTimeZone(TimeZone.getTimeZone(tz));
    return fmt.format(d);
  }
}
