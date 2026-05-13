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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionCount} — the {@code count()} SQL aggregator. Standalone (no DB).
 *
 * <p>Semantics under test:
 *
 * <ul>
 *   <li>Non-null parameter always increments the cumulative counter.
 *   <li>{@code null} parameter is skipped — this is how {@code count(field)} differs from
 *       {@code count(*)}.
 *   <li>Zero-argument call (invalid by {@code minParams=1} but not enforced by the function
 *       itself) still increments — a drift guard in case dispatch-time validation changes.
 *   <li>{@link SQLFunctionCount#aggregateResults()} is {@code true} and {@code getResult()}
 *       returns the accumulated total unchanged.
 *   <li>{@link SQLFunctionCount#setResult(Object)} coerces any {@link Number} via
 *       {@code longValue()}.
 * </ul>
 */
public class SQLFunctionCountTest {

  @Test
  public void nonNullParameterIncrementsCounter() {
    final var fn = new SQLFunctionCount();
    final var ctx = new BasicCommandContext();

    assertEquals(1L, fn.execute(null, null, null, new Object[] {"a"}, ctx));
    assertEquals(2L, fn.execute(null, null, null, new Object[] {"b"}, ctx));
    assertEquals(3L, fn.execute(null, null, null, new Object[] {42}, ctx));
  }

  @Test
  public void nullParameterDoesNotIncrementCounter() {
    final var fn = new SQLFunctionCount();
    final var ctx = new BasicCommandContext();

    assertEquals(1L, fn.execute(null, null, null, new Object[] {"a"}, ctx));
    assertEquals(1L, fn.execute(null, null, null, new Object[] {null}, ctx));
    assertEquals(1L, fn.execute(null, null, null, new Object[] {null}, ctx));
    assertEquals(2L, fn.execute(null, null, null, new Object[] {"b"}, ctx));
  }

  @Test
  public void zeroArgArrayIncrementsCounter() {
    // count() is declared with minParams=1, but the body treats length==0 as "count always".
    // A drift guard: if a refactor tightens the guard, this test changes with intent.
    final var fn = new SQLFunctionCount();
    final var ctx = new BasicCommandContext();

    assertEquals(1L, fn.execute(null, null, null, new Object[] {}, ctx));
    assertEquals(2L, fn.execute(null, null, null, new Object[] {}, ctx));
  }

  @Test
  public void aggregateResultsIsTrue() {
    assertTrue(new SQLFunctionCount().aggregateResults());
  }

  @Test
  public void getResultReflectsCumulativeTotalWithoutClearing() {
    final var fn = new SQLFunctionCount();
    final var ctx = new BasicCommandContext();

    fn.execute(null, null, null, new Object[] {"x"}, ctx);
    fn.execute(null, null, null, new Object[] {"y"}, ctx);

    assertEquals(2L, fn.getResult());
    // getResult must not clear — subsequent call must start from 2, not 0.
    assertEquals(3L, fn.execute(null, null, null, new Object[] {"z"}, ctx));
    assertEquals(3L, fn.getResult());
  }

  @Test
  public void setResultCoercesLongShortDoubleAllViaLongValue() {
    final var fn = new SQLFunctionCount();

    fn.setResult(42L);
    assertEquals(42L, fn.getResult());

    // Short/Integer/Double/BigDecimal all go through Number.longValue() — fractional part lost.
    fn.setResult((short) 7);
    assertEquals(7L, fn.getResult());

    fn.setResult(100);
    assertEquals(100L, fn.getResult());

    fn.setResult(99.9);
    assertEquals(99L, fn.getResult());

    // Negative value — pins sign preservation. A mutation like Math.abs(...) would fail here.
    fn.setResult(-5L);
    assertEquals(-5L, fn.getResult());
  }

  @Test(expected = ClassCastException.class)
  public void setResultWithNonNumberThrowsClassCastException() {
    // Pins the hard cast (Number) iResult — non-Number input (e.g. String) is unsupported.
    new SQLFunctionCount().setResult("not a number");
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionCount();
    assertEquals("count", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(1, fn.getMaxParams(null));
    assertEquals("count(<field>|*)", fn.getSyntax(null));
  }
}
