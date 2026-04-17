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
package com.jetbrains.youtrackdb.internal.core.sql.method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>charAt()</code> method. Returns the character at the given index as a
 * single-character string. Covers happy-path indexing, null first-param, and out-of-range error
 * paths (NumberFormatException / StringIndexOutOfBoundsException / NullPointerException).
 */
public class SQLMethodCharAtTest {

  private SQLMethodCharAt method;

  @Before
  public void setup() {
    method = new SQLMethodCharAt();
  }

  @Test
  public void returnsCharacterAtZero() {
    assertEquals("f", method.execute("foo", null, null, null, new Object[] {0}));
  }

  @Test
  public void returnsCharacterAtMiddleIndex() {
    assertEquals("o", method.execute("foo", null, null, null, new Object[] {1}));
  }

  @Test
  public void returnsCharacterAtLastIndex() {
    assertEquals("o", method.execute("foo", null, null, null, new Object[] {2}));
  }

  @Test
  public void nullFirstParamReturnsNull() {
    // Short-circuit guard: iParams[0] == null returns null early.
    assertNull(method.execute("foo", null, null, null, new Object[] {null}));
  }

  @Test
  public void stringIndexParsedFromString() {
    // Integer.parseInt accepts string numerics.
    assertEquals("o", method.execute("foo", null, null, null, new Object[] {"1"}));
  }

  @Test
  public void indexOutOfRangeThrows() {
    try {
      method.execute("foo", null, null, null, new Object[] {10});
      fail("Expected StringIndexOutOfBoundsException");
    } catch (StringIndexOutOfBoundsException expected) {
      // expected — delegated from String.charAt
    }
  }

  @Test
  public void negativeIndexThrows() {
    try {
      method.execute("foo", null, null, null, new Object[] {-1});
      fail("Expected StringIndexOutOfBoundsException");
    } catch (StringIndexOutOfBoundsException expected) {
      // expected
    }
  }

  @Test
  public void nonNumericIndexThrows() {
    try {
      method.execute("foo", null, null, null, new Object[] {"abc"});
      fail("Expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // expected
    }
  }

  @Test
  public void nullIThisThrowsNpe() {
    // The method does not guard null iThis before .toString() — documented behaviour.
    try {
      method.execute(null, null, null, null, new Object[] {0});
      fail("Expected NullPointerException");
    } catch (NullPointerException expected) {
      // expected — iThis.toString() on null.
    }
  }
}
