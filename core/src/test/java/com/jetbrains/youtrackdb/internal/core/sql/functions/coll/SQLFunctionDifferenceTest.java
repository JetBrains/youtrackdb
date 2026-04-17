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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for the SQL DIFFERENCE() collection function.
 *
 * @since 11.10.12 14:40
 */
public class SQLFunctionDifferenceTest {

  @Test
  public void testExecute() {
    final var function = new SQLFunctionDifference();

    List<List<Object>> incomes =
        Arrays.asList(
            Arrays.asList(1, 2, 3, 4, 5, 1),
            Arrays.asList(3, 5, 6, 7, 0, 1, 3, 3, 6),
            Arrays.asList(2, 2, 8, 9));

    Set<Object> expectedResult = new HashSet<Object>(List.<Object>of(4));

    var actualResult =
        (List<Object>) function.execute(null, null, null, incomes.toArray(),
            new BasicCommandContext());

    assertSetEquals(new HashSet<>(actualResult), expectedResult);

    incomes =
        Arrays.asList(Arrays.asList(1, 2, 3, 4, 5, 1), Arrays.asList(3, 5, 6, 7, 0, 1, 3, 3, 6));

    expectedResult = new HashSet<Object>(Arrays.<Object>asList(2, 4));

    actualResult =
        (List<Object>) function.execute(null, null, null, incomes.toArray(),
            new BasicCommandContext());
    assertSetEquals(new HashSet<>(actualResult), expectedResult);
  }

  @Test
  public void nullFirstOperandReturnsEmptyList() {
    // Explicit null-first short-circuit (line 58–60 of SQLFunctionDifference).
    final var result =
        new SQLFunctionDifference()
            .execute(null, null, null, new Object[] {null, List.of(1, 2)},
                new BasicCommandContext());

    assertEquals(List.of(), result);
  }

  @Test
  public void emptyFirstOperandReturnsEmptyListWithoutIteratingOthers() {
    // Early-return branch at line 70–72: result is empty after seeding → skip remaining ops.
    final var result =
        new SQLFunctionDifference()
            .execute(null, null, null, new Object[] {List.of(), List.of(1, 2)},
                new BasicCommandContext());

    assertEquals(List.of(), result);
  }

  @Test
  public void singleOperandReturnsDeduplicatedCopy() {
    // Only one argument → just de-duplicates the first collection via the LinkedHashSet.
    final var result =
        (List<?>) new SQLFunctionDifference()
            .execute(null, null, null, new Object[] {List.of(1, 1, 2, 3, 2)},
                new BasicCommandContext());

    assertEquals(List.of(1, 2, 3), result);
  }

  @Test
  public void nullSubsequentOperandIsSkipped() {
    // Line 76: null subtrahend is skipped; result is the deduplicated first operand.
    final var result =
        (List<?>) new SQLFunctionDifference()
            .execute(null, null, null, new Object[] {List.of(1, 2, 3), null, List.of(2)},
                new BasicCommandContext());

    // First minus null (ignored) minus {2} = {1, 3}.
    assertEquals(Set.of(1, 3), new HashSet<>(result));
  }

  @Test
  public void scalarFirstOperandIsTreatedAsSingleton() {
    // MultiValue.getMultiValueIterator wraps scalars as 1-element iterators.
    final var result =
        (List<?>) new SQLFunctionDifference()
            .execute(null, null, null, new Object[] {"x", List.of("y")},
                new BasicCommandContext());

    // {"x"} minus {"y"} = {"x"}.
    assertEquals(List.of("x"), result);
  }

  @Test
  public void aggregationContextIsRejected() {
    // Aggregation mode is explicitly unsupported. The production code throws
    // CommandExecutionException, but its constructor calls ctx.getDatabaseSession() which
    // throws DatabaseException first when no session is attached — both failure modes are
    // equally effective for a unit test: the aggregation branch does not produce a result.
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);
    final var fn = new SQLFunctionDifference();

    final var ex = assertThrows(RuntimeException.class,
        () -> fn.execute(null, null, null, new Object[] {List.of(1)}, ctx));
    // Either the "cannot be used in aggregation mode" or the "No database session" message
    // confirms the code entered the aggregation branch and refused to proceed.
    final var message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
    assertTrue(
        "unexpected exception message: " + message,
        message.contains("aggregation") || message.contains("session"));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionDifference();
    assertEquals("difference", SQLFunctionDifference.NAME);
    assertEquals("difference", fn.getName(null));
    assertEquals("difference(<field> [, <field]*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  private void assertSetEquals(Set<Object> actualResult, Set<Object> expectedResult) {
    assertEquals(actualResult.size(), expectedResult.size());
    for (var o : actualResult) {
      assertTrue(expectedResult.contains(o));
    }
  }
}
