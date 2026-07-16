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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Commit-time index engine lifecycle coverage: an index created or dropped inside a transaction
 * becomes real storage structure at commit. A tx-created index builds its engine inside the commit's
 * own atomic operation, populated from the transaction's final record state, and is published into
 * the shared index manager only after the records are durable. A tx-dropped index has its engine
 * deleted and its registry entry removed at commit. A drop-then-recreate of the same name in one
 * transaction is a replace. A deferred index create resolves its class's collection at commit
 * instead of throwing (the pure same-transaction provisional-id path stays forward-looking until
 * in-transaction property creation is de-guarded). A build whose source collection
 * already holds committed rows is loudly rejected (the v1 empty-class bound). A failed engine-creating
 * commit leaves no phantom engine registration and frees the engine id for reuse; a failed
 * engine-dropping commit leaves the surviving committed index fully usable.
 *
 * <p>Concurrency boundary (accepted v1 semantic, tracked by YTDB-1101): a schema-carrying commit
 * excludes concurrent data <em>commits</em> via the index-manager write lock, but the shared
 * lookup-map publish is NOT atomic against lock-free <em>readers</em>. A concurrent reader can
 * observe a torn two-map view mid-publish; the reader-atomic snapshot flip is deferred to the
 * read-chain snapshot-versioning work (YTDB-1101). The concurrency test below asserts only the
 * accepted best-effort semantic (eventual consistency after the commit completes), not reader
 * atomicity.
 */
public class CommitTimeIndexBuildTest extends DbTestBase {

  /**
   * A commit-window primitive that regressed to re-taking the non-reentrant {@code stateLock} would
   * busy-spin forever rather than throw, hanging the whole surefire fork with no signal. A per-method
   * timeout converts that hang into a fast, diagnosable failure that names the stuck commit thread.
   */
  @Rule
  public Timeout globalTimeout =
      Timeout.builder()
          .withTimeout(120, TimeUnit.SECONDS)
          .withLookingForStuckThread(true)
          .build();

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
   * A deferred index create on a committed class resolves the class's real collection at commit
   * without throwing {@code IndexException("Collection with id -2 does not exist")}: the class and
   * its property are created and committed first (in-transaction property creation is still
   * throw-guarded), then the index is created in a second transaction whose deferred handle names
   * the committed class's real collection, and the engine builds against that real collection at
   * commit.
   *
   * <p>Not covered here (forward-looking): the pure same-transaction "create class + property +
   * index together" path, in which the deferred handle would resolve a provisional collection id
   * ({@code <= -2}) through the tx-local name carrier. That path is unreachable until
   * in-transaction property creation is de-guarded (a later change); the provisional-name resolver
   * on the deferred create is the half that path will rely on, and it stays without end-to-end
   * coverage until then.
   */
  @Test
  public void deferredIndexCreateOnACommittedClassResolvesItsRealCollectionWithoutThrowing() {
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
   * Failed-commit engine cleanliness, create side (the engine arm of the failed-commit
   * registry-cleanliness guarantee): a schema-carrying commit that builds and publishes an index
   * engine and then fails, after the engine exists but before the record apply makes the commit
   * durable, leaves no phantom engine registration, and the freed engine id is reused by the next
   * successful build. The fault fires through the post-engine-build hook, which runs after the engine
   * is published — the pre-record-apply commit-window hook fires before any engine exists, so it
   * cannot exercise the create-side revert arm at all. The hook first asserts the engine IS registered
   * at the fault point, so the post-failure assertions are load-bearing (a broken revert arm would
   * leave a real registration behind rather than pass vacuously). The default in-memory profile is the
   * one that caught the equivalent collection-arm leak (the in-memory cache does not revert an eager
   * file addFile on rollback), so the surviving engine files this arm drops would otherwise block the
   * id-reusing rebuild with a "file already exists" error.
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
    // observable against a live storage rather than a poisoned one. The post-build hook fires after
    // the engine is built and published, so the create-side revert arm genuinely has an engine to
    // revert; the precondition assert proves the arm is not exercised over an empty set.
    storage.setPostEngineBuildTestHook(
        () -> {
          assertTrue(
              "the tx-created engine must be published before the fault so the create-side revert"
                  + " arm has real work",
              storage.isIndexEngineRegisteredInCommitWindow(indexName));
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected post-engine-build index-commit fault");
        });
    try {
      session.begin();
      session.getMetadata().getSchema().getClass("FailBuildTarget")
          .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
      try {
        session.commit();
        fail("the index-building commit must fail when the post-build fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + the engine-registry undo, as intended.
      }
    } finally {
      storage.setPostEngineBuildTestHook(null);
    }

    assertFalse("a failed index-building commit must leave no phantom index in the shared manager",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertFalse("a failed index-building commit must leave no phantom engine registration",
        engineIsRegistered(indexName));

    // The durable arm: re-parse the index manager's persisted records and reopen the session, so
    // the no-phantom assertions are re-derived from durable state instead of the in-memory undo's
    // leftovers. On the disk profile this catches a regression where the engine's configuration
    // write did not revert with the rolled-back atomic operation. The full storage-restart
    // re-derivation (hard crash plus WAL replay) stays with the ignored crash-recovery breadcrumb.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    assertFalse("a failed engine-creating commit must leave no phantom index in durable state",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertFalse("a failed engine-creating commit must leave no phantom engine after a reopen",
        engineIsRegistered(indexName));

    // The next successful build reuses the freed engine id (the allocator finds the slot free again)
    // and publishes cleanly, proving the failed commit leaked no engine slot AND that the create-side
    // engine-file revert arm dropped the surviving in-memory-profile engine files (otherwise this
    // id-reusing rebuild would fail with a "file already exists" error).
    var freedEngineId = storage().loadIndexEngine(indexName);
    assertEquals("the failed build's engine must be fully unregistered", -1, freedEngineId);
    session.executeInTx(tx -> session.getMetadata().getSchema().getClass("FailBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
    assertTrue("the post-failure build must succeed and publish the index",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertTrue("the post-failure build's engine must be registered", engineIsRegistered(indexName));
  }

  /**
   * Failed-commit engine cleanliness, drop side (the symmetric engine arm): a schema-carrying commit
   * that drops a committed index's engine and then fails, after the drop tore the engine out of the
   * in-memory registry but before the commit is durable, MUST leave the surviving committed index
   * fully usable — queryable, with a registered engine, no unregistered-engine throw. The drop's
   * synchronous registry removal is reconstructed on the failure path from the engine's captured
   * durable data. Without the drop-restore arm the committed index would point at a nulled engine slot
   * and throw {@code InvalidIndexEngineIdException} on the next read.
   *
   * <p>Two committed indexes are dropped in one transaction; the fault fires after both drops so the
   * restore arm reconstructs both. The assertion that the surviving index still returns its rows is
   * the deterministic invariant bar. Runs on the active profile (in-memory by default, disk under
   * {@code -Dyoutrackdb.test.env=ci}); on the disk profile the rolled-back atomic operation restored
   * the engine files, and on the in-memory profile the eager delete's files may differ, so the
   * reconstruction reads whatever durable state the profile left — the invariant (usable surviving
   * index) must hold on both.
   */
  @Test
  public void failedDropCommitLeavesTheSurvivingCommittedIndexUsable() {
    var storage = storage();
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("FailDropTarget");
    cls.createProperty("keep", PropertyType.STRING);
    cls.createProperty("gone", PropertyType.STRING);
    var keepIndexName = "FailDropTarget.keep";
    var goneIndexName = "FailDropTarget.gone";
    // Two committed indexes on the same class, and one committed row so the survivor has a real entry
    // to return after the failed drop commit.
    cls.createIndex(keepIndexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "keep");
    cls.createIndex(goneIndexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "gone");
    session.begin();
    var row = (EntityImpl) session.newEntity("FailDropTarget");
    row.setProperty("keep", "survivor");
    row.setProperty("gone", "doomed");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("both indexes must exist before the failed drop",
        indexManager.existsIndex(keepIndexName));
    assertTrue("both indexes must exist before the failed drop",
        indexManager.existsIndex(goneIndexName));

    // Fault fires after the drop phase tore both engines out of the registry, so the drop-restore arm
    // has both to reconstruct. The precondition assert proves the drops actually happened before the
    // fault (a vacuous test would find the engines still registered here).
    storage.setPostEngineBuildTestHook(
        () -> {
          assertFalse(
              "the dropped engine must be unregistered at the fault point so the drop-restore arm has"
                  + " real work",
              storage.isIndexEngineRegisteredInCommitWindow(keepIndexName));
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected post-drop index-commit fault");
        });
    try {
      session.begin();
      indexManager.dropIndex(session, keepIndexName);
      indexManager.dropIndex(session, goneIndexName);
      try {
        session.commit();
        fail("the index-dropping commit must fail when the post-drop fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + the drop-restore arm, as intended.
      }
    } finally {
      storage.setPostEngineBuildTestHook(null);
    }

    // Deterministic invariant: the failed drop commit rolled back, so BOTH committed indexes must be
    // present and their engines reconstructed — the surviving index must be fully usable, not just
    // present in the shared map with a nulled engine slot.
    assertTrue("a failed drop commit must leave the surviving committed index published",
        indexManager.existsIndex(keepIndexName));
    assertTrue("a failed drop commit must leave the surviving committed index published",
        indexManager.existsIndex(goneIndexName));
    assertTrue("the surviving committed index's engine must be reconstructed after the failed drop",
        engineIsRegistered(keepIndexName));
    assertTrue("the surviving committed index's engine must be reconstructed after the failed drop",
        engineIsRegistered(goneIndexName));

    // The invariant bar: a query through the surviving index must return its committed row without an
    // unregistered-engine / null-slot throw — the whole point of the drop-restore arm.
    var keepIndex = indexManager.getIndex(keepIndexName);
    var survivorRids = session.computeInTx(tx -> keepIndex.getRids(session, "survivor").toList());
    assertEquals(
        "the surviving committed index must still return its committed row after the failed"
            + " drop commit",
        1, survivorRids.size());

    // The durable arm: re-parse the index manager's persisted records and reopen the session;
    // both committed indexes must re-derive from durable state with loadable engines, and the
    // survivor must still return its committed row. On the disk profile this catches a rollback
    // that failed to restore the dropped engines' durable configuration entries.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedManager = session.getSharedContext().getIndexManager();
    assertTrue("the committed indexes must survive the failed drop in durable state",
        reloadedManager.existsIndex(keepIndexName));
    assertTrue("the committed indexes must survive the failed drop in durable state",
        reloadedManager.existsIndex(goneIndexName));
    assertTrue("the surviving indexes' engines must load after a reopen",
        engineIsRegistered(keepIndexName));
    assertTrue("the surviving indexes' engines must load after a reopen",
        engineIsRegistered(goneIndexName));
    var reloadedKeep = reloadedManager.getIndex(keepIndexName);
    var reloadedRids =
        session.computeInTx(tx -> reloadedKeep.getRids(session, "survivor").toList());
    assertEquals("the surviving committed index must return its row after a reopen", 1,
        reloadedRids.size());
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
   * so its collection is a real id when the ripple runs; the same-transaction subclass case (a
   * provisional collection id resolved through its carried name) is covered by
   * {@link #sameTxSubclassUnderIndexedParentResolvesRealCollectionNameInMembership}.
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

  /**
   * A committed membership removal (the removeSuperClass ripple) must make the parent index stop
   * covering the ex-subclass collection at commit: the remove side has its own mutator and
   * overlay category, so an error that left the collection in the parent index's membership
   * would keep indexing ex-subclass rows and keep returning them from polymorphic lookups. The
   * add side is covered by the sibling test above; this is the end-to-end remove half.
   */
  @Test
  public void committedMembershipRemovalMakesParentIndexStopCoveringExSubclassRows() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemRemParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "MemRemParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // A committed subclass: creating it under the parent ripples its collection into the parent
    // index's membership through the committed (non-transactional) path.
    schema.createClass("MemRemChild", schema.getClass("MemRemParent"));

    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("MemRemChild").getCollectionIds()[0]);
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertTrue("the subclass collection must be covered before the membership removal",
        new HashSet<>(index.getCollections()).contains(childCollectionName));

    // Remove the superclass inside a transaction: the ripple records a membership removal into
    // the overlay, persisted in the enroll phase and applied to the shared index at commit.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().getClass("MemRemChild")
            .removeSuperClass(session.getMetadata().getSchema().getClass("MemRemParent")));

    assertFalse(
        "after commit the parent index must no longer cover the ex-subclass collection",
        new HashSet<>(index.getCollections()).contains(childCollectionName));

    // A row inserted into the ex-subclass afterwards must not be indexed under the parent index:
    // the index no longer tracks that collection, so a lookup through it misses the row.
    session.begin();
    var exChildRow = (EntityImpl) session.newEntity("MemRemChild");
    exChildRow.setProperty("name", "afterRemoval");
    session.commit();
    var exChildRids =
        session.computeInTx(tx -> index.getRids(session, "afterRemoval").toList());
    assertTrue("a lookup through the parent index must not return ex-subclass rows",
        exChildRids.isEmpty());
  }

  /**
   * Failed-commit membership cleanliness (the third failure arm next to the create-side and
   * drop-side engine arms): a commit that ripples a membership add into a shared committed index
   * and then fails after the eager in-memory apply must revert the mutation. Otherwise the shared
   * committed index would keep covering a collection whose membership record write rolled back,
   * and later data commits would write durable index entries under that phantom membership. The
   * fault fires through the post-engine-build hook, which runs after the enroll phase applied the
   * eager membership mutation; the hook's precondition assert proves the mutation was applied at
   * the fault point, so the revert arm has real work and the test cannot pass vacuously.
   */
  @Test
  public void failedCommitRevertsEagerMembershipMutation() {
    var storage = storage();
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemFailParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "MemFailParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // An independent committed child with a real collection, so the addSuperClass ripple records
    // a plain committed-collection membership add.
    var child = schema.createClass("MemFailChild");
    child.createProperty("name", PropertyType.STRING);
    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("MemFailChild").getCollectionIds()[0]);

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var membershipBefore = new HashSet<>(index.getCollections());
    assertFalse("the child collection must not be covered before the failed commit",
        membershipBefore.contains(childCollectionName));

    storage.setPostEngineBuildTestHook(
        () -> {
          assertTrue(
              "the eager membership mutation must be applied before the fault so the revert arm"
                  + " has real work",
              index.getCollections().contains(childCollectionName));
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected post-membership index-commit fault");
        });
    try {
      session.begin();
      session.getMetadata().getSchema().getClass("MemFailChild")
          .addSuperClass(session.getMetadata().getSchema().getClass("MemFailParent"));
      try {
        session.commit();
        fail("the membership-changing commit must fail when the post-build fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + the membership revert arm, as intended.
      }
    } finally {
      storage.setPostEngineBuildTestHook(null);
    }

    assertEquals("a failed commit must revert the eager in-memory membership mutation",
        membershipBefore, new HashSet<>(index.getCollections()));
    // The revert agrees with durable state: the membership record write rolled back with the
    // atomic operation, so a reload must not resurrect the collection.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedIndex = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertFalse("the rolled-back membership must not reappear after a reload",
        new HashSet<>(reloadedIndex.getCollections()).contains(childCollectionName));
  }

  /**
   * A tx-created UNIQUE index whose engine is unbuilt during the transaction first meets
   * uniqueness at the commit-time build: the transaction's tracked entries are stripped before
   * the working set and re-derived into the fresh engine, so two same-tx rows carrying the same
   * key must fail the commit through the natural duplicated-key rejection (not an injected
   * fault), route through the failure-path undo, and leave no phantom index, no phantom engine,
   * and a rebuildable slot behind. A build that silently accepted the duplicate would corrupt
   * the unique invariant on durable bytes.
   */
  @Test
  public void uniqueIndexBuildRejectsSameTxDuplicateKeyAndLeavesNoPhantomEngine() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("UniqueBuildTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "UniqueBuildTarget.name";

    session.begin();
    session.getMetadata().getSchema().getClass("UniqueBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var firstDuplicate = (EntityImpl) session.newEntity("UniqueBuildTarget");
    firstDuplicate.setProperty("name", "dup");
    var secondDuplicate = (EntityImpl) session.newEntity("UniqueBuildTarget");
    secondDuplicate.setProperty("name", "dup");
    try {
      session.commit();
      fail("a UNIQUE build over same-tx duplicate keys must fail the commit");
    } catch (final RuntimeException expected) {
      assertTrue("the failure must be the natural duplicated-key rejection, got: " + expected,
          chainContainsDuplicatedKey(expected));
    }

    // The natural (non-injected) failure must route through the same undo arms the injected
    // fault tests exercise: no phantom index and no phantom engine registration survive, and the
    // transaction's rows rolled back with the commit.
    assertFalse("a rejected UNIQUE build must leave no phantom index in the shared manager",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertFalse("a rejected UNIQUE build must leave no phantom engine registration",
        engineIsRegistered(indexName));
    // Explicit begin/commit around the query: a bare query would leave its implicit read-only
    // transaction active, and the recovery-class DDL below would run inside it.
    session.begin();
    try (var rs = session.query("select from UniqueBuildTarget")) {
      assertEquals("the duplicate rows must roll back with the failed commit", 0L,
          rs.stream().count());
    }
    session.commit();

    // A later UNIQUE build over a distinct key succeeds, proving the failed build left the index
    // manager and the engine allocator usable. The build targets a fresh class: the failed
    // commit's rollback reverts the rows but not the source collection's in-heap approximate
    // record counter, and the v1 empty-source bound accepts that over-report as a false
    // rejection, so an immediate same-class rebuild would be bounced by the stale counter.
    var recoveryClass = schema.createClass("UniqueBuildRecovery");
    recoveryClass.createProperty("name", PropertyType.STRING);
    var recoveryIndexName = "UniqueBuildRecovery.name";
    session.begin();
    session.getMetadata().getSchema().getClass("UniqueBuildRecovery")
        .createIndex(recoveryIndexName, SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var distinctRow = (EntityImpl) session.newEntity("UniqueBuildRecovery");
    distinctRow.setProperty("name", "distinct");
    session.commit();
    assertTrue("the post-failure build must succeed and publish the index",
        session.getSharedContext().getIndexManager().existsIndex(recoveryIndexName));
    assertTrue("the post-failure build's engine must be registered",
        engineIsRegistered(recoveryIndexName));
  }

  /**
   * A transaction that drops a committed index and recreates one of the same name is a replace: the
   * old committed engine is deleted and a new engine (with the new type) is built and published under
   * the same name, in one commit, without a "already exists" collision. The old committed index is
   * NOTUNIQUE; the recreate is UNIQUE. After commit the index is the new type, a row inserted in the
   * same recreate transaction is present, and the replace survives a reload. Before the overlay
   * netted a drop-then-create to a create-only entry, so the old engine was never deleted and the
   * new build collided with the still-registered old engine.
   */
  @Test
  public void dropThenRecreateSameIndexNameInOneTransactionReplacesTheIndex() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ReplaceTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "ReplaceTarget.name";
    // Commit the original NOTUNIQUE index.
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    assertFalse("the original committed index must be NOTUNIQUE before the replace",
        indexManager.getIndex(indexName).isUnique());

    // Drop then recreate the same name (as a UNIQUE index) in one transaction, and insert a row so
    // the replaced index has a same-tx entry.
    session.begin();
    indexManager.dropIndex(session, indexName);
    session.getMetadata().getSchema().getClass("ReplaceTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var e = (EntityImpl) session.newEntity("ReplaceTarget");
    e.setProperty("name", "replaced");
    session.commit();

    // After commit the index exists exactly once, is the NEW type, and carries the same-tx row.
    assertTrue("the recreated index must be published after commit",
        indexManager.existsIndex(indexName));
    var replaced = indexManager.getIndex(indexName);
    assertTrue("the replace must leave the index as the new UNIQUE type", replaced.isUnique());
    assertTrue("the recreated index's engine must be registered", engineIsRegistered(indexName));
    var rids = session.computeInTx(tx -> replaced.getRids(session, "replaced").toList());
    assertEquals("the same-tx row must be in the recreated index", 1, rids.size());

    // The replace is durable: after a reload the index is still the new type with the row.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedManager = session.getSharedContext().getIndexManager();
    assertTrue("the recreated index must survive a reload", reloadedManager.existsIndex(indexName));
    assertTrue("the recreated index must still be UNIQUE after a reload",
        reloadedManager.getIndex(indexName).isUnique());
    var reloadedRids =
        session.computeInTx(
            tx -> reloadedManager.getIndex(indexName).getRids(session, "replaced").toList());
    assertEquals("the recreated index's row must survive the reload", 1, reloadedRids.size());
  }

  /**
   * The accepted best-effort publish semantic (tracked by YTDB-1101): a concurrent reader on another
   * session polling the shared index lookup path across a commit-time publish window must never crash
   * on that lock-free read path, and once the create commit completes it eventually sees the index
   * present in the shared maps. This does NOT assert reader atomicity mid-publish: a schema commit
   * excludes concurrent data commits but not lock-free reads, so a concurrent reader can observe a
   * torn two-map view (the index in one map, not yet the other) mid-publish, and the reader-atomic
   * snapshot flip is deferred to the read-chain snapshot-versioning work (YTDB-1101). A torn view is
   * the accepted v1 semantic, so this test does not fail on it; it pins the crash-free
   * lock-free-read behaviour and the eventual-consistency end state instead.
   *
   * <p>The interaction is deliberately bounded to a single publish window: the residual
   * concurrent-schema-commit-vs-reader window (a concurrent reader reading the schema/index-manager
   * record while the committer re-parses it during promotion) is the same accepted YTDB-1101 boundary
   * and is out of this step's scope, so the reader stays on the lock-free lookup-map path and the
   * committer runs a single schema commit rather than stress-cycling many.
   */
  @Test
  public void concurrentReaderNeverCrashesAndSeesEventuallyConsistentPublish() throws Exception {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PublishRaceTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "PublishRaceTarget.name";

    var readerReady = new CyclicBarrier(2);
    var stop = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();

    // A reader on its own session/thread repeatedly consults the shared lookup maps through the
    // lock-free public read path while the main thread publishes the index at commit. The reader must
    // never throw on that path (a crash there would be a real bug); a torn mid-publish view is the
    // accepted v1 semantic and is deliberately NOT asserted against.
    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        var im = other.getSharedContext().getIndexManager();
        readerReady.await(5, TimeUnit.SECONDS);
        while (!stop.get()) {
          im.existsIndex(indexName);
          im.getIndex(indexName);
        }
      } catch (final Throwable t) {
        readerError.compareAndSet(null, t);
      }
    }, "publish-race-reader");
    reader.start();

    session.activateOnCurrentThread();
    readerReady.await(5, TimeUnit.SECONDS);
    var indexManager = session.getSharedContext().getIndexManager();
    // A single create commit whose publish the concurrent reader crosses.
    session.executeInTx(tx -> session.getMetadata().getSchema().getClass("PublishRaceTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
    stop.set(true);
    reader.join(TimeUnit.SECONDS.toMillis(30));

    assertNull("a concurrent reader must never crash on the lock-free lookup-map read path against"
        + " the commit-time index publish", readerError.get());
    // Eventual consistency end state: after the create commit completes, a settled reader sees the
    // index in the shared maps.
    assertTrue(
        "after the create commit completes, the published index is visible in the shared maps",
        indexManager.existsIndex(indexName));
  }

  /**
   * The commit-time build handles non-scalar and null keys: an index over an EMBEDDEDLIST
   * property emits one entry per element of a same-tx row's list value (the collection-of-keys
   * branch), and a row whose indexed property is null is indexed under the null key, because the
   * deferred in-tx create applies the same ignoreNullValues resolution as the committed create
   * path (the storage default, false). The deferred path leaving the definition at its
   * ignore-nulls constructor default — silently skipping null keys a committed create would have
   * indexed — was a pinned divergence, fixed by routing both paths through the shared setting.
   * Every other build test uses a scalar non-null STRING key, so a failure on a null key or a
   * first-element-only population would be invisible without this test.
   */
  @Test
  public void commitTimeBuildIndexesMultiValueKeysAndNullKeyRows() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("MultiValueBuild");
    cls.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    var indexName = "MultiValueBuild.tags";

    session.begin();
    session.getMetadata().getSchema().getClass("MultiValueBuild")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "tags");
    var multi = (EntityImpl) session.newEntity("MultiValueBuild");
    multi.newEmbeddedList("tags", List.of("red", "green"));
    // A row whose indexed property is absent (a null key) must not break the build.
    var nullRow = (EntityImpl) session.newEntity("MultiValueBuild");
    nullRow.setProperty("unrelated", "value");
    session.commit();

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertTrue("the multi-value index's engine must be built after commit",
        engineIsRegistered(indexName));
    var redRids = session.computeInTx(tx -> index.getRids(session, "red").toList());
    assertEquals("each element of the list value must be indexed", 1, redRids.size());
    var greenRids = session.computeInTx(tx -> index.getRids(session, "green").toList());
    assertEquals("each element of the list value must be indexed", 1, greenRids.size());
    assertEquals("the multi-value row must be indexed under both its elements",
        multi.getIdentity(), redRids.getFirst());
    // The deferred in-tx create applies the same ignoreNullValues resolution as the committed
    // create path: no explicit metadata means the storage-wide default (false), so the null-key
    // row is indexed by the build exactly as it would be by a committed create.
    assertFalse(
        "the deferred in-tx create must apply the storage ignore-nulls default (false), as the"
            + " committed create path does",
        index.getDefinition().isNullValuesIgnored());
    var nullRids = session.computeInTx(tx -> index.getRids(session, null).toList());
    assertEquals("the null-key row must be indexed when null values are not ignored",
        1, nullRids.size());
    assertEquals("the null-key entry must point at the row whose indexed property is absent",
        nullRow.getIdentity(), nullRids.getFirst());
  }

  /**
   * A tx-created index carrying explicit {@code ignoreNullValues=true} metadata must honor it at
   * the commit-time build: the metadata wins over the storage default, so a same-tx row whose
   * indexed property is null is skipped while a valued row is indexed — exactly the committed
   * create path's contract. Together with the sibling default-resolution test this pins both arms
   * of the shared ignoreNullValues setting on the deferred path (a regression that dropped the
   * setting entirely would fail the sibling; one that inverted or hardcoded it fails this one).
   */
  @Test
  public void txCreatedIndexHonorsExplicitIgnoreNullValuesMetadata() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("NullMetaBuild");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "NullMetaBuild.name";

    session.begin();
    session.getMetadata().getSchema().getClass("NullMetaBuild")
        .createIndex(
            indexName,
            SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
            null,
            Map.of("ignoreNullValues", true),
            new String[] {"name"});
    var valued = (EntityImpl) session.newEntity("NullMetaBuild");
    valued.setProperty("name", "present");
    var nullRow = (EntityImpl) session.newEntity("NullMetaBuild");
    nullRow.setProperty("unrelated", "x");
    session.commit();

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertTrue("explicit ignoreNullValues=true metadata must be honored by the deferred create",
        index.getDefinition().isNullValuesIgnored());
    var presentRids = session.computeInTx(tx -> index.getRids(session, "present").toList());
    assertEquals("the valued same-tx row must be indexed", 1, presentRids.size());
    assertEquals(valued.getIdentity(), presentRids.getFirst());
    var nullRids = session.computeInTx(tx -> index.getRids(session, null).toList());
    assertTrue("a null-key row must be skipped when ignoreNullValues=true is explicit",
        nullRids.isEmpty());
  }

  /**
   * A subclass created under an indexed parent in the SAME transaction resolves its provisional
   * collection into the parent index's committed membership as the real collection name. During
   * the transaction the subclass's collection id is provisional (id->name resolution answers
   * null), so the membership ripple must resolve through the carried {@code <class>_<counter>}
   * name the commit creates the real collection under — the regression fixed here recorded a null
   * placeholder that persisted into the committed index's {@code collectionsToIndex}. After
   * commit (and across a reopen, the durable arm) the membership must carry the real collection
   * name and no null, and a subclass row inserted afterwards must be returned by a polymorphic
   * lookup through the parent index — proving the membership is functional, not just cosmetic.
   */
  @Test
  public void sameTxSubclassUnderIndexedParentResolvesRealCollectionNameInMembership() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ProvMemParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "ProvMemParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Create the subclass under the indexed parent inside one transaction: its collection is
    // provisional (<= -2) while the transaction is open, so the membership ripple resolves the
    // carried provisional name instead of a null placeholder.
    session.executeInTx(
        tx -> assertNotNull("the same-tx subclass create must succeed",
            session.getMetadata().getSchema()
                .createClass("ProvMemChild",
                    session.getMetadata().getSchema().getClass("ProvMemParent"))));

    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("ProvMemChild").getCollectionIds()[0]);
    assertNotNull("the committed subclass must own a real collection", childCollectionName);

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var membership = new HashSet<>(index.getCollections());
    assertFalse("no null placeholder may persist into the committed index's membership",
        membership.contains(null));
    assertTrue("the parent index must cover the same-tx subclass's real collection",
        membership.contains(childCollectionName));

    // The durable arm: force the index manager to re-parse its persisted record first — a reOpen
    // alone does NOT discard the SharedContext's in-memory index registry, so without the reload
    // the assertions below would re-read the same in-memory state and a save()-side serialization
    // bug would pass unnoticed (the sibling durable tests in this file use the same pattern).
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedIndex = session.getSharedContext().getIndexManager().getIndex(indexName);
    var reloadedMembership = new HashSet<>(reloadedIndex.getCollections());
    assertFalse("no null placeholder may survive a reopen in the durable membership",
        reloadedMembership.contains(null));
    assertTrue("the durable membership must carry the real collection name",
        reloadedMembership.contains(childCollectionName));

    // Functional proof: a subclass row inserted after the commit is indexed under the parent
    // index (its collection is covered), so a polymorphic lookup through it returns the row.
    session.begin();
    var childRow = (EntityImpl) session.newEntity("ProvMemChild");
    childRow.setProperty("name", "childValue");
    session.commit();
    var rids = session.computeInTx(tx -> reloadedIndex.getRids(session, "childValue").toList());
    assertEquals(
        "a polymorphic lookup through the parent index must return the same-tx subclass's row",
        1, rids.size());
    assertEquals(childRow.getIdentity(), rids.getFirst());
  }

  /**
   * The replace-then-ripple sequence (BG103): one transaction drops a committed index on the
   * parent, recreates the same name, and then creates a subclass under the parent. The ripple
   * targets the RECREATED tx-created handle — resolving it via the committed-only registry instead
   * returns the stale old committed handle, whose record the commit's dropped loop deletes before
   * the membership loops run, crashing the commit with RecordNotFound. With the overlay-aware fold
   * the commit succeeds and the recreated index covers the subclass's real collection, so a
   * polymorphic lookup returns subclass rows.
   */
  @Test
  public void replaceThenSubclassRippleCommitsWithRecreatedIndexCoveringSubclass() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("RepRipParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "RepRipParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var indexManager = session.getSharedContext().getIndexManager();

    session.executeInTx(
        tx -> {
          var s = session.getMetadata().getSchema();
          indexManager.dropIndex(session, indexName);
          s.getClass("RepRipParent")
              .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
          s.createClass("RepRipChild", s.getClass("RepRipParent"));
        });

    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("RepRipChild").getCollectionIds()[0]);
    assertNotNull("the committed subclass must own a real collection", childCollectionName);
    var recreated = indexManager.getIndex(indexName);
    assertNotNull("the recreated index must be published after the commit", recreated);
    assertTrue("the recreated index must cover the same-tx subclass's collection",
        new HashSet<>(recreated.getCollections()).contains(childCollectionName));

    // Functional arm: a subclass row inserted after commit is indexed under the recreated index.
    session.begin();
    var childRow = (EntityImpl) session.newEntity("RepRipChild");
    childRow.setProperty("name", "replacedChild");
    session.commit();
    var rids = session.computeInTx(tx -> recreated.getRids(session, "replacedChild").toList());
    assertEquals("a polymorphic lookup through the recreated index must return the subclass row",
        1, rids.size());
  }

  /**
   * The pure tx-created-index-then-ripple sequence (BG103's sibling, the already-filed BG102
   * shape): one transaction creates an index on the parent and then creates a subclass under it.
   * The ripple targets the tx-created handle, which the committed-only registry misses entirely —
   * pre-fix the resolution threw IndexException from the createClassInternal index-update loop
   * (and the setSuperClasses ripple's copy of the failure was swallowed into a dropped
   * polymorphic collection id). With the overlay-aware fold the subclass's carried collection
   * name joins the deferred handle's covered set, flows through the commit build, and the
   * committed index covers the subclass collection.
   */
  @Test
  public void txCreatedIndexThenSubclassRippleCommitsWithMembershipFolded() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("TxIdxRipParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "TxIdxRipParent.name";

    session.executeInTx(
        tx -> {
          var s = session.getMetadata().getSchema();
          s.getClass("TxIdxRipParent")
              .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
          s.createClass("TxIdxRipChild", s.getClass("TxIdxRipParent"));
        });

    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("TxIdxRipChild").getCollectionIds()[0]);
    assertNotNull("the committed subclass must own a real collection", childCollectionName);
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertNotNull("the tx-created index must be published after the commit", index);
    assertTrue("the folded membership must cover the same-tx subclass's collection",
        new HashSet<>(index.getCollections()).contains(childCollectionName));

    // Functional arm: a subclass row inserted after commit is indexed and found polymorphically.
    session.begin();
    var childRow = (EntityImpl) session.newEntity("TxIdxRipChild");
    childRow.setProperty("name", "foldedChild");
    session.commit();
    var rids = session.computeInTx(tx -> index.getRids(session, "foldedChild").toList());
    assertEquals("a polymorphic lookup through the tx-created index must return the subclass row",
        1, rids.size());
  }

  /**
   * A subclass created AND dropped under an indexed parent in the same transaction must net to no
   * membership change: the create ripple records the subclass's carried provisional
   * {@code <class>_<counter>} name into the overlay's membership-added category, and the drop
   * ripple must resolve the SAME carried name so the overlay cancels the pair. A remove side that
   * resolved the provisional id to null (the asymmetry fixed here) would fail to cancel the add,
   * and the commit would persist a phantom collection name into the committed index's
   * {@code collectionsToIndex} — naming a collection the reconciliation never creates, because
   * the dropped class allocates none. The durable arm re-parses the persisted index record to
   * prove no phantom name reached the record bytes.
   */
  @Test
  public void sameTxSubclassCreateThenDropLeavesNoPhantomMembership() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ProvDropParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "ProvDropParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var membershipBefore = new HashSet<>(index.getCollections());

    // Create the subclass under the indexed parent and drop it again inside one transaction: the
    // add ripple and the drop ripple both resolve the child's provisional collection id, so the
    // membership deltas must cancel to nothing.
    session.executeInTx(
        tx -> {
          var s = session.getMetadata().getSchema();
          s.createClass("ProvDropChild", s.getClass("ProvDropParent"));
          s.dropClass("ProvDropChild");
        });

    assertEquals(
        "a same-tx subclass create-then-drop must leave the committed membership unchanged",
        membershipBefore, new HashSet<>(index.getCollections()));

    // The durable arm: reload re-parses the persisted index record (reOpen alone does not discard
    // the SharedContext's in-memory index registry), so a phantom name persisted into the record
    // by the enroll phase would surface here.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedIndex = session.getSharedContext().getIndexManager().getIndex(indexName);
    var reloadedMembership = new HashSet<>(reloadedIndex.getCollections());
    assertEquals("no phantom collection name may survive in the durable membership",
        membershipBefore, reloadedMembership);
    assertFalse("no null placeholder may survive in the durable membership",
        reloadedMembership.contains(null));
  }

  /**
   * The detach variant of the create-then-drop pin: a subclass created under an indexed parent and
   * detached again ({@code removeSuperClass}) in the same transaction must not leave the parent
   * index covering the subclass's collection. Unlike the drop variant the subclass survives the
   * commit with a real collection, so this arm additionally proves the parent index does not name
   * that real collection and does not index rows inserted into the detached class afterwards — a
   * remove side that resolved the provisional id to null would leave the carried name dangling in
   * the membership-added category and the commit would durably cover the detached class.
   */
  @Test
  public void sameTxSubclassCreateThenDetachLeavesParentMembershipUnchanged() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ProvDetachParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "ProvDetachParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    var membershipBefore = new HashSet<>(index.getCollections());

    session.executeInTx(
        tx -> {
          var s = session.getMetadata().getSchema();
          s.createClass("ProvDetachChild", s.getClass("ProvDetachParent"));
          s.getClass("ProvDetachChild").removeSuperClass(s.getClass("ProvDetachParent"));
        });

    // The detached child commits its real collection (it still exists as a standalone class)…
    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("ProvDetachChild").getCollectionIds()[0]);
    assertNotNull("the detached child must own a real collection after commit",
        childCollectionName);

    // …but the parent index's membership must be exactly what it was before the transaction: the
    // add and remove ripples resolved the same carried name and cancelled.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloadedIndex = session.getSharedContext().getIndexManager().getIndex(indexName);
    var reloadedMembership = new HashSet<>(reloadedIndex.getCollections());
    assertEquals("the detach must cancel the same-tx membership add durably",
        membershipBefore, reloadedMembership);
    assertFalse("the parent index must not cover the detached class's collection",
        reloadedMembership.contains(childCollectionName));

    // Functional proof: a row inserted into the detached class is not returned through the parent
    // index (its collection is not covered).
    session.begin();
    var detachedRow = (EntityImpl) session.newEntity("ProvDetachChild");
    detachedRow.setProperty("name", "detachedValue");
    session.commit();
    var rids = session.computeInTx(tx -> reloadedIndex.getRids(session, "detachedValue").toList());
    assertTrue("a lookup through the parent index must not return the detached class's rows",
        rids.isEmpty());
  }

  /**
   * A membership ripple recorded for an index that is dropped later in the SAME transaction is
   * moot and must not break the commit: creating a subclass under an indexed parent records a
   * membership add for the parent index into the overlay, and the subsequent same-tx drop of that
   * index must purge the delta. Without the purge the enroll phase's membership apply re-writes
   * the index's metadata record AFTER the dropped loop already deleted it, failing the commit with
   * a record-not-found-family error — a legal DDL sequence turned into a commit failure.
   */
  @Test
  public void sameTxMembershipRippleThenIndexDropCommitsCleanly() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("RippleDropParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "RippleDropParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var indexManager = session.getSharedContext().getIndexManager();

    // One transaction: the subclass create ripples a membership add for the parent index, then
    // the index itself is dropped. The commit must succeed cleanly.
    session.executeInTx(
        tx -> {
          var s = session.getMetadata().getSchema();
          s.createClass("RippleDropChild", s.getClass("RippleDropParent"));
          indexManager.dropIndex(session, indexName);
        });

    assertFalse("the dropped index must be gone after the commit",
        indexManager.existsIndex(indexName));
    // The subclass itself must have committed (the drop invalidated only the index delta).
    assertNotNull("the subclass must survive the commit",
        session.getMetadata().getSchema().getClass("RippleDropChild"));

    // Durable arm: re-parse the persisted index-manager state and reopen.
    session.getSharedContext().getIndexManager().reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    assertFalse("the dropped index must stay gone after a reload",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
  }

  /**
   * Builds a deferred (unbuilt, unregistered) index handle carrying the given definition and
   * covered-collection names, the shape a tx-created index has between create and commit. Used by
   * the commit-window guard tests to inject invariant-violating handles (a bogus collection name,
   * a null definition) that no supported flow can produce, so the new fail-loud guards are
   * exercisable at all.
   */
  private IndexAbstract deferredHandle(
      String name, IndexDefinition definition, Set<String> collections) {
    var algorithm = Indexes.chooseDefaultIndexAlgorithm("NOTUNIQUE");
    var handle = (IndexAbstract) Indexes.createIndexInstance("NOTUNIQUE", algorithm, storage());
    handle.markDeferred(
        new IndexMetadata(name, definition, collections, "NOTUNIQUE", algorithm, -1, null));
    return handle;
  }

  /** Walks the cause chain looking for a message containing {@code fragment}. */
  private static boolean chainContainsMessage(Throwable thrown, String fragment) {
    for (var cause = thrown; cause != null;
        cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The enroll phase fails the commit loudly when a tx-dropped index name no longer resolves to a
   * committed registry entry — instead of silently skipping, which would commit the transaction
   * with the index fully alive and stale (the engine deletion and registry removal all key off the
   * plan entry the skip would omit). The invariant is normally protected by the metadata-write
   * mutex; the one reachable breach is the legacy non-transactional dropIndex path, which removes
   * the registry entry without engaging the mutex — exactly what this test drives from a second
   * session while the first session's transactional drop is still uncommitted.
   */
  @Test
  public void commitFailsLoudlyWhenTxDroppedIndexVanishesFromRegistry() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DropVanishTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DropVanishTarget.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    session.getSharedContext().getIndexManager().dropIndex(session, indexName);

    // The mutex bypass: a second session's legacy (non-transactional) drop removes the committed
    // registry entry and deletes the engine while the first session's drop is still tx-local.
    var other = openDatabase();
    try {
      other.activateOnCurrentThread();
      other.getSharedContext().getIndexManager().dropIndex(other, indexName);
    } finally {
      other.activateOnCurrentThread();
      other.close();
      session.activateOnCurrentThread();
    }

    try {
      session.commit();
      fail("the commit must fail loudly when the tx-dropped index vanished from the registry");
    } catch (final RuntimeException e) {
      assertTrue("the failure must name the vanished tx-dropped index, got: " + e,
          chainContainsMessage(e, "tx-dropped index '" + indexName + "'"));
    }
  }

  /**
   * The membership-add enroll loop fails the commit loudly when its target index no longer
   * resolves to a committed registry entry (same legacy-bypass seam as the tx-dropped guard test).
   * A silent skip would ship a parent index that never gains the subclass collection — silently
   * missed polymorphic query rows. The ripple is recorded by creating a subclass under the indexed
   * parent inside the transaction; the second session then legacy-drops the parent index before
   * the commit.
   */
  @Test
  public void commitFailsLoudlyWhenMembershipAddTargetVanishesFromRegistry() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemVanishParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "MemVanishParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    session.getMetadata().getSchema()
        .createClass("MemVanishChild",
            session.getMetadata().getSchema().getClass("MemVanishParent"));

    var other = openDatabase();
    try {
      other.activateOnCurrentThread();
      other.getSharedContext().getIndexManager().dropIndex(other, indexName);
    } finally {
      other.activateOnCurrentThread();
      other.close();
      session.activateOnCurrentThread();
    }

    try {
      session.commit();
      fail("the commit must fail loudly when a membership-add target vanished from the registry");
    } catch (final RuntimeException e) {
      assertTrue("the failure must name the vanished membership-add target, got: " + e,
          chainContainsMessage(e, "membership add for index '" + indexName + "'"));
    }
  }

  /**
   * The membership-remove enroll loop's mirror guard: a committed subclass is detached inside the
   * transaction (recording a membership removal for the parent index), the second session
   * legacy-drops the parent index, and the commit must fail loudly naming the vanished target
   * rather than silently skipping — which would leave a phantom stale membership durably covering
   * the detached collection.
   */
  @Test
  public void commitFailsLoudlyWhenMembershipRemoveTargetVanishesFromRegistry() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemRVanishParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "MemRVanishParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // A committed subclass, so the detach ripples a plain committed-collection membership removal.
    schema.createClass("MemRVanishChild", schema.getClass("MemRVanishParent"));

    session.begin();
    session.getMetadata().getSchema().getClass("MemRVanishChild")
        .removeSuperClass(session.getMetadata().getSchema().getClass("MemRVanishParent"));

    var other = openDatabase();
    try {
      other.activateOnCurrentThread();
      other.getSharedContext().getIndexManager().dropIndex(other, indexName);
    } finally {
      other.activateOnCurrentThread();
      other.close();
      session.activateOnCurrentThread();
    }

    try {
      session.commit();
      fail(
          "the commit must fail loudly when a membership-remove target vanished from the registry");
    } catch (final RuntimeException e) {
      assertTrue("the failure must name the vanished membership-remove target, got: " + e,
          chainContainsMessage(e, "membership remove for index '" + indexName + "'"));
    }
  }

  /**
   * The enroll-phase emptiness bound fails the commit loudly when a tx-created handle covers a
   * collection name that does not resolve post-reconciliation — instead of silently skipping the
   * name, which would bypass both count stages and ship a committed index silently missing that
   * collection's rows. No supported flow produces such a handle (the deferred create resolves
   * every name through the shared provisional-aware resolver), so the test injects one directly
   * into the overlay. The failed commit must also leave no phantom index behind.
   */
  @Test
  public void commitFailsLoudlyOnUnresolvableCoveredCollectionAtEnroll() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("SeamDefClass");
    cls.createProperty("name", PropertyType.STRING);

    session.begin();
    // A small genuine schema write seeds the tx-local state the injection needs.
    session.getMetadata().getSchema().createClass("SeamHostClass");
    var handle =
        deferredHandle(
            "SeamBrokenCover.idx",
            new PropertyIndexDefinition("SeamDefClass", "name", PropertyTypeInternal.STRING),
            Set.of("no_such_collection"));
    session.getTxSchemaState().ensureIndexOverlay().recordCreated(handle);

    try {
      session.commit();
      fail("the commit must fail loudly on an unresolvable covered collection");
    } catch (final RuntimeException e) {
      assertTrue("the failure must name the unresolvable collection, got: " + e,
          chainContainsMessage(e, "'no_such_collection'"));
    }
    assertFalse("the failed commit must leave no phantom index",
        session.getSharedContext().getIndexManager().existsIndex("SeamBrokenCover.idx"));
    assertFalse("the failed commit must roll back the tx-created class",
        session.getMetadata().getSchema().existsClass("SeamHostClass"));
  }

  /**
   * The enroll-phase emptiness bound fails the commit loudly on a definition-less tx-created
   * handle (an invariant violation — manual indexes are rejected at create) instead of silently
   * waiving the bound and later committing a completely empty index.
   */
  @Test
  public void commitFailsLoudlyOnDefinitionlessTxCreatedHandle() {
    session.begin();
    session.getMetadata().getSchema().createClass("NoDefHostClass");
    var handle = deferredHandle("NoDefSeam.idx", null, Set.of());
    session.getTxSchemaState().ensureIndexOverlay().recordCreated(handle);

    try {
      session.commit();
      fail("the commit must fail loudly on a definition-less tx-created handle");
    } catch (final RuntimeException e) {
      assertTrue("the failure must name the definition-less handle, got: " + e,
          chainContainsMessage(e, "carries no definition at commit time"));
    }
    assertFalse("the failed commit must leave no phantom index",
        session.getSharedContext().getIndexManager().existsIndex("NoDefSeam.idx"));
  }

  /**
   * The commit-time population's coverage-set build throws (rather than silently dropping the
   * collection from the coverage set, which would build an index missing that collection's rows)
   * when a covered name does not resolve. Unreachable end-to-end because the enroll-phase bound
   * throws first for the same handle, so the guard is exercised by calling the package-private
   * population step directly with an injected broken handle — defense in depth across the two
   * phases.
   */
  @Test
  public void populateThrowsOnUnresolvableCoveredCollection() {
    session.begin();
    try {
      var handle =
          deferredHandle(
              "PopulateBroken.idx",
              new PropertyIndexDefinition("Whatever", "name", PropertyTypeInternal.STRING),
              Set.of("no_such_collection"));
      var transaction = (FrontendTransactionImpl) session.getTransactionInternal();
      var manager = session.getSharedContext().getIndexManager();
      var thrown =
          assertThrows(IndexException.class,
              () -> manager.populateTxCreatedIndex(session, transaction, handle));
      assertTrue("the failure must name the unresolvable collection, got: " + thrown,
          thrown.getMessage().contains("'no_such_collection'"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The commit-time population throws on a definition-less handle instead of returning silently —
   * a silent return would commit a completely empty index. Direct-call seam for the same reason as
   * the sibling coverage-guard test (the enroll-phase guard shadows this one end-to-end).
   */
  @Test
  public void populateThrowsOnDefinitionlessHandle() {
    session.begin();
    try {
      var handle = deferredHandle("PopulateNoDef.idx", null, Set.of());
      var transaction = (FrontendTransactionImpl) session.getTransactionInternal();
      var manager = session.getSharedContext().getIndexManager();
      var thrown =
          assertThrows(IllegalStateException.class,
              () -> manager.populateTxCreatedIndex(session, transaction, handle));
      assertTrue("the failure must name the definition-less handle, got: " + thrown,
          thrown.getMessage().contains("carries no definition at commit-time build"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The commit-time population throws on a record operation whose RID still carries a provisional
   * collection id (≤ -2) — post-apply every provisional id has been rewritten to its real
   * collection, so a leftover is an apply-phase invariant violation that the coverage filter
   * (covered ids are always ≥ 0) would otherwise skip silently. Direct-call seam: mid-transaction
   * a fresh entity of a tx-created class carries exactly that provisional-id shape.
   */
  @Test
  public void populateThrowsOnProvisionalCollectionIdRid() {
    session.begin();
    try {
      session.getMetadata().getSchema().createClass("ProvRidGuardClass");
      var row = (EntityImpl) session.newEntity("ProvRidGuardClass");
      row.setProperty("name", "pending");
      var handle =
          deferredHandle(
              "ProvRidGuard.idx",
              new PropertyIndexDefinition("ProvRidGuardClass", "name",
                  PropertyTypeInternal.STRING),
              Set.of());
      var transaction = (FrontendTransactionImpl) session.getTransactionInternal();
      var manager = session.getSharedContext().getIndexManager();
      var thrown =
          assertThrows(IllegalStateException.class,
              () -> manager.populateTxCreatedIndex(session, transaction, handle));
      assertTrue("the failure must name the provisional-id violation, got: " + thrown,
          thrown.getMessage().contains("provisional collection id"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The commit-time population throws on a COVERED row whose RID is still non-persistent (an
   * apply-phase invariant violation — indexing it would store a temporary RID as the index value,
   * skipping it would drop the row from the index). The coverage check runs first, so the guard is
   * probed mid-transaction: a fresh entity of a committed covered class carries the real
   * collection id with a temporary position, exactly the covered-but-non-persistent shape.
   */
  @Test
  public void populateThrowsOnCoveredNonPersistentRid() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RidGuardClass");
    cls.createProperty("name", PropertyType.STRING);
    // Cover ALL the class's collections: the collection-selection strategy may assign the fresh
    // entity to any of them, and the guard only fires for a COVERED non-persistent RID.
    var collectionNames = new HashSet<String>();
    for (var collectionId : session.getMetadata().getSchema().getClass("RidGuardClass")
        .getCollectionIds()) {
      collectionNames.add(session.getCollectionNameById(collectionId));
    }

    session.begin();
    try {
      var row = (EntityImpl) session.newEntity("RidGuardClass");
      row.setProperty("name", "pending");
      var handle =
          deferredHandle(
              "RidGuard.idx",
              new PropertyIndexDefinition("RidGuardClass", "name", PropertyTypeInternal.STRING),
              collectionNames);
      var transaction = (FrontendTransactionImpl) session.getTransactionInternal();
      var manager = session.getSharedContext().getIndexManager();
      var thrown =
          assertThrows(IllegalStateException.class,
              () -> manager.populateTxCreatedIndex(session, transaction, handle));
      assertTrue("the failure must name the non-persistent covered RID, got: " + thrown,
          thrown.getMessage().contains("non-persistent RID"));
    } finally {
      session.rollback();
    }
  }

  /**
   * Explicit {@code ignoreNullValues} metadata beats the storage-wide default on the deferred
   * path, in BOTH directions: with the global default flipped to {@code true} (ignore nulls), a
   * tx-created index with no metadata inherits the flipped default (proving the default
   * resolution reads the config rather than hardcoding {@code false}), while a sibling tx-created
   * index carrying explicit {@code ignoreNullValues=false} still indexes null keys (proving
   * explicit metadata wins over the default). Complements the sibling null-handling tests, which
   * exercise only the false-default and explicit-true arms.
   */
  @Test
  public void txCreatedIndexExplicitFalseMetadataBeatsFlippedGlobalDefault() {
    var configuration = storage().getContextConfiguration();
    var originalDefault =
        configuration.getValueAsBoolean(GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT);
    configuration.setValue(GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT, true);
    try {
      var schema = session.getMetadata().getSchema();
      var cls = schema.createClass("NullFlipBuild");
      cls.createProperty("a", PropertyType.STRING);
      cls.createProperty("b", PropertyType.STRING);

      session.begin();
      var target = session.getMetadata().getSchema().getClass("NullFlipBuild");
      target.createIndex("NullFlipBuild.a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
      target.createIndex(
          "NullFlipBuild.b",
          SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", false),
          new String[] {"b"});
      // Both indexed properties are absent, so the row carries a null key for both indexes.
      var row = (EntityImpl) session.newEntity("NullFlipBuild");
      row.setProperty("unrelated", "x");
      session.commit();

      var manager = session.getSharedContext().getIndexManager();
      var defaultIndex = manager.getIndex("NullFlipBuild.a");
      var explicitIndex = manager.getIndex("NullFlipBuild.b");
      assertTrue(
          "with the flipped global default, a metadata-less deferred create must ignore nulls",
          defaultIndex.getDefinition().isNullValuesIgnored());
      assertFalse("explicit ignoreNullValues=false must beat the flipped global default",
          explicitIndex.getDefinition().isNullValuesIgnored());
      assertTrue("the null-key row must be skipped by the default-resolved index",
          session.computeInTx(tx -> defaultIndex.getRids(session, null).toList()).isEmpty());
      assertEquals("the null-key row must be indexed by the explicit-false index",
          1, session.computeInTx(tx -> explicitIndex.getRids(session, null).toList()).size());
    } finally {
      configuration.setValue(GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT,
          originalDefault);
    }
  }

  /**
   * Two indexes created in one transaction both build and publish at commit: the commit-local
   * engine-id allocator hands the second create a distinct slot because each create publishes
   * its engine into the slot before the next scan. A regression that allocated the same id
   * twice (or dropped the second build) only surfaces with two-or-more creates in a single
   * commit, which no other test drives (the failed-drop test drops two, but no test creates
   * two).
   */
  @Test
  public void twoIndexesCreatedInOneTransactionBothBuildAndPublish() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("MultiCreate");
    cls.createProperty("a", PropertyType.STRING);
    cls.createProperty("b", PropertyType.STRING);
    var firstIndexName = "MultiCreate.a";
    var secondIndexName = "MultiCreate.b";

    session.begin();
    var target = session.getMetadata().getSchema().getClass("MultiCreate");
    target.createIndex(firstIndexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    target.createIndex(secondIndexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "b");
    var row = (EntityImpl) session.newEntity("MultiCreate");
    row.setProperty("a", "left");
    row.setProperty("b", "right");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the first tx-created index must be published after commit",
        indexManager.existsIndex(firstIndexName));
    assertTrue("the second tx-created index must be published after commit",
        indexManager.existsIndex(secondIndexName));
    var first = indexManager.getIndex(firstIndexName);
    var second = indexManager.getIndex(secondIndexName);
    assertTrue("both engines must be built and registered", engineIsRegistered(firstIndexName));
    assertTrue("both engines must be built and registered",
        engineIsRegistered(secondIndexName));
    assertTrue("both built engines must carry real engine ids", first.getIndexId() >= 0);
    assertTrue("both built engines must carry real engine ids", second.getIndexId() >= 0);
    assertNotEquals("the two builds must occupy distinct engine slots",
        first.getIndexId(), second.getIndexId());
    var leftRids = session.computeInTx(tx -> first.getRids(session, "left").toList());
    assertEquals("the same-tx row must be indexed by the first index", 1, leftRids.size());
    var rightRids = session.computeInTx(tx -> second.getRids(session, "right").toList());
    assertEquals("the same-tx row must be indexed by the second index", 1, rightRids.size());
  }

  /**
   * A concurrent reader consulting a shared committed index's collection membership across a
   * commit that applies a membership change must never crash on that read path, and once the
   * commit completes it sees the final membership (eventual consistency). The membership read
   * ({@code getCollections()}) takes the per-index shared lock against the commit's eager
   * per-index-write-locked mutation; the reader deliberately probes the returned view with
   * {@code contains} rather than iterating it, because the view is live and iteration atomicity
   * mid-commit is part of the accepted best-effort publish boundary (YTDB-1101), not a v1
   * guarantee. Bounded: the reader stops right after the commit returns, and the class's
   * stuck-thread timeout backstops a wedged thread.
   */
  @Test
  public void concurrentMembershipReaderNeverCrashesAcrossAMembershipCommit() throws Exception {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemRaceParent");
    parent.createProperty("name", PropertyType.STRING);
    var indexName = "MemRaceParent.name";
    parent.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var child = schema.createClass("MemRaceChild");
    child.createProperty("name", PropertyType.STRING);
    var childCollectionName =
        session.getCollectionNameById(
            session.getMetadata().getSchema().getClass("MemRaceChild").getCollectionIds()[0]);

    var readerReady = new CyclicBarrier(2);
    var stop = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();
    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        // Fetch the shared index handle once, so the loop contends only on the per-index
        // membership read, not on the index-manager lock the whole commit window holds.
        var sharedIndex = other.getSharedContext().getIndexManager().getIndex(indexName);
        readerReady.await(5, TimeUnit.SECONDS);
        while (!stop.get()) {
          // The lock-handshake probe: getCollections acquires and releases the per-index
          // shared lock against the commit's exclusive-locked add; contains avoids iterating
          // the live view (see the Javadoc).
          sharedIndex.getCollections().contains(childCollectionName);
        }
      } catch (final Throwable t) {
        readerError.compareAndSet(null, t);
      }
    }, "membership-race-reader");
    reader.start();

    session.activateOnCurrentThread();
    readerReady.await(5, TimeUnit.SECONDS);
    session.executeInTx(
        tx -> session.getMetadata().getSchema().getClass("MemRaceChild")
            .addSuperClass(session.getMetadata().getSchema().getClass("MemRaceParent")));
    stop.set(true);
    reader.join(TimeUnit.SECONDS.toMillis(30));
    assertFalse("the reader thread must terminate within the bound", reader.isAlive());

    assertNull("a concurrent membership reader must never crash across a membership commit",
        readerError.get());
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertTrue("after the commit completes the parent index must cover the child collection",
        new HashSet<>(index.getCollections()).contains(childCollectionName));
  }

  /**
   * Breadcrumb for crash-before-commit / crash-after-commit recovery of a commit-time index engine
   * build and delete. The rollback half is covered by the failed-commit tests
   * ({@code failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId} and
   * {@code failedDropCommitLeavesTheSurvivingCommittedIndexUsable}); the clean-reload half by
   * {@code builtIndexSurvivesReload}. The crash half — stop the storage hard, restore from the WAL,
   * and assert the built engine's files replayed (committed) or are absent (crashed before commit) —
   * leans on the {@code LocalPaginatedStorageRestoreFromWALIT} close-copy-restore harness and is
   * deferred to the integration-test layer, mirroring the collection-arm breadcrumb. Kept as an
   * ignored placeholder so the gap is visible at the test surface.
   */
  @Test
  @Ignore("crash recovery of a commit-time index engine build/delete: needs the "
      + "LocalPaginatedStorageRestoreFromWALIT harness; deferred to the integration-test layer")
  public void crashRecoveryOfCommitTimeIndexEngineIsDeferredToIT() {
    // Intentionally empty: see the Javadoc breadcrumb.
  }

  /**
   * Walks the cause chain looking for the duplicated-key failure. The commit path may rethrow
   * the rejection as-is or wrapped, so the assertion accepts either shape without pinning the
   * wrapper type.
   */
  private static boolean chainContainsDuplicatedKey(Throwable thrown) {
    for (var cause = thrown; cause != null;
        cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (cause instanceof RecordDuplicatedException) {
        return true;
      }
    }
    return false;
  }
}
