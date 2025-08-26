package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.util.Random;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StorageBackupTest {

  private String testDirectory;

  @Before
  public void before() {
    testDirectory = DbTestBase.getBaseDirectoryPath(getClass());
  }

  @Test
  public void testSingeThreadFullBackup() {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.execute(
        "create database `" + dbName + "` disk users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty("num", PropertyType.INTEGER);
    backupClass.createProperty("data", PropertyType.BINARY);

    backupClass.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.setProperty("num", num);
      document.setProperty("data", data);

      db.commit();
    }

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.incrementalBackup(backupDir.toPath());
    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    if (youTrackDB.exists(dbName)) {
      youTrackDB.drop(dbName);
    }
    if (youTrackDB.exists(backupDbName)) {
      youTrackDB.drop(backupDbName);
    }

    youTrackDB.close();

    FileUtils.deleteRecursively(backupDir);
  }

  @Test
  public void testSingeThreadIncrementalBackup() {
    FileUtils.deleteRecursively(new File(testDirectory));

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);

    final var dbName = StorageBackupTest.class.getSimpleName();
    youTrackDB.execute(
        "create database `" + dbName + "` disk users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty("num", PropertyType.INTEGER);
    backupClass.createProperty("data", PropertyType.BINARY);

    backupClass.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.setProperty("num", num);
      document.setProperty("data", data);

      db.commit();
    }

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.incrementalBackup(backupDir.toPath());

    for (var n = 0; n < 3; n++) {
      for (var i = 0; i < 1000; i++) {
        db.begin();
        final var data = new byte[16];
        random.nextBytes(data);

        final var num = random.nextInt();

        final var document = ((EntityImpl) db.newEntity("BackupClass"));
        document.setProperty("num", num);
        document.setProperty("data", data);

        db.commit();
      }

      db.incrementalBackup(backupDir.toPath());
    }

    db.incrementalBackup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.drop(dbName);
    youTrackDB.drop(backupDbName);

    youTrackDB.close();

    FileUtils.deleteRecursively(backupDir);
  }

  @Test
  public void testSingeThreadIncrementalBackupEncryption() {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "T1JJRU5UREJfSVNfQ09PTA==");

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);

    final var dbName = StorageBackupTest.class.getSimpleName();
    youTrackDB.execute(
        "create database `" + dbName + "` disk users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty("num", PropertyType.INTEGER);
    backupClass.createProperty("data", PropertyType.BINARY);

    backupClass.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.setProperty("num", num);
      document.setProperty("data", data);

      db.commit();
    }

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.incrementalBackup(backupDir.toPath());

    for (var n = 0; n < 3; n++) {
      for (var i = 0; i < 1000; i++) {
        db.begin();
        final var data = new byte[16];
        random.nextBytes(data);

        final var num = random.nextInt();

        final var document = ((EntityImpl) db.newEntity("BackupClass"));
        document.setProperty("num", num);
        document.setProperty("data", data);

        db.commit();
      }

      db.incrementalBackup(backupDir.toPath());
    }

    db.incrementalBackup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        config);

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
    if (youTrackDB.exists(dbName)) {
      youTrackDB.drop(dbName);
    }
    if (youTrackDB.exists(backupDbName)) {
      youTrackDB.drop(backupDbName);
    }

    youTrackDB.close();

    FileUtils.deleteRecursively(backupDir);
  }
}
