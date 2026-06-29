/*
 *
 *
 *  *
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
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Commit-time reconciliation coverage: a schema-changing transaction now becomes real storage
 * structure at commit. A class created inside a transaction carries a provisional collection id
 * during the transaction; at commit the storage creates the real collection inside the commit's own
 * atomic operation, resolves the provisional id to its real id before any record serializes, and
 * promotes the tx-local schema into the committed shared instances. A rolled-back schema transaction
 * leaves no real collection. These tests assert the create/resolve/promote round trip, the rollback
 * leaves-clean property, and the positive drop.
 *
 * <p>A class created inside a transaction is not yet resolvable through the immutable schema
 * snapshot {@code session.newEntity} consults, so the record-insert tests create-and-commit the
 * class in one transaction and insert records in a later one, matching how production code reaches a
 * newly created class.
 */
public class SchemaCommitReconciliationTest extends DbTestBase {

  private SchemaShared schemaShared() {
    return session.getSharedContext().getSchema();
  }

  /**
   * Reads the RID set held by the root record's {@code "classes"} link set, the persisted membership
   * the per-class-record format relies on. Loaded inside a transaction as in production.
   */
  private Set<RID> rootClassLinks() {
    return session.computeInTx(tx -> {
      var root = session.<EntityImpl>load(schemaShared().getIdentity());
      var links = root.getLinkSet("classes");
      var rids = new HashSet<RID>();
      if (links != null) {
        for (var link : links) {
          rids.add(link.getIdentity());
        }
      }
      return rids;
    });
  }

  /**
   * A class created inside a transaction is promoted to the committed schema at commit, carrying a
   * real (non-negative) collection id: the provisional id it held during the transaction is resolved
   * to a real collection created inside the commit, and that collection exists on disk. This is the
   * create half of the metadata-first inversion.
   */
  @Test
  public void inTransactionCreateResolvesToRealCollectionAtCommit() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("CommitCreated"));

    var cls = schemaShared().getClass("CommitCreated");
    assertNotNull("the created class must be promoted to the committed schema after commit", cls);
    var collectionIds = cls.getCollectionIds();
    assertTrue("a committed class must own at least one collection", collectionIds.length > 0);
    for (var collectionId : collectionIds) {
      assertTrue(
          "no provisional id may survive commit; every collection id must be a real (>= 0) id, was "
              + collectionId,
          collectionId >= 0);
      var collectionName = session.getCollectionNameById(collectionId);
      assertNotNull(
          "the resolved real collection must exist in storage, id " + collectionId, collectionName);
    }
    // The default collection id is the class's primary real collection and must also be resolved.
    assertTrue("the default collection id must be a resolved real id",
        cls.getCollectionIds()[0] >= 0);
  }

  /**
   * The committed class and its real collection survive a durable round trip: after a reload re-reads
   * the on-disk per-class records, the class resolves to the same real collection ids, and no
   * provisional id reached durable bytes. The reload-then-reopen forces a fromStream re-parse
   * on every storage profile.
   */
  @Test
  public void committedClassAndCollectionSurviveReload() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("DurableCreated"));

    var idsBefore = schemaShared().getClass("DurableCreated").getCollectionIds();
    assertTrue("the class must own a collection before the round trip", idsBefore.length > 0);
    assertTrue("the bound link set must contain the created class record before reload",
        !rootClassLinks().isEmpty());

    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    var clsAfter = schemaShared().getClass("DurableCreated");
    assertNotNull("the created class must survive a reload", clsAfter);
    assertEquals("the class's real collection ids must survive the round trip unchanged",
        java.util.Arrays.toString(idsBefore),
        java.util.Arrays.toString(clsAfter.getCollectionIds()));
    for (var collectionId : clsAfter.getCollectionIds()) {
      assertTrue("no provisional id may survive a reload, was " + collectionId, collectionId >= 0);
      assertNotNull("the real collection must still exist after the reload",
          session.getCollectionNameById(collectionId));
    }
  }

  /**
   * Records inserted into a committed-then-reconciled class resolve to the class's real collection:
   * the class is created and committed in one transaction, then a record is inserted in a later one,
   * lands in a persistent RID inside one of the class's resolved real collections, and reads back
   * with its value. This proves the promoted class is usable for record writes and the snapshot was
   * invalidated so the new class is visible.
   */
  @Test
  public void recordInsertedIntoReconciledClassResolvesToRealCollection() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("InsertTarget"));

    var classCollectionIds = schemaShared().getClass("InsertTarget").getCollectionIds();
    var validCollectionIds = new HashSet<Integer>();
    for (var id : classCollectionIds) {
      validCollectionIds.add(id);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity("InsertTarget");
    entity.setProperty("value", 7);
    session.commit();

    var recordId = entity.getIdentity();
    assertTrue("the inserted record must hold a persistent RID after commit",
        recordId.isPersistent());
    assertTrue(
        "the inserted record must land in one of the class's resolved real collections, was "
            + recordId.getCollectionId(),
        validCollectionIds.contains(recordId.getCollectionId()));

    // Read the property inside the transaction: a record loaded through the active transaction is
    // bound only for that transaction's lifetime, so the value must be read before the tx closes.
    var reloadedValue =
        session.computeInTx(
            tx -> {
              var reloaded = session.getActiveTransaction().<EntityImpl>load(recordId);
              assertNotNull("the inserted record must be readable from its real collection",
                  reloaded);
              return reloaded.<Integer>getProperty("value");
            });
    assertEquals("the inserted record's value must round-trip", Integer.valueOf(7), reloadedValue);
  }

  /**
   * A class created inside a transaction that rolls back leaves no real collection: the metadata
   * change is discarded and storage is byte-for-byte unchanged. The provisional id never resolves to
   * a real collection, so no collection file or registry entry is created. The committed
   * schema also stays without the class.
   */
  @Test
  public void rolledBackInTransactionCreateLeavesNoCollection() {
    var collectionsBefore = new HashSet<>(session.getCollectionNames());

    session.begin();
    session.getMetadata().getSchema().createClass("RolledBackCommit");
    session.rollback();

    assertFalse("the rolled-back class must be absent from the committed schema",
        schemaShared().existsClass("RolledBackCommit"));
    assertEquals(
        "a rolled-back schema transaction must create no real collection (storage unchanged)",
        collectionsBefore, new HashSet<>(session.getCollectionNames()));
  }

  /**
   * The positive drop across a commit: a class created and committed in one transaction, then
   * dropped and committed in a second, has its real collection removed from storage and its class
   * record unlinked from the root, confirmed after a durable reload. A drop must be detected from the
   * collection-id set difference, not the changed-record set (a dropped class's record is deleted,
   * so it carries no per-property change signal).
   */
  @Test
  public void droppedClassRemovesItsCollectionAcrossCommit() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("ToReconcileDrop"));

    var droppedClass = schemaShared().getClass("ToReconcileDrop");
    var droppedRid = droppedClass.getRecordId();
    var droppedCollectionIds = droppedClass.getCollectionIds();
    assertTrue("the class must own a real collection before the drop",
        droppedCollectionIds.length > 0 && droppedCollectionIds[0] >= 0);
    var droppedName = session.getCollectionNameById(droppedCollectionIds[0]);
    assertNotNull("the class's real collection must exist before the drop", droppedName);
    assertTrue("the class record must be linked from the root before the drop",
        rootClassLinks().contains(droppedRid));

    session.executeInTx(tx -> session.getMetadata().getSchema().dropClass("ToReconcileDrop"));

    assertFalse("the dropped class must be gone from the committed schema after commit",
        schemaShared().existsClass("ToReconcileDrop"));
    assertFalse("the dropped class record must be unlinked from the root after commit",
        rootClassLinks().contains(droppedRid));

    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    assertFalse("the dropped class must not reappear after a reload",
        schemaShared().existsClass("ToReconcileDrop"));
    assertFalse("the dropped class's collection must not exist after a reload",
        session.getCollectionNames().contains(droppedName));
  }
}
