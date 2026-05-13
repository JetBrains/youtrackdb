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

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>size()</code> method. Dispatches on input type:
 * <ul>
 *   <li>null → 0</li>
 *   <li>{@link com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable} → 1
 *       (a single RID-referenced record counts as one)</li>
 *   <li>otherwise →
 *       {@link com.jetbrains.youtrackdb.internal.common.collection.MultiValue#getSize}</li>
 * </ul>
 */
public class SQLMethodSizeTest {

  private SQLMethodSize method;

  @Before
  public void setup() {
    method = new SQLMethodSize();
  }

  @Test
  public void nullInputReturnsZero() {
    // Null → 0 (not null — Size is a scalar count).
    assertEquals(0, ((Number) method.execute(null, null, null, null, null)).intValue());
  }

  @Test
  public void identifiableReturnsOne() {
    // Single record reference counts as 1, regardless of cluster id / position.
    RID rid = new RecordId(10, 5);
    assertEquals(1, ((Number) method.execute(null, null, null, rid, null)).intValue());
  }

  @Test
  public void emptyCollectionReturnsZero() {
    assertEquals(0,
        ((Number) method.execute(null, null, null, Collections.emptyList(), null)).intValue());
  }

  @Test
  public void listSizeReflectsElementCount() {
    var list = Arrays.asList("a", "b", "c");
    assertEquals(3, ((Number) method.execute(null, null, null, list, null)).intValue());
  }

  @Test
  public void mapSizeReflectsEntryCount() {
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    assertEquals(2, ((Number) method.execute(null, null, null, map, null)).intValue());
  }

  @Test
  public void arraySizeReflectsLength() {
    assertEquals(4, ((Number) method.execute(null, null, null, new int[] {1, 2, 3, 4}, null))
        .intValue());
  }

  @Test
  public void stringInputDelegatesToMultiValueSizeReturnsZero() {
    // MultiValue.getSize returns 0 for any non-multi-value input (a plain String counts as a
    // scalar, not a collection). This is documented MultiValue behaviour.
    assertEquals(0, ((Number) method.execute(null, null, null, "scalar", null)).intValue());
  }
}
