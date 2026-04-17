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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.UUID;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionUUID} — emits a random {@link UUID} string per invocation.
 *
 * <p>Standalone (no DB). Verifies:
 *
 * <ul>
 *   <li>Returned value is a non-empty String parseable by {@link UUID#fromString(String)}.
 *   <li>Two consecutive calls return distinct UUIDs (probabilistically guaranteed at ~2^-122).
 *   <li>{@link SQLFunctionUUID#aggregateResults(Object[])} is {@code false} (non-aggregating).
 *   <li>{@link SQLFunctionUUID#getResult()} is always {@code null} (the function has no state).
 *   <li>Metadata (name, min/max params, syntax).
 * </ul>
 */
public class SQLFunctionUUIDTest {

  @Test
  public void executeReturnsValidUuidString() {
    final var fn = new SQLFunctionUUID();
    final var result = fn.execute(null, null, null, new Object[] {}, new BasicCommandContext());

    assertNotNull(result);
    assertTrue("expected String, got " + result.getClass(), result instanceof String);
    final var s = (String) result;
    assertFalse("UUID string must be non-empty", s.isEmpty());
    // Parses — an invalid UUID throws IllegalArgumentException; a pass confirms the format.
    final var parsed = UUID.fromString(s);
    assertEquals(s, parsed.toString());
  }

  @Test
  public void consecutiveCallsReturnDistinctValues() {
    final var fn = new SQLFunctionUUID();
    final var ctx = new BasicCommandContext();
    final var a = fn.execute(null, null, null, new Object[] {}, ctx);
    final var b = fn.execute(null, null, null, new Object[] {}, ctx);
    assertNotEquals("randomUUID must produce distinct values across calls", a, b);
  }

  @Test
  public void aggregateResultsIsFalse() {
    // UUID has its own aggregateResults(Object[]) overload returning false.
    assertFalse(new SQLFunctionUUID().aggregateResults(new Object[] {}));
  }

  @Test
  public void getResultIsAlwaysNull() {
    final var fn = new SQLFunctionUUID();
    fn.execute(null, null, null, new Object[] {}, new BasicCommandContext());
    assertNull(fn.getResult());
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionUUID();
    assertEquals("uuid", fn.getName(null));
    assertEquals(0, fn.getMinParams());
    assertEquals(0, fn.getMaxParams(null));
    assertEquals("uuid()", fn.getSyntax(null));
  }
}
