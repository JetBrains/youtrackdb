package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Tests that {@link AbstractStorage#doShutdownOnDelete()} gracefully handles exceptions thrown
 * by {@code readCache.deleteStorage()} and {@code writeAheadLog.delete()}.
 *
 * <p>These error-handling catch blocks (YTDB-527) ensure that failures during cache or WAL
 * cleanup do not prevent the storage from reaching CLOSED status, which would otherwise leave
 * the YouTrackDB instance in a corrupted state.
 *
 * <p><b>Reflected field names:</b> This test accesses private/protected fields via reflection.
 * If any of the following fields are renamed, this test must be updated:
 * <ul>
 *   <li>{@code YouTrackDBInternalEmbedded.storages}
 *   <li>{@code YouTrackDBInternalEmbedded.currentStorageIds}
 *   <li>{@code YouTrackDBInternalEmbedded.sharedContexts}
 *   <li>{@code AbstractStorage.readCache}
 *   <li>{@code AbstractStorage.writeAheadLog}
 * </ul>
 */
public class StorageDeleteErrorHandlingTest {

  /**
   * Verifies that {@code storage.delete()} completes successfully even when both
   * {@code readCache.deleteStorage()} and {@code writeAheadLog.delete()} throw.
   *
   * <p>Uses reflection to inject a mock readCache and a spy on the WAL to simulate
   * infrastructure failures during the deletion cleanup path.
   */
  @Test
  public void testStorageDeleteHandlesCacheAndWalErrors() throws Exception {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {

      var dbName = "errorHandlingTest";
      ytdb.create(dbName, DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
              PredefinedLocalRole.ADMIN));

      // Open, create some data (populates internal structures), then close
      var session = ytdb.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
      session.executeInTx(Transaction::newEntity);
      session.close();

      // Access the internal storage via the public YouTrackDBImpl.internal field
      var internal = (YouTrackDBInternalEmbedded) ytdb.internal;
      var storagesField = YouTrackDBInternalEmbedded.class.getDeclaredField("storages");
      storagesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var storages = (Map<String, AbstractStorage>) storagesField.get(internal);
      var storage = storages.get(dbName);

      // Replace readCache with a mock that throws on deleteStorage()
      var readCacheField = AbstractStorage.class.getDeclaredField("readCache");
      readCacheField.setAccessible(true);
      var mockReadCache = mock(ReadCache.class);
      doThrow(new RuntimeException("Simulated cache deletion failure"))
          .when(mockReadCache).deleteStorage(any());
      readCacheField.set(storage, mockReadCache);

      // Spy on the writeAheadLog and make delete() throw
      var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
      walField.setAccessible(true);
      var originalWal = (MemoryWriteAheadLog) walField.get(storage);
      var spiedWal = spy(originalWal);
      doThrow(new IOException("Simulated WAL deletion failure"))
          .when(spiedWal).delete();
      walField.set(storage, spiedWal);

      // Call storage.delete() directly — the try-catch blocks in doShutdownOnDelete()
      // should catch both exceptions and allow the deletion to complete.
      storage.delete();

      // Clean up the YouTrackDB internal maps (normally done by drop())
      var storageId = storage.getId();
      storages.remove(dbName);
      var currentStorageIdsField =
          YouTrackDBInternalEmbedded.class.getDeclaredField("currentStorageIds");
      currentStorageIdsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var currentStorageIds = (Set<Integer>) currentStorageIdsField.get(internal);
      currentStorageIds.remove(storageId);
      var sharedContextsField =
          YouTrackDBInternalEmbedded.class.getDeclaredField("sharedContexts");
      sharedContextsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var sharedContexts = (Map<String, ?>) sharedContextsField.get(internal);
      sharedContexts.remove(dbName);

      assertFalse("Database should not exist after delete", ytdb.exists(dbName));
    }
  }
}
