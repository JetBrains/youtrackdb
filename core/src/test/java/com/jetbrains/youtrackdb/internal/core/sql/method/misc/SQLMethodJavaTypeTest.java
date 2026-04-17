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

import java.util.ArrayList;
import java.util.Date;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>javaType()</code> method. This method returns the fully-qualified runtime
 * class name of the ioResult argument. Tests cover null propagation and a range of primitive
 * wrapper and concrete collection types.
 */
public class SQLMethodJavaTypeTest {

  private SQLMethodJavaType method;

  @Before
  public void setup() {
    method = new SQLMethodJavaType();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void stringReturnsJavaLangString() {
    assertEquals("java.lang.String", method.execute(null, null, null, "abc", null));
  }

  @Test
  public void integerReturnsJavaLangInteger() {
    assertEquals("java.lang.Integer", method.execute(null, null, null, 1, null));
  }

  @Test
  public void longReturnsJavaLangLong() {
    assertEquals("java.lang.Long", method.execute(null, null, null, 1L, null));
  }

  @Test
  public void doubleReturnsJavaLangDouble() {
    assertEquals("java.lang.Double", method.execute(null, null, null, 1.5, null));
  }

  @Test
  public void booleanReturnsJavaLangBoolean() {
    assertEquals("java.lang.Boolean", method.execute(null, null, null, Boolean.TRUE, null));
  }

  @Test
  public void dateReturnsJavaUtilDate() {
    assertEquals("java.util.Date", method.execute(null, null, null, new Date(), null));
  }

  @Test
  public void arrayListReturnsConcreteClass() {
    // Returns the concrete class, not an interface.
    assertEquals("java.util.ArrayList",
        method.execute(null, null, null, new ArrayList<>(), null));
  }
}
