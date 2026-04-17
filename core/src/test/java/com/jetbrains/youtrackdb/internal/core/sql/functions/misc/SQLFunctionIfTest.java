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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionIf} — the ternary {@code if(cond, tv, fv)} SQL function. Standalone
 * (no DB).
 *
 * <p>Covers the three condition type branches (Boolean, String, Number) plus the unsupported-type
 * branch that returns {@code null}, the 2-param path (missing {@code fv} → caught
 * {@link ArrayIndexOutOfBoundsException} → returns null), and the exception-swallow path.
 */
public class SQLFunctionIfTest {

  @Test
  public void booleanTrueReturnsTruePath() {
    final var fn = new SQLFunctionIf();
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {Boolean.TRUE, "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void booleanFalseReturnsFalsePath() {
    final var fn = new SQLFunctionIf();
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {Boolean.FALSE, "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void stringTrueIsParsedAsTrue() {
    // Boolean.parseBoolean matches "true" case-insensitively; anything else is false.
    final var fn = new SQLFunctionIf();
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {"true", "yes", "no"},
            new BasicCommandContext()));
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {"TRUE", "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void stringNonTrueIsParsedAsFalse() {
    final var fn = new SQLFunctionIf();
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {"false", "yes", "no"},
            new BasicCommandContext()));
    // Boolean.parseBoolean treats any non-"true" value (including "yes", "1") as false.
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {"yes", "yes", "no"},
            new BasicCommandContext()));
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {"1", "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void numberGreaterThanZeroIsTrue() {
    final var fn = new SQLFunctionIf();
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {1, "yes", "no"},
            new BasicCommandContext()));
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {42L, "yes", "no"},
            new BasicCommandContext()));
    // Double > 0 — intValue() truncates but stays > 0.
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {1.9, "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void numberZeroOrNegativeIsFalse() {
    final var fn = new SQLFunctionIf();
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {0, "yes", "no"},
            new BasicCommandContext()));
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {-5, "yes", "no"},
            new BasicCommandContext()));
    // Small positive double that truncates to 0 — intValue() > 0 is false.
    assertEquals("no",
        fn.execute(null, null, null, new Object[] {0.5, "yes", "no"},
            new BasicCommandContext()));
  }

  @Test
  public void unsupportedConditionTypeReturnsNull() {
    // A non-Boolean / non-String / non-Number condition falls through to the else-null branch.
    final var fn = new SQLFunctionIf();
    assertNull(fn.execute(null, null, null, new Object[] {new Object(), "yes", "no"},
        new BasicCommandContext()));
  }

  @Test
  public void nullConditionReturnsNull() {
    // null does not match any instanceof arm → null.
    final var fn = new SQLFunctionIf();
    assertNull(fn.execute(null, null, null, new Object[] {null, "yes", "no"},
        new BasicCommandContext()));
  }

  @Test
  public void twoParamTrueReturnsParam1() {
    // With only 2 params and condition=true, iParams[1] is returned — no index fault.
    final var fn = new SQLFunctionIf();
    assertEquals("yes",
        fn.execute(null, null, null, new Object[] {Boolean.TRUE, "yes"},
            new BasicCommandContext()));
  }

  @Test
  public void twoParamFalseSwallowsAioobeAndReturnsNull() {
    // With 2 params and condition=false, iParams[2] access throws AIOOBE which is caught and
    // logged, yielding null. This is an observable behavior to pin (a refactor that checks
    // iParams.length before accessing [2] would still return null, preserving the contract).
    final var fn = new SQLFunctionIf();
    assertNull(fn.execute(null, null, null, new Object[] {Boolean.FALSE, "yes"},
        new BasicCommandContext()));
  }

  @Test
  public void syntaxIsPinned() {
    assertEquals(
        "if(<field|value|expression>, <return_value_if_true> [,<return_value_if_false>])",
        new SQLFunctionIf().getSyntax(null));
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionIf();
    assertEquals("if", fn.getName(null));
    assertEquals(2, fn.getMinParams());
    assertEquals(3, fn.getMaxParams(null));
    assertTrue(!fn.aggregateResults());
  }
}
