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
import static org.junit.Assert.assertSame;

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
 * Tests for {@link SQLFunctionFirst} — returns the first element of a multi-value input (list,
 * set, array, map) or the value itself when not multi-valued. Standalone (no database session).
 */
public class SQLFunctionFirstTest {

  @Test
  public void firstElementOfListIsReturned() {
    final var fn = new SQLFunctionFirst();

    assertEquals("a",
        fn.execute(null, null, null, new Object[] {List.of("a", "b", "c")},
            new BasicCommandContext()));
  }

  @Test
  public void firstElementOfArrayIsReturned() {
    final var fn = new SQLFunctionFirst();
    final var input = new Object[] {new Object[] {1, 2, 3}};

    assertEquals(1, fn.execute(null, null, null, input, new BasicCommandContext()));
  }

  @Test
  public void firstElementOfInsertionOrderedSetIsReturned() {
    final var fn = new SQLFunctionFirst();
    final var set = new LinkedHashSet<>(Arrays.asList("alpha", "beta", "gamma"));

    assertEquals("alpha",
        fn.execute(null, null, null, new Object[] {set}, new BasicCommandContext()));
  }

  @Test
  public void firstEntryValueOfInsertionOrderedMapIsReturned() {
    final var fn = new SQLFunctionFirst();
    final var map = new LinkedHashMap<String, Integer>();
    map.put("x", 10);
    map.put("y", 20);

    // MultiValue.getFirstValue on a Map returns the first value, not the entry.
    assertEquals(10,
        fn.execute(null, null, null, new Object[] {map}, new BasicCommandContext()));
  }

  @Test
  public void nonMultiValueInputPassesThrough() {
    final var fn = new SQLFunctionFirst();

    assertEquals("hello",
        fn.execute(null, null, null, new Object[] {"hello"}, new BasicCommandContext()));
    assertEquals(7,
        fn.execute(null, null, null, new Object[] {7}, new BasicCommandContext()));
  }

  @Test
  public void nullInputPassesThrough() {
    final var fn = new SQLFunctionFirst();

    assertNull(fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext()));
  }

  @Test
  public void emptyListReturnsNull() {
    final var fn = new SQLFunctionFirst();

    assertNull(
        fn.execute(null, null, null, new Object[] {List.of()}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemIsResolvedBeforeFirstExtraction() {
    // The filter item resolves to a 2-element list; first() must return the first element,
    // not the filter item itself.
    final var resolved = List.of("resolved-first", "resolved-second");
    final var filterItem = new StubFilterItem(resolved);
    final var fn = new SQLFunctionFirst();

    assertEquals("resolved-first",
        fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemResolvingToScalarPassesThrough() {
    final var filterItem = new StubFilterItem("scalar");
    final var fn = new SQLFunctionFirst();

    assertEquals("scalar",
        fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void sqlFilterItemResolvingToNullReturnsNull() {
    final var filterItem = new StubFilterItem(null);
    final var fn = new SQLFunctionFirst();

    assertNull(fn.execute(null, null, null, new Object[] {filterItem}, new BasicCommandContext()));
  }

  @Test
  public void singletonCollectionReturnsItsElement() {
    final var fn = new SQLFunctionFirst();
    final var probe = new Object();

    // Identity preserved — MultiValue.getFirstValue returns the reference, not a copy.
    assertSame(probe,
        fn.execute(null, null, null, new Object[] {List.of(probe)}, new BasicCommandContext()));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionFirst();
    assertEquals("first", SQLFunctionFirst.NAME);
    assertEquals("first", fn.getName(null));
    assertEquals("first(<field>)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(1, fn.getMaxParams(null));
  }

  /** Minimal SQLFilterItem stub — captures the context and returns a pre-configured value. */
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
