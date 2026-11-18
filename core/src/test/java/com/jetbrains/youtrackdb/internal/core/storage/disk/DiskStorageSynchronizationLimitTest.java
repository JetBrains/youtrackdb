package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category(SequentialTest.class)
public class DiskStorageSynchronizationLimitTest {

  @Rule
  public TemporaryFolder directory = new TemporaryFolder();

  /** An existing disk storage rejects zero at physical open and names the setting. */
  @Test
  public void diskOpenRejectsZero() {
    assertRejectedOpen(0);
  }

  /** An existing disk storage rejects a negative value at physical open and names the setting. */
  @Test
  public void diskOpenRejectsNegative() {
    assertRejectedOpen(-7);
  }

  /** A storage context override reaches the cache instead of falling back to the global default. */
  @Test
  public void diskOpenUsesStorageContextLimit() throws Exception {
    createStorage();
    var config = new BaseConfiguration();
    var setting = GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_SYNCHRONIZATION_LIMIT;
    config.setProperty(setting.getKey(), 7);
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toString(), config);
        var session = manager.open("limit", "admin", "admin")) {
      var storage =
          (DiskStorage) ((com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) session)
              .getStorage();
      var retention = field(WOWCache.class, "pageRetentionState").get(storage.getWriteCache());
      assertEquals(7, field(retention.getClass(), "synchronizationLimit").get(retention));
    }
  }

  /** Rejected create acquires no WAL, cache, startup history, or periodic tasks. */
  @Test
  public void diskCreateRejectsInvalidLimitBeforeAcquiringResources() throws Exception {
    assertRejectedStartup(0, true);
  }

  /** Failed-create cleanup preserves the parent and unrelated storage-extension files in it. */
  @Test
  public void rejectedCreatePreservesParentFilesAndAllowsRetry() throws Exception {
    var parent = directory.getRoot().toPath();
    var unrelated = parent.resolve("unrelated.pcl");
    var content = new byte[] {1, 3, 5, 7};
    Files.write(unrelated, content);

    assertRejectedStartup(0, true);

    assertTrue("The databases parent must survive rejection", Files.isDirectory(parent));
    assertTrue("Cleanup must not delete files in the parent", Files.exists(unrelated));
    assertArrayEquals(content, Files.readAllBytes(unrelated));
    createStorage();
    try (var manager = (YouTrackDBImpl) YourTracks.instance(parent.toString());
        var session = manager.open("limit", "admin", "admin")) {
      assertFalse(session.isClosed());
    }
  }

  /**
   * A rejected restore leaves an empty target with no birth record or IV. A valid restore of the
   * same backup into the same name then succeeds without a drop or a destructive restart.
   */
  @Test
  public void rejectedRestoreLeavesNoBirthRecordAndAllowsRetry() throws Exception {
    var backup = directory.newFolder("backup").toPath();
    createStorage();
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toString());
        var session = manager.open("limit", "admin", "admin")) {
      var internal = (YouTrackDBInternalEmbedded) manager.internal;
      assertNotNull(internal.getStorage("limit").fullBackup(backup));
    }

    var config = new BaseConfiguration();
    var setting = GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_SYNCHRONIZATION_LIMIT;
    config.setProperty(setting.getKey(), 0);
    try (var manager =
        (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toString(), config)) {
      var internal = (YouTrackDBInternalEmbedded) manager.internal;
      var failure = assertThrows(IllegalArgumentException.class,
          () -> internal.restore("restored", backup.toString(), null, null));
      assertTrue(failure.getMessage().contains(setting.getKey()));
    }
    var target = directory.getRoot().toPath().resolve("restored");
    assertTrue("The rejected restore must create its target folder", Files.isDirectory(target));
    try (var entries = Files.list(target)) {
      assertEquals("No birth record, IV, or other storage file may be written", 0, entries.count());
    }
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toString())) {
      var internal = (YouTrackDBInternalEmbedded) manager.internal;
      internal.restore("restored", backup.toString(), null, null);
      try (var session = manager.open("restored", "admin", "admin")) {
        assertFalse(session.isClosed());
      }
    }
  }

  private void assertRejectedOpen(int limit) {
    try {
      assertRejectedStartup(limit, false);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private void assertRejectedStartup(int limit, boolean create) throws Exception {
    if (!create) {
      createStorage();
    }
    var history = directory.getRoot().toPath().resolve("limit")
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    var historyBefore = create ? null : Files.readAllBytes(history);
    var modifiedBefore = create ? null : Files.getLastModifiedTime(history);
    var observed = new AtomicReference<DiskStorage>();
    DiskStorage.setOpenIndicationActionForTesting(
        directory.getRoot().toPath().resolve("limit"), observed::set);
    var setting = GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_SYNCHRONIZATION_LIMIT;
    var config = new BaseConfiguration();
    config.setProperty(setting.getKey(), limit);
    try (var manager =
        (YouTrackDBImpl) YourTracks.instance(directory.getRoot().toString(), config)) {
      var failure = assertThrows(RuntimeException.class,
          () -> {
            if (create) {
              manager.create("limit", DatabaseType.DISK, "admin", "admin", "admin");
            } else {
              manager.open("limit", "admin", "admin");
            }
          });
      Throwable cause = failure;
      while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
        cause = cause.getCause();
      }
      assertTrue(cause.toString(), cause instanceof IllegalArgumentException);
      assertTrue(cause.toString(), cause.getMessage().contains(setting.getKey()));
      var storage = observed.get();
      assertNotNull("The rejected physical storage must be observed", storage);
      assertNull(storage.getWALInstance());
      assertNull(storage.getWriteCache());
      if (!create) {
        assertArrayEquals("Rejected open must preserve changed-page history",
            historyBefore, Files.readAllBytes(history));
        assertEquals("Rejected open must not rewrite changed-page history",
            modifiedBefore, Files.getLastModifiedTime(history));
        assertFalse("Rejected open must not publish temporary tracker state",
            Files.exists(history.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
      }
      assertNull(field(DiskStorage.class, "fuzzyCheckpointTask").get(storage));
      assertNull(field(DiskStorage.class, "recordsGcTask").get(storage));
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  private void createStorage() {
    try (var manager = YourTracks.instance(directory.getRoot().toString())) {
      manager.create("limit", DatabaseType.DISK, "admin", "admin", "admin");
    }
  }

  private static Field field(Class<?> type, String name) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
