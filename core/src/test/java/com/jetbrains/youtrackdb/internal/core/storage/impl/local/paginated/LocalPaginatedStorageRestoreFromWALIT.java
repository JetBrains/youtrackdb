package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for restoring local paginated storage from write-ahead log.
 *
 * @since 29.05.13
 */
@Category(SequentialTest.class)
public class LocalPaginatedStorageRestoreFromWALIT {

  private static YouTrackDBImpl youTrackDB;
  private static File buildDir;
  private DatabaseSessionEmbedded testDocumentTx;
  private DatabaseSessionEmbedded baseDocumentTx;
  private final ExecutorService executorService = Executors.newCachedThreadPool();
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger retries = new AtomicInteger();

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
    GlobalConfiguration.FILE_LOCK.setValue(false);
    GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.setValue(100000000);

    var buildDirectory = System.getProperty("buildDirectory", "./target");
    buildDirectory += "/localPaginatedStorageRestoreFromWAL";

    buildDir = new File(buildDirectory);
    if (buildDir.exists()) {
      buildDir.delete();
    }

    buildDir.mkdir();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDir.getAbsolutePath());
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.close();
    buildDir.delete();
  }

  @Before
  public void beforeMethod() {
    if (youTrackDB.exists("baseLocalPaginatedStorageRestoreFromWAL")) {
      youTrackDB.drop("baseLocalPaginatedStorageRestoreFromWAL");
    }

    youTrackDB.create("baseLocalPaginatedStorageRestoreFromWAL", DatabaseType.DISK,
        YouTrackDBConfig.defaultConfig(),
        "admin", "admin", "admin");

    baseDocumentTx =
        (DatabaseSessionEmbedded) youTrackDB.open("baseLocalPaginatedStorageRestoreFromWAL",
            "admin", "admin");
    createSchema(baseDocumentTx);
  }

  @Test
  public void testSimpleRestore() throws Exception {
    baseDocumentTx.freeze();
    baseDocumentTx.release();

    List<Future<Void>> futures = new ArrayList<>();

    for (var i = 0; i < 8; i++) {
      futures.add(executorService.submit(new DataPropagationTask()));
    }

    for (var future : futures) {
      future.get();
    }

    // Sanity gate: ensure the concurrent workload actually produced meaningful
    // WAL traffic. Without this a regression that made retryable exceptions
    // fire on every iteration would leave both databases near-empty, and the
    // downstream DatabaseCompare would trivially pass. Threshold is a small
    // fraction of the 8 * 5000 = 40000 iterations to stay far from observed
    // steady-state (~99% commit rate) yet catch pathological runs.
    var totalCommits = commits.get();
    var totalRetries = retries.get();
    Assert.assertTrue(
        "Expected most iterations to commit; got " + totalCommits + " commits / "
            + totalRetries + " retries — suggests a regression making retryable "
            + "exceptions ubiquitous",
        totalCommits > 20_000);

    Thread.sleep(1500);
    WalTestUtils.withWalProtection(
        baseDocumentTx, this::copyDataFromTestWithoutClose);

    var baseStorage = (DiskStorage) baseDocumentTx.getStorage();
    baseDocumentTx.close();
    baseStorage.close(baseDocumentTx);

    testDocumentTx = (DatabaseSessionEmbedded) youTrackDB.open(
        "testLocalPaginatedStorageRestoreFromWAL", "admin", "admin");
    testDocumentTx.close();

    testDocumentTx = (DatabaseSessionEmbedded) youTrackDB.open(
        "testLocalPaginatedStorageRestoreFromWAL", "admin", "admin");
    baseDocumentTx = (DatabaseSessionEmbedded) youTrackDB.open(
        "baseLocalPaginatedStorageRestoreFromWAL", "admin", "admin");

    var databaseCompare =
        new DatabaseCompare(testDocumentTx, baseDocumentTx, System.out::println);
    databaseCompare.setCompareIndexMetadata(true);

    Assert.assertTrue(databaseCompare.compare());
    testDocumentTx.close();
    baseDocumentTx.close();
  }

  private void copyDataFromTestWithoutClose() throws Exception {
    final var testStoragePath = Path.of(baseDocumentTx.getURL().substring("disk:".length()))
        .toAbsolutePath().toString();
    final var copyTo =
        Path.of(
            buildDir.getAbsolutePath() + File.separator + "testLocalPaginatedStorageRestoreFromWAL")
            .toAbsolutePath().toString();

    FileUtils.deleteRecursively(new File(copyTo));

    final var testStorageDir = new File(testStoragePath);
    final var copyToDir = new File(copyTo);

    Assert.assertFalse(copyToDir.exists());
    Assert.assertTrue(copyToDir.mkdir());

    var storageFiles = testStorageDir.listFiles();
    Assert.assertNotNull(storageFiles);

    for (var storageFile : storageFiles) {
      var copyToPath = copyToDir.getAbsolutePath() + File.separator + storageFile.getName();
      if (storageFile.getName().equals("dirty.fl")) {
        continue;
      }

      try {
        copyFile(storageFile.getAbsolutePath(), copyToPath);
      } catch (FileNotFoundException e) {
        // Double-write log files (.dwl) can be deleted by background checkpoint
        // operations between listFiles() and copyFile(). The WAL protection in
        // WalTestUtils only prevents WAL segment truncation, not DWL file deletion.
        // Skipping deleted DWL files is safe because they are not needed for
        // WAL-based restore — they protect against torn page writes during normal
        // operation, and the restore process replays the WAL from scratch.
        if (storageFile.getName().endsWith(".dwl")) {
          continue;
        }
        throw e;
      }
    }
  }

  private static void createSchema(DatabaseSessionEmbedded session) {
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

      try (var db = (DatabaseSessionEmbedded) youTrackDB.open(baseDocumentTx.getDatabaseName(),
          "admin", "admin")) {
        List<RID> testTwoList = new ArrayList<>();
        List<RID> firstDocs = new ArrayList<>();

        var classOne = db.getMetadata().getSchema().getClass("TestOne");
        var classTwo = db.getMetadata().getSchema().getClass("TestTwo");

        for (var i = 0; i < 5000; i++) {
          // Snapshot lists before each transaction so we can restore on conflict
          var firstDocsSnapshot = new ArrayList<>(firstDocs);
          var testTwoSnapshot = new ArrayList<>(testTwoList);
          try {
            db.executeInTx(transaction -> {
              var entityOne = ((EntityImpl) transaction.newEntity(classOne));
              entityOne.setProperty("intProp", random.nextInt());

              var stringData = new byte[256];
              random.nextBytes(stringData);
              var stringProp = new String(stringData);

              entityOne.setProperty("stringProp", stringProp);

              Set<String> stringSet = new HashSet<>();
              for (var n = 0; n < 5; n++) {
                stringSet.add("str" + random.nextInt());
              }
              entityOne.newEmbeddedSet("stringSet", stringSet);

              firstDocs.add(entityOne.getIdentity());

              if (random.nextBoolean()) {
                var docTwo = ((EntityImpl) db.newEntity(classTwo));

                List<String> stringList = new ArrayList<>();

                for (var n = 0; n < 5; n++) {
                  stringList.add("strnd" + random.nextInt());
                }

                docTwo.newEmbeddedList("stringList", stringList);

                testTwoList.add(docTwo.getIdentity());
              }

              if (!testTwoList.isEmpty()) {
                var startIndex = random.nextInt(testTwoList.size());
                var endIndex =
                    random.nextInt(testTwoList.size() - startIndex) + startIndex;

                Map<String, RID> linkMap = new HashMap<>();

                for (var n = startIndex; n < endIndex; n++) {
                  var docTwoRid = testTwoList.get(n);
                  linkMap.put(docTwoRid.toString(), docTwoRid);
                }

                entityOne.newLinkMap("linkMap", linkMap);
              }

              var deleteEntity = random.nextDouble() <= 0.2;
              if (deleteEntity) {
                var rid = firstDocs.remove(random.nextInt(firstDocs.size()));
                var entityToDelete = db.load(rid);
                db.delete(entityToDelete);
              }
            });
            commits.incrementAndGet();
          } catch (NeedRetryException | RecordNotFoundException e) {
            // Retryable commit-time exceptions under 8-thread snapshot isolation:
            //   * NeedRetryException subclasses (ConcurrentModification,
            //     LinksConsistency, ConcurrentCreate, …) — framework contract
            //     is to retry, see NeedRetryException javadoc.
            //   * RecordNotFoundException — a target record is not visible in
            //     the current snapshot during commit-time link-consistency
            //     processing; may succeed on re-execution.
            // The transaction was rolled back by executeInTx's finally block,
            // so restore the thread-local RID lists to their pre-transaction
            // state. The outer sanity assertion in testSimpleRestore guards
            // against a pathological case where every iteration retries.
            retries.incrementAndGet();
            firstDocs.clear();
            firstDocs.addAll(firstDocsSnapshot);
            testTwoList.clear();
            testTwoList.addAll(testTwoSnapshot);
          }
        }
      }

      return null;
    }
  }
}
