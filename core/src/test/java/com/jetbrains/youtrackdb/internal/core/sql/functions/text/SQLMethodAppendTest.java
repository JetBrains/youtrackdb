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
 * Tests for {@link SQLMethodAppend} — pure concatenation method with signature
 * {@code execute(iThis, iCurrentRecord, iContext, ioResult, iParams)} (SQLMethod order: context
 * 3rd, not 5th).
 *
 * <p>No {@link com.jetbrains.youtrackdb.internal.DbTestBase} — the method never touches the context
 * or the session; passing {@code null} for all surrounding parameters is safe.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → returns {@code iThis} (null) directly, short-circuiting the
 *       StringBuilder path (the check is in the same if-clause as {@code iParams[0] == null}).
 *   <li>{@code iThis != null} but {@code iParams[0] == null} → returns {@code iThis} unchanged
 *       (same early-exit branch) — exactly-same identity pinned by assertSame.
 *   <li>Single-arg append concatenates {@code iThis.toString()} with {@code iParams[0].toString()}.
 *   <li>Multi-arg append concatenates ALL elements (the loop starts at index 0, so the first param
 *       is appended twice when there are multiple — latent behaviour pinned below).
 *   <li>Null entries past index 0 are skipped by the inner guard.
 *   <li>Non-String {@code iThis} is coerced via {@code toString()}.
 *   <li>{@code getSyntax()} and the metadata (name, min/max) match the declared contract.
 * </ul>
 */
public class SQLMethodAppendTest {

  private SQLMethodAppend method() {
    return new SQLMethodAppend();
  }

  // ---------------------------------------------------------------------------
  // Null short-circuit paths
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    // iThis == null short-circuits before the StringBuilder is allocated.
    var result = method().execute(null, null, null, null, new Object[] {"x"});

    assertNull(result);
  }

  @Test
  public void nullFirstParamReturnsIThisUnchanged() {
    // iParams[0] == null short-circuit: returns iThis as-is (same identity).
    var iThis = "base";
    var result = method().execute(iThis, null, null, null, new Object[] {null});

    assertSame("null first param must return the ORIGINAL iThis reference", iThis, result);
  }

  @Test
  public void nullFirstParamWithTrailingNonNullStillReturnsIThis() {
    // The guard is on iParams[0] specifically — even if later params are non-null, the method
    // exits via the early return.
    var result = method().execute("base", null, null, null, new Object[] {null, "ignored"});

    assertEquals("base", result);
  }

  // ---------------------------------------------------------------------------
  // Single-arg concatenation
  // ---------------------------------------------------------------------------

  @Test
  public void singleParamConcatenates() {
    var result = method().execute("hello ", null, null, null, new Object[] {"world"});

    assertEquals("hello world", result);
  }

  @Test
  public void singleParamWithEmptyStringIsNoOp() {
    // Appending "" to a non-empty base returns the base.
    var result = method().execute("base", null, null, null, new Object[] {""});

    assertEquals("base", result);
  }

  @Test
  public void singleParamToEmptyBaseProducesParamOnly() {
    // Empty base + non-empty first param → the param itself (no null short-circuit: "" is not
    // null).
    var result = method().execute("", null, null, null, new Object[] {"tail"});

    assertEquals("tail", result);
  }

  // ---------------------------------------------------------------------------
  // Multi-arg — LATENT: first param is appended TWICE (loop starts at i=0)
  // ---------------------------------------------------------------------------

  @Test
  public void multiArgAppendsFirstParamTwiceThenRemaining() {
    // WHEN-FIXED: the inner loop `for (i = 0; i < iParams.length; ++i)` starts at 0, so params[0]
    // is already tested against null at the top, then appended inside the loop as well.
    // With iParams=["-", "X", "Y"] we get base + "-" + "X" + "Y".
    // The StringBuilder is seeded with iThis.toString() only; the first param is NOT pre-appended
    // before the loop. So "a" + loop("-", "X", "Y") = "a-XY".
    var result = method().execute("a", null, null, null, new Object[] {"-", "X", "Y"});

    assertEquals("a-XY", result);
  }

  @Test
  public void multiArgSkipsNullEntriesBeyondFirst() {
    // The inner loop has a per-iteration `if (iParams[i] != null)` guard, so interspersed nulls
    // (at indices >= 1) are skipped. iParams[0] is already guaranteed non-null by the early-exit
    // check at the top of execute().
    var result = method().execute("X", null, null, null, new Object[] {"-", null, "Y", null});

    assertEquals("X-Y", result);
  }

  // ---------------------------------------------------------------------------
  // Non-String iThis / params — toString coercion
  // ---------------------------------------------------------------------------

  @Test
  public void integerIThisIsCoercedViaToString() {
    // iThis is not a String — the method calls iThis.toString() unconditionally. Integer.toString
    // produces decimal digits.
    var result = method().execute(Integer.valueOf(42), null, null, null, new Object[] {"!"});

    assertEquals("42!", result);
  }

  @Test
  public void nonStringParamsAreCoercedViaImplicitAppend() {
    // StringBuilder.append(Object) delegates to String.valueOf → toString on non-null. Integers,
    // doubles, booleans all round-trip through their canonical toString.
    var result = method().execute("v=", null, null, null,
        new Object[] {Integer.valueOf(1), Double.valueOf(2.5), Boolean.TRUE});

    assertEquals("v=12.5true", result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("append", SQLMethodAppend.NAME);
    assertEquals("append", m.getName());
    assertEquals(1, m.getMinParams());
    assertEquals(-1, m.getMaxParams(null));
    assertEquals("append([<value|expression|field>]*)", m.getSyntax());
  }
}
