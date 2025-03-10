package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.util.Random;
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
    final String dbName = StorageBackupTest.class.getSimpleName();
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();
    final var dbDirectory = testDirectory + File.separator + dbName;

    YouTrackDB youTrackDB = YourTracks.embedded(testDirectory, YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database `" + dbName + "` plocal users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty(db, "num", PropertyType.INTEGER);
    backupClass.createProperty(db, "data", PropertyType.BINARY);

    backupClass.createIndex(db, "backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.field("num", num);
      document.field("data", data);

      db.commit();
    }

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.incrementalBackup(backupDir.toPath());
    youTrackDB.close();

    final String backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = YourTracks.embedded(testDirectory, YouTrackDBConfig.defaultConfig());
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final DatabaseCompare compare =
        new DatabaseCompare(
            (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionInternal) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = YourTracks.embedded(testDirectory, YouTrackDBConfig.defaultConfig());
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

    YouTrackDB youTrackDB = YourTracks.embedded(testDirectory, YouTrackDBConfig.defaultConfig());

    final var dbName = StorageBackupTest.class.getSimpleName();
    youTrackDB.execute(
        "create database `" + dbName + "` plocal users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty(db, "num", PropertyType.INTEGER);
    backupClass.createProperty(db, "data", PropertyType.BINARY);

    backupClass.createIndex(db, "backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.field("num", num);
      document.field("data", data);

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
        document.field("num", num);
        document.field("data", data);

        db.commit();
      }

      db.incrementalBackup(backupDir.toPath());
    }

    db.incrementalBackup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = YourTracks.embedded(testDirectory, YouTrackDBConfig.defaultConfig());
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final DatabaseCompare compare =
        new DatabaseCompare(
            (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionInternal) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = new YouTrackDBImpl("embedded:" + testDirectory, YouTrackDBConfig.defaultConfig());
    youTrackDB.drop(dbName);
    youTrackDB.drop(backupDbName);

    youTrackDB.close();

    FileUtils.deleteRecursively(backupDir);
  }

  @Test
  public void testSingeThreadIncrementalBackupEncryption() {
    FileUtils.deleteRecursively(new File(testDirectory));
    final YouTrackDBConfigImpl config =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                "T1JJRU5UREJfSVNfQ09PTA==")
            .build();
    YouTrackDB youTrackDB = YourTracks.embedded(testDirectory, config);

    final var dbName = StorageBackupTest.class.getSimpleName();
    youTrackDB.execute(
        "create database `" + dbName + "` plocal users(admin identified by 'admin' role admin)");

    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty(db, "num", PropertyType.INTEGER);
    backupClass.createProperty(db, "data", PropertyType.BINARY);

    backupClass.createIndex(db, "backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

    final var random = new Random();
    for (var i = 0; i < 1000; i++) {
      db.begin();
      final var data = new byte[16];
      random.nextBytes(data);

      final var num = random.nextInt();

      final var document = ((EntityImpl) db.newEntity("BackupClass"));
      document.field("num", num);
      document.field("data", data);

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
        document.field("num", num);
        document.field("data", data);

        db.commit();
      }

      db.incrementalBackup(backupDir.toPath());
    }

    db.incrementalBackup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = YourTracks.embedded(testDirectory, config);
    youTrackDB.restore(
        backupDbName,
        null,
        null,
        backupDir.getAbsolutePath(),
        config);

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin"),
            (DatabaseSessionInternal) youTrackDB.open(backupDbName, "admin", "admin"),
            System.out::println);

    Assert.assertTrue(compare.compare());

    if (youTrackDB.isOpen()) {
      youTrackDB.close();
    }

    youTrackDB = YourTracks.embedded(testDirectory, config);
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
