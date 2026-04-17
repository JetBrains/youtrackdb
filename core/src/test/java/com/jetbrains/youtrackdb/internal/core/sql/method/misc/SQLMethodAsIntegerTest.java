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

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>asInteger()</code> method. Exercises Number→int narrowing (including
 * overflow and fractional truncation), string parsing with trimming, null propagation, and
 * the error path for non-integer strings (e.g. "1.5" or "abc").
 */
public class SQLMethodAsIntegerTest {

  private SQLMethodAsInteger method;

  @Before
  public void setup() {
    method = new SQLMethodAsInteger();
  }

  @Test
  public void nullInputReturnsNull() {
    // Null propagates without attempting coercion.
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void numberInputCoercedViaIntValue() {
    // Number path uses intValue() — truncates fractional part for floats/doubles.
    assertEquals(Integer.valueOf(42), method.execute(null, null, null, 42, null));
    assertEquals(Integer.valueOf(42), method.execute(null, null, null, 42L, null));
    assertEquals(Integer.valueOf(1), method.execute(null, null, null, 1.9, null));
    assertEquals(Integer.valueOf(-1), method.execute(null, null, null, -1.9, null));
  }

  @Test
  public void longOverflowWrapsAroundSilently() {
    // Long.MAX_VALUE truncated via intValue() — loses high bits, yields -1 (documented JDK behaviour).
    assertEquals(Integer.valueOf(-1), method.execute(null, null, null, Long.MAX_VALUE, null));
  }

  @Test
  public void stringInputTrimmedAndParsed() {
    // Non-Number: toString().trim() → Integer.valueOf.
    assertEquals(Integer.valueOf(7), method.execute(null, null, null, "7", null));
    assertEquals(Integer.valueOf(7), method.execute(null, null, null, "  7  ", null));
    assertEquals(Integer.valueOf(-42), method.execute(null, null, null, "-42", null));
  }

  @Test
  public void fractionalStringThrowsNumberFormatException() {
    // Integer.valueOf rejects decimal points.
    try {
      method.execute(null, null, null, "1.5", null);
      fail("Expected NumberFormatException for fractional string");
    } catch (NumberFormatException expected) {
      // expected
    }
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

  @Test
  public void bigDecimalTruncatedViaIntValue() {
    // BigDecimal is a Number — intValue() truncates both the fractional part and any
    // magnitude beyond 32 bits.
    assertEquals(Integer.valueOf(3),
        method.execute(null, null, null, new BigDecimal("3.9"), null));
    assertEquals(Integer.valueOf(-3),
        method.execute(null, null, null, new BigDecimal("-3.9"), null));
  }

  @Test
  public void bigIntegerAt2To32WrapsToZero() {
    // BigInteger.intValue() takes only the low 32 bits. 2^32 → 0.
    var bi = BigInteger.ONE.shiftLeft(32);
    assertEquals(Integer.valueOf(0), method.execute(null, null, null, bi, null));
  }

  @Test
  public void integerBoundariesParsedFromString() {
    assertEquals(Integer.valueOf(Integer.MAX_VALUE),
        method.execute(null, null, null, String.valueOf(Integer.MAX_VALUE), null));
    assertEquals(Integer.valueOf(Integer.MIN_VALUE),
        method.execute(null, null, null, String.valueOf(Integer.MIN_VALUE), null));
  }

  @Test
  public void whitespaceOnlyStringThrowsNumberFormatException() {
    try {
      method.execute(null, null, null, "   ", null);
      fail("Expected NumberFormatException for whitespace-only string");
    } catch (NumberFormatException expected) {
      // expected
    }
  }
}
