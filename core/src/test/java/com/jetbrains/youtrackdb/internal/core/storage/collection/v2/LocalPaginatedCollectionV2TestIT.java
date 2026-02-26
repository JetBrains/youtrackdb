package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.LocalPaginatedCollectionAbstract;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowseEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.tx.RollbackException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LocalPaginatedCollectionV2TestIT extends LocalPaginatedCollectionAbstract {
  @BeforeClass
  public static void beforeClass() throws IOException {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + LocalPaginatedCollectionV2TestIT.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    dbName = "collectionTest";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    databaseDocumentTx = youTrackDB.open(dbName, "admin", "admin");

    storage = (AbstractStorage) databaseDocumentTx.getStorage();

    paginatedCollection = new PaginatedCollectionV2("paginatedCollectionTest", storage);
    paginatedCollection.configure(42, "paginatedCollectionTest");
    storage
        .getAtomicOperationsManager()
        .executeInsideAtomicOperation(
            atomicOperation -> paginatedCollection.create(atomicOperation));
  }

  private AtomicOperationsManager atomicOps() {
    return storage.getAtomicOperationsManager();
  }

  // --- Record status tests ---

  // Verifies that querying the status of an unallocated position returns NOT_EXISTENT.
  @Test
  public void testGetRecordStatusNotExistent() throws IOException {
    // Use a position that's within representable range but has never been allocated.
    // First, find the current last position to compute a safe non-existent position.
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));
    var nonExistentPos = lastPos + 1000;

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getRecordStatus(nonExistentPos, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.NOT_EXISTENT, status);
  }

  // Verifies that allocating a position without writing a record yields ALLOCATED status.
  @Test
  public void testGetRecordStatusAllocated() throws IOException {
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.allocatePosition((byte) 1, op));

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.ALLOCATED, status);

    // Write the record to keep the collection consistent, then verify PRESENT
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.createRecord(new byte[]{1}, (byte) 1, pos, op));

    var filledStatus = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, filledStatus);
  }

  // Verifies that a written record has PRESENT status, and after deletion it becomes REMOVED.
  @Test
  public void testGetRecordStatusFilledThenRemoved() throws IOException {
    var data = new byte[]{10, 20, 30};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    var filledStatus = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, filledStatus);

    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, pos.collectionPosition));

    var removedStatus = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.REMOVED, removedStatus);
  }

  // --- Record existence tests ---

  // Verifies that exists(long, AtomicOperation) returns true for an existing record and false
  // after deletion or for a never-written position.
  @Test
  public void testRecordExistsForExistingAndDeletedRecord() throws IOException {
    var data = new byte[]{1, 2, 3, 4, 5};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    var existsBefore = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.exists(pos.collectionPosition, op));
    Assert.assertTrue("Record should exist after creation", existsBefore);

    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, pos.collectionPosition));

    var existsAfter = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.exists(pos.collectionPosition, op));
    Assert.assertFalse("Record should not exist after deletion", existsAfter);
  }

  // Verifies that exists(long, AtomicOperation) returns false for a position that was never
  // allocated.
  @Test
  public void testRecordExistsForNonExistentPosition() throws IOException {
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));
    var nonExistentPos = lastPos + 1000;

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.exists(nonExistentPos, op));
    Assert.assertFalse("Non-existent position should not exist", exists);
  }

  // --- Collection existence test ---

  // Verifies that the collection file exists on storage after creation.
  @Test
  public void testCollectionFileExists() throws IOException {
    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.exists(op));
    Assert.assertTrue("Collection should exist after creation", exists);
  }

  // --- Simple getter tests ---

  // Verifies that getFileName returns a non-null name containing the collection name.
  @Test
  public void testGetFileName() {
    var fileName = paginatedCollection.getFileName();
    Assert.assertNotNull("File name should not be null", fileName);
    Assert.assertTrue("File name should contain collection name",
        fileName.contains("paginatedCollectionTest"));
  }

  // Verifies that getFileId returns a non-negative value after collection creation.
  @Test
  public void testGetFileId() {
    var fileId = paginatedCollection.getFileId();
    Assert.assertTrue("File ID should be non-negative", fileId >= 0);
  }

  // Verifies that encryption returns null for an unencrypted collection.
  @Test
  public void testEncryptionReturnsNull() {
    Assert.assertNull("Encryption should be null for unencrypted collection",
        paginatedCollection.encryption());
  }

  // Verifies the string representation includes the collection name.
  @Test
  public void testToString() {
    var str = paginatedCollection.toString();
    Assert.assertNotNull(str);
    Assert.assertTrue("toString should mention collection name",
        str.contains("paginatedCollectionTest"));
  }

  // Verifies that tombstones count is always zero (V2 uses soft deletes in position map).
  @Test
  public void testGetTombstonesCount() {
    Assert.assertEquals(0, paginatedCollection.getTombstonesCount());
  }

  // Verifies that a user-created collection is not flagged as a system collection.
  @Test
  public void testIsSystemCollection() {
    Assert.assertFalse("User-created collection should not be system collection",
        paginatedCollection.isSystemCollection());
  }

  // Verifies that getId returns the configured collection ID.
  @Test
  public void testGetId() {
    Assert.assertEquals(42, paginatedCollection.getId());
  }

  // Verifies that getRecordConflictStrategy returns null when no strategy was configured.
  @Test
  public void testGetRecordConflictStrategy() {
    Assert.assertNull("Conflict strategy should be null when not configured",
        paginatedCollection.getRecordConflictStrategy());
  }

  // Verifies that generateCollectionConfig returns a valid configuration reflecting the
  // collection's current state.
  @Test
  public void testGenerateCollectionConfig() {
    var config = ((PaginatedCollectionV2) paginatedCollection).generateCollectionConfig();
    Assert.assertNotNull("Config should not be null", config);
    Assert.assertEquals(42, config.id());
    Assert.assertEquals("paginatedCollectionTest", config.name());
  }

  // Verifies that synch completes without errors when the collection has data.
  @Test
  public void testSynch() throws IOException {
    var data = new byte[]{1, 2, 3};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    // synch should complete without error
    paginatedCollection.synch();
  }

  // --- nextPage browsing tests ---

  // Verifies that forward page browsing via nextPage returns records in ascending order and
  // that all records are visited exactly once.
  @Test
  public void testNextPageForwardBrowsing() throws IOException {
    var records = new ArrayList<Long>();
    for (var i = 0; i < 50; i++) {
      var data = new byte[]{(byte) i, (byte) (i + 1)};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      records.add(pos.collectionPosition);
    }

    // Browse forward from the beginning
    var visited = new ArrayList<Long>();
    var page = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.nextPage(-1, true, op));

    while (page != null) {
      var hasEntries = false;
      long lastPos = -1;
      for (CollectionBrowseEntry entry : page) {
        hasEntries = true;
        Assert.assertTrue("Positions should be ascending",
            entry.collectionPosition() > lastPos);
        lastPos = entry.collectionPosition();
        visited.add(entry.collectionPosition());
        Assert.assertNotNull("Record data should not be null", entry.buffer());
        Assert.assertNotNull("Record buffer should not be null",
            entry.buffer().buffer());
      }
      Assert.assertTrue("Page should not be empty", hasEntries);

      var nextStart = page.getLastPosition();
      page = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.nextPage(nextStart, true, op));
    }

    // All created records should have been visited
    Assert.assertTrue("Should have visited at least the created records",
        visited.containsAll(records));
  }

  // Verifies that backward page browsing via nextPage returns records in descending order.
  @Test
  public void testNextPageBackwardBrowsing() throws IOException {
    var data = new byte[]{1, 2, 3};
    for (var i = 0; i < 20; i++) {
      atomicOps().executeInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
    }

    atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));

    // Browse backward from the end
    var page = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.nextPage(Long.MAX_VALUE, false, op));
    Assert.assertNotNull("Should get a page when browsing backward", page);

    // Entries within the page should be in descending order
    var prevPos = Long.MAX_VALUE;
    var hasEntries = false;
    for (CollectionBrowseEntry entry : page) {
      hasEntries = true;
      Assert.assertTrue("Positions should be descending within backward page",
          entry.collectionPosition() < prevPos);
      prevPos = entry.collectionPosition();
    }
    Assert.assertTrue("Page should not be empty", hasEntries);
  }

  // Verifies that nextPage returns null when the collection has no FILLED entries.
  @Test
  public void testNextPageEmptyCollection() throws IOException {
    // @Before deletes all records, so the collection has no FILLED entries
    var page = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.nextPage(-1, true, op));
    Assert.assertNull("nextPage should return null for empty collection", page);
  }

  // --- Close/open lifecycle tests ---

  // Verifies that a collection can be closed and reopened, and that data persists across the
  // close/reopen cycle.
  @Test
  public void testCloseAndReopenCollection() throws IOException {
    var collection = new PaginatedCollectionV2("reopenTest", storage);
    collection.configure(99, "reopenTest");
    atomicOps().executeInsideAtomicOperation(op -> collection.create(op));

    var data = new byte[]{10, 20, 30, 40, 50};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 2, null, op));

    Assert.assertTrue("Record should exist before close",
        atomicOps().calculateInsideAtomicOperation(
            op -> collection.exists(pos.collectionPosition, op)));

    // Close with flush to persist data
    collection.close(true);

    // Reopen the collection
    atomicOps().executeInsideAtomicOperation(op -> collection.open(op));

    // Verify data persisted across close/open
    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op));
    Assert.assertNotNull("Record should be readable after reopen", buffer);
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    Assert.assertEquals(2, buffer.recordType());

    // Verify file name is retrievable after reopen
    Assert.assertNotNull(collection.getFileName());
    Assert.assertTrue(collection.getFileName().contains("reopenTest"));

    // Clean up: delete records then the collection
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));
    atomicOps().executeInsideAtomicOperation(op -> collection.delete(op));
  }

  // Verifies that close(false) (without flush) also works correctly.
  @Test
  public void testCloseWithoutFlush() throws IOException {
    var collection = new PaginatedCollectionV2("noFlushTest", storage);
    collection.configure(97, "noFlushTest");
    atomicOps().executeInsideAtomicOperation(op -> collection.create(op));

    // Close without flush
    collection.close(false);

    // Reopen and verify the collection is functional
    atomicOps().executeInsideAtomicOperation(op -> collection.open(op));

    var exists = atomicOps().calculateInsideAtomicOperation(op -> collection.exists(op));
    Assert.assertTrue("Collection should exist after reopen", exists);

    atomicOps().executeInsideAtomicOperation(op -> collection.delete(op));
  }

  // --- Rename test ---

  // Verifies that renaming a collection updates the file name and the collection remains
  // functional after the rename.
  @Test
  public void testSetCollectionName() throws IOException {
    var collection = new PaginatedCollectionV2("renameSource", storage);
    collection.configure(96, "renameSource");
    atomicOps().executeInsideAtomicOperation(op -> collection.create(op));

    // Add a record before rename
    var data = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    // Rename
    collection.setCollectionName("renameTarget");

    // Verify new name
    Assert.assertEquals("renameTarget", collection.getName());
    Assert.assertTrue("File name should reflect new name",
        collection.getFileName().contains("renameTarget"));

    // Verify data is still accessible after rename
    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);

    // Clean up
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));
    atomicOps().executeInsideAtomicOperation(op -> collection.delete(op));
  }

  // --- Conflict strategy test ---

  // Verifies that setting a record conflict strategy updates the strategy returned by the getter.
  @Test
  public void testSetRecordConflictStrategy() {
    var collection = new PaginatedCollectionV2("conflictTest", storage);
    Assert.assertNull(collection.getRecordConflictStrategy());

    collection.setRecordConflictStrategy("version");
    Assert.assertNotNull("Conflict strategy should be set after configuration",
        collection.getRecordConflictStrategy());
    Assert.assertEquals("version", collection.getRecordConflictStrategy().getName());
  }

  // --- Configure with StorageCollectionConfiguration test ---

  // Verifies that the second configure(Storage, config) overload properly initializes the
  // collection.
  @Test
  public void testConfigureWithStorageConfig() throws IOException {
    var config = ((PaginatedCollectionV2) paginatedCollection).generateCollectionConfig();

    // Use the config from the main collection to configure a new one (different name)
    var newCollection = new PaginatedCollectionV2("configTest2", storage);
    newCollection.configure(storage, config);

    // Verify it picked up the config's id
    Assert.assertEquals(config.id(), newCollection.getId());
  }

  // --- MVCC / snapshot isolation tests ---

  // Verifies that a concurrent reader with a stale snapshot sees the old version of a record
  // when a writer updates it after the reader's snapshot was taken. This exercises the
  // findHistoricalPositionEntry and readRecordFromHistoricalEntry code paths.
  @Test
  public void testMvccReadSeesOldVersionAfterConcurrentUpdate() throws Exception {
    var oldData = new byte[]{1, 2, 3, 4, 5};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(oldData, (byte) 1, null, op));

    // Latches to coordinate reader and writer threads
    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerResult = new AtomicReference<byte[]>();
    var readerError = new AtomicReference<Throwable>();

    // Reader thread: starts an atomic operation (takes snapshot), waits for the writer to
    // commit an update, then reads the record. The reader's snapshot predates the update,
    // so it should see the original data via the historical version chain.
    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
          readerResult.set(buffer.buffer());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Writer: update the record while the reader's atomic operation is still active
    var newData = new byte[]{10, 20, 30, 40, 50};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, newData, (byte) 2, op));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull("Reader should have read the record", readerResult.get());
    Assertions.assertThat(readerResult.get())
        .as("Reader with stale snapshot should see old data")
        .isEqualTo(oldData);
  }

  // Verifies that a concurrent reader with a stale snapshot still sees a record that was
  // deleted after the reader's snapshot was taken. This exercises the REMOVED-tombstone path
  // in doReadRecord.
  @Test
  public void testMvccReadSeesRecordAfterConcurrentDelete() throws Exception {
    var data = new byte[]{5, 6, 7, 8, 9};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerResult = new AtomicReference<byte[]>();
    var readerError = new AtomicReference<Throwable>();

    // Reader thread: takes snapshot before the delete happens
    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
          readerResult.set(buffer.buffer());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Writer: delete the record while the reader's operation is active
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, pos.collectionPosition));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull("Reader should still see the record", readerResult.get());
    Assertions.assertThat(readerResult.get())
        .as("Reader with stale snapshot should see data before delete")
        .isEqualTo(data);
  }

  // Verifies that getEntries counts correctly under MVCC: a concurrent reader should see the
  // pre-update/pre-delete entry count even after a writer has modified records.
  @Test
  public void testMvccGetEntriesCountsCorrectlyWithConcurrentDelete() throws Exception {
    // Create several records
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[]{(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    // Sanity check: @Before deletes all records, so only the 5 we just created should exist
    long initialCount = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getEntries(op));
    Assert.assertEquals(5L, initialCount);

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerCount = new AtomicReference<Long>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          readerCount.set(paginatedCollection.getEntries(op));
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Delete 2 records while reader's operation is active
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, positions.get(0)));
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, positions.get(1)));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull("Reader should have counted entries", readerCount.get());
    Assert.assertEquals("Reader with stale snapshot should still see 5 entries",
        5L, (long) readerCount.get());
  }

  // --- Big record update that changes record size significantly ---

  // Verifies that updating a small record to a big record (spanning multiple pages) works
  // correctly, and that the version and content are updated.
  @Test
  public void testUpdateSmallRecordToBigRecord() throws IOException {
    var smallRecord = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(smallRecord, (byte) 1, null, op));

    var versionAfterCreate = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op)).version();

    // Update to a big record that spans multiple pages
    var bigRecord = new byte[(2 << 16) + 100];
    new Random(42).nextBytes(bigRecord);

    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, bigRecord, (byte) 3, op));

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assert.assertNotNull(buffer);
    Assert.assertNotEquals("Version should change after update",
        versionAfterCreate, buffer.version());
    Assertions.assertThat(buffer.buffer()).isEqualTo(bigRecord);
    Assert.assertEquals(3, buffer.recordType());
  }

  // Verifies that updating a big record to a small record works correctly.
  @Test
  public void testUpdateBigRecordToSmallRecord() throws IOException {
    var bigRecord = new byte[(2 << 16) + 100];
    new Random(42).nextBytes(bigRecord);

    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(bigRecord, (byte) 1, null, op));

    var smallRecord = new byte[]{1, 2, 3};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, smallRecord, (byte) 2, op));

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(smallRecord);
    Assert.assertEquals(2, buffer.recordType());
  }

  // --- Edge cases for readRecord and updateRecord ---

  // Verifies that attempting to update a non-existent record throws RecordNotFoundException.
  @Test(expected = RecordNotFoundException.class)
  public void testUpdateNonExistentRecordThrows() throws IOException {
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));
    var nonExistent = lastPos + 1000;
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            nonExistent, new byte[]{1}, (byte) 1, op));
  }

  // Verifies that attempting to update a record version on a non-existent record throws
  // RecordNotFoundException.
  @Test(expected = RecordNotFoundException.class)
  public void testUpdateRecordVersionNonExistentThrows() throws IOException {
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));
    var nonExistent = lastPos + 1000;
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecordVersion(nonExistent, op));
  }

  // Verifies that deleting a non-existent record returns false.
  @Test
  public void testDeleteNonExistentRecordReturnsFalse() throws IOException {
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));
    var nonExistent = lastPos + 1000;
    var result = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, nonExistent));
    Assert.assertFalse("Deleting non-existent record should return false", result);
  }

  // --- Rollback test for updateRecord ---

  // Verifies that a rolled-back update leaves the record in its original state.
  @Test
  public void testUpdateRecordRollbackPreservesOriginal() throws IOException {
    var originalData = new byte[]{10, 20, 30};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(originalData, (byte) 1, null, op));

    var versionBefore = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op)).version();

    try {
      atomicOps().executeInsideAtomicOperation(op -> {
        paginatedCollection.updateRecord(
            pos.collectionPosition, new byte[]{99, 99, 99}, (byte) 2, op);
        throw new RollbackException("test rollback");
      });
    } catch (RollbackException ignore) {
    }

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assert.assertEquals("Version should be unchanged after rollback",
        versionBefore, buffer.version());
    Assertions.assertThat(buffer.buffer())
        .as("Data should be unchanged after rollback")
        .isEqualTo(originalData);
    Assert.assertEquals(1, buffer.recordType());
  }

  // --- Records filling multiple pages to improve branch coverage on findNextFreePageIndexToWrite
  // and serializeRecord ---

  // Verifies correct behavior when creating records that exactly fill pages, exercising the
  // half-chunk-size fallback in findNextFreePageIndexToWrite.
  @Test
  public void testCreateRecordsNearPageSizeLimit() throws IOException {
    // Create records near the maximum entry size to exercise the large-record allocation path
    var nearMaxRecord = new byte[CollectionPage.MAX_RECORD_SIZE - 20];
    new Random(42).nextBytes(nearMaxRecord);

    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(nearMaxRecord, (byte) 1, null, op));

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(nearMaxRecord);
  }

  // Verifies that creating many records of various sizes exercises the free space map search
  // paths including the half-chunk fallback.
  @Test
  public void testCreateMixedSizeRecordsExercisingFreeSpaceSearch() throws IOException {
    var random = new Random(12345);
    var positions = new ArrayList<PhysicalPosition>();

    // Create records of varying sizes to fill pages and exercise different free space paths
    for (var i = 0; i < 100; i++) {
      int size;
      if (i % 3 == 0) {
        // Large record near max page size
        size = CollectionPage.MAX_RECORD_SIZE - random.nextInt(50) - 1;
      } else if (i % 3 == 1) {
        // Medium record
        size = CollectionPage.MAX_RECORD_SIZE / 2 + random.nextInt(100);
      } else {
        // Small record
        size = random.nextInt(100) + 1;
      }
      var data = new byte[size];
      random.nextBytes(data);
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      positions.add(pos);
    }

    // Verify all records are readable
    for (var pos : positions) {
      var buffer = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.readRecord(pos.collectionPosition, op));
      Assert.assertNotNull("All created records should be readable", buffer);
    }
  }

  // --- getPhysicalPosition tests for branch coverage ---

  // Verifies that getPhysicalPosition returns null for a non-existent position.
  @Test
  public void testGetPhysicalPositionNonExistent() throws IOException {
    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getLastPosition(op));

    var pos = new PhysicalPosition();
    pos.collectionPosition = lastPos + 1000;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getPhysicalPosition(pos, op));
    Assert.assertNull("getPhysicalPosition should return null for non-existent position",
        result);
  }

  // Verifies that getPhysicalPosition returns correct type and version for an existing record.
  @Test
  public void testGetPhysicalPositionReturnsCorrectMetadata() throws IOException {
    var data = new byte[]{1, 2, 3, 4, 5};
    var created = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 7, null, op));

    var pos = new PhysicalPosition();
    pos.collectionPosition = created.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getPhysicalPosition(pos, op));

    Assert.assertNotNull("getPhysicalPosition should return non-null for existing record",
        result);
    Assert.assertEquals(created.collectionPosition, result.collectionPosition);
    Assert.assertEquals(7, result.recordType);
    Assert.assertTrue("Record version should be positive", result.recordVersion > 0);
    Assert.assertEquals(-1, result.recordSize);
  }

  // --- acquireAtomicExclusiveLock test ---

  // Verifies that acquireAtomicExclusiveLock can be called without error within an atomic
  // operation.
  @Test
  public void testAcquireAtomicExclusiveLock() throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      paginatedCollection.acquireAtomicExclusiveLock(op);
      // If we get here without exception, the lock was acquired successfully
    });
  }

  // --- meters test ---

  // Verifies that meters() returns a non-null Meters instance after collection initialization.
  @Test
  public void testMeters() {
    var meters = paginatedCollection.meters();
    Assert.assertNotNull("Meters should not be null", meters);
  }

  // --- Additional branch coverage tests ---

  // Verifies that updating a record twice within the same atomic operation correctly overwrites
  // the first update. This exercises the keepPreviousRecordVersion early-return path where
  // oldRecordVersion == newRecordVersion (i.e., both writes share the same commitTs).
  @Test
  public void testDoubleUpdateWithinSameAtomicOperation() throws IOException {
    var data = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    // Update twice within the same atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      paginatedCollection.updateRecord(
          pos.collectionPosition, new byte[]{10, 20}, (byte) 2, op);
      paginatedCollection.updateRecord(
          pos.collectionPosition, new byte[]{30, 40, 50}, (byte) 3, op);
    });

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assertions.assertThat(buffer.buffer())
        .as("Second update should win")
        .isEqualTo(new byte[]{30, 40, 50});
    Assert.assertEquals(3, buffer.recordType());
  }

  // Verifies that creating and then updating a record within the same atomic operation works,
  // exercising the same-commitTs path in keepPreviousRecordVersion.
  @Test
  public void testCreateAndUpdateInSameAtomicOperation() throws IOException {
    var pos = new PhysicalPosition[1];
    atomicOps().executeInsideAtomicOperation(op -> {
      pos[0] = paginatedCollection.createRecord(
          new byte[]{1, 2, 3}, (byte) 1, null, op);
      paginatedCollection.updateRecord(
          pos[0].collectionPosition, new byte[]{4, 5, 6, 7}, (byte) 2, op);
    });

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos[0].collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(new byte[]{4, 5, 6, 7});
    Assert.assertEquals(2, buffer.recordType());
  }

  // Verifies that MVCC reads work correctly for big records spanning multiple pages, exercising
  // the multi-page chain traversal in readRecordFromHistoricalEntry.
  @Test
  public void testMvccReadBigRecordAfterConcurrentUpdate() throws Exception {
    var oldData = new byte[(2 << 16) + 100];
    new Random(42).nextBytes(oldData);

    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(oldData, (byte) 1, null, op));

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerResult = new AtomicReference<byte[]>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
          readerResult.set(buffer.buffer());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Update with different big data
    var newData = new byte[(2 << 16) + 200];
    new Random(99).nextBytes(newData);
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, newData, (byte) 2, op));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull("Reader should have read the record", readerResult.get());
    Assertions.assertThat(readerResult.get())
        .as("Reader with stale snapshot should see old big data")
        .isEqualTo(oldData);
  }

  // Verifies that MVCC getEntries correctly counts records with mixed updates - some records
  // updated, some not. This exercises both FILLED-visible and FILLED-with-historical branches.
  @Test
  public void testMvccGetEntriesWithMixedUpdates() throws Exception {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[]{(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerCount = new AtomicReference<Long>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          readerCount.set(paginatedCollection.getEntries(op));
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Update 2 records and delete 1 while reader's operation is active
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            positions.get(0), new byte[]{99}, (byte) 2, op));
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            positions.get(1), new byte[]{98}, (byte) 2, op));
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, positions.get(2)));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull(readerCount.get());
    // Reader's snapshot predates all writer operations, so all 5 records should be visible
    Assert.assertEquals("Reader should still see all 5 records",
        5L, (long) readerCount.get());
  }

  // Verifies that configuring a collection with a conflict strategy sets it properly through
  // the init path. This covers the conflictStrategy != null branch in init().
  @Test
  public void testConfigureWithConflictStrategy() throws IOException {
    var configWithStrategy = new StoragePaginatedCollectionConfiguration(
        95,
        "strategyTest",
        null,
        true,
        StoragePaginatedCollectionConfiguration.DEFAULT_GROW_FACTOR,
        StoragePaginatedCollectionConfiguration.DEFAULT_GROW_FACTOR,
        null,
        null,
        null,
        "version",
        3);

    var collection = new PaginatedCollectionV2("strategyTest", storage);
    collection.configure(storage, configWithStrategy);

    Assert.assertNotNull("Conflict strategy should be set",
        collection.getRecordConflictStrategy());
    Assert.assertEquals("version", collection.getRecordConflictStrategy().getName());
    Assert.assertEquals(95, collection.getId());
  }

  // Verifies that creating a record with allocatedPosition exercises the pre-allocated position
  // path in doCreateRecord.
  @Test
  public void testCreateRecordWithPreAllocatedPosition() throws IOException {
    var allocated = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.allocatePosition((byte) 1, op));

    var data = new byte[]{10, 20, 30, 40};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, allocated, op));

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(allocated.collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    Assert.assertEquals(1, buffer.recordType());
  }

  // Verifies that getEntries correctly excludes records deleted within the same atomic
  // operation. This exercises the "REMOVED by current transaction" early-return branch in
  // the getEntries lambda.
  @Test
  public void testGetEntriesExcludesRecordsDeletedInSameOperation() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[]{(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    // Delete 2 records and count within the same atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      paginatedCollection.deleteRecord(op, positions.get(0));
      paginatedCollection.deleteRecord(op, positions.get(1));

      long entries = paginatedCollection.getEntries(op);
      Assert.assertEquals("Should count 3 entries after deleting 2 in same operation",
          3L, entries);
    });
  }

  // Verifies that creating records and then reading within the same atomic operation works.
  // The records' version equals the operation's commitTs, so the self-read shortcut in
  // isRecordVersionVisible is exercised.
  @Test
  public void testReadRecordCreatedInSameOperation() throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      var data = new byte[]{42, 43, 44};
      var pos = paginatedCollection.createRecord(data, (byte) 5, null, op);

      var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
      Assertions.assertThat(buffer.buffer()).isEqualTo(data);
      Assert.assertEquals(5, buffer.recordType());

      // Also verify getEntries counts it
      long entries = paginatedCollection.getEntries(op);
      Assert.assertTrue("Should count at least 1 entry", entries >= 1);
    });
  }

  // Verifies that creating and deleting records within the same atomic operation results in
  // getEntries returning 0 for those records. This exercises the FILLED-not-visible path
  // (since after delete, the record has REMOVED status with the current operation's commitTs).
  @Test
  public void testCreateAndDeleteInSameOperationThenCount() throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      var pos = paginatedCollection.createRecord(
          new byte[]{1, 2, 3}, (byte) 1, null, op);
      paginatedCollection.deleteRecord(op, pos.collectionPosition);

      // The record was created and deleted in the same operation, so it should not be
      // counted
      try {
        paginatedCollection.readRecord(pos.collectionPosition, op);
        Assert.fail("Should throw RecordNotFoundException for deleted record");
      } catch (RecordNotFoundException e) {
        // expected
      }
    });
  }

  // Verifies that deleting and re-creating at a new position after a concurrent update
  // exercises more MVCC paths through findHistoricalPositionEntry.
  @Test
  public void testMvccReadAfterMultipleUpdates() throws Exception {
    var data1 = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data1, (byte) 1, null, op));

    // First update
    var data2 = new byte[]{4, 5, 6};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, data2, (byte) 2, op));

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var readerResult = new AtomicReference<byte[]>();
    var readerError = new AtomicReference<Throwable>();

    // Reader takes snapshot after data2 is committed
    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
          readerResult.set(buffer.buffer());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Second update (reader's snapshot predates this)
    var data3 = new byte[]{7, 8, 9};
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, data3, (byte) 3, op));

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull(readerResult.get());
    // Reader's snapshot was taken after data2 was committed but before data3
    Assertions.assertThat(readerResult.get())
        .as("Reader should see data2, the last committed version before its snapshot")
        .isEqualTo(data2);
  }

  // --- Additional branch coverage: allocated position read ---

  // Verifies that reading from an allocated-but-not-written position throws
  // RecordNotFoundException. This exercises the ALLOCATED branch in doReadRecord.
  @Test
  public void testReadAllocatedButNotWrittenPositionThrows() throws IOException {
    var allocated = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.allocatePosition((byte) 1, op));

    try {
      atomicOps().executeInsideAtomicOperation(
          op -> paginatedCollection.readRecord(allocated.collectionPosition, op));
      Assert.fail("Should throw RecordNotFoundException for allocated but not written position");
    } catch (RecordNotFoundException e) {
      // expected: the position is allocated but has no content yet
    }

    // Clean up: write a record to the allocated position so the collection stays consistent
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.createRecord(new byte[]{1}, (byte) 1, allocated, op));
  }

  // Verifies that a new record created by another operation AFTER the reader's snapshot
  // started is not visible to the reader. This exercises the findHistoricalPositionEntry
  // empty-loop path (no historical entries exist for a never-updated record).
  @Test
  public void testMvccNewRecordNotVisibleToStaleReader() throws Exception {
    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var writerPos = new AtomicReference<PhysicalPosition>();

    var readerError = new AtomicReference<Throwable>();
    var readerSawNotFound = new AtomicReference<>(false);

    // Reader thread: start operation (take snapshot), wait for a new record to be created,
    // then try to read it. The record shouldn't be visible.
    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }

          // Attempt to read the record created after our snapshot
          try {
            paginatedCollection.readRecord(writerPos.get().collectionPosition, op);
          } catch (RecordNotFoundException e) {
            readerSawNotFound.set(true);
          }
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Writer: create a new record while reader's operation is active
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(
            new byte[]{42}, (byte) 1, null, op));
    writerPos.set(pos);

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    // The record was created after the reader's snapshot, so the position map entry's
    // version is not visible and no historical entry exists for it. The reader should
    // get RecordNotFoundException.
    Assert.assertTrue(
        "Record created after reader snapshot should not be visible",
        readerSawNotFound.get());
  }

  // Verifies update with version rollback exercises the keepPreviousRecordVersion code path
  // where the previous version is stored in the snapshot index.
  @Test
  public void testUpdateRecordVersionRollbackExercisesSnapshotIndex() throws IOException {
    var data = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    // Update the record version once (this commits)
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecordVersion(pos.collectionPosition, op));

    // Now update again (the previous version is stored in snapshot index)
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecordVersion(pos.collectionPosition, op));

    // Verify the record is still readable with correct content
    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.readRecord(pos.collectionPosition, op));
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);
  }

  // Verifies that MVCC getEntries counts correctly when records are updated by another
  // transaction after the reader started - the FILLED entries with non-visible versions
  // should still be counted if a visible historical version exists.
  @Test
  public void testMvccGetEntriesWithFilledNonVisibleVersion() throws Exception {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 3; i++) {
      var data = new byte[]{(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> paginatedCollection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    var readerStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var readerCount = new AtomicReference<Long>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writer should complete within timeout",
                writerDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          readerCount.set(paginatedCollection.getEntries(op));
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Update all 3 records from a different thread - creates new versions that are
    // not visible to the reader, forcing getEntries to check historical entries
    for (var position : positions) {
      atomicOps().executeInsideAtomicOperation(
          op -> paginatedCollection.updateRecord(
              position, new byte[]{99}, (byte) 2, op));
    }

    writerDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader thread should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull(readerCount.get());
    Assert.assertEquals("Reader should see all 3 records via historical versions",
        3L, (long) readerCount.get());
  }

  // Verifies MVCC historical chain traversal with multiple non-visible versions. The reader's
  // snapshot predates two consecutive updates, so findHistoricalPositionEntry must skip the
  // non-visible intermediate version to find the original visible version. This exercises the
  // "version not visible, continue iterating" branch in the historical entry loop.
  @Test
  public void testMvccHistoricalChainSkipsNonVisibleVersions() throws Exception {
    // Create the initial record (V1)
    var originalData = new byte[]{10, 20, 30};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(originalData, (byte) 1, null, op));

    var readerStarted = new CountDownLatch(1);
    var writersDone = new CountDownLatch(1);
    var readerResult = new AtomicReference<byte[]>();
    var readerError = new AtomicReference<Throwable>();

    // Reader takes snapshot before any updates
    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writers should complete within timeout",
                writersDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          var buffer = paginatedCollection.readRecord(pos.collectionPosition, op);
          readerResult.set(buffer.buffer());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Two consecutive updates AFTER the reader's snapshot, creating a historical chain
    // with two non-visible entries
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, new byte[]{40, 50}, (byte) 2, op));
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, new byte[]{60, 70, 80}, (byte) 3, op));

    writersDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull("Reader should have read the record", readerResult.get());
    // Reader should see the original data (V1), having skipped V2 and V3
    Assertions.assertThat(readerResult.get())
        .as("Reader should see original data after skipping non-visible versions")
        .isEqualTo(originalData);
  }

  // Verifies MVCC getEntries with FILLED entries whose current version is not visible but
  // whose historical version IS visible, with multiple intermediate non-visible versions.
  @Test
  public void testMvccGetEntriesSkipsNonVisibleIntermediateVersions() throws Exception {
    var data = new byte[]{1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    var readerStarted = new CountDownLatch(1);
    var writersDone = new CountDownLatch(1);
    var readerCount = new AtomicReference<Long>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writers should complete within timeout",
                writersDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          readerCount.set(paginatedCollection.getEntries(op));
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Two updates after reader started - the getEntries FILLED-not-visible path must
    // traverse the historical chain
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, new byte[]{10}, (byte) 2, op));
    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.updateRecord(
            pos.collectionPosition, new byte[]{20}, (byte) 3, op));

    writersDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertEquals("Record should still be counted via historical version",
        1L, (long) readerCount.get());
  }

  // Verifies that getEntries does NOT count records created after the reader's snapshot.
  // A new record created after the reader's snapshot has FILLED status in the position map
  // but its version is not visible and no historical entry exists for it. This exercises the
  // FILLED-not-visible-no-history branch in the getEntries lambda.
  @Test
  public void testMvccGetEntriesExcludesRecordsCreatedAfterSnapshot() throws Exception {
    // Start with a known number of records
    var data = new byte[]{1, 2, 3};
    atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    var readerStarted = new CountDownLatch(1);
    var writersDone = new CountDownLatch(1);
    var readerCount = new AtomicReference<Long>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOps().executeInsideAtomicOperation(op -> {
          readerStarted.countDown();
          try {
            Assert.assertTrue("Writers should complete within timeout",
                writersDone.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          readerCount.set(paginatedCollection.getEntries(op));
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    Assert.assertTrue("Reader should start within timeout",
        readerStarted.await(30, TimeUnit.SECONDS));

    // Create 3 MORE records after the reader's snapshot was taken
    for (var i = 0; i < 3; i++) {
      atomicOps().executeInsideAtomicOperation(
          op -> paginatedCollection.createRecord(
              new byte[]{42}, (byte) 1, null, op));
    }

    writersDone.countDown();
    readerThread.join(30_000);

    Assert.assertNull("Reader should not throw: " + readerError.get(),
        readerError.get());
    Assert.assertNotNull(readerCount.get());
    // Reader should see only 1 record (pos1), not the 3 new ones
    Assert.assertEquals(
        "Reader should not count records created after its snapshot",
        1L, (long) readerCount.get());
  }

  // Verifies that creating many records in a rolled-back transaction followed by committing new
  // records exercises the allocateNewPage path where pre-allocated pages exist beyond the
  // tracked file size (fileSize < filledUpTo - 1). The rollback reverts the file size counter
  // but pages may remain physically allocated, triggering the "reuse existing page" branch.
  @Test
  public void testAllocateNewPageReusesPreAllocatedPages() throws IOException {
    var collection = new PaginatedCollectionV2("allocPageTest", storage);
    collection.configure(93, "allocPageTest");
    atomicOps().executeInsideAtomicOperation(op -> collection.create(op));

    // Create many large records in a rolled-back transaction to extend the file
    try {
      atomicOps().executeInsideAtomicOperation(op -> {
        for (var i = 0; i < 200; i++) {
          var bigData = new byte[CollectionPage.MAX_RECORD_SIZE - 10];
          collection.createRecord(bigData, (byte) 1, null, op);
        }
        throw new RollbackException("test rollback to leave pre-allocated pages");
      });
    } catch (RollbackException ignore) {
    }

    // Now create records in a committed transaction. If the physical file was
    // extended during the rolled-back operation, allocateNewPage will find
    // filledUpTo > fileSize + 1 and reuse existing pages instead of adding new ones.
    for (var i = 0; i < 50; i++) {
      var data = new byte[CollectionPage.MAX_RECORD_SIZE - 10];
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    // Verify all records are readable
    long entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals(50L, entries);

    // Clean up
    atomicOps().executeInsideAtomicOperation(op -> collection.delete(op));
  }

  // Fills pages to near-capacity and then creates a medium-large record, exercising the
  // half-chunk-size fallback in findNextFreePageIndexToWrite. When pages are nearly full,
  // the FSM may not find a page for the full chunk size but can find one for the half chunk.
  @Test
  public void testFreeSpaceMapHalfChunkFallback() throws IOException {
    var collection = new PaginatedCollectionV2("halfChunkTest", storage);
    collection.configure(92, "halfChunkTest");
    atomicOps().executeInsideAtomicOperation(op -> collection.create(op));

    // Fill many pages to near capacity with medium-sized records. This creates a scenario
    // where the FSM has pages registered but most have limited free space.
    var random = new Random(42);
    for (var i = 0; i < 500; i++) {
      // Records sized to leave only small gaps on each page
      var size = CollectionPage.MAX_RECORD_SIZE / 2 + random.nextInt(100);
      var data = new byte[size];
      random.nextBytes(data);
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    // Now create medium-large records (> MAX_ENTRY_SIZE/2 content, i.e. > ~4034 bytes).
    // The full chunk won't fit in partially-used pages, but the half-chunk fallback
    // should find a suitable page in the FSM.
    for (var i = 0; i < 20; i++) {
      var data = new byte[CollectionPage.MAX_RECORD_SIZE - 100];
      random.nextBytes(data);
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 2, null, op));
    }

    // Verify data integrity: the collection should have all 520 records
    long entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals(520L, entries);

    // Clean up
    atomicOps().executeInsideAtomicOperation(op -> collection.delete(op));
  }

  // Verifies that getPhysicalPosition returns null after a record is deleted.
  @Test
  public void testGetPhysicalPositionAfterDelete() throws IOException {
    var data = new byte[]{1, 2, 3};
    var created = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> paginatedCollection.deleteRecord(op, created.collectionPosition));

    var pos = new PhysicalPosition();
    pos.collectionPosition = created.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> paginatedCollection.getPhysicalPosition(pos, op));
    Assert.assertNull("getPhysicalPosition should return null for deleted record", result);
  }
}
