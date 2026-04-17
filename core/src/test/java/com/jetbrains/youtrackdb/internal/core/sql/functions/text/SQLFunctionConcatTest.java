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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link SQLFunctionConcat} — aggregating string-concatenation over multiple rows. Each
 * {@code execute()} call represents one row; the final string is pulled via {@code getResult()}.
 *
 * <p>No DB required — the function never dereferences the context or session.
 *
 * <p>Signature reminder: this is a {@link com.jetbrains.youtrackdb.internal.core.sql.functions
 * .SQLFunctionConfigurableAbstract}, so the parameter order is
 * {@code execute(iThis, iCurrentRecord, iCurrentResult, iParams, iContext)} — context LAST.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code aggregateResults()} returns {@code true}.
 *   <li>First call seeds {@code sb} (delim not appended): output starts at {@code iParams[0]}.
 *   <li>Subsequent calls append {@code iParams[1]} (delim) then {@code iParams[0]}.
 *   <li>Delim is optional — single-arg rows concatenate directly without separators.
 *   <li>Mixing single-arg / two-arg rows: the first row's single arg seeds, a later two-arg row
 *       applies its delim, following two-arg rows append delim+value — pin interaction.
 *   <li>Zero rows → {@code getResult()} returns null (the StringBuilder is never allocated).
 *   <li>Null elements are appended as the literal "null" (StringBuilder.append(Object) → "null").
 *   <li>Non-String params are coerced via StringBuilder.append's Object overload.
 *   <li>{@code execute()} always returns null.
 *   <li>Metadata: name, min/max, syntax.
 * </ul>
 */
public class SQLFunctionConcatTest {

  private SQLFunctionConcat function() {
    return new SQLFunctionConcat();
  }

  // ---------------------------------------------------------------------------
  // Aggregation contract
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsTrue() {
    assertTrue(function().aggregateResults());
  }

  @Test
  public void executeReturnsNullInBothSeedingAndContinuingCalls() {
    // The function emits NOTHING from execute(); the final value is only observable via
    // getResult(). Pin both branches (first call and a subsequent call).
    var f = function();

    assertNull(f.execute(null, null, null, new Object[] {"A"}, null));
    assertNull(f.execute(null, null, null, new Object[] {"B", ","}, null));
  }

  // ---------------------------------------------------------------------------
  // Empty state
  // ---------------------------------------------------------------------------

  @Test
  public void getResultBeforeAnyExecuteReturnsNull() {
    // Without an execute() call, sb is null → getResult() returns null (not an empty string).
    var f = function();

    assertNull(f.getResult());
  }

  // ---------------------------------------------------------------------------
  // Single-arg execute — delim absent → plain concat
  // ---------------------------------------------------------------------------

  @Test
  public void singleArgConcatsDirectly() {
    var f = function();

    f.execute(null, null, null, new Object[] {"A"}, null);
    f.execute(null, null, null, new Object[] {"B"}, null);
    f.execute(null, null, null, new Object[] {"C"}, null);

    assertEquals("ABC", f.getResult());
  }

  // ---------------------------------------------------------------------------
  // Two-arg execute — delim IS appended starting from the second call
  // ---------------------------------------------------------------------------

  @Test
  public void twoArgConcatInsertsDelimBetweenRows() {
    // First call: sb null → seed with "A", no delim.
    // Second call: sb != null → append "," then "B".
    // Third call: append "," then "C".
    var f = function();

    f.execute(null, null, null, new Object[] {"A", ","}, null);
    f.execute(null, null, null, new Object[] {"B", ","}, null);
    f.execute(null, null, null, new Object[] {"C", ","}, null);

    assertEquals("A,B,C", f.getResult());
  }

  @Test
  public void twoArgConcatReadsDelimPerCallNotFromFirstRow() {
    // The delim is read from the CURRENT call's iParams[1] (no caching across calls). Rows with
    // different delimiters compose in call order: "A" (seed, no delim) + "-B" (second row's "-"
    // delim) + "|C" (third row's "|" delim) = "A-B|C".
    var f = function();

    f.execute(null, null, null, new Object[] {"A"}, null);
    f.execute(null, null, null, new Object[] {"B", "-"}, null);
    f.execute(null, null, null, new Object[] {"C", "|"}, null);

    assertEquals("A-B|C", f.getResult());
  }

  @Test
  public void emptyStringDelimIsNoOpSeparator() {
    // Empty-string delim ("") is NOT short-circuited — it's appended. StringBuilder.append("") is
    // a true no-op, so the observable output is the concatenation of values only.
    var f = function();

    f.execute(null, null, null, new Object[] {"A"}, null);
    f.execute(null, null, null, new Object[] {"B", ""}, null);
    f.execute(null, null, null, new Object[] {"C", ""}, null);

    assertEquals("ABC", f.getResult());
  }

  // ---------------------------------------------------------------------------
  // Null / non-String — toString-style coercion via StringBuilder.append
  // ---------------------------------------------------------------------------

  @Test
  public void nullFirstArgAppendsLiteralNull() {
    // StringBuilder.append(null) produces the 4-char sequence "null". Pin the behaviour.
    var f = function();

    f.execute(null, null, null, new Object[] {null}, null);
    f.execute(null, null, null, new Object[] {"B", ","}, null);

    assertEquals("null,B", f.getResult());
  }

  @Test
  public void nonStringArgsCoercedViaToString() {
    // Integer + Double flow through StringBuilder.append(Object).
    var f = function();

    f.execute(null, null, null, new Object[] {Integer.valueOf(1)}, null);
    f.execute(null, null, null, new Object[] {Double.valueOf(2.5), "_"}, null);

    assertEquals("1_2.5", f.getResult());
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var f = function();

    assertEquals("concat", SQLFunctionConcat.NAME);
    assertEquals("concat", f.getName(null));
    assertEquals(1, f.getMinParams());
    assertEquals(2, f.getMaxParams(null));
    assertEquals("concat(<field>, [<delim>])", f.getSyntax(null));
  }
}
