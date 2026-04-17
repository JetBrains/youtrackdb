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
 * Tests the SQL <code>asString()</code> method. The method is a thin wrapper around
 * <code>Object.toString()</code>; this test verifies null propagation, the identity case
 * for a String input, and coercion of primitive and object inputs.
 */
public class SQLMethodAsStringTest {

  private SQLMethodAsString method;

  @Before
  public void setup() {
    method = new SQLMethodAsString();
  }

  @Test
  public void nullInputReturnsNull() {
    // Null is preserved — no empty string coercion.
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void stringInputReturnsIdenticalReference() {
    // Optimisation check: String.toString() returns this, so the same reference is returned.
    var input = "hello";
    assertSame(input, method.execute(null, null, null, input, null));
  }

  @Test
  public void emptyStringPreserved() {
    assertEquals("", method.execute(null, null, null, "", null));
  }

  @Test
  public void numberInputFormattedViaToString() {
    // Integer.toString → "42".
    assertEquals("42", method.execute(null, null, null, 42, null));
    // Double.toString uses minimal representation.
    assertEquals("1.5", method.execute(null, null, null, 1.5, null));
  }

  @Test
  public void booleanInputFormattedViaToString() {
    assertEquals("true", method.execute(null, null, null, Boolean.TRUE, null));
    assertEquals("false", method.execute(null, null, null, Boolean.FALSE, null));
  }

  @Test
  public void arbitraryObjectUsesItsOwnToString() {
    // Custom toString() is honoured — the method defers entirely to Object.toString().
    var custom = new Object() {
      @Override
      public String toString() {
        return "custom-rep";
      }
    };
    assertEquals("custom-rep", method.execute(null, null, null, custom, null));
  }
}
