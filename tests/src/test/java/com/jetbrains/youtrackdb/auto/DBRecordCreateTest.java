package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

@Test
public class DBRecordCreateTest extends BaseDBTest {
  @Test
  public void testNewRecord() {
    final var entityId = session.computeInTx(tx -> {
      final var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      return element.getIdentity();
    });

    session.executeInTx(tx -> {
      assertTrue(tx.exists(entityId));
    });
  }

  @Test
  public void testNewRecordRollbackTx() {
    final var entityId = session.computeInTx(tx -> {
      final var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      tx.rollback();
      return element.getIdentity();
    });

    session.executeInTx(tx -> {
      assertFalse(tx.exists(entityId));
    });
  }

  @Test
  public void testDeleteRecord() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      element.delete();
      assertFalse(tx.exists(element.getIdentity()));
    });
  }

  @Test
  public void testLoadDeleteSameTx() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      var loaded = tx.load(element.getIdentity());
      assertTrue(tx.exists(loaded.getIdentity()));
      element.delete();
      assertFalse(tx.exists(loaded.getIdentity()));
    });
  }

  @Test
  public void testLoadDeleteDifferentTx() {
    final var entityId =
        session.computeInTx(tx -> tx.newEntity().getIdentity());

    session.executeInTx(tx -> {
      var loaded = tx.load(entityId);
      assertTrue(tx.exists(loaded.getIdentity()));
      loaded.delete();
      assertFalse(tx.exists(loaded.getIdentity()));
    });

    session.executeInTx(tx -> assertFalse(tx.exists(entityId)));
  }
}
