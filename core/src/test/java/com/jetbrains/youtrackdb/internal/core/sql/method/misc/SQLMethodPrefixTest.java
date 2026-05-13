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
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>prefix()</code> method. Prepends a prefix string to a value. Covers null
 * propagation for both arguments and non-String prefix coercion.
 */
public class SQLMethodPrefixTest {

  private SQLMethodPrefix method;

  @Before
  public void setup() {
    method = new SQLMethodPrefix();
  }

  @Test
  public void nullThisReturnsNull() {
    // Null target → null (the method returns iThis when it is null).
    assertNull(method.execute(null, null, null, null, new Object[] {"prefix-"}));
  }

  @Test
  public void nullPrefixReturnsThisUnchanged() {
    // Null prefix → method returns iThis unchanged.
    var input = "value";
    assertSame(input, method.execute(input, null, null, null, new Object[] {null}));
  }

  @Test
  public void stringPrefixConcatenatedBeforeValue() {
    assertEquals("pre-value", method.execute("value", null, null, null, new Object[] {"pre-"}));
  }

  @Test
  public void emptyPrefixReturnsValueUnchanged() {
    assertEquals("value", method.execute("value", null, null, null, new Object[] {""}));
  }

  @Test
  public void numberPrefixCoerced() {
    // Non-String prefix is concatenated via + operator → uses toString.
    assertEquals("42value", method.execute("value", null, null, null, new Object[] {42}));
  }

  @Test
  public void numberThisCoercedToString() {
    // iThis is toString'd before concatenation.
    assertEquals("prefix-42",
        method.execute(42, null, null, null, new Object[] {"prefix-"}));
  }

  @Test
  public void nullParamsArrayThrowsNpe() {
    // The method indexes iParams[0] with no null-array guard (arity is declared as (NAME, 1)
    // so a well-formed call always supplies the array). Pin current NPE behaviour.
    try {
      method.execute("value", null, null, null, null);
      fail("Expected NullPointerException — iParams is indexed without a null guard");
    } catch (NullPointerException expected) {
      // expected
    }
  }
}
