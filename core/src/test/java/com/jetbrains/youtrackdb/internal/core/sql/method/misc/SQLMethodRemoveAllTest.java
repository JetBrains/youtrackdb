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
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>removeAll()</code> method. Removes <em>all</em> occurrences of the given
 * elements from a collection. Compare with {@link SQLMethodRemove} which removes only the first
 * occurrence.
 */
public class SQLMethodRemoveAllTest {

  private SQLMethodRemoveAll method;
  private BasicCommandContext context;

  @Before
  public void setup() {
    method = new SQLMethodRemoveAll();
    context = new BasicCommandContext();
  }

  @Test
  public void nullParamsArrayReturnsIoResultUnchanged() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    assertSame(list, method.execute(null, null, context, list, null));
  }

  @Test
  public void emptyParamsArrayReturnsIoResultUnchanged() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    assertSame(list, method.execute(null, null, context, list, new Object[] {}));
  }

  @Test
  public void nullFirstParamReturnsIoResultUnchanged() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    assertSame(list, method.execute(null, null, context, list, new Object[] {null}));
  }

  @Test
  public void removesAllOccurrencesOfElement() {
    var list = new ArrayList<>(Arrays.asList("a", "b", "c", "b", "b"));
    var result = method.execute(null, null, context, list, new Object[] {"b"});
    // All three "b"s gone — contrast with SQLMethodRemove that removes only the first.
    assertEquals(Arrays.asList("a", "c"), result);
  }

  @Test
  public void removesAllMatchesAcrossMultipleParams() {
    var list = new ArrayList<>(Arrays.asList("a", "b", "c", "b", "a", "c"));
    var result = method.execute(null, null, context, list, new Object[] {"a", "b"});
    assertEquals(Arrays.asList("c", "c"), result);
  }

  @Test
  public void variableLookupViaDollarPrefix() {
    context.setVariable("toRemove", "b");
    var list = new ArrayList<>(Arrays.asList("a", "b", "c", "b"));
    var result = method.execute(null, null, context, list, new Object[] {"$toRemove"});
    assertEquals(Arrays.asList("a", "c"), result);
  }

  @Test
  public void unknownElementLeavesCollectionUnchanged() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    var result = method.execute(null, null, context, list, new Object[] {"x"});
    assertEquals(Arrays.asList("a", "b"), result);
  }
}
