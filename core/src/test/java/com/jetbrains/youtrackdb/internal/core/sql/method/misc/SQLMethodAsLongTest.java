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
import static org.junit.Assert.fail;

import java.util.Date;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>asLong()</code> method. Exercises three dispatch branches: Number →
 * longValue(), Date → getTime() (epoch millis), and fallback to Long.valueOf(toString().trim()).
 * Also verifies null propagation and the error path for unparsable strings.
 */
public class SQLMethodAsLongTest {

  private SQLMethodAsLong method;

  @Before
  public void setup() {
    method = new SQLMethodAsLong();
  }

  @Test
  public void nullInputReturnsNull() {
    // Null propagates.
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void numberInputCoercedViaLongValue() {
    assertEquals(Long.valueOf(42L), method.execute(null, null, null, 42, null));
    assertEquals(Long.valueOf(42L), method.execute(null, null, null, 42L, null));
    // Double truncated via longValue().
    assertEquals(Long.valueOf(1L), method.execute(null, null, null, 1.9, null));
  }

  @Test
  public void dateInputReturnsEpochMillis() {
    // Date branch returns getTime() directly — no formatting.
    var date = new Date(1_000L);
    assertEquals(Long.valueOf(1_000L), method.execute(null, null, null, date, null));
    var epoch = new Date(0L);
    assertEquals(Long.valueOf(0L), method.execute(null, null, null, epoch, null));
  }

  @Test
  public void stringInputTrimmedAndParsed() {
    // Fallback path: toString().trim() → Long.valueOf.
    assertEquals(Long.valueOf(42L), method.execute(null, null, null, "42", null));
    assertEquals(Long.valueOf(42L), method.execute(null, null, null, "  42  ", null));
    assertEquals(Long.valueOf(Long.MIN_VALUE),
        method.execute(null, null, null, String.valueOf(Long.MIN_VALUE), null));
  }

  @Test
  public void nonNumericStringThrowsNumberFormatException() {
    try {
      method.execute(null, null, null, "abc", null);
      fail("Expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // expected
    }
  }
}
