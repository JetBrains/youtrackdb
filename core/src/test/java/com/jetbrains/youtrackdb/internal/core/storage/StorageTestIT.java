package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.Metadata;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class StorageTestIT {

  private YouTrackDBImpl youTrackDB;

  private static Path buildPath;

  @BeforeClass
  public static void beforeClass() throws IOException {
    var buildDirectory = System.getProperty("buildDirectory", ".");
    buildPath = Paths.get(buildDirectory).resolve("databases")
        .resolve(StorageTestIT.class.getSimpleName());
    Files.createDirectories(buildPath);
  }

  @Test
  public void testCheckSumFailureReadOnly() throws Exception {

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndSwitchReadOnlyMode.name());
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
        config);
    youTrackDB.execute(
        "create database "
            + StorageTestIT.class.getSimpleName()
            + " disk users ( admin identified by 'admin' role admin)");

    var session =
        (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
            "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
    Metadata metadata = session.getMetadata();
    Schema schema = metadata.getSchema();
    schema.createClass("PageBreak");

    session.executeInTx(transaction -> {
      for (var i = 0; i < 10; i++) {
        var document = transaction.newEntity("PageBreak");
        document.setProperty("value", "value");
      }
    });

    var storage =
        (DiskStorage) session.getStorage();
    var wowCache = storage.getWriteCache();
    var ctx = session.getSharedContext();
    session.close();

    final var storagePath = storage.getStoragePath();

    var fileHandler = wowCache.fileHandlerByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileHandler.fileId());

    storage.shutdown();
    ctx.close();

    var position = 3 * 1024;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);

    var bt = file.read();
    file.seek(position);
    file.write(bt + 1);
    file.close();

    session = (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    try {
      session.query("select from PageBreak").close();
      Assert.fail();
    } catch (StorageException e) {
      youTrackDB.close();
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
          config);
      youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin", "admin");
    }
  }

  @Test
  public void testCheckMagicNumberReadOnly() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndSwitchReadOnlyMode.name());
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
        config);
    youTrackDB.execute(
        "create database "
            + StorageTestIT.class.getSimpleName()
            + " disk users ( admin identified by 'admin' role admin)");

    var db =
        (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
            "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
    Metadata metadata = db.getMetadata();
    Schema schema = metadata.getSchema();
    schema.createClass("PageBreak");

    db.executeInTx(transaction -> {
      for (var i = 0; i < 10; i++) {
        var document = transaction.newEntity("PageBreak");
        document.setProperty("value", "value");
      }
    });

    var storage =
        (DiskStorage) db.getStorage();
    var wowCache = storage.getWriteCache();
    var ctx = db.getSharedContext();
    db.close();

    final var storagePath = storage.getStoragePath();

    var fileHandler = wowCache.fileHandlerByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileHandler.fileId());

    storage.shutdown();
    ctx.close();

    var position = File.HEADER_SIZE + DurablePage.MAGIC_NUMBER_OFFSET;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);
    file.write(1);
    file.close();

    db = (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    try {
      db.query("select from PageBreak").close();
      Assert.fail();
    } catch (StorageException e) {
      youTrackDB.close();
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
          config);
      youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin", "admin");
    }
  }

  @Test
  public void testCheckMagicNumberVerify() throws Exception {

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndVerify.name());

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
        config);
    youTrackDB.execute(
        "create database "
            + StorageTestIT.class.getSimpleName()
            + " disk users ( admin identified by 'admin' role admin)");

    var db =
        (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
            "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
    Metadata metadata = db.getMetadata();
    Schema schema = metadata.getSchema();
    schema.createClass("PageBreak");

    db.executeInTx(transaction -> {
      for (var i = 0; i < 10; i++) {
        var document = transaction.newEntity("PageBreak");
        document.setProperty("value", "value");

      }
    });

    var storage =
        (DiskStorage) db.getStorage();
    var wowCache = storage.getWriteCache();
    var ctx = db.getSharedContext();
    db.close();

    final var storagePath = storage.getStoragePath();

    var fileHandler = wowCache.fileHandlerByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileHandler.fileId());

    storage.shutdown();
    ctx.close();

    var position = File.HEADER_SIZE + DurablePage.MAGIC_NUMBER_OFFSET;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);
    file.write(1);
    file.close();

    db = (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    db.executeInTx(transaction -> {
      transaction.query("select from PageBreak").close();
    });

    Thread.sleep(100); // lets wait till event will be propagated

    db.executeInTx(transaction -> {
      var document = transaction.newEntity("PageBreak");
      document.setProperty("value", "value");
    });

    db.close();
  }

  @Test
  public void testCheckSumFailureVerifyAndLog() throws Exception {

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndVerify);
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
        config);
    youTrackDB.execute(
        "create database "
            + StorageTestIT.class.getSimpleName()
            + " disk users ( admin identified by 'admin' role admin)");

    var db =
        (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
            "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
    Metadata metadata = db.getMetadata();
    Schema schema = metadata.getSchema();
    schema.createClass("PageBreak");

    db.executeInTx(transaction -> {
      for (var i = 0; i < 10; i++) {
        var document = transaction.newEntity("PageBreak");
        document.setProperty("value", "value");

      }
    });

    var storage =
        (DiskStorage) db.getStorage();
    var wowCache = storage.getWriteCache();
    var ctx = db.getSharedContext();
    db.close();

    final var storagePath = storage.getStoragePath();

    var fileHandler = wowCache.fileHandlerByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileHandler.fileId());

    storage.shutdown();
    ctx.close();

    var position = 3 * 1024;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);

    var bt = file.read();
    file.seek(position);
    file.write(bt + 1);
    file.close();

    db = (DatabaseSessionInternal) youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    db.executeInTx(transaction -> {
      transaction.query("select from PageBreak").close();
    });

    Thread.sleep(100); // lets wait till event will be propagated

    db.executeInTx(transaction -> {
      var document = transaction.newEntity("PageBreak");
      document.setProperty("value", "value");
    });

    db.close();
  }

  @Test
  public void testCreatedVersionIsStored() {
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.execute(
        "create database "
            + StorageTestIT.class.getSimpleName()
            + " disk users ( admin identified by 'admin' role admin)");

    final var session =
        youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin", "admin");
    var tx = session.begin();
    try (var resultSet = tx.query("SELECT FROM metadata:storage")) {
      Assert.assertTrue(resultSet.hasNext());

      final var result = resultSet.next();
      Assert.assertEquals(YouTrackDBConstants.getVersion(), result.getProperty("createdAtVersion"));
    }
    tx.commit();
  }

  @After
  public void after() {
    youTrackDB.drop(StorageTestIT.class.getSimpleName());
  }
}
