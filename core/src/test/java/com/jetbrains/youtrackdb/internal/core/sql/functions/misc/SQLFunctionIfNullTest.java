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

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionIfNull} — returns the value or a fallback when null. Standalone.
 *
 * <p>Two arities:
 *
 * <ul>
 *   <li>{@code ifnull(v, fallback)}: {@code v} if non-null, else {@code fallback}
 *   <li>{@code ifnull(v, fallback, replacement)}: {@code replacement} if {@code v} is
 *       non-null, else {@code fallback} — counter-intuitive but documented in the Javadoc.
 * </ul>
 */
public class SQLFunctionIfNullTest {

  @Test
  public void twoParamNonNullReturnsValue() {
    final var fn = new SQLFunctionIfNull();
    assertEquals("a",
        fn.execute(null, null, null, new Object[] {"a", "fallback"},
            new BasicCommandContext()));
  }

  @Test
  public void twoParamNullReturnsFallback() {
    final var fn = new SQLFunctionIfNull();
    assertEquals("fallback",
        fn.execute(null, null, null, new Object[] {null, "fallback"},
            new BasicCommandContext()));
  }

  @Test
  public void threeParamNonNullReturnsReplacement() {
    // Counter-intuitive per Javadoc: third param is the "non-null replacement", not the fallback.
    final var fn = new SQLFunctionIfNull();
    assertEquals("c",
        fn.execute(null, null, null, new Object[] {"a", "b", "c"}, new BasicCommandContext()));
  }

  @Test
  public void threeParamNullReturnsFallback() {
    final var fn = new SQLFunctionIfNull();
    assertEquals("b",
        fn.execute(null, null, null, new Object[] {null, "b", "c"}, new BasicCommandContext()));
  }

  @Test
  public void nullReplacementIsHonoured() {
    // When param[0] is non-null and param[2] is explicit null, null is returned (not param[0]).
    final var fn = new SQLFunctionIfNull();
    assertNull(fn.execute(null, null, null, new Object[] {"a", "b", null},
        new BasicCommandContext()));
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionIfNull();
    assertEquals("ifnull", fn.getName(null));
    assertEquals(2, fn.getMinParams());
    assertEquals(3, fn.getMaxParams(null));
    assertEquals(
        "Syntax error: ifnull(<field|value>, <return_value_if_null>"
            + " [,<return_value_if_not_null>])",
        fn.getSyntax(null));
  }
}
