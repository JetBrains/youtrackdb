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
 * Tests the SQL <code>left()</code> method. Returns the first N characters of a string,
 * clamped to the string's length. Covers happy paths, null propagation, and the error path
 * for a negative or non-numeric length.
 */
public class SQLMethodLeftTest {

  private SQLMethodLeft method;

  @Before
  public void setup() {
    method = new SQLMethodLeft();
  }

  @Test
  public void returnsLeftmostNCharacters() {
    assertEquals("foo", method.execute("foobar", null, null, null, new Object[] {3}));
  }

  @Test
  public void lengthZeroReturnsEmptyString() {
    assertEquals("", method.execute("foobar", null, null, null, new Object[] {0}));
  }

  @Test
  public void lengthEqualsStringLengthReturnsWholeString() {
    assertEquals("foobar", method.execute("foobar", null, null, null, new Object[] {6}));
  }

  @Test
  public void lengthExceedingStringLengthClamped() {
    // Length > string length is silently clamped to the string's length.
    assertEquals("foobar", method.execute("foobar", null, null, null, new Object[] {100}));
  }

  @Test
  public void nullThisReturnsNull() {
    // Guard: iThis == null → null (no substring attempt).
    assertNull(method.execute(null, null, null, null, new Object[] {3}));
  }

  @Test
  public void nullFirstParamReturnsNull() {
    // Guard: iParams[0] == null → null.
    assertNull(method.execute("foo", null, null, null, new Object[] {null}));
  }

  @Test
  public void stringLengthParsedFromString() {
    // Integer.parseInt accepts string numerics for the length arg.
    assertEquals("fo", method.execute("foobar", null, null, null, new Object[] {"2"}));
  }

  @Test
  public void negativeLengthThrows() {
    // Negative length is not clamped — substring(0, -1) throws.
    try {
      method.execute("foobar", null, null, null, new Object[] {-1});
      fail("Expected StringIndexOutOfBoundsException for negative length");
    } catch (StringIndexOutOfBoundsException expected) {
      // expected
    }
  }

  @Test
  public void nonNumericLengthThrows() {
    try {
      method.execute("foobar", null, null, null, new Object[] {"abc"});
      fail("Expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // expected
    }
  }

  @Test
  public void numericIThisCoercedToString() {
    // iThis is toString'd before substring — numbers work.
    assertEquals("12", method.execute(12345, null, null, null, new Object[] {2}));
  }
}
