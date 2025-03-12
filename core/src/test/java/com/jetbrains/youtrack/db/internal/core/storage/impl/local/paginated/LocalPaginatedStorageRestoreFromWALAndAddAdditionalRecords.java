package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import org.junit.Ignore;
import org.junit.Test;

/**
 * @since 18.06.13
 */
public class LocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords {

  private static File buildDir;
  private DatabaseSessionInternal testDocumentTx;
  private DatabaseSessionInternal baseDocumentTx;
  private final ExecutorService executorService = Executors.newCachedThreadPool();

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_COMPRESSION_METHOD.setValue("nothing");
    GlobalConfiguration.FILE_LOCK.setValue(false);

    var buildDirectory = System.getProperty("buildDirectory", ".");
    buildDirectory += "/localPaginatedStorageRestoreFromWALAndAddAdditionalRecords";

    buildDir = new File(buildDirectory);
    if (buildDir.exists()) {
      buildDir.delete();
    }

    buildDir.mkdir();
  }

  @AfterClass
  public static void afterClass() throws IOException {
    //    Files.delete(buildDir.toPath());
    FileUtils.deleteRecursively(buildDir);
  }

  @Before
  public void beforeMethod() throws IOException {
    FileUtils.deleteRecursively(buildDir);

    baseDocumentTx =
        new DatabaseDocumentTx(
            "plocal:"
                + buildDir.getAbsolutePath()
                + "/baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords");
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
  @Ignore
  public void testRestoreAndAddNewItems() throws Exception {
    List<Future<Void>> futures = new ArrayList<Future<Void>>();

    var random = new Random();

    var seeds = new long[5];
    for (var i = 0; i < 5; i++) {
      seeds[i] = random.nextLong();
      System.out.println("Seed [" + i + "] = " + seeds[i]);
    }

    for (var seed : seeds) {
      futures.add(executorService.submit(new DataPropagationTask(seed)));
    }

    for (var future : futures) {
      future.get();
    }

    futures.clear();

    Thread.sleep(1500);
    copyDataFromTestWithoutClose();
    var storage = baseDocumentTx.getStorage();
    baseDocumentTx.close();
    storage.close(baseDocumentTx);

    testDocumentTx =
        new DatabaseDocumentTx(
            "plocal:"
                + buildDir.getAbsolutePath()
                + "/testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords");
    testDocumentTx.open("admin", "admin");
    testDocumentTx.close();

    var dataAddSeed = random.nextLong();
    System.out.println("Data add seed = " + dataAddSeed);
    for (var i = 0; i < 1; i++) {
      futures.add(executorService.submit(new DataPropagationTask(dataAddSeed)));
    }

    for (var future : futures) {
      future.get();
    }

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
    final var testStoragePath = Paths.get(baseDocumentTx.getURL().substring("plocal:".length()));
    var buildPath = Paths.get(buildDir.toURI());

    final var copyTo =
        buildPath.resolve("testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords");

    Files.copy(testStoragePath, copyTo);

    Files.walkFileTree(
        testStoragePath,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            var fileToCopy = copyTo.resolve(testStoragePath.relativize(file));
            if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.wmr")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.wmr");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.0.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.0.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.1.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.1.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.2.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.2.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.3.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.3.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.4.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.4.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.5.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.5.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.6.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.6.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.7.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.7.wal");
            } else if (fileToCopy.endsWith(
                "baseLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.8.wal")) {
              fileToCopy =
                  fileToCopy
                      .getParent()
                      .resolve(
                          "testLocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords.8.wal");
            }

            if (fileToCopy.endsWith("dirty.fl")) {
              return FileVisitResult.CONTINUE;
            }

            Files.copy(file, fileToCopy);

            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void createSchema(DatabaseSessionInternal session) {
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

    private final DatabaseSessionInternal baseDB;
    private DatabaseSessionInternal testDB;
    private final long seed;

    public DataPropagationTask(long seed) {
      this.seed = seed;

      baseDB = new DatabaseDocumentTx(baseDocumentTx.getURL());
      baseDB.open("admin", "admin");

      if (testDocumentTx != null) {
        testDB = new DatabaseDocumentTx(testDocumentTx.getURL());
        testDB.open("admin", "admin");
      }
    }

    @Override
    public Void call() throws Exception {

      var random = new Random(seed);
      try {
        List<RID> testTwoList = new ArrayList<RID>();
        List<RID> firstDocs = new ArrayList<RID>();

        var classOne = baseDB.getMetadata().getSchema().getClass("TestOne");
        var classTwo = baseDB.getMetadata().getSchema().getClass("TestTwo");

        for (var i = 0; i < 10000; i++) {
          var docOne = ((EntityImpl) baseDB.newEntity(classOne));
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

          saveDoc(docOne);

          firstDocs.add(docOne.getIdentity());

          if (random.nextBoolean()) {
            var docTwo = ((EntityImpl) baseDB.newEntity(classTwo));

            List<String> stringList = new ArrayList<String>();

            for (var n = 0; n < 5; n++) {
              stringList.add("strnd" + random.nextInt());
            }

            docTwo.field("stringList", stringList);
            saveDoc(docTwo);
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

            saveDoc(docOne);
          }

          var deleteDoc = random.nextDouble() <= 0.2;
          if (deleteDoc) {
            var rid = firstDocs.remove(random.nextInt(firstDocs.size()));

            deleteDoc(rid);
          }
        }
      } finally {
        baseDB.close();
        if (testDB != null) {
          testDB.close();
        }
      }

      return null;
    }

    private void saveDoc(EntityImpl document) {
      var testDoc = ((EntityImpl) baseDB.newEntity());
      var propertyNames = document.getPropertyNames();

      for (var propertyName : propertyNames) {
        testDoc.setProperty(propertyName, document.getProperty(propertyName));
      }

      if (testDB != null) {
        Assert.assertEquals(testDoc.getIdentity(), document.getIdentity());
      }
    }

    private void deleteDoc(RID rid) {
      var record = baseDB.load(rid);
      baseDB.delete(record);
      if (testDB != null) {
        Assert.assertNotNull(testDB.load(rid));
        record = testDB.load(rid);
        testDB.delete(record);
        Assert.assertNull(testDB.load(rid));
      }
    }
  }
}
