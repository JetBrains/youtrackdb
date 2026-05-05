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

import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_CLASS_NAME;
import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_INDEX_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinkListConverter}. The converter walks an
 * {@link com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl EntityLinkListImpl}
 * (a tracked list of {@link Identifiable}) and either passes the list through unchanged (no
 * rewrite needed) or returns a fresh {@code LinkList} with mapped/dropped rids. The dispatch
 * for each element delegates to {@link LinkConverter} via {@link
 * AbstractCollectionConverter#convertSingleValue}.
 */
public class LinkListConverterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (Track 5+ idiom).
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
   * Sets up the export-import rid mapping schema with one mapping {@code from -> to}.
   */
  private void setupRidMapping(RID from, RID to) {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass(EXPORT_IMPORT_CLASS_NAME);
    cls.createProperty("key", PropertyType.STRING);
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex(EXPORT_IMPORT_INDEX_NAME, INDEX_TYPE.UNIQUE, "key");

    session.executeInTx(tx -> {
      var mapping = (EntityImpl) tx.newEntity(EXPORT_IMPORT_CLASS_NAME);
      mapping.setProperty("key", from.toString());
      mapping.setProperty("value", to.toString());
    });
  }

  /**
   * A link list whose every rid is unmapped and not broken returns by reference. The lookup
   * SELECT runs for each rid but finds no mapping, so no element changes.
   */
  @Test
  public void testLinkListWithNoMappingsReturnedByReference() {
    setupRidMapping(new RecordId(99, 0), new RecordId(99, 1));

    var converter = new LinkListConverter(new ConverterData(session, new HashSet<>()));

    List<Identifiable> input = session.newLinkList();
    input.add(new RecordId(20, 1));
    input.add(new RecordId(20, 2));

    var result = converter.convert(session, input);

    assertSame("no-change path must return input by reference", input, result);
  }

  /**
   * A list with at least one mapped rid produces a fresh {@code LinkList} with the rewrite in
   * place. Order is preserved (the for-loop iterates input order).
   */
  @Test
  public void testLinkListWithMappedRidRewritesToFreshList() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    setupRidMapping(fromRid, toRid);

    var converter = new LinkListConverter(new ConverterData(session, new HashSet<>()));

    List<Identifiable> input = session.newLinkList();
    input.add(fromRid);
    input.add(new RecordId(20, 1));

    var result = converter.convert(session, input);

    assertNotSame("rewrite path must return a fresh list", input, result);
    assertEquals(2, result.size());
    assertEquals("rewritten rid must be the mapped target", toRid, result.get(0).getIdentity());
    assertEquals("second rid passes through unchanged",
        new RecordId(20, 1), result.get(1).getIdentity());
  }

  /**
   * Broken rids are dropped from the result list. Mirrors the embedded-list test but on the
   * link-list shape.
   */
  @Test
  public void testLinkListWithBrokenRidDropsEntry() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    setupRidMapping(new RecordId(99, 0), new RecordId(99, 1));

    var converter = new LinkListConverter(new ConverterData(session, brokenRids));

    List<Identifiable> input = session.newLinkList();
    input.add(brokenRid);
    input.add(new RecordId(20, 1));

    var result = converter.convert(session, input);

    assertNotSame(input, result);
    assertEquals(1, result.size());
    assertEquals(new RecordId(20, 1), result.get(0).getIdentity());
  }

  /**
   * An empty link list short-circuits the for-loop and returns by reference. Pins the
   * empty-collection fast-path.
   */
  @Test
  public void testEmptyLinkListReturnedByReference() {
    var converter = new LinkListConverter(new ConverterData(session, new HashSet<>()));

    List<Identifiable> input = session.newLinkList();
    var result = converter.convert(session, input);

    assertSame(input, result);
    assertTrue(result.isEmpty());
  }
}
