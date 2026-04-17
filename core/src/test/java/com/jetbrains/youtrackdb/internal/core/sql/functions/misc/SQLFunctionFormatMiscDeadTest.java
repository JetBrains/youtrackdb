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

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for the {@code misc.SQLFunctionFormat} class.
 *
 * <p><b>DEAD CODE.</b> {@code DefaultSQLFunctionFactory} imports and registers the
 * {@code text.SQLFunctionFormat} class under the name "format"; this {@code misc} variant with
 * the same {@link SQLFunctionFormat#NAME} = "format" is never instantiated by production code.
 * These tests cover the class so that JaCoCo reflects its true state (executable and correct),
 * but the class itself should be removed.
 *
 * <p>WHEN-FIXED: delete {@code misc.SQLFunctionFormat} entirely and update this test to be
 * deleted alongside it. The {@code text.SQLFunctionFormat} is the canonical implementation and
 * is covered by the Step 8 tests for the {@code text} subpackage.
 */
public class SQLFunctionFormatMiscDeadTest {

  @Test
  public void formatsSingleArg() {
    final var fn = new SQLFunctionFormat();
    final var ctx = new BasicCommandContext();

    assertEquals("hello world",
        fn.execute(null, null, null, new Object[] {"hello %s", "world"}, ctx));
  }

  @Test
  public void formatsMultipleArgs() {
    final var fn = new SQLFunctionFormat();
    final var ctx = new BasicCommandContext();

    assertEquals("a + b = c",
        fn.execute(null, null, null, new Object[] {"%s + %s = %s", "a", "b", "c"}, ctx));
  }

  @Test
  public void formatsIntegerSpecifier() {
    final var fn = new SQLFunctionFormat();
    final var ctx = new BasicCommandContext();

    assertEquals("42",
        fn.execute(null, null, null, new Object[] {"%d", 42}, ctx));
  }

  @Test(expected = NullPointerException.class)
  public void nullFormatStringThrowsNpe() {
    // iParams[0] is hard-cast to String — null triggers a direct NPE.
    final var fn = new SQLFunctionFormat();
    fn.execute(null, null, null, new Object[] {null, "ignored"}, new BasicCommandContext());
  }

  @Test(expected = ClassCastException.class)
  public void nonStringFormatThrowsClassCast() {
    // iParams[0] is cast to String — passing a non-String throws ClassCastException.
    final var fn = new SQLFunctionFormat();
    fn.execute(null, null, null, new Object[] {42, 1}, new BasicCommandContext());
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionFormat();
    assertEquals("format", fn.getName(null));
    assertEquals(2, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
    assertEquals("format(<format>, <arg1> [,<argN>]*)", fn.getSyntax(null));
  }
}
