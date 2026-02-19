package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;

final class WalTestUtils {

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  private WalTestUtils() {
  }

  /**
   * Prevents WAL segment truncation during the execution of the given action.
   * This is needed when copying storage files while the database is still open,
   * to avoid {@code FileNotFoundException} due to WAL segment rotation.
   */
  static void withWalProtection(
      DatabaseSessionEmbedded session, ThrowingRunnable action) throws Exception {
    var storage = (DiskStorage) session.getStorage();
    var wal = storage.getWALInstance();
    wal.flush();

    var walBegin = wal.begin();
    wal.addCutTillLimit(walBegin);
    try {
      action.run();
    } finally {
      wal.removeCutTillLimit(walBegin);
    }
  }
}
