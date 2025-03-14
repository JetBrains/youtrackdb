package com.jetbrains.youtrack.db.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TransactionChangesDetectionTest {

  private YouTrackDB factory;
  private DatabaseSessionInternal db;

  @Before
  public void before() {
    factory =
        CreateDatabaseUtil.createDatabase(
            TransactionChangesDetectionTest.class.getSimpleName(),
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    db =
        (DatabaseSessionInternal)
            factory.open(
                TransactionChangesDetectionTest.class.getSimpleName(),
                "admin",
                CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    db.createClass("test");
  }

  @After
  public void after() {
    db.close();
    factory.drop(TransactionChangesDetectionTest.class.getSimpleName());
    factory.close();
  }

  @Test
  public void testTransactionChangeTrackingCompleted() {
    db.begin();
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    db.newEntity("test");
    assertTrue(currentTx.isChanged());
    assertFalse(currentTx.isStartedOnServer());
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());

    currentTx.resetChangesTracking();
    db.newEntity("test");
    assertTrue(currentTx.isChanged());
    assertTrue(currentTx.isStartedOnServer());
    assertEquals(2, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
    db.commit();
    assertEquals(FrontendTransaction.TXSTATUS.COMPLETED, currentTx.getStatus());
  }

  @Test
  public void testTransactionChangeTrackingRolledBack() {
    db.begin();
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    db.newEntity("test");
    assertTrue(currentTx.isChanged());
    assertFalse(currentTx.isStartedOnServer());
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
    db.rollback();
    assertEquals(FrontendTransaction.TXSTATUS.ROLLED_BACK, currentTx.getStatus());
  }

  @Test
  public void testTransactionChangeTrackingAfterRollback() {
    db.begin();
    final var initialTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    db.newEntity("test");
    assertEquals(1, initialTx.getTxStartCounter());
    db.rollback();
    assertEquals(FrontendTransaction.TXSTATUS.ROLLED_BACK, initialTx.getStatus());
    assertEquals(0, initialTx.getEntryCount());

    db.begin();
    assertTrue(db.getTransactionInternal() instanceof FrontendTransactionOptimistic);
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    assertEquals(1, currentTx.getTxStartCounter());
    db.newEntity("test");
    assertTrue(currentTx.isChanged());
    assertFalse(currentTx.isStartedOnServer());
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
  }

  @Test
  public void testTransactionTxStartCounterCommits() {
    db.begin();
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    db.newEntity("test");
    assertEquals(1, currentTx.getTxStartCounter());
    assertEquals(1, currentTx.getEntryCount());

    db.begin();
    assertEquals(2, currentTx.getTxStartCounter());
    db.commit();
    assertEquals(1, currentTx.getTxStartCounter());
    db.newEntity("test");
    db.commit();
    assertEquals(0, currentTx.getTxStartCounter());
  }

  @Test(expected = RollbackException.class)
  public void testTransactionRollbackCommit() {
    db.begin();
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    assertEquals(1, currentTx.getTxStartCounter());
    db.begin();
    assertEquals(2, currentTx.getTxStartCounter());
    db.rollback();
    assertEquals(1, currentTx.getTxStartCounter());
    db.commit();
    fail("Should throw an 'RollbackException'.");
  }

  @Test
  public void testTransactionTwoStartedThreeCompleted() {
    db.begin();
    final var currentTx = (FrontendTransactionOptimistic) db.getTransactionInternal();
    assertEquals(1, currentTx.getTxStartCounter());
    db.begin();
    assertEquals(2, currentTx.getTxStartCounter());
    db.commit();
    assertEquals(1, currentTx.getTxStartCounter());
    db.commit();
    assertEquals(0, currentTx.getTxStartCounter());
    assertFalse(currentTx.isActive());
  }
}
