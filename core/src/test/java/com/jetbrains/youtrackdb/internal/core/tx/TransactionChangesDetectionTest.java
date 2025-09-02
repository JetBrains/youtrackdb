package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionChangesDetectionTest {

  private YouTrackDBImpl factory;
  private DatabaseSessionInternal db;

  @Before
  public void before() {
    factory =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase(
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
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
    db.newEntity("test");
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());

    db.newEntity("test");
    assertEquals(2, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
    db.commit();
    assertEquals(FrontendTransaction.TXSTATUS.COMPLETED, currentTx.getStatus());
  }

  @Test
  public void testTransactionChangeTrackingRolledBack() {
    db.begin();
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
    db.newEntity("test");
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
    db.rollback();
    assertEquals(FrontendTransaction.TXSTATUS.ROLLED_BACK, currentTx.getStatus());
  }

  @Test
  public void testTransactionChangeTrackingAfterRollback() {
    db.begin();
    final var initialTx = (FrontendTransactionImpl) db.getTransactionInternal();
    db.newEntity("test");
    assertEquals(1, initialTx.getTxStartCounter());
    db.rollback();
    assertEquals(FrontendTransaction.TXSTATUS.ROLLED_BACK, initialTx.getStatus());
    assertEquals(0, initialTx.getEntryCount());

    db.begin();
    assertTrue(db.getTransactionInternal() instanceof FrontendTransactionImpl);
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
    assertEquals(1, currentTx.getTxStartCounter());
    db.newEntity("test");
    assertEquals(1, currentTx.getEntryCount());
    assertEquals(FrontendTransaction.TXSTATUS.BEGUN, currentTx.getStatus());
  }

  @Test
  public void testTransactionTxStartCounterCommits() {
    db.begin();
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
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

  @Test(expected = TransactionException.class)
  public void testTransactionRollbackCommit() {
    db.begin();
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
    assertEquals(1, currentTx.getTxStartCounter());
    db.begin();
    assertEquals(2, currentTx.getTxStartCounter());
    db.rollback();
    assertEquals(1, currentTx.getTxStartCounter());
    db.commit();
    fail("Should throw an 'TransactionException'.");
  }

  @Test
  public void testTransactionTwoStartedThreeCompleted() {
    db.begin();
    final var currentTx = (FrontendTransactionImpl) db.getTransactionInternal();
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
