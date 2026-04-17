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
 * Tests for {@link SQLFunctionStrcmpci} — case-insensitive string comparison normalized to
 * -1 / 0 / 1. Standalone (no DB).
 *
 * <p>Contract surface:
 *
 * <ul>
 *   <li>null/null → 0, null/non-null → -1, non-null/null → 1 (non-string args are treated as null
 *       by the param-type guard, so Integer vs null also follows these rules).
 *   <li>Result of {@link String#compareToIgnoreCase(String)} is normalized via
 *       {@code res / Math.abs(res)} so the return set is strictly {-1, 0, 1}.
 *   <li>Metadata (name, min/max params=2, syntax).
 * </ul>
 */
public class SQLFunctionStrcmpciTest {

  @Test
  public void bothNullReturnsZero() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(0,
        fn.execute(null, null, null, new Object[] {null, null}, new BasicCommandContext()));
  }

  @Test
  public void firstNullReturnsMinusOne() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(-1,
        fn.execute(null, null, null, new Object[] {null, "abc"}, new BasicCommandContext()));
  }

  @Test
  public void secondNullReturnsOne() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(1,
        fn.execute(null, null, null, new Object[] {"abc", null}, new BasicCommandContext()));
  }

  @Test
  public void equalStringsReturnZero() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(0,
        fn.execute(null, null, null, new Object[] {"abc", "abc"}, new BasicCommandContext()));
  }

  @Test
  public void caseInsensitiveEqualsReturnsZero() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(0,
        fn.execute(null, null, null, new Object[] {"abc", "ABC"}, new BasicCommandContext()));
    assertEquals(0,
        fn.execute(null, null, null, new Object[] {"MiXeD", "mIxEd"}, new BasicCommandContext()));
  }

  @Test
  public void lesserReturnsNormalizedMinusOne() {
    final var fn = new SQLFunctionStrcmpci();
    // compareToIgnoreCase("abc", "bcd") returns a negative int (-1 or otherwise); normalize → -1.
    assertEquals(-1,
        fn.execute(null, null, null, new Object[] {"abc", "bcd"}, new BasicCommandContext()));
    // Long distance > 1 also normalizes to -1.
    assertEquals(-1,
        fn.execute(null, null, null, new Object[] {"aaa", "zzz"}, new BasicCommandContext()));
  }

  @Test
  public void greaterReturnsNormalizedOne() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(1,
        fn.execute(null, null, null, new Object[] {"bcd", "abc"}, new BasicCommandContext()));
    assertEquals(1,
        fn.execute(null, null, null, new Object[] {"zzz", "aaa"}, new BasicCommandContext()));
  }

  @Test
  public void nonStringFirstParamIsTreatedAsNull() {
    // Integer is non-null but also non-String — the guard `instanceof String` leaves s1 = null.
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(-1,
        fn.execute(null, null, null, new Object[] {123, "abc"}, new BasicCommandContext()));
  }

  @Test
  public void nonStringSecondParamIsTreatedAsNull() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(1,
        fn.execute(null, null, null, new Object[] {"abc", 123}, new BasicCommandContext()));
  }

  @Test
  public void bothNonStringReturnsZero() {
    // Both non-String → both s1, s2 remain null → 0. Drift guard: a future type check that
    // raises ClassCastException would break this contract.
    final var fn = new SQLFunctionStrcmpci();
    assertEquals(0,
        fn.execute(null, null, null, new Object[] {123, 456}, new BasicCommandContext()));
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionStrcmpci();
    assertEquals("strcmpci", fn.getName(null));
    assertEquals(2, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
    assertEquals("strcmpci(<arg1>, <arg2>)", fn.getSyntax(null));
  }
}
