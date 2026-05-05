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
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinkBagConverter}. Unlike list/set/map converters, the bag
 * iterates over {@link com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair RidPair}
 * tuples and dispatches each {@code primaryRid} through {@link LinkConverter}; the resulting
 * rid is added back to a fresh {@link LinkBag} via the callback. Because the bag's internal
 * {@code EmbeddedLinkBag} delegate enforces an active transaction at {@code add} time, every
 * test wraps its converter call in {@code session.executeInTx}.
 */
public class LinkBagConverterTest extends DbTestBase {

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
   * A bag whose entries are not in the mapping table and not broken returns by reference.
   * The SELECT runs for each entry but finds no match.
   */
  @Test
  public void testLinkBagWithNoMappingsReturnedByReference() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    session.executeInTx(tx -> {
      var converter = new LinkBagConverter(new ConverterData(session, new HashSet<>()));

      var input = new LinkBag(session);
      input.add(new RecordId(20, 1));
      input.add(new RecordId(20, 2));

      var result = converter.convert(session, input);

      assertSame("no-change path must return input by reference", input, result);
    });
  }

  /**
   * A bag containing a mapped rid produces a fresh {@link LinkBag} with the rewrite in place.
   */
  @Test
  public void testLinkBagWithMappedRidRewritesToFreshBag() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    session.executeInTx(tx -> {
      var converter = new LinkBagConverter(new ConverterData(session, new HashSet<>()));

      var input = new LinkBag(session);
      input.add(fromRid);
      input.add(new RecordId(20, 1));

      var result = converter.convert(session, input);

      assertNotSame("rewrite path must return a fresh bag", input, result);
      assertEquals(2, result.size());
      assertTrue("mapped target rid must be present in the rewritten bag",
          result.contains(toRid));
      assertTrue("untouched rid must be present",
          result.contains(new RecordId(20, 1)));
    });
  }

  /**
   * A broken rid is dropped from the result bag (callback is never invoked when the converter
   * returns the {@code BROKEN_LINK} sentinel — see {@link
   * AbstractCollectionConverter#convertSingleValue}).
   */
  @Test
  public void testLinkBagWithBrokenRidDropsEntry() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    session.executeInTx(tx -> {
      var converter = new LinkBagConverter(new ConverterData(session, brokenRids));

      var input = new LinkBag(session);
      input.add(brokenRid);
      input.add(new RecordId(20, 1));

      var result = converter.convert(session, input);

      assertNotSame(input, result);
      assertEquals(1, result.size());
      assertTrue(result.contains(new RecordId(20, 1)));
    });
  }

  /**
   * An empty link bag short-circuits the for-loop and returns by reference. Pins the
   * empty-collection fast-path on the bag shape.
   */
  @Test
  public void testEmptyLinkBagReturnedByReference() {
    session.executeInTx(tx -> {
      var converter = new LinkBagConverter(new ConverterData(session, new HashSet<>()));

      var input = new LinkBag(session);
      var result = converter.convert(session, input);

      assertSame(input, result);
      assertTrue(result.isEmpty());
    });
  }
}
