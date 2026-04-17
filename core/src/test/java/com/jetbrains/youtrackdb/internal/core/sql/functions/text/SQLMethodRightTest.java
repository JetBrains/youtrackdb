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
 * Tests for {@link SQLMethodRight} — returns the last N characters of the subject, where N is
 * {@code Integer.parseInt(iParams[0].toString())}.
 *
 * <p>No DB required.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} or {@code iParams[0] == null} → null.
 *   <li>{@code offset < valueAsString.length()} → {@code substring(length - offset)} (tail slice).
 *   <li>{@code offset >= valueAsString.length()} → entire string (starts at 0).
 *   <li>{@code offset == length} — boundary: starts at 0 and returns the whole string (the
 *       condition is strict {@code <}, so equal flips to the "whole string" branch).
 *   <li>{@code offset == 0} — returns an empty string (starts at length).
 *   <li>{@code offset < 0} — String.substring throws StringIndexOutOfBoundsException. WHEN-FIXED:
 *       the method does not clamp negatives to zero.
 *   <li>Non-numeric {@code iParams[0]} → NumberFormatException (Integer.parseInt).
 *   <li>Numeric-like Number input (boxed Integer) is tolerated through toString.
 *   <li>Non-String iThis is coerced via toString().
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLMethodRightTest {

  private SQLMethodRight method() {
    return new SQLMethodRight();
  }

  // ---------------------------------------------------------------------------
  // Null / early-exit paths
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    assertNull(method().execute(null, null, null, null, new Object[] {3}));
  }

  @Test
  public void nullFirstParamReturnsNull() {
    assertNull(method().execute("abcdef", null, null, null, new Object[] {null}));
  }

  // ---------------------------------------------------------------------------
  // Happy paths — tail slicing
  // ---------------------------------------------------------------------------

  @Test
  public void offsetLessThanLengthReturnsTail() {
    var result = method().execute("abcdef", null, null, null, new Object[] {3});

    assertEquals("def", result);
  }

  @Test
  public void offsetOneReturnsFinalCharacter() {
    var result = method().execute("abcdef", null, null, null, new Object[] {1});

    assertEquals("f", result);
  }

  // ---------------------------------------------------------------------------
  // Boundary — offset >= length returns the WHOLE string (not empty)
  // ---------------------------------------------------------------------------

  @Test
  public void offsetEqualsLengthReturnsWholeString() {
    // Condition is strict `<`, so offset == length falls into the "else" branch (start at 0).
    var result = method().execute("abcd", null, null, null, new Object[] {4});

    assertEquals("abcd", result);
  }

  @Test
  public void offsetGreaterThanLengthReturnsWholeString() {
    var result = method().execute("abcd", null, null, null, new Object[] {99});

    assertEquals("abcd", result);
  }

  @Test
  public void offsetZeroReturnsEmptyString() {
    // offset 0 is < length → substring(length - 0) = substring(length) = "".
    var result = method().execute("abcd", null, null, null, new Object[] {0});

    assertEquals("", result);
  }

  // ---------------------------------------------------------------------------
  // Negative offset — WHEN-FIXED: should probably clamp, but currently throws
  // ---------------------------------------------------------------------------

  @Test
  public void negativeOffsetThrowsStringIndexOutOfBounds() {
    // WHEN-FIXED: A sensible implementation would clamp negatives to 0 (empty result) or treat
    // them as "no chars from the right". Currently, `length - offset` > length and substring
    // throws StringIndexOutOfBoundsException.
    try {
      method().execute("abcd", null, null, null, new Object[] {-1});
      fail("expected StringIndexOutOfBoundsException on negative offset");
    } catch (StringIndexOutOfBoundsException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Non-String / non-Integer param coercion
  // ---------------------------------------------------------------------------

  @Test
  public void stringDigitsParamIsParsedAsInt() {
    // Integer.parseInt("2") → 2.
    var result = method().execute("abcdef", null, null, null, new Object[] {"2"});

    assertEquals("ef", result);
  }

  @Test
  public void boxedIntegerParamIsCoercedViaToString() {
    var result = method().execute("abcdef", null, null, null, new Object[] {Integer.valueOf(4)});

    assertEquals("cdef", result);
  }

  @Test
  public void nonNumericParamThrowsNumberFormat() {
    // Integer.parseInt("abc") throws NumberFormatException.
    try {
      method().execute("abcdef", null, null, null, new Object[] {"abc"});
      fail("expected NumberFormatException for non-numeric param");
    } catch (NumberFormatException expected) {
      // pinned
    }
  }

  @Test
  public void integerIThisIsCoercedViaToString() {
    // Integer.toString(12345) = "12345"; last 3 = "345".
    var result = method().execute(Integer.valueOf(12345), null, null, null, new Object[] {3});

    assertEquals("345", result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("right", SQLMethodRight.NAME);
    assertEquals("right", m.getName());
    assertEquals(1, m.getMinParams());
    assertEquals(1, m.getMaxParams(null));
    assertEquals("right( <characters>)", m.getSyntax());
  }
}
