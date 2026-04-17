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

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>split()</code> method. Wraps {@link String#split} and wraps the result
 * in an immutable {@link List}. The method's guard returns the original <code>iThis</code>
 * unchanged when <code>iThis</code> or the delimiter param is null.
 */
public class SQLMethodSplitTest {

  private SQLMethodSplit method;

  @Before
  public void setup() {
    method = new SQLMethodSplit();
  }

  @Test
  public void nullThisReturnsNull() {
    // When iThis is null, short-circuit returns null (no split attempted).
    assertNull(method.execute(null, null, null, null, new Object[] {","}));
  }

  @Test
  public void nullDelimiterReturnsIThisUnchanged() {
    // Null delimiter param returns iThis unchanged (identity pass-through).
    var input = "a,b,c";
    assertSame(input, method.execute(input, null, null, null, new Object[] {null}));
  }

  @Test
  public void commaDelimiterProducesList() {
    assertEquals(List.of("a", "b", "c"),
        method.execute("a,b,c", null, null, null, new Object[] {","}));
  }

  @Test
  public void regexDelimiterSupported() {
    // The delimiter is a regex (String.split contract).
    assertEquals(List.of("a", "b", "c"),
        method.execute("a1b2c", null, null, null, new Object[] {"\\d"}));
  }

  @Test
  public void trailingEmptyStringsDroppedByDefault() {
    // String.split with default limit discards trailing empty strings.
    assertEquals(List.of("a", "b"),
        method.execute("a,b,,", null, null, null, new Object[] {","}));
  }

  @Test
  public void noDelimiterMatchReturnsSingletonList() {
    assertEquals(List.of("abc"),
        method.execute("abc", null, null, null, new Object[] {","}));
  }

  @Test
  public void numberInputToStringThenSplit() {
    // Non-String iThis is toString'd before split.
    assertEquals(List.of("1", "2", "3"),
        method.execute("1-2-3", null, null, null, new Object[] {"-"}));
  }
}
