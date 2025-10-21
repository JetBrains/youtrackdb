package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;

public class StorageBackupMTIT {

  private final CountDownLatch latch = new CountDownLatch(1);
  private volatile boolean stop = false;
  private YouTrackDBImpl youTrackDB;
  private String dbName;

  @Test
  public void testParallelBackup() throws Exception {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    dbName = StorageBackupMTIT.class.getSimpleName();

    final var backupDir = new File(buildDirectory, "backupDir");
    final var backupDbName = StorageBackupMTIT.class.getSimpleName() + "BackUp";

    FileUtils.deleteRecursively(new File(DbTestBase.getBaseDirectoryPath(getClass())));

    try {
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(getClass()));
      youTrackDB.execute(
          "create database " + dbName + " disk users ( admin identified by 'admin' role admin)");

      var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        graph.autoExecuteInTx(g -> g.createSchemaClass("BackupClass",
            __.createSchemaProperty("num", PropertyType.INTEGER)
                .createPropertyIndex("backupIndex", IndexType.NOT_UNIQUE),
            __.createSchemaProperty("data", PropertyType.BINARY)
        ));
      }

      FileUtils.deleteRecursively(backupDir);

      if (!backupDir.exists()) {
        Assert.assertTrue(backupDir.mkdirs());
      }

      try (final var executor = Executors.newCachedThreadPool()) {
        final List<Future<Void>> futures = new ArrayList<>();

        for (var i = 0; i < 4; i++) {
          futures.add(executor.submit(new DataWriterCallable()));
        }

        futures.add(executor.submit(new DBBackupCallable(backupDir.getAbsolutePath())));

        latch.countDown();

        TimeUnit.MINUTES.sleep(15);

        stop = true;

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
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    final var backupDbName = StorageBackupMTIT.class.getSimpleName() + "BackUp";
    final var backedUpDbDirectory = buildDirectory + File.separator + backupDbName;
    final var backupDir = new File(buildDirectory, "backupDir");

    dbName = StorageBackupMTIT.class.getSimpleName();

    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "T1JJRU5UREJfSVNfQ09PTA==");
    try {
      FileUtils.deleteRecursively(new File(DbTestBase.getBaseDirectoryPath(getClass())));

      youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
          config);
      youTrackDB.execute(
          "create database " + dbName + " disk users ( admin identified by 'admin' role admin)");

      var db = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");

      try (var graph = youTrackDB.openGraph(dbName, "admin", "admin")) {
        graph.autoExecuteInTx(g -> g.createSchemaClass("BackupClass",
            __.createSchemaProperty("num", PropertyType.INTEGER)
                .createPropertyIndex("backupIndex", IndexType.NOT_UNIQUE),
            __.createSchemaProperty("data", PropertyType.BINARY)
        ));
      }

      FileUtils.deleteRecursively(backupDir);

      if (!backupDir.exists()) {
        Assert.assertTrue(backupDir.mkdirs());
      }

      final var executor = Executors.newCachedThreadPool();
      final List<Future<Void>> futures = new ArrayList<>();

      for (var i = 0; i < 4; i++) {
        futures.add(executor.submit(new DataWriterCallable()));
      }

      futures.add(executor.submit(new DBBackupCallable(backupDir.getAbsolutePath())));

      latch.countDown();

      TimeUnit.MINUTES.sleep(5);

      stop = true;

      for (var future : futures) {
        future.get();
      }

      System.out.println("do inc backup last time");
      db.incrementalBackup(backupDir.toPath());

      youTrackDB.close();

      FileUtils.deleteRecursively(new File(backedUpDbDirectory));

      System.out.println("create and restore");

      GlobalConfiguration.STORAGE_ENCRYPTION_KEY.setValue("T1JJRU5UREJfSVNfQ09PTA==");
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
          config);
      youTrackDB.restore(backupDbName, null, null, backupDir.getAbsolutePath(), config);

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
            DbTestBase.getBaseDirectoryPath(getClass()), config);
        youTrackDB.drop(dbName);
        youTrackDB.drop(backupDbName);

        youTrackDB.close();

        FileUtils.deleteRecursively(backupDir);
      } catch (Exception ex) {
        LogManager.instance().error(this, "", ex);
      }
    }
  }

  private final class DataWriterCallable implements Callable<Void> {

    @Override
    public Void call() throws Exception {
      latch.await();

      System.out.println(Thread.currentThread() + " - start writing");

      try (var databaseSession = youTrackDB.open(dbName, "admin", "admin")) {
        final var random = new Random();
        while (!stop) {
          try {
            final var data = new byte[16];
            random.nextBytes(data);

            final var num = random.nextInt();

            databaseSession.executeInTx(transaction -> {
              var entity = transaction.newEntity("BackupClass");
              entity.setProperty("num", num);
              entity.setProperty("data", data);
            });
          } catch (ModificationOperationProhibitedException e) {
            System.out.println("Modification prohibited ... wait ...");
            //noinspection BusyWait
            Thread.sleep(1000);
          } catch (Exception | Error e) {
            LogManager.instance().error(this, "", e);
            throw e;
          }
        }
      }

      System.out.println(Thread.currentThread() + " - done writing");

      return null;
    }
  }

  public final class DBBackupCallable implements Callable<Void> {

    private final String backupPath;

    public DBBackupCallable(String backupPath) {
      this.backupPath = backupPath;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      try (var db = youTrackDB.open(dbName, "admin", "admin")) {
        System.out.println(Thread.currentThread() + " - start backup");
        while (!stop) {
          TimeUnit.MINUTES.sleep(1);

          System.out.println(Thread.currentThread() + " do inc backup");
          db.incrementalBackup(Path.of(backupPath));
          System.out.println(Thread.currentThread() + " done inc backup");
        }
      } catch (Exception | Error e) {
        LogManager.instance().error(this, "", e);
        throw e;
      }

      System.out.println(Thread.currentThread() + " - stop backup");

      return null;
    }
  }
}
