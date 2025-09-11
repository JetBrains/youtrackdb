package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.YTDBIOUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.UUID;
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
  public void testBrokenFullBackup() throws Exception {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    String backupFileName;
    final var random = new Random();

    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        generateChunkOfData(graph, random);

        //full backup
        backupFileName = graph.backup(backupDir.toPath());
        //lock file and backup file
        Assert.assertEquals(2, backupDir.listFiles().length);

        try (var backupChannel = FileChannel.open(backupDir.toPath().resolve(backupFileName),
            StandardOpenOption.WRITE, StandardOpenOption.READ)) {
          var fileSize = backupChannel.size();
          var position = random.nextLong(fileSize);
          var data = ByteBuffer.allocate(1);

          YTDBIOUtils.readByteBuffer(data, backupChannel, position, true);
          data.flip();
          var readByte = data.get();

          data.rewind();
          data.put((byte) (readByte + 1));
          data.flip();

          YTDBIOUtils.writeByteBuffer(data, backupChannel, position);
        }

        generateChunkOfData(graph, random);

        //one more full backup
        graph.backup(backupDir.toPath());
        //lock file and backup file
        Assert.assertEquals(2, backupDir.listFiles().length);
      }
    }

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory)) {
      youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

      final var compare =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
              System.out::println);

      Assert.assertTrue(compare.compare());
    }
  }

  @Test
  public void testBrokenIncrementalBackup() throws Exception {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    String backupFileName;
    final var random = new Random();

    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        generateChunkOfData(graph, random);

        //full backup
        graph.backup(backupDir.toPath());
        Assert.assertEquals(2, backupDir.listFiles().length);

        generateChunkOfData(graph, random);

        //incremental backup
        backupFileName = graph.backup(backupDir.toPath());
        Assert.assertEquals(3, backupDir.listFiles().length);

        try (var backupChannel = FileChannel.open(backupDir.toPath().resolve(backupFileName),
            StandardOpenOption.WRITE, StandardOpenOption.READ)) {
          var fileSize = backupChannel.size();
          var position = random.nextLong(fileSize);
          var data = ByteBuffer.allocate(1);

          YTDBIOUtils.readByteBuffer(data, backupChannel, position, true);
          data.flip();
          var readByte = data.get();

          data.rewind();
          data.put((byte) (readByte + 1));
          data.flip();

          YTDBIOUtils.writeByteBuffer(data, backupChannel, position);
        }

        generateChunkOfData(graph, random);

        //as incremental backup broken we create new one starting from changes from full backup.
        graph.backup(backupDir.toPath());
        Assert.assertEquals(3, backupDir.listFiles().length);
      }
    }

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory)) {
      youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

      final var compare =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
              System.out::println);

      Assert.assertTrue(compare.compare());
    }
  }


  @Test
  public void testRemoveFullBackupAndLeaveTwoIncrementalBackups() throws Exception {
    //Create three backups and remove full backup, ensure that the error is thrown during restore.
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    var random = new Random();
    String backupFileName;
    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        generateChunkOfData(graph, random);

        backupFileName = graph.backup(backupDir.toPath());
        Assert.assertEquals(2, backupDir.listFiles().length);

        generateChunkOfData(graph, random);
        graph.backup(backupDir.toPath());
        Assert.assertEquals(3, backupDir.listFiles().length);

        generateChunkOfData(graph, random);
        graph.backup(backupDir.toPath());
        Assert.assertEquals(4, backupDir.listFiles().length);
      }
    }

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";
    Files.deleteIfExists(backupDir.toPath().resolve(backupFileName));
    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      try {
        youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());
        Assert.fail("Exception expected");
      } catch (Exception e) {
        //we have removed a full backup and left two incremental backups, so the exception is expected.
      }
    }
  }

  @Test
  public void testRemoveIncrementalBackupAndFullAndIncrementalBackups() throws Exception {
    //Create three backups and remove the first incremental backup, ensure that the error is thrown during restore.
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    var random = new Random();
    String backupFileName;
    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        generateChunkOfData(graph, random);

        graph.backup(backupDir.toPath());
        Assert.assertEquals(2, backupDir.listFiles().length);

        generateChunkOfData(graph, random);
        backupFileName = graph.backup(backupDir.toPath());
        Assert.assertEquals(3, backupDir.listFiles().length);

        generateChunkOfData(graph, random);
        graph.backup(backupDir.toPath());
        Assert.assertEquals(4, backupDir.listFiles().length);
      }
    }

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";
    Files.deleteIfExists(backupDir.toPath().resolve(backupFileName));
    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      try {
        youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());
        Assert.fail("Exception expected");
      } catch (Exception e) {
        //we have removed a middle incremental backup and left full and last incremental backups,
        //so the exception is expected.
      }
    }
  }

  @Test
  public void testBackupWithoutSequenceIndex() throws Exception {
    FileUtils.deleteRecursively(new File(testDirectory));
    final var dbName = StorageBackupTest.class.getSimpleName();

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    var random = new Random();
    String backupFileName;

    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        generateChunkOfData(graph, random);

        backupFileName = graph.backup(backupDir.toPath());
      }
    }

    var sequenceNumberStartIndex = 57;
    var sequenceNumberEndIndex = backupFileName.indexOf('-', sequenceNumberStartIndex);
    var sequenceNumber = backupFileName.substring(sequenceNumberStartIndex, sequenceNumberEndIndex);
    Assert.assertEquals("0", sequenceNumber);

    var newFileName =
        backupFileName.substring(0, sequenceNumberStartIndex) + backupFileName.substring(
            sequenceNumberEndIndex + 1);
    Files.move(backupDir.toPath().resolve(backupFileName), backupDir.toPath().resolve(newFileName));

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";
    Files.deleteIfExists(backupDir.toPath().resolve(backupFileName));
    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      try {
        youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());
        Assert.fail("Exception expected");
      } catch (Exception e) {
        //we have removed sequence index, so the exception is expected.
      }
    }
  }

  @Test
  public void testTwoDBsMakeBackupToTheSameDir() {
    FileUtils.deleteRecursively(new File(testDirectory));

    final var dbName1 = StorageBackupTest.class.getSimpleName() + "1";
    final var dbName2 = StorageBackupTest.class.getSimpleName() + "2";

    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    var random = new Random();
    UUID firstDbUUId;
    UUID secondDbUUId;

    try (var youTrackDB = YourTracks.instance(testDirectory)) {
      youTrackDB.create(dbName1, DatabaseType.DISK, "admin", "admin", "admin");
      youTrackDB.create(dbName2, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph1 = youTrackDB.openGraph(dbName1, "admin", "admin")) {
        try (var graph2 = youTrackDB.openGraph(dbName2, "admin", "admin")) {

          firstDbUUId = graph1.uuid();
          secondDbUUId = graph2.uuid();

          generateChunkOfData(graph1, random);
          generateChunkOfData(graph2, random);

          graph1.backup(backupDir.toPath());
          graph2.backup(backupDir.toPath());

          generateChunkOfData(graph1, random);
          generateChunkOfData(graph2, random);

          graph1.backup(backupDir.toPath());
          graph2.backup(backupDir.toPath());

          generateChunkOfData(graph1, random);
          generateChunkOfData(graph2, random);

          graph1.backup(backupDir.toPath());
          graph2.backup(backupDir.toPath());
        }
      }

    }
    final var backupDbName1 = StorageBackupTest.class.getSimpleName() + "BackUp1";
    final var backupDbName2 = StorageBackupTest.class.getSimpleName() + "BackUp2";

    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory)) {
      youTrackDB.restore(backupDbName1, backupDir.getAbsolutePath(), firstDbUUId.toString(),
          new BaseConfiguration());
      youTrackDB.restore(backupDbName2, backupDir.getAbsolutePath(), secondDbUUId.toString(),
          new BaseConfiguration());

      final var compare1 =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName1, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName1, "admin", "admin"),
              System.out::println);

      Assert.assertTrue(compare1.compare());

      final var compare2 =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName2, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName2, "admin", "admin"),
              System.out::println);

      Assert.assertTrue(compare2.compare());
    }
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

    db.backup(backupDir.toPath());
    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

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

    db.backup(backupDir.toPath());

    for (var n = 0; n < 10; n++) {
      for (var i = 0; i < 10_000; i++) {
        db.begin();
        final var data = new byte[16];
        random.nextBytes(data);

        final var num = random.nextInt();

        final var document = ((EntityImpl) db.newEntity("BackupClass"));
        document.setProperty("num", num);
        document.setProperty("data", data);

        db.commit();
      }

      db.backup(backupDir.toPath());
    }

    db.backup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory);
    youTrackDB.restore(
        backupDbName,
        backupDir.getAbsolutePath());

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

    db.backup(backupDir.toPath());

    for (var n = 0; n < 10; n++) {
      for (var i = 0; i < 10_000; i++) {
        db.begin();
        final var data = new byte[16];
        random.nextBytes(data);

        final var num = random.nextInt();

        final var document = ((EntityImpl) db.newEntity("BackupClass"));
        document.setProperty("num", num);
        document.setProperty("data", data);

        db.commit();
      }

      db.backup(backupDir.toPath());
    }

    db.backup(backupDir.toPath());
    db.close();

    youTrackDB.close();

    final var backupDbName = StorageBackupTest.class.getSimpleName() + "BackUp";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
    youTrackDB.restore(
        backupDbName,
        backupDir.getAbsolutePath(),
        null,
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

  private static void generateChunkOfData(YTDBGraph graph, Random random) {
    for (var i = 0; i < 1000; i++) {
      graph.executeInTx(g -> {
        final var data = new byte[16];
        random.nextBytes(data);
        final var num = random.nextInt();

        g.addV("BrokenClass").
            property("num", num, "data", data).
            iterate();
      });
    }
  }
}
