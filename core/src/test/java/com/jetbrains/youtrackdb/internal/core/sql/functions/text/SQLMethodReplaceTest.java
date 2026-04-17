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
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for {@link SQLMethodReplace} — literal (NOT regex) replace-all, returning
 * {@code iThis.toString().replace(iParams[0].toString(), iParams[1].toString())}.
 *
 * <p>No DB required.
 *
 * <p>Covered branches (including the peculiar "return iParams[0] when null" short-circuit):
 *
 * <ul>
 *   <li>{@code iThis == null} → returns {@code iParams[0]} (NOT {@code null} unconditionally).
 *       This is a latent behaviour pin — WHEN-FIXED: the more usual contract is "null in, null
 *       out"; here a non-null needle leaks through when the subject is null.
 *   <li>{@code iParams[0] == null} (with non-null subject) → returns {@code iParams[0]} (null).
 *   <li>{@code iParams[1] == null} (with non-null subject & needle) → returns {@code iParams[0]}
 *       (the needle itself, NOT null). Another latent surprise — pinned.
 *   <li>Happy path: all three non-null → literal String.replace result.
 *   <li>Needle not found → input returned unchanged (String.replace semantics).
 *   <li>Multiple occurrences → all replaced (not just the first).
 *   <li>Empty replacement ({@code ""}) removes every occurrence of the needle.
 *   <li>Regex metacharacters in the needle are treated as literals.
 *   <li>Non-String iThis/params are coerced via toString().
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLMethodReplaceTest {

  private SQLMethodReplace method() {
    return new SQLMethodReplace();
  }

  // ---------------------------------------------------------------------------
  // Null short-circuits — production returns iParams[0], NOT null
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNeedleNotNull() {
    // WHEN-FIXED: contract asymmetry. A null subject would most likely be expected to yield null,
    // but the production code returns iParams[0] (the needle), leaking the search key out to the
    // caller. Pin this behaviour so a future bugfix is visible in the test diff.
    var needle = "foo";

    var result = method().execute(null, null, null, null, new Object[] {needle, "bar"});

    assertSame("null-iThis returns needle identity, not null", needle, result);
  }

  @Test
  public void nullNeedleReturnsNull() {
    // With non-null subject and null needle, the early-exit returns iParams[0] which IS null —
    // so the observable result is null.
    var result = method().execute("x", null, null, null, new Object[] {null, "bar"});

    assertNull(result);
  }

  @Test
  public void nullReplacementReturnsNeedleNotNull() {
    // WHEN-FIXED: similar asymmetry. Non-null subject, non-null needle, null replacement → the
    // method returns the needle itself rather than throwing or returning null. Callers that feed
    // a null replacement would silently get the search key back.
    var needle = "foo";

    var result = method().execute("xxxfooxxx", null, null, null, new Object[] {needle, null});

    assertSame("null-replacement returns needle identity, not null or input", needle, result);
  }

  // ---------------------------------------------------------------------------
  // Happy paths
  // ---------------------------------------------------------------------------

  @Test
  public void replacesAllOccurrencesOfLiteralNeedle() {
    // String.replace(CharSequence, CharSequence) replaces ALL non-overlapping occurrences.
    var result = method().execute("ababab", null, null, null, new Object[] {"a", "Z"});

    assertEquals("ZbZbZb", result);
  }

  @Test
  public void needleNotFoundReturnsInputUnchanged() {
    var result = method().execute("abc", null, null, null, new Object[] {"Z", "X"});

    assertEquals("abc", result);
  }

  @Test
  public void emptyReplacementRemovesAllOccurrences() {
    // Replacement = "" deletes every match.
    var result = method().execute("abXYabXYabXY", null, null, null, new Object[] {"XY", ""});

    assertEquals("ababab", result);
  }

  @Test
  public void regexMetacharactersInNeedleAreTreatedAsLiteral() {
    // String.replace(CharSequence, CharSequence) does NOT interpret the needle as a regex, so
    // ".*" matches the literal 4-character sequence, not "any number of any character".
    var result = method().execute("a.*b.*c", null, null, null, new Object[] {".*", "X"});

    assertEquals("aXbXc", result);
  }

  @Test
  public void dollarBackslashInReplacementIsLiteral() {
    // The replace(CharSequence, CharSequence) overload (NOT replaceAll) treats $ / \ literally.
    var result = method().execute("ab", null, null, null, new Object[] {"a", "$0\\"});

    assertEquals("$0\\b", result);
  }

  @Test
  public void nonStringIThisAndParamsAreCoercedViaToString() {
    // iThis is Integer; needle is Integer; replacement is Integer. All three go through
    // toString() → "123".replace("2", "9") = "193".
    var result = method().execute(Integer.valueOf(123), null, null, null,
        new Object[] {Integer.valueOf(2), Integer.valueOf(9)});

    assertEquals("193", result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("replace", SQLMethodReplace.NAME);
    assertEquals("replace", m.getName());
    assertEquals(2, m.getMinParams());
    assertEquals(2, m.getMaxParams(null));
    assertEquals("replace(<to-find>, <to-replace>)", m.getSyntax());
  }
}
