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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionList} — builds a list from parameters. With a single parameter the
 * function aggregates across {@code execute()} calls; with multiple parameters it operates inline
 * (stateless, fresh list per call). Standalone (no database session).
 */
public class SQLFunctionListTest {

  @Test
  public void aggregationModeAccumulatesSingleValuesAcrossCalls() {
    // Single parameter per call triggers aggregation (statefull) mode.
    final var fn = new SQLFunctionList();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);
    fn.execute(null, null, null, new Object[] {"c"}, ctx);

    assertEquals(List.of("a", "b", "c"), fn.getResult());
  }

  @Test
  public void aggregationModeExpandsCollectionArgument() {
    // MultiValue.add unrolls a Collection arg into its elements.
    final var fn = new SQLFunctionList();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {List.of(1, 2, 3)}, ctx);
    fn.execute(null, null, null, new Object[] {4}, ctx);

    assertEquals(List.of(1, 2, 3, 4), fn.getResult());
  }

  @Test
  public void mapArgumentIsInsertedAsWholeMapNotExpanded() {
    // SQLFunctionList special-cases Map: it is added as a single element, not unrolled.
    final var fn = new SQLFunctionList();
    final var map = Map.of("k", "v");

    final var result =
        (List<?>) fn.execute(null, null, null, new Object[] {map}, new BasicCommandContext());

    assertEquals(1, result.size());
    assertEquals(map, result.get(0));
  }

  @Test
  public void aggregationModeSkipsNullValues() {
    final var fn = new SQLFunctionList();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {null}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);

    assertEquals(List.of("a", "b"), fn.getResult());
  }

  @Test
  public void inlineModeReturnsFreshListPerCall() {
    // >1 parameters triggers inline mode — each execute() starts with a new list.
    final var fn = new SQLFunctionList();

    final var first =
        (List<?>) fn.execute(null, null, null, new Object[] {1, 2}, new BasicCommandContext());
    final var second =
        (List<?>) fn.execute(null, null, null, new Object[] {3, 4}, new BasicCommandContext());

    assertEquals(List.of(1, 2), first);
    assertEquals(List.of(3, 4), second);
    // Distinct lists: inline mode resets `context` each call.
    assertNotSame(first, second);
  }

  @Test
  public void inlineModeSkipsNullParameters() {
    final var fn = new SQLFunctionList();

    final var result =
        (List<?>) fn.execute(null, null, null, new Object[] {"a", null, "b", null, "c"},
            new BasicCommandContext());

    assertEquals(List.of("a", "b", "c"), result);
  }

  @Test
  public void getResultClearsInternalContext() {
    final var fn = new SQLFunctionList();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);
    final var first = fn.getResult();
    // A second getResult must return null — context was cleared by the first.
    final var second = fn.getResult();

    assertEquals(List.of("a", "b"), first);
    assertNull(second);
  }

  @Test
  public void aggregationModeWithOnlyNullSingleParamProducesNullResult() {
    // Null is skipped, so context is never initialized — getResult returns null.
    final var fn = new SQLFunctionList();

    fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext());

    assertNull(fn.getResult());
  }

  @Test
  public void aggregateResultsInstanceFlagFalse() {
    assertFalse(new SQLFunctionList().aggregateResults(new Object[] {new Object()}));
  }

  @Test
  public void filterResultFalse() {
    // SQLFunctionList collects values; it does not filter them.
    assertFalse(new SQLFunctionList().filterResult());
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionList();
    assertEquals("list", SQLFunctionList.NAME);
    assertEquals("list", fn.getName(null));
    assertEquals("list(<value>*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    // Max -1 indicates unbounded.
    assertEquals(-1, fn.getMaxParams(null));
  }

  @Test
  public void multipleInstancesAggregateIndependently() {
    final var a = new SQLFunctionList();
    final var b = new SQLFunctionList();
    final var ctx = new BasicCommandContext();

    a.execute(null, null, null, new Object[] {"A1"}, ctx);
    a.execute(null, null, null, new Object[] {"A2"}, ctx);
    b.execute(null, null, null, new Object[] {"B1"}, ctx);

    assertEquals(List.of("A1", "A2"), a.getResult());
    assertEquals(List.of("B1"), b.getResult());
  }

  @Test
  public void abstractBaseAggregateResultsTrueForSingleConfiguredParam() {
    final var fn = new SQLFunctionList();
    fn.config(new Object[] {new Object()});
    // SQLFunctionMultiValueAbstract#aggregateResults() returns true iff configuredParameters.length
    // == 1 — this is the implementation path other collection functions share.
    assertTrue(fn.aggregateResults());
  }

  @Test
  public void abstractBaseAggregateResultsFalseForMultipleConfiguredParams() {
    final var fn = new SQLFunctionList();
    fn.config(new Object[] {1, 2, 3});
    assertFalse(fn.aggregateResults());
  }
}
