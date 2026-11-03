package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.StorageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real disk reopen tests. Session close alone does not run storage open. */
public class RegisteredOrphanOwnerPassTest {
  private Path root;
  private YouTrackDBImpl db;
  private static final String NAME = "owners";

  @Before
  public void before() {
    root = DbTestBase.getBaseDirectoryPath(getClass());
    db = (YouTrackDBImpl) YourTracks.instance(root);
    db.create(NAME, DatabaseType.DISK, "admin", "adminpwd", "admin");
  }

  @After
  public void after() throws Exception {
    if (db != null) {
      db.close();
    }
    org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
  }

  private DatabaseSessionEmbedded open() {
    return (DatabaseSessionEmbedded) db.open(NAME, "admin", "adminpwd");
  }

  private void reopen() {
    db.close();
    db = (YouTrackDBImpl) YourTracks.instance(root);
  }

  /** Every supported orphan family moves. Foreign and out-of-range names remain registered. */
  @Test
  public void reopenMovesOnlyBoundedUnownedLogicalComponentsAndAllowsCreation() throws Exception {
    final var orphanNames = List.of("ghost.pcl", "ghost.cpm", "ghost.fsm", "ghost.dpb",
        "global_collection_2147483647.grb", "ie_9999.cbt", "ie_9999.nbt",
        "ie_9999$null.cbt", "ie_9999$null.nbt", "ie_9999.ixs");
    final var unknown = List.of("foreign.bin", "ie_2147483648.cbt", "ie_0.cbt",
        "ie_999999999999999999999.cbt", "ie_1_22.cbt", "config_22.cd",
        "global_collection_2147483648.grb");
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      for (final var name : orphanNames) {
        storage.getWriteCache().addFile(name);
      }
      for (final var name : unknown) {
        storage.getWriteCache().addFile(name);
      }
      session.addCollection("kept");
    }
    reopen();
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      for (final var name : orphanNames) {
        assertFalse(name, storage.getWriteCache().files().containsKey(name));
      }
      for (final var name : unknown) {
        assertTrue(name, storage.getWriteCache().files().containsKey(name));
      }
      assertTrue(storage.getWriteCache().files().containsKey("kept.pcl"));
      session.addCollection("ghost");
      assertTrue(storage.getWriteCache().files().containsKey("ghost.pcl"));
    }
    try (var paths = Files.walk(root.resolve(NAME).resolve("orphans"))) {
      assertEquals(orphanNames.size(), paths.filter(Files::isRegularFile).count());
    }
  }

  /** A quarantined id can be reused in the same cache and retired by the next owner pass. */
  @Test
  public void reusedQuarantinedIdIsNotSkippedAsAmbiguous() throws Exception {
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      final var cache = (WOWCache) storage.getWriteCache();
      final var id = cache.addFile("ghost.pcl");
      storage.stateLock.writeLock().lock();
      try {
        storage.quarantineRegisteredOrphans();
        assertFalse(cache.files().containsKey("ghost.pcl"));
        assertEquals(id, cache.addFile("other.pcl", id));
        assertFalse(cache.ambiguousFileIds().contains(id));
        storage.quarantineRegisteredOrphans();
        assertFalse(cache.files().containsKey("other.pcl"));
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
    try (var paths = Files.walk(root.resolve(NAME).resolve("orphans"))) {
      assertEquals(2, paths.filter(Files::isRegularFile).count());
    }
  }

  /** A missing configured primary stops the entire pass, including unrelated orphan files. */
  @Test
  public void missingConfiguredMainStopsAllMoves() throws Exception {
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      session.addCollection("kept");
      final var cache = (WOWCache) storage.getWriteCache();
      cache.addFile("ghost.pcl");
      final var primary = root.resolve(NAME)
          .resolve(cache.nativeFileNameById(cache.files().get("kept.pcl")));
      final var hidden = primary.resolveSibling("hidden-primary");
      storage.stateLock.writeLock().lock();
      try {
        Files.move(primary, hidden, StandardCopyOption.ATOMIC_MOVE);
        try {
          storage.quarantineRegisteredOrphans();
          assertTrue(cache.files().containsKey("ghost.pcl"));
          assertFalse(Files.exists(root.resolve(NAME).resolve("orphans")));
        } finally {
          Files.move(hidden, primary, StandardCopyOption.ATOMIC_MOVE);
        }
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  /** Filtering uses configured ids, even when orphan registration is retained by the safety stop. */
  @Test
  public void linkBagLoadIgnoresUnconfiguredTreeWithoutReadingItsEmptyBytes() throws Exception {
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      final var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      final var field = LinkCollectionsBTreeManagerShared.class.getDeclaredField("fileIdBTreeMap");
      field.setAccessible(true);
      final var before = ((java.util.Map<?, ?>) field.get(manager)).size();
      storage.getWriteCache().addFile("global_collection_2147483647.grb");
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(manager::load);
      assertEquals(before, ((java.util.Map<?, ?>) field.get(manager)).size());
    }
  }

  /** Incremental restore runs the pass before opening collections and loads only owned link bags. */
  @Test
  public void incrementalRestoreQuarantinesOrphanAndFiltersLoadedLinkBags() throws Exception {
    final var backup = Files.createDirectory(root.resolve("backup"));
    try (var session = open()) {
      final var storage = (AbstractStorage) session.getStorage();
      storage.fullBackup(backup);
      storage.getWriteCache().addFile("ghost.pcl");
      storage.getWriteCache().addFile("global_collection_2147483647.grb");
      storage.backup(backup);
      storage.restoreFromBackup(backup, null);
      assertFalse(storage.getWriteCache().files().containsKey("ghost.pcl"));
      assertFalse(storage.getWriteCache().files().containsKey("global_collection_2147483647.grb"));
      final var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      final var field = LinkCollectionsBTreeManagerShared.class.getDeclaredField("fileIdBTreeMap");
      field.setAccessible(true);
      final var expected = storage.getWriteCache().files().keySet().stream()
          .filter(name -> name.startsWith("global_collection_") && name.endsWith(".grb")).count();
      assertEquals(expected, ((java.util.Map<?, ?>) field.get(manager)).size());
    }
  }

  /** A killed JVM leaves a dirty image. Recovery must run before the owner pass retires its orphan. */
  @Test
  public void crashThenReopenQuarantinesRegisteredOrphan() throws Exception {
    final var crashRoot = root.resolve("crash-child");
    final var command = new java.util.ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (final var argument : java.lang.management.ManagementFactory.getRuntimeMXBean()
        .getInputArguments()) {
      if (!argument.startsWith("-javaagent")) {
        command.add(argument);
      }
    }
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(RegisteredOrphanOpenBenchmark.class.getName());
    command.addAll(List.of("prepare", crashRoot.toString(), "16", "crash"));
    final var process = new ProcessBuilder(command)
        .redirectErrorStream(true).redirectOutput(root.resolve("crash-child.log").toFile()).start();
    assertTrue("crash fixture child must finish",
        process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    try (var crashed = (YouTrackDBImpl) YourTracks.instance(crashRoot);
        var session = (DatabaseSessionEmbedded) crashed.open("bench", "admin", "adminpwd")) {
      final var storage = (AbstractStorage) session.getStorage();
      assertTrue(storage.wereDataRestoredAfterOpen());
      assertFalse(storage.getWriteCache().files().containsKey("benchmark_sentinel.pcl"));
      session.addCollection("benchmark_sentinel");
    }
  }

  /** Logical shape matching rejects physical suffixes, signs, zero engine ids and overflow. */
  @Test
  public void logicalShapeBoundsAreIndependentOfPhysicalShapes() {
    for (final var name : List.of("ie_1.cbt", "ie_2147483647$null.nbt",
        "global_collection_0.grb", "global_collection_2147483647.grb", "hello.pcl", "config.cd")) {
      assertTrue(name, StorageFileNames.isLogicalComponentFile(name));
    }
    for (final var name : List.of("ie_0.cbt", "ie_-1.cbt", "ie_01.cbt", "ie_1_5.cbt",
        "ie_2147483648.cbt", "global_collection_2147483648.grb", "config_5.cd",
        "foreign.cbt", "../ghost.pcl", "ie_999999999999999999999.cbt")) {
      assertFalse(name, StorageFileNames.isLogicalComponentFile(name));
    }
  }
}
