/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionCoalesce} — returns the first non-null argument. Standalone.
 */
public class SQLFunctionCoalesceTest {

  @Test
  public void firstNonNullIsReturned() {
    final var fn = new SQLFunctionCoalesce();
    assertEquals("a",
        fn.execute(null, null, null, new Object[] {"a", "b"}, new BasicCommandContext()));
  }

  @Test
  public void firstNullSecondWinsAndShortCircuits() {
    final var fn = new SQLFunctionCoalesce();
    // Use a sentinel object to verify identity (first match returned, not a copy).
    final var sentinel = new Object();
    assertSame(sentinel,
        fn.execute(null, null, null, new Object[] {null, sentinel, "later"},
            new BasicCommandContext()));
  }

  @Test
  public void allNullsReturnsNull() {
    final var fn = new SQLFunctionCoalesce();
    assertNull(fn.execute(null, null, null, new Object[] {null, null, null},
        new BasicCommandContext()));
  }

  @Test
  public void singleNonNullParameterWorks() {
    // minParams=1 — single-arg invocation is valid.
    final var fn = new SQLFunctionCoalesce();
    assertEquals(42,
        fn.execute(null, null, null, new Object[] {42}, new BasicCommandContext()));
  }

  @Test
  public void singleNullParameterReturnsNull() {
    final var fn = new SQLFunctionCoalesce();
    assertNull(fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext()));
  }

  @Test
  public void emptyParameterArrayReturnsNull() {
    // length=0 is an invalid dispatch (minParams=1), but the body tolerates it via empty for-loop.
    // Drift guard: if a future guard rejects length==0, this test changes with intent.
    final var fn = new SQLFunctionCoalesce();
    assertNull(fn.execute(null, null, null, new Object[] {}, new BasicCommandContext()));
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionCoalesce();
    assertEquals("coalesce", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(1000, fn.getMaxParams(null));
    assertEquals(
        "Returns the first not-null parameter or null if all parameters are null. Syntax:"
            + " coalesce(<field|value> [,<field|value>]*)",
        fn.getSyntax(null));
  }
}
