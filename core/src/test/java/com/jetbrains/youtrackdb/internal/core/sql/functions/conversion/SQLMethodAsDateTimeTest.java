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
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.util.Date;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodAsDateTime} — like {@link SQLMethodAsDate} but without HMS zeroing.
 * Branches: Date → same instance (pinned by assertSame!), Number → new Date(longValue), String →
 * parsed via DB datetime format.
 *
 * <p>Uses {@link DbTestBase} for the String branch.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>Date input is returned AS-IS (identity — the branch does NOT clone). WHEN-FIXED: a caller
 *       that mutates the returned Date affects the input.
 *   <li>Number input → {@code new Date(longValue)} — full epoch-ms preserved.
 *   <li>String input round-trips via the DB's default datetime format (uses DateHelper).
 *   <li>Unparseable string → null (ParseException swallowed + logged).
 *   <li>Metadata.
 * </ul>
 */
public class SQLMethodAsDateTimeTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var c = new BasicCommandContext();
    c.setDatabaseSession(session);
    return c;
  }

  private SQLMethodAsDateTime method() {
    return new SQLMethodAsDateTime();
  }

  // ---------------------------------------------------------------------------
  // Null / Date branches
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    assertNull(method().execute(null, null, ctx(), null, new Object[] {}));
  }

  @Test
  public void dateInputReturnsSameInstance() {
    // WHEN-FIXED: the branch returns iThis directly — pins that no defensive copy is made.
    var date = new Date(1_700_050_350_777L);

    var result = method().execute(date, null, ctx(), null, new Object[] {});

    assertSame("Date input must be returned as the SAME instance", date, result);
  }

  // ---------------------------------------------------------------------------
  // Number branch — FULL epoch-ms preserved (unlike SQLMethodAsDate which zeroes HMS)
  // ---------------------------------------------------------------------------

  @Test
  public void longInputReturnsDateWithFullEpochMs() {
    var epochMs = 1_700_050_350_777L;

    var result = (Date) method().execute(Long.valueOf(epochMs), null, ctx(), null, new Object[] {});

    assertEquals(new Date(epochMs), result);
    // Pin that HMS is NOT zeroed — the ms value survives.
    assertEquals(epochMs, result.getTime());
  }

  @Test
  public void integerInputReturnsDateFromLongValue() {
    var epochMs = 1_700_000_000;

    var result = (Date) method().execute(Integer.valueOf(epochMs), null, ctx(), null,
        new Object[] {});

    assertEquals(new Date((long) epochMs), result);
  }

  // ---------------------------------------------------------------------------
  // String branch
  // ---------------------------------------------------------------------------

  @Test
  public void stringInputUsesDbDateTimeFormatToParse() throws Exception {
    // Round-trip via the DB's configured datetime format.
    var format = DateHelper.getDateTimeFormatInstance(session);
    var expected = new Date(1_700_050_350_000L);
    var inputText = format.format(expected);

    var result = (Date) method().execute(inputText, null, ctx(), null, new Object[] {});

    assertNotNull(result);
    // Compare via the SAME format — small sub-second truncation is acceptable because
    // DateFormat.parse() may drop millis.
    assertEquals(format.format(expected), format.format(result));
  }

  @Test
  public void unparseableStringReturnsNull() {
    // Invalid format → ParseException → caught → null.
    var result = method().execute("not-a-datetime", null, ctx(), null, new Object[] {});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("asdatetime", SQLMethodAsDateTime.NAME);
    assertEquals("asdatetime", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(0, m.getMaxParams(null));
    assertEquals("asDatetime()", m.getSyntax());
  }
}
