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

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>indexOf()</code> method. Wraps {@link String#indexOf}; the search term is
 * run through {@link com.jetbrains.youtrackdb.internal.common.io.IOUtils#getStringContent} so
 * surrounding quotes are stripped. The optional second parameter is the start index.
 */
public class SQLMethodIndexOfTest {

  private SQLMethodIndexOf method;

  @Before
  public void setup() {
    method = new SQLMethodIndexOf();
  }

  @Test
  public void nullThisReturnsNull() {
    // Null target string → null result (method guards with a ternary).
    assertNull(method.execute(null, null, null, null, new Object[] {"a"}));
  }

  @Test
  public void substringFoundAtStart() {
    assertEquals(Integer.valueOf(0),
        method.execute("foobar", null, null, null, new Object[] {"foo"}));
  }

  @Test
  public void substringFoundInMiddle() {
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"bar"}));
  }

  @Test
  public void substringNotFoundReturnsMinusOne() {
    assertEquals(Integer.valueOf(-1),
        method.execute("foobar", null, null, null, new Object[] {"xyz"}));
  }

  @Test
  public void startIndexHonored() {
    // "oo" appears at index 1; start=2 skips it, returning -1.
    assertEquals(Integer.valueOf(-1),
        method.execute("foobar", null, null, null, new Object[] {"oo", 2}));
    // Searching for "bar" starting at 0 → 3.
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"bar", 0}));
    // Searching for "bar" starting at 4 → -1.
    assertEquals(Integer.valueOf(-1),
        method.execute("foobar", null, null, null, new Object[] {"bar", 4}));
  }

  @Test
  public void quotedSubstringStripped() {
    // IOUtils.getStringContent removes surrounding double quotes — searches for "bar", not "\"bar\"".
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"\"bar\""}));
    // Same for single quotes.
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"'bar'"}));
  }

  @Test
  public void startIndexBeyondLengthReturnsMinusOne() {
    // Start index past end → no match.
    assertEquals(Integer.valueOf(-1),
        method.execute("foo", null, null, null, new Object[] {"f", 10}));
  }

  @Test
  public void negativeStartIndexTreatedAsZero() {
    // String.indexOf accepts negative indexes as 0.
    assertEquals(Integer.valueOf(0),
        method.execute("foobar", null, null, null, new Object[] {"foo", -5}));
  }

  @Test
  public void nullFirstParamThrowsNpe() {
    // Production: iParams[0].toString() is called unconditionally. Pin the current
    // unchecked-NPE behaviour so a future guard shift is detected.
    try {
      method.execute("foobar", null, null, null, new Object[] {null});
      fail("Expected NullPointerException — no null-guard on iParams[0]");
    } catch (NullPointerException expected) {
      // expected
    }
  }
}
