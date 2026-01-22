package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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
    testDirectory = DbTestBase.getBaseDirectoryPathStr(getClass());
  }

  @Test
  public void testSingeThreadFullBackup() {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    var db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);

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
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final var compare =
        new DatabaseCompare(
            youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD),
            youTrackDB.open(backupDbName, "admin", DbTestBase.ADMIN_PASSWORD),
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
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);

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
        backupDir.getAbsolutePath(),
        YouTrackDBConfig.defaultConfig());

    final var compare =
        new DatabaseCompare(
            youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD),
            youTrackDB.open(backupDbName, "admin", DbTestBase.ADMIN_PASSWORD),
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
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);

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
        backupDir.getAbsolutePath(),
        config);

    final var compare =
        new DatabaseCompare(
            youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD),
            youTrackDB.open(backupDbName, "admin", DbTestBase.ADMIN_PASSWORD),
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
