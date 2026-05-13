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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link EmbeddedMapConverter}. Mirrors the embedded list/set tests:
 * embedded maps cannot hold RIDs as values (rejected at {@code put} by
 * {@link com.jetbrains.youtrackdb.internal.core.db.record.EmbeddedTrackedMultiValue
 * EmbeddedTrackedMultiValue}), so this test focuses on pass-through and recursion arms only.
 * The mapped/broken arms for the map shape are exercised by {@link LinkMapConverterTest} and
 * {@link AbstractCollectionConverterTest}.
 */
public class EmbeddedMapConverterTest extends DbTestBase {

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
   * A map of scalar values has no converter dispatch hit on any value. The input must be
   * returned by reference — the map fast-path is symmetric with the list/set fast-paths.
   */
  @Test
  public void testMapOfScalarsReturnedByReferenceWhenNoChange() {
    var converter = new EmbeddedMapConverter(new ConverterData(session, new HashSet<>()));

    Map<String, Object> input = session.newEmbeddedMap();
    input.put("name", "alice");
    input.put("age", 42);

    var result = converter.convert(session, input);

    assertSame("no-change path must return input by reference", input, result);
  }

  /**
   * An empty embedded map short-circuits the for-loop and returns by reference. Pins the
   * empty-collection fast-path so a refactor that always allocates trips the test.
   */
  @Test
  public void testEmptyMapReturnedByReference() {
    var converter = new EmbeddedMapConverter(new ConverterData(session, new HashSet<>()));

    Map<String, Object> input = session.newEmbeddedMap();
    var result = converter.convert(session, input);

    assertSame("empty map must pass through by reference", input, result);
    assertTrue(result.isEmpty());
  }

}
