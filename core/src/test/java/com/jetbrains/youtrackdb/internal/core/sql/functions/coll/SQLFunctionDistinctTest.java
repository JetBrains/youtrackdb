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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionDistinct} — an aggregate filter that returns each distinct value
 * exactly once and null for every subsequent repeat. Standalone (no database session).
 */
public class SQLFunctionDistinctTest {

  @Test
  public void firstOccurrenceOfEachValueIsReturned() {
    final var fn = new SQLFunctionDistinct();
    final var ctx = new BasicCommandContext();

    assertEquals("a",
        fn.execute(null, null, null, new Object[] {"a"}, ctx));
    assertEquals("b",
        fn.execute(null, null, null, new Object[] {"b"}, ctx));
    assertEquals(42,
        fn.execute(null, null, null, new Object[] {42}, ctx));
  }

  @Test
  public void repeatedValueReturnsNull() {
    final var fn = new SQLFunctionDistinct();
    final var ctx = new BasicCommandContext();

    assertEquals("a",
        fn.execute(null, null, null, new Object[] {"a"}, ctx));
    assertNull(fn.execute(null, null, null, new Object[] {"a"}, ctx));
    assertNull(fn.execute(null, null, null, new Object[] {"a"}, ctx));
  }

  @Test
  public void nullInputReturnsNullAndDoesNotPoisonFurtherCalls() {
    final var fn = new SQLFunctionDistinct();
    final var ctx = new BasicCommandContext();

    assertNull(fn.execute(null, null, null, new Object[] {null}, ctx));
    // Null must not get recorded as a distinct value — a subsequent real value still emits.
    assertEquals(1, fn.execute(null, null, null, new Object[] {1}, ctx));
    assertNull(fn.execute(null, null, null, new Object[] {1}, ctx));
  }

  @Test
  public void differentTypesAreTrackedIndependently() {
    final var fn = new SQLFunctionDistinct();
    final var ctx = new BasicCommandContext();

    assertEquals(1, fn.execute(null, null, null, new Object[] {1}, ctx));
    // Integer 1 and Long 1 are NOT equal — both emit.
    assertEquals(1L, fn.execute(null, null, null, new Object[] {1L}, ctx));
    // Integer 1 re-input — second emission suppressed.
    assertNull(fn.execute(null, null, null, new Object[] {1}, ctx));
  }

  @Test
  public void filterResultReportsTrue() {
    assertTrue(new SQLFunctionDistinct().filterResult());
  }

  @Test
  public void nameMatchesConstant() {
    assertEquals("distinct", SQLFunctionDistinct.NAME);
    assertEquals("distinct", new SQLFunctionDistinct().getName(null));
  }

  @Test
  public void syntaxMentionsField() {
    assertEquals("distinct(<field>)", new SQLFunctionDistinct().getSyntax(null));
  }

  @Test
  public void minAndMaxParamsAreOne() {
    final var fn = new SQLFunctionDistinct();
    assertEquals(1, fn.getMinParams());
    assertEquals(1, fn.getMaxParams(null));
  }

  @Test
  public void aggregateResultsReturnsFalse() {
    // Distinct is a filter, not an aggregate — it does not collect into a final result.
    assertFalse(new SQLFunctionDistinct().aggregateResults());
  }

  @Test
  public void getResultReturnsNullByDefault() {
    // Distinct does not override getResult; the abstract default (null) applies.
    assertNull(new SQLFunctionDistinct().getResult());
  }

  @Test
  public void separateInstancesTrackStateIndependently() {
    final var a = new SQLFunctionDistinct();
    final var b = new SQLFunctionDistinct();
    final var ctx = new BasicCommandContext();

    assertEquals("x", a.execute(null, null, null, new Object[] {"x"}, ctx));
    // Instance b has never seen "x" — it must emit on its first call.
    assertEquals("x", b.execute(null, null, null, new Object[] {"x"}, ctx));
  }

  @Test
  public void returnedValueIsSameReferenceAsInput() {
    final var fn = new SQLFunctionDistinct();
    final var probe = new Object();

    assertSame(probe,
        fn.execute(null, null, null, new Object[] {probe}, new BasicCommandContext()));
  }
}
