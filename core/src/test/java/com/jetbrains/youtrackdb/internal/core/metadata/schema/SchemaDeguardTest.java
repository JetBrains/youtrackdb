package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
}
