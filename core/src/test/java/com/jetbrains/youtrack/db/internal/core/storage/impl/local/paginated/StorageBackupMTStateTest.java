package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.PartitionedDatabasePool;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(3);
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

  private volatile PartitionedDatabasePool pool;

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

    dbURL = "plocal:" + dbDirectory;

    System.out.println("Create database");
    DatabaseSessionInternal databaseDocumentTx = new DatabaseDocumentTx(dbURL);
    databaseDocumentTx.create();

    System.out.println("Create schema");
    final Schema schema = databaseDocumentTx.getMetadata().getSchema();

    for (var i = 0; i < 3; i++) {
      createClass(schema, databaseDocumentTx);
    }

    databaseDocumentTx.close();

    pool = new PartitionedDatabasePool(dbURL, "admin", "admin");

    System.out.println("Start data modification");
    final var executor = Executors.newFixedThreadPool(5);
    final var backupExecutor = Executors.newSingleThreadScheduledExecutor();
    final var classCreatorExecutor =
        Executors.newSingleThreadScheduledExecutor();
    final var classDeleterExecutor =
        Executors.newSingleThreadScheduledExecutor();

    classDeleterExecutor.scheduleWithFixedDelay(new ClassDeleter(), 10, 10, TimeUnit.MINUTES);
    backupExecutor.scheduleWithFixedDelay(new IncrementalBackupThread(), 5, 5, TimeUnit.MINUTES);
    classCreatorExecutor.scheduleWithFixedDelay(new ClassAdder(), 7, 5, TimeUnit.MINUTES);

    List<Future<Void>> futures = new ArrayList<Future<Void>>();

    futures.add(executor.submit(new NonTxInserter()));
    futures.add(executor.submit(new NonTxInserter()));
    futures.add(executor.submit(new TxInserter()));
    futures.add(executor.submit(new TxInserter()));
    futures.add(executor.submit(new RecordsDeleter()));

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

    pool.close();

    System.out.println("Final incremental  backup");
    databaseDocumentTx = new DatabaseDocumentTx(dbURL);
    databaseDocumentTx.open("admin", "admin");
    databaseDocumentTx.incrementalBackup(backupDir.toPath());

    var storage = databaseDocumentTx.getStorage();
    databaseDocumentTx.close();

    storage.shutdown();

    System.out.println("Create backup database");
    final DatabaseSessionInternal backedUpDb =
        new DatabaseDocumentTx("plocal:" + backedUpDbDirectory);
    backedUpDb.create(backupDir.getAbsolutePath());

    final var backupStorage = backedUpDb.getStorage();
    backedUpDb.close();

    backupStorage.shutdown();

    System.out.println("Compare databases");
    databaseDocumentTx.open("admin", "admin");
    backedUpDb.open("admin", "admin");

    final var compare =
        new DatabaseCompare(
            databaseDocumentTx,
            backedUpDb,
            new CommandOutputListener() {
              @Override
              public void onMessage(String iText) {
                System.out.println(iText);
              }
            });

    Assert.assertTrue(compare.compare());

    System.out.println("Drop databases and backup directory");

    databaseDocumentTx.open("admin", "admin");
    databaseDocumentTx.drop();

    backedUpDb.open("admin", "admin");
    backedUpDb.drop();

    FileUtils.deleteRecursively(backupDir);
  }

  private SchemaClass createClass(Schema schema, DatabaseSession session) {
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

    return cls;
  }

  private final class NonTxInserter extends Inserter {

    @Override
    public Void call() throws Exception {
      while (!stop) {
        while (true) {
          DatabaseSessionInternal db = pool.acquire();
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

    @Override
    public Void call() throws Exception {

      while (!stop) {
        while (true) {
          DatabaseSessionInternal db = pool.acquire();
          try {
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
          } finally {
            db.close();
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

      var linkedDocuments = new RidBag(db);

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

    @Override
    public void run() {
      DatabaseSessionInternal db = new DatabaseDocumentTx(dbURL);
      db.open("admin", "admin");
      try {
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
      } finally {
        db.close();
      }
    }
  }

  private final class ClassAdder implements Runnable {

    @Override
    public void run() {
      DatabaseSessionInternal databaseDocumentTx = new DatabaseDocumentTx(dbURL);
      databaseDocumentTx.open("admin", "admin");
      try {
        flowLock.acquireReadLock();
        try {
          Schema schema = databaseDocumentTx.getMetadata().getSchema();
          createClass(schema, databaseDocumentTx);
        } finally {
          flowLock.releaseReadLock();
        }
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        databaseDocumentTx.close();
      }
    }
  }

  private final class RecordsDeleter implements Callable<Void> {

    private final Random random = new Random();

    @Override
    public Void call() throws Exception {
      var counter = 0;
      while (!stop) {
        while (true) {
          DatabaseSessionInternal databaseDocumentTx = pool.acquire();
          try {
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
          } finally {
            databaseDocumentTx.close();
          }
        }
      }

      return null;
    }
  }

  private final class ClassDeleter implements Runnable {

    private final Random random = new Random();

    @Override
    public void run() {
      var db = pool.acquire();
      try {
        flowLock.acquireWriteLock();
        try {
          final Schema schema = db.getMetadata().getSchema();
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
      } finally {
        db.close();
      }
    }
  }
}
