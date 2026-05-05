/*
 *
 *
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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link EmbeddedListConverter}. Embedded collections in YouTrackDB
 * <strong>cannot contain raw RIDs</strong> — {@link
 * com.jetbrains.youtrackdb.internal.core.db.record.EmbeddedTrackedMultiValue#checkValue
 * EmbeddedTrackedMultiValue.checkValue} rejects them with a {@code SchemaException}. The link
 * rewrite arms therefore cannot fire on a real embedded list during import; the production
 * importer relies on the converter's pass-through and recursion behaviour to walk embedded
 * lists for nested embedded structures (which themselves may contain link rewrites).
 *
 * <p>This test pins:
 *
 * <ul>
 *   <li>Pass-through-by-reference for scalar-only and null-bearing lists.</li>
 *   <li>Pass-through-by-reference for empty lists (the for-loop short-circuits).</li>
 *   <li>Recursion into a nested embedded list — {@link ImportConvertersFactory} dispatches
 *       the nested element back to {@link EmbeddedListConverter} with its own pass-through
 *       result, and the outer list returns by reference because nothing changed.</li>
 * </ul>
 *
 * <p>The mapped-rid / broken-rid arms are exercised by {@link LinkListConverterTest} and
 * {@link AbstractCollectionConverterTest}; reproducing them here against an embedded list would
 * fail at the {@code result.add(rid)} call by design.
 */
public class EmbeddedListConverterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (rollback safety net) — rolls back any transaction the test forgot to
   * close so subsequent tests start with a fresh session.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  /**
   * A list of plain scalars (no links, no nested collections) has no converter dispatch hit, so
   * no element changes and the input is returned by reference. Asserting {@code assertSame} is
   * load-bearing: it pins the no-op fast-path.
   */
  @Test
  public void testListOfScalarsReturnedByReferenceWhenNoChange() {
    var converter = new EmbeddedListConverter(new ConverterData(session, new HashSet<>()));

    List<Object> input = session.newEmbeddedList();
    input.add("a");
    input.add(42);
    input.add(3.14);

    var result = converter.convert(session, input);

    assertSame("no-change path must return input by reference", input, result);
  }

  /**
   * A null element in the list must be preserved through the converter — the
   * {@code convertSingleValue} arm for null adds null to the result and reports no change.
   * Combined with all-other-elements being scalars, the list returns by reference.
   */
  @Test
  public void testListWithNullElementReturnedByReferenceWhenNoChange() {
    var converter = new EmbeddedListConverter(new ConverterData(session, new HashSet<>()));

    List<Object> input = session.newEmbeddedList();
    input.add("a");
    input.add(null);
    input.add("b");

    var result = converter.convert(session, input);

    assertSame("list with only null + scalars hits no rewrite arm", input, result);
  }

  /**
   * An empty embedded list short-circuits the for-loop and returns by reference — no converter
   * dispatch happens. Pinning the empty-collection fast-path catches a refactor that would
   * accidentally allocate a fresh list even when no work needed doing.
   */
  @Test
  public void testEmptyListReturnedByReference() {
    var converter = new EmbeddedListConverter(new ConverterData(session, new HashSet<>()));

    List<Object> input = session.newEmbeddedList();
    var result = converter.convert(session, input);

    assertSame("empty list must pass through by reference", input, result);
    assertTrue(result.isEmpty());
    assertEquals(0, result.size());
  }
}
