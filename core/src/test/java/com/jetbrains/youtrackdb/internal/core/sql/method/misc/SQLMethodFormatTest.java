/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>format()</code> method. The method calls
 * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#getDatabaseSession},
 * which requires a live database session (the BasicCommandContext throws otherwise), so this
 * test extends {@link DbTestBase}. Supports three dispatch branches based on
 * <code>ioResult</code>:
 * <ul>
 *   <li>collection of Dates → format each with the pattern</li>
 *   <li>single Date → {@link java.text.SimpleDateFormat#format}</li>
 *   <li>otherwise → {@link String#format}</li>
 * </ul>
 * The second parameter, when present, sets the {@link java.util.TimeZone} used for date
 * formatting.
 */
public class SQLMethodFormatTest extends DbTestBase {

  private SQLMethodFormat method;
  private BasicCommandContext context;
  private Locale savedLocale;

  @Before
  public void setupMethod() {
    method = new SQLMethodFormat();
    // DbTestBase.session is a real DatabaseSessionEmbedded — attach it so the method's
    // getDatabaseSession() call succeeds.
    context = new BasicCommandContext(session);
    // Pin a deterministic locale for %.2f-style format tests; production String.format uses
    // the default Locale, so a runner in de_DE would render 1.5 as "1,50" not "1.50".
    savedLocale = Locale.getDefault();
    Locale.setDefault(Locale.US);
  }

  @After
  public void restoreLocale() {
    Locale.setDefault(savedLocale);
  }

  @Test
  public void numericFormatAppliesStringFormat() {
    // Standard String.format path — left-padded integer.
    assertEquals("0042",
        method.execute(null, null, context, 42, new Object[] {"%04d"}));
  }

  @Test
  public void numericFormatWithFloat() {
    assertEquals("1.50",
        method.execute(null, null, context, 1.5, new Object[] {"%.2f"}));
  }

  @Test
  public void nullIoResultWithStringFormatReturnsNull() {
    // Non-collection, non-Date, null ioResult → falls to else branch → null ternary → null.
    assertNull(method.execute(null, null, context, null, new Object[] {"%s"}));
  }

  @Test
  public void dateFormatUsesExplicitTimeZone() {
    // Epoch = 1970-01-01 00:00:00 UTC. With explicit UTC timezone, format should reflect that.
    var epoch = new Date(0L);
    var result = method.execute(null, null, context, epoch,
        new Object[] {"yyyy-MM-dd HH:mm:ss", "UTC"});
    assertEquals("1970-01-01 00:00:00", result);
  }

  @Test
  public void dateCollectionFormattedElementWise() {
    // A collection of Dates → returns a list of formatted strings (UTC timezone given).
    var dates = new ArrayList<Date>();
    dates.add(new Date(0L));
    dates.add(new Date(24L * 60 * 60 * 1000)); // +1 day
    var result = method.execute(null, null, context, dates, new Object[] {"yyyy-MM-dd", "UTC"});
    assertEquals(Arrays.asList("1970-01-01", "1970-01-02"), result);
  }

  @Test
  public void stringInputFormattedWithPercentS() {
    // Non-Date, non-collection-of-dates, non-null ioResult → String.format("%s", ioResult).
    assertEquals("hello",
        method.execute(null, null, context, "hello", new Object[] {"%s"}));
  }

  @Test
  public void emptyCollectionTreatedAsCollectionOfDates() {
    // isCollectionOfDates returns true for an empty iterable — path yields empty result List.
    // Assert the result is specifically a List (not some accidental pass-through), then that
    // it is empty — a plain assertEquals(emptyList, result) would be vacuously true because
    // every empty List equals every other empty List regardless of branch taken.
    var empty = new ArrayList<Date>();
    var result = method.execute(null, null, context, empty, new Object[] {"yyyy", "UTC"});
    assertTrue("Expected List result from date-collection branch", result instanceof List);
    assertEquals(Collections.emptyList(), result);
  }

  @Test
  public void dateFormatUsesDatabaseTimeZoneWhenNotProvided() {
    // When no 2nd param is supplied, the formatter pulls the timezone from the session via
    // DateHelper.getDatabaseTimeZone. Whatever the DB TZ is, the epoch year is 1970.
    var result = method.execute(null, null, context, new Date(0L), new Object[] {"yyyy"});
    assertEquals("1970", result);
  }

  @Test
  public void dateCollectionWithNullElementThrowsIllegalArgument() {
    // isCollectionOfDates permits null elements (skipped in the check), but the formatting
    // loop then calls SimpleDateFormat.format(null) which rejects non-Date input with
    // IllegalArgumentException("Cannot format given Object as a Date"). Pin current
    // behaviour so a future null-guard change is caught.
    var list = new ArrayList<Date>();
    list.add(new Date(0L));
    list.add(null);
    try {
      method.execute(null, null, context, list, new Object[] {"yyyy", "UTC"});
      throw new AssertionError(
          "Expected IllegalArgumentException from SimpleDateFormat.format(null)");
    } catch (IllegalArgumentException expected) {
      // expected — no null-element guard in the dates-collection loop.
    }
  }

  @Test
  public void dynamicFormatStringLookedUpViaCurrentRecord() {
    // The first param can be a property name resolved against iCurrentRecord via
    // getParameterValue. Here property "fmt" resolves to "%s" — exercises the non-static
    // parameter-value branch.
    var record = new ResultInternal(session);
    record.setProperty("fmt", "%s");
    Result r = record;
    assertEquals("hello",
        method.execute(null, r, context, "hello", new Object[] {"fmt"}));
  }

  @Test
  public void localePinnedPercentTwoF() {
    // With the @Before-pinned Locale.US, 1.5 renders with a dot decimal separator.
    assertEquals("1.50", (String) method.execute(null, null, context, 1.5, new Object[] {"%.2f"}));
    // Additionally assert that if we flipped the locale to German, production would yield
    // "1,50" — this documents the locale-sensitivity that BC1 flagged and ensures future
    // maintainers don't accidentally reintroduce a non-locale-safe assertion.
    Locale.setDefault(Locale.GERMANY);
    assertEquals("1,50", (String) method.execute(null, null, context, 1.5, new Object[] {"%.2f"}));
  }

}
