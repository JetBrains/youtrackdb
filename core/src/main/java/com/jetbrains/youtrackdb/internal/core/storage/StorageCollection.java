/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowsePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import javax.annotation.Nonnull;

public interface StorageCollection {

  enum ATTRIBUTES {
    NAME,
    CONFLICTSTRATEGY,
  }

  void configure(int iId, String iCollectionName) throws IOException;

  void configure(Storage iStorage, StorageCollectionConfiguration iConfig) throws IOException;

  void create(AtomicOperation atomicOperation) throws IOException;

  void open(AtomicOperation atomicOperation) throws IOException;

  void close() throws IOException;

  void close(boolean flush) throws IOException;

  void delete(AtomicOperation atomicOperation) throws IOException;

  void setCollectionName(String name);

  void setRecordConflictStrategy(String conflictStrategy);

  String encryption();

  long getTombstonesCount();

  /**
   * Allocates a physical position pointer on the storage for generate an id without a content.
   *
   * @param recordType the type of record of which allocate the position.
   * @return the allocated position.
   */
  PhysicalPosition allocatePosition(final byte recordType, final AtomicOperation atomicOperation)
      throws IOException;

  /**
   * Creates a new record in the collection.
   *
   * @param content           the content of the record.
   * @param recordVersion     the current version
   * @param recordType        the type of the record
   * @param allocatedPosition the eventual allocated position or null if there is no allocated
   *                          position.
   * @return the position where the record si created.
   */
  PhysicalPosition createRecord(
      byte[] content,
      int recordVersion,
      byte recordType,
      PhysicalPosition allocatedPosition,
      AtomicOperation atomicOperation);

  boolean deleteRecord(AtomicOperation atomicOperation, long collectionPosition);

  void updateRecord(
      long collectionPosition,
      byte[] content,
      int recordVersion,
      byte recordType,
      AtomicOperation atomicOperation);

  void updateRecordVersion(long collectionPosition,
      int recordVersion,
      AtomicOperation atomicOperation);

  @Nonnull
  RawBuffer readRecord(long collectionPosition) throws IOException;

  boolean exists();

  /**
   * Fills and return the PhysicalPosition object received as parameter with the physical position
   * of logical record iPosition
   */
  PhysicalPosition getPhysicalPosition(PhysicalPosition iPPosition) throws IOException;

  /**
   * Check if a rid is existent and deleted or not existent
   *
   * @return true if the record is deleted or not existent
   */
  boolean exists(long collectionPosition) throws IOException;

  long getEntries();

  long getFirstPosition() throws IOException;

  long getLastPosition() throws IOException;

  long getNextFreePosition() throws IOException;

  String getFileName();

  int getId();

  void synch() throws IOException;

  String getName();

  /**
   * Returns the size of the records contained in the collection in bytes.
   */
  long getRecordsSize() throws IOException;

  String compression();

  boolean isSystemCollection();

  PhysicalPosition[] higherPositions(PhysicalPosition position, int limit) throws IOException;

  PhysicalPosition[] ceilingPositions(PhysicalPosition position, int limit) throws IOException;

  PhysicalPosition[] lowerPositions(PhysicalPosition position, int limit) throws IOException;

  PhysicalPosition[] floorPositions(PhysicalPosition position, int limit) throws IOException;

  RecordConflictStrategy getRecordConflictStrategy();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * collection.
   */
  void acquireAtomicExclusiveLock();

  CollectionBrowsePage nextPage(long lastPosition, boolean forward) throws IOException;

  int getBinaryVersion();

  default Meters meters() {
    return Meters.NOOP;
  }

  record Meters(
      TimeRate create,
      TimeRate read,
      TimeRate update,
      TimeRate delete,
      TimeRate conflict
  ) {

    public static final Meters NOOP = new Meters(
        TimeRate.NOOP,
        TimeRate.NOOP,
        TimeRate.NOOP,
        TimeRate.NOOP,
        TimeRate.NOOP
    );
  }
}
