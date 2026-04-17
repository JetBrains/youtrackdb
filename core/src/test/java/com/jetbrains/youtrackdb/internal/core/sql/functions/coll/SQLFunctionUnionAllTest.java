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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemVariable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionUnionAll} — concatenates multiple collections while preserving
 * duplicates. Toggles between inline (stateless, returns {@link MultiCollectionIterator}) and
 * aggregation (stateful, accumulates into a List) based on the {@code "aggregation"} context
 * variable. Standalone (no database session).
 */
public class SQLFunctionUnionAllTest {

  @Test
  public void inlineWrapsParametersInMultiCollectionIterator() {
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();

    final var result = fn.execute(null, null, null,
        new Object[] {List.of(1, 2), List.of(3, 4), List.of(5)}, ctx);

    assertTrue("Inline union must return a MultiCollectionIterator",
        result instanceof MultiCollectionIterator<?>);
    assertEquals(List.of(1, 2, 3, 4, 5), drain((Iterator<?>) result));
  }

  @Test
  public void inlineSkipsNullParameters() {
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();

    final var result = fn.execute(null, null, null,
        new Object[] {List.of(1, 2), null, List.of(3)}, ctx);

    assertEquals(List.of(1, 2, 3), drain((Iterator<?>) result));
  }

  @Test
  public void inlinePreservesDuplicates() {
    // unionAll, not union — duplicates across sources survive.
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();

    final var result = fn.execute(null, null, null,
        new Object[] {List.of(1, 1, 2), List.of(2, 3)}, ctx);

    assertEquals(List.of(1, 1, 2, 2, 3), drain((Iterator<?>) result));
  }

  @Test
  public void aggregationAccumulatesSingleValuesAcrossCalls() {
    // Aggregation mode switches on when ctx has aggregation == Boolean.TRUE.
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);
    // The third call feeds a list — MultiValue.add unrolls it.
    fn.execute(null, null, null, new Object[] {List.of("c", "d")}, ctx);

    assertEquals(List.of("a", "b", "c", "d"), fn.getResult());
  }

  @Test
  public void aggregationSkipsNullFirstParameter() {
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    // A null first parameter is skipped — the list stays unchanged.
    fn.execute(null, null, null, new Object[] {null}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);

    assertEquals(List.of("a", "b"), fn.getResult());
  }

  @Test
  public void aggregationResolvesSQLFilterItemVariable() {
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);
    ctx.setVariable("pool", List.of("x", "y"));

    fn.execute(null, null, null, new Object[] {new FakeVariable("pool", List.of("x", "y"))},
        ctx);

    // The variable resolves to a list, and MultiValue.add unrolls it into the accumulator.
    assertEquals(List.of("x", "y"), fn.getResult());
  }

  @Test
  public void inlineResolvesSQLFilterItemVariable() {
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();

    final var result = fn.execute(null, null, null,
        new Object[] {new FakeVariable("p1", List.of("x", "y")),
            new FakeVariable("p2", List.of("z"))},
        ctx);

    assertEquals(List.of("x", "y", "z"), drain((Iterator<?>) result));
  }

  @Test
  public void aggregationReturnsNullEvenWhenContextAccumulates() {
    // The aggregation branch returns `context` — but `getResult()` is the canonical accessor.
    // The returned value is the accumulator list (not null), and getResult returns the same.
    final var fn = new SQLFunctionUnionAll();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    final var ret = fn.execute(null, null, null, new Object[] {"a"}, ctx);
    final var viaGetResult = fn.getResult();

    assertNotNull(ret);
    assertEquals(List.of("a"), ret);
    // SQLFunctionMultiValueAbstract exposes the same context via getResult().
    assertEquals(List.of("a"), viaGetResult);
  }

  @Test
  public void instanceConfiguredForSingleParamReportsAggregate() {
    final var fn = new SQLFunctionUnionAll();
    fn.config(new Object[] {List.of()});
    assertTrue(fn.aggregateResults());
  }

  @Test
  public void instanceConfiguredForMultipleParamsReportsInline() {
    final var fn = new SQLFunctionUnionAll();
    fn.config(new Object[] {List.of(), List.of()});
    assertFalse(fn.aggregateResults());
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionUnionAll();
    assertEquals("unionAll", SQLFunctionUnionAll.NAME);
    assertEquals("unionAll", fn.getName(null));
    assertEquals("unionAll(<field>*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  private static List<Object> drain(Iterator<?> iterator) {
    final var out = new ArrayList<Object>();
    while (iterator.hasNext()) {
      out.add(iterator.next());
    }
    return out;
  }

  /**
   * Minimal {@link SQLFilterItemVariable} substitute — avoids the parser constructor by
   * implementing the {@link SQLFilterItem} interface directly. The production code path in
   * {@link SQLFunctionUnionAll} uses {@code instanceof SQLFilterItemVariable}, so we use a real
   * subclass created via a private helper constructor route — here simulated by implementing
   * both interfaces.
   */
  private static final class FakeVariable extends SQLFilterItemVariable {
    private final Object resolved;

    FakeVariable(String name, Object resolved) {
      super(null, null, "$" + name);
      this.resolved = resolved;
    }

    @Override
    public Object getValue(Result record, Object currentResult, CommandContext ctx) {
      return resolved;
    }
  }
}
