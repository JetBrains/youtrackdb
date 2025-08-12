package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.api.common.BasicYouTrackDB;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public abstract class LocalPaginatedCollectionAbstract {

  protected static String buildDirectory;
  protected static PaginatedCollection paginatedCollection;
  protected static DatabaseSessionInternal databaseDocumentTx;
  protected static BasicYouTrackDB youTrackDB;
  protected static String dbName;
  protected static AbstractStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;

  @AfterClass
  public static void afterClass() throws IOException {
    final var firstPosition = paginatedCollection.getFirstPosition();
    var positions =
        paginatedCollection.ceilingPositions(new PhysicalPosition(firstPosition), Integer.MAX_VALUE);
    while (positions.length > 0) {
      for (var position : positions) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.deleteRecord(atomicOperation, position.collectionPosition));
      }
      positions = paginatedCollection.higherPositions(positions[positions.length - 1],
          Integer.MAX_VALUE);
    }
    atomicOperationsManager.executeInsideAtomicOperation(
        null, atomicOperation -> paginatedCollection.delete(atomicOperation));

    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  @Before
  public void beforeMethod() throws IOException {
    atomicOperationsManager = storage.getAtomicOperationsManager();
    final var firstPosition = paginatedCollection.getFirstPosition();
    var positions =
        paginatedCollection.ceilingPositions(new PhysicalPosition(firstPosition), Integer.MAX_VALUE);
    while (positions.length > 0) {
      for (var position : positions) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.deleteRecord(atomicOperation, position.collectionPosition));
      }

      positions = paginatedCollection.higherPositions(positions[positions.length - 1],
          Integer.MAX_VALUE);
    }
  }

  @Test
  public void testDeleteRecordAndAddNewOnItsPlace() throws IOException {
    var smallRecord = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    final var recordVersion = 2;

    var atomicOperationsManager = storage.getAtomicOperationsManager();

    final var physicalPosition = new PhysicalPosition[1];
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation);
            paginatedCollection.deleteRecord(atomicOperation, physicalPosition[0].collectionPosition);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCollection.getEntries());
    try {
      paginatedCollection.readRecord(physicalPosition[0].collectionPosition);
      Assert.fail();
    } catch (RecordNotFoundException ignore) {
      // expected
    }

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation -> {
          physicalPosition[0] =
              paginatedCollection.createRecord(
                  smallRecord, recordVersion, (byte) 1, null, atomicOperation);
          paginatedCollection.deleteRecord(atomicOperation, physicalPosition[0].collectionPosition);
        });

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    Assert.assertEquals(recordVersion, physicalPosition[0].recordVersion);
  }

  @Test
  public void testAddOneSmallRecord() throws IOException {
    var smallRecord = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    final var recordVersion = 2;

    final var physicalPosition = new PhysicalPosition[1];
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCollection.getEntries());
    try {
      paginatedCollection.readRecord(physicalPosition[0].collectionPosition);
      Assert.fail();
    } catch (RecordNotFoundException recordNotFoundException) {
      // expected
    }

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCollection.readRecord(physicalPosition[0].collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
    Assert.assertEquals(1, rawBuffer.recordType);
  }

  @Test
  public void testAddOneBigRecord() throws IOException {
    var bigRecord = new byte[2 * 65536 + 100];
    var mersenneTwisterFast = new Random();
    mersenneTwisterFast.nextBytes(bigRecord);

    final var recordVersion = 2;

    final var physicalPosition = new PhysicalPosition[1];
    var atomicOperationsManager = storage.getAtomicOperationsManager();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCollection.getEntries());
    try {
      paginatedCollection.readRecord(physicalPosition[0].collectionPosition);
      Assert.fail();
    } catch (RecordNotFoundException recordNotFoundException) {
      // expected
    }

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCollection.readRecord(physicalPosition[0].collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
    Assert.assertEquals(1, rawBuffer.recordType);
  }

  @Test
  public void testAddManySmallRecords() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testAddManySmallRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
          });
    }

    final Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
              var smallRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(smallRecord);

              final var physicalPosition =
                  paginatedCollection.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation);
              rolledBackRecordSet.add(physicalPosition.collectionPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long collectionPosition : rolledBackRecordSet) {
      try {
        paginatedCollection.readRecord(collectionPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testAddManyBigRecords() throws IOException {
    final var records = 5000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testAddManyBigRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records / 2; i++) {
      var recordSize =
          mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
              + CollectionPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
          });
    }

    Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize =
                  mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
                      + CollectionPage.MAX_RECORD_SIZE
                      + 1;
              var bigRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(bigRecord);

              final var physicalPosition =
                  paginatedCollection.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation);
              rolledBackRecordSet.add(physicalPosition.collectionPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long collectionPosition : rolledBackRecordSet) {
      try {
        paginatedCollection.readRecord(collectionPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize =
          mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
              + CollectionPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testAddManyRecords() throws IOException {
    final var records = 10000;
    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testAddManyRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
          });
    }

    Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
              var smallRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(smallRecord);

              final var physicalPosition =
                  paginatedCollection.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation);

              rolledBackRecordSet.add(physicalPosition.collectionPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long collectionPosition : rolledBackRecordSet) {
      try {
        paginatedCollection.readRecord(collectionPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testAllocatePositionMap() throws IOException {
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCollection.allocatePosition((byte) 'd', atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var position =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation -> paginatedCollection.allocatePosition((byte) 'd', atomicOperation));

    Assert.assertTrue(position.collectionPosition >= 0);
    try {
      paginatedCollection.readRecord(position.collectionPosition);
      Assert.fail();
    } catch (RecordNotFoundException recordNotFoundException) {
      // expected
    }

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCollection.createRecord(new byte[20], 1, (byte) 'd', position, atomicOperation));

    var rec = paginatedCollection.readRecord(position.collectionPosition);
    Assert.assertNotNull(rec);
  }

  @Test
  public void testManyAllocatePositionMap() throws IOException {
    final var records = 10000;

    List<PhysicalPosition> positions = new ArrayList<>();
    for (var i = 0; i < records / 2; i++) {
      var position =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation -> paginatedCollection.allocatePosition((byte) 'd', atomicOperation));
      Assert.assertTrue(position.collectionPosition >= 0);
      try {
        paginatedCollection.readRecord(position.collectionPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }
      positions.add(position);
    }

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var position =
                  paginatedCollection.allocatePosition((byte) 'd', atomicOperation);
              Assert.assertTrue(position.collectionPosition >= 0);
              try {
                paginatedCollection.readRecord(position.collectionPosition);
                Assert.fail();
              } catch (RecordNotFoundException recordNotFoundException) {
                //expected
              }
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (var i = records / 2; i < records; i++) {
      var position =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation -> paginatedCollection.allocatePosition((byte) 'd', atomicOperation));
      Assert.assertTrue(position.collectionPosition >= 0);
      try {
        paginatedCollection.readRecord(position.collectionPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }

      positions.add(position);
    }

    for (var i = 0; i < records; i++) {
      var position = positions.get(i);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              paginatedCollection.createRecord(
                  new byte[20], 1, (byte) 'd', position, atomicOperation));
      var rec = paginatedCollection.readRecord(position.collectionPosition);
      Assert.assertNotNull(rec);
    }
  }

  @Test
  public void testRemoveHalfSmallRecords() throws IOException {
    final var records = 10000;
    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfSmallRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              Assert.assertEquals(records, paginatedCollection.getEntries());
              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCollection.deleteRecord(atomicOperation, collectionPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCollection.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCollection.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(collectionPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCollection.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      try {
        paginatedCollection.readRecord(deletedPosition);
        Assert.fail();
      } catch (RecordNotFoundException recordNotFoundException) {
        // expected
      }

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCollection.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testRemoveHalfBigRecords() throws IOException {
    final var records = 5000;
    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfBigRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize =
          mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
              + CollectionPage.MAX_RECORD_SIZE
              + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
    }

    {
      Assert.assertEquals(records, paginatedCollection.getEntries());

      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCollection.deleteRecord(atomicOperation, collectionPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());
                }
              }

              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCollection.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCollection.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(collectionPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCollection.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      try {
        paginatedCollection.readRecord(deletedPosition);
        Assert.fail();
      } catch (RecordNotFoundException ignore) {
        // expected
      }

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCollection.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testRemoveHalfRecords() throws IOException {
    final var records = 10000;
    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(3 * CollectionPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              Assert.assertEquals(records, paginatedCollection.getEntries());
              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCollection.deleteRecord(atomicOperation, collectionPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCollection.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCollection.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(collectionPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCollection.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCollection.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      try {
        paginatedCollection.readRecord(deletedPosition);
        Assert.fail();
      } catch (RecordNotFoundException ignore) {
      }

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCollection.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testRemoveHalfRecordsAndAddAnotherHalfAgain() throws IOException {
    final var records = 10_000;
    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfRecordsAndAddAnotherHalfAgain seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(3 * CollectionPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCollection.getEntries());

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        deletedRecords++;

        Assert.assertEquals(paginatedCollection.getEntries(), records - deletedRecords);

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCollection.getEntries(), records - deletedRecords);

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(3 * CollectionPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
    }

    Assert.assertEquals(paginatedCollection.getEntries(), (long) (1.5 * records - deletedRecords));

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(recordVersion, rawBuffer.version);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      Assert.assertEquals(2, rawBuffer.recordType);
    }
  }

  @Test
  public void testUpdateOneSmallRecord() throws IOException {
    final var smallRecord = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    final var recordVersion = 2;

    var physicalPosition =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final var updatedRecordVersion = 3;
    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation));

    rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);

    Assert.assertEquals(updatedRecordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(updatedRecord);
    Assert.assertEquals(2, rawBuffer.recordType);
  }

  @Test
  public void testUpdateOneSmallRecordVersionIsLowerCurrentOne() throws IOException {
    final var smallRecord = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    final var recordVersion = 2;

    var physicalPosition =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final var updateRecordVersion = 1;

    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                smallRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);
    Assert.assertNotNull(rawBuffer);
    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation));
    rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);

    Assert.assertEquals(updateRecordVersion, rawBuffer.version);

    Assertions.assertThat(rawBuffer.buffer).isEqualTo(updatedRecord);
    Assert.assertEquals(2, rawBuffer.recordType);
  }

  @Test
  public void testUpdateOneSmallRecordVersionIsMinusTwo() throws IOException {
    final var smallRecord = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    final var recordVersion = 2;

    var physicalPosition =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final int updateRecordVersion;
    updateRecordVersion = -2;

    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                smallRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation));

    rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);

    Assert.assertEquals(updateRecordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
    Assert.assertEquals(2, rawBuffer.recordType);
  }

  @Test
  public void testUpdateOneBigRecord() throws IOException {
    final var bigRecord = new byte[(2 << 16) + 100];
    final var seed = System.nanoTime();
    System.out.println("testUpdateOneBigRecord seed " + seed);
    var mersenneTwisterFast = new Random(seed);

    mersenneTwisterFast.nextBytes(bigRecord);

    final var recordVersion = 2;

    var physicalPosition =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
    Assert.assertEquals(1, rawBuffer.recordType);

    final var updatedRecordVersion = 3;
    final var updatedBigRecord = new byte[(2 << 16) + 20];
    mersenneTwisterFast.nextBytes(updatedBigRecord);

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedBigRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCollection.updateRecord(
                physicalPosition.collectionPosition,
                updatedBigRecord,
                recordVersion,
                (byte) 2,
                atomicOperation));
    rawBuffer = paginatedCollection.readRecord(physicalPosition.collectionPosition);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(updatedBigRecord);
    Assert.assertEquals(2, rawBuffer.recordType);
  }

  @Test
  public void testUpdateManySmallRecords() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManySmallRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.collectionPosition, smallRecord);
          });
    }

    final int newRecordVersion;
    newRecordVersion = recordVersion + 1;

    {
      for (long collectionPosition : positionRecordMap.keySet()) {
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
                  var smallRecord = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(smallRecord);

                  if (collectionPosition == 100) {
                    System.out.println();
                  }

                  paginatedCollection.updateRecord(
                      collectionPosition, smallRecord, newRecordVersion, (byte) 3, atomicOperation);
                }
                throw new RollbackException();
              });
        } catch (RollbackException ignore) {
        }
      }
    }

    for (long collectionPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize = mersenneTwisterFast.nextInt(CollectionPage.MAX_RECORD_SIZE - 1) + 1;
        var smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.updateRecord(
                    collectionPosition, smallRecord, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(collectionPosition, smallRecord);
        updatedPositions.add(collectionPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());

      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(newRecordVersion, rawBuffer.version);
        Assert.assertEquals(3, rawBuffer.recordType);
      } else {
        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }
  }

  @Test
  public void testUpdateManyBigRecords() throws IOException {
    final var records = 5000;

    var seed = 1605083213475L; // System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManyBigRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize =
          mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
              + CollectionPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.collectionPosition, bigRecord);
          });
    }

    final var newRecordVersion = recordVersion + 1;
    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
                          + CollectionPage.MAX_RECORD_SIZE
                          + 1;
                  var bigRecord = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(bigRecord);

                  paginatedCollection.updateRecord(
                      collectionPosition, bigRecord, newRecordVersion, (byte) 3, atomicOperation);
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (long collectionPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize =
            mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE)
                + CollectionPage.MAX_RECORD_SIZE
                + 1;
        var bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.updateRecord(
                    collectionPosition, bigRecord, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(collectionPosition, bigRecord);
        updatedPositions.add(collectionPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());

      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(newRecordVersion, rawBuffer.version);

        Assert.assertEquals(3, rawBuffer.recordType);
      } else {
        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }
  }

  @Test
  public void testUpdateManyRecords() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManyRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    final var recordVersion = 2;

    for (var i = 0; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.collectionPosition, record);
    }

    final var newRecordVersion = recordVersion + 1;

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
                  var record = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(record);

                  paginatedCollection.updateRecord(
                      collectionPosition, record, newRecordVersion, (byte) 3, atomicOperation);
                }
              }

              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (long collectionPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
        var record = new byte[recordSize];
        mersenneTwisterFast.nextBytes(record);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.updateRecord(
                    collectionPosition, record, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(collectionPosition, record);
        updatedPositions.add(collectionPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCollection.readRecord(entry.getKey());
      Assert.assertNotNull(rawBuffer);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(newRecordVersion, rawBuffer.version);
        Assert.assertEquals(3, rawBuffer.recordType);
      } else {
        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }
  }

  @Test
  public void testForwardIteration() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testForwardIteration seed : " + seed);

    NavigableMap<Long, byte[]> positionRecordMap = new TreeMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCollection.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.collectionPosition, record);
          });
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                paginatedCollection.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
              }

              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCollection.deleteRecord(atomicOperation, collectionPosition));
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.collectionPosition, record);
    }

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        positionIterator.remove();
      }
    }

    var physicalPosition = new PhysicalPosition();
    physicalPosition.collectionPosition = 0;

    var positions = paginatedCollection.ceilingPositions(physicalPosition, Integer.MAX_VALUE);
    Assert.assertTrue(positions.length > 0);

    var counter = 0;
    for (long testedPosition : positionRecordMap.keySet()) {
      Assert.assertTrue(positions.length > 0);
      Assert.assertEquals(positions[0].collectionPosition, testedPosition);

      var positionToFind = positions[0];
      positions = paginatedCollection.higherPositions(positionToFind, Integer.MAX_VALUE);

      counter++;
    }

    Assert.assertEquals(paginatedCollection.getEntries(), counter);

    Assert.assertEquals(paginatedCollection.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCollection.getLastPosition(), (long) positionRecordMap.lastKey());
  }

  @Test
  public void testBackwardIteration() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testBackwardIteration seed : " + seed);

    NavigableMap<Long, byte[]> positionRecordMap = new TreeMap<>();

    final var recordVersion = 2;

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.collectionPosition, record);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                paginatedCollection.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
              }

              for (long collectionPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCollection.deleteRecord(atomicOperation, collectionPosition));
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.collectionPosition, record);
    }

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long collectionPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCollection.deleteRecord(atomicOperation, collectionPosition)));
        positionIterator.remove();
      }
    }

    var physicalPosition = new PhysicalPosition();
    physicalPosition.collectionPosition = Long.MAX_VALUE;

    var positions = paginatedCollection.floorPositions(physicalPosition, Integer.MAX_VALUE);
    Assert.assertTrue(positions.length > 0);

    positionIterator = positionRecordMap.descendingKeySet().iterator();
    var counter = 0;
    while (positionIterator.hasNext()) {
      Assert.assertTrue(positions.length > 0);

      long testedPosition = positionIterator.next();
      Assert.assertEquals(positions[positions.length - 1].collectionPosition, testedPosition);

      var positionToFind = positions[positions.length - 1];
      positions = paginatedCollection.lowerPositions(positionToFind, Integer.MAX_VALUE);

      counter++;
    }

    Assert.assertEquals(paginatedCollection.getEntries(), counter);

    Assert.assertEquals(paginatedCollection.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCollection.getLastPosition(), (long) positionRecordMap.lastKey());
  }

  @Test
  public void testGetPhysicalPosition() throws IOException {
    final var records = 10000;

    var seed = System.currentTimeMillis();
    var mersenneTwisterFast = new Random(seed);
    System.out.println("testGetPhysicalPosition seed : " + seed);

    Set<PhysicalPosition> positions = new HashSet<>();

    final var recordVersion = new ModifiableInteger();

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      recordVersion.increment();

      final var recordType = (byte) i;
      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion.value, recordType, null, atomicOperation));
      positions.add(physicalPosition);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                recordVersion.increment();

                paginatedCollection.createRecord(
                    record, recordVersion.value, (byte) i, null, atomicOperation);
              }

              for (var position : positions) {
                var physicalPosition = new PhysicalPosition();
                physicalPosition.collectionPosition = position.collectionPosition;

                physicalPosition = paginatedCollection.getPhysicalPosition(physicalPosition);

                Assert.assertEquals(physicalPosition.collectionPosition, position.collectionPosition);
                Assert.assertEquals(physicalPosition.recordType, position.recordType);

                Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
                if (mersenneTwisterFast.nextBoolean()) {
                  paginatedCollection.deleteRecord(atomicOperation, position.collectionPosition);
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * CollectionPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);
      recordVersion.increment();

      final var currentType = (byte) i;
      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCollection.createRecord(
                      record, recordVersion.value, currentType, null, atomicOperation));
      positions.add(physicalPosition);
    }

    Set<PhysicalPosition> removedPositions = new HashSet<>();
    for (var position : positions) {
      var physicalPosition = new PhysicalPosition();
      physicalPosition.collectionPosition = position.collectionPosition;

      physicalPosition = paginatedCollection.getPhysicalPosition(physicalPosition);

      Assert.assertEquals(physicalPosition.collectionPosition, position.collectionPosition);
      Assert.assertEquals(physicalPosition.recordType, position.recordType);

      Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCollection.deleteRecord(atomicOperation, position.collectionPosition));
        removedPositions.add(position);
      }
    }

    for (var position : positions) {
      var physicalPosition = new PhysicalPosition();
      physicalPosition.collectionPosition = position.collectionPosition;

      physicalPosition = paginatedCollection.getPhysicalPosition(physicalPosition);

      if (removedPositions.contains(position)) {
        Assert.assertNull(physicalPosition);
      } else {
        Assert.assertEquals(physicalPosition.collectionPosition, position.collectionPosition);
        Assert.assertEquals(physicalPosition.recordType, position.recordType);

        Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      }
    }
  }
}

