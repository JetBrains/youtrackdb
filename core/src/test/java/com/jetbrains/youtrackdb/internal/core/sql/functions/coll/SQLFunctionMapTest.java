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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionMap} — builds a map. With a single parameter (a Map) it aggregates
 * across calls; with multiple parameters they must come in even pairs [key, value, key, value,
 * ...]. Standalone (no database session).
 */
public class SQLFunctionMapTest {

  @Test
  public void inlineEvenPairsBuildsMapForSingleCall() {
    // 4 params = 2 pairs, inline mode — each call produces a fresh HashMap.
    final var fn = new SQLFunctionMap();

    final var result = fn.execute(null, null, null, new Object[] {"k1", "v1", "k2", "v2"},
        new BasicCommandContext());

    assertEquals(Map.of("k1", "v1", "k2", "v2"), result);
  }

  @Test
  public void inlineModeStartsFreshMapEachCall() {
    final var fn = new SQLFunctionMap();

    fn.execute(null, null, null, new Object[] {"k1", "v1", "k2", "v2"},
        new BasicCommandContext());
    final var second = fn.execute(null, null, null, new Object[] {"x", "y", "a", "b"},
        new BasicCommandContext());

    assertEquals(Map.of("x", "y", "a", "b"), second);
  }

  @Test
  public void inlinePairWithOnlyNullValueLeavesContextNull() {
    // Aggregation-compatible pair path (length <= 2 && context == null). With a null value,
    // the put() is skipped and the HashMap is never created — the function returns null, and
    // a subsequent getResult() also returns null. This pins the null-vs-empty contract.
    final var fn = new SQLFunctionMap();

    assertNull(
        fn.execute(null, null, null, new Object[] {"k", null}, new BasicCommandContext()));
    assertNull(fn.getResult());
  }

  @Test
  public void inlineSkipsNullValuesButKeepsKeys() {
    // Per SQLFunctionMap.execute: if value is null, the put() is skipped entirely.
    final var fn = new SQLFunctionMap();

    final var result = (Map<?, ?>) fn.execute(null, null, null,
        new Object[] {"k1", "v1", "k2", null, "k3", "v3"},
        new BasicCommandContext());

    assertEquals(Map.of("k1", "v1", "k3", "v3"), result);
  }

  @Test
  public void aggregationPairAccumulatesAcrossCalls() {
    // Single pair per call (2 params, NOT >2) → aggregation path.
    final var fn = new SQLFunctionMap();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"k1", "v1"}, ctx);
    fn.execute(null, null, null, new Object[] {"k2", "v2"}, ctx);
    fn.execute(null, null, null, new Object[] {"k3", "v3"}, ctx);

    assertEquals(Map.of("k1", "v1", "k2", "v2", "k3", "v3"), fn.getResult());
  }

  @Test
  public void singleMapParameterIsPutAllInAggregationMode() {
    final var fn = new SQLFunctionMap();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {Map.of("a", 1)}, ctx);
    fn.execute(null, null, null, new Object[] {Map.of("b", 2)}, ctx);

    assertEquals(Map.of("a", 1, "b", 2), fn.getResult());
  }

  @Test
  public void singleMapParameterWithNullReturnsNull() {
    final var fn = new SQLFunctionMap();

    assertNull(
        fn.execute(null, null, null, new Object[] {null}, new BasicCommandContext()));
  }

  private static final String MAP_EXPECTED_MESSAGE =
      "Map function: expected a map or pairs of parameters as key, value";

  @Test
  public void singleNonMapParameterThrowsIllegalArgument() {
    final var fn = new SQLFunctionMap();

    final var ex = assertThrows(IllegalArgumentException.class,
        () -> fn.execute(null, null, null, new Object[] {"not-a-map"}, new BasicCommandContext()));
    // Pin the exact message so a swap between the single-non-map and odd-count branches
    // cannot silently pass (production uses the same string in both).
    assertEquals(MAP_EXPECTED_MESSAGE, ex.getMessage());
  }

  @Test
  public void oddNumberOfParametersThrowsIllegalArgument() {
    final var fn = new SQLFunctionMap();

    final var ex = assertThrows(IllegalArgumentException.class,
        () -> fn.execute(null, null, null,
            new Object[] {"k1", "v1", "orphan"}, new BasicCommandContext()));
    assertEquals(MAP_EXPECTED_MESSAGE, ex.getMessage());
  }

  @Test
  public void keyCanBeNullWhenValueIsNonNull() {
    final var fn = new SQLFunctionMap();

    @SuppressWarnings("unchecked")
    final Map<Object, Object> result = (Map<Object, Object>) fn.execute(null, null, null,
        new Object[] {null, "x", "k2", "v2"}, new BasicCommandContext());

    // HashMap permits null keys; only the null-value path is skipped.
    assertEquals(2, result.size());
    assertTrue(result.containsKey(null));
    assertEquals("x", result.get(null));
    assertEquals("v2", result.get("k2"));
  }

  @Test
  public void getResultClearsInternalContext() {
    final var fn = new SQLFunctionMap();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"k", "v"}, ctx);
    final var first = fn.getResult();
    final var second = fn.getResult();

    assertEquals(Map.of("k", "v"), first);
    assertNull(second);
  }

  @Test
  public void aggregateResultsTrueWhenConfiguredParamsLengthIsOne() {
    final var fn = new SQLFunctionMap();
    fn.config(new Object[] {Map.of()});
    assertTrue(fn.aggregateResults());
  }

  @Test
  public void aggregateResultsTrueWhenConfiguredParamsLengthIsTwo() {
    // Single key-value pair => aggregation, per the overridden method.
    final var fn = new SQLFunctionMap();
    fn.config(new Object[] {"k", "v"});
    assertTrue(fn.aggregateResults());
  }

  @Test
  public void aggregateResultsFalseForInlineMultiPair() {
    final var fn = new SQLFunctionMap();
    fn.config(new Object[] {"k1", "v1", "k2", "v2"});
    assertFalse(fn.aggregateResults());
  }

  @Test
  public void instanceAggregateResultsMatchesOverload() {
    final var fn = new SQLFunctionMap();
    // The int[]-based overload takes configuredParameters directly.
    assertTrue(fn.aggregateResults(new Object[] {Map.of()}));
    assertFalse(fn.aggregateResults(new Object[] {"k1", "v1", "k2", "v2"}));
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    final var fn = new SQLFunctionMap();
    assertEquals("map", SQLFunctionMap.NAME);
    assertEquals("map", fn.getName(null));
    assertEquals("map(<map>|[<key>,<value>]*)", fn.getSyntax(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  @Test
  public void inlineRespectsInsertionOrderOfPairs() {
    final var fn = new SQLFunctionMap();

    @SuppressWarnings("unchecked")
    final Map<Object, Object> result = (Map<Object, Object>) fn.execute(null, null, null,
        new Object[] {"k1", "v1", "k2", "v2", "k3", "v3"}, new BasicCommandContext());

    // HashMap does not guarantee ordering, so compare semantically.
    final var expected = new HashMap<>();
    expected.put("k1", "v1");
    expected.put("k2", "v2");
    expected.put("k3", "v3");
    assertEquals(expected, result);
  }

  @Test
  public void aggregationMapReceivesPutAllOverwritingSemantics() {
    // putAll means the second call replaces the first's value for an overlapping key.
    final var fn = new SQLFunctionMap();
    final var ctx = new BasicCommandContext();

    final var first = new LinkedHashMap<String, String>();
    first.put("k", "v1");
    fn.execute(null, null, null, new Object[] {first}, ctx);

    final var second = new LinkedHashMap<String, String>();
    second.put("k", "v2");
    fn.execute(null, null, null, new Object[] {second}, ctx);

    assertEquals(Map.of("k", "v2"), fn.getResult());
  }
}
