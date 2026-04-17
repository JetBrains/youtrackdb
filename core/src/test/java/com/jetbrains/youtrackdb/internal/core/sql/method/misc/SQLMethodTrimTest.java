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

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>trim()</code> method. Wraps {@link String#trim} over a
 * <code>toString()</code> call; covers null propagation, whitespace-only strings,
 * mixed-whitespace strings, and non-String input (which is toString'd first).
 */
public class SQLMethodTrimTest {

  private SQLMethodTrim method;

  @Before
  public void setup() {
    method = new SQLMethodTrim();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void leadingTrailingWhitespaceIsRemoved() {
    assertEquals("hello", method.execute(null, null, null, "  hello  ", null));
  }

  @Test
  public void internalWhitespacePreserved() {
    assertEquals("a b c", method.execute(null, null, null, "  a b c  ", null));
  }

  @Test
  public void tabsAndNewlinesRemoved() {
    // String.trim removes any char with value ≤ U+0020, including \t and \n.
    assertEquals("x", method.execute(null, null, null, "\t\n x \r\n", null));
  }

  @Test
  public void whitespaceOnlyReturnsEmpty() {
    assertEquals("", method.execute(null, null, null, "   ", null));
  }

  @Test
  public void alreadyTrimmedStringIsUnchanged() {
    assertEquals("hello", method.execute(null, null, null, "hello", null));
  }

  @Test
  public void numberInputToStringThenTrim() {
    // Integer.toString has no whitespace, result equals the number as string.
    assertEquals("42", method.execute(null, null, null, 42, null));
  }
}
