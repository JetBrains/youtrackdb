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
 * Tests the SQL <code>toUpperCase()</code> method. Uses {@link java.util.Locale#ENGLISH} so
 * locale-specific case mappings are not triggered; tests cover null propagation, mixed-case
 * input, already-uppercase strings, and numeric input (toString'd).
 */
public class SQLMethodToUpperCaseTest {

  private SQLMethodToUpperCase method;

  @Before
  public void setup() {
    method = new SQLMethodToUpperCase();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void lowercaseBecomesUppercase() {
    assertEquals("HELLO WORLD", method.execute(null, null, null, "hello world", null));
  }

  @Test
  public void mixedCaseBecomesUppercase() {
    assertEquals("HELLO", method.execute(null, null, null, "HeLLo", null));
  }

  @Test
  public void alreadyUppercaseIsUnchanged() {
    assertEquals("ABC", method.execute(null, null, null, "ABC", null));
  }

  @Test
  public void emptyStringReturnsEmpty() {
    assertEquals("", method.execute(null, null, null, "", null));
  }

  @Test
  public void nonLetterCharactersPreserved() {
    assertEquals("ABC-123!", method.execute(null, null, null, "abc-123!", null));
  }

  @Test
  public void numberInputToStringThenUppercased() {
    assertEquals("42", method.execute(null, null, null, 42, null));
  }

  @Test
  public void localeEnglishNotTurkish() {
    // Under ENGLISH locale, lowercase 'i' uppercases to 'I', not the dotted Turkish 'İ'.
    assertEquals("I", method.execute(null, null, null, "i", null));
  }
}
