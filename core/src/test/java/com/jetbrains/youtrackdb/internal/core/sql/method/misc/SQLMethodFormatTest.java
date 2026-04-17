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

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.ArrayList;
import java.util.Date;
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

  @Before
  public void setupMethod() {
    method = new SQLMethodFormat();
    // DbTestBase.session is a real DatabaseSessionEmbedded — attach it so the method's
    // getDatabaseSession() call succeeds.
    context = new BasicCommandContext(session);
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
    assertEquals(java.util.Arrays.asList("1970-01-01", "1970-01-02"), result);
  }

  @Test
  public void stringInputFormattedWithPercentS() {
    // Non-Date, non-collection-of-dates, non-null ioResult → String.format("%s", ioResult).
    assertEquals("hello",
        method.execute(null, null, context, "hello", new Object[] {"%s"}));
  }

  @Test
  public void emptyCollectionTreatedAsCollectionOfDates() {
    // isCollectionOfDates returns true for an empty iterable — path yields empty result list.
    var empty = new ArrayList<Date>();
    var result = method.execute(null, null, context, empty, new Object[] {"yyyy", "UTC"});
    assertEquals(new ArrayList<>(), result);
  }
}
