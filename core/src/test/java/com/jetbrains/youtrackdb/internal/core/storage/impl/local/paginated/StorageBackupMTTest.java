package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;

public class StorageBackupMTTest {

  private final CountDownLatch started = new CountDownLatch(1);
  private final Stack<CountDownLatch> backupIterationRecordCount = new Stack<>();
  private final CountDownLatch finished = new CountDownLatch(1);

  private YouTrackDBImpl youTrackDB;
  private String dbName;

  @Test
  public void testParallelBackup() throws Exception {
    backupIterationRecordCount.clear();
    for (var i = 0; i < 100; i++) {
      var latch = new CountDownLatch(4);
      backupIterationRecordCount.add(latch);
    }

    dbName = StorageBackupMTTest.class.getSimpleName();

    final var buildDirectory =
        System.getProperty("buildDirectory", ".") + File.separator + getClass().getSimpleName();
    final var backupDir = new File(buildDirectory, "backupDir");

    FileUtils.deleteRecursively(backupDir);
    FileUtils.deleteRecursively(new File(DbTestBase.getBaseDirectoryPath(getClass())));

    final var backupDbName = StorageBackupMTTest.class.getSimpleName() + "BackUp";
    try {
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(getClass()));
      youTrackDB.execute(
          "create database `" + dbName + "` disk users(admin identified by 'admin' role admin)");

      var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        graph.autoExecuteInTx(g ->
            g.addSchemaClass(dbName,
                __.addSchemaProperty("num", PropertyType.INTEGER)
                    .addPropertyIndex("backupIndex", IndexType.NOT_UNIQUE),
                __.addSchemaProperty("data", PropertyType.BINARY)
            )
        );
      }
      if (!backupDir.exists()) {
        Assert.assertTrue(backupDir.mkdirs());
      }

      try (var executor = Executors.newCachedThreadPool()) {
        final List<Future<Void>> futures = new ArrayList<>();

        for (var i = 0; i < 4; i++) {
          var producerIterationRecordCount = new Stack<CountDownLatch>();
          producerIterationRecordCount.addAll(backupIterationRecordCount);
          futures.add(executor.submit(new DataWriterCallable(producerIterationRecordCount, 1000)));
        }

        futures.add(executor.submit(new DBBackupCallable(backupDir.getAbsolutePath())));

        started.countDown();

        finished.await();

        for (var future : futures) {
          future.get();
        }
      }

      System.out.println("do inc backup last time");
      db.incrementalBackup(backupDir.toPath());

      youTrackDB.close();

      System.out.println("create and restore");

      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(getClass()));
      youTrackDB.restore(backupDbName, null, null, backupDir.getAbsolutePath(),
          YouTrackDBConfig.defaultConfig());

      final var compare =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
              System.out::println);

      System.out.println("compare");

      var areSame = compare.compare();
      Assert.assertTrue(areSame);
    } finally {
      if (youTrackDB != null && youTrackDB.isOpen()) {
        try {
          youTrackDB.close();
        } catch (Exception ex) {
          LogManager.instance().error(this, "", ex);
        }
      }
      try {
        youTrackDB = (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()));

        if (youTrackDB.exists(dbName)) {
          youTrackDB.drop(dbName);
        }
        if (youTrackDB.exists(backupDbName)) {
          youTrackDB.drop(backupDbName);
        }

        youTrackDB.close();

        FileUtils.deleteRecursively(backupDir);
      } catch (Exception ex) {
        LogManager.instance().error(this, "", ex);
      }
    }
  }

  @Test
  public void testParallelBackupEncryption() throws Exception {
    backupIterationRecordCount.clear();
    for (var i = 0; i < 100; i++) {
      var latch = new CountDownLatch(4);
      backupIterationRecordCount.add(latch);
    }

    var testDirectory = DbTestBase.getBaseDirectoryPath(getClass());
    FileUtils.deleteRecursively(new File(testDirectory));

    FileUtils.createDirectoryTree(testDirectory);
    final var backupDbName = StorageBackupMTTest.class.getSimpleName() + "BackUp";
    final var backupDir = new File(testDirectory, "backupDir");
    FileUtils.deleteRecursively(backupDir);

    dbName = StorageBackupMTTest.class.getSimpleName();
    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "T1JJRU5UREJfSVNfQ09PTA==");
    try {
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
      youTrackDB.execute(
          "create database `" + dbName + "` disk users(admin identified by 'admin' role admin)");
      var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        graph.autoExecuteInTx(g -> g.addSchemaClass("BackupClass",
            __.addSchemaProperty("num", PropertyType.INTEGER)
                .addPropertyIndex("backupIndex", IndexType.NOT_UNIQUE),
            __.addSchemaProperty("data", PropertyType.BINARY)
        ));
      }

      if (!backupDir.exists()) {
        Assert.assertTrue(backupDir.mkdirs());
      }

      try (final var executor = Executors.newCachedThreadPool()) {
        final List<Future<Void>> futures = new ArrayList<>();

        for (var i = 0; i < 4; i++) {
          var producerIterationRecordCount = new Stack<CountDownLatch>();
          producerIterationRecordCount.addAll(backupIterationRecordCount);
          futures.add(executor.submit(new DataWriterCallable(producerIterationRecordCount, 1000)));
        }

        futures.add(executor.submit(new DBBackupCallable(backupDir.getAbsolutePath())));

        started.countDown();

        finished.await();

        for (var future : futures) {
          future.get();
        }
      }

      System.out.println("do inc backup last time");
      db.incrementalBackup(backupDir.toPath());

      youTrackDB.close();

      System.out.println("create and restore");

      youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
      youTrackDB.restore(backupDbName, null, null,
          backupDir.getAbsolutePath(), config);

      final var compare =
          new DatabaseCompare(
              (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin"),
              (DatabaseSessionEmbedded) youTrackDB.open(backupDbName, "admin", "admin"),
              System.out::println);

      System.out.println("compare");

      var areSame = compare.compare();
      Assert.assertTrue(areSame);
    } finally {
      if (youTrackDB.isOpen()) {
        try {
          youTrackDB.close();
        } catch (Exception ex) {
          LogManager.instance().error(this, "", ex);
        }
      }
      try {
        youTrackDB = (YouTrackDBImpl) YourTracks.instance(testDirectory, config);
        if (youTrackDB.exists(dbName)) {
          youTrackDB.drop(dbName);
        }
        if (youTrackDB.exists(backupDbName)) {
          youTrackDB.drop(backupDbName);
        }

        youTrackDB.close();

        FileUtils.deleteRecursively(backupDir);
      } catch (Exception ex) {
        LogManager.instance().error(this, "", ex);
      }
    }
  }

  private final class DataWriterCallable implements Callable<Void> {

    private final Stack<CountDownLatch> producerIterationRecordCount;
    private final int count;

    public DataWriterCallable(Stack<CountDownLatch> producerIterationRecordCount, int count) {
      this.producerIterationRecordCount = producerIterationRecordCount;
      this.count = count;
    }

    @Override
    public Void call() throws Exception {
      started.await();

      System.out.println(Thread.currentThread() + " - start writing");

      try (var session = (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", "admin")) {
        var random = new Random();
        List<RID> ids = new ArrayList<>();
        while (!producerIterationRecordCount.isEmpty()) {

          for (var i = 0; i < count; i++) {
            try {
              var tx = session.begin();
              final var data = new byte[random.nextInt(1024)];
              random.nextBytes(data);

              final var num = random.nextInt();
              if (!ids.isEmpty() && i % 8 == 0) {
                var id = ids.removeFirst();
                var transaction = session.getActiveTransaction();
                session.getActiveTransaction().delete(transaction.loadEntity(id));
              } else if (!ids.isEmpty() && i % 4 == 0) {
                var id = ids.removeFirst();
                final EntityImpl document = session.getActiveTransaction().load(id);
                document.setProperty("data", data);
              } else {
                final var document = ((EntityImpl) session.getActiveTransaction()
                    .newEntity("BackupClass"));
                document.setProperty("num", num);
                document.setProperty("data", data);

                RID id = document.getIdentity();
                if (ids.size() < 100) {
                  ids.add(id);
                }
              }
              tx.commit();

            } catch (ModificationOperationProhibitedException e) {
              System.out.println("Modification prohibited ... wait ...");
              //noinspection BusyWait
              Thread.sleep(1000);
            }
          }
          producerIterationRecordCount.pop().countDown();
          System.out.println(Thread.currentThread() + " writing of a batch done");
        }

        System.out.println(Thread.currentThread() + " - done writing");
        finished.countDown();
        return null;
      }
    }
  }

  public final class DBBackupCallable implements Callable<Void> {

    private final String backupPath;

    public DBBackupCallable(String backupPath) {
      this.backupPath = backupPath;
    }

    @Override
    public Void call() throws Exception {
      started.await();

      try (var db = youTrackDB.open(dbName, "admin", "admin")) {
        System.out.println(Thread.currentThread() + " - start backup");
        while (!backupIterationRecordCount.isEmpty()) {
          var latch = backupIterationRecordCount.pop();
          latch.await();

          System.out.println(Thread.currentThread() + " do inc backup");
          db.incrementalBackup(Path.of(backupPath));
          System.out.println(Thread.currentThread() + " done inc backup");
        }
      } catch (Exception | Error e) {
        LogManager.instance().error(this, "", e);
        throw e;
      }
      finished.countDown();

      System.out.println(Thread.currentThread() + " - stop backup");

      return null;
    }
  }
}
