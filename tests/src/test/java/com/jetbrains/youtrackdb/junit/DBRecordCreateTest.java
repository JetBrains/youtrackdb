package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DBRecordCreateTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.DBRecordCreateTest#testNewRecord
  @Test
  void testNewRecord() {
    final var entityId = session.computeInTx(tx -> {
      final var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      return element.getIdentity();
    });

    session.executeInTx(tx -> {
      assertTrue(tx.exists(entityId));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DBRecordCreateTest#testNewRecordRollbackTx
  @Test
  void testNewRecordRollbackTx() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.DBRecordCreateTest#testDeleteRecord
  @Test
  void testDeleteRecord() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      element.delete();
      assertFalse(tx.exists(element.getIdentity()));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DBRecordCreateTest#testLoadDeleteSameTx
  @Test
  void testLoadDeleteSameTx() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      var loaded = tx.load(element.getIdentity());
      assertTrue(tx.exists(loaded.getIdentity()));
      element.delete();
      assertFalse(tx.exists(loaded.getIdentity()));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DBRecordCreateTest#testLoadDeleteDifferentTx
  @Test
  void testLoadDeleteDifferentTx() {
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
