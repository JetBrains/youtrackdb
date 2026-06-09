package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the {@code mutationVersion} counter and the {@link RecordOperation#version} stamp added
 * for the tx-result cache. The counter must advance monotonically on every {@code
 * addRecordOperation} call, including the collapse path that folds repeated operations on one RID
 * without changing the operation count, and each operation must carry the latest version.
 */
public class TransactionMutationVersionTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(getClass().getSimpleName(), DatabaseType.MEMORY,
        getClass());
    db = youTrackDB.open(getClass().getSimpleName(), "admin", DbTestBase.ADMIN_PASSWORD);
    db.createClass("test");
  }

  @After
  public void after() {
    db.close();
    youTrackDB.drop(getClass().getSimpleName());
    youTrackDB.close();
  }

  /**
   * A fresh transaction starts at version 0, and each new-record operation advances the counter by
   * exactly one. Confirms the new-op branch of addRecordOperation increments and the getter
   * reflects the latest value.
   */
  @Test
  public void newRecordOperationsAdvanceVersionMonotonically() {
    db.begin();
    final var tx = (FrontendTransactionImpl) db.getTransactionInternal();
    assertEquals("Fresh transaction must start at version 0", 0L, tx.getMutationVersion());

    db.newEntity("test");
    assertEquals(1L, tx.getMutationVersion());

    db.newEntity("test");
    assertEquals(2L, tx.getMutationVersion());

    db.newEntity("test");
    assertEquals(3L, tx.getMutationVersion());
    db.rollback();
  }

  /**
   * The collapse path (a second operation on a RID already in the transaction) must still advance
   * the counter even though the operation count stays constant, and must re-stamp the existing
   * operation's version. This is the case that {@code recordOperations.size()} cannot detect and
   * the reason the cache uses a version counter rather than the operation count.
   */
  @Test
  public void collapseUpdateAdvancesVersionAndRestampsOperation() {
    db.begin();
    final var tx = (FrontendTransactionImpl) db.getTransactionInternal();

    var entity = db.newEntity("test");
    var record = (RecordAbstract) entity;
    var rid = record.getIdentity();
    var versionAfterCreate = tx.getMutationVersion();
    assertEquals(1L, versionAfterCreate);
    assertEquals(versionAfterCreate, tx.getRecordEntry(rid).version);

    // Re-register the same record so addRecordOperation collapses onto the existing op. CREATED
    // collapses with a later UPDATED back to CREATED, so the operation count is unchanged, but the
    // version must advance and the op must be re-stamped.
    tx.addRecordOperation(record, RecordOperation.UPDATED);

    var versionAfterUpdate = tx.getMutationVersion();
    assertTrue("Collapse-update must advance the version past the create version",
        versionAfterUpdate > versionAfterCreate);
    assertEquals("Only one operation should exist for the single RID after collapse",
        1, tx.getEntryCount());
    assertEquals("The collapsed operation must carry the latest version stamp",
        versionAfterUpdate, tx.getRecordEntry(rid).version);
    db.rollback();
  }

  /**
   * The cache-code re-entrancy depth counter must start at zero, count nested enters, and floor its
   * decrement at zero. The session brackets the whole cache lookup-and-view scope with
   * enter/exit so a query() issued from inside that scope (for example a user-defined function in a
   * WHERE clause) sees a positive depth and bypasses the cache; the floor guarantees an unbalanced
   * exit cannot drive the counter negative and wrongly re-enable the cache for a still-nested caller.
   */
  @Test
  public void cacheCodeDepthCountsNestedEntersAndFloorsAtZero() {
    db.begin();
    final var tx = (FrontendTransactionImpl) db.getTransactionInternal();
    assertEquals("A fresh transaction must start at cache-code depth 0", 0, tx.getCacheCodeDepth());

    tx.enterCacheCode();
    assertEquals("First enter must raise depth to 1", 1, tx.getCacheCodeDepth());

    // A re-entrant cache scope (e.g. a nested query() under WHERE evaluation) nests the depth.
    tx.enterCacheCode();
    assertEquals("Nested enter must raise depth to 2", 2, tx.getCacheCodeDepth());

    tx.exitCacheCode();
    assertEquals("Exit must lower depth back to 1", 1, tx.getCacheCodeDepth());

    tx.exitCacheCode();
    assertEquals("Balanced exit must return depth to 0", 0, tx.getCacheCodeDepth());

    // An unbalanced extra exit must clamp at zero rather than go negative.
    tx.exitCacheCode();
    assertEquals("An extra exit must clamp depth at 0, never negative", 0, tx.getCacheCodeDepth());
    db.rollback();
  }

  /**
   * Each distinct record's operation carries the version stamped at its own addRecordOperation
   * call, so later records have strictly higher version stamps than earlier ones.
   */
  @Test
  public void eachOperationCarriesItsOwnVersionStamp() {
    db.begin();
    final var tx = (FrontendTransactionImpl) db.getTransactionInternal();

    var first = db.newEntity("test");
    var firstRid = ((RecordAbstract) first).getIdentity();
    var firstVersion = tx.getRecordEntry(firstRid).version;

    var second = db.newEntity("test");
    var secondRid = ((RecordAbstract) second).getIdentity();
    var secondVersion = tx.getRecordEntry(secondRid).version;

    assertTrue("A later operation must carry a strictly higher version stamp",
        secondVersion > firstVersion);
    db.rollback();
  }
}
