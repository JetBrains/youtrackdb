/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for {@link SQLMethodSubString} — returns a range or tail slice of the subject, with four
 * distinct clamp branches in both the 1-param and 2-param paths.
 *
 * <p>No DB required.
 *
 * <p>Covered branches (all explicitly enumerated so a refactor that drops a clamp is caught):
 *
 * <h3>Early-exit</h3>
 * <ul>
 *   <li>{@code iThis == null} → null.
 *   <li>{@code iParams[0] == null} → null.
 * </ul>
 *
 * <h3>2-param path</h3>
 * <ul>
 *   <li>Happy: positive {@code from}, {@code to} both inside bounds.
 *   <li>{@code from < 0} → clamped to 0.
 *   <li>{@code from >= length} → returns "".
 *   <li>{@code to > length} → clamped to length.
 *   <li>{@code to <= from} → returns "".
 *   <li>{@code from == to == 0} — covered by the {@code to <= from} branch.
 * </ul>
 *
 * <h3>1-param path</h3>
 * <ul>
 *   <li>Happy: positive {@code from}.
 *   <li>{@code from < 0} → clamped to 0 (whole string).
 *   <li>{@code from >= length} → returns "".
 *   <li>{@code from == length} — boundary: {@code >= length} flips to "".
 * </ul>
 *
 * <h3>Metadata</h3>
 * <ul>
 *   <li>name, min/max, syntax.
 * </ul>
 */
public class SQLMethodSubStringTest {

  private SQLMethodSubString method() {
    return new SQLMethodSubString();
  }

  // ---------------------------------------------------------------------------
  // Early-exit
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    assertNull(method().execute(null, null, null, null, new Object[] {0}));
  }

  @Test
  public void nullFirstParamReturnsNull() {
    assertNull(method().execute("abcde", null, null, null, new Object[] {null}));
  }

  // ---------------------------------------------------------------------------
  // 2-param path
  // ---------------------------------------------------------------------------

  @Test
  public void twoParamHappyPath() {
    var result = method().execute("abcdef", null, null, null, new Object[] {1, 4});

    assertEquals("bcd", result);
  }

  @Test
  public void twoParamNegativeFromIsClampedToZero() {
    var result = method().execute("abcdef", null, null, null, new Object[] {-3, 3});

    assertEquals("abc", result);
  }

  @Test
  public void twoParamFromAtOrBeyondLengthReturnsEmpty() {
    var result = method().execute("abcd", null, null, null, new Object[] {4, 10});

    assertEquals("", result);
  }

  @Test
  public void twoParamFromBeyondLengthReturnsEmpty() {
    var result = method().execute("abcd", null, null, null, new Object[] {99, 99});

    assertEquals("", result);
  }

  @Test
  public void twoParamToBeyondLengthIsClampedToLength() {
    var result = method().execute("abcd", null, null, null, new Object[] {1, 99});

    assertEquals("bcd", result);
  }

  @Test
  public void twoParamToEqualsFromReturnsEmpty() {
    // to <= from triggers the third clamp → "".
    var result = method().execute("abcdef", null, null, null, new Object[] {2, 2});

    assertEquals("", result);
  }

  @Test
  public void twoParamToLessThanFromReturnsEmpty() {
    // to < from triggers the third clamp → "". Important: the check is STRICT so to == from
    // flips here too.
    var result = method().execute("abcdef", null, null, null, new Object[] {3, 1});

    assertEquals("", result);
  }

  @Test
  public void twoParamNumericStringsAreParsed() {
    // Integer.parseInt("2") / parseInt("5").
    var result = method().execute("abcdef", null, null, null, new Object[] {"2", "5"});

    assertEquals("cde", result);
  }

  @Test
  public void twoParamNonNumericFromThrowsNumberFormat() {
    try {
      method().execute("abcdef", null, null, null, new Object[] {"xx", "3"});
      fail("expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // 1-param path
  // ---------------------------------------------------------------------------

  @Test
  public void oneParamHappyPath() {
    var result = method().execute("abcdef", null, null, null, new Object[] {2});

    assertEquals("cdef", result);
  }

  @Test
  public void oneParamZeroFromReturnsWholeString() {
    var result = method().execute("abcdef", null, null, null, new Object[] {0});

    assertEquals("abcdef", result);
  }

  @Test
  public void oneParamNegativeFromIsClampedToZero() {
    var result = method().execute("abcdef", null, null, null, new Object[] {-5});

    assertEquals("abcdef", result);
  }

  @Test
  public void oneParamFromEqualsLengthReturnsEmpty() {
    // The `from >= length` branch triggers on equality too.
    var result = method().execute("abcd", null, null, null, new Object[] {4});

    assertEquals("", result);
  }

  @Test
  public void oneParamFromBeyondLengthReturnsEmpty() {
    var result = method().execute("abcd", null, null, null, new Object[] {99});

    assertEquals("", result);
  }

  // ---------------------------------------------------------------------------
  // Non-String iThis coercion
  // ---------------------------------------------------------------------------

  @Test
  public void integerIThisCoercedViaToStringTwoParam() {
    // Integer.toString(9876543210L doesn't fit; use int). Integer.toString(12345) = "12345",
    // substring(1,4) = "234".
    var result = method().execute(Integer.valueOf(12345), null, null, null, new Object[] {1, 4});

    assertEquals("234", result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("substring", SQLMethodSubString.NAME);
    assertEquals("substring", m.getName());
    assertEquals(1, m.getMinParams());
    assertEquals(2, m.getMaxParams(null));
    assertEquals("subString(<from-index> [,<to-index>])", m.getSyntax());
  }
}
