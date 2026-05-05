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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinkMapConverter}. Mirrors {@link EmbeddedMapConverterTest}'s
 * key-preservation pattern but operates on a link-typed map (values are {@link Identifiable}).
 */
public class LinkMapConverterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (rollback safety net).
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
   * A link map with no mappings returns by reference.
   */
  @Test
  public void testLinkMapWithNoMappingsReturnedByReference() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    var converter = new LinkMapConverter(new ConverterData(session, new HashSet<>()));

    Map<String, Identifiable> input = session.newLinkMap();
    input.put("primary", new RecordId(20, 1));

    var result = converter.convert(session, input);

    assertSame(input, result);
  }

  /**
   * Mapped rid in the value position is rewritten; keys are preserved exactly.
   */
  @Test
  public void testLinkMapWithMappedRidRewritesValueAndPreservesKeys() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    var converter = new LinkMapConverter(new ConverterData(session, new HashSet<>()));

    Map<String, Identifiable> input = session.newLinkMap();
    input.put("link", fromRid);
    input.put("other", new RecordId(20, 1));

    var result = converter.convert(session, input);

    assertNotSame(input, result);
    assertEquals(2, result.size());
    assertEquals("link key must point at the mapped target rid",
        toRid, result.get("link").getIdentity());
    assertEquals("other key must remain unchanged",
        new RecordId(20, 1), result.get("other").getIdentity());
  }

  /**
   * Broken rid drops the entire entry.
   */
  @Test
  public void testLinkMapWithBrokenRidDropsEntry() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    var converter = new LinkMapConverter(new ConverterData(session, brokenRids));

    Map<String, Identifiable> input = session.newLinkMap();
    input.put("broken", brokenRid);
    input.put("kept", new RecordId(20, 1));

    var result = converter.convert(session, input);

    assertNotSame(input, result);
    assertEquals(1, result.size());
    assertTrue(result.containsKey("kept"));
  }

  /**
   * Empty link map short-circuits and returns by reference.
   */
  @Test
  public void testEmptyLinkMapReturnedByReference() {
    var converter = new LinkMapConverter(new ConverterData(session, new HashSet<>()));

    Map<String, Identifiable> input = session.newLinkMap();
    var result = converter.convert(session, input);

    assertSame(input, result);
    assertTrue(result.isEmpty());
  }
}
