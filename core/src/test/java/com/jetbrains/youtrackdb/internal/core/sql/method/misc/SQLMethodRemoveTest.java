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
 * Tests the SQL <code>remove()</code> method. Removes the <em>first</em> matching element from
 * a collection (see {@link SQLMethodRemoveAll} for the all-matches variant). Also exercises the
 * <code>$variable</code> resolution branch that reads values from the command context.
 */
public class SQLMethodRemoveTest {

  private SQLMethodRemove method;
  private BasicCommandContext context;

  @Before
  public void setup() {
    method = new SQLMethodRemove();
    context = new BasicCommandContext();
  }

  @Test
  public void nullParamsArrayReturnsIoResultUnchanged() {
    // Guard: null iParams → no removal, ioResult returned as-is.
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
  public void removesFirstMatchingElement() {
    var list = new ArrayList<>(Arrays.asList("a", "b", "c", "b"));
    var result = method.execute(null, null, context, list, new Object[] {"b"});
    // Only the first "b" removed; trailing "b" remains.
    assertEquals(Arrays.asList("a", "c", "b"), result);
  }

  @Test
  public void removesMultipleParamsEachFirstOccurrence() {
    var list = new ArrayList<>(Arrays.asList("a", "b", "c", "b", "a"));
    var result = method.execute(null, null, context, list, new Object[] {"a", "b"});
    // First "a" and first "b" both removed once.
    assertEquals(Arrays.asList("c", "b", "a"), result);
  }

  @Test
  public void variableLookupViaDollarPrefix() {
    // Param "$toRemove" looks up context variable "toRemove".
    context.setVariable("toRemove", "b");
    var list = new ArrayList<>(Arrays.asList("a", "b", "c"));
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
