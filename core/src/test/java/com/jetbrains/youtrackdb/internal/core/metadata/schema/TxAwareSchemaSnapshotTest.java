package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.HashSet;
import java.util.List;
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
 * collection id is provisional ({@code <= -2}) until commit and the entity's record id carries
 * the provisional id, so a same-transaction query serves the transaction's own rows from the
 * transaction phase of the collection scan (no physical collection exists yet), id&rarr;name
 * resolution answers {@code null} without fabricating a name, and the commit resolves the real
 * collection, rebuilds the pinned snapshot, and rewrites every provisional record-collection id
 * to the reconciled real id before the working set is gathered.
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
   * During the transaction the class's collection is provisional and the record id carries the
   * provisional id. At commit the reconciliation creates the real collection, resolves the
   * provisional id, rebuilds the pinned snapshot, and rewrites the record's provisional
   * collection id to the reconciled real id before the working set is gathered, so the record
   * lands in the real collection and no provisional id reaches durable bytes.
   */
  @Test
  public void commitSucceedsWhenTheTransactionCreatesAClassAndInsertsAnEntityOfIt() {
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxCommittedClass");
    var entity = (EntityImpl) session.newEntity("TxCommittedClass");
    entity.setProperty("name", "first");
    assertTrue("during the transaction the record id must carry the provisional collection id,"
        + " got " + entity.getIdentity(),
        SchemaShared.isProvisionalCollectionId(entity.getIdentity().getCollectionId()));
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
   * A query against a tx-created class inside the creating transaction must return the
   * transaction's own rows: the record ids carry the class's provisional collection id, the
   * scan set includes that id, and the collection iterator serves the rows from its
   * transaction phase (no physical collection exists until commit, so the storage phase is
   * skipped). The WHERE and rid-descending variants pin the filter path and the backward
   * iterator branch over the same transaction-phase rows.
   */
  @Test
  public void sameTransactionQueryOfATxCreatedClassReturnsTheTransactionsOwnRows() {
    session.begin();
    try {
      var schema = session.getMetadata().getSchema();
      schema.createClass("TxQueriedClass");
      var first = (EntityImpl) session.newEntity("TxQueriedClass");
      first.setProperty("name", "pending-1");
      var second = (EntityImpl) session.newEntity("TxQueriedClass");
      second.setProperty("name", "pending-2");
      assertTrue("the record id must carry the provisional collection id, got "
          + first.getIdentity(),
          SchemaShared.isProvisionalCollectionId(first.getIdentity().getCollectionId()));

      try (var rs = session.query("select from TxQueriedClass")) {
        var names = rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList();
        assertEquals("both uncommitted rows must be served from the transaction phase",
            List.of("pending-1", "pending-2"), names);
      }

      try (var rs = session.query("select from TxQueriedClass where name = 'pending-2'")) {
        assertEquals("a WHERE filter must evaluate against the transaction-phase rows",
            1L, rs.stream().count());
      }

      try (var rs = session.query("select from TxQueriedClass order by @rid desc")) {
        assertEquals("a rid-ordered (backward-scan) query must serve the same rows", 2L,
            rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * Insert, update, and delete rows of a tx-created class in one transaction: the
   * same-transaction query must reflect exactly the surviving final state, and the commit must
   * land exactly those rows. The deleted row's record operation still carries the provisional
   * collection id at commit time, so the commit-time rewrite must map it to the reconciled real
   * collection (where the never-persisted delete is a no-op) instead of failing on it.
   */
  @Test
  public void sameTransactionQueryAndCommitReflectUpdatesAndDeletesOfTxCreatedClassRows() {
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxMutatedClass");
    var kept = (EntityImpl) session.newEntity("TxMutatedClass");
    kept.setProperty("name", "kept");
    var updated = (EntityImpl) session.newEntity("TxMutatedClass");
    updated.setProperty("name", "before-update");
    var deleted = (EntityImpl) session.newEntity("TxMutatedClass");
    deleted.setProperty("name", "doomed");

    updated.setProperty("name", "after-update");
    session.delete(deleted);

    try (var rs = session.query("select from TxMutatedClass")) {
      var names = rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList();
      assertEquals("the same-tx query must reflect the update and the delete",
          List.of("after-update", "kept"), names);
    }
    session.commit();

    try (var rs = session.query("select from TxMutatedClass")) {
      var names = rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList();
      assertEquals("the commit must land exactly the surviving final state",
          List.of("after-update", "kept"), names);
    }
  }

  /**
   * A plan built inside the creating transaction bakes the provisional collection id into its
   * scan set, so it must not be served from the shared statement cache after the commit
   * replaces the provisional id with the real collection: the post-commit query must re-plan
   * and return the committed row instead of scanning a collection that no longer exists.
   */
  @Test
  public void queryPlanBuiltInsideTheCreatingTransactionIsNotReusedAfterCommit() {
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxPlanCacheClass");
    var entity = (EntityImpl) session.newEntity("TxPlanCacheClass");
    entity.setProperty("name", "row");
    try (var rs = session.query("select from TxPlanCacheClass")) {
      assertEquals("the uncommitted row must be visible to the same-tx query", 1L,
          rs.stream().count());
    }
    session.commit();

    try (var rs = session.query("select from TxPlanCacheClass")) {
      assertEquals("the committed row must be visible after commit; a stale plan carrying the"
          + " provisional collection id would return zero rows", 1L, rs.stream().count());
    }
  }

  /**
   * A commit whose working set mixes a record of a pre-existing committed class (a real
   * collection id) with a record of a tx-created class (a provisional collection id) must land
   * both: the commit-time rewrite must re-point only the provisional record and leave the real
   * one untouched. This exercises the rewrite's selectivity end to end through a durable
   * commit — a regression that rewrote (or skipped) the wrong side would misplace one of the
   * records.
   */
  @Test
  public void commitMixesAPreExistingClassInsertWithATxCreatedClassInsert() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxMixedCommitted");

    session.begin();
    var existing = (EntityImpl) session.newEntity("TxMixedCommitted");
    existing.setProperty("name", "real");
    schema.createClass("TxMixedNew");
    var fresh = (EntityImpl) session.newEntity("TxMixedNew");
    fresh.setProperty("name", "provisional");
    var realIdDuringTx = existing.getIdentity().getCollectionId();
    assertTrue("the pre-existing class's record must carry a real collection id during the tx,"
        + " got " + existing.getIdentity(), realIdDuringTx >= 0);
    assertTrue("the tx-created class's record must carry a provisional collection id, got "
        + fresh.getIdentity(),
        SchemaShared.isProvisionalCollectionId(fresh.getIdentity().getCollectionId()));
    session.commit();

    assertEquals("the rewrite must not touch a record already bound to a real collection",
        realIdDuringTx, existing.getIdentity().getCollectionId());
    assertTrue("the tx-created class's record must land in a reconciled real collection, got "
        + fresh.getIdentity(), fresh.getIdentity().getCollectionId() >= 0);
    try (var rs = session.query("select from TxMixedCommitted")) {
      assertEquals("the pre-existing class's row must land", 1L, rs.stream().count());
    }
    try (var rs = session.query("select from TxMixedNew")) {
      assertEquals("the tx-created class's row must land", 1L, rs.stream().count());
    }
  }

  /**
   * The reconciled real collection id must reach durable bytes, not just the in-memory record id
   * the commit-time rewrite patched: create a class and insert an entity of it in one
   * transaction, commit, force the schema to re-parse from its persisted records, and reopen the
   * session so every session-level cache holding the rewritten record id is discarded. The
   * re-read record must carry a real ({@code >= 0}) collection id and its payload. A rewrite
   * that patched only cached state (or a serialization that persisted the provisional id in a
   * JVM running without assertions) would surface here as a provisional-id record or a lost row.
   */
  @Test
  public void committedTxCreatedClassRecordCarriesARealCollectionIdAfterReopen() {
    session.begin();
    session.getMetadata().getSchema().createClass("TxDurableClass");
    var entity = (EntityImpl) session.newEntity("TxDurableClass");
    entity.setProperty("name", "durable");
    assertTrue("during the transaction the record id must carry the provisional collection id,"
        + " got " + entity.getIdentity(),
        SchemaShared.isProvisionalCollectionId(entity.getIdentity().getCollectionId()));
    session.commit();

    // Force a re-parse of the persisted schema records, then reopen the session so the caches
    // that already hold the rewritten record id are discarded; the same reload-then-reopen
    // pattern the schema-reconciliation durability tests use, and it runs on every storage
    // profile.
    session.getSharedContext().getSchema().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    try (var rs = session.query("select from TxDurableClass where name = 'durable'")) {
      var rows = rs.stream().toList();
      assertEquals("the committed row must be readable after the reopen", 1, rows.size());
      var rid = rows.getFirst().getIdentity();
      assertTrue("the persisted record must carry a real collection id, got " + rid,
          rid.getCollectionId() >= 0);
    }
  }

  /**
   * Creating a class, inserting a row of it, and dropping the class in the same transaction
   * leaves a record operation whose provisional collection id has no resolution at commit: the
   * dropped class owns no collection in the tx-local schema, so reconciliation creates nothing
   * for it. The commit must fail loudly with an error naming the dropped-class cause — silently
   * discarding the insert would hide a data-losing commit, and letting the provisional id
   * continue would corrupt durable bytes — and the failed commit must leave the committed schema
   * and the storage's collection set unchanged.
   */
  @Test
  public void commitFailsLoudlyWhenATxCreatedClassWithRowsIsDroppedInTheSameTransaction() {
    var collectionsBefore = new HashSet<>(session.getCollectionNames());
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxCreateDropped");
    var entity = (EntityImpl) session.newEntity("TxCreateDropped");
    entity.setProperty("name", "orphan");
    assertTrue("the record id must carry the provisional collection id, got "
        + entity.getIdentity(),
        SchemaShared.isProvisionalCollectionId(entity.getIdentity().getCollectionId()));
    schema.dropClass("TxCreateDropped");

    var thrown = assertThrows(RuntimeException.class, session::commit);
    assertTrue("the failure must name the dropped-class cause, got: " + thrown,
        chainContainsMessage(thrown, "dropped or made abstract"));

    if (session.getTransactionInternal().isActive()) {
      session.rollback();
    }
    assertNull("the class must not exist in the committed schema after the failed commit",
        session.getSharedContext().getSchema().getClass("TxCreateDropped"));
    assertEquals("a failed commit must leave the storage's collection set unchanged",
        collectionsBefore, new HashSet<>(session.getCollectionNames()));
  }

  /**
   * Creating a class and dropping it again in the same transaction, with no rows written, must
   * commit cleanly and create no collection: the class's provisional collection ids have no owner
   * in the tx-local schema by commit time, so the reconciliation must skip them instead of
   * publishing an orphan collection no schema path could ever reach (the committed class set does
   * not contain the class, so nothing would reference the collection).
   */
  @Test
  public void createAndDropOfAClassInOneTransactionCommitsCleanlyWithoutACollection() {
    var collectionsBefore = new HashSet<>(session.getCollectionNames());
    session.begin();
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxCreateDropNoRows");
    schema.dropClass("TxCreateDropNoRows");
    session.commit();

    assertNull("the class created and dropped in one transaction must not survive the commit",
        session.getSharedContext().getSchema().getClass("TxCreateDropNoRows"));
    assertEquals("the commit must not create a collection for the dropped class",
        collectionsBefore, new HashSet<>(session.getCollectionNames()));
  }

  /**
   * A polymorphic scan plan cached from committed state must not hide a subclass created in a
   * later transaction: during that transaction the shared plan cache is bypassed, so the
   * parent query re-plans against the tx-aware snapshot and merges the committed parent rows
   * with the tx-created subclass's transaction-phase rows (its collection id is still
   * provisional, so the scan set mixes real and provisional collection ids).
   */
  @Test
  public void polymorphicQueryCachedBeforeTheTransactionSeesATxCreatedSubclassRows() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxPolyParent");
    session.begin();
    var parentRow = (EntityImpl) session.newEntity("TxPolyParent");
    parentRow.setProperty("name", "committed-parent-row");
    session.commit();

    // Prime the shared statement cache with the committed-state plan for the parent scan. The
    // explicit surrounding transaction keeps the session's transaction state clean: a bare
    // query would leave its implicit read-only transaction active and the begin() below would
    // nest into it instead of opening a writable one.
    session.begin();
    try (var rs = session.query("select from TxPolyParent")) {
      assertEquals(1L, rs.stream().count());
    }
    session.commit();

    session.begin();
    try {
      schema.createClass("TxPolyChild", schema.getClass("TxPolyParent"));
      var childRow = (EntityImpl) session.newEntity("TxPolyChild");
      childRow.setProperty("name", "tx-child-row");

      try (var rs = session.query("select from TxPolyParent")) {
        var names = rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList();
        assertEquals("the mid-tx parent scan must merge committed parent rows with the"
            + " tx-created subclass's rows",
            List.of("committed-parent-row", "tx-child-row"), names);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * A polymorphic plan built inside a schema transaction must never enter the shared
   * cross-session statement cache: its scan set carries the tx-created subclass's provisional
   * collection id, meaningful only inside the creating transaction, and a leaked entry would
   * survive the rollback (only a schema commit invalidates the cache). Two guards keep it out —
   * the step's {@code canBeCached() == false} and the cache's put-side tx-state bypass — and
   * both act by preventing the entry, so the discriminating assertion is the direct
   * cache-membership probe: a row-level probe cannot catch a regression, because a
   * provisional-id scan silently yields zero rows outside the owning transaction. The committed
   * control entry proves the cache is live for this statement shape, so the leak assertions
   * cannot pass vacuously with a disabled cache.
   */
  @Test
  public void polymorphicPlanBuiltInsideASchemaTxNeverEntersTheSharedCache() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxPolyLeakParent");
    session.begin();
    var parentRow = (EntityImpl) session.newEntity("TxPolyLeakParent");
    parentRow.setProperty("name", "committed");
    session.commit();

    // Control: a committed-state plan for the same class must enter the shared cache. The
    // explicit surrounding transaction keeps the session's transaction state clean for the
    // begin() below (a bare query would leave its implicit read-only transaction active).
    var committedStatement = "select from TxPolyLeakParent";
    session.begin();
    try (var rs = session.query(committedStatement)) {
      assertEquals(1L, rs.stream().count());
    }
    session.commit();
    var planCache = YqlExecutionPlanCache.instance(session);
    assertTrue("a committed-state plan must enter the shared cache (the live-cache control the"
        + " leak assertions below rely on)", planCache.contains(committedStatement));

    // Same statement semantics under a distinct cache key (the key is the verbatim statement
    // text), so the mid-tx plan cannot hide behind the committed control entry above.
    var txStatement = "SELECT from TxPolyLeakParent";
    session.begin();
    try {
      schema.createClass("TxPolyLeakChild", schema.getClass("TxPolyLeakParent"));
      var childRow = (EntityImpl) session.newEntity("TxPolyLeakChild");
      childRow.setProperty("name", "tx-child");
      // Plan and execute under the transaction; the planning-time cache put is where a
      // regressed guard would publish the tx-shaped plan to every other session.
      try (var rs = session.query(txStatement)) {
        assertEquals("the mid-tx polymorphic scan must merge parent and tx-child rows", 2L,
            rs.stream().count());
      }
      assertFalse("a plan whose scan set carries a provisional collection id must never enter"
          + " the shared statement cache", planCache.contains(txStatement));
    } finally {
      session.rollback();
    }

    // Post-rollback this session is a faithful stand-in for a foreign session — the shared-cache
    // read path branches only on the session's own transaction state. Nothing tx-shaped may have
    // survived, and a clean-context execution of the same text must see only the committed row.
    assertFalse("no tx-shaped plan may survive the rollback in the shared cache",
        planCache.contains(txStatement));
    try (var rs = session.query(txStatement)) {
      assertEquals("a clean-context execution must see only the committed row",
          List.of("committed"),
          rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList());
    }
  }

  /**
   * After the creating transaction rolls back, its class is gone: the same statement text must
   * fail with class-not-found instead of silently executing a cached plan that was built
   * against the transaction's provisional scan set (such a plan must never enter the shared
   * statement cache).
   */
  @Test
  public void rolledBackTxCreatedClassLeavesNoReusablePlanBehind() {
    session.begin();
    try {
      session.getMetadata().getSchema().createClass("TxRolledBackQueried");
      var entity = (EntityImpl) session.newEntity("TxRolledBackQueried");
      entity.setProperty("name", "gone");
      try (var rs = session.query("select from TxRolledBackQueried")) {
        assertEquals(1L, rs.stream().count());
      }
    } finally {
      session.rollback();
    }

    var thrown = assertThrows(RuntimeException.class,
        () -> session.query("select from TxRolledBackQueried").close());
    assertTrue("the rolled-back class must not resolve, got: " + thrown.getMessage(),
        thrown.getMessage() != null && thrown.getMessage().contains("TxRolledBackQueried"));
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

  /**
   * Walks the cause chain looking for a message containing the given fragment. The commit path
   * may rethrow the storage failure as-is or wrapped, so the assertion accepts either shape
   * without pinning the wrapper type.
   */
  private static boolean chainContainsMessage(Throwable thrown, String fragment) {
    for (var cause = thrown; cause != null;
        cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}
