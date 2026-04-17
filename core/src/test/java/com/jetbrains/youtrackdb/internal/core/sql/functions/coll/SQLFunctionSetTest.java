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
import java.util.Set;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionSet} — de-duplicating counterpart of {@link SQLFunctionList}. Uses a
 * {@code HashSet}, so the order of elements is not asserted. Standalone (no database session).
 */
public class SQLFunctionSetTest {

  @Test
  public void aggregationDeduplicatesRepeatsAcrossCalls() {
    final var fn = new SQLFunctionSet();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {"b"}, ctx);
    fn.execute(null, null, null, new Object[] {"a"}, ctx);

    assertEquals(Set.of("a", "b"), fn.getResult());
  }

  @Test
  public void aggregationExpandsCollectionArgAndDeduplicates() {
    // MultiValue.add unrolls collection args into the set; duplicates collapse.
    final var fn = new SQLFunctionSet();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {List.of(1, 2, 3, 2, 1)}, ctx);
    fn.execute(null, null, null, new Object[] {4}, ctx);

    assertEquals(Set.of(1, 2, 3, 4), fn.getResult());
  }

  @Test
  public void aggregationSkipsNullValues() {
    final var fn = new SQLFunctionSet();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"a"}, ctx);
    fn.execute(null, null, null, new Object[] {null}, ctx);

    assertEquals(Set.of("a"), fn.getResult());
  }

  @Test
  public void aggregationWithOnlyNullProducesNullResult() {
    // Context never initialized when the only call has a null value.
    final var fn = new SQLFunctionSet();

    fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext());

    assertNull(fn.getResult());
  }

  @Test
  public void inlineReturnsFreshSetPerCall() {
    final var fn = new SQLFunctionSet();

    final var first = (Set<?>) fn.execute(null, null, null, new Object[] {1, 2, 2, 3},
        new BasicCommandContext());
    final var second = (Set<?>) fn.execute(null, null, null, new Object[] {10, 20},
        new BasicCommandContext());

    assertEquals(Set.of(1, 2, 3), first);
    assertEquals(Set.of(10, 20), second);
    assertNotSame(first, second);
  }

  @Test
  public void inlineSkipsNullParameters() {
    final var fn = new SQLFunctionSet();

    final var result = (Set<?>) fn.execute(null, null, null,
        new Object[] {"a", null, "b", null, "a"}, new BasicCommandContext());

    assertEquals(Set.of("a", "b"), result);
  }

  @Test
  public void getResultClearsInternalContext() {
    final var fn = new SQLFunctionSet();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"x"}, ctx);
    final var first = fn.getResult();
    final var second = fn.getResult();

    assertEquals(Set.of("x"), first);
    assertNull(second);
  }

  @Test
  public void aggregateResultsInstanceFlagTrueForSingleParam() {
    assertTrue(new SQLFunctionSet().aggregateResults(new Object[] {new Object()}));
  }

  @Test
  public void aggregateResultsInstanceFlagFalseForMultipleParams() {
    assertFalse(new SQLFunctionSet().aggregateResults(new Object[] {new Object(), new Object()}));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionSet();
    assertEquals("set", SQLFunctionSet.NAME);
    assertEquals("set", fn.getName(null));
    assertEquals("set(<value>*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }
}
