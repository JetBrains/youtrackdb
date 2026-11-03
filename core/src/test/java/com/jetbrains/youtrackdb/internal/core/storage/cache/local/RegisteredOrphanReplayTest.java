package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.Test;

public class RegisteredOrphanReplayTest {
  /** Capture formatted owner-pass messages, not just a prefix that unexpanded formats share. */
  private static final class WarningCapture extends java.util.logging.Handler
      implements AutoCloseable {
    private final java.util.List<String> messages = new java.util.ArrayList<>();
    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("");

    private WarningCapture() {
      logger.addHandler(this);
    }

    @Override
    public void publish(java.util.logging.LogRecord record) {
      if (record.getMessage().contains("Skipping ")) {
        messages.add(record.getMessage());
      }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
      logger.removeHandler(this);
    }
  }

  private static void append(java.nio.file.Path directory, NameFileIdEntry... entries)
      throws Exception {
    for (final var name : java.util.List.of("name_id_map_v3.cm", "name_id_map_v3_backup.cm")) {
      for (final var entry : entries) {
        final var record = NameIdMapFile.encode(entry, "aliases");
        final var bytes = new byte[record.remaining()];
        record.get(bytes);
        Files.write(directory.resolve(name), bytes, StandardOpenOption.APPEND);
      }
    }
  }

  /** An existing same-id alias cannot mask a missing owned main or allow unrelated moves. */
  @Test
  public void missingOwnedMainBehindAliasStopsAllMovesWithOneWarning() throws Exception {
    final var root = DbTestBase.getBaseDirectoryPath(getClass()).resolve("missingMain");
    try {
      int ownedId;
      String primary;
      String alias;
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        db.create("aliases", DatabaseType.DISK, "admin", "adminpwd", "admin");
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          session.addCollection("owned");
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          ownedId = AbstractWriteCache.extractFileId(cache.files().get("owned.pcl"));
          primary = cache.nativeFileNameById(cache.files().get("owned.pcl"));
          alias = cache.nativeFileNameById(cache.addFile("alias.pcl"));
          cache.addFile("independent.pcl");
        }
      }
      Files.delete(root.resolve("aliases").resolve(primary));
      append(root.resolve("aliases"), new NameFileIdEntry("alias.pcl", ownedId, alias));
      final var warnings = new WarningCapture();
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        // The missing main may also prevent collection open. The pass must stop before that.
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          assertTrue(session.getStorage().getWriteCache().files().containsKey("independent.pcl"));
        }
        org.junit.Assert.assertEquals(java.util.List.of(
            "youtrackdb:aliases [aliases] Skipping registered orphan quarantine:"
                + " configured main file owned.pcl"
                + " is missing"),
            warnings.messages);
        assertFalse(Files.exists(root.resolve("aliases/orphans")));
      } finally {
        warnings.close();
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
    }
  }

  /** Missing last-path normalization must not hide an alias of an owned auxiliary file. */
  @Test
  public void missingLastAliasStillProtectsSurvivingOwnedAuxiliary() throws Exception {
    final var root = DbTestBase.getBaseDirectoryPath(getClass()).resolve("missingAlias");
    try {
      int ownedId;
      int ghostId;
      String auxiliary;
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        db.create("aliases", DatabaseType.DISK, "admin", "adminpwd", "admin");
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          session.addCollection("owned");
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          ownedId = AbstractWriteCache.extractFileId(cache.files().get("owned.cpm"));
          auxiliary = cache.nativeFileNameById(cache.files().get("owned.cpm"));
          ghostId = AbstractWriteCache.extractFileId(cache.addFile("ghost.pcl"));
        }
      }
      append(root.resolve("aliases"),
          new NameFileIdEntry("ghost.pcl", ghostId, auxiliary),
          new NameFileIdEntry("alias.pcl", ownedId, "missing.pcl"));
      try (var db = (YouTrackDBImpl) YourTracks.instance(root);
          var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
        final var cache = (WOWCache) session.getStorage().getWriteCache();
        assertTrue(cache.files().containsKey("ghost.pcl"));
        assertTrue(Files.exists(root.resolve("aliases").resolve(auxiliary)));
        assertFalse(Files.exists(root.resolve("aliases/orphans")));
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
    }
  }

  /** A clean close preserves mixed same-id and cross-id aliases through the second open. */
  @Test
  public void mixedAliasesRemainProtectedAfterCleanCloseAndSecondOpen() throws Exception {
    final var root = DbTestBase.getBaseDirectoryPath(getClass()).resolve("repeat");
    try {
      int firstId;
      int thirdId;
      String firstDisk;
      String secondDisk;
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        db.create("aliases", DatabaseType.DISK, "admin", "adminpwd", "admin");
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          final var first = cache.addFile("a.pcl");
          firstId = AbstractWriteCache.extractFileId(first);
          firstDisk = cache.nativeFileNameById(first);
          secondDisk = cache.nativeFileNameById(cache.addFile("b.pcl"));
          thirdId = AbstractWriteCache.extractFileId(cache.addFile("c.pcl"));
        }
      }
      append(root.resolve("aliases"),
          new NameFileIdEntry("b.pcl", firstId, secondDisk),
          new NameFileIdEntry("c.pcl", thirdId, firstDisk));
      for (var open = 0; open < 2; open++) {
        try (var db = (YouTrackDBImpl) YourTracks.instance(root);
            var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          for (final var logical : java.util.List.of("a.pcl", "b.pcl", "c.pcl")) {
            assertTrue(logical, cache.files().containsKey(logical));
          }
          assertTrue(Files.exists(root.resolve("aliases").resolve(firstDisk)));
          assertTrue(Files.exists(root.resolve("aliases").resolve(secondDisk)));
          assertFalse(Files.exists(root.resolve("aliases/orphans")));
        }
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
    }
  }

  /** Two ids claiming an owned primary must both stay. An independent orphan must still move. */
  @Test
  public void crossIdDiskAliasPreservesOwnedPrimaryAndMovesIndependentOrphan() throws Exception {
    final var root = DbTestBase.getBaseDirectoryPath(getClass());
    try {
      int ghostId;
      String primary;
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        db.create("aliases", DatabaseType.DISK, "admin", "adminpwd", "admin");
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          session.addCollection("owned");
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          primary = cache.nativeFileNameById(cache.files().get("owned.pcl"));
          ghostId = AbstractWriteCache.extractFileId(cache.addFile("ghost.pcl"));
          cache.addFile("independent.pcl");
        }
      }
      final var record = NameIdMapFile.encode(
          new NameFileIdEntry("ghost.pcl", ghostId, primary), "aliases");
      final var bytes = new byte[record.remaining()];
      record.get(bytes);
      for (final var name : java.util.List.of("name_id_map_v3.cm",
          "name_id_map_v3_backup.cm")) {
        Files.write(root.resolve("aliases").resolve(name), bytes, StandardOpenOption.APPEND);
      }
      try (var db = (YouTrackDBImpl) YourTracks.instance(root);
          var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
        final var cache = (WOWCache) session.getStorage().getWriteCache();
        assertTrue(cache.files().containsKey("owned.pcl"));
        assertTrue(cache.files().containsKey("ghost.pcl"));
        assertTrue(Files.exists(root.resolve("aliases").resolve(primary)));
        assertFalse(cache.files().containsKey("independent.pcl"));
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
    }
  }

  /** Aliases and unknown files stay registered and emit fully formatted names and ids. */
  @Test
  public void sameIdLogicalAliasIsSkipped() throws Exception {
    final var root = DbTestBase.getBaseDirectoryPath(getClass()).resolve("sameId");
    try {
      int id;
      String disk;
      try (var db = (YouTrackDBImpl) YourTracks.instance(root)) {
        db.create("aliases", DatabaseType.DISK, "admin", "adminpwd", "admin");
        try (var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
          final var cache = (WOWCache) session.getStorage().getWriteCache();
          final var external = cache.addFile("first.pcl");
          id = AbstractWriteCache.extractFileId(external);
          disk = cache.nativeFileNameById(external);
          cache.addFile("foreign.bin");
        }
      }
      final var record = NameIdMapFile.encode(new NameFileIdEntry("second.pcl", id, disk),
          "aliases");
      final var bytes = new byte[record.remaining()];
      record.get(bytes);
      for (final var name : java.util.List.of("name_id_map_v3.cm",
          "name_id_map_v3_backup.cm")) {
        Files.write(root.resolve("aliases").resolve(name), bytes, StandardOpenOption.APPEND);
      }
      try (var warnings = new WarningCapture();
          var db = (YouTrackDBImpl) YourTracks.instance(root);
          var session = (DatabaseSessionEmbedded) db.open("aliases", "admin", "adminpwd")) {
        final var files = session.getStorage().getWriteCache().files();
        assertTrue(files.containsKey("first.pcl"));
        assertTrue(files.containsKey("second.pcl"));
        final var expected = java.util.Set.of(
            "youtrackdb:aliases [aliases] Skipping ambiguous file registration first.pcl (id "
                + files.get("first.pcl") + ")",
            "youtrackdb:aliases [aliases] Skipping ambiguous file registration second.pcl (id "
                + files.get("second.pcl") + ")",
            "youtrackdb:aliases [aliases] Skipping unknown file registration foreign.bin (id "
                + files.get("foreign.bin") + ")");
        org.junit.Assert.assertEquals(expected.size(), warnings.messages.size());
        org.junit.Assert.assertEquals(expected, new java.util.HashSet<>(warnings.messages));
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
    }
  }
}
