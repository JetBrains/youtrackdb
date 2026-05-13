/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Standalone tests for {@link FunctionUtilWrapper}. The class is a stateless helper used inside
 * scripted functions to test for "defined" values (not null, not the strings {@code "undefined"}
 * or {@code "null"}) and to pass a value through unchanged. Pre-existing coverage left the
 * class at 25% line / 0% branch because no test ever drove the four canonical input shapes. The
 * tests below pin every branch of {@link FunctionUtilWrapper#exists(Object[])} and the identity
 * contract of {@link FunctionUtilWrapper#value(Object)}.
 *
 * <p>No database session is needed — the class is a plain POJO with no member state. The
 * default constructor is exercised implicitly by every test method.
 */
public class FunctionUtilWrapperTest {

  /**
   * {@code exists(null)} short-circuits to {@code false} because the {@code iValues != null} guard
   * fails before the loop runs. Pinning this branch ensures the null-array path stays distinct
   * from the empty-array path below.
   */
  @Test
  public void existsReturnsFalseWhenArrayIsNull() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists((Object[]) null));
  }

  /**
   * {@code exists(new Object[0])} returns {@code false} because the loop body never executes —
   * distinct from the null-array branch above (the guard passes, but there is nothing to check).
   */
  @Test
  public void existsReturnsFalseWhenArrayIsEmpty() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists(new Object[0]));
  }

  /**
   * Every element is {@code null} → loop visits each entry, every {@code o != null} guard fails,
   * and the method falls through to {@code return false}. Pins the all-null branch.
   */
  @Test
  public void existsReturnsFalseWhenAllElementsAreNull() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists(null, null, null));
  }

  /**
   * Every element is the literal string {@code "undefined"} → the {@code !o.equals("undefined")}
   * guard fails for each entry. Mirrors the JavaScript {@code typeof x === "undefined"} check
   * the wrapper is named after.
   */
  @Test
  public void existsReturnsFalseWhenAllElementsAreUndefinedString() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists("undefined", "undefined"));
  }

  /**
   * Every element is the literal string {@code "null"} → the {@code !o.equals("null")} guard
   * fails for each entry. Distinct from {@link #existsReturnsFalseWhenAllElementsAreNull()}
   * because here the values are non-null Java strings carrying the text {@code "null"}.
   */
  @Test
  public void existsReturnsFalseWhenAllElementsAreNullString() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists("null", "null"));
  }

  /**
   * Mixed array of "undefined-equivalent" entries (null, the string {@code "undefined"}, the
   * string {@code "null"}). All three guards must fail in sequence; method returns
   * {@code false}. Pins the cross-product of the three guard conditions.
   */
  @Test
  public void existsReturnsFalseForMixedUndefinedEquivalentValues() {
    var wrapper = new FunctionUtilWrapper();
    assertFalse(wrapper.exists(null, "undefined", "null"));
  }

  /**
   * A single concrete value among the array → method short-circuits via {@code return true} on
   * the first concrete entry. Pins the early-return branch so a future loop refactor that
   * accidentally drops the short-circuit is caught.
   */
  @Test
  public void existsReturnsTrueWhenAtLeastOneConcreteValuePresent() {
    var wrapper = new FunctionUtilWrapper();
    assertTrue(wrapper.exists("hello"));
  }

  /**
   * Concrete value sandwiched between "undefined-equivalent" entries → the method must scan past
   * the leading null and "undefined"/"null" strings, then return true on the concrete entry.
   * Pins the in-loop true-return branch.
   */
  @Test
  public void existsReturnsTrueWhenConcreteValueAfterUndefinedEquivalents() {
    var wrapper = new FunctionUtilWrapper();
    assertTrue(wrapper.exists(null, "undefined", "null", 42));
  }

  /**
   * {@code value(null)} returns {@code null} unchanged. The method is documented as a
   * pass-through; pinning the null arm catches a future regression that adds defensive
   * normalisation.
   */
  @Test
  public void valueReturnsNullWhenInputIsNull() {
    var wrapper = new FunctionUtilWrapper();
    assertNull(wrapper.value(null));
  }

  /**
   * {@code value(x)} returns the same reference for non-null inputs. Pinned via {@code assertSame}
   * to catch a regression that accidentally introduces a defensive copy.
   */
  @Test
  public void valueReturnsSameReferenceForNonNullInput() {
    var wrapper = new FunctionUtilWrapper();
    var input = new Object();
    assertSame(input, wrapper.value(input));
  }

  /**
   * Two consecutive {@code value(x)} calls return the same reference each time — implicitly
   * confirms the method is stateless (no caching layer slips in).
   */
  @Test
  public void valueIsStatelessAcrossCalls() {
    var wrapper = new FunctionUtilWrapper();
    var firstInput = "first";
    var secondInput = "second";
    assertEquals("first", wrapper.value(firstInput));
    assertEquals("second", wrapper.value(secondInput));
  }
}
