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
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinkSetConverter}. Mirrors {@link LinkListConverterTest}'s
 * arms but operates on the link-set shape. {@code session.newLinkSet()} constructs an
 * {@link com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl} backed by an
 * {@code EmbeddedLinkBag}; that delegate's {@code add} path requires an active transaction, so
 * each test wraps the converter call in {@code session.executeInTx}.
 */
public class LinkSetConverterTest extends DbTestBase {

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
   * A link set with no mappings returns by reference.
   */
  @Test
  public void testLinkSetWithNoMappingsReturnedByReference() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    session.executeInTx(tx -> {
      var converter = new LinkSetConverter(new ConverterData(session, new HashSet<>()));

      var input = session.newLinkSet();
      input.add(new RecordId(20, 1));

      var result = converter.convert(session, input);

      assertSame(input, result);
    });
  }

  /**
   * A link set with at least one mapped rid produces a fresh {@code LinkSet} with the
   * rewrite in place. Membership-based assertions accommodate the unordered shape.
   */
  @Test
  public void testLinkSetWithMappedRidRewritesToFreshSet() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    session.executeInTx(tx -> {
      var converter = new LinkSetConverter(new ConverterData(session, new HashSet<>()));

      var input = session.newLinkSet();
      input.add(fromRid);
      input.add(new RecordId(20, 1));

      var result = converter.convert(session, input);

      assertNotSame(input, result);
      assertEquals(2, result.size());
      assertTrue("mapped target rid must be present",
          result.stream().anyMatch(id -> toRid.equals(id.getIdentity())));
      assertTrue("untouched rid must be present",
          result.stream().anyMatch(id -> new RecordId(20, 1).equals(id.getIdentity())));
    });
  }

  /**
   * Broken rids are dropped from the result set.
   */
  @Test
  public void testLinkSetWithBrokenRidDropsEntry() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    session.executeInTx(tx -> {
      var converter = new LinkSetConverter(new ConverterData(session, brokenRids));

      var input = session.newLinkSet();
      input.add(brokenRid);
      input.add(new RecordId(20, 1));

      var result = converter.convert(session, input);

      assertNotSame(input, result);
      assertEquals(1, result.size());
      assertTrue(result.stream().anyMatch(id -> new RecordId(20, 1).equals(id.getIdentity())));
    });
  }

  /**
   * Empty link set short-circuits and returns by reference.
   */
  @Test
  public void testEmptyLinkSetReturnedByReference() {
    session.executeInTx(tx -> {
      var converter = new LinkSetConverter(new ConverterData(session, new HashSet<>()));

      var input = session.newLinkSet();
      var result = converter.convert(session, input);

      assertSame(input, result);
      assertTrue(result.isEmpty());
    });
  }
}
