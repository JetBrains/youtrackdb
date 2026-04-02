package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for optimistic read paths in {@link PaginatedCollectionV2}. Each test inserts records in
 * one atomic operation, commits, then reads in a separate atomic operation with no pending
 * changes. This exercises the optimistic (lock-free) read path that
 * {@code executeOptimisticStorageRead} tries before falling back to the pinned (locked) path.
 *
 * <p>Each test gets a fresh database and collection to ensure full isolation — no state leaks
 * between tests.
 *
 * <p>The following optimistic code paths are covered:
 * <ul>
 *   <li>{@code readRecord} / {@code doReadRecordOptimistic} / {@code doReadRecordOptimisticInner}
 *   <li>{@code getPhysicalPosition} / {@code doGetPhysicalPosition}
 *   <li>{@code exists} / {@code doExists}
 *   <li>{@code getFirstPosition}, {@code getLastPosition}
 *   <li>{@code higherPositions}, {@code ceilingPositions}, {@code lowerPositions},
 *       {@code floorPositions}
 *   <li>{@code getRecordStatus} / {@code doGetRecordStatus}
 *   <li>{@code getEntries} / {@code doGetEntries}
 *   <li>{@code nextPage} / {@code doNextPage}
 * </ul>
 *
 * <p>The FreeSpaceMap and CollectionPositionMapV2 optimistic paths are exercised indirectly
 * through the PaginatedCollectionV2 read operations.
 */
@Category(SequentialTest.class)
public class PaginatedCollectionV2OptimisticReadTest {

  private YouTrackDBImpl youTrackDB;
  private AbstractStorage storage;
  private PaginatedCollectionV2 collection;
  private String buildDirectory;
  private static final String DB_NAME = "optimisticReadTest";

  @Before
  public void setUp() throws IOException {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }
    buildDirectory += File.separator
        + PaginatedCollectionV2OptimisticReadTest.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = (AbstractStorage) session.getStorage();

    collection = new PaginatedCollectionV2("optReadCollection", storage);
    collection.configure(55, "optReadCollection");
    atomicOps().executeInsideAtomicOperation(
        op -> collection.create(op));
  }

  @After
  public void tearDown() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
  }

  private AtomicOperationsManager atomicOps() {
    return storage.getAtomicOperationsManager();
  }

  /**
   * Closes and reopens the database so all pages are evicted from the write
   * cache. After reopening, any page load goes through the read cache,
   * enabling the optimistic read path.
   */
  private void flushToReadCache() {
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = (AbstractStorage) session.getStorage();

    // Reopen the collection with the new storage reference.
    collection = new PaginatedCollectionV2("optReadCollection", storage);
    try {
      collection.configure(55, "optReadCollection");
      atomicOps().executeInsideAtomicOperation(
          op -> collection.open(op));

      // Warm-up: read all existing records to populate the read cache
      // with all collection pages. The optimistic path only works for
      // pages already in the read cache.
      atomicOps().executeInsideAtomicOperation(op -> {
        var first = collection.getFirstPosition(op);
        var last = collection.getLastPosition(op);
        collection.getEntries(op);
        var positions = collection.ceilingPositions(
            new PhysicalPosition(first), Integer.MAX_VALUE, op);
        for (var pos : positions) {
          collection.readRecord(pos.collectionPosition, op).toRawBuffer();
        }
      });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // --- readRecord optimistic path ---

  // Verifies that reading a small (single-page) record in a separate read-only atomic operation
  // exercises the optimistic read path in doReadRecordOptimistic and returns the correct data.
  @Test
  public void testReadRecordOptimisticSmallRecord() throws IOException {
    var data = new byte[] {10, 20, 30, 40, 50};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 2, null, op));

    flushToReadCache();

    // Read in a separate atomic operation (no pending changes) to hit the optimistic path
    var rawResult = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op));
    Assert.assertTrue("Optimistic path should return RawPageBuffer for single-page record",
        rawResult instanceof RawPageBuffer);
    var buffer = rawResult.toRawBuffer();

    Assert.assertNotNull("Optimistic read should return a non-null buffer", buffer);
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    Assert.assertEquals(2, buffer.recordType());
    // The record version encodes the commit timestamp from the atomic operation.
    // It must be positive and non-zero for any committed record.
    Assert.assertTrue("Version should be positive for a committed record, got: "
        + buffer.version(), buffer.version() > 0);
  }

  // Verifies that reading multiple small records in the same read-only atomic operation
  // exercises the optimistic path repeatedly and returns correct data for each.
  @Test
  public void testReadRecordOptimisticMultipleRecords() throws IOException {
    var records = new ArrayList<byte[]>();
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 10; i++) {
      var data = new byte[] {(byte) (i * 10), (byte) (i * 10 + 1)};
      records.add(data);
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    // Read all records in a single read-only atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      for (var i = 0; i < positions.size(); i++) {
        var buffer = collection.readRecord(positions.get(i), op).toRawBuffer();
        Assertions.assertThat(buffer.buffer())
            .as("Record %d should match inserted data", i)
            .isEqualTo(records.get(i));
      }
    });
  }

  // Verifies that reading a large (multi-page) record falls back from the optimistic path
  // to the pinned path and still returns the correct data.
  @Test
  public void testReadRecordOptimisticFallbackForMultiPageRecord() throws IOException {
    // 131172 bytes = (2 << 16) + 100 = 128 KB + 100 bytes. This is much larger than
    // the default 8 KB page size, guaranteeing the record spans multiple pages. The +100
    // avoids landing exactly on a page boundary, exercising partial-page handling.
    var bigData = new byte[(2 << 16) + 100];
    new Random(42).nextBytes(bigData);

    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(bigData, (byte) 3, null, op));

    flushToReadCache();

    // The optimistic path detects the multi-page pointer and falls back to the pinned path
    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());

    Assert.assertNotNull(buffer);
    Assertions.assertThat(buffer.buffer()).isEqualTo(bigData);
    Assert.assertEquals(3, buffer.recordType());
  }

  // Verifies that reading a non-existent position throws RecordNotFoundException even
  // through the optimistic path (the RuntimeException catch in doReadRecordOptimistic
  // converts RecordNotFoundException to OptimisticReadFailedException, so the pinned
  // path produces the authoritative answer). The exception must reference the requested
  // position so the caller can diagnose which record was missing.
  @Test
  public void testReadRecordOptimisticNonExistentThrows() throws IOException {
    // Insert at least one record so the collection is not empty (getLastPosition needs it)
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));
    var nonExistent = lastPos + 5000;

    flushToReadCache();

    try {
      atomicOps().calculateInsideAtomicOperation(
          op -> collection.readRecord(nonExistent, op).toRawBuffer());
      Assert.fail("Should throw RecordNotFoundException for non-existent position");
    } catch (RecordNotFoundException e) {
      // The exception should reference the non-existent position.
      Assertions.assertThat(e.getMessage())
          .as("Exception message should contain the requested position")
          .contains(String.valueOf(nonExistent));
    }
  }

  // --- getPhysicalPosition optimistic path ---

  // Verifies that getPhysicalPosition in a read-only atomic operation exercises the optimistic
  // path and returns correct metadata (type, version, position).
  @Test
  public void testGetPhysicalPositionOptimistic() throws IOException {
    var data = new byte[] {1, 2, 3, 4, 5};
    var created = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 7, null, op));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = created.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));

    Assert.assertNotNull("Physical position should be non-null for existing record",
        result);
    Assert.assertEquals(created.collectionPosition, result.collectionPosition);
    Assert.assertEquals(7, result.recordType);
    // The record version encodes the commit timestamp from the atomic operation.
    // It must be positive and non-zero for any committed record.
    Assert.assertTrue("Record version should be positive for a committed record, got: "
        + result.recordVersion, result.recordVersion > 0);
    // PaginatedCollectionV2.getPhysicalPosition() does not compute record size — it sets
    // recordSize = -1 as a sentinel meaning "size not available" (see doGetPhysicalPosition).
    Assert.assertEquals("recordSize should be -1 (not computed by getPhysicalPosition)",
        -1, result.recordSize);
  }

  // Verifies that getPhysicalPosition returns null for a non-existent position through the
  // optimistic path.
  @Test
  public void testGetPhysicalPositionOptimisticNonExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = lastPos + 5000;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Should return null for non-existent position", result);
  }

  // Verifies that getPhysicalPosition returns null for a deleted record through the
  // optimistic path.
  @Test
  public void testGetPhysicalPositionOptimisticDeletedRecord() throws IOException {
    var data = new byte[] {1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = pos.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Should return null for deleted record", result);
  }

  // --- exists optimistic path ---

  // Verifies that exists() in a read-only atomic operation exercises the optimistic path
  // and returns true for an existing record.
  @Test
  public void testExistsOptimisticTrue() throws IOException {
    var data = new byte[] {5, 6, 7};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(pos.collectionPosition, op));
    Assert.assertTrue("Record should exist", exists);
  }

  // Verifies that exists() returns false for a non-existent position through the optimistic
  // path.
  @Test
  public void testExistsOptimisticFalseNonExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(lastPos + 5000, op));
    Assert.assertFalse("Non-existent position should not exist", exists);
  }

  // Verifies that exists() returns false after a record is deleted, exercising the optimistic
  // path for the deleted-record case.
  @Test
  public void testExistsOptimisticFalseAfterDelete() throws IOException {
    var data = new byte[] {8, 9, 10};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(pos.collectionPosition, op));
    Assert.assertFalse("Deleted record should not exist", exists);
  }

  // --- getFirstPosition / getLastPosition optimistic paths ---

  // Verifies that getFirstPosition and getLastPosition return correct bounds after inserting
  // multiple records, exercising the optimistic path for position map boundary queries.
  @Test
  public void testGetFirstAndLastPositionOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var first = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getFirstPosition(op));
    var last = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    Assert.assertEquals("First position should equal smallest created position",
        positions.get(0).longValue(), (long) first);
    Assert.assertEquals("Last position should equal largest created position",
        positions.get(positions.size() - 1).longValue(), (long) last);
  }

  // --- higherPositions / ceilingPositions / lowerPositions / floorPositions ---

  // Verifies that higherPositions returns positions strictly greater than the given position,
  // exercising the optimistic path in the position map.
  @Test
  public void testHigherPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var query = new PhysicalPosition(positions.get(0));
    var higher = atomicOps().calculateInsideAtomicOperation(
        op -> collection.higherPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 4 higher positions",
        4, higher.length);
    for (var pp : higher) {
      Assert.assertTrue("All positions should be strictly higher than the query",
          pp.collectionPosition > positions.get(0));
    }
  }

  // Verifies that ceilingPositions returns positions >= the given position, exercising the
  // optimistic path.
  @Test
  public void testCeilingPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var query = new PhysicalPosition(positions.get(0));
    var ceiling = atomicOps().calculateInsideAtomicOperation(
        op -> collection.ceilingPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 5 ceiling positions",
        5, ceiling.length);
    Assert.assertEquals("First ceiling position should equal the query position",
        positions.get(0).longValue(), ceiling[0].collectionPosition);
  }

  // Verifies that lowerPositions returns positions strictly less than the given position,
  // exercising the optimistic path.
  @Test
  public void testLowerPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var lastPos = positions.get(positions.size() - 1);
    var query = new PhysicalPosition(lastPos);
    var lower = atomicOps().calculateInsideAtomicOperation(
        op -> collection.lowerPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 4 lower positions",
        4, lower.length);
    for (var pp : lower) {
      Assert.assertTrue("All positions should be strictly lower than the query",
          pp.collectionPosition < lastPos);
    }
  }

  // Verifies that floorPositions returns positions <= the given position, exercising the
  // optimistic path.
  @Test
  public void testFloorPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var lastPos = positions.get(positions.size() - 1);
    var query = new PhysicalPosition(lastPos);
    var floor = atomicOps().calculateInsideAtomicOperation(
        op -> collection.floorPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 5 floor positions",
        5, floor.length);
    var lastFloor = floor[floor.length - 1];
    Assert.assertEquals("Last floor position should equal the query position",
        lastPos.longValue(), (long) lastFloor.collectionPosition);
  }

  // --- getRecordStatus optimistic path ---

  // Verifies that getRecordStatus returns PRESENT for a filled record through the optimistic
  // path.
  @Test
  public void testGetRecordStatusOptimisticPresent() throws IOException {
    var data = new byte[] {1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);
  }

  // Verifies that getRecordStatus returns NOT_EXISTENT for a non-existent position through
  // the optimistic path.
  @Test
  public void testGetRecordStatusOptimisticNotExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(lastPos + 5000, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.NOT_EXISTENT, status);
  }

  // Verifies that getRecordStatus returns REMOVED for a deleted record through the optimistic
  // path.
  @Test
  public void testGetRecordStatusOptimisticRemoved() throws IOException {
    var data = new byte[] {4, 5, 6};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.REMOVED, status);
  }

  // --- getEntries optimistic path ---

  // Verifies that getEntries in a read-only atomic operation exercises the optimistic path
  // and returns the correct count of records.
  @Test
  public void testGetEntriesOptimistic() throws IOException {
    var count = 7;
    for (var i = 0; i < count; i++) {
      var data = new byte[] {(byte) i, (byte) (i + 1)};
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    flushToReadCache();

    var entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Should have exactly the inserted records",
        count, entries.longValue());
  }

  // Verifies that getEntries correctly reflects the count after deletions, exercising the
  // REMOVED-entry logic in doGetEntries through the optimistic path.
  @Test
  public void testGetEntriesOptimisticAfterDeletions() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    // Delete 2 records
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, positions.get(0)));
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, positions.get(1)));

    flushToReadCache();

    var entriesAfter = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Entry count should be 3 after inserting 5 and deleting 2",
        3L, entriesAfter.longValue());
  }

  // --- nextPage optimistic path ---

  // Verifies that forward page browsing via nextPage exercises the optimistic path in
  // doNextPage and visits all inserted records.
  @Test
  public void testNextPageOptimisticForward() throws IOException {
    var createdPositions = new ArrayList<Long>();
    for (var i = 0; i < 20; i++) {
      var data = new byte[] {(byte) i, (byte) (i + 1), (byte) (i + 2)};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      createdPositions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var visitedPositions = new ArrayList<Long>();
    var page = atomicOps().calculateInsideAtomicOperation(
        op -> collection.nextPage(-1, true, op));

    while (page != null) {
      for (var entry : page) {
        visitedPositions.add(entry.collectionPosition());
      }
      var nextStart = page.getLastPosition();
      page = atomicOps().calculateInsideAtomicOperation(
          op -> collection.nextPage(nextStart, true, op));
    }

    Assert.assertTrue("Should visit all created records",
        visitedPositions.containsAll(createdPositions));
    Assert.assertEquals("Should visit exactly the created records",
        createdPositions.size(), visitedPositions.size());

    // Verify ascending order of visited positions.
    for (var i = 1; i < visitedPositions.size(); i++) {
      Assert.assertTrue(
          "Positions should be in ascending order, but position at index " + (i - 1)
              + " (" + visitedPositions.get(i - 1) + ") >= position at index " + i
              + " (" + visitedPositions.get(i) + ")",
          visitedPositions.get(i - 1) < visitedPositions.get(i));
    }
  }

  // Verifies that backward page browsing via nextPage exercises the optimistic path in
  // doNextPage and returns entries in descending order.
  @Test
  public void testNextPageOptimisticBackward() throws IOException {
    for (var i = 0; i < 10; i++) {
      var data = new byte[] {(byte) i};
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    flushToReadCache();

    var page = atomicOps().calculateInsideAtomicOperation(
        op -> collection.nextPage(Long.MAX_VALUE, false, op));
    Assert.assertNotNull("Should get a page when browsing backward", page);

    var prevPos = Long.MAX_VALUE;
    var hasEntries = false;
    for (var entry : page) {
      hasEntries = true;
      Assert.assertTrue("Positions should be descending",
          entry.collectionPosition() < prevPos);
      prevPos = entry.collectionPosition();
    }
    Assert.assertTrue("Page should have entries", hasEntries);
  }

  // --- Zero-length record edge case ---

  // Verifies that a zero-length (empty byte[0]) record can be created, flushed, and read
  // back through the optimistic path. Empty records could trigger edge cases in size
  // calculations or optimistic read buffer allocation.
  @Test
  public void testReadRecordOptimisticZeroLengthRecord() throws IOException {
    var emptyData = new byte[0];
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(emptyData, (byte) 4, null, op));

    flushToReadCache();

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());

    Assert.assertNotNull("Optimistic read should return a non-null buffer for empty record",
        buffer);
    Assertions.assertThat(buffer.buffer())
        .as("Empty record should return a zero-length byte array")
        .isEqualTo(emptyData);
    Assert.assertEquals(4, buffer.recordType());
    Assert.assertTrue("Version should be positive for a committed record, got: "
        + buffer.version(), buffer.version() > 0);
  }

  // --- Combined read scenario ---

  // Verifies that all optimistic read paths work correctly within a single read-only
  // atomic operation, exercising the optimistic read scope reuse across multiple calls.
  @Test
  public void testCombinedOptimisticReadsInSingleAtomicOperation() throws IOException {
    var data = new byte[] {100, (byte) 200, 42, 7};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 5, null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // readRecord
      var buffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assertions.assertThat(buffer.buffer()).isEqualTo(data);
      Assert.assertEquals(5, buffer.recordType());

      // exists
      Assert.assertTrue(collection.exists(pos.collectionPosition, op));

      // getPhysicalPosition
      var query = new PhysicalPosition();
      query.collectionPosition = pos.collectionPosition;
      var physPos = collection.getPhysicalPosition(query, op);
      Assert.assertNotNull(physPos);
      Assert.assertEquals(5, physPos.recordType);

      // getRecordStatus
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);

      // getFirstPosition / getLastPosition
      var first = collection.getFirstPosition(op);
      var last = collection.getLastPosition(op);
      Assert.assertEquals(pos.collectionPosition, first);
      Assert.assertEquals(pos.collectionPosition, last);

      // getEntries
      var entries = collection.getEntries(op);
      Assert.assertEquals("Should have exactly 1 entry", 1L, entries);

      // higherPositions from (first - 1) should include our record
      var higher = collection.higherPositions(
          new PhysicalPosition(first - 1), 10, op);
      Assert.assertEquals("Should have 1 position above (first - 1)", 1, higher.length);

      // ceilingPositions from our position (should include our record)
      var ceiling = collection.ceilingPositions(
          new PhysicalPosition(pos.collectionPosition), 10, op);
      Assert.assertEquals("Should have 1 ceiling position", 1, ceiling.length);
      Assert.assertEquals(pos.collectionPosition, ceiling[0].collectionPosition);

      // lowerPositions from Long.MAX_VALUE (should include our record)
      var lower = collection.lowerPositions(
          new PhysicalPosition(Long.MAX_VALUE), 10, op);
      Assert.assertEquals("Should have 1 lower position", 1, lower.length);

      // floorPositions from our position (should include our record)
      var floor = collection.floorPositions(
          new PhysicalPosition(pos.collectionPosition), 10, op);
      Assert.assertEquals("Should have 1 floor position", 1, floor.length);
    });
  }

  // --- Delete scenario: verify status transitions through optimistic paths ---

  // Verifies the full lifecycle of a record — create, verify present, delete, verify removed
  // and non-existent — all through optimistic read paths in separate atomic operations.
  @Test
  public void testDeleteLifecycleThroughOptimisticPaths() throws IOException {
    var data = new byte[] {11, 22, 33, 44};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    // Verify PRESENT status and existence through optimistic path
    atomicOps().executeInsideAtomicOperation(op -> {
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);
      Assert.assertTrue(collection.exists(pos.collectionPosition, op));

      var buffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    });

    // Delete the record
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    // Verify REMOVED status and non-existence through optimistic path
    atomicOps().executeInsideAtomicOperation(op -> {
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.REMOVED, status);
      Assert.assertFalse(collection.exists(pos.collectionPosition, op));
    });

    // Verify readRecord throws RecordNotFoundException for deleted record
    try {
      atomicOps().calculateInsideAtomicOperation(
          op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());
      Assert.fail("Should throw RecordNotFoundException for deleted record");
    } catch (RecordNotFoundException e) {
      // expected
    }

    // Verify getPhysicalPosition returns null for deleted record
    var query = new PhysicalPosition();
    query.collectionPosition = pos.collectionPosition;
    var physPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Physical position should be null for deleted record", physPos);
  }

  // --- Bulk insert then bulk read: exercises optimistic paths with many entries ---

  // Verifies that inserting many records and then reading them all in separate read-only
  // atomic operations exercises the optimistic paths across multiple position map buckets
  // and data pages, indirectly covering FreeSpaceMap and CollectionPositionMapV2 optimistic
  // paths.
  @Test
  public void testBulkInsertThenBulkOptimisticRead() throws IOException {
    var random = new Random(99);
    var positions = new ArrayList<Long>();
    var dataList = new ArrayList<byte[]>();

    // Insert 50 records of varying sizes
    for (var i = 0; i < 50; i++) {
      var size = 10 + random.nextInt(200);
      var data = new byte[size];
      random.nextBytes(data);
      dataList.add(data);

      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    // Read all records in a single read-only atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      for (var i = 0; i < positions.size(); i++) {
        var buffer = collection.readRecord(positions.get(i), op).toRawBuffer();
        Assert.assertNotNull("Record " + i + " should be readable", buffer);
        Assertions.assertThat(buffer.buffer())
            .as("Record %d data should match", i)
            .isEqualTo(dataList.get(i));
      }
    });

    // Verify entry count through optimistic path
    var entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Should have exactly 50 entries", 50L, entries.longValue());

    // Navigate positions with ceiling/higher/lower/floor in a read-only op
    atomicOps().executeInsideAtomicOperation(op -> {
      var first = collection.getFirstPosition(op);
      var last = collection.getLastPosition(op);

      var ceiling = collection.ceilingPositions(
          new PhysicalPosition(first), Integer.MAX_VALUE, op);
      Assert.assertEquals("Ceiling from first should cover all records",
          50, ceiling.length);

      var floor = collection.floorPositions(
          new PhysicalPosition(last), Integer.MAX_VALUE, op);
      Assert.assertEquals("Floor from last should cover all records",
          50, floor.length);
    });
  }

  // --- Integration tests for CollectionPage offset methods and RawPageBuffer ---

  // Verifies that CollectionPage.getRecordContentOffset and getRecordContentLength
  // return values consistent with the bytes returned by readRecord. Creates a record
  // via PaginatedCollectionV2, loads the page directly, and compares the content
  // bytes at the computed offset with the readRecord result.
  @Test
  public void testCollectionPageContentOffsetMatchesReadRecord() throws IOException {
    var data = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // Read via normal path to get expected content
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      // Load the page directly and check offset/length
      long fileId = collection.getFileId();
      // Page 1 is the first data page (page 0 is the state page)
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);

        // The first record is at position 0 on the page
        int contentOffset = page.getRecordContentOffset(0);
        int contentLength = page.getRecordContentLength(0);

        Assert.assertEquals("Content length should match record data length",
            expected.length, contentLength);
        // Content offset must be at least past the entry header (3 ints)
        // and metadata header within the page.
        int minContentOffset =
            3 * IntegerSerializer.INT_SIZE + CollectionPage.RECORD_METADATA_HEADER_SIZE;
        Assert.assertTrue("Content offset " + contentOffset + " should be >= "
            + minContentOffset, contentOffset >= minContentOffset);
        Assert.assertTrue("Content must fit within page",
            contentOffset + contentLength <= CollectionPage.PAGE_SIZE);

        // Read bytes directly from the page buffer at the content offset
        ByteBuffer pageBuffer = entry.getCachePointer().getPageFrame().getBuffer();
        byte[] actual = new byte[contentLength];
        for (int i = 0; i < contentLength; i++) {
          actual[i] = pageBuffer.get(contentOffset + i);
        }
        Assert.assertArrayEquals("Bytes at content offset must match readRecord result",
            expected, actual);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies the end-to-end zero-copy path: constructs a RawPageBuffer from a real
  // PageFrame obtained via the read cache, calls sliceContent(), wraps in
  // ReadBytesContainer, and verifies the bytes match the expected record content.
  @Test
  public void testRawPageBufferSliceContentIntegration() throws IOException {
    var data = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // Read via normal path to get expected content
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      // Load the page and construct a RawPageBuffer
      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        int contentOffset = page.getRecordContentOffset(0);
        int contentLength = page.getRecordContentLength(0);

        // Construct RawPageBuffer from the real PageFrame
        var pageFrame = entry.getCachePointer().getPageFrame();
        long stamp = pageFrame.tryOptimisticRead();
        var rawPageBuffer = new RawPageBuffer(
            pageFrame, stamp, contentOffset, contentLength,
            rawBuffer.version(), rawBuffer.recordType());

        // sliceContent should return a ByteBuffer with matching content
        ByteBuffer slice = rawPageBuffer.sliceContent();
        Assert.assertEquals(contentLength, slice.capacity());

        // Read bytes from the slice
        byte[] actual = new byte[contentLength];
        slice.get(actual);
        Assert.assertArrayEquals("sliceContent bytes must match readRecord result",
            expected, actual);

        // Stamp should still be valid (no writes happened)
        Assert.assertTrue("Stamp should still be valid after reading",
            pageFrame.validate(stamp));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies that CollectionPage content offset/length work for zero-length record content.
  // This exercises the contentLength == 0 boundary (recordSize == metadata + tail exactly).
  @Test
  public void testCollectionPageContentOffsetEmptyRecord() throws IOException {
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(new byte[0], (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assert.assertEquals(0, rawBuffer.buffer().length);

      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        Assert.assertEquals(0, page.getRecordContentLength(0));

        var pageFrame = entry.getCachePointer().getPageFrame();
        long stamp = pageFrame.tryOptimisticRead();
        var rpb = new RawPageBuffer(pageFrame, stamp,
            page.getRecordContentOffset(0), 0,
            rawBuffer.version(), rawBuffer.recordType());
        ByteBuffer slice = rpb.sliceContent();
        Assert.assertEquals(0, slice.capacity());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies that CollectionPage content offset/length work for a non-first record
  // (position > 0) on the same page, catching pointer-array indexing bugs.
  @Test
  public void testCollectionPageContentOffsetSecondRecord() throws IOException {
    var data1 = new byte[] {10, 20, 30};
    var data2 = new byte[] {40, 50, 60, 70, 80};
    atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data1, (byte) 'd', null, op));
    var pos2 = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data2, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      var rawBuffer = collection.readRecord(pos2.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        int offset = page.getRecordContentOffset(1);
        int length = page.getRecordContentLength(1);
        Assert.assertEquals(expected.length, length);

        ByteBuffer buf = entry.getCachePointer().getPageFrame().getBuffer();
        byte[] actual = new byte[length];
        for (int i = 0; i < length; i++) {
          actual[i] = buf.get(offset + i);
        }
        Assert.assertArrayEquals(expected, actual);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }
}
