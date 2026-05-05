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

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinkConverter}. The converter rewrites individual {@link RID}
 * references during import: a non-persistent (transient/embedded) rid is passed through; a rid
 * present in the broken-rids set is replaced with the {@code BROKEN_LINK} sentinel; otherwise the
 * converter queries the import-side rid mapping table and returns either the mapped rid or the
 * original value if the mapping is absent.
 *
 * <p>Each branch is exercised against a real {@link com.jetbrains.youtrackdb.internal.core.db
 * .DatabaseSessionEmbedded DatabaseSessionEmbedded} because the converter calls {@code
 * computeInTx} and runs an SQL select against the rid-mapping schema; mocking would entrench the
 * implementation details rather than the contract.
 */
public class LinkConverterTest extends DbTestBase {

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
   * A non-persistent (transient) rid bypasses both the broken-rids check and the SQL lookup —
   * the converter returns the input by reference. This is the fast-path that protects embedded
   * RID references (e.g., a {@link com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId})
   * from being misclassified as broken or queried against the import map.
   */
  @Test
  public void testNonPersistentRidPassesThroughByReference() {
    var converter = new LinkConverter(new ConverterData(session, new HashSet<>()));

    // Cluster id < 0 marks the rid as transient/non-persistent.
    var transientRid = new RecordId(-1, -1);
    var result = converter.convert(session, transientRid);

    assertSame("non-persistent rid must pass through unchanged", transientRid, result);
  }

  /**
   * A rid present in the broken-rids set is replaced with the {@code BROKEN_LINK} sentinel. The
   * caller (typically {@link AbstractCollectionConverter#convertSingleValue}) uses {@code ==} to
   * detect this sentinel and skip the entry; equality semantics matter here, so the assertion
   * uses {@code assertSame}.
   */
  @Test
  public void testBrokenRidIsReplacedWithSentinel() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);

    var converter = new LinkConverter(new ConverterData(session, brokenRids));
    var result = converter.convert(session, brokenRid);

    assertSame("broken rid must resolve to the BROKEN_LINK sentinel by reference",
        ImportConvertersFactory.BROKEN_LINK, result);
  }

  /**
   * A rid that is persistent, not broken, and present in the mapping table is rewritten to the
   * mapped target. This is the main rewrite arm exercised during import when the source database
   * was exported under different cluster ids.
   */
  @Test
  public void testMappedRidIsRewrittenToTarget() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    var converter = new LinkConverter(new ConverterData(session, new HashSet<>()));
    var result = converter.convert(session, fromRid);

    assertEquals("mapped rid must be rewritten to the target rid", toRid, result);
  }

  /**
   * A rid that is persistent and not broken but absent from the mapping table is returned
   * unchanged. This pass-through behaviour is essential to migrations where most rids do not
   * change cluster ids and only the broken/remapped subset needs rewriting.
   */
  @Test
  public void testUnmappedRidPassesThroughUnchanged() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(10, 4), new RecordId(10, 3));

    var unmappedRid = new RecordId(20, 5);
    var converter = new LinkConverter(new ConverterData(session, new HashSet<>()));
    var result = converter.convert(session, unmappedRid);

    assertSame("unmapped rid must pass through by reference (no mapping found)",
        unmappedRid, result);
  }
}
