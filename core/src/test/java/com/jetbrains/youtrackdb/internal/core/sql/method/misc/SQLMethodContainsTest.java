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
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>contains()</code> method. This method delegates to
 * {@link com.jetbrains.youtrackdb.internal.common.collection.MultiValue#contains}.
 *
 * <p><strong>WHEN-FIXED</strong>: SQLMethodContains has a dead-branch bug — the guard reads
 * <code>if (iParams == null &amp;&amp; iParams.length != 1)</code>. The <code>&amp;&amp;</code>
 * short-circuits on the null case, so <em>both</em> operands must be true for the guard to
 * fire, which is impossible (a null <code>iParams</code> can't have a length). The guard is
 * therefore never executed; invalid params trigger NPE/AIOBE at
 * <code>iParams[0]</code> immediately after. This test pins the current observable behaviour
 * (NPE on null params, AIOBE on empty array). When the bug is fixed (<code>&amp;&amp;</code>
 * → <code>||</code>), this test must be rewritten to assert the method returns false instead.
 */
public class SQLMethodContainsTest {

  private SQLMethodContains method;

  @Before
  public void setup() {
    method = new SQLMethodContains();
  }

  @Test
  public void collectionContainsElement() {
    var list = Arrays.asList("a", "b", "c");
    assertEquals(Boolean.TRUE, method.execute(list, null, null, null, new Object[] {"b"}));
  }

  @Test
  public void collectionDoesNotContainElement() {
    var list = Arrays.asList("a", "b", "c");
    assertEquals(Boolean.FALSE, method.execute(list, null, null, null, new Object[] {"x"}));
  }

  @Test
  public void emptyCollectionNeverContains() {
    assertEquals(Boolean.FALSE,
        method.execute(Collections.emptyList(), null, null, null, new Object[] {"a"}));
  }

  @Test
  public void scalarComparedAsEquality() {
    // MultiValue.contains on a non-multi-value falls back to equals().
    assertEquals(Boolean.TRUE, method.execute("hello", null, null, null, new Object[] {"hello"}));
    assertEquals(Boolean.FALSE, method.execute("hello", null, null, null, new Object[] {"other"}));
  }

  @Test
  public void nullParamsTriggersNullPointerExceptionDueToBug() {
    // WHEN-FIXED: after &&→|| fix, this should return false instead of throwing NPE.
    try {
      method.execute("ignored", null, null, null, null);
      fail("Expected NullPointerException due to broken guard (WHEN-FIXED)");
    } catch (NullPointerException expected) {
      // expected — the broken && guard means null iParams is not caught.
    }
  }

  @Test
  public void emptyParamsArrayTriggersAioobeDueToBug() {
    // WHEN-FIXED: after &&→|| fix, this should return false instead of throwing AIOBE.
    try {
      method.execute("ignored", null, null, null, new Object[] {});
      fail("Expected ArrayIndexOutOfBoundsException due to broken guard (WHEN-FIXED)");
    } catch (ArrayIndexOutOfBoundsException expected) {
      // expected — the broken && guard means zero-length iParams is not caught.
    }
  }
}
