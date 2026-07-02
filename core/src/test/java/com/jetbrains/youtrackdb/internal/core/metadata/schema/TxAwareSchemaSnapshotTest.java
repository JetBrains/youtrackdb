package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Coverage for the tx-aware immutable schema snapshot. During a schema- or index-changing
 * transaction the snapshot ({@code SchemaProxy.makeSnapshot()}) is built from the transaction's
 * private tx-local {@code SchemaShared} copy, so {@code EntityImpl.validate()} and serialization
 * see the transaction's own uncommitted classes, property types, and constraint rules instead of
 * silently skipping them. Outside such a transaction the snapshot stays on the shared committed
 * cache, byte-for-byte the pre-existing fast path.
 *
 * <p>The tests also pin the commit-path behavior for a class created inside the transaction: its
 * collection id is provisional ({@code <= -2}) until commit, so the entity's record collection is
 * deferred (the record id carries the invalid-collection sentinel), a same-transaction query skips
 * the provisional collection instead of failing, id&rarr;name resolution answers {@code null}
 * without fabricating a name, and the commit resolves the real collection and rebuilds the pinned
 * snapshot before the working set is gathered.
 */
public class TxAwareSchemaSnapshotTest extends DbTestBase {

  /**
   * A strict-mode constraint set on a class created inside the same transaction must be enforced
   * on that transaction's own entities. Before the snapshot was tx-aware, the tx-created class
   * resolved to null in the committed-only snapshot and every validation check was silently
   * skipped. The violating entity stays in a rolled-back transaction; committing it would fail at
   * the commit-time re-validation, which is not what this test exercises.
   */
  @Test
  public void strictModeOnTxCreatedClassIsEnforcedInsideTheSameTransaction() {
    session.begin();
    try {
      var schema = session.getMetadata().getSchema();
      var cls = schema.createClass("TxStrictCreated");
      cls.setStrictMode(true);

      var entity = (EntityImpl) session.newEntity("TxStrictCreated");
      entity.setProperty("undeclared", "value");

      var thrown = assertThrows(ValidationException.class, entity::validate);
      assertTrue("the failure must be the strict-mode check, got: " + thrown.getMessage(),
          thrown.getMessage().contains("STRICT"));
    } finally {
      session.rollback();
    }
  }

  /**
   * A constraint added inside a transaction to an existing committed class's existing property
   * must be enforced on the same transaction's entities of that class, and must vanish with the
   * rollback. This is the read-your-writes half for the schema contract: the structural write
   * routes to the tx-local copy, and validation reads it back through the tx-aware snapshot.
   */
  @Test
  public void regexpAddedToACommittedClassPropertyIsEnforcedInsideTheSameTransaction() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxRegexTarget");
    cls.createProperty("code", PropertyType.STRING);

    session.begin();
    try {
      schema.getClass("TxRegexTarget").getProperty("code").setRegexp("[a-z]+");

      var entity = (EntityImpl) session.newEntity("TxRegexTarget");
      entity.setProperty("code", "123");
      assertThrows(ValidationException.class, entity::validate);
    } finally {
      session.rollback();
    }

    assertNull("the rolled-back constraint must not survive into the committed schema",
        schema.getClass("TxRegexTarget").getProperty("code").getRegexp());
  }

  /**
   * A constraint added after an entity already resolved (and cached) its immutable class in the
   * same transaction must still be enforced on the next validation of that same entity. The
   * entity's cached class is keyed by the snapshot version; the mid-transaction schema write
   * force-rebuilds the snapshot and the tx-local schema mutation advances the version, so the
   * entity re-resolves instead of serving the pre-constraint view. A failure here means the
   * force-rebuild fired but the version-keyed cache never noticed.
   */
  @Test
  public void constraintAddedAfterAnEntityResolvedItsClassIsEnforcedOnTheNextValidation() {
    session.begin();
    try {
      var schema = session.getMetadata().getSchema();
      schema.createClass("TxReResolved");

      var entity = (EntityImpl) session.newEntity("TxReResolved");
      entity.setProperty("undeclared", "value");
      // The first validation resolves and caches the (not yet strict) immutable class on the
      // entity, and passes: the class carries no constraints yet.
      entity.validate();

      schema.getClass("TxReResolved").setStrictMode(true);

      assertThrows(ValidationException.class, entity::validate);
    } finally {
      session.rollback();
    }
  }

  /**
   * A transaction that creates a class and inserts an entity of it must commit successfully.
   * During the transaction the class's collection is provisional, so the record's collection
   * assignment is deferred (the record id carries the invalid-collection sentinel). At commit the
   * reconciliation creates the real collection, resolves the provisional id, and rebuilds the
   * pinned snapshot before the working set is gathered, so the working-set read resolves the
   * reconciled real collection id and the record lands in it.
   */
  @Test
  public void commitSucceedsWhenTheTransactionCreatesAClassAndInsertsAnEntityOfIt() {
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxCommittedClass");
    var entity = (EntityImpl) session.newEntity("TxCommittedClass");
    entity.setProperty("name", "first");
    assertEquals("during the transaction the record's collection must be deferred",
        RID.COLLECTION_ID_INVALID, entity.getIdentity().getCollectionId());
    session.commit();

    var committedClass = session.getSharedContext().getSchema().getClass("TxCommittedClass");
    assertNotNull("the committed schema must carry the promoted class", committedClass);
    for (var collectionId : committedClass.getCollectionIds()) {
      assertTrue("a committed class must carry only real collection ids, got " + collectionId,
          collectionId >= 0);
    }
    assertTrue("after commit the record id must carry the reconciled real collection, got "
        + entity.getIdentity(),
        entity.getIdentity().getCollectionId() >= 0);

    try (var rs = session.query("select from TxCommittedClass")) {
      assertEquals("the committed row must be visible to a committed-state query", 1L,
          rs.stream().count());
    }
  }

  /**
   * A query against a tx-created class inside the creating transaction must complete without a
   * collection-not-found or engine-not-found error: the scan setup skips the provisional
   * collection id instead of trying to resolve a collection that does not exist yet. The
   * transaction's own row is not returned, because its record id carries no collection until
   * commit, so the merged scan has nothing to merge it into; surfacing those rows is a separate
   * record-id mechanism.
   */
  @Test
  public void sameTransactionQueryOfATxCreatedClassCompletesWithoutError() {
    session.begin();
    try {
      var schema = session.getMetadata().getSchema();
      schema.createClass("TxQueriedClass");
      var entity = (EntityImpl) session.newEntity("TxQueriedClass");
      entity.setProperty("name", "pending");

      try (var rs = session.query("select from TxQueriedClass")) {
        assertEquals("the accepted semantic is no-error with zero rows until commit", 0L,
            rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * The tx-aware snapshot exposes a tx-created class to non-planner readers, so the two named
   * resolution paths must tolerate its provisional collection id: id&rarr;name resolution answers
   * {@code null} (never a fabricated name, never an error), and serializing the transaction's own
   * entity of that class completes. A provisional id ({@code <= -2}) must never leak out as if it
   * were a real collection.
   */
  @Test
  public void serializationAndCollectionNameResolutionLeakNoProvisionalCollectionId() {
    session.begin();
    try {
      var schema = session.getMetadata().getSchema();
      schema.createClass("TxSerializedClass");
      var entity = (EntityImpl) session.newEntity("TxSerializedClass");
      entity.setProperty("name", "payload");

      var snapshotClass = session.getMetadata().getImmutableSchemaSnapshot()
          .getClass("TxSerializedClass");
      assertNotNull("the tx-aware snapshot must expose the tx-created class", snapshotClass);

      var sawProvisional = false;
      for (var collectionId : snapshotClass.getCollectionIds()) {
        if (SchemaShared.isProvisionalCollectionId(collectionId)) {
          sawProvisional = true;
          assertNull("id->name resolution must answer null for a provisional collection",
              session.getCollectionNameById(collectionId));
        }
      }
      assertTrue("a tx-created class must carry a provisional collection id during the tx",
          sawProvisional);

      var bytes = session.getSerializer().toStream(session, entity);
      assertNotNull("serializing the transaction's own entity must complete", bytes);
      assertTrue(bytes.length > 0);
    } finally {
      session.rollback();
    }
  }

  /**
   * Outside a schema/index transaction the snapshot read is a strict no-op change: it must keep
   * resolving to the shared committed cache, both with no transaction at all and inside a
   * pure-data transaction that never seeds tx-local schema state. This pins the fast path the
   * tx-aware branch is gated behind.
   */
  @Test
  public void snapshotOutsideASchemaTransactionStaysOnTheSharedCommittedCache() {
    var schemaProxy = session.getMetadata().getSchema();
    var first = schemaProxy.makeSnapshot();
    assertSame("outside a transaction the snapshot must come from the shared cache", first,
        schemaProxy.makeSnapshot());

    session.begin();
    try {
      assertNull("a pure-data transaction must not seed tx-local schema state",
          session.getTxSchemaState());
      assertSame("a pure-data transaction must keep the committed snapshot fast path", first,
          schemaProxy.makeSnapshot());
    } finally {
      session.rollback();
    }
  }

  /**
   * The in-place pinned-snapshot rebuild is only meaningful while a pin is held (the schema-carry
   * commit holds one across the whole commit); calling it with no pin held is a misuse and must
   * fail loudly instead of silently doing nothing. With a pin held it must complete and leave a
   * usable snapshot behind.
   */
  @Test
  public void rebuildingTheThreadLocalSnapshotRequiresAHeldPin() {
    var metadata = session.getMetadata();
    assertThrows(IllegalStateException.class, metadata::rebuildThreadLocalSchemaSnapshot);

    metadata.makeThreadLocalSchemaSnapshot();
    try {
      metadata.rebuildThreadLocalSchemaSnapshot();
      // Outside a schema transaction both the pin and the rebuild resolve the shared cached
      // snapshot, so the rebuilt pin is the same usable instance.
      assertNotNull(metadata.getImmutableSchemaSnapshot());
    } finally {
      metadata.clearThreadLocalSchemaSnapshot();
    }
  }

  /**
   * The commit re-validates every created entity through the pinned tx-aware snapshot, so a
   * strict-mode constraint added to a committed class inside the transaction rejects a violating
   * entity at commit time — the constraint is enforced even when the caller never validated
   * explicitly. The failed commit must leave the committed schema unchanged (the strict-mode flag
   * rolls back with the transaction).
   */
  @Test
  public void strictModeAddedToACommittedClassInTxIsEnforcedAtCommitTime() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxStrictExisting");

    session.begin();
    schema.getClass("TxStrictExisting").setStrictMode(true);
    var entity = (EntityImpl) session.newEntity("TxStrictExisting");
    entity.setProperty("undeclared", "value");

    var thrown = assertThrows(RuntimeException.class, session::commit);
    assertTrue("the commit failure must carry the validation error, got: " + thrown,
        chainContainsValidation(thrown));

    if (session.getTransactionInternal().isActive()) {
      session.rollback();
    }
    assertFalse("the failed commit must leave the committed schema unchanged",
        schema.getClass("TxStrictExisting").isStrictMode());
  }

  /**
   * An entity that resolved (and cached) its immutable class outside any transaction must
   * re-resolve inside a later schema transaction and observe a constraint that transaction added,
   * even when the transaction's schema version walks onto the very number the entity cached from
   * the committed version space. The cache is keyed by a single version int compared with
   * {@code !=} across both spaces; a tx-local version counter restarting near zero could collide
   * with the cached committed number and silently serve the stale pre-constraint class. Version
   * values come from a process-wide monotonic generator, so the two spaces are disjoint and the
   * padding loop below exits immediately; under the per-instance-counter regression the loop
   * walks the tx-local version exactly onto the cached committed number and the final assertion
   * catches the stale class.
   */
  @Test
  public void entityClassCachedOutsideTheTxReResolvesDespiteAVersionNumberReplay() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxVersionDisjoint");
    // Extra committed schema activity, so the committed version number sits comfortably above
    // the low values a freshly re-parsed tx-local copy's counter would restart from.
    schema.createClass("TxVersionDisjointFillerA");
    schema.createClass("TxVersionDisjointFillerB");

    session.begin();
    var entity = (EntityImpl) session.newEntity("TxVersionDisjoint");
    session.commit();

    // Resolve and cache the entity's immutable class with no transaction active: the cached
    // version number comes from the committed version space. Read the committed version from
    // the same snapshot generation the resolution consults.
    var committedVersion = session.getMetadata().getImmutableSchemaSnapshot().getVersion();
    var cachedOutsideTx = entity.getImmutableSchemaClass(session);
    assertNotNull("the committed class must resolve outside a transaction", cachedOutsideTx);
    assertFalse("strict mode must not be set before the transaction",
        cachedOutsideTx.isStrictMode());

    session.begin();
    try {
      schema.getClass("TxVersionDisjoint").setStrictMode(true);
      // Walk the tx snapshot version toward the entity's cached committed number. With disjoint
      // version spaces the tx version is already beyond it and the loop never iterates; with a
      // per-instance counter (the regression) each pad advances the tx-local version by one
      // until it lands exactly on the cached number.
      var pad = 0;
      while (session.getMetadata().getImmutableSchemaSnapshot().getVersion() < committedVersion
          && pad <= committedVersion) {
        schema.getClass("TxVersionDisjointFillerA").setDescription("pad-" + pad++);
      }
      var reResolved = entity.getImmutableSchemaClass(session);
      assertNotNull("the entity must resolve its class through the tx-aware snapshot",
          reResolved);
      assertTrue("the entity must re-resolve its cached class and see the same-tx strict-mode"
          + " constraint instead of serving the stale pre-transaction class",
          reResolved.isStrictMode());
    } finally {
      session.rollback();
    }
  }

  /**
   * The tx-aware snapshot is session-private: a genuinely concurrent reader on another thread
   * must never observe a class created in a foreign open transaction, nor any provisional
   * collection id, in its own snapshot. The single-threaded sibling tests prove the memoized tx
   * snapshot never lands in the process-shared cache, but sessions are thread-bound, so only a
   * second thread with its own session can catch a regression that publishes the tx-local
   * snapshot (or its provisional collection ids) into shared state — a leak that would corrupt
   * an unrelated session's collection resolution.
   */
  @Test
  public void concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId()
      throws Exception {
    var txSnapshotBuilt = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    var sawTxClass = new AtomicBoolean(false);
    var sawProvisionalId = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();

    // The reader runs on its own thread with its own session (sessions are thread-bound). It
    // waits until the writer has built and memoized its session-private tx snapshot, then takes
    // its own snapshot: the tx-created class and every provisional collection id must be absent.
    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        if (!txSnapshotBuilt.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the writer never signalled its tx snapshot");
        }
        var snap = other.getMetadata().getImmutableSchemaSnapshot();
        if (snap.getClass("TxConcurrentNewClass") != null) {
          sawTxClass.set(true);
        }
        for (var cls : snap.getClasses()) {
          for (var collectionId : cls.getCollectionIds()) {
            if (SchemaShared.isProvisionalCollectionId(collectionId)) {
              sawProvisionalId.set(true);
            }
          }
        }
      } catch (Throwable t) {
        readerError.set(t);
      } finally {
        readerDone.countDown();
      }
    }, "tx-created-class-concurrent-reader");
    reader.start();

    // openDatabase() on the reader thread activates that session there; reassert the main
    // session on this thread before driving the transaction.
    session.activateOnCurrentThread();
    session.begin();
    try {
      session.getMetadata().getSchema().createClass("TxConcurrentNewClass");
      // Build (and memoize) the creating session's tx-aware snapshot before the reader looks,
      // so the reader races an actually-built session-private snapshot rather than nothing.
      assertNotNull("the creating session must see its own tx-created class",
          session.getMetadata().getImmutableSchemaSnapshot().getClass("TxConcurrentNewClass"));
      txSnapshotBuilt.countDown();
      assertTrue("the concurrent reader must finish", readerDone.await(10, TimeUnit.SECONDS));
    } finally {
      txSnapshotBuilt.countDown();
      session.rollback();
    }
    reader.join(TimeUnit.SECONDS.toMillis(10));

    assertNull("the concurrent reader must not fail", readerError.get());
    assertFalse("a concurrent session must never see a foreign tx-created class",
        sawTxClass.get());
    assertFalse("a provisional collection id must never reach a concurrent session's snapshot",
        sawProvisionalId.get());
  }

  /**
   * Walks the cause chain looking for the validation failure. The commit path may rethrow the
   * validation error as-is or wrapped, so the assertion accepts either shape without pinning the
   * wrapper type.
   */
  private static boolean chainContainsValidation(Throwable thrown) {
    for (var cause = thrown; cause != null;
        cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (cause instanceof ValidationException) {
        return true;
      }
    }
    return false;
  }
}
