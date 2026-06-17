package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Set;
import org.junit.Test;

/**
 * Coverage for the de-guarded schema- and index-mutation entry points. The throw-guards (the
 * {@code SchemaShared} schema-record save, {@code dropClass} / {@code dropClassInternal}, and the
 * index-manager {@code createIndex} / {@code dropIndex}) and the collection-membership self-commit
 * sites ({@code addCollectionToIndex} / {@code removeCollectionFromIndex}) used to forbid running
 * inside a transaction. They now route a mutation into the transaction-local schema view when a
 * schema transaction is in progress, leaving the committed shared schema and the shared index
 * registry untouched until commit.
 *
 * <p>The committed-schema promotion and the structural reconciliation at commit are a later track,
 * so these tests assert the isolation-and-rollback half only: an in-transaction DDL write succeeds
 * (no longer throws), is invisible to a concurrent session during the transaction, and leaves the
 * shared state untouched on rollback. The silent failure these tests defend against is the eager
 * shared-index membership apply: a membership ripple that mutated the shared {@code Index}'s
 * {@code collectionsToIndex} during the transaction would survive a rollback.
 */
public class SchemaDeguardTest extends DbTestBase {

  /**
   * A class created inside a transaction no longer throws: the de-guarded save path routes the
   * change into the tx-local copy instead of refusing the active transaction. The class is visible
   * through the routing seam during the transaction (it was created in the tx-local view), and the
   * write-view is seeded as the side effect of the first routed write.
   */
  @Test
  public void createClassInsideTransactionRoutesToTxLocalAndDoesNotThrow() {
    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          var created = schema.createClass("InTxCreated");
          assertNotNull("an in-transaction create must succeed against the tx-local copy", created);

          assertTrue("the created class must be visible through the routing seam during the tx",
              schema.existsClass("InTxCreated"));
          assertNotNull("the first routed write must have seeded the tx-local schema state",
              session.getTxSchemaState());
        });
  }

  /**
   * A class created inside a transaction lands only in the tx-local copy: the committed shared
   * schema is not mutated during the transaction, and after a rollback the class is absent from the
   * committed schema. This is the record-local isolation property (the create is not promoted to
   * the shared instance until commit, which is a later track).
   */
  @Test
  public void inTransactionCreateIsNotInCommittedSchemaAndRollsBack() {
    var committed = session.getSharedContext().getSchema();

    session.begin();
    session.getMetadata().getSchema().createClass("RolledBackCreate");
    assertFalse("an in-transaction create must not touch the committed shared schema",
        committed.existsClass("RolledBackCreate"));
    session.rollback();

    assertFalse("after rollback the created class must be absent from the committed schema",
        committed.existsClass("RolledBackCreate"));
  }

  /**
   * A class dropped inside a transaction no longer throws: the de-guarded {@code dropClass} routes
   * the removal into the tx-local copy. During the transaction the committed shared schema still
   * carries the class (the drop is isolated), and after a rollback the class is still present in
   * the committed schema. The structural collection/index deletion is deferred to commit, so this
   * test asserts the metadata-level isolation only.
   */
  @Test
  public void dropClassInsideTransactionIsIsolatedAndRollsBack() {
    session.getMetadata().getSchema().createClass("ToDropInTx");
    var committed = session.getSharedContext().getSchema();
    assertTrue("the class must exist in the committed schema before the test",
        committed.existsClass("ToDropInTx"));

    session.begin();
    session.getMetadata().getSchema().dropClass("ToDropInTx");
    assertTrue("an in-transaction drop must leave the committed shared schema untouched",
        committed.existsClass("ToDropInTx"));
    session.rollback();

    assertTrue("after rollback the dropped class must still be present in the committed schema",
        committed.existsClass("ToDropInTx"));
  }

  /**
   * The silent-leak surface this de-guard defends against. A polymorphic collection-membership
   * ripple (creating a subclass of an indexed class inside a transaction) must not eagerly apply to
   * the shared {@code Index}'s {@code collectionsToIndex}. The de-guarded membership site records
   * the change into the tx-local changed-class set instead, so after a rollback the shared index's
   * collection membership is exactly what it was before the transaction. An implementation that
   * left the eager shared apply in place would leak the subclass collection into the shared index
   * and survive the rollback.
   */
  @Test
  public void membershipRippleInTransactionLeavesSharedIndexUntouchedOnRollback() {
    // Build an indexed superclass at the top level (the committed path), so its index exists and is
    // shared. Creating a subclass ripples the subclass's collection into the superclass index.
    var schema = session.getMetadata().getSchema();
    var superCls = schema.createClass("RippleSuper");
    superCls.createProperty("name", PropertyType.STRING);
    var indexName = "RippleSuper.name";
    superCls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    var sharedIndex = indexManager.getIndex(indexName);
    assertNotNull("the superclass index must exist on the committed path", sharedIndex);
    var membershipBefore = Set.copyOf(sharedIndex.getCollections());

    // Create a subclass inside a transaction: the membership ripple registers the subclass's
    // collection with the superclass index. With the de-guard this records into the tx-local
    // changed-class set, not the shared Index.
    session.begin();
    var subCls = schema.createClass("RippleSub", superCls);
    assertNotNull("the in-transaction subclass create must succeed", subCls);

    // During the transaction the shared index membership must already be untouched: the eager
    // shared apply is exactly the leak the de-guard removes.
    assertEquals(
        "the shared index collection membership must be untouched during the transaction",
        membershipBefore, Set.copyOf(sharedIndex.getCollections()));
    session.rollback();

    assertEquals(
        "after rollback the shared index collection membership must equal the pre-transaction set",
        membershipBefore, Set.copyOf(sharedIndex.getCollections()));
  }

  /**
   * The de-guarded membership ripple records the affected class into the tx-local changed-class set
   * (the hook the commit consumes), proving the change is captured rather than dropped. Creating an
   * indexed subclass in a transaction must mark the index's owning class changed.
   */
  @Test
  public void membershipRippleRecordsChangedClassIntoTxLocalState() {
    var schema = session.getMetadata().getSchema();
    var superCls = schema.createClass("RecordSuper");
    superCls.createProperty("name", PropertyType.STRING);
    superCls.createIndex("RecordSuper.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.executeInTx(
        tx -> {
          schema.createClass("RecordSub", superCls);
          var state = session.getTxSchemaState();
          assertNotNull("an in-transaction membership ripple must have seeded the tx-local state",
              state);
          assertTrue(
              "the index's owning class must be recorded in the tx-local changed-class set",
              state.getChangedClasses().contains("RecordSuper"));
        });
  }

  /**
   * A second session does not observe an in-transaction class create. The first session creates a
   * class inside an open transaction; a concurrent session opened against the same database must
   * not see it until commit. The create is rolled back so the committed schema stays clean for
   * other tests.
   */
  @Test
  public void concurrentSessionDoesNotSeeInTransactionCreate() {
    session.begin();
    session.getMetadata().getSchema().createClass("ConcurrentInvisible");

    DatabaseSessionEmbedded other = openDatabase();
    try {
      assertFalse(
          "a concurrent session must not see an uncommitted in-transaction class create",
          other.getMetadata().getSchema().existsClass("ConcurrentInvisible"));
    } finally {
      other.close();
    }
    session.rollback();
  }

  /**
   * An index created inside a transaction no longer throws and is not eagerly registered in the
   * shared index manager: the de-guarded {@code createIndex} records the index's owning class into
   * the tx-local changed-class set and defers the engine build and shared registration to commit.
   * So during the transaction the shared index manager does not yet know the index, and a rollback
   * leaves it unknown.
   */
  @Test
  public void createIndexInsideTransactionDefersToCommitAndDoesNotThrow() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("InTxIndexed");
    cls.createProperty("name", PropertyType.STRING);

    var indexManager = session.getSharedContext().getIndexManager();
    var indexName = "InTxIndexed.name";

    session.begin();
    // Must not throw the old "Cannot create a new index inside a transaction".
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    assertFalse(
        "an in-transaction index create must not register the index in the shared manager yet",
        indexManager.existsIndex(indexName));
    var state = session.getTxSchemaState();
    assertNotNull("an in-transaction index create must have seeded the tx-local state", state);
    assertTrue("the index's owning class must be recorded in the tx-local changed-class set",
        state.getChangedClasses().contains("InTxIndexed"));
    session.rollback();

    assertFalse("after rollback the deferred index must remain unknown to the shared manager",
        indexManager.existsIndex(indexName));
  }

  /**
   * An index dropped inside a transaction no longer throws and is not eagerly removed from the
   * shared index manager: the de-guarded {@code dropIndex} records the index's owning class into
   * the tx-local changed-class set and defers the engine drop and shared removal to commit. So
   * during the transaction the index is still present in the shared manager, and a rollback keeps
   * it.
   */
  @Test
  public void dropIndexInsideTransactionDefersToCommitAndDoesNotThrow() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("InTxDropIndex");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "InTxDropIndex.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the index must exist on the committed path before the test",
        indexManager.existsIndex(indexName));

    session.begin();
    // Must not throw the old "Cannot drop an index inside a transaction".
    indexManager.dropIndex(session, indexName);

    assertTrue("an in-transaction index drop must leave the shared index manager untouched",
        indexManager.existsIndex(indexName));
    var state = session.getTxSchemaState();
    assertNotNull("an in-transaction index drop must have seeded the tx-local state", state);
    assertTrue("the index's owning class must be recorded in the tx-local changed-class set",
        state.getChangedClasses().contains("InTxDropIndex"));
    session.rollback();

    assertTrue(
        "after rollback the deferred drop must leave the index present in the shared manager",
        indexManager.existsIndex(indexName));
  }

  /**
   * A {@code CREATE INDEX} issued through the public SQL path while a user transaction is open must
   * not NPE. The de-guarded {@code createIndex} returns a transaction-deferred handle whose engine
   * is not built yet; the SQL statement immediately probes the new index with {@code size()}, which
   * previously dereferenced the unset engine and crashed. The deferred handle now carries its
   * definition and reports an empty (zero) size for the unbuilt index, so the statement completes
   * cleanly. The index is still not registered in the shared manager during the transaction and the
   * owning class is recorded into the tx-local changed-class set; a rollback leaves it unknown.
   */
  @Test
  public void createIndexSqlInsideTransactionDoesNotNpeAndDefersToCommit() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("InTxSqlIndexed");
    cls.createProperty("name", PropertyType.STRING);

    var indexManager = session.getSharedContext().getIndexManager();
    var indexName = "InTxSqlIndexed.name";

    session.begin();
    // The SQL CREATE INDEX execute path runs idx.size() on the freshly created handle. Before the
    // fix this NPE'd on the engine-less deferred handle; it must now complete without throwing.
    session.command("CREATE INDEX " + indexName + " ON InTxSqlIndexed (name) NOTUNIQUE");

    assertFalse(
        "an in-transaction SQL index create must not register the index in the shared manager yet",
        indexManager.existsIndex(indexName));
    var state = session.getTxSchemaState();
    assertNotNull("an in-transaction SQL index create must have seeded the tx-local state", state);
    assertTrue("the index's owning class must be recorded in the tx-local changed-class set",
        state.getChangedClasses().contains("InTxSqlIndexed"));
    session.rollback();

    assertFalse("after rollback the deferred SQL index must remain unknown to the shared manager",
        indexManager.existsIndex(indexName));
  }

  /**
   * The transaction-deferred index handle returned by the index manager is safe to use on the
   * public path: it carries its definition (so name and owning class answer sensibly) and reports a
   * zero size for the unbuilt index instead of dereferencing an absent engine. This is the contract
   * the SQL execute path relies on when it probes the new index with {@code size()}.
   */
  @Test
  public void deferredIndexHandleReportsDefinitionAndZeroSize() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("InTxDeferredHandle");
    cls.createProperty("name", PropertyType.STRING);

    var indexManager = session.getSharedContext().getIndexManager();
    var indexName = "InTxDeferredHandle.name";

    session.executeInTx(
        tx -> {
          var deferred =
              indexManager.createIndex(
                  session,
                  indexName,
                  SchemaClass.INDEX_TYPE.NOTUNIQUE.name(),
                  new PropertyIndexDefinition(
                      "InTxDeferredHandle", "name", PropertyTypeInternal.STRING),
                  cls.getPolymorphicCollectionIds(),
                  null,
                  null);

          assertNotNull("the de-guarded createIndex must return a non-null deferred handle",
              deferred);
          assertEquals("the deferred handle must carry its name from the definition",
              indexName, deferred.getName());
          assertNotNull("the deferred handle must carry its definition", deferred.getDefinition());
          assertEquals("the deferred handle's definition must name the owning class",
              "InTxDeferredHandle", deferred.getDefinition().getClassName());
          assertEquals("an unbuilt deferred index must report a zero size, not crash",
              0L, deferred.size(session));

          // The deferred handle must report the collection membership derived from its definition's
          // collection ids, not an empty set. markDeferred copies findCollectionsByIds(...) into
          // collectionsToIndex; this asserts that copy survived rather than being dropped. The
          // expected set is the owning class's polymorphic collections resolved to names exactly as
          // production resolves them, so an empty-set markDeferred regression fails here (the size()
          // and metadata assertions above would still pass under that regression).
          var expectedCollections = new java.util.HashSet<String>();
          for (var collectionId : cls.getPolymorphicCollectionIds()) {
            expectedCollections.add(session.getCollectionNameById(collectionId));
          }
          assertFalse(
              "an index over a class that owns collections must carry a non-empty membership",
              expectedCollections.isEmpty());
          assertEquals(
              "the deferred handle must report the collections derived from its definition's"
                  + " collection ids",
              expectedCollections, Set.copyOf(deferred.getCollections()));

          // A value lookup on the unbuilt deferred handle must answer "no rids" instead of
          // dereferencing the absent engine (indexId = -1). This pins the documented contract that a
          // deferred handle answers get()/getRids() sensibly, not just size(): the engine-less read
          // short-circuits to an empty stream rather than passing indexId = -1 to the storage layer.
          // The NOTUNIQUE handle above is multi-value; a UNIQUE deferred handle exercises the
          // one-value guard, so both index-value families are covered.
          try (var rids = deferred.getRids(session, "anything")) {
            assertEquals(
                "a value lookup on an unbuilt multi-value deferred handle must find no rids",
                0L, rids.count());
          }
          var uniqueDeferred =
              indexManager.createIndex(
                  session,
                  "InTxDeferredHandle.unique",
                  SchemaClass.INDEX_TYPE.UNIQUE.name(),
                  new PropertyIndexDefinition(
                      "InTxDeferredHandle", "name", PropertyTypeInternal.STRING),
                  cls.getPolymorphicCollectionIds(),
                  null,
                  null);
          try (var rids = uniqueDeferred.getRids(session, "anything")) {
            assertEquals(
                "a value lookup on an unbuilt one-value deferred handle must find no rids",
                0L, rids.count());
          }

          assertFalse(
              "the deferred handle must not be registered in the shared manager during the tx",
              indexManager.existsIndex(indexName));
        });

    assertFalse("after commit the deferred-only index must remain unregistered until a later track"
        + " builds it",
        indexManager.existsIndex(indexName));
  }

  /**
   * Creating an index inside a transaction whose name already exists in the shared registry does not
   * throw and does not eagerly register a second index: the de-guarded create records the owning
   * class into the tx-local changed-class set and returns a deferred handle, deferring collision
   * detection to the commit-time reconciliation a later track owns. This is the intentional
   * divergence from the legacy top-level path, which rejects a duplicate name loudly. The test pins
   * both halves so a later track that moves the collision check is a deliberate, test-visible change
   * rather than a silent contract drift: the legacy path still throws, the in-transaction path still
   * defers.
   */
  @Test
  public void duplicateIndexNameInsideTransactionDefersInsteadOfThrowing() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DupIdx");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DupIdx.name";
    // Committed, top-level: the index now exists in the shared registry.
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("the index must exist on the committed path before the test",
        indexManager.existsIndex(indexName));

    // The legacy top-level path rejects the duplicate name loudly: re-creating it outside a
    // transaction throws. This is the contract the in-transaction path deliberately diverges from.
    assertThrows(
        "re-creating an existing index name outside a transaction must be rejected loudly",
        IndexException.class,
        () -> cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));

    session.begin();
    // Inside a transaction the duplicate name does not throw: the create is routed into the tx-local
    // view and collision detection is deferred to commit (a later track). It records the owning class
    // as changed and leaves the shared registry untouched.
    var deferred =
        indexManager.createIndex(
            session,
            indexName,
            SchemaClass.INDEX_TYPE.NOTUNIQUE.name(),
            new PropertyIndexDefinition("DupIdx", "name", PropertyTypeInternal.STRING),
            cls.getPolymorphicCollectionIds(),
            null,
            null);
    assertNotNull(
        "a duplicate-name create inside a transaction must defer (return a handle), not throw",
        deferred);
    var state = session.getTxSchemaState();
    assertNotNull("the duplicate-name in-transaction create must have seeded the tx-local state",
        state);
    assertTrue("the duplicate-name create must record the owning class as changed",
        state.getChangedClasses().contains("DupIdx"));
    session.rollback();

    // The shared registry still holds exactly the original committed index; the deferred duplicate
    // was never registered.
    assertTrue("the original committed index must remain registered after rollback",
        indexManager.existsIndex(indexName));
  }

  /**
   * Dropping an index by a name that does not exist while a transaction is open does not throw and
   * records no changed class: the de-guarded {@code dropIndex} seeds the tx-local state but, finding
   * no matching index, marks nothing changed and returns. This pins the silent-no-op contract (the
   * divergence from the legacy path's behaviour for an unknown name) so a later track that makes an
   * unknown-name drop loud is a deliberate change, and documents that the seed/engage still happens
   * for the no-op write.
   */
  @Test
  public void dropUnknownIndexInsideTransactionDoesNotThrowAndRecordsNoChangedClass() {
    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    // Must not throw for an unknown index name.
    indexManager.dropIndex(session, "DoesNotExist.nope");

    var state = session.getTxSchemaState();
    // The drop still seeds the tx-local state (and engages the mutex) even though it is a no-op for
    // the unknown name — the seed is the side effect of routing any de-guarded write.
    assertNotNull("an in-transaction dropIndex seeds the tx-local state even for an unknown name",
        state);
    assertTrue("an unknown-index drop must record no changed class",
        state.getChangedClasses().isEmpty());
    session.rollback();
  }
}
