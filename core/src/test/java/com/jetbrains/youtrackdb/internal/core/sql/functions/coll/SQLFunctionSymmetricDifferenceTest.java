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
package com.jetbrains.youtrackdb.internal.core.sql.functions.coll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for the SQL SYMMETRICDIFFERENCE() collection function.
 *
 * @since 11.10.12 14:40
 */
public class SQLFunctionSymmetricDifferenceTest {

  @Test
  public void testOperator() {
    final var differenceFunction =
        new SQLFunctionSymmetricDifference();

    final List<Object> income = Arrays.asList(1, 2, 3, 1, 4, 5, 2, 2, 1, 1);
    final Set<Object> expectedResult = new HashSet<Object>(Arrays.asList(3, 4, 5));

    for (var i : income) {
      differenceFunction.execute(null, null, null, new Object[] {i}, null);
    }

    final var actualResult = differenceFunction.getResult();

    assertSetEquals(actualResult, expectedResult);
  }

  @Test
  public void testExecute() {
    final var function = new SQLFunctionSymmetricDifference();

    final List<List<Object>> incomes =
        Arrays.asList(
            Arrays.asList(1, 2, 3, 4, 5, 1),
            Arrays.asList(3, 5, 6, 7, 0, 1, 3, 3, 6),
            Arrays.asList(2, 2, 8, 9));

    final Set<Object> expectedResult = new HashSet<Object>(Arrays.<Object>asList(4, 7, 8, 9, 0));

    final var actualResult =
        (Set<Object>) function.execute(null, null, null, incomes.toArray(),
            new BasicCommandContext());

    assertSetEquals(actualResult, expectedResult);
  }

  @Test
  public void aggregationReturnsNullAndAccumulatesInto() {
    // Single-param aggregation mode: execute() returns null but fills the internal Set.
    final var fn = new SQLFunctionSymmetricDifference();

    for (var v : List.of(1, 2, 2, 3)) {
      final var ret = fn.execute(null, null, null, new Object[] {v}, null);
      assertNull(ret);
    }

    // {1} added, {2} added, {2} now rejected (seen before) → removed, {3} added.
    assertSetEquals(fn.getResult(), Set.of(1, 3));
  }

  @Test
  public void aggregationAcceptsCollectionArgAndUnrolls() {
    // Collection arg in aggregation mode → each element fed through addItemToResult.
    final var fn = new SQLFunctionSymmetricDifference();

    fn.execute(null, null, null, new Object[] {List.of(1, 2, 3)}, null);
    fn.execute(null, null, null, new Object[] {List.of(2, 4)}, null);

    // First call seeds {1,2,3}; second rejects 2 (now in rejected), adds 4 → {1,3,4}.
    assertSetEquals(fn.getResult(), Set.of(1, 3, 4));
  }

  @Test
  public void nullFirstParameterReturnsNullNoStateChange() {
    final var fn = new SQLFunctionSymmetricDifference();

    assertNull(fn.execute(null, null, null, new Object[] {null}, null));
    // No context was ever created.
    assertNull(fn.getResult());
  }

  @Test
  public void inlineScalarParametersComputeSymmetricDifference() {
    // Inline mode with non-collection args routes through addItemToResult per scalar.
    final var fn = new SQLFunctionSymmetricDifference();

    final var result = (Set<?>) fn.execute(null, null, null, new Object[] {1, 2, 2, 3},
        new BasicCommandContext());

    assertSetEquals(new HashSet<>(result), Set.of(1, 3));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionSymmetricDifference();
    assertEquals("symmetricDifference", SQLFunctionSymmetricDifference.NAME);
    assertEquals("symmetricDifference", fn.getName(null));
    // Note: getSyntax reuses the "difference(...)" text — covering the accessor, not the grammar.
    assertEquals("difference(<field>*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  private static void assertSetEquals(Set<?> actualResult, Set<?> expectedResult) {
    assertEquals(actualResult.size(), expectedResult.size());
    for (var o : actualResult) {
      assertTrue(expectedResult.contains(o));
    }
  }
}
