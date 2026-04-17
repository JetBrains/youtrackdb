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
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>lastIndexOf()</code> method. Wraps {@link String#lastIndexOf}; the
 * search term goes through
 * {@link com.jetbrains.youtrackdb.internal.common.io.IOUtils#getStringContent} so surrounding
 * quotes are stripped. With a second parameter, the search is bounded by that index.
 */
public class SQLMethodLastIndexOfTest {

  private SQLMethodLastIndexOf method;

  @Before
  public void setup() {
    method = new SQLMethodLastIndexOf();
  }

  @Test
  public void substringFoundReturnsLastOccurrence() {
    // "o" appears at 1 and 2 → last is 2.
    assertEquals(Integer.valueOf(2),
        method.execute("foobar", null, null, null, new Object[] {"o"}));
    // Distinct substring → only occurrence.
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"bar"}));
  }

  @Test
  public void substringNotFoundReturnsMinusOne() {
    assertEquals(Integer.valueOf(-1),
        method.execute("foobar", null, null, null, new Object[] {"xyz"}));
  }

  @Test
  public void boundedLastIndexOf() {
    // "foobarfoo" — "foo" appears at 0 and 6. Bounded at index 5 returns 0.
    assertEquals(Integer.valueOf(0),
        method.execute("foobarfoo", null, null, null, new Object[] {"foo", 5}));
    // Bounded at 10 (past end) → returns the last occurrence, 6.
    assertEquals(Integer.valueOf(6),
        method.execute("foobarfoo", null, null, null, new Object[] {"foo", 10}));
  }

  @Test
  public void quotedSubstringStripped() {
    // Surrounding double quotes removed before search.
    assertEquals(Integer.valueOf(3),
        method.execute("foobar", null, null, null, new Object[] {"\"bar\""}));
  }

  @Test
  public void emptyStringMatchesEndIndex() {
    // Java contract: "abc".lastIndexOf("") == 3 (length of the string).
    assertEquals(Integer.valueOf(3),
        method.execute("abc", null, null, null, new Object[] {""}));
  }

  @Test
  public void nullThisThrowsNpeDueToMissingGuard() {
    // WHEN-FIXED: SQLMethodLastIndexOf lacks the null-iThis guard that its sibling
    // SQLMethodIndexOf has (see SQLMethodIndexOf.execute return-ternary). The asymmetry
    // is a latent bug. When the symmetric guard is added, replace with assertNull.
    try {
      method.execute(null, null, null, null, new Object[] {"a"});
      fail("Expected NullPointerException — SQLMethodLastIndexOf does not null-guard iThis");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void nullFirstParamThrowsNpe() {
    // iParams[0].toString() is called unconditionally — null first param NPEs.
    try {
      method.execute("foobar", null, null, null, new Object[] {null});
      fail("Expected NullPointerException — no null-guard on iParams[0]");
    } catch (NullPointerException expected) {
      // expected
    }
  }
}
