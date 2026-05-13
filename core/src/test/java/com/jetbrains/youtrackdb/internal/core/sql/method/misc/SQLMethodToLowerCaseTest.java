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
 * Tests the SQL <code>toLowerCase()</code> method. Uses {@link java.util.Locale#ENGLISH} so
 * locale-specific case mappings (e.g. Turkish dotted/dotless I) are not triggered; tests cover
 * null propagation, mixed-case input, already-lowercase strings, and numeric input (toString'd).
 */
public class SQLMethodToLowerCaseTest {

  private SQLMethodToLowerCase method;

  @Before
  public void setup() {
    method = new SQLMethodToLowerCase();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void uppercaseBecomesLowercase() {
    assertEquals("hello world", method.execute(null, null, null, "HELLO WORLD", null));
  }

  @Test
  public void mixedCaseBecomesLowercase() {
    assertEquals("hello", method.execute(null, null, null, "HeLLo", null));
  }

  @Test
  public void alreadyLowercaseIsUnchanged() {
    assertEquals("abc", method.execute(null, null, null, "abc", null));
  }

  @Test
  public void emptyStringReturnsEmpty() {
    assertEquals("", method.execute(null, null, null, "", null));
  }

  @Test
  public void nonLetterCharactersPreserved() {
    assertEquals("abc-123!", method.execute(null, null, null, "ABC-123!", null));
  }

  @Test
  public void numberInputToStringThenLowercased() {
    assertEquals("42", method.execute(null, null, null, 42, null));
  }

  @Test
  public void localeEnglishNotTurkish() {
    // Under ENGLISH locale, capital I lowercases to ASCII 'i', not the dotless Turkish 'ı'.
    assertEquals("i", method.execute(null, null, null, "I", null));
  }
}
