package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Smoke test to verify the JUnit 5 dual-runner setup works correctly.
 * Tests basic database open/close operations using the shared YouTrackDB instance.
 */
public class SmokeTest extends BaseJUnit5Test {

  @Override
  protected DatabaseSessionEmbedded createSessionInstance(
      YouTrackDBImpl youTrackDB, String dbName, String user, String password) {
    return (DatabaseSessionEmbedded) youTrackDB.open(dbName, user, password);
  }

  @Test
  @Order(1)
  void databaseInstanceIsAvailable() {
    var ytdb = getYouTrackDB();
    assertNotNull(ytdb);
    assertTrue(ytdb.isOpen());
  }

  @Test
  @Order(2)
  void canOpenAndCloseSession() {
    assertNotNull(session);
    assertFalse(session.isClosed());
  }
}
