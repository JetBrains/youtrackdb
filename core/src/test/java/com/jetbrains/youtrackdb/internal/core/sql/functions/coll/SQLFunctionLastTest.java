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

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionLast} — returns the last element of a multi-value input (list,
 * set, array, map) or the value itself when not multi-valued. Standalone (no database session).
 */
public class SQLFunctionLastTest {

  @Test
  public void lastElementOfListIsReturned() {
    final var fn = new SQLFunctionLast();

    assertEquals("c",
        fn.execute(null, null, null, new Object[] {List.of("a", "b", "c")},
            new BasicCommandContext()));
  }

  @Test
  public void lastElementOfArrayIsReturned() {
    final var fn = new SQLFunctionLast();
    final var input = new Object[] {new Object[] {1, 2, 3}};

    assertEquals(3, fn.execute(null, null, null, input, new BasicCommandContext()));
  }

  @Test
  public void lastElementOfInsertionOrderedSetIsReturned() {
    final var fn = new SQLFunctionLast();
    final var set = new LinkedHashSet<>(Arrays.asList("alpha", "beta", "gamma"));

    assertEquals("gamma",
        fn.execute(null, null, null, new Object[] {set}, new BasicCommandContext()));
  }

  @Test
  public void lastEntryValueOfInsertionOrderedMapIsReturned() {
    final var fn = new SQLFunctionLast();
    final var map = new LinkedHashMap<String, Integer>();
    map.put("x", 10);
    map.put("y", 20);

    assertEquals(20,
        fn.execute(null, null, null, new Object[] {map}, new BasicCommandContext()));
  }

  @Test
  public void nonMultiValueInputPassesThrough() {
    final var fn = new SQLFunctionLast();

    assertEquals("hello",
        fn.execute(null, null, null, new Object[] {"hello"}, new BasicCommandContext()));
    assertEquals(7,
        fn.execute(null, null, null, new Object[] {7}, new BasicCommandContext()));
  }

  @Test
  public void nullInputPassesThrough() {
    final var fn = new SQLFunctionLast();

    assertNull(fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemIsResolvedBeforeLastExtraction() {
    final var resolved = List.of("first", "middle", "tail");
    final var filterItem = new StubFilterItem(resolved);
    final var fn = new SQLFunctionLast();

    assertEquals("tail",
        fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemResolvingToScalarPassesThrough() {
    final var filterItem = new StubFilterItem("scalar");
    final var fn = new SQLFunctionLast();

    assertEquals("scalar",
        fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemResolvingToNullReturnsNull() {
    final var filterItem = new StubFilterItem(null);
    final var fn = new SQLFunctionLast();

    assertNull(
        fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionLast();
    assertEquals("last", SQLFunctionLast.NAME);
    assertEquals("last", fn.getName(null));
    assertEquals("last(<field>)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(1, fn.getMaxParams(null));
  }

  /** Minimal SQLFilterItem stub — returns a pre-configured value. */
  private static final class StubFilterItem implements SQLFilterItem {
    private final Object value;

    StubFilterItem(Object value) {
      this.value = value;
    }

    @Override
    public Object getValue(Result record, Object currentResult, CommandContext ctx) {
      return value;
    }
  }
}
