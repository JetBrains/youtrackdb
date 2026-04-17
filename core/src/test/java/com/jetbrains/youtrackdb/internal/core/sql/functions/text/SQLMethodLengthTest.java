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

import org.junit.Test;

/**
 * Tests for {@link SQLMethodLength} — returns the {@code String.length()} of the
 * {@code iThis.toString()} representation, or {@code 0} if {@code iThis} is null.
 *
 * <p>No DB required. All surrounding parameters are passed as {@code null}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → literal {@code 0}.
 *   <li>Empty String → 0.
 *   <li>ASCII String → code-unit count equal to character count.
 *   <li>Non-String {@code iThis} is coerced via {@code toString()}.
 *   <li>Unicode surrogate pair (e.g. emoji) counts as 2 UTF-16 code units — not 1 code point.
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLMethodLengthTest {

  private SQLMethodLength method() {
    return new SQLMethodLength();
  }

  @Test
  public void nullIThisReturnsZero() {
    var result = method().execute(null, null, null, null, new Object[] {});

    assertEquals(0, result);
  }

  @Test
  public void emptyStringReturnsZero() {
    var result = method().execute("", null, null, null, new Object[] {});

    assertEquals(0, result);
  }

  @Test
  public void asciiStringLengthMatchesCharacterCount() {
    var result = method().execute("hello", null, null, null, new Object[] {});

    assertEquals(5, result);
  }

  @Test
  public void integerIThisIsLengthOfDecimalRepresentation() {
    // Integer.toString(12345) = "12345" → length 5. Pins toString coercion.
    var result = method().execute(Integer.valueOf(12345), null, null, null, new Object[] {});

    assertEquals(5, result);
  }

  @Test
  public void surrogatePairCountsAsTwoCodeUnits() {
    // "A" + U+1F600 (grinning face, outside BMP) is encoded as 1 + 2 UTF-16 code units = 3.
    // Pins that SQLMethodLength uses String.length() (code units) rather than codePointCount.
    var withEmoji = "A\uD83D\uDE00";
    var result = method().execute(withEmoji, null, null, null, new Object[] {});

    assertEquals(3, result);
  }

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("length", SQLMethodLength.NAME);
    assertEquals("length", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(0, m.getMaxParams(null));
    assertEquals("length()", m.getSyntax());
  }
}
