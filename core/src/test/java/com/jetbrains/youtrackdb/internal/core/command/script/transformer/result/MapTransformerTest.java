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
package com.jetbrains.youtrackdb.internal.core.command.script.transformer.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Behavioral tests for {@link MapTransformer}, the built-in Map → {@link Result} transformer
 * registered by {@link ScriptTransformerImpl}. Covers the three per-entry dispatch branches
 * inside the {@code forEach}:
 *
 * <ol>
 *   <li>value handled by the enclosing transformer (e.g., nested Map) → recursive {@code toResult}
 *   <li>value is an {@link Iterable} and not handled → stream-collected List of Results
 *   <li>value is neither handled nor Iterable → set as raw property
 * </ol>
 *
 * <p>Also pins the {@link ResultInternal} output shape (empty map, non-String keys using
 * {@code toString()}), and the null-value passthrough edge.
 *
 * <p>Extends {@link TestUtilsFixture} because {@code ResultInternal} writes are session-guarded
 * by assertions. The transformer is constructed with a real {@link ScriptTransformerImpl} so the
 * recursion branch uses the actual production dispatch instead of a mock.
 */
public class MapTransformerTest extends TestUtilsFixture {

  private ScriptTransformerImpl scriptTransformer;
  private MapTransformer mapTransformer;

  @Before
  public void initTransformers() {
    scriptTransformer = new ScriptTransformerImpl();
    mapTransformer = new MapTransformer(scriptTransformer);
  }

  /**
   * Empty map → the returned Result has no properties. Pins the "empty in, empty out" contract.
   */
  @Test
  public void transformEmptyMapProducesEmptyResult() {
    final var result = mapTransformer.transform(session, Collections.emptyMap());
    assertNotNull(result);
    assertTrue(
        "empty map must produce a Result with no properties", result.getPropertyNames().isEmpty());
  }

  /**
   * Scalar values go through the plain "not handled, not iterable" branch and are set as-is on
   * the Result.
   */
  @Test
  public void transformMapWithPrimitivesSetsPropertiesVerbatim() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("str", "hello");
    input.put("num", 42);
    input.put("flag", Boolean.TRUE);

    final var result = mapTransformer.transform(session, input);
    assertEquals("hello", result.getProperty("str"));
    assertEquals(Integer.valueOf(42), result.getProperty("num"));
    assertEquals(Boolean.TRUE, result.getProperty("flag"));
  }

  /**
   * A null value in the input Map currently triggers a {@link NullPointerException} inside
   * the forEach lambda because {@link com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl#doesHandleResult(Object)}
   * dereferences {@code value.getClass()} without a null guard. Pins the observed NPE shape
   * so Track 22's hardening (either a null guard before {@code doesHandleResult} or a
   * null-guard inside {@code doesHandleResult} itself) is a deliberate, visible change.
   *
   * <p>WHEN-FIXED: Track 22 — add null-guard in
   * {@code ScriptTransformerImpl.doesHandleResult} OR guard the lambda before calling it.
   * Once fixed, this test should be re-pinned to assert the new null-passthrough contract
   * (the null value is set verbatim, {@code result.hasProperty("maybe") && getProperty ==
   * null}).
   */
  @Test
  public void transformMapWithNullValueThrowsNpe() {
    final Map<Object, Object> input = new HashMap<>();
    input.put("maybe", null);

    assertThrows(
        "null value currently NPEs via doesHandleResult(null).getClass() — WHEN-FIXED: Track 22",
        NullPointerException.class,
        () -> mapTransformer.transform(session, input));
  }

  /**
   * A null element INSIDE an Iterable value triggers the same latent NPE as a top-level null
   * value, but via a different call site: the stream lambda at {@code MapTransformer.java:33}
   * invokes {@code transformer.toResult(db, e)} for each element, and
   * {@code ScriptTransformerImpl.toResult(db, null)} dereferences {@code value.getClass()}
   * without a null guard. Pins the observed NPE shape so Track 22's hardening flips BOTH this
   * test and {@link #transformMapWithNullValueThrowsNpe} consistently. If only one is fixed,
   * the asymmetry is caught.
   *
   * <p>WHEN-FIXED: Track 22 — same fix as {@link #transformMapWithNullValueThrowsNpe} (null
   * guard in {@code toResult} or {@code doesHandleResult}).
   */
  @Test
  public void transformMapWithIterableContainingNullElementThrowsNpe() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("items", Arrays.asList("a", null, "c"));

    assertThrows(
        "null element in iterable NPEs via toResult(db, null).getClass() — WHEN-FIXED: Track 22",
        NullPointerException.class,
        () -> mapTransformer.transform(session, input));
  }

  /**
   * Non-String keys must be coerced via {@code key.toString()} at property-set time. Pins that
   * the Map.Object key type is not required to be String — {@link Integer} keys (and any other
   * Object key) work via the toString() coercion.
   */
  @Test
  public void transformMapWithNonStringKeysCoercesViaToString() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put(1, "one");
    input.put(2, "two");

    final var result = mapTransformer.transform(session, input);
    assertEquals("one", result.getProperty("1"));
    assertEquals("two", result.getProperty("2"));
  }

  /**
   * A nested Map value is handled recursively via {@code transformer.toResult}, producing a
   * {@link Result} nested as a property value. Pins the recursive branch of the transformer.
   */
  @Test
  public void transformMapWithNestedMapRecursesIntoResult() {
    final Map<Object, Object> inner = new LinkedHashMap<>();
    inner.put("deep", "value");

    final Map<Object, Object> outer = new LinkedHashMap<>();
    outer.put("nested", inner);
    outer.put("flat", 1);

    final var result = mapTransformer.transform(session, outer);

    assertEquals(
        "flat scalar must be set verbatim alongside the nested Result",
        Integer.valueOf(1),
        result.getProperty("flat"));

    final var nestedResult = result.<Result>getProperty("nested");
    assertNotNull("nested Map must be materialized into a Result", nestedResult);
    assertEquals("value", nestedResult.getProperty("deep"));
  }

  /**
   * An Iterable value (not handled by a registered transformer) takes the stream/collect branch.
   * Each element of the Iterable is run through {@code toResult}; for plain scalars the default
   * {@code ResultInternal} with a "value" property is produced. Pins the list-of-Results shape.
   */
  @Test
  public void transformMapWithIterableCollectsToListOfResults() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("items", Arrays.asList("a", "b", "c"));

    final var result = mapTransformer.transform(session, input);
    final List<Result> items = result.getProperty("items");
    assertNotNull("iterable values must be collected into a property list", items);
    assertEquals(3, items.size());
    assertEquals("a", items.get(0).getProperty("value"));
    assertEquals("b", items.get(1).getProperty("value"));
    assertEquals("c", items.get(2).getProperty("value"));
  }

  /**
   * An empty Iterable collects to an empty list (never null). Pins the empty-iterable
   * contract — {@code StreamSupport.stream(...).collect(toList())} always produces a non-null
   * list even when the spliterator has zero elements.
   */
  @Test
  public void transformMapWithEmptyIterableCollectsToEmptyList() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("empty", Collections.emptyList());

    final var result = mapTransformer.transform(session, input);
    final List<Result> items = result.getProperty("empty");
    assertNotNull("empty iterable must map to a non-null empty list (not null)", items);
    assertTrue("empty iterable produces an empty list", items.isEmpty());
  }

  /**
   * A nested Map inside a list-valued key exercises BOTH branches:
   *
   * <ul>
   *   <li>outer key maps to an Iterable (list branch)
   *   <li>each list element is a Map (handled branch → recurses)
   * </ul>
   *
   * Pins the branch composition — the list branch uses {@code toResult} per element, which in
   * turn dispatches via the Map.class transformer.
   */
  @Test
  public void transformMapWithListOfMapsRecursesInList() {
    final Map<Object, Object> entryA = new LinkedHashMap<>();
    entryA.put("id", 1);
    final Map<Object, Object> entryB = new LinkedHashMap<>();
    entryB.put("id", 2);

    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("rows", Arrays.asList(entryA, entryB));

    final var result = mapTransformer.transform(session, input);
    final List<Result> rows = result.getProperty("rows");
    assertEquals(2, rows.size());
    assertEquals(Integer.valueOf(1), rows.get(0).getProperty("id"));
    assertEquals(Integer.valueOf(2), rows.get(1).getProperty("id"));
  }
}
