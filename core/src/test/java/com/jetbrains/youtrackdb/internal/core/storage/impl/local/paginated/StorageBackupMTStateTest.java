package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @since 10/6/2015
 */
public class StorageBackupMTStateTest {

  static {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(3);
    GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(10);
  }

  private final ReadersWriterSpinLock flowLock = new ReadersWriterSpinLock();

  private final ConcurrentMap<String, AtomicInteger> classInstancesCounters =
      new ConcurrentHashMap<String, AtomicInteger>();

  private final AtomicInteger classCounter = new AtomicInteger();

  private final String CLASS_PREFIX = "StorageBackupMTStateTest";
  private String dbURL;
  private File backupDir;
  private volatile boolean stop = false;

  @Test
  @Ignore
  public void testRun() throws Exception {
    var buildDirectory = System.getProperty("buildDirectory", ".");
    var dbDirectory =
        buildDirectory + File.separator + StorageBackupMTStateTest.class.getSimpleName();

    System.out.println("Clean up old data");

    FileUtils.deleteRecursively(new File(dbDirectory));

    final var backedUpDbDirectory =
        buildDirectory + File.separator + StorageBackupMTStateTest.class.getSimpleName() + "BackUp";
    FileUtils.deleteRecursively(new File(backedUpDbDirectory));

    backupDir =
        new File(buildDirectory, StorageBackupMTStateTest.class.getSimpleName() + "BackupDir");
    FileUtils.deleteRecursively(backupDir);

    if (!backupDir.exists()) {
      Assert.assertTrue(backupDir.mkdirs());
    }

    dbURL = "disk:" + dbDirectory;

    System.out.println("Create database");
    try (var youTrackDb = YourTracks.embedded(dbDirectory)) {
      if (youTrackDb.exists(StorageBackupMTStateTest.class.getSimpleName())) {
        youTrackDb.drop(StorageBackupMTStateTest.class.getSimpleName());
      }

      youTrackDb.create(StorageBackupMTStateTest.class.getSimpleName(), DatabaseType.DISK, "admin",
          "admin", "admin");

      try (var databaseDocumentTx = youTrackDb.open(StorageBackupMTStateTest.class.getSimpleName(),
          "admin", "admin")) {
        System.out.println("Create schema");
        final var schema = databaseDocumentTx.getSchema();

        for (var i = 0; i < 3; i++) {
          createClass(schema);
        }
      }

      try (var pool = youTrackDb.cachedPool(StorageBackupMTStateTest.class.getSimpleName(), "admin",
          "admin")) {
        System.out.println("Start data modification");
        final var executor = Executors.newFixedThreadPool(5);
        final var backupExecutor = Executors.newSingleThreadScheduledExecutor();
        final var classCreatorExecutor =
            Executors.newSingleThreadScheduledExecutor();
        final var classDeleterExecutor =
            Executors.newSingleThreadScheduledExecutor();

        classDeleterExecutor.scheduleWithFixedDelay(new ClassDeleter(pool), 10, 10,
            TimeUnit.MINUTES);
        backupExecutor.scheduleWithFixedDelay(new IncrementalBackupThread(pool), 5, 5,
            TimeUnit.MINUTES);
        classCreatorExecutor.scheduleWithFixedDelay(new ClassAdder(pool), 7, 5, TimeUnit.MINUTES);

        List<Future<Void>> futures = new ArrayList<Future<Void>>();

        futures.add(executor.submit(new NonTxInserter(pool)));
        futures.add(executor.submit(new NonTxInserter(pool)));
        futures.add(executor.submit(new TxInserter(pool)));
        futures.add(executor.submit(new TxInserter(pool)));
        futures.add(executor.submit(new RecordsDeleter(pool)));

        var k = 0;
        while (k < 180) {
          Thread.sleep(30 * 1000);
          k++;

          System.out.println(k * 0.5 + " minutes...");
        }

        stop = true;

        System.out.println("Stop backup");
        backupExecutor.shutdown();

        System.out.println("Stop class creation/deletion");
        classCreatorExecutor.shutdown();
        classDeleterExecutor.shutdown();

        backupExecutor.awaitTermination(15, TimeUnit.MINUTES);
        classCreatorExecutor.awaitTermination(15, TimeUnit.MINUTES);
        classDeleterExecutor.awaitTermination(15, TimeUnit.MINUTES);

        System.out.println("Stop data threads");

        for (var future : futures) {
          future.get();
        }

        System.out.println("All threads are stopped");

        System.out.println("Final incremental  backup");
      }

      try (var databaseDocumentTx = youTrackDb.open(StorageBackupMTStateTest.class.getSimpleName(),
          "admin", "admin")) {
        databaseDocumentTx.incrementalBackup(backupDir.toPath());
      }

      System.out.println("Create backup database");
      youTrackDb.restore(StorageBackupMTStateTest.class.getSimpleName() + "Restored", "admin",
          "admin",
          backupDir.getAbsolutePath(), YouTrackDBConfig.defaultConfig());

      try (var backedUpDb = (DatabaseSessionEmbedded) youTrackDb.open(
          StorageBackupMTStateTest.class.getSimpleName() + "Restored", "admin", "admin")) {
        try (var databaseDocumentTx = (DatabaseSessionEmbedded) youTrackDb.open(
            StorageBackupMTStateTest.class.getSimpleName(), "admin", "admin")) {

          final var compare =
              new DatabaseCompare(
                  databaseDocumentTx,
                  backedUpDb,
                  System.out::println);

          Assert.assertTrue(compare.compare());

          System.out.println("Drop databases and backup directory");
        }
      }

      youTrackDb.drop(StorageBackupMTStateTest.class.getSimpleName());
      youTrackDb.drop(StorageBackupMTStateTest.class.getSimpleName() + "Restored");
    }

    FileUtils.deleteRecursively(backupDir);
  }

  private void createClass(Schema schema) {
    var cls = schema.createClass(CLASS_PREFIX + classCounter.getAndIncrement());

    cls.createProperty("id", PropertyType.LONG);
    cls.createProperty("intValue", PropertyType.INTEGER);
    cls.createProperty("stringValue", PropertyType.STRING);
    cls.createProperty("linkedDocuments", PropertyType.LINKBAG);

    cls.createIndex(cls.getName() + "IdIndex", SchemaClass.INDEX_TYPE.UNIQUE, "id");
    cls.createIndex(
        cls.getName() + "IntValueIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intValue");

    classInstancesCounters.put(cls.getName(), new AtomicInteger());

    System.out.println("Class " + cls.getName() + " is added");

  }

  private final class NonTxInserter extends Inserter {

    private final SessionPool<DatabaseSession> pool;

    private NonTxInserter(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {
      while (!stop) {
        while (true) {
          var db = (DatabaseSessionEmbedded) pool.acquire();
          try {
            flowLock.acquireReadLock();
            try {
              insertRecord(db);
              break;
            } finally {
              flowLock.releaseReadLock();
            }
          } catch (RecordNotFoundException rne) {
            // retry
          } catch (ConcurrentModificationException cme) {
            // retry
          } catch (ModificationOperationProhibitedException e) {
            System.out.println("Modification prohibited , wait 5s ...");
            Thread.sleep(2000);
            // retry
          } catch (Exception e) {
            e.printStackTrace();
            throw e;
          } finally {
            db.close();
          }
        }
      }

      return null;
    }
  }

  private final class TxInserter extends Inserter {

    private final SessionPool<DatabaseSession> pool;

    private TxInserter(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {

      while (!stop) {
        while (true) {
          try (var db = (DatabaseSessionEmbedded) pool.acquire()) {
            flowLock.acquireReadLock();
            try {
              db.begin();
              insertRecord(db);
              db.commit();
              break;
            } finally {
              flowLock.releaseReadLock();
            }
          } catch (RecordNotFoundException rne) {
            // retry
          } catch (ConcurrentModificationException cme) {
            // retry
          } catch (ModificationOperationProhibitedException e) {
            System.out.println("Modification prohibited , wait 5s ...");
            Thread.sleep(2000);
            // retry
          } catch (Exception e) {
            e.printStackTrace();
            throw e;
          }
        }
      }

      return null;
    }
  }

  private abstract class Inserter implements Callable<Void> {

    protected final Random random = new Random();

    protected void insertRecord(DatabaseSessionInternal db) {
      final int docId;
      final var classes = classCounter.get();

      String className;
      AtomicInteger classCounter;

      do {
        className = CLASS_PREFIX + random.nextInt(classes);
        classCounter = classInstancesCounters.get(className);
      } while (classCounter == null);

      final var doc = ((EntityImpl) db.newEntity(className));
      docId = classCounter.getAndIncrement();

      doc.setProperty("id", docId);
      doc.setProperty("stringValue", "value");
      doc.setProperty("intValue", random.nextInt(1024));

      String linkedClassName;
      AtomicInteger linkedClassCounter = null;

      do {
        linkedClassName = CLASS_PREFIX + random.nextInt(classes);

        if (linkedClassName.equalsIgnoreCase(className)) {
          continue;
        }

        linkedClassCounter = classInstancesCounters.get(linkedClassName);
      } while (linkedClassCounter == null);

      var linkedDocuments = new LinkBag(db);

      var linkedClassCount = db.countClass(linkedClassName);
      long tCount = 0;

      while (linkedDocuments.size() < 5 && linkedDocuments.size() < linkedClassCount) {
        var docs =
            db.query(
                "select * from "
                    + linkedClassName
                    + " where id="
                    + random.nextInt(linkedClassCounter.get()));

        if (docs.hasNext()) {
          linkedDocuments.add(docs.next().getIdentity());
        }

        tCount++;

        if (tCount % 10 == 0) {
          linkedClassCount = db.countClass(linkedClassName);
        }
      }

      doc.setProperty("linkedDocuments", linkedDocuments);

      if (docId % 10000 == 0) {
        System.out.println(docId + " documents of class " + className + " were inserted");
      }
    }
  }

  private final class IncrementalBackupThread implements Runnable {

    private final SessionPool<DatabaseSession> pool;

    private IncrementalBackupThread(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public void run() {
      try (var db = pool.acquire()) {
        flowLock.acquireReadLock();
        try {
          System.out.println("Start backup");
          db.incrementalBackup(backupDir.toPath());
          System.out.println("End backup");
        } finally {
          flowLock.releaseReadLock();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private final class ClassAdder implements Runnable {

    private final SessionPool<DatabaseSession> pool;

    private ClassAdder(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public void run() {
      try (var databaseDocumentTx = pool.acquire()) {
        flowLock.acquireReadLock();
        try {
          var schema = databaseDocumentTx.getSchema();
          createClass(schema);
        } finally {
          flowLock.releaseReadLock();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private final class RecordsDeleter implements Callable<Void> {

    private final Random random = new Random();
    private final SessionPool<DatabaseSession> pool;

    private RecordsDeleter(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {
      var counter = 0;
      while (!stop) {
        while (true) {
          try (var databaseDocumentTx = (DatabaseSessionEmbedded) pool.acquire()) {
            flowLock.acquireReadLock();
            try {
              final var classes = classCounter.get();

              String className;
              AtomicInteger classCounter;

              long countClasses;
              do {
                className = CLASS_PREFIX + random.nextInt(classes);
                classCounter = classInstancesCounters.get(className);

                if (classCounter != null) {
                  countClasses = databaseDocumentTx.countClass(className);
                } else {
                  countClasses = 0;
                }
              } while (classCounter == null || countClasses == 0);

              var deleted = false;
              do {
                var docs =
                    databaseDocumentTx.query(
                        "select * from "
                            + className
                            + " where id="
                            + random.nextInt(classCounter.get()));

                if (docs.hasNext()) {
                  var document = docs.next();
                  databaseDocumentTx.delete(document.asEntity());
                  deleted = true;
                }
              } while (!deleted);

              counter++;

              if (counter % 1000 == 0) {
                System.out.println(counter + " documents are deleted");
                System.out.println("Pause for 1 second...");
                Thread.sleep(1000);
              }

              break;
            } finally {
              flowLock.releaseReadLock();
            }
          } catch (ModificationOperationProhibitedException mope) {
            System.out.println("Modification was prohibited ... wait 3s.");
            Thread.sleep(3 * 1000);
          } catch (RecordNotFoundException rnfe) {
            // retry
          } catch (ConcurrentModificationException cme) {
            // retry
          } catch (RuntimeException e) {
            e.printStackTrace();
            throw e;
          }
        }
      }

      return null;
    }
  }

  private final class ClassDeleter implements Runnable {

    private final Random random = new Random();
    private final SessionPool<DatabaseSession> pool;

    private ClassDeleter(SessionPool<DatabaseSession> pool) {
      this.pool = pool;
    }

    @Override
    public void run() {
      try (var db = pool.acquire()) {
        flowLock.acquireWriteLock();
        try {
          final var schema = db.getSchema();
          final var classes = classCounter.get();

          String className;
          AtomicInteger classCounter;

          do {
            className = CLASS_PREFIX + random.nextInt(classes);
            classCounter = classInstancesCounters.get(className);
          } while (classCounter == null);

          schema.dropClass(className);
          classInstancesCounters.remove(className);
          System.out.println("Class " + className + " was deleted");

        } catch (RuntimeException e) {
          e.printStackTrace();
          throw e;
        } finally {
          flowLock.releaseWriteLock();
        }
      }
    }
  }
}
