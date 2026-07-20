package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

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
   * The index-membership fold on a class rejects an unresolvable collection id loudly.
   * {@code resolveCollectionNameById} is nullable by contract (an unknown committed id answers
   * null), and folding that null onward would persist a null placeholder into every class
   * index's covered set at commit — the silent-corruption family every other resolver consumer
   * already guards against. No production caller passes an unknown id today, so the guard is
   * exercised through the protected seam directly.
   */
  @Test
  public void addCollectionIdToIndexesRejectsUnresolvableCollectionId() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("UnresolvedFoldProbe");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("UnresolvedFoldProbe.val", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    var embedded = (SchemaClassEmbedded) session.getSharedContext().getSchema()
        .getClass("UnresolvedFoldProbe");
    var thrown = assertThrows(IllegalStateException.class,
        () -> embedded.addCollectionIdToIndexes(session, 999_999, false));
    assertTrue("the failure must name the unresolvable id",
        thrown.getMessage().contains("999999"));
    assertTrue("the failure must name the class",
        thrown.getMessage().contains("UnresolvedFoldProbe"));
  }

  /**
   * A commit-window primitive that regressed to re-taking the non-reentrant {@code stateLock}
   * would busy-spin forever rather than throw, hanging the whole surefire fork with no signal.
   * These tests drive schema-carrying commits (and cross-thread readers) through that substrate,
   * so a per-method stuck-thread timeout converts such a hang into a fast, diagnosable failure
   * naming the stuck thread, matching the sibling CommitTimeIndexBuildTest guard.
   */
  @Rule
  public Timeout globalTimeout =
      Timeout.builder()
          .withTimeout(120, TimeUnit.SECONDS)
          .withLookingForStuckThread(true)
          .build();

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

    // The commit builds the deferred index's engine and publishes it into the shared manager (the
    // commit-time engine build): the class is empty, so the build passes the v1 empty-source bound,
    // and after commit the index is a real, registered index. During the transaction it stayed
    // deferred (the assertions above); the registration is the commit-time transition.
    assertTrue("after commit the deferred index must be built and registered in the shared manager",
        indexManager.existsIndex(indexName));
  }

  /**
   * Creating an index inside a transaction whose name already exists in the shared registry is
   * rejected loudly, matching the legacy top-level contract. The earlier deferred shape (pinned by
   * this test's previous incarnation as an explicitly provisional contract) silently last-wins
   * overwrote the pending definition in the overlay's create category — the silent last-wins
   * gap — so the
   * in-tx branch now carries the same duplicate-name guard as the committed branch. Both halves
   * stay pinned: the legacy path throws, and the in-transaction path throws the same
   * IndexException while leaving the shared registry untouched, seeding no tx-local schema state
   * and engaging no metadata-write mutex (the guard fires before ensureTxSchemaState, so a
   * rejected duplicate cannot block other schema writers for the rest of the transaction — the
   * resolve-before-seed pattern). A same-tx drop-then-recreate of the name stays allowed — the
   * documented replace flow, covered by CommitTimeIndexBuildTest.
   */
  @Test
  public void duplicateIndexNameInsideTransactionIsRejectedLoudly() {
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
    // Inside a transaction the duplicate name now throws exactly like the top-level path; the
    // guard fires before the create routes anything into the tx-local view.
    assertThrows(
        "re-creating an existing index name inside a transaction must be rejected loudly",
        IndexException.class,
        () -> indexManager.createIndex(
            session,
            indexName,
            SchemaClass.INDEX_TYPE.NOTUNIQUE.name(),
            new PropertyIndexDefinition("DupIdx", "name", PropertyTypeInternal.STRING),
            cls.getPolymorphicCollectionIds(),
            null,
            null));
    // The guard runs before ensureTxSchemaState (reading the overlay through the nullable
    // probe), so the rejected duplicate seeds nothing: no tx-local schema state means the
    // single-permit metadata-write mutex was never engaged, and other schema writers stay
    // unblocked for the rest of this transaction.
    assertNull("a rejected duplicate create must not seed tx-local schema state (nor engage the"
        + " metadata-write mutex)", session.getTxSchemaState());
    session.rollback();

    // The shared registry still holds exactly the original committed index.
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

  /**
   * A class created inside a transaction is recorded in the tx-local changed-class set, so the
   * commit knows to write its new per-class record. The superclass is plain and NON-indexed, so the
   * created subclass appears in the changed set only because the create path itself records it, not
   * because an index-membership ripple recorded its owning class. Without the create-path recording
   * the commit would silently skip a tx-created class's per-class record.
   */
  @Test
  public void createClassInsideTransactionRecordsChangedClass() {
    var schema = session.getMetadata().getSchema();
    // A plain, non-indexed superclass committed at the top level. Creating a subclass of it inside
    // the transaction triggers no index-membership ripple, so the only way the subclass can land in
    // the changed-class set is the create path recording it.
    var superCls = schema.createClass("PlainCreateSuper");

    session.executeInTx(
        tx -> {
          var created = schema.createClass("PlainCreateSub", superCls);
          assertNotNull("the in-transaction subclass create must succeed", created);

          var state = session.getTxSchemaState();
          assertNotNull("an in-transaction create must have seeded the tx-local state", state);
          assertTrue(
              "the created class must be recorded in the tx-local changed-class set",
              state.getChangedClasses().contains("PlainCreateSub"));
        });
  }

  /**
   * A class renamed inside a transaction records the NEW name in the tx-local changed-class set and
   * does NOT record the old name. The changed-class set must stay accurate: a rename touches the
   * class under its new name, and the old name no longer designates a live class, so recording the
   * old name would describe a class that is not there. An accurate set keeps the selective-rewrite
   * filter correct, so the test pins that the old name is absent from the changed set.
   */
  @Test
  public void renameClassInsideTransactionRecordsNewNameOnly() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("RenameBefore");

    session.executeInTx(
        tx -> {
          var cls = schema.getClass("RenameBefore");
          assertNotNull("the class must exist before the in-transaction rename", cls);
          // Public single-argument setName goes through the routing seam (SchemaClassProxy), which
          // resolves the write target into the tx-local copy. The two-argument internal variant
          // would bypass the proxy.
          cls.setName("RenameAfter");

          var state = session.getTxSchemaState();
          assertNotNull("an in-transaction rename must have seeded the tx-local state", state);
          assertTrue(
              "the rename must record the new name in the tx-local changed-class set",
              state.getChangedClasses().contains("RenameAfter"));
          assertFalse(
              "the rename must NOT record the old name (the old name no longer designates a live "
                  + "class, so keeping it would make the changed-class set inaccurate)",
              state.getChangedClasses().contains("RenameBefore"));
        });
  }

  /**
   * Truncating a class inside a transaction is a data operation that changes no schema, so it must
   * not route onto the schema-carry commit path. The proxy routes truncate through the read
   * resolver, so it neither seeds a tx-local schema state nor records the class in the changed-class
   * set; the record deletions truncate performs are what make the transaction a write transaction.
   * Recording the class here would falsely force the heavier schema write-lock commit branch for a
   * pure-data truncate and rewrite the class's unchanged per-class record.
   */
  @Test
  public void truncateInsideTransactionRecordsNoSchemaChange() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TruncateOnly");
    assertNotNull("the class to truncate must be created at the top level first", cls);

    session.executeInTx(
        tx -> {
          // truncate() is a SchemaClassInternal method, not on the public SchemaClass interface.
          var clsInternal =
              (SchemaClassInternal) session.getMetadata().getSchema().getClass("TruncateOnly");
          clsInternal.truncate();

          var state = session.getTxSchemaState();
          // A truncate-only transaction performs no schema write, so the read-routed truncate must
          // not seed the tx-local schema state. If a future change makes truncate seed the state,
          // this asserts it still records no changed class so the commit stays off the schema-carry
          // branch.
          if (state != null) {
            assertFalse(
                "a truncate-only transaction must not record the class as a schema change",
                state.getChangedClasses().contains("TruncateOnly"));
          }
        });
  }

  /**
   * A class created inside a transaction carries a provisional collection id, not a real one. The
   * eager, self-committing collection allocation is replaced by a provisional placeholder drawn from
   * the {@code <= -2} sub-range, so during the transaction the class's collection id is provisional.
   * Pinning the id range proves the create no longer allocates a real (non-negative) collection id
   * before commit — the precondition for the rollback guarantee the next test checks.
   *
   * <p>This pins only the tx-local-view half of the no-provisional-id-on-disk guarantee. The
   * durable-bytes half (commit then reload yields a real id, with no {@code <= -2} provisional id
   * surviving on disk) is verified once the commit-time reconciliation core lands, because nothing
   * resolves the provisional id to a real collection yet — a commit-then-reload assertion written
   * here would either fail (no resolution wired) or pass vacuously (the create never materializes a
   * real collection).
   */
  @Test
  public void inTransactionCreateCarriesProvisionalCollectionId() {
    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          var created = schema.createClass("ProvisionalCreate");
          assertNotNull("the in-transaction create must succeed", created);

          var collectionIds = created.getCollectionIds();
          assertTrue("a plain class must own at least one collection", collectionIds.length > 0);
          for (var collectionId : collectionIds) {
            assertTrue(
                "a tx-created class must carry a provisional collection id (<= -2), got "
                    + collectionId,
                SchemaShared.isProvisionalCollectionId(collectionId));
            assertNotEquals(
                "a provisional id must be disjoint from the abstract-class marker (-1)",
                SchemaShared.ABSTRACT_COLLECTION_ID, collectionId);
          }
        });
  }

  /**
   * The stray-collection-on-rollback defect: a class created inside a transaction that then rolls
   * back must leave NO collection on disk. Before the provisional-id inversion the create eagerly
   * self-committed a real storage collection, which survived the user transaction's rollback as an
   * orphan. With the provisional allocation the create touches no storage collection during the
   * transaction, so the storage collection set is byte-for-byte identical before and after the
   * rolled-back transaction, and the committed schema never sees the class. Collection names are
   * counter-only ({@code c_<counter>}, no class-name component), so the before/after set equality
   * is the whole no-stray-collection guarantee: any leaked collection would appear as a new name
   * in the set.
   *
   * <p>This covers the explicit-{@code rollback()} half of the no-stray-collection guarantee. The
   * crash-before-commit half (close without flush, then WAL replay) is verified once the
   * crash-restore integration harness exercises a partially-reconciled commit; on the provisional
   * create path a crash before commit is now trivially a no-op (no file was ever created), so the
   * load-bearing crash test belongs where the commit-time reconciliation can leave files.
   */
  @Test
  public void rolledBackInTransactionCreateLeavesNoCollectionOnDisk() {
    var committed = session.getSharedContext().getSchema();
    var collectionsBefore = new HashSet<>(session.getCollectionNames());

    session.begin();
    session.getMetadata().getSchema().createClass("StrayCollectionProbe");
    assertFalse("an in-transaction create must not touch the committed shared schema",
        committed.existsClass("StrayCollectionProbe"));
    session.rollback();

    var collectionsAfter = new HashSet<>(session.getCollectionNames());
    assertEquals(
        "a rolled-back in-transaction create must leave the storage collection set unchanged"
            + " (no stray collection on disk)",
        collectionsBefore, collectionsAfter);
    assertFalse("after rollback the created class must be absent from the committed schema",
        committed.existsClass("StrayCollectionProbe"));
  }

  /**
   * The stray-collection-on-rollback defect on the abstract&rarr;concrete alter path: making an
   * abstract class concrete inside a transaction that then rolls back must leave NO collection on
   * disk. Before the provisional-id inversion was extended to this path, {@code setAbstract(false)}
   * inside a transaction eagerly self-committed a real storage collection (the same eager allocation
   * the create path inverted), which survived the user transaction's rollback as an orphan. With the
   * provisional allocation the alter touches no storage collection during the transaction: the
   * tx-local class carries a provisional id ({@code <= -2}) the commit would resolve, and on
   * rollback the storage collection set is byte-for-byte identical. The class also stays abstract in
   * the committed schema, since the transaction never committed.
   */
  @Test
  public void rolledBackInTransactionSetAbstractFalseLeavesNoCollectionOnDisk() {
    var committed = session.getSharedContext().getSchema();
    // Commit an abstract class first, so the in-tx alter exercises the make-non-abstract branch.
    session.getMetadata().getSchema().createAbstractClass("AbstractToConcreteProbe");
    assertTrue("the class must be abstract before the test",
        committed.getClass("AbstractToConcreteProbe").isAbstract());

    var collectionsBefore = new HashSet<>(session.getCollectionNames());

    session.begin();
    var inTx = session.getMetadata().getSchema().getClass("AbstractToConcreteProbe");
    inTx.setAbstract(false);
    // During the tx the class now owns a provisional collection id (<= -2), not an eagerly allocated
    // real one — proving setAbstract(false) no longer self-commits a real collection.
    for (var collectionId : inTx.getCollectionIds()) {
      assertTrue(
          "an in-transaction setAbstract(false) must carry a provisional collection id (<= -2), got "
              + collectionId,
          SchemaShared.isProvisionalCollectionId(collectionId));
    }
    var state = session.getTxSchemaState();
    assertNotNull("the in-transaction alter must have seeded the tx-local state", state);
    assertTrue("the altered class must be recorded in the tx-local changed-class set",
        state.getChangedClasses().contains("AbstractToConcreteProbe"));
    assertTrue("an in-transaction alter must not touch the committed shared schema",
        committed.getClass("AbstractToConcreteProbe").isAbstract());
    session.rollback();

    var collectionsAfter = new HashSet<>(session.getCollectionNames());
    assertEquals(
        "a rolled-back in-transaction setAbstract(false) must leave the storage collection set"
            + " unchanged (no stray collection on disk)",
        collectionsBefore, collectionsAfter);
    assertTrue("after rollback the class must remain abstract in the committed schema",
        committed.getClass("AbstractToConcreteProbe").isAbstract());
  }

  /**
   * The commit half of the abstract&rarr;concrete alter provisional-id path: making an abstract
   * class concrete inside a transaction and then COMMITTING must resolve the provisional collection
   * id the alter allocated to a real (non-negative) collection that exists in storage and survives a
   * durable reload. The rollback half is covered by
   * {@link #rolledBackInTransactionSetAbstractFalseLeavesNoCollectionOnDisk}; this is its commit
   * mirror. {@code setAbstract(false)} is the second provisional-id producer (the create path is the
   * first), and it mutates an already-committed class in place — re-pointing the class's collection
   * id list and recording the class changed. A regression in this alter-specific producer's commit
   * resolution (the patch list missing the alter-allocated id, the real collection created under the
   * wrong name, or the collection-id reassignment not re-pointed) would commit a structurally broken
   * concrete class that loses its collection at the next open. The test commits the alter and asserts
   * the class is concrete with only real collection ids that resolve in storage, then reloads and
   * reopens to confirm the resolution reached durable bytes with no {@code <= -2} provisional id
   * surviving.
   */
  @Test
  public void inTransactionSetAbstractFalseResolvesToRealCollectionAtCommit() {
    var schema = session.getMetadata().getSchema();
    var committed = session.getSharedContext().getSchema();
    // Commit an abstract class first, so the in-tx alter exercises the make-non-abstract branch that
    // allocates a provisional collection id.
    schema.createAbstractClass("AlterToConcrete");
    assertTrue("the class must be abstract before the alter",
        committed.getClass("AlterToConcrete").isAbstract());

    // Make the class concrete inside a transaction and commit: the provisional id allocated by
    // setAbstractInternal must resolve to a real collection at commit.
    session.executeInTx(tx -> schema.getClass("AlterToConcrete").setAbstract(false));

    var cls = committed.getClass("AlterToConcrete");
    assertFalse("the class must be concrete after the committed alter", cls.isAbstract());
    var idsBefore = cls.getCollectionIds();
    assertTrue("a concrete class must own at least one collection after the alter",
        idsBefore.length > 0);
    for (var collectionId : idsBefore) {
      assertTrue(
          "no provisional id may survive the alter commit; every collection id must be a real "
              + "(>= 0) id, was " + collectionId,
          collectionId >= 0);
      assertNotNull("the resolved real collection must exist in storage, id " + collectionId,
          session.getCollectionNameById(collectionId));
    }

    // The resolution reached durable bytes: after a reload re-parses the on-disk per-class record,
    // the class is still concrete with the same real collection ids. A regression that left a
    // provisional id on disk, or created the real collection under the wrong carried name, would fail
    // here because the reload would either resurrect the provisional id or fail to resolve the
    // collection.
    committed.reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var after = session.getSharedContext().getSchema().getClass("AlterToConcrete");
    assertNotNull("the altered class must survive a reload", after);
    assertFalse("the class must remain concrete after a durable reload", after.isAbstract());
    assertEquals("the alter's resolved real collection ids must survive the round trip unchanged",
        java.util.Arrays.toString(idsBefore),
        java.util.Arrays.toString(after.getCollectionIds()));
    for (var collectionId : after.getCollectionIds()) {
      assertTrue("no provisional id may survive a reload of the altered class, was " + collectionId,
          collectionId >= 0);
      assertNotNull("the alter's real collection must still exist after the reload",
          session.getCollectionNameById(collectionId));
    }
  }

  /**
   * An abstract class created inside a transaction still reads the single abstract marker
   * {@code -1}, not a provisional id. The provisional sub-range is {@code <= -2} precisely so it
   * cannot collide with the abstract marker; this test pins that the two id families stay disjoint
   * on the tx-local create path (an abstract create takes the {@code {-1}} branch, never the
   * provisional allocator).
   */
  @Test
  public void inTransactionAbstractCreateStillReadsMinusOne() {
    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          var created = schema.createAbstractClass("AbstractInTx");
          assertNotNull("the in-transaction abstract create must succeed", created);
          assertTrue("an abstract class must report isAbstract", created.isAbstract());

          var collectionIds = created.getCollectionIds();
          assertEquals("an abstract class owns exactly the single abstract marker",
              1, collectionIds.length);
          assertEquals("an abstract class's collection id is the abstract marker (-1)",
              SchemaShared.ABSTRACT_COLLECTION_ID, collectionIds[0]);
          assertFalse(
              "the abstract marker must not be classified as a provisional id",
              SchemaShared.isProvisionalCollectionId(collectionIds[0]));
        });
  }

  /**
   * Two classes created inside the same transaction receive distinct provisional collection ids.
   * The per-transaction provisional allocator decrements on each allocation, so no two tx-created
   * collections share a provisional id within a transaction (the uniqueness the in-memory reverse
   * map relies on when it treats provisional ids as pending-real). Reusing a provisional id across
   * classes would later collide both classes onto one real collection at commit.
   */
  @Test
  public void provisionalCollectionIdsAreUniqueWithinMultiClassTransaction() {
    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          var first = schema.createClass("MultiClassA");
          var second = schema.createClass("MultiClassB");

          var allProvisionalIds = new HashSet<Integer>();
          for (var collectionId : first.getCollectionIds()) {
            assertTrue("first class's ids must be provisional",
                SchemaShared.isProvisionalCollectionId(collectionId));
            assertTrue("a provisional id must be unique across the transaction's classes",
                allProvisionalIds.add(collectionId));
          }
          for (var collectionId : second.getCollectionIds()) {
            assertTrue("second class's ids must be provisional",
                SchemaShared.isProvisionalCollectionId(collectionId));
            assertTrue("a provisional id must be unique across the transaction's classes",
                allProvisionalIds.add(collectionId));
          }
          assertEquals(
              "the two classes' provisional id sets must be disjoint",
              first.getCollectionIds().length + second.getCollectionIds().length,
              allProvisionalIds.size());

          // The in-memory reverse map resolves each provisional id back to its owning tx-created
          // class during the transaction (the pending-real treatment): a missed in-memory map site
          // would leave the provisional id unmapped here.
          var txLocal = session.getTxSchemaState().getTxLocalSchema();
          for (var collectionId : first.getCollectionIds()) {
            assertEquals("the provisional id must resolve to its owning class in the reverse map",
                "MultiClassA", txLocal.getClassByCollectionId(collectionId).getName());
          }
        });
  }

  /**
   * The {@link TxSchemaState} provisional&rarr;real carrier round-trips: a recorded resolution reads
   * back the real id, an unrecorded provisional id reads the {@link TxSchemaState#NO_RESOLUTION}
   * not-resolved sentinel (deliberately distinct from the abstract-class marker {@code -1}), and the
   * allocator hands out a strictly decreasing, disjoint-from-{@code -1} sequence. This is the carrier
   * the commit-time reconciliation populates and the patch list reads to re-point every provisional
   * reference to its real id before any record serializes.
   *
   * <p>This test pins the tx-local-view half of the no-provisional-id-on-disk guarantee (a
   * provisional id is exposed only in the tx carrier) with a hand-fed real id; the durable-bytes
   * half (commit then reload yields a real id with no {@code <= -2} survivor on disk) is verified
   * once the commit-time reconciliation core that resolves provisional ids and re-points property
   * values lands.
   */
  @Test
  public void txSchemaStateProvisionalToRealCarrierRoundTrips() {
    session.executeInTx(
        tx -> {
          // Seed the tx-local state by routing one schema write through the proxy, then read the
          // state's carrier directly (the carrier is the substrate the commit consumes).
          session.getMetadata().getSchema().createClass("CarrierSeed");
          var state = session.getTxSchemaState();
          assertNotNull("a routed schema write must have seeded the tx-local state", state);

          var firstProvisional = state.allocateProvisionalCollectionId("carrierseed_1");
          var secondProvisional = state.allocateProvisionalCollectionId("carrierseed_2");
          assertTrue("an allocated provisional id must be in the <= -2 sub-range",
              SchemaShared.isProvisionalCollectionId(firstProvisional));
          assertTrue("an allocated provisional id must be in the <= -2 sub-range",
              SchemaShared.isProvisionalCollectionId(secondProvisional));
          assertTrue("successive provisional ids must strictly decrease (stay unique)",
              secondProvisional < firstProvisional);
          assertNotEquals("a provisional id must never equal the abstract marker",
              SchemaShared.ABSTRACT_COLLECTION_ID, firstProvisional);
          assertEquals("the allocated provisional id must carry the name it was created with",
              "carrierseed_1", state.getProvisionalCollectionName(firstProvisional));
          assertEquals("the second allocated provisional id must carry its own name",
              "carrierseed_2", state.getProvisionalCollectionName(secondProvisional));
          // The CarrierSeed createClass above also allocated provisional ids (a vertex class gets
          // several collections), each carrying its name, so the map holds those plus the two
          // explicit allocations here — it must at least contain both explicit names.
          assertTrue("the provisional-name map must carry both explicitly allocated names",
              state.getProvisionalCollectionNames().containsValue("carrierseed_1")
                  && state.getProvisionalCollectionNames().containsValue("carrierseed_2"));

          assertEquals(
              "an unrecorded provisional id must read the not-resolved sentinel (NO_RESOLUTION)",
              TxSchemaState.NO_RESOLUTION, state.getResolvedCollectionId(firstProvisional));
          assertNotEquals(
              "the not-resolved sentinel must be distinct from the abstract-class marker (-1)",
              SchemaShared.ABSTRACT_COLLECTION_ID, TxSchemaState.NO_RESOLUTION);

          state.recordResolvedCollectionId(firstProvisional, 42);
          assertEquals("a recorded provisional id must read back its real id",
              42, state.getResolvedCollectionId(firstProvisional));
          assertEquals("an unrecorded provisional id stays at the not-resolved sentinel",
              TxSchemaState.NO_RESOLUTION,
              state.getResolvedCollectionId(secondProvisional));
          assertEquals("the live resolution map must carry exactly the one recorded mapping",
              1, state.getResolvedCollectionIds().size());
        });
  }

  /**
   * The provisional-id allocator fails loudly at the {@link Short#MIN_VALUE} floor the record id's
   * short-width collection-id serialization imposes, naming the real cause (too many collections
   * created in one transaction). Without the explicit floor check the exhaustion would only
   * surface later, at record insert, as a misleading record-id serialization error. The last id
   * before the floor must still allocate normally, so the check does not shrink the usable space.
   */
  @Test
  public void provisionalIdAllocatorFailsLoudlyAtTheRecordIdShortFloor() {
    session.begin();
    try {
      // Seed the tx-local state by routing one schema write through the proxy; the create itself
      // consumes a few provisional ids, so the drain below starts wherever the counter stands.
      session.getMetadata().getSchema().createClass("AllocatorFloorSeed");
      var state = session.getTxSchemaState();
      assertNotNull("a routed schema write must have seeded the tx-local state", state);

      // Drain the allocator down to the floor. Each allocation is a counter decrement plus a map
      // put, so the ~32K iterations stay cheap.
      var last = state.allocateProvisionalCollectionId("allocatorfloorseed_drain");
      while (last > Short.MIN_VALUE) {
        last = state.allocateProvisionalCollectionId("allocatorfloorseed_drain");
      }
      assertEquals("the floor id itself must still be allocatable", Short.MIN_VALUE, last);

      var thrown = assertThrows(DatabaseException.class,
          () -> state.allocateProvisionalCollectionId("allocatorfloorseed_over"));
      assertTrue("the exhaustion error must name the too-many-collections-in-one-transaction"
          + " cause, got: " + thrown.getMessage(),
          thrown.getMessage().contains("in one transaction"));
      // The allocator must stay exhausted (and keep failing loudly) instead of wrapping around.
      assertThrows(DatabaseException.class,
          () -> state.allocateProvisionalCollectionId("allocatorfloorseed_again"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The predicate split keeps the abstract-class marker {@code -1} skipped at every in-memory map
   * site. Creating an abstract class (committed path) and then dropping it drives the single
   * {@code -1} marker through the uniqueness check ({@code checkCollectionsAreAbsent}), the
   * reverse-map populate ({@code addCollectionClassMap}), and the reverse-map remove
   * ({@code removeCollectionClassMap}). Each must skip the abstract marker exactly as before the
   * split: the marker is never entered into the reverse map, and the create/drop succeed. This pins
   * that the {@code <= -2} vs {@code -1} split left the abstract half of the predicate behaviourally
   * unchanged (only the {@code <= -2} provisional half is newly treated as pending-real).
   */
  @Test
  public void abstractClassMarkerStaysSkippedAtInMemoryMapSites() {
    var schema = session.getMetadata().getSchema();
    var committed = session.getSharedContext().getSchema();

    // Abstract create: the class's collection id list is {-1}. checkCollectionsAreAbsent must skip
    // the marker (no SchemaException for a duplicate), and addCollectionClassMap must skip it (no
    // reverse-map entry for an abstract class).
    var cls = schema.createAbstractClass("AbstractMarkerProbe");
    assertTrue("the class must be abstract", cls.isAbstract());
    assertEquals("an abstract class owns only the abstract marker", 1,
        cls.getCollectionIds().length);
    assertEquals("an abstract class's only collection id is the abstract marker (-1)",
        SchemaShared.ABSTRACT_COLLECTION_ID, cls.getCollectionIds()[0]);
    assertEquals("the abstract marker must never be mapped to a class in the reverse map",
        null, committed.getClassByCollectionId(SchemaShared.ABSTRACT_COLLECTION_ID));

    // Drop: removeCollectionClassMap iterates the class's ids ({-1}) and must skip the abstract
    // marker (no spurious reverse-map removal). The drop succeeds and the class is gone.
    schema.dropClass("AbstractMarkerProbe");
    assertFalse("the dropped abstract class must be gone from the committed schema",
        committed.existsClass("AbstractMarkerProbe"));
    assertEquals("dropping an abstract class must leave the abstract marker unmapped",
        null, committed.getClassByCollectionId(SchemaShared.ABSTRACT_COLLECTION_ID));
  }

  // Reads a class's raw index set out of a freshly taken immutable snapshot. A fresh snapshot is
  // materialized on demand when the thread-local pin is clear, so calling this after a mid-tx index
  // change re-materializes the class's index list through the overlay-aware routing seam. Returns
  // the index names so the assertions read cleanly.
  private Set<String> snapshotIndexNamesFor(String className) {
    var snapshotClass =
        (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
            .getClassInternal(className);
    assertNotNull("the class must be present in the immutable snapshot", snapshotClass);
    var names = new HashSet<String>();
    for (var index : snapshotClass.getIndexesInternal()) {
      names.add(index.getName());
    }
    return names;
  }

  /**
   * An index created inside a transaction becomes visible in the owning class's raw index set through
   * the per-session routing seam, even though the shared index manager does not register it until
   * commit. The class is committed first (so the immutable snapshot, which reads committed schema,
   * carries it), then the index is created inside a transaction. A snapshot re-taken during the
   * transaction lists the new index on the class because the seam resolves the class's raw index set
   * against the tx-local overlay and the mid-tx create forced the snapshot to rebuild. This is the
   * routing that makes the query-side and same-transaction index tracking see the new index; without
   * the overlay resolution the class's index list would stay frozen at the pre-index committed set.
   */
  @Test
  public void txCreatedIndexIsVisibleInClassRawIndexSetThroughOverlay() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayVisibleClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayVisibleClass.name";

    session.executeInTx(
        tx -> {
          // Before the create the class carries no index.
          assertFalse(
              "the class must carry no index before the in-transaction create",
              snapshotIndexNamesFor("OverlayVisibleClass").contains(indexName));

          cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

          // The overlay routing seam surfaces the tx-created index into the class's raw index set,
          // even though the shared manager has not registered it.
          assertTrue(
              "the tx-created index must appear in the class's raw index set through the overlay",
              snapshotIndexNamesFor("OverlayVisibleClass").contains(indexName));
          assertFalse(
              "the shared index manager must not register the tx-created index during the tx",
              session.getSharedContext().getIndexManager().existsIndex(indexName));
        });
  }

  /**
   * The index the overlay surfaces for a tx-created index is the unbuilt deferred handle: its engine
   * is not built, so its index id is negative. This is the property the query planner keys its
   * skip-unbuilt-index guard on, so pinning it here proves the overlay hands the planner an index it
   * will correctly skip rather than a built one it would try to read (which would throw on the absent
   * engine).
   */
  @Test
  public void txCreatedIndexSurfacedThroughOverlayIsUnbuilt() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayUnbuiltClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayUnbuiltClass.name";

    session.executeInTx(
        tx -> {
          cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

          var snapshotClass =
              (SchemaClassInternal) session
                  .getMetadata()
                  .getImmutableSchemaSnapshot()
                  .getClassInternal("OverlayUnbuiltClass");
          Index surfaced = null;
          for (var index : snapshotClass.getIndexesInternal()) {
            if (indexName.equals(index.getName())) {
              surfaced = index;
            }
          }
          assertNotNull("the overlay must surface the tx-created index", surfaced);
          assertTrue(
              "a tx-created index has no built engine, so its index id must be negative, was "
                  + surfaced.getIndexId(),
              surfaced.getIndexId() < 0);
        });
  }

  /**
   * An index dropped inside a transaction is hidden from the owning class's raw index set through the
   * overlay, while the shared index manager keeps it until commit. The class and index are committed
   * first, then the index is dropped inside a transaction. A snapshot re-taken during the transaction
   * no longer lists the index on the class, but the shared manager still has it (the drop is
   * commit-deferred and rolls back for free). Without the overlay hiding the name the class's index
   * list would still show the index that the transaction intends to drop.
   */
  @Test
  public void txDroppedIndexIsHiddenFromClassRawIndexSetThroughOverlay() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayHiddenClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayHiddenClass.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue(
        "the committed index must be visible in the class's raw index set before the drop",
        snapshotIndexNamesFor("OverlayHiddenClass").contains(indexName));

    session.begin();
    indexManager.dropIndex(session, indexName);

    assertFalse(
        "the tx-dropped index must be hidden from the class's raw index set through the overlay",
        snapshotIndexNamesFor("OverlayHiddenClass").contains(indexName));
    assertTrue(
        "the shared index manager must still hold the index during the tx (drop is deferred)",
        indexManager.existsIndex(indexName));
    session.rollback();

    // After rollback the overlay is discarded and the committed index is visible again.
    assertTrue(
        "after rollback the class's raw index set must show the committed index again",
        snapshotIndexNamesFor("OverlayHiddenClass").contains(indexName));
  }

  /**
   * A query inside the transaction that created an index returns correct results through the full
   * class scan, not through the unbuilt index (which would throw on the absent engine). The class
   * holds committed rows; inside a transaction an index is created on it and a WHERE query on the
   * indexed property is run. The planner must skip the unbuilt index and fall through to the class
   * scan, which returns the correct row. This is the query-usability guarantee: a tx-created index
   * accelerates nothing until commit builds its engine, but the query still returns the right answer.
   */
  @Test
  public void queryInsideCreatingTransactionFallsThroughToScanNotUnbuiltIndex() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ScanFallbackClass");
    cls.createProperty("name", PropertyType.STRING);
    // Commit rows before the index exists, so the scan fallback has committed data to return.
    session.executeInTx(
        tx -> {
          session.newEntity("ScanFallbackClass").setProperty("name", "alice");
          session.newEntity("ScanFallbackClass").setProperty("name", "bob");
        });

    session.begin();
    // Create the index inside the same transaction; its engine is unbuilt until commit.
    cls.createIndex("ScanFallbackClass.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // The query must not throw on the engine-less index; it falls through to the class scan and
    // returns the matching committed row.
    long matches;
    try (var result = session.query("SELECT FROM ScanFallbackClass WHERE name = 'alice'")) {
      matches = result.stream().count();
    }
    assertEquals(
        "a query inside the creating transaction must return the correct row via the scan"
            + " fallback, not crash on the unbuilt index",
        1L, matches);
    session.rollback();
  }

  /**
   * The tx-local index overlay does not leak into a concurrent session's snapshot. The first session
   * creates an index inside an open transaction; a second session opened against the same database
   * takes its own snapshot and must not see the uncommitted index in the class's raw index set,
   * because the overlay is keyed to the first session's transaction and the seam resolves each
   * session's reads against that session's own overlay (the concurrent session has none).
   */
  @Test
  public void overlayDoesNotLeakToConcurrentSessionSnapshot() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayIsolatedClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayIsolatedClass.name";

    session.begin();
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    // The creating session sees the index through its own overlay.
    assertTrue(
        "the creating session must see its own tx-created index",
        snapshotIndexNamesFor("OverlayIsolatedClass").contains(indexName));

    DatabaseSessionEmbedded other = openDatabase();
    try {
      var otherClass =
          (SchemaClassInternal) other
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClassInternal("OverlayIsolatedClass");
      assertNotNull("the committed class must be visible to the concurrent session", otherClass);
      var otherNames = new HashSet<String>();
      for (var index : otherClass.getIndexesInternal()) {
        otherNames.add(index.getName());
      }
      assertFalse(
          "a concurrent session must not see the first session's uncommitted tx-created index",
          otherNames.contains(indexName));
    } finally {
      other.close();
    }
    session.rollback();
  }

  /**
   * A collection-membership ripple inside a transaction records the (index, collection) pair into the
   * overlay's membership-added category, so the commit can persist the {@code collectionsToIndex}
   * delta and the parent index then covers the new subclass collection. Creating an indexed
   * superclass at the top level and then a subclass inside a transaction ripples the subclass's
   * collection into the superclass index; this asserts the overlay carries that membership add
   * (beyond recording the owning class as changed, which the earlier de-guard tests already cover).
   * The tx-created subclass's collection is provisional during the transaction, so the ripple must
   * resolve it through the carried {@code <class>_<counter>} name the commit creates the real
   * collection under — never a null placeholder (the regression this test originally pinned).
   */
  @Test
  public void membershipRippleRecordsCollectionAddIntoOverlay() {
    var schema = session.getMetadata().getSchema();
    var superCls = schema.createClass("OverlayMemberSuper");
    superCls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayMemberSuper.name";
    superCls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.executeInTx(
        tx -> {
          var subCls = schema.createClass("OverlayMemberSub", superCls);
          assertNotNull("the in-transaction subclass create must succeed", subCls);

          var overlay = session.getTxSchemaState().getIndexOverlay();
          assertNotNull(
              "an in-transaction membership ripple must have created the index overlay", overlay);
          var added = overlay.getMembershipAdded().get(indexName);
          assertNotNull(
              "the membership ripple must record a collection-add against the superclass index",
              added);
          // Exact-set assertion over the recording semantics: the tx-local inheritance
          // rebuild re-records the parent's own committed collection names (idempotent
          // re-adds at commit), and the tx-created subclass's provisional collection ids
          // resolve to the carried <class>_<counter> names the commit creates the real
          // collections under — mid-tx id->name resolution answers null for a provisional
          // id, so the ripple reads the carried name off TxSchemaState instead. A null
          // placeholder here would persist into the committed index's collectionsToIndex at
          // commit (the regression this exact-set check pins), and a wrong-collection or
          // extra-entry regression fails it where a bare non-empty check stayed green.
          assertFalse(
              "the recorded membership-add must never carry a null placeholder for a"
                  + " provisional subclass collection",
              added.contains(null));
          var expectedNames = new HashSet<String>();
          for (var parentCollectionId : superCls.getCollectionIds()) {
            expectedNames.add(session.getCollectionNameById(parentCollectionId));
          }
          var txState = session.getTxSchemaState();
          for (var subCollectionId : subCls.getCollectionIds()) {
            assertTrue(
                "a tx-created subclass must carry provisional collection ids during the tx",
                SchemaShared.isProvisionalCollectionId(subCollectionId));
            expectedNames.add(txState.getProvisionalCollectionName(subCollectionId));
          }
          assertEquals(
              "the recorded membership-add must carry exactly the parent's committed names"
                  + " plus the provisional subclass's carried collection names",
              expectedNames, added);
        });
  }

  /**
   * The membership ripple's add and remove sides resolve a provisional subclass collection id to
   * the SAME carried {@code <class>_<counter>} name, so a same-tx create-then-drop of the subclass
   * cancels in the overlay: after the drop the membership-added category carries exactly the
   * parent's own committed names (the idempotent inheritance re-adds) and the membership-removed
   * category carries nothing. A remove side that resolved the provisional id to null instead (the
   * asymmetry regression this pins against) would leave the carried name dangling in the added set
   * — to be persisted at commit as a phantom membership — and record a spurious null removal.
   */
  @Test
  public void membershipRippleCancelsOnSameTxSubclassDrop() {
    var schema = session.getMetadata().getSchema();
    var superCls = schema.createClass("OverlayCancelSuper");
    superCls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayCancelSuper.name";
    superCls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.executeInTx(
        tx -> {
          schema.createClass("OverlayCancelSub", superCls);
          schema.dropClass("OverlayCancelSub");

          var overlay = session.getTxSchemaState().getIndexOverlay();
          assertNotNull("the membership ripple must have created the index overlay", overlay);
          // The parent's own committed names are re-recorded by the inheritance rebuild (an
          // idempotent no-op at commit); the subclass's carried names must be gone — cancelled by
          // the drop ripple resolving the same names.
          var expectedNames = new HashSet<String>();
          for (var parentCollectionId : superCls.getCollectionIds()) {
            expectedNames.add(session.getCollectionNameById(parentCollectionId));
          }
          assertEquals(
              "after the same-tx drop the added category must carry only the parent's own names",
              expectedNames, overlay.getMembershipAdded().get(indexName));
          assertNull("a cancelled add/remove pair must record no removal",
              overlay.getMembershipRemoved().get(indexName));
        });
  }

  /**
   * The SQL CREATE INDEX statement's existence precheck is overlay-aware: a repeated
   * same-tx {@code CREATE INDEX … IF NOT EXISTS} must be a silent no-op, because the tx-created
   * name reads as existing in the transaction's view. A committed-only precheck missed the
   * tx-created name and fell through to the manager's duplicate-name guard, turning the
   * documented IF NOT EXISTS no-op contract into an "already exists" failure.
   */
  @Test
  public void sameTxRepeatedCreateIndexIfNotExistsIsSilentNoOp() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IfNotExistsTx");
    cls.createProperty("name", PropertyType.STRING);

    session.begin();
    try {
      session.execute(
          "create index IfNotExistsTx.name IF NOT EXISTS on IfNotExistsTx (name) notunique")
          .close();
      // The repeat inside the same transaction must be a silent no-op, not a throw.
      try (var repeat = session.execute(
          "create index IfNotExistsTx.name IF NOT EXISTS on IfNotExistsTx (name) notunique")) {
        assertFalse("the repeated IF NOT EXISTS create must report a no-op", repeat.hasNext());
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * The statement-layer half of the drop-then-recreate replace flow: after an in-tx DROP
   * INDEX, a SQL {@code CREATE INDEX} of the same name must pass the statement's existence
   * precheck (the tx-dropped name reads as absent in the transaction's view) and reach the
   * manager's replace flow. A committed-only precheck still saw the registry entry (it survives
   * until the commit publishes the drop) and rejected the documented replace with "already
   * exists" — making the replace flow reachable only through the internal API.
   */
  @Test
  public void inTxDropIndexThenSqlCreateIndexReachesReplaceFlow() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("SqlReplaceTx");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "SqlReplaceTx.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    try {
      indexManager.dropIndex(session, indexName);
      session.execute("create index SqlReplaceTx.name on SqlReplaceTx (name) notunique").close();
      var overlay = session.getTxSchemaState().getIndexOverlay();
      assertNotNull("the replace must have recorded index deltas", overlay);
      assertTrue("the recreated name must be tx-created in the overlay (the replace flow)",
          overlay.isTxCreated(indexName));
      assertTrue("the old committed name must stay tx-dropped (the replace flow)",
          overlay.isTxDropped(indexName));
    } finally {
      session.rollback();
    }
  }

  /**
   * The committed-only involved-index lookups hide an index dropped inside the open transaction
   * (the stale-accelerator gap): a tx-dropped index keeps its live engine and committed-registry
   * entry until
   * commit, but ClassIndexManager stops maintaining it at drop time, so any consumer that still
   * resolves it through {@code getClassInvolvedIndexes}/{@code areIndexed} (the MATCH planner and
   * the out()/in() supernode shortcut) would read a stale engine and miss the transaction's own
   * post-drop writes. Mid-tx after DROP INDEX all three lookup shapes must hide the dropped index;
   * after rollback the committed view is restored. Tx-created indexes stay invisible in these
   * lookups per the adjudicated design (a tx-created index is not query-usable until commit) —
   * this fix is hide-only.
   */
  @Test
  public void involvedIndexLookupsHideTxDroppedIndex() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DropInvolved");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DropInvolved.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var indexManager = session.getSharedContext().getIndexManager();

    session.begin();
    try {
      indexManager.dropIndex(session, indexName);

      assertTrue("getClassInvolvedIndexes must hide the tx-dropped index mid-tx",
          indexManager.getClassInvolvedIndexes(session, "DropInvolved", "name").isEmpty());
      assertFalse("areIndexed must not report the tx-dropped index mid-tx",
          indexManager.areIndexed(session, "DropInvolved", "name"));
      // The snapshot-level path MATCH and out()/in() actually consult.
      var snapshotClass = session.getMetadata().getImmutableSchemaSnapshot()
          .getClassInternal("DropInvolved");
      assertTrue("the snapshot's involved-indexes lookup must hide the tx-dropped index mid-tx",
          snapshotClass.getInvolvedIndexesInternal(session, "name").isEmpty());
    } finally {
      session.rollback();
    }

    // The rollback restores the committed view: the lookups see the index again.
    assertFalse("after rollback the committed involved-index view must be restored",
        indexManager.getClassInvolvedIndexes(session, "DropInvolved", "name").isEmpty());
    assertTrue("after rollback areIndexed must report the committed index again",
        indexManager.areIndexed(session, "DropInvolved", "name"));
  }

  /**
   * A same-transaction duplicate CREATE INDEX must fail loudly (the silent last-wins gap):
   * the overlay's create category is a plain map put, so without a guard the second create
   * silently discarded the first definition — no error at execute or commit time. The guard
   * mirrors the committed branch's duplicate-name rejection.
   */
  @Test
  public void sameTxDuplicateCreateIndexFailsLoudly() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DupTxCreate");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DupTxCreate.name";

    session.begin();
    try {
      cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
      var thrown = assertThrows(IndexException.class,
          () -> cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
      assertTrue("the duplicate create must name the collision, got: " + thrown.getMessage(),
          thrown.getMessage().contains("already exists"));
    } finally {
      session.rollback();
    }
  }

  /**
   * An in-transaction CREATE INDEX colliding with a COMMITTED index name must fail loudly too,
   * mirroring the committed branch's guard — but a drop-then-recreate of the same name in one
   * transaction stays allowed (the documented replace flow, covered end-to-end by
   * CommitTimeIndexBuildTest); this test pins the non-dropped collision only.
   */
  @Test
  public void inTxCreateIndexOverCommittedNameFailsLoudly() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DupCommittedCreate");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "DupCommittedCreate.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    try {
      var thrown = assertThrows(IndexException.class,
          () -> cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
      assertTrue("the committed-name collision must name the collision, got: "
          + thrown.getMessage(),
          thrown.getMessage().contains("already exists"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The provisional-name resolver fails loudly (never records a null placeholder) when it meets a
   * provisional collection id outside the transaction that allocated it, and the polymorphic
   * ripple rethrows that invariant violation past its historical warn-and-skip catch instead of
   * silently dropping the collection id. The scenario is structurally prevented in real use (a
   * provisional id exists only inside its allocating transaction), so the guard is exercised
   * directly: a committed class is handed a provisional polymorphic id it never allocated and
   * rippled into another class with no transaction open.
   */
  @Test
  public void polymorphicRippleFailsLoudlyOnUnresolvableProvisionalId() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("RippleGuardParent");
    schema.createClass("RippleGuardBase");
    var committed = session.getSharedContext().getSchema();
    var parent = committed.getClass("RippleGuardParent");
    var base = committed.getClass("RippleGuardBase");
    // Inject an unallocated provisional id into the base's polymorphic ids (package-level access
    // to the protected field). No transaction is open, so the shared resolver must throw — and
    // addPolymorphicCollectionIds must rethrow — rather than degrade to a warn-and-skip that
    // records a null placeholder or silently drops the id.
    base.polymorphicCollectionIds = new int[] {-5};
    var thrown =
        assertThrows(IllegalStateException.class,
            () -> parent.addPolymorphicCollectionIds(session, base, true));
    assertTrue("the guard must name the unresolvable provisional id",
        thrown.getMessage().contains("Provisional collection id"));
  }

  /**
   * {@code TxSchemaState.getProvisionalCollectionName} throws unconditionally (not assert-only)
   * for a provisional id the transaction never allocated: returning null instead would silently
   * reintroduce the null-placeholder membership bug in a JVM running without {@code -ea}. The
   * guard is probed directly on a seeded tx-local state with an id from the provisional range
   * that was never handed out.
   */
  @Test
  public void unallocatedProvisionalIdNameLookupFailsLoudly() {
    session.executeInTx(
        tx -> {
          // Seed the tx-local schema state (and allocate the class's own provisional ids).
          session.getMetadata().getSchema().createClass("ProvNameGuard");
          var txState = session.getTxSchemaState();
          assertNotNull("the schema write must have seeded the tx-local state", txState);
          var thrown =
              assertThrows(IllegalStateException.class,
                  () -> txState.getProvisionalCollectionName(-9999));
          assertTrue("the guard must say no name was recorded",
              thrown.getMessage().contains("No provisional collection name recorded"));
        });
  }

  /**
   * A schema-only transaction that never touches an index allocates no index overlay. Creating a
   * class inside a transaction seeds the tx-local schema state but must leave the overlay null,
   * proving the overlay is created lazily only on the first index change and the common schema-only
   * path pays nothing for it.
   */
  @Test
  public void schemaOnlyTransactionAllocatesNoIndexOverlay() {
    var schema = session.getMetadata().getSchema();

    session.executeInTx(
        tx -> {
          schema.createClass("NoOverlayClass");

          var state = session.getTxSchemaState();
          assertNotNull("a schema write must have seeded the tx-local state", state);
          assertEquals(
              "a schema-only transaction must not allocate an index overlay",
              null, state.getIndexOverlay());
        });
  }

  /**
   * Creating an index and then dropping it by the same name inside one transaction nets to no index:
   * the overlay's create-then-drop resolves to nothing, so the class's raw index set carries neither
   * the created index (it was dropped) nor a spurious drop of a committed index (none existed). This
   * pins the create-then-drop cancellation in the overlay so a same-transaction create/drop pair does
   * not leave a dangling deferred handle the commit would try to build.
   */
  @Test
  public void createThenDropSameIndexInTransactionNetsToNoIndex() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("CreateDropNetClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "CreateDropNetClass.name";
    var indexManager = session.getSharedContext().getIndexManager();

    session.executeInTx(
        tx -> {
          cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
          assertTrue(
              "the tx-created index must be visible before the drop",
              snapshotIndexNamesFor("CreateDropNetClass").contains(indexName));

          indexManager.dropIndex(session, indexName);

          assertFalse(
              "a create-then-drop of the same index in one tx must leave the index absent",
              snapshotIndexNamesFor("CreateDropNetClass").contains(indexName));
          var overlay = session.getTxSchemaState().getIndexOverlay();
          assertNotNull("the index changes must have created the overlay", overlay);
          assertFalse(
              "the cancelled create must not remain a tx-created entry",
              overlay.isTxCreated(indexName));
          assertFalse(
              "a create-then-drop must not record the never-committed index as tx-dropped",
              overlay.isTxDropped(indexName));
        });
  }

  /**
   * The tx-local index overlay stays invisible to a genuinely concurrent reader on another thread.
   * The single-thread sibling test above proves the overlay never taints the process-shared snapshot
   * cache, but sessions are thread-bound, so it can never place two live sessions on two threads at
   * once. This test starts a second thread with its own session, holds the overlay open on the main
   * thread across a mid-transaction {@code createIndex}, and has the reader read the shared immutable
   * snapshot during that window. The reader must never observe the uncommitted index and must not
   * fail: the overlay is session-scoped (built into the owning session's private, uncached snapshot
   * and never written to the shared cache), so a concurrent reader on another thread races only the
   * committed shared snapshot, not the overlay. This catches a regression that (re)introduces a
   * shared-cache write on the overlay path or a visibility bug on the {@code volatile} snapshot
   * publish, neither of which the sequential test can surface.
   */
  @Test
  public void overlayIsInvisibleToAConcurrentReaderOnAnotherThread() throws Exception {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayRaceClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayRaceClass.name";

    var overlayBuilt = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    var leaked = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();

    // The reader runs on its own thread with its own session (sessions are thread-bound). It waits
    // until the main thread has recorded the overlay and taken its overlay-resolved snapshot, then
    // reads the shared committed snapshot; the uncommitted index must never appear in it.
    var reader =
        new Thread(
            () -> {
              try (var other = openDatabase()) {
                other.activateOnCurrentThread();
                overlayBuilt.await(5, TimeUnit.SECONDS);
                var otherClass =
                    (SchemaClassInternal) other
                        .getMetadata()
                        .getImmutableSchemaSnapshot()
                        .getClassInternal("OverlayRaceClass");
                if (otherClass != null) {
                  for (var idx : otherClass.getIndexesInternal()) {
                    if (indexName.equals(idx.getName())) {
                      leaked.set(true);
                    }
                  }
                }
              } catch (Throwable t) {
                readerError.set(t);
              } finally {
                readerDone.countDown();
              }
            },
            "overlay-concurrent-reader");
    reader.start();

    // openDatabase() on the reader thread activated the reader's session on that thread; the main
    // session is still bound to this thread, but reassert it explicitly before driving the overlay.
    session.activateOnCurrentThread();
    session.begin();
    try {
      cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
      // Signal only after the overlay has been recorded and the creating session's snapshot has been
      // force-rebuilt, so the reader observes the shared snapshot during the overlay window rather
      // than before or after it.
      assertTrue(
          "the creating session must see its own tx-created index through the overlay",
          snapshotIndexNamesFor("OverlayRaceClass").contains(indexName));
      overlayBuilt.countDown();
      assertTrue(
          "the concurrent reader must finish", readerDone.await(10, TimeUnit.SECONDS));
    } finally {
      overlayBuilt.countDown();
      session.rollback();
    }
    reader.join(TimeUnit.SECONDS.toMillis(10));

    assertNull("the concurrent reader must not fail", readerError.get());
    assertFalse(
        "the overlay must not leak into a concurrent reader's snapshot", leaked.get());
  }

  /**
   * A mid-transaction index change force-rebuilds the schema snapshot, and that rebuild is guarded
   * on a zero snapshot pin count: the force-clear throws {@code IllegalStateException} when a read
   * pin is still held, surfacing a misplaced call (an index DDL issued from inside a pinned
   * read-record operation). The supported paths never issue an index DDL under a held pin, so the
   * common case runs at pin count zero; this test documents both arms of the contract by holding a
   * pin explicitly and asserting the force-rebuild throws loudly rather than silently clearing a
   * pinned snapshot. Without a held pin the overlay tests above already cover the safe zero-count
   * path, so this test pins deliberately to exercise the throwing arm.
   */
  @Test
  public void forceRebuildUnderAHeldSnapshotPinThrowsLoudly() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PinnedRebuildClass");
    cls.createProperty("name", PropertyType.STRING);

    session.begin();
    var meta = session.getMetadata();
    // Pin the thread-local snapshot so immutableCount is 1; the force-rebuild the mid-tx createIndex
    // triggers must then hit the non-zero-pin guard and throw.
    meta.makeThreadLocalSchemaSnapshot();
    try {
      assertThrows(
          "an index DDL that force-rebuilds under a held snapshot pin must throw loudly",
          IllegalStateException.class,
          () -> cls.createIndex(
              "PinnedRebuildClass.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
    } finally {
      meta.clearThreadLocalSchemaSnapshot();
      session.rollback();
    }
  }
}
