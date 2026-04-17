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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>type()</code> method. This method returns the YouTrackDB
 * {@link PropertyTypeInternal} name for the value via
 * {@link PropertyTypeInternal#getTypeByValue}. Tests cover null propagation, primitive wrappers,
 * date/array/collection types, and a value whose Java class does not map to any property type
 * (returns null).
 */
public class SQLMethodTypeTest {

  private SQLMethodType method;

  @Before
  public void setup() {
    method = new SQLMethodType();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void stringReturnsStringType() {
    assertEquals(PropertyTypeInternal.STRING.toString(),
        method.execute(null, null, null, "abc", null));
  }

  @Test
  public void integerReturnsIntegerType() {
    assertEquals(PropertyTypeInternal.INTEGER.toString(),
        method.execute(null, null, null, 1, null));
  }

  @Test
  public void longReturnsLongType() {
    assertEquals(PropertyTypeInternal.LONG.toString(),
        method.execute(null, null, null, 1L, null));
  }

  @Test
  public void doubleReturnsDoubleType() {
    assertEquals(PropertyTypeInternal.DOUBLE.toString(),
        method.execute(null, null, null, 1.5, null));
  }

  @Test
  public void booleanReturnsBooleanType() {
    assertEquals(PropertyTypeInternal.BOOLEAN.toString(),
        method.execute(null, null, null, Boolean.TRUE, null));
  }

  @Test
  public void dateReturnsDateTimeType() {
    // java.util.Date is classified as DATETIME, not DATE.
    assertEquals(PropertyTypeInternal.DATETIME.toString(),
        method.execute(null, null, null, new Date(), null));
  }

  @Test
  public void collectionReturnsEmbeddedListType() {
    var result = method.execute(null, null, null, new ArrayList<>(), null);
    assertNotNull(result);
    assertEquals(PropertyTypeInternal.EMBEDDEDLIST.toString(), result);
  }

  @Test
  public void mapReturnsEmbeddedMapType() {
    assertEquals(PropertyTypeInternal.EMBEDDEDMAP.toString(),
        method.execute(null, null, null, new HashMap<>(), null));
  }
}
