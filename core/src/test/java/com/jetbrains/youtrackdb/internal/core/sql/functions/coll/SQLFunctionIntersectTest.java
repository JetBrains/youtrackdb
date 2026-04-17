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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.util.SupportsContains;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionIntersect} — computes the intersection of N collections. Covers
 * inline (stateless) and aggregation (stateful) paths and the two {@code intersectWith} overloads.
 * RidSet / LinkBag / Identifiable paths require a database session and are covered in Step 4.
 */
public class SQLFunctionIntersectTest {

  @Test
  public void nullFirstParameterShortCircuitsToEmptySet() {
    final var fn = new SQLFunctionIntersect();

    final var result = fn.execute(null, null, null, new Object[] {null, List.of(1, 2)},
        new BasicCommandContext());

    assertEquals(Set.of(), result);
  }

  @Test
  public void emptyCollectionParameterShortCircuitsToEmptySet() {
    final var fn = new SQLFunctionIntersect();

    final var result = fn.execute(null, null, null,
        new Object[] {List.of(1, 2, 3), List.of()}, new BasicCommandContext());

    assertEquals(Set.of(), result);
  }

  @Test
  public void inlineSingleParamDeduplicatesAndReturnsList() {
    // 1-arg inline mode iterates the source and drops duplicates into a LinkedHashSet, then
    // wraps it in an ArrayList to preserve "instanceof Set" downstream semantics.
    final var fn = new SQLFunctionIntersect();

    final var result = (List<?>) fn.execute(null, null, null,
        new Object[] {List.of(1, 1, 2, 3, 2)}, new BasicCommandContext());

    assertEquals(List.of(1, 2, 3), result);
  }

  @Test
  public void inlineTwoListsReturnsCommonElements() {
    final var fn = new SQLFunctionIntersect();

    final var result = (List<?>) fn.execute(null, null, null,
        new Object[] {List.of(1, 2, 3, 4), List.of(3, 4, 5, 6)}, new BasicCommandContext());

    // Iteration order is the left-operand order through the LinkedHashSet.
    assertEquals(List.of(3, 4), result);
  }

  @Test
  public void inlineThreeWayIntersectionNarrowsRepeatedly() {
    final var fn = new SQLFunctionIntersect();

    final var result = (List<?>) fn.execute(null, null, null,
        new Object[] {List.of(1, 2, 3, 4), List.of(2, 3, 4, 5), List.of(3, 4, 6)},
        new BasicCommandContext());

    assertEquals(List.of(3, 4), result);
  }

  @Test
  public void inlineIntersectionWithDisjointOperandsYieldsEmpty() {
    final var fn = new SQLFunctionIntersect();

    final var result = (List<?>) fn.execute(null, null, null,
        new Object[] {List.of(1, 2, 3), List.of(4, 5, 6)}, new BasicCommandContext());

    assertEquals(List.of(), result);
  }

  @Test
  public void inlineSetSecondOperandUsesFastContainsPath() {
    // The `value instanceof Set<?> set` branch in the multi-param loop uses the Set variant of
    // intersectWith — verify it produces the expected intersection.
    final var fn = new SQLFunctionIntersect();

    final var result = (List<?>) fn.execute(null, null, null,
        new Object[] {List.of(1, 2, 3, 4), Set.of(2, 4, 6)}, new BasicCommandContext());

    assertTrue(result.containsAll(List.of(2, 4)));
    assertEquals(2, result.size());
  }

  @Test
  public void aggregationAcceptsCollectionAsInitialContext() {
    // First call seeds the context as the incoming Collection; subsequent calls intersect.
    final var fn = new SQLFunctionIntersect();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {List.of(1, 2, 3, 4)}, ctx);
    fn.execute(null, null, null, new Object[] {List.of(2, 3, 5)}, ctx);
    fn.execute(null, null, null, new Object[] {List.of(3, 6)}, ctx);

    // getResult() normalises the internal context into a Set.
    assertEquals(Set.of(3), fn.getResult());
  }

  @Test
  public void aggregationWithInitialIteratorContext() {
    // First call — value is an Iterator, so the case matches `Iterator iterator ->`.
    final var fn = new SQLFunctionIntersect();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {List.of(1, 2, 3).iterator()}, ctx);
    fn.execute(null, null, null, new Object[] {List.of(2, 3, 4)}, ctx);

    assertEquals(Set.of(2, 3), fn.getResult());
  }

  @Test
  public void aggregationWithIterableContext() {
    // Supply an Iterable that's not a Collection or Iterator, hitting the `Iterable iterable ->`
    // branch.
    final var fn = new SQLFunctionIntersect();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    final Iterable<Integer> iterable = () -> List.of(1, 2, 3).iterator();
    fn.execute(null, null, null, new Object[] {iterable}, ctx);
    fn.execute(null, null, null, new Object[] {List.of(2, 3, 4)}, ctx);

    assertEquals(Set.of(2, 3), fn.getResult());
  }

  @Test
  public void aggregationWithScalarFirstParameter() {
    // A non-Iterable, non-Collection, non-Iterator first value hits the `null, default ->` case
    // which wraps the value in a List.of(...).iterator() — subsequent intersections narrow.
    final var fn = new SQLFunctionIntersect();
    final var ctx = new BasicCommandContext();
    ctx.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {"common"}, ctx);
    fn.execute(null, null, null, new Object[] {List.of("common", "other")}, ctx);

    assertEquals(Set.of("common"), fn.getResult());
  }

  @Test
  public void intersectWithSetOverloadIntersectsAgainstList() {
    final Set<Integer> current = new HashSet<>(List.of(1, 2, 3, 4));

    final var result = SQLFunctionIntersect.intersectWith(current, List.of(2, 4, 6));

    assertEquals(Set.of(2, 4), result);
  }

  @Test
  public void intersectWithSetOverloadAcceptsIterator() {
    final Set<Integer> current = new HashSet<>(List.of(1, 2, 3));

    final var result = SQLFunctionIntersect.intersectWith(current, List.of(2, 3, 4).iterator());

    assertEquals(Set.of(2, 3), result);
  }

  @Test
  public void intersectWithSetOverloadAcceptsScalarViaMultiValueIterator() {
    final Set<Integer> current = new HashSet<>(List.of(1, 2, 3));

    // MultiValue.getMultiValueIterator wraps a scalar as a 1-element iterator.
    final var result = SQLFunctionIntersect.intersectWith(current, 2);

    assertEquals(Set.of(2), result);
  }

  @Test
  public void intersectWithIteratorOverloadAgainstSet() {
    final var current = List.of(1, 2, 3, 4).iterator();

    final var result = SQLFunctionIntersect.intersectWith(current, Set.of(2, 4));

    assertEquals(Set.of(2, 4), new HashSet<>(result));
  }

  @Test
  public void intersectWithIteratorOverloadAgainstCollection() {
    // A List operand goes through MultiValue.toSet and lands in the Collection case.
    final var current = List.of(1, 2, 3, 4).iterator();

    final var result = SQLFunctionIntersect.intersectWith(current, List.of(2, 4));

    assertEquals(Set.of(2, 4), new HashSet<>(result));
  }

  @Test
  public void intersectWithIteratorOverloadAgainstSupportsContains() {
    final var current = List.of("a", "b", "c").iterator();
    final var supports = new StubSupportsContains(Set.of("a", "c"));

    final var result = SQLFunctionIntersect.intersectWith(current, supports);

    assertEquals(Set.of("a", "c"), new HashSet<>(result));
  }

  @Test
  public void intersectWithIteratorOverloadConvertsOpaqueValueToSet() {
    // A non-Set, non-SupportsContains, non-LinkBag right-hand value goes through the outer
    // conversion block (MultiValue.toSet), landing as a Set → matched by the Collection case.
    // 99 becomes {99}; intersection with {1, 2} is empty.
    final var current = List.of(1, 2).iterator();

    final var result = SQLFunctionIntersect.intersectWith(current, 99);

    assertTrue(result.isEmpty());
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionIntersect();
    assertEquals("intersect", SQLFunctionIntersect.NAME);
    assertEquals("intersect", fn.getName(null));
    assertEquals("intersect(<field>*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  @Test
  public void aggregateResultsConfiguredFlag() {
    final var fn = new SQLFunctionIntersect();
    fn.config(new Object[] {List.of()});
    assertTrue(fn.aggregateResults());

    fn.config(new Object[] {List.of(), List.of()});
    assertFalse(fn.aggregateResults());
  }

  /** SupportsContains-backed by a Set — reports fast-contains=true so the outer conversion
   * block is skipped and the inner switch matches the SupportsContains branch. */
  private static final class StubSupportsContains implements SupportsContains<Object> {
    private final Set<?> backing;

    StubSupportsContains(Set<?> backing) {
      this.backing = backing;
    }

    @Override
    public boolean supportsFastContains() {
      return true;
    }

    @Override
    public boolean contains(Object value) {
      return backing.contains(value);
    }
  }
}
