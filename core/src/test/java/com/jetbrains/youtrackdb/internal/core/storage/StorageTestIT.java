package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.Metadata;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.RandomAccessFile;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class StorageTestIT {

  private YouTrackDBImpl youTrackDB;

  @Test
  public void testCheckSumFailureReadOnly() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndSwitchReadOnlyMode.name());
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);

    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    youTrackDB.create(StorageTestIT.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    var session =
        youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
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
    session.close();

    final var storagePath = storage.getStoragePath();

    var fileId = wowCache.fileIdByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileId);
    youTrackDB.close();

    // Corrupt a byte in a data page (skip past metadata page at page 0)
    var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;
    var position = File.HEADER_SIZE + pageSize + (3 << 10);

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);

    var bt = file.read();
    file.seek(position);
    file.write(bt + 1);
    file.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    session = youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    session.executeInTx(transaction -> {
      try (var result = transaction.query("select from PageBreak")) {
        result.toEntityList();
      }
    });
    try {
      session.executeInTx(transaction -> transaction.newEntity("PageBreak"));
      Assert.fail();
    } catch (StorageException e) {
      //ignore
    }
  }

  @Test
  public void testCheckMagicNumberReadOnly() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndSwitchReadOnlyMode.name());
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);

    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    youTrackDB.create(StorageTestIT.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    var db =
        youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
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
    db.close();
    final var storagePath = storage.getStoragePath();

    var fileId = wowCache.fileIdByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileId);
    youTrackDB.close();

    // Corrupt magic number of a data page (skip past metadata page at page 0)
    var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;
    var position = File.HEADER_SIZE + pageSize + DurablePage.MAGIC_NUMBER_OFFSET;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);
    file.write(1);
    file.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    db = youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    db.executeInTx(transaction -> {
      try (var selectFromPageBreak = transaction.query("select from PageBreak")) {
        selectFromPageBreak.toEntityList();
      }
    });

    try {
      db.executeInTx(transaction -> transaction.newEntity("PageBreak"));
      Assert.fail();
    } catch (StorageException e) {
      //ignore
    }
  }

  @Test
  public void testCheckMagicNumberVerify() throws Exception {

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndVerify.name());

    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    youTrackDB.create(StorageTestIT.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    var db =
        youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
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
    db.close();

    final var storagePath = storage.getStoragePath();

    var fileId = wowCache.fileIdByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileId);

    youTrackDB.close();
    var position = File.HEADER_SIZE + DurablePage.MAGIC_NUMBER_OFFSET;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);
    file.write(1);
    file.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    db = youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    db.executeInTx(transaction -> transaction.query("select from PageBreak").close());

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

    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    youTrackDB.create(StorageTestIT.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    var db =
        youTrackDB.open(StorageTestIT.class.getSimpleName(), "admin",
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
    db.close();

    final var storagePath = storage.getStoragePath();

    var fileId = wowCache.fileIdByName("pagebreak.pcl");
    var nativeFileName = wowCache.nativeFileNameById(fileId);

    youTrackDB.close();

    var position = 3 << 10;

    var file =
        new RandomAccessFile(storagePath.resolve(nativeFileName).toFile(), "rw");
    file.seek(position);

    var bt = file.read();
    file.seek(position);
    file.write(bt + 1);
    file.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath,
        config);
    db = youTrackDB.open(StorageTestIT.class.getSimpleName(),
        "admin", "admin");
    db.executeInTx(transaction -> transaction.query("select from PageBreak").close());

    Thread.sleep(100); // lets wait till event will be propagated

    db.executeInTx(transaction -> {
      var document = transaction.newEntity("PageBreak");
      document.setProperty("value", "value");
    });

    db.close();
  }

  @Test
  public void testCreatedVersionIsStored() {
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(directoryPath);
    youTrackDB.create(StorageTestIT.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

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
  public void after() throws Exception {
    youTrackDB.close();

    var internal = YouTrackDBInternal.extract(youTrackDB);
    var dbPath = internal.getBasePath();
    FileUtils.deleteDirectory(new java.io.File(dbPath));
  }
}
