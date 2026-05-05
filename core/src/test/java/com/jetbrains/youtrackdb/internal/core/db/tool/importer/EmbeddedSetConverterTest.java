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
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link EmbeddedSetConverter}. Mirrors {@link
 * EmbeddedListConverterTest}: embedded sets cannot contain RIDs (rejected at {@code add} time
 * by {@link com.jetbrains.youtrackdb.internal.core.db.record.EmbeddedTrackedMultiValue
 * EmbeddedTrackedMultiValue}), so this test pins the realistic pass-through arms only. The
 * mapped/broken arms for set-shape are exercised by {@link LinkSetConverterTest} and
 * {@link AbstractCollectionConverterTest}.
 */
public class EmbeddedSetConverterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (Track 5+ idiom) — rolls back any transaction the test forgot to
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
   * A set of plain scalars hits no converter dispatch arm, so no element changes and the input
   * is returned by reference. Sets have no order guarantee, but the no-change fast-path is
   * shape-independent.
   */
  @Test
  public void testSetOfScalarsReturnedByReferenceWhenNoChange() {
    var converter = new EmbeddedSetConverter(new ConverterData(session, new HashSet<>()));

    Set<Object> input = session.newEmbeddedSet();
    input.add("a");
    input.add(42);

    var result = converter.convert(session, input);

    assertSame("no-change path must return input by reference", input, result);
  }

  /**
   * An empty embedded set short-circuits the for-loop and returns by reference. Pins the
   * empty-collection fast-path against a refactor that allocates regardless.
   */
  @Test
  public void testEmptySetReturnedByReference() {
    var converter = new EmbeddedSetConverter(new ConverterData(session, new HashSet<>()));

    Set<Object> input = session.newEmbeddedSet();
    var result = converter.convert(session, input);

    assertSame("empty set must pass through by reference", input, result);
    assertTrue(result.isEmpty());
  }
}
