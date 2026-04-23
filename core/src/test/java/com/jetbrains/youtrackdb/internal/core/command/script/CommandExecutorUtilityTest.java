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
package com.jetbrains.youtrackdb.internal.core.command.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Behavioral tests for {@link CommandExecutorUtility#transformResult(Object)}.
 *
 * <p><b>Execution-environment note:</b> the utility exists to work around Nashorn's (JDK 8 /
 * JDK 11 up to JDK 15) odd habit of returning a {@link Map} for JS arrays. It locates Nashorn
 * via {@code Class.forName("jdk.nashorn.api.scripting.JSObject")} at static-init time. On
 * JDK 21+ Nashorn was removed from the JDK — the class-not-found branch runs, and
 * {@code java8MethodIsArray} is always {@code null}. The first guard in {@code transformResult}
 * short-circuits for ANY input and returns the input verbatim.
 *
 * <p>Additionally, {@link GlobalConfiguration#SCRIPT_POLYGLOT_USE_GRAAL} defaults to
 * {@code true}, which also causes the utility's static initializer to skip the Nashorn
 * lookup entirely. So on the current build the utility is a no-op on every code path.
 *
 * <p>These tests pin the observable no-op shape across a representative set of input types so
 * that a future environment change (Nashorn reintroduced, or {@code SCRIPT_POLYGLOT_USE_GRAAL}
 * flipped false AND Nashorn actually installed) that wakes the transformation branches is a
 * deliberate, visible event — one or more of these tests will start to fail.
 *
 * <p>WHEN-FIXED: Track 22 — when Nashorn support is formally removed from the YouTrackDB
 * build, {@link CommandExecutorUtility} can be deleted entirely. These tests protect
 * against accidental behavior drift until that happens.
 */
public class CommandExecutorUtilityTest {

  /**
   * null input → null output. Pins the null-safe contract even though the guard is a simple
   * instanceof check.
   */
  @Test
  public void transformResultReturnsNullVerbatim() {
    assertNull(CommandExecutorUtility.transformResult(null));
  }

  /** String input is not a Map → short-circuits on the {@code !(result instanceof Map)} leg. */
  @Test
  public void transformResultReturnsStringVerbatim() {
    final var input = "hello";
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /** Integer (boxed primitive) input is not a Map → short-circuits. */
  @Test
  public void transformResultReturnsNumberVerbatim() {
    final Integer input = 42;
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /** Primitive array (not a Map, not a Collection) → short-circuits. */
  @Test
  public void transformResultReturnsArrayVerbatim() {
    final var input = new int[] {1, 2, 3};
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /** List input is a Collection but not a Map → short-circuits. */
  @Test
  public void transformResultReturnsListVerbatim() {
    final List<String> input = Arrays.asList("a", "b");
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /**
   * Map input: on JDK 21+ (no Nashorn, or SCRIPT_POLYGLOT_USE_GRAAL == true), the utility
   * returns the Map unchanged because {@code java8MethodIsArray == null} wins the guard
   * before the Map check is applied. Pins the identity contract for the Map leg — if Nashorn
   * resurfaces and {@code java8MethodIsArray} becomes non-null, this identity will break and
   * the test will fail, flagging the behavior change.
   */
  @Test
  public void transformResultReturnsMapVerbatimOnJdk21Plus() {
    final Map<String, Object> input = new LinkedHashMap<>();
    input.put("k", "v");
    input.put("n", 1);

    final var output = CommandExecutorUtility.transformResult(input);
    assertSame("Map input must be returned unchanged on JDK 21+ (Nashorn absent)", input, output);
  }

  /** Empty Map: same no-op behavior. */
  @Test
  public void transformResultReturnsEmptyMapVerbatim() {
    final Map<String, Object> input = Collections.emptyMap();
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /**
   * HashMap (concrete type, not just Map interface) to prove the check is against the Map
   * interface rather than a specific subclass. Pins the dispatch via instanceof Map.
   */
  @Test
  public void transformResultReturnsHashMapVerbatim() {
    final Map<String, Object> input = new HashMap<>();
    input.put("a", 1);
    assertSame(input, CommandExecutorUtility.transformResult(input));
  }

  /**
   * Nested Map inside a Map: since the outer Map is returned verbatim (no Nashorn),
   * the nested value is also intact — no deep visitation happens. Pins that no recursive
   * traversal occurs on JDK 21+.
   */
  @Test
  public void transformResultDoesNotRecurseIntoNestedMapsOnJdk21Plus() {
    final Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("deep", "value");

    final Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("nested", inner);

    final var output = CommandExecutorUtility.transformResult(outer);
    assertSame("outer Map identity is preserved", outer, output);
    // The inner Map must also be untouched — no "deep" key reassignment, no transformation.
    @SuppressWarnings("unchecked")
    final Map<String, Object> returnedInner = (Map<String, Object>) outer.get("nested");
    assertSame("inner Map is not mutated or replaced", inner, returnedInner);
    assertEquals("value", returnedInner.get("deep"));
  }
}
