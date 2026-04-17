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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import org.junit.Test;

/**
 * Tests for the shared behaviour of {@link SQLFunctionMultiValueAbstract}: the {@code context}
 * accumulator, {@code getResult()}, and the {@code aggregateResults()} flag driven by
 * {@code configuredParameters.length}. Exercised through a minimal in-test subclass so the
 * assertions are isolated from any specific collection/map/set implementation.
 */
public class SQLFunctionMultiValueAbstractTest {

  @Test
  public void aggregateResultsReturnsTrueWhenConfiguredWithSingleParam() {
    final var fn = new ProbeFunction();
    fn.config(new Object[] {42});
    assertTrue(fn.aggregateResults());
  }

  @Test
  public void aggregateResultsReturnsFalseWhenConfiguredWithMultipleParams() {
    final var fn = new ProbeFunction();
    fn.config(new Object[] {1, 2});
    assertFalse(fn.aggregateResults());
  }

  @Test
  public void aggregateResultsReturnsFalseWhenConfiguredWithZeroParams() {
    final var fn = new ProbeFunction();
    fn.config(new Object[] {});
    assertFalse(fn.aggregateResults());
  }

  @Test
  public void getResultReturnsCurrentContextWithoutClearing() {
    // The default getResult in SQLFunctionMultiValueAbstract returns context WITHOUT clearing it
    // (subclasses like SQLFunctionList/SQLFunctionSet override this to clear).
    final var fn = new ProbeFunction();

    assertNull(fn.getResult());
    fn.setContext("accumulated");
    assertEquals("accumulated", fn.getResult());
    assertEquals("accumulated", fn.getResult());
  }

  @Test
  public void nameIsPropagatedToAbstractBase() {
    final var fn = new ProbeFunction();
    assertEquals("probe", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(null));
  }

  /** Minimal concrete subclass exposing the protected {@code context} field for assertions. */
  private static final class ProbeFunction extends SQLFunctionMultiValueAbstract<String> {
    ProbeFunction() {
      super("probe", 1, -1);
    }

    void setContext(String value) {
      this.context = value;
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      return null;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return "probe()";
    }
  }
}
