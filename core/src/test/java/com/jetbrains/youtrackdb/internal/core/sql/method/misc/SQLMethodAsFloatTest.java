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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>asFloat()</code> method. Exercises Number→float coercion, parsing of
 * non-Number values via <code>toString().trim()</code>, null propagation, precision loss for
 * doubles, and the error path for unparsable strings (NumberFormatException).
 */
public class SQLMethodAsFloatTest {

  private SQLMethodAsFloat method;

  @Before
  public void setup() {
    method = new SQLMethodAsFloat();
  }

  @Test
  public void nullInputReturnsNull() {
    // Null input propagates; no Float instantiated.
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void numberInputCoercedViaFloatValue() {
    // Number path uses floatValue() — Integer, Long, Double, BigDecimal all supported.
    assertEquals(42.0f, (Float) method.execute(null, null, null, 42, null), 0.0f);
    assertEquals(42.0f, (Float) method.execute(null, null, null, 42L, null), 0.0f);
    assertEquals(1.5f, (Float) method.execute(null, null, null, 1.5, null), 0.0f);
    assertEquals(-3.25f, (Float) method.execute(null, null, null, -3.25f, null), 0.0f);
  }

  @Test
  public void stringInputTrimmedAndParsed() {
    // Non-Number: toString().trim() → Float.valueOf; whitespace is tolerated.
    assertEquals(1.5f, (Float) method.execute(null, null, null, "1.5", null), 0.0f);
    assertEquals(1.5f, (Float) method.execute(null, null, null, "  1.5  ", null), 0.0f);
    // Scientific notation supported by Float parser.
    assertEquals(1.0e3f, (Float) method.execute(null, null, null, "1e3", null), 0.0f);
  }

  @Test
  public void largeDoublesCoerceToPositiveInfinity() {
    // Double.MAX_VALUE exceeds Float range → specifically POSITIVE_INFINITY, not negative.
    var result = (Float) method.execute(null, null, null, Double.MAX_VALUE, null);
    assertEquals(Float.POSITIVE_INFINITY, result, 0.0f);
  }

  @Test
  public void largeNegativeDoubleCoercesToNegativeInfinity() {
    var result = (Float) method.execute(null, null, null, -Double.MAX_VALUE, null);
    assertEquals(Float.NEGATIVE_INFINITY, result, 0.0f);
  }

  @Test
  public void bigDecimalCoercedViaFloatValue() {
    // BigDecimal is a Number — floatValue() is a pure function, so the result is exactly
    // determined by BigDecimal.floatValue's documented IEEE-754 single-precision rounding.
    // Pin with delta=0.0f (exact-match): anything looser would accept any nearby float and
    // weaken falsifiability. The previous 1e-6f delta was ~100× larger than any plausible
    // implementation drift.
    var bd = new BigDecimal("3.14159265358979323846");
    assertEquals(
        bd.floatValue(), (Float) method.execute(null, null, null, bd, null), 0.0f);
  }

  @Test
  public void specialDoubleStringsParsedAsNaNAndInfinity() {
    // Float.valueOf accepts "NaN", "Infinity", "-Infinity" per Double.parseDouble contract.
    assertTrue(Float.isNaN((Float) method.execute(null, null, null, "NaN", null)));
    assertEquals(Float.POSITIVE_INFINITY,
        (Float) method.execute(null, null, null, "Infinity", null), 0.0f);
    assertEquals(Float.NEGATIVE_INFINITY,
        (Float) method.execute(null, null, null, "-Infinity", null), 0.0f);
  }

  @Test
  public void whitespaceOnlyStringThrowsNumberFormatException() {
    // trim() leaves "" which Float.valueOf rejects; there's no empty-check short-circuit.
    try {
      method.execute(null, null, null, "   ", null);
      fail("Expected NumberFormatException for whitespace-only string");
    } catch (NumberFormatException expected) {
      // expected
    }
  }

  @Test
  public void unparsableStringThrowsNumberFormatException() {
    try {
      method.execute(null, null, null, "not-a-float", null);
      fail("Expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // expected — unparsable strings surface as NumberFormatException.
    }
  }
}
