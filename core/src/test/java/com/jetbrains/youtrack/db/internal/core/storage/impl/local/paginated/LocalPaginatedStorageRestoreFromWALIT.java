package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @since 29.05.13
 */
public class LocalPaginatedStorageRestoreFromWALIT {

  private static File buildDir;
  private DatabaseSessionInternal testDocumentTx;
  private DatabaseSessionInternal baseDocumentTx;
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

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_COMPRESSION_METHOD.setValue("nothing");
    GlobalConfiguration.FILE_LOCK.setValue(false);
    GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.setValue(100000000);

    var buildDirectory = System.getProperty("buildDirectory", ".");
    buildDirectory += "/localPaginatedStorageRestoreFromWAL";

    buildDir = new File(buildDirectory);
    if (buildDir.exists()) {
      buildDir.delete();
    }

    buildDir.mkdir();
  }

  @AfterClass
  public static void afterClass() {
    buildDir.delete();
  }

  @Before
  public void beforeMethod() {
    baseDocumentTx =
        new DatabaseDocumentTx(
            "plocal:" + buildDir.getAbsolutePath() + "/baseLocalPaginatedStorageRestoreFromWAL");
    if (baseDocumentTx.exists()) {
      baseDocumentTx.open("admin", "admin");
      baseDocumentTx.drop();
    }

    baseDocumentTx.create();

    createSchema(baseDocumentTx);
  }

  @After
  public void afterMethod() {
    testDocumentTx.open("admin", "admin");
    testDocumentTx.drop();

    baseDocumentTx.open("admin", "admin");
    baseDocumentTx.drop();
  }

  @Test
  public void testSimpleRestore() throws Exception {
    List<Future<Void>> futures = new ArrayList<Future<Void>>();

    for (var i = 0; i < 8; i++) {
      futures.add(executorService.submit(new DataPropagationTask()));
    }

    for (var future : futures) {
      future.get();
    }

    Thread.sleep(1500);
    copyDataFromTestWithoutClose();
    var baseStorage = baseDocumentTx.getStorage();
    baseDocumentTx.close();
    baseStorage.close(baseDocumentTx);

    testDocumentTx =
        new DatabaseDocumentTx(
            "plocal:" + buildDir.getAbsolutePath() + "/testLocalPaginatedStorageRestoreFromWAL");
    testDocumentTx.open("admin", "admin");
    testDocumentTx.close();

    testDocumentTx =
        new DatabaseDocumentTx(
            "plocal:" + buildDir.getAbsolutePath() + "/testLocalPaginatedStorageRestoreFromWAL");
    testDocumentTx.open("admin", "admin");
    baseDocumentTx =
        new DatabaseDocumentTx(
            "plocal:" + buildDir.getAbsolutePath() + "/baseLocalPaginatedStorageRestoreFromWAL");
    baseDocumentTx.open("admin", "admin");
    var databaseCompare =
        new DatabaseCompare(testDocumentTx, baseDocumentTx, System.out::println);
    databaseCompare.setCompareIndexMetadata(true);

    Assert.assertTrue(databaseCompare.compare());
    testDocumentTx.close();
    baseDocumentTx.close();
  }

  private void copyDataFromTestWithoutClose() throws Exception {
    final var testStoragePath = baseDocumentTx.getURL().substring("plocal:".length());
    final var copyTo =
        buildDir.getAbsolutePath() + File.separator + "testLocalPaginatedStorageRestoreFromWAL";
    FileUtils.deleteRecursively(new File(copyTo));

    final var testStorageDir = new File(testStoragePath);
    final var copyToDir = new File(copyTo);

    Assert.assertFalse(copyToDir.exists());
    Assert.assertTrue(copyToDir.mkdir());

    var storageFiles = testStorageDir.listFiles();
    Assert.assertNotNull(storageFiles);

    for (var storageFile : storageFiles) {
      String copyToPath;
      if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.wmr")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.wmr";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.0.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.0.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.1.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.1.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.2.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.2.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.3.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.3.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.4.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.4.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.5.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.5.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.6.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.6.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.7.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.7.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.8.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.8.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.9.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.9.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.10.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.10.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.11.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.11.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.12.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.12.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.13.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.13.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.14.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.14.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.15.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.15.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.16.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.16.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.17.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.17.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.18.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.18.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.19.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.19.wal";
      } else if (storageFile.getName().equals("baseLocalPaginatedStorageRestoreFromWAL.20.wal")) {
        copyToPath =
            copyToDir.getAbsolutePath()
                + File.separator
                + "testLocalPaginatedStorageRestoreFromWAL.20.wal";
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

    @Override
    public Void call() throws Exception {

      var random = new Random();

      final DatabaseSessionInternal db = new DatabaseDocumentTx(baseDocumentTx.getURL());
      db.open("admin", "admin");
      try {
        List<RID> testTwoList = new ArrayList<RID>();
        List<RID> firstDocs = new ArrayList<RID>();

        var classOne = db.getMetadata().getSchema().getClass("TestOne");
        var classTwo = db.getMetadata().getSchema().getClass("TestTwo");

        for (var i = 0; i < 5000; i++) {
          var docOne = ((EntityImpl) db.newEntity(classOne));
          docOne.field("intProp", random.nextInt());

          var stringData = new byte[256];
          random.nextBytes(stringData);
          var stringProp = new String(stringData);

          docOne.field("stringProp", stringProp);

          Set<String> stringSet = new HashSet<String>();
          for (var n = 0; n < 5; n++) {
            stringSet.add("str" + random.nextInt());
          }
          docOne.field("stringSet", stringSet);

          firstDocs.add(docOne.getIdentity());

          if (random.nextBoolean()) {
            var docTwo = ((EntityImpl) db.newEntity(classTwo));

            List<String> stringList = new ArrayList<String>();

            for (var n = 0; n < 5; n++) {
              stringList.add("strnd" + random.nextInt());
            }

            docTwo.field("stringList", stringList);

            testTwoList.add(docTwo.getIdentity());
          }

          if (!testTwoList.isEmpty()) {
            var startIndex = random.nextInt(testTwoList.size());
            var endIndex = random.nextInt(testTwoList.size() - startIndex) + startIndex;

            Map<String, RID> linkMap = new HashMap<String, RID>();

            for (var n = startIndex; n < endIndex; n++) {
              var docTwoRid = testTwoList.get(n);
              linkMap.put(docTwoRid.toString(), docTwoRid);
            }

            docOne.field("linkMap", linkMap);

          }

          var deleteDoc = random.nextDouble() <= 0.2;
          if (deleteDoc) {
            var rid = firstDocs.remove(random.nextInt(firstDocs.size()));
            var entityToDelete = db.load(rid);
            db.delete(entityToDelete);
          }
        }
      } finally {
        db.close();
      }

      return null;
    }
  }
}
