package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

public class StorageBackupTestWithLuceneIndex {

  private String buildDirectory;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;
  private String dbDirectory;
  private String backedUpDbDirectory;

  @Before
  public void before() {
    buildDirectory = System.getProperty("buildDirectory", ".");
    dbDirectory =
        buildDirectory + File.separator + StorageBackupTestWithLuceneIndex.class.getSimpleName();
    FileUtils.deleteRecursively(new File(dbDirectory));
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(dbDirectory);
    if (youTrackDB.exists(StorageBackupTestWithLuceneIndex.class.getSimpleName())) {
      youTrackDB.drop(StorageBackupTestWithLuceneIndex.class.getSimpleName());
    }

    youTrackDB.create(StorageBackupTestWithLuceneIndex.class.getSimpleName(), DatabaseType.DISK,
        "admin", "admin", "admin");

    backedUpDbDirectory =
        buildDirectory
            + File.separator
            + StorageBackupTestWithLuceneIndex.class.getSimpleName()
            + "BackUpDir";
  }

  @After
  public void after() {
    if (youTrackDB.exists(StorageBackupTestWithLuceneIndex.class.getSimpleName())) {
      youTrackDB.drop(StorageBackupTestWithLuceneIndex.class.getSimpleName());
    }

    if (youTrackDB.exists(StorageBackupTestWithLuceneIndex.class.getSimpleName() + "Backup")) {
      youTrackDB.drop(StorageBackupTestWithLuceneIndex.class.getSimpleName());
    }

    FileUtils.deleteRecursively(new File(dbDirectory));
    FileUtils.deleteRecursively(new File(buildDirectory, "backupDir"));
  }

  // @Test
  public void testSingeThreadFullBackup() throws IOException {
    final Schema schema = db.getMetadata().getSlowMutableSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty("num", PropertyType.INTEGER);
    backupClass.createProperty("name", PropertyType.STRING);

    backupClass.createIndex(
        "backupLuceneIndex",
        SchemaManager.INDEX_TYPE.FULLTEXT.toString(),
        null,
        new String[]{"name"});

    db.begin();
    final var document = ((EntityImpl) db.newEntity("BackupClass"));
    document.setProperty("num", 1);
    document.setProperty("name", "Storage");

    db.commit();

    final var backupDir = new File(buildDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.incrementalBackup(backupDir.toPath());
    final var storage = db.getStorage();
    db.close();

    storage.close(db, true);

    FileUtils.deleteRecursively(new File(backedUpDbDirectory));

    youTrackDB.restore(StorageBackupTestWithLuceneIndex.class.getSimpleName() + "Backup",
        "admin", "admin", backedUpDbDirectory,
        YouTrackDBConfig.defaultConfig().toApacheConfiguration());
    final var backedUpDb = (DatabaseSessionEmbedded) youTrackDB.open(
        StorageBackupTestWithLuceneIndex.class.getSimpleName() + "Backup", "admin", "admin");

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionEmbedded)
                youTrackDB.open(
                    StorageBackupTestWithLuceneIndex.class.getSimpleName(), "admin", "admin"),
            backedUpDb,
            System.out::println);

    Assert.assertTrue(compare.compare());
  }

  // @Test
  public void testSingeThreadIncrementalBackup() throws IOException {

    final Schema schema = db.getMetadata().getSlowMutableSchema();
    final var backupClass = schema.createClass("BackupClass");
    backupClass.createProperty("num", PropertyType.INTEGER);
    backupClass.createProperty("name", PropertyType.STRING);

    backupClass.createIndex(
        "backupLuceneIndex",
        SchemaManager.INDEX_TYPE.FULLTEXT.toString(),
        null,
        new String[]{"name"});

    final var backupDir = new File(buildDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    db.begin();
    var document = ((EntityImpl) db.newEntity("BackupClass"));
    document.setProperty("num", 1);
    document.setProperty("name", "Storage");

    db.commit();

    db.incrementalBackup(backupDir.toPath());

    db.begin();
    document = ((EntityImpl) db.newEntity("BackupClass"));
    document.setProperty("num", 1);
    document.setProperty("name", "Storage1");

    db.commit();

    db.incrementalBackup(backupDir.toPath());

    final var storage = db.getStorage();
    db.close();

    storage.close(db, true);

    final var backedUpDbDirectory =
        buildDirectory
            + File.separator
            + StorageBackupTestWithLuceneIndex.class.getSimpleName()
            + "BackUp";
    FileUtils.deleteRecursively(new File(backedUpDbDirectory));

    youTrackDB.restore(StorageBackupTestWithLuceneIndex.class.getSimpleName() + "Backup",
        "admin", "admin", backedUpDbDirectory, YouTrackDBConfig.defaultConfig());
    final var backedUpDb = (DatabaseSessionEmbedded) youTrackDB.open(
        StorageBackupTestWithLuceneIndex.class.getSimpleName() + "Backup", "admin", "admin");

    final var compare =
        new DatabaseCompare(
            (DatabaseSessionEmbedded)
                youTrackDB.open(
                    StorageBackupTestWithLuceneIndex.class.getSimpleName(), "admin", "admin"),
            backedUpDb,
            System.out::println);
    Assert.assertTrue(compare.compare());
  }
}
