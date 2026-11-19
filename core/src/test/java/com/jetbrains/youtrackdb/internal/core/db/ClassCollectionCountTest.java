package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Checks count ownership across creation, full restart, shared settings, and backup restore. */
public class ClassCollectionCountTest {

  private Path root;
  private Path databases;

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("class-collection-count-");
    databases = Files.createDirectories(root.resolve("databases"));
  }

  @After
  public void tearDown() {
    FileUtils.deleteRecursively(root.toFile());
  }

  @Test
  public void absentZeroAndPositiveCountsApplyToMemoryAndDiskClasses() {
    assertEquals(1, GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValueAsInteger());
    try (var manager = manager()) {
      for (var type : DatabaseType.values()) {
        create(manager, "default" + type, type, YouTrackDBConfig.defaultConfig());
        assertCounts(manager, "default" + type, 1);
        create(manager, "auto" + type, type, count(0));
        assertCounts(manager, "auto" + type, 1);
        create(manager, "explicit" + type, type, count(3));
        assertCounts(manager, "explicit" + type, 3);
      }
    }
  }

  @Test
  public void persistedEightSurvivesFullShutdownAndFreshOpenSettings() {
    try (var manager = manager()) {
      create(manager, "eight", DatabaseType.DISK, count(8));
      assertCounts(manager, "eight", 8);
    }
    try (var manager = manager();
        var session = manager.open("eight", "admin", "adminpwd",
            YouTrackDBConfig.defaultConfig())) {
      assertEquals(8, session.getStorage().getMinimumCollections());
      assertEquals(8,
          session.getMetadata().getSchema().getClass("Created").getCollectionIds().length);
      assertEquals(8,
          session.getMetadata().getSchema().createClass("AfterRestart").getCollectionIds().length);
    }
  }

  @Test
  public void startupLoadAllKeepsBothCountsAndDoesNotContaminateLaterCreation() {
    try (var manager = manager()) {
      create(manager, "eight", DatabaseType.DISK, count(8));
      create(manager, "one", DatabaseType.DISK, count(1));
    }
    try (var manager = manager()) {
      var engine = (YouTrackDBInternalEmbedded) manager.internal;
      engine.loadAllDatabases();
      // No persisted count may enter engine settings, even if a one-count database loads last.
      assertFalse(engine.getConfiguration().getConfiguration().getContextKeys()
          .contains(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey()));
      // Assert both databases after the complete scan, regardless of filesystem scan order.
      assertCounts(manager, "eight", 8);
      assertCounts(manager, "one", 1);
      create(manager, "later", DatabaseType.DISK, YouTrackDBConfig.defaultConfig());
      assertCounts(manager, "later", 1);
      assertEquals(8, ((YouTrackDBInternalEmbedded) manager.internal)
          .getStorage("eight").getMinimumCollections());
    }
  }

  @Test
  public void openingEightDoesNotTurnReusedCallerSettingsIntoAnOverride() {
    try (var manager = manager()) {
      create(manager, "eight", DatabaseType.DISK, count(8));
    }
    var caller = (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig();
    try (var manager = manager()) {
      try (var session = manager.open("eight", "admin", "adminpwd", caller)) {
        assertEquals(8, session.getStorage().getMinimumCollections());
      }
      assertFalse(caller.getConfiguration().getContextKeys()
          .contains(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey()));
      create(manager, "later", DatabaseType.DISK, caller);
      assertCounts(manager, "later", 1);
      assertCounts(manager, "eight", 8);
    }
  }

  @Test
  public void restorePreservesEightWithoutContaminatingReusedCallerSettings() throws Exception {
    var backup = Files.createDirectories(root.resolve("backup"));
    try (var manager = manager()) {
      create(manager, "source", DatabaseType.DISK, count(8));
      assertCounts(manager, "source", 8);
      ((YouTrackDBInternalEmbedded) manager.internal).getStorage("source").fullBackup(backup);
    }
    var caller = (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig();
    try (var manager = manager()) {
      ((YouTrackDBInternalEmbedded) manager.internal)
          .restore("restored", backup.toString(), null, caller);
      try (var session = manager.open("restored", "admin", "adminpwd")) {
        assertEquals(8,
            session.getMetadata().getSchema().getClass("Created").getCollectionIds().length);
      }
      try (var session = manager.open("restored", "admin", "adminpwd")) {
        assertEquals(8, session.getStorage().getMinimumCollections());
        assertEquals(8,
            session.getMetadata().getSchema().createClass("AfterRestore")
                .getCollectionIds().length);
      }
      assertFalse(caller.getConfiguration().getContextKeys()
          .contains(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey()));
      create(manager, "later", DatabaseType.DISK, caller);
      assertCounts(manager, "later", 1);
    }
  }

  @Test
  public void onlyCountIsIsolatedWhileOtherConfigurationWritesRemainShared() {
    var caller = (YouTrackDBConfigImpl) count(0);
    try (var manager = manager()) {
      create(manager, "auto", DatabaseType.MEMORY, caller);
      try (var session = manager.open("auto", "admin", "adminpwd")) {
        var effective = session.getStorage().getContextConfiguration();
        assertEquals(1, effective.getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
        assertEquals(0, caller.getConfiguration()
            .getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
        // Use the storage's shared source, which can include inherited engine settings.
        effective.setValue(GlobalConfiguration.DB_VALIDATION, false);
        assertFalse(effective.getValueAsBoolean(GlobalConfiguration.DB_VALIDATION));
        assertFalse(caller.getConfiguration().getValueAsBoolean(GlobalConfiguration.DB_VALIDATION));
        assertEquals(Boolean.FALSE, effective.getValue(
            GlobalConfiguration.DB_VALIDATION.getKey(), (Object) null));
        assertEquals(1, effective.getValue(
            GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), (Object) null));
        var merged = new com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration();
        merged.setValue(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, 3);
        merged.setValue(GlobalConfiguration.DB_VALIDATION, true);
        effective.merge(merged);
        assertEquals(3, effective.getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
        assertEquals(0, caller.getConfiguration()
            .getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
        var exported = new org.apache.commons.configuration2.BaseConfiguration();
        effective.merge(exported);
        assertEquals(3, exported.getInt(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey()));
        assertEquals(true, exported.getBoolean(GlobalConfiguration.DB_VALIDATION.getKey()));
      }
    }
  }

  @Test
  public void copyingAndMergingStorageSettingsKeepLocalCountAndSharedOverrides() {
    var caller = (YouTrackDBConfigImpl) count(0);
    // Pick a non-default shared value so global fallback cannot satisfy the assertions.
    var validation = !GlobalConfiguration.DB_VALIDATION.getValueAsBoolean();
    caller.getConfiguration().setValue(GlobalConfiguration.DB_VALIDATION, validation);
    try (var manager = manager()) {
      create(manager, "copy", DatabaseType.MEMORY, caller);
      try (var session = manager.open("copy", "admin", "adminpwd")) {
        var effective = session.getStorage().getContextConfiguration();
        var copy = new ContextConfiguration(effective);
        var merged = new ContextConfiguration();
        merged.setValue("copy.unrelated", "retained");
        merged.merge(effective);
        for (var result : new ContextConfiguration[] {copy, merged}) {
          assertEquals(1, result.getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
          assertEquals(validation, result.getValueAsBoolean(GlobalConfiguration.DB_VALIDATION));
          assertEquals(effective.getContextKeys(), copy.getContextKeys());
          // A copy must not remain connected to the delegated shared settings.
          result.setValue(GlobalConfiguration.DB_VALIDATION, !validation);
          assertEquals(validation, effective.getValueAsBoolean(GlobalConfiguration.DB_VALIDATION));
        }
        assertEquals("retained", merged.getValue("copy.unrelated", ""));
        assertEquals(0, caller.getConfiguration()
            .getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT));
      }
    }
  }

  private YouTrackDBImpl manager() {
    return (YouTrackDBImpl) YourTracks.instance(databases.toString());
  }

  private static YouTrackDBConfig count(int count) {
    return YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, count)
        .build();
  }

  private static void create(YouTrackDBImpl manager, String name, DatabaseType type,
      YouTrackDBConfig settings) {
    manager.create(name, type, settings, "admin", "adminpwd", "admin");
  }

  private static void assertCounts(YouTrackDBImpl manager, String name, int expected) {
    try (var session = manager.open(name, "admin", "adminpwd")) {
      assertEquals(expected, session.getStorage().getMinimumCollections());
      assertEquals(expected,
          session.getMetadata().getSchema().createClass("Created").getCollectionIds().length);
    }
  }
}
