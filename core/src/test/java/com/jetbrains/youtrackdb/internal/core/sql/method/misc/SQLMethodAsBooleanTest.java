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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>asBoolean()</code> method. Exercises string parsing (case-insensitive
 * "true"/"false" via {@link Boolean#valueOf}, whitespace trimming), numeric coercion
 * (zero → false, non-zero → true), null propagation, and the identity pass-through branch for
 * values that are neither a {@link String} nor a {@link Number}.
 */
public class SQLMethodAsBooleanTest {

  private SQLMethodAsBoolean method;

  @Before
  public void setup() {
    method = new SQLMethodAsBoolean();
  }

  @Test
  public void nullInputReturnsNull() {
    // Null should propagate — method never coerces null.
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void stringTrueVariantsParseAsTrue() {
    // Boolean.valueOf is case-insensitive and returns true only for literal "true" (trimmed).
    assertEquals(Boolean.TRUE, method.execute(null, null, null, "true", null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, "TRUE", null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, "True", null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, "  true  ", null));
  }

  @Test
  public void stringFalseAndGarbageParseAsFalse() {
    // Boolean.valueOf yields false for anything that is not literal "true".
    assertEquals(Boolean.FALSE, method.execute(null, null, null, "false", null));
    assertEquals(Boolean.FALSE, method.execute(null, null, null, "FALSE", null));
    assertEquals(Boolean.FALSE, method.execute(null, null, null, "not-a-bool", null));
    assertEquals(Boolean.FALSE, method.execute(null, null, null, "", null));
  }

  @Test
  public void numberZeroIsFalseAndNonZeroIsTrue() {
    // Numeric coercion uses intValue(); 0 → false, everything else → true.
    assertEquals(Boolean.FALSE, method.execute(null, null, null, 0, null));
    assertEquals(Boolean.FALSE, method.execute(null, null, null, 0L, null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, 1, null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, -42, null));
    // Doubles with fractional part < 1 truncate to 0 → false.
    assertEquals(Boolean.FALSE, method.execute(null, null, null, 0.4, null));
    assertEquals(Boolean.TRUE, method.execute(null, null, null, 2.5, null));
  }

  @Test
  public void nonStringNonNumberReturnedUnchanged() {
    // An Object that is neither String nor Number is passed through as-is (identity branch).
    var marker = new Object();
    assertSame(marker, method.execute(null, null, null, marker, null));
  }

  @Test
  public void booleanInputPassedThroughUnchanged() {
    // Boolean is not a Number nor String, so the method returns it unchanged.
    assertTrue((Boolean) method.execute(null, null, null, Boolean.TRUE, null));
    assertFalse((Boolean) method.execute(null, null, null, Boolean.FALSE, null));
  }
}
