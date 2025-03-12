package com.jetbrains.youtrack.db.internal.core.storage.cluster;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
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

public abstract class LocalPaginatedClusterAbstract {
  protected static String buildDirectory;
  protected static PaginatedCluster paginatedCluster;
  protected static DatabaseSessionInternal databaseDocumentTx;
  protected static YouTrackDB youTrackDB;
  protected static String dbName;
  protected static AbstractPaginatedStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;

  @AfterClass
  public static void afterClass() throws IOException {
    final var firstPosition = paginatedCluster.getFirstPosition();
    var positions =
        paginatedCluster.ceilingPositions(new PhysicalPosition(firstPosition), Integer.MAX_VALUE);
    while (positions.length > 0) {
      for (var position : positions) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.deleteRecord(atomicOperation, position.clusterPosition));
      }
      positions = paginatedCluster.higherPositions(positions[positions.length - 1],
          Integer.MAX_VALUE);
    }
    atomicOperationsManager.executeInsideAtomicOperation(
        null, atomicOperation -> paginatedCluster.delete(atomicOperation));

    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  @Before
  public void beforeMethod() throws IOException {
    atomicOperationsManager = storage.getAtomicOperationsManager();
    final var firstPosition = paginatedCluster.getFirstPosition();
    var positions =
        paginatedCluster.ceilingPositions(new PhysicalPosition(firstPosition), Integer.MAX_VALUE);
    while (positions.length > 0) {
      for (var position : positions) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.deleteRecord(atomicOperation, position.clusterPosition));
      }

      positions = paginatedCluster.higherPositions(positions[positions.length - 1],
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
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation);
            paginatedCluster.deleteRecord(atomicOperation, physicalPosition[0].clusterPosition);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCluster.getEntries());
    Assert.assertNull(paginatedCluster.readRecord(physicalPosition[0].clusterPosition));

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation -> {
          physicalPosition[0] =
              paginatedCluster.createRecord(
                  smallRecord, recordVersion, (byte) 1, null, atomicOperation);
          paginatedCluster.deleteRecord(atomicOperation, physicalPosition[0].clusterPosition);
        });

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCluster.createRecord(
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
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCluster.getEntries());
    Assert.assertNull(paginatedCluster.readRecord(physicalPosition[0].clusterPosition));

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCluster.readRecord(physicalPosition[0].clusterPosition);
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
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    Assert.assertEquals(0, paginatedCluster.getEntries());
    Assert.assertNull(paginatedCluster.readRecord(physicalPosition[0].clusterPosition));

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            physicalPosition[0] =
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCluster.readRecord(physicalPosition[0].clusterPosition);
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
      var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
          });
    }

    final Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
              var smallRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(smallRecord);

              final var physicalPosition =
                  paginatedCluster.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation);
              rolledBackRecordSet.add(physicalPosition.clusterPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long clusterPosition : rolledBackRecordSet) {
      var rawBuffer = paginatedCluster.readRecord(clusterPosition);
      Assert.assertNull(rawBuffer);
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
          mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
              + ClusterPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
          });
    }

    Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize =
                  mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
                      + ClusterPage.MAX_RECORD_SIZE
                      + 1;
              var bigRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(bigRecord);

              final var physicalPosition =
                  paginatedCluster.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation);
              rolledBackRecordSet.add(physicalPosition.clusterPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long clusterPosition : rolledBackRecordSet) {
      var rawBuffer = paginatedCluster.readRecord(clusterPosition);
      Assert.assertNull(rawBuffer);
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize =
          mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
              + ClusterPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
          });
    }

    Set<Long> rolledBackRecordSet = new HashSet<>();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
              var smallRecord = new byte[recordSize];
              mersenneTwisterFast.nextBytes(smallRecord);

              final var physicalPosition =
                  paginatedCluster.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation);

              rolledBackRecordSet.add(physicalPosition.clusterPosition);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (long clusterPosition : rolledBackRecordSet) {
      var rawBuffer = paginatedCluster.readRecord(clusterPosition);
      Assert.assertNull(rawBuffer);
    }

    for (var i = records / 2; i < records; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);

            positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
          });
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
            paginatedCluster.allocatePosition((byte) 'd', atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var position =
        atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation -> paginatedCluster.allocatePosition((byte) 'd', atomicOperation));

    Assert.assertTrue(position.clusterPosition >= 0);
    var rec = paginatedCluster.readRecord(position.clusterPosition);
    Assert.assertNull(rec);
    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCluster.createRecord(new byte[20], 1, (byte) 'd', position, atomicOperation));

    rec = paginatedCluster.readRecord(position.clusterPosition);
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
              atomicOperation -> paginatedCluster.allocatePosition((byte) 'd', atomicOperation));
      Assert.assertTrue(position.clusterPosition >= 0);
      var rec = paginatedCluster.readRecord(position.clusterPosition);
      Assert.assertNull(rec);
      positions.add(position);
    }

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            for (var i = records / 2; i < records; i++) {
              var position =
                  paginatedCluster.allocatePosition((byte) 'd', atomicOperation);
              Assert.assertTrue(position.clusterPosition >= 0);
              var rec = paginatedCluster.readRecord(position.clusterPosition);
              Assert.assertNull(rec);
            }
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    for (var i = records / 2; i < records; i++) {
      var position =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation -> paginatedCluster.allocatePosition((byte) 'd', atomicOperation));
      Assert.assertTrue(position.clusterPosition >= 0);
      var rec = paginatedCluster.readRecord(position.clusterPosition);
      Assert.assertNull(rec);
      positions.add(position);
    }

    for (var i = 0; i < records; i++) {
      var position = positions.get(i);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              paginatedCluster.createRecord(
                  new byte[20], 1, (byte) 'd', position, atomicOperation));
      var rec = paginatedCluster.readRecord(position.clusterPosition);
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
      var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      smallRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              Assert.assertEquals(records, paginatedCluster.getEntries());
              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCluster.deleteRecord(atomicOperation, clusterPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCluster.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCluster.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(clusterPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      Assert.assertNull(paginatedCluster.readRecord(deletedPosition));
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCluster.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
          mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
              + ClusterPage.MAX_RECORD_SIZE
              + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
    }

    {
      Assert.assertEquals(records, paginatedCluster.getEntries());

      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCluster.deleteRecord(atomicOperation, clusterPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());
                }
              }

              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCluster.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCluster.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(clusterPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      Assert.assertNull(paginatedCluster.readRecord(deletedPosition));
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCluster.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
      var recordSize = mersenneTwisterFast.nextInt(3 * ClusterPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              var deletedRecords = 0;
              Assert.assertEquals(records, paginatedCluster.getEntries());
              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCluster.deleteRecord(atomicOperation, clusterPosition));
                  deletedRecords++;

                  Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }

      for (var entry : positionRecordMap.entrySet()) {
        var rawBuffer = paginatedCluster.readRecord(entry.getKey());
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(recordVersion, rawBuffer.version);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(2, rawBuffer.recordType);
      }
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCluster.getEntries());
    Set<Long> deletedPositions = new HashSet<>();
    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        deletedPositions.add(clusterPosition);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        deletedRecords++;

        Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
    for (long deletedPosition : deletedPositions) {
      Assert.assertNull(paginatedCluster.readRecord(deletedPosition));
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              Assert.assertFalse(paginatedCluster.deleteRecord(atomicOperation, deletedPosition)));
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
      var recordSize = mersenneTwisterFast.nextInt(3 * ClusterPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
    }

    var deletedRecords = 0;
    Assert.assertEquals(records, paginatedCluster.getEntries());

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        deletedRecords++;

        Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);

        positionIterator.remove();
      }
    }

    Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(3 * ClusterPage.MAX_RECORD_SIZE) + 1;

      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      bigRecord, recordVersion, (byte) 2, null, atomicOperation));

      positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
    }

    Assert.assertEquals(paginatedCluster.getEntries(), (long) (1.5 * records - deletedRecords));

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final var updatedRecordVersion = 3;
    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation));

    rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);

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
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final var updateRecordVersion = 1;

    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                smallRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);
    Assert.assertNotNull(rawBuffer);
    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation));
    rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);

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
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 1, null, atomicOperation));

    final int updateRecordVersion;
    updateRecordVersion = -2;

    final var updatedRecord = new byte[]{2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3};

    try {
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    var rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                smallRecord,
                updateRecordVersion,
                (byte) 2,
                atomicOperation));

    rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);

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
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 1, null, atomicOperation));

    var rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);
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
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedBigRecord,
                updatedRecordVersion,
                (byte) 2,
                atomicOperation);
            throw new RollbackException();
          });
    } catch (RollbackException ignore) {
    }

    rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(recordVersion, rawBuffer.version);
    Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
    Assert.assertEquals(1, rawBuffer.recordType);

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation ->
            paginatedCluster.updateRecord(
                physicalPosition.clusterPosition,
                updatedBigRecord,
                recordVersion,
                (byte) 2,
                atomicOperation));
    rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition);

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
      var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
      var smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    smallRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
          });
    }

    final int newRecordVersion;
    newRecordVersion = recordVersion + 1;

    {
      for (long clusterPosition : positionRecordMap.keySet()) {
        try {
          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              atomicOperation -> {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
                  var smallRecord = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(smallRecord);

                  if (clusterPosition == 100) {
                    System.out.println();
                  }

                  paginatedCluster.updateRecord(
                      clusterPosition, smallRecord, newRecordVersion, (byte) 3, atomicOperation);
                }
                throw new RollbackException();
              });
        } catch (RollbackException ignore) {
        }
      }
    }

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize = mersenneTwisterFast.nextInt(ClusterPage.MAX_RECORD_SIZE - 1) + 1;
        var smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.updateRecord(
                    clusterPosition, smallRecord, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(clusterPosition, smallRecord);
        updatedPositions.add(clusterPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
          mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
              + ClusterPage.MAX_RECORD_SIZE
              + 1;
      var bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    bigRecord, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
          });
    }

    final var newRecordVersion = recordVersion + 1;
    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
                          + ClusterPage.MAX_RECORD_SIZE
                          + 1;
                  var bigRecord = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(bigRecord);

                  paginatedCluster.updateRecord(
                      clusterPosition, bigRecord, newRecordVersion, (byte) 3, atomicOperation);
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize =
            mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE)
                + ClusterPage.MAX_RECORD_SIZE
                + 1;
        var bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.updateRecord(
                    clusterPosition, bigRecord, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(clusterPosition, bigRecord);
        updatedPositions.add(clusterPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    final var newRecordVersion = recordVersion + 1;

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  var recordSize =
                      mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
                  var record = new byte[recordSize];
                  mersenneTwisterFast.nextBytes(record);

                  paginatedCluster.updateRecord(
                      clusterPosition, record, newRecordVersion, (byte) 3, atomicOperation);
                }
              }

              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
        var record = new byte[recordSize];
        mersenneTwisterFast.nextBytes(record);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.updateRecord(
                    clusterPosition, record, newRecordVersion, (byte) 3, atomicOperation));

        positionRecordMap.put(clusterPosition, record);
        updatedPositions.add(clusterPosition);
      }
    }

    for (var entry : positionRecordMap.entrySet()) {
      var rawBuffer = paginatedCluster.readRecord(entry.getKey());
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
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> {
            final var physicalPosition =
                paginatedCluster.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
            positionRecordMap.put(physicalPosition.clusterPosition, record);
          });
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                paginatedCluster.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
              }

              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCluster.deleteRecord(atomicOperation, clusterPosition));
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        positionIterator.remove();
      }
    }

    var physicalPosition = new PhysicalPosition();
    physicalPosition.clusterPosition = 0;

    var positions = paginatedCluster.ceilingPositions(physicalPosition, Integer.MAX_VALUE);
    Assert.assertTrue(positions.length > 0);

    var counter = 0;
    for (long testedPosition : positionRecordMap.keySet()) {
      Assert.assertTrue(positions.length > 0);
      Assert.assertEquals(positions[0].clusterPosition, testedPosition);

      var positionToFind = positions[0];
      positions = paginatedCluster.higherPositions(positionToFind, Integer.MAX_VALUE);

      counter++;
    }

    Assert.assertEquals(paginatedCluster.getEntries(), counter);

    Assert.assertEquals(paginatedCluster.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCluster.getLastPosition(), (long) positionRecordMap.lastKey());
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
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                paginatedCluster.createRecord(
                    record, recordVersion, (byte) 2, null, atomicOperation);
              }

              for (long clusterPosition : positionRecordMap.keySet()) {
                if (mersenneTwisterFast.nextBoolean()) {
                  Assert.assertTrue(
                      paginatedCluster.deleteRecord(atomicOperation, clusterPosition));
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion, (byte) 2, null, atomicOperation));
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    var positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                Assert.assertTrue(paginatedCluster.deleteRecord(atomicOperation, clusterPosition)));
        positionIterator.remove();
      }
    }

    var physicalPosition = new PhysicalPosition();
    physicalPosition.clusterPosition = Long.MAX_VALUE;

    var positions = paginatedCluster.floorPositions(physicalPosition, Integer.MAX_VALUE);
    Assert.assertTrue(positions.length > 0);

    positionIterator = positionRecordMap.descendingKeySet().iterator();
    var counter = 0;
    while (positionIterator.hasNext()) {
      Assert.assertTrue(positions.length > 0);

      long testedPosition = positionIterator.next();
      Assert.assertEquals(positions[positions.length - 1].clusterPosition, testedPosition);

      var positionToFind = positions[positions.length - 1];
      positions = paginatedCluster.lowerPositions(positionToFind, Integer.MAX_VALUE);

      counter++;
    }

    Assert.assertEquals(paginatedCluster.getEntries(), counter);

    Assert.assertEquals(paginatedCluster.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCluster.getLastPosition(), (long) positionRecordMap.lastKey());
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
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      recordVersion.increment();

      final var recordType = (byte) i;
      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion.value, recordType, null, atomicOperation));
      positions.add(physicalPosition);
    }

    {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              for (var i = 0; i < records / 2; i++) {
                var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
                var record = new byte[recordSize];
                mersenneTwisterFast.nextBytes(record);

                recordVersion.increment();

                paginatedCluster.createRecord(
                    record, recordVersion.value, (byte) i, null, atomicOperation);
              }

              for (var position : positions) {
                var physicalPosition = new PhysicalPosition();
                physicalPosition.clusterPosition = position.clusterPosition;

                physicalPosition = paginatedCluster.getPhysicalPosition(physicalPosition);

                Assert.assertEquals(physicalPosition.clusterPosition, position.clusterPosition);
                Assert.assertEquals(physicalPosition.recordType, position.recordType);

                Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
                if (mersenneTwisterFast.nextBoolean()) {
                  paginatedCluster.deleteRecord(atomicOperation, position.clusterPosition);
                }
              }
              throw new RollbackException();
            });
      } catch (RollbackException ignore) {
      }
    }

    for (var i = 0; i < records / 2; i++) {
      var recordSize = mersenneTwisterFast.nextInt(2 * ClusterPage.MAX_RECORD_SIZE) + 1;
      var record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);
      recordVersion.increment();

      final var currentType = (byte) i;
      final var physicalPosition =
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  paginatedCluster.createRecord(
                      record, recordVersion.value, currentType, null, atomicOperation));
      positions.add(physicalPosition);
    }

    Set<PhysicalPosition> removedPositions = new HashSet<>();
    for (var position : positions) {
      var physicalPosition = new PhysicalPosition();
      physicalPosition.clusterPosition = position.clusterPosition;

      physicalPosition = paginatedCluster.getPhysicalPosition(physicalPosition);

      Assert.assertEquals(physicalPosition.clusterPosition, position.clusterPosition);
      Assert.assertEquals(physicalPosition.recordType, position.recordType);

      Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      if (mersenneTwisterFast.nextBoolean()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                paginatedCluster.deleteRecord(atomicOperation, position.clusterPosition));
        removedPositions.add(position);
      }
    }

    for (var position : positions) {
      var physicalPosition = new PhysicalPosition();
      physicalPosition.clusterPosition = position.clusterPosition;

      physicalPosition = paginatedCluster.getPhysicalPosition(physicalPosition);

      if (removedPositions.contains(position)) {
        Assert.assertNull(physicalPosition);
      } else {
        Assert.assertEquals(physicalPosition.clusterPosition, position.clusterPosition);
        Assert.assertEquals(physicalPosition.recordType, position.recordType);

        Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      }
    }
  }
}

