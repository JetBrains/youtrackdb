package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @since 14.06.13
 */
public class LocalPaginatedStorageRestoreTx {

  private static YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded testDocumentTx;
  private DatabaseSessionEmbedded baseDocumentTx;
  private File buildDir;

  private final ExecutorService executorService = Executors.newCachedThreadPool();

  private static void copyFile(String from, String to) throws IOException {
    final var fromFile = new File(from);
    var fromInputStream = new FileInputStream(fromFile);
    var fromBufferedStream = new BufferedInputStream(fromInputStream);

    var toOutputStream = new FileOutputStream(to);
    var data = new byte[1024];
    var bytesRead = fromBufferedStream.read(data);
    while (bytesRead > 0) {
      toOutputStream.write(data, 0, bytesRead);
      bytesRead = fromBufferedStream.read(data);
    }

    fromBufferedStream.close();
    toOutputStream.close();
  }

  @Before
  public void beforeClass() {
    GlobalConfiguration.FILE_LOCK.setValue(false);

    var buildDirectory = System.getProperty("buildDirectory", ".");
    buildDirectory += "/localPaginatedStorageRestoreFromTx";

    buildDir = new File(buildDirectory);
    if (buildDir.exists()) {
      buildDir.delete();
    }

    buildDir.mkdir();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDir.getAbsolutePath());

    if (youTrackDB.exists("localPaginatedStorageRestoreFromTx")) {
      youTrackDB.drop("localPaginatedStorageRestoreFromTx");
    }

    youTrackDB.create("localPaginatedStorageRestoreFromTx", DatabaseType.DISK,
        YouTrackDBConfig.defaultConfig(), "admin", "admin", "admin");
    baseDocumentTx =
        youTrackDB.open("localPaginatedStorageRestoreFromTx", "admin",
            "admin");
    createSchema(baseDocumentTx);
  }

  @After
  public void afterMethod() {
    youTrackDB.drop("localPaginatedStorageRestoreFromTx");
    youTrackDB.drop("basePaginatedStorageRestoreFromTx");
  }

  @Test
  @Ignore
  public void testSimpleRestore() throws Exception {
    List<Future<Void>> futures = new ArrayList<Future<Void>>();

    try (var pool = youTrackDB.cachedPool("basePaginatedStorageRestoreFromTx", "admin", "admin")) {
      for (var i = 0; i < 8; i++) {
        futures.add(executorService.submit(new DataPropagationTask(pool)));
      }
    }

    for (var future : futures) {
      future.get();
    }

    Thread.sleep(1500);
    copyDataFromTestWithoutClose();

    testDocumentTx = youTrackDB.open(
        "testLocalPaginatedStorageRestoreFromTx", "admin", "admin");
    var databaseCompare =
        new DatabaseCompare(
            testDocumentTx,
            baseDocumentTx,
            new CommandOutputListener() {
              @Override
              public void onMessage(String text) {
                System.out.println(text);
              }
            });
    databaseCompare.setCompareIndexMetadata(true);

    Assert.assertTrue(databaseCompare.compare());
  }

  private void copyDataFromTestWithoutClose() throws Exception {
    final var testStoragePath = baseDocumentTx.getURL().substring("disk:".length());
    final var copyTo =
        buildDir.getAbsolutePath() + File.separator + "testLocalPaginatedStorageRestoreFromTx";

    final var testStorageDir = new File(testStoragePath);
    final var copyToDir = new File(copyTo);

    Assert.assertFalse(copyToDir.exists());
    Assert.assertTrue(copyToDir.mkdir());

    var storageFiles = testStorageDir.listFiles();
    Assert.assertNotNull(storageFiles);

    for (var storageFile : storageFiles) {
      String copyToPath;
      if (storageFile.getAbsolutePath().endsWith("baseLocalPaginatedStorageRestoreFromTx.wmr")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.wmr";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.0.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.0.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.1.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.1.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.2.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.2.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.3.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.3.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.4.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.4.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.5.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.5.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.6.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.6.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.7.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.7.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.8.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.8.wal";
      } else if (storageFile
          .getAbsolutePath()
          .endsWith("baseLocalPaginatedStorageRestoreFromTx.9.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromTx.9.wal";
      } else {
        copyToPath = copyToDir.getAbsolutePath() + File.separator + storageFile.getName();
      }

      if (storageFile.getName().equals("dirty.fl")) {
        continue;
      }

      copyFile(storageFile.getAbsolutePath(), copyToPath);
    }
  }

  private static void createSchema(DatabaseSessionInternal session) {
    Schema schema = session.getMetadata().getSchema();
    var testOneClass = schema.createClass("TestOne");

    testOneClass.createProperty("intProp", PropertyType.INTEGER);
    testOneClass.createProperty("stringProp", PropertyType.STRING);
    testOneClass.createProperty("stringSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    testOneClass.createProperty("linkMap", PropertyType.LINKMAP);

    var testTwoClass = schema.createClass("TestTwo");

    testTwoClass.createProperty("stringList", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
  }

  public class DataPropagationTask implements Callable<Void> {

    private final SessionPool pool;

    public DataPropagationTask(SessionPool pool) {
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {

      var random = new Random();

      var db = pool.acquire();
      var rollbacksCount = 0;
      try {
        List<RID> secondDocs = new ArrayList<RID>();
        List<RID> firstDocs = new ArrayList<RID>();

        var classOne = db.getSchema().getClass("TestOne");
        var classTwo = db.getSchema().getClass("TestTwo");

        for (var i = 0; i < 20000; i++) {
          try {

            db.executeInTx(transaction -> {
              var docOne = transaction.newEntity(classOne);
              docOne.setProperty("intProp", random.nextInt());

              var stringData = new byte[256];
              random.nextBytes(stringData);
              var stringProp = new String(stringData);

              docOne.setProperty("stringProp", stringProp);

              Set<String> stringSet = new HashSet<String>();
              for (var n = 0; n < 5; n++) {
                stringSet.add("str" + random.nextInt());
              }
              docOne.setProperty("stringSet", stringSet);

              Entity docTwo = null;

              if (random.nextBoolean()) {
                docTwo = transaction.newEntity(classTwo);

                List<String> stringList = new ArrayList<String>();

                for (var n = 0; n < 5; n++) {
                  stringList.add("strnd" + random.nextInt());
                }

                docTwo.setProperty("stringList", stringList);

              }

              if (!secondDocs.isEmpty()) {
                var startIndex = random.nextInt(secondDocs.size());
                var endIndex = random.nextInt(secondDocs.size() - startIndex) + startIndex;

                Map<String, RID> linkMap = new HashMap<String, RID>();

                for (var n = startIndex; n < endIndex; n++) {
                  var docTwoRid = secondDocs.get(n);
                  linkMap.put(docTwoRid.toString(), docTwoRid);
                }

                docOne.setProperty("linkMap", linkMap);

              }

              var deleteIndex = -1;
              if (!firstDocs.isEmpty()) {
                var deleteDoc = random.nextDouble() <= 0.2;

                if (deleteDoc) {
                  deleteIndex = random.nextInt(firstDocs.size());
                  if (deleteIndex >= 0) {
                    var rid = firstDocs.get(deleteIndex);
                    var record = transaction.loadEntity(rid);
                    record.delete();
                  }
                }
              }

              if (!secondDocs.isEmpty() && (random.nextDouble() <= 0.2)) {
                try (var conflictSession = pool.acquire()) {
                  conflictSession.executeInTx(conflictTransaction -> {
                    var conflictEntityTwo = conflictTransaction.loadEntity(secondDocs.getFirst());
                    conflictEntityTwo.setInt("intProp", random.nextInt());
                  });
                }
              }

              if (deleteIndex >= 0) {
                firstDocs.remove(deleteIndex);
              }

              firstDocs.add(docOne.getIdentity());
              if (docTwo != null) {
                secondDocs.add(docTwo.getIdentity());
              }
            });
          } catch (Exception e) {
            rollbacksCount++;
          }
        }
      } finally {
        db.close();
      }

      System.out.println("Rollbacks count " + rollbacksCount);
      return null;
    }
  }
}
