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
package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.HashSet;
import org.junit.Test;

/**
 * Commit-time index engine lifecycle coverage: an index created or dropped inside a transaction
 * becomes real storage structure at commit. A tx-created index builds its engine inside the commit's
 * own atomic operation, populated from the transaction's final record state, and is published into
 * the shared index manager only after the records are durable. A tx-dropped index has its engine
 * deleted and its registry entry removed at commit. Indexing a class created in the same transaction
 * resolves the class's provisional collection id (<= -2) at commit instead of throwing. A build whose
 * source collection already holds committed rows is loudly rejected (the v1 empty-class bound). A
 * failed engine-creating commit leaves no phantom engine registration and frees the engine id for
 * reuse.
 */
public class CommitTimeIndexBuildTest extends DbTestBase {

  private AbstractStorage storage() {
    return (AbstractStorage) session.getStorage();
  }

  /**
   * The engine registry probe: {@code loadIndexEngine} returns the engine's external id when the
   * engine is registered in both {@code indexEngineNameMap} and {@code indexEngines}, and -1 when it
   * is absent. A negative result is the clean "no phantom engine" assertion.
   */
  private boolean engineIsRegistered(String indexName) {
    return storage().loadIndexEngine(indexName) >= 0;
  }

  /**
   * An index created inside a transaction that also inserts rows into the indexed class has its
   * engine built at commit and populated from the transaction's final record state (guarding the
   * silent-untracking regression where a same-transaction insert into a new index is dropped). The
   * class pre-exists (committed) so its collection is a normal real collection; the index is created
   * and the rows inserted in the same later transaction. After commit the built engine contains
   * exactly those rows and accelerates the lookup.
   */
  @Test
  public void indexCreatedAndPopulatedInSameTransactionContainsRowsAfterCommit() {
    // A committed, empty class: its collection exists and is empty, so the v1 build bound is met.
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("BuildTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "BuildTarget.name";

    session.begin();
    session.getMetadata().getSchema().getClass("BuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    for (var value : new String[] {"alpha", "beta", "alpha"}) {
      var e = (EntityImpl) session.newEntity("BuildTarget");
      e.setProperty("name", value);
    }
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the tx-created index must be published to the shared manager after commit",
        indexManager.existsIndex(indexName));
    assertTrue("the tx-created index's engine must be built and registered after commit",
        engineIsRegistered(indexName));

    var index = indexManager.getIndex(indexName);
    assertTrue("the built engine must have a real (>= 0) engine id after commit",
        index.getIndexId() >= 0);
    var alphaRids = session.computeInTx(tx -> index.getRids(session, "alpha").toList());
    assertEquals("both same-tx-inserted 'alpha' rows must be in the committed index", 2,
        alphaRids.size());
    var betaRids = session.computeInTx(tx -> index.getRids(session, "beta").toList());
    assertEquals("the same-tx-inserted 'beta' row must be in the committed index", 1,
        betaRids.size());
  }

  /**
   * A build reflects exactly the transaction's final state: a row created then updated in the same
   * transaction is indexed under its final value, and a row created then deleted contributes no
   * entry. This exercises the final-state re-derivation's skip-deleted / final-value behaviour.
   */
  @Test
  public void indexBuildReflectsFinalTransactionState() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("FinalStateTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "FinalStateTarget.name";

    session.begin();
    session.getMetadata().getSchema().getClass("FinalStateTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // Created then updated: must index under the final value only.
    var updated = (EntityImpl) session.newEntity("FinalStateTarget");
    updated.setProperty("name", "original");
    updated.setProperty("name", "final");
    // Created then deleted in the same transaction: must contribute no entry.
    var deleted = (EntityImpl) session.newEntity("FinalStateTarget");
    deleted.setProperty("name", "doomed");
    session.delete(deleted);
    session.commit();

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var finalRids = session.computeInTx(tx -> index.getRids(session, "final").toList());
    assertEquals("the updated row must be indexed under its final value", 1, finalRids.size());
    var originalRids = session.computeInTx(tx -> index.getRids(session, "original").toList());
    assertTrue("the pre-update value must not survive in the built index", originalRids.isEmpty());
    var doomedRids = session.computeInTx(tx -> index.getRids(session, "doomed").toList());
    assertTrue("a created-then-deleted row must not be indexed", doomedRids.isEmpty());
    var size = session.computeInTx(tx -> index.size(session));
    assertEquals("the built index must hold exactly the one surviving row", 1L, (long) size);
  }

  /**
   * A class created earlier in the same transaction, then indexed later in that transaction, resolves
   * its provisional collection id (<= -2) at commit through the deferred handle's collection-name
   * carrier rather than throwing {@code IndexException("Collection with id -2 does not exist")}. The
   * class needs a property to index; in-transaction property creation is still throw-guarded, so the
   * class and its property are created and committed in one transaction, and the index-on-that-class
   * is created in a second transaction whose deferred handle names the (now real) collection. This
   * asserts the deferred create resolves the collection without throwing and the engine builds
   * against a real collection at commit.
   *
   * <p>The pure same-transaction "create class + property + index together" path is not reachable
   * until in-transaction property creation is de-guarded (a later track); the provisional-name
   * resolver on the deferred create is the forward-looking half that path will rely on.
   */
  @Test
  public void deferredIndexCreateResolvesCollectionWithoutThrowing() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DeferredResolve");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DeferredResolve.name";

    session.begin();
    // Must not throw a "Collection ... does not exist" while resolving the deferred handle's
    // collections: the resolver reads the collection name for the indexed class.
    session.getMetadata().getSchema().getClass("DeferredResolve")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the deferred index must be published after commit",
        indexManager.existsIndex(indexName));
    assertTrue("the index's engine must be built after commit", engineIsRegistered(indexName));
    assertTrue("the indexed class must own a real collection after commit",
        session.getMetadata().getSchema().getClass("DeferredResolve").getCollectionIds()[0] >= 0);
  }

  /**
   * A tx-created index and its rows survive a durable reload: after the on-disk index-manager and
   * index records are re-parsed, the index is present, its engine loads, and it still contains the
   * committed rows. This proves the commit-time build reached durable bytes on every storage profile.
   */
  @Test
  public void builtIndexSurvivesReload() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DurableIndex");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DurableIndex.name";

    session.begin();
    session.getMetadata().getSchema().getClass("DurableIndex")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var e = (EntityImpl) session.newEntity("DurableIndex");
    e.setProperty("name", "persisted");
    session.commit();

    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the built index must survive a reload", indexManager.existsIndex(indexName));
    assertTrue("the reloaded index's engine must be registered", engineIsRegistered(indexName));
    var index = indexManager.getIndex(indexName);
    var rids = session.computeInTx(tx -> index.getRids(session, "persisted").toList());
    assertEquals("the built index's row must survive the reload", 1, rids.size());
  }

  /**
   * An index dropped inside a transaction has its engine deleted and its registry entry removed at
   * commit: after commit the index is gone from the shared manager, its engine is unregistered, and
   * the drop survives a reload. Before this track the tx-local drop only marked the class changed, so
   * the index survived the commit and kept indexing; this is the drop-side commit half.
   */
  @Test
  public void indexDroppedInTransactionIsRemovedAndEngineDeletedAtCommit() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DropIndexTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DropIndexTarget.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the index must exist before the drop", indexManager.existsIndex(indexName));
    assertTrue("the engine must be registered before the drop", engineIsRegistered(indexName));

    session.executeInTx(tx -> indexManager.dropIndex(session, indexName));

    assertFalse("the dropped index must be gone from the shared manager after commit",
        indexManager.existsIndex(indexName));
    assertFalse("the dropped index's engine must be unregistered after commit",
        engineIsRegistered(indexName));

    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    assertFalse("the dropped index must not reappear after a reload",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
  }

  /**
   * The v1 empty-source-collection build bound: building an index inside a transaction on a
   * class whose collection already holds committed rows is loudly rejected with an error pointing at
   * the follow-up. The class is created and populated in a first committed transaction, then an index
   * on it is created in a second transaction whose commit must fail with the bound message. The
   * unbounded populated-class build is deferred; this asserts the rejection, not an accept.
   */
  @Test
  public void buildOnNonEmptySourceCollectionIsLoudlyRejected() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PopulatedTarget");
    cls.createProperty("name", PropertyType.STRING);
    // Populate the class's collection with a committed row before the in-tx index build.
    session.begin();
    var e = (EntityImpl) session.newEntity("PopulatedTarget");
    e.setProperty("name", "existing");
    session.commit();

    var indexName = "PopulatedTarget.name";
    session.begin();
    session.getMetadata().getSchema().getClass("PopulatedTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    try {
      session.commit();
      fail("building an index on a non-empty source collection inside a transaction must be "
          + "rejected in v1");
    } catch (final RuntimeException expected) {
      assertTrue(
          "the rejection must point at the follow-up for the unbounded populated-class build",
          expected.getMessage() != null && expected.getMessage().contains("YTDB-1064"));
    }

    // The rejected build left no phantom: the index is not published and its engine is not
    // registered, so a later empty-class build can still succeed.
    assertFalse("the rejected index must not be published to the shared manager",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertFalse("the rejected index's engine must not be registered",
        engineIsRegistered(indexName));
  }

  /**
   * Failed-commit engine cleanliness (the engine arm of the failed-commit registry-cleanliness
   * guarantee): a schema-carrying commit that creates an index engine and then fails after publishing
   * it (but before the record apply makes the commit durable) leaves no phantom engine registration,
   * and the freed engine id is reused by the next successful build. The fault is injected through the
   * in-window test hook, which fires on the failure-path side. The collection arm of this criterion is
   * already covered elsewhere; the engine arm is exercised here. The default in-memory profile is the
   * one that caught the equivalent collection-arm leak (the in-memory cache does not revert an eager
   * file addFile on rollback), so it exercises the create-side engine-file revert arm.
   */
  @Test
  public void failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId() {
    var storage = storage();
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("FailBuildTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "FailBuildTarget.name";

    // A retry-family fault (CommandInterruptedException) keeps the storage OPEN after the failure,
    // as the collection-arm failed-commit test relies on, so the phantom-registration check is
    // observable against a live storage rather than a poisoned one.
    storage.setCommitWindowTestHook(
        () -> {
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected in-window index-commit fault");
        });
    try {
      session.begin();
      session.getMetadata().getSchema().getClass("FailBuildTarget")
          .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
      try {
        session.commit();
        fail("the index-building commit must fail when the in-window fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + the engine-registry undo, as intended.
      }
    } finally {
      storage.setCommitWindowTestHook(null);
    }

    assertFalse("a failed index-building commit must leave no phantom index in the shared manager",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertFalse("a failed index-building commit must leave no phantom engine registration",
        engineIsRegistered(indexName));

    // The next successful build reuses the freed engine id (the allocator finds the slot free again)
    // and publishes cleanly, proving the failed commit leaked no engine slot.
    var freedEngineId = storage.loadIndexEngine(indexName);
    assertEquals("the failed build's engine must be fully unregistered", -1, freedEngineId);
    session.executeInTx(tx -> session.getMetadata().getSchema().getClass("FailBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
    assertTrue("the post-failure build must succeed and publish the index",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertTrue("the post-failure build's engine must be registered", engineIsRegistered(indexName));
  }

  /**
   * A polymorphic query through a superclass index sees a subclass's rows after a committed
   * collection-membership change (the {@code addSuperClass} ripple; positive membership coverage). A
   * superclass carrying an index and an independent child class are created and committed first (so
   * the child owns a real committed collection). Then in a transaction the child is made a subclass of
   * the indexed parent; that ripples the child's real collection into the parent index's membership.
   * After commit the parent index covers the child collection, so inserting a child row and looking it
   * up through the parent index returns it. An implementation that omitted the membership category
   * would fail this while passing isolation and rollback.
   *
   * <p>The child is committed independently first (rather than created under the parent in one step)
   * so its collection is a real id when the ripple runs; a same-transaction subclass would carry a
   * provisional collection id, which the membership persistence does not yet resolve.
   */
  @Test
  public void committedMembershipChangeMakesParentIndexCoverSubclassRows() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("PolyParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "PolyParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // An independent, committed child with its own real collection and the indexed property.
    var child = schema.createClass("PolyChild");
    child.createProperty("name", PropertyType.STRING);

    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("PolyChild").getCollectionIds()[0]);
    assertNotNull("the committed child must own a real collection before the membership change",
        childCollectionName);

    // Make the child a subclass of the indexed parent inside a transaction; the ripple records the
    // child's real collection into the parent index's membership, persisted at commit.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().getClass("PolyChild")
            .addSuperClass(session.getMetadata().getSchema().getClass("PolyParent")));

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var membership = new HashSet<>(index.getCollections());
    assertTrue(
        "the committed membership change must add the subclass collection to the parent index",
        membership.contains(childCollectionName));

    // A child row inserted after the membership change is indexed under the parent index, so a
    // polymorphic lookup through it returns the subclass row.
    session.begin();
    var childRow = (EntityImpl) session.newEntity("PolyChild");
    childRow.setProperty("name", "childValue");
    session.commit();

    var rids = session.computeInTx(tx -> index.getRids(session, "childValue").toList());
    assertEquals(
        "a polymorphic lookup through the parent index must return the subclass row after the "
            + "committed membership change",
        1, rids.size());
  }
}
