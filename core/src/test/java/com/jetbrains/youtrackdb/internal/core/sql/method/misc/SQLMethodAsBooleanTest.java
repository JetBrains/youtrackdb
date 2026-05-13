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
import static org.junit.Assert.assertSame;

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
    // Boolean is not a Number nor String, so the method returns the SAME reference unchanged.
    // assertSame pins the identity-branch (no allocation of a fresh Boolean).
    assertSame(Boolean.TRUE, method.execute(null, null, null, Boolean.TRUE, null));
    assertSame(Boolean.FALSE, method.execute(null, null, null, Boolean.FALSE, null));
  }

  @Test
  public void bigDecimalZeroAndFractionalLessThanOneAreFalse() {
    // BigDecimal is a Number — intValue() truncates. BigDecimal.ZERO → 0 → false.
    // BigDecimal("0.99") → intValue=0 → false (surprising to callers expecting 0.99 → true).
    assertEquals(Boolean.FALSE,
        method.execute(null, null, null, java.math.BigDecimal.ZERO, null));
    assertEquals(Boolean.FALSE,
        method.execute(null, null, null, new java.math.BigDecimal("0.99"), null));
    assertEquals(Boolean.TRUE,
        method.execute(null, null, null, new java.math.BigDecimal("1.01"), null));
  }

  @Test
  public void stringWithInnerWhitespaceTrimmedButStillNotTrue() {
    // Boolean.valueOf("true extra") → false. The trim only strips leading/trailing whitespace;
    // any trailing non-whitespace chars after "true" invalidate the match.
    assertEquals(Boolean.FALSE,
        method.execute(null, null, null, " true extra ", null));
  }
}
