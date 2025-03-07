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
package com.jetbrains.youtrack.db.internal.core.storage;

import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrack.db.internal.core.config.StorageClusterConfiguration;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.ClusterBrowsePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import javax.annotation.Nonnull;

public interface StorageCluster {

  enum ATTRIBUTES {
    NAME,
    CONFLICTSTRATEGY,
    STATUS
  }

  void configure(int iId, String iClusterName) throws IOException;

  void configure(Storage iStorage, StorageClusterConfiguration iConfig) throws IOException;

  void create(AtomicOperation atomicOperation) throws IOException;

  void open(AtomicOperation atomicOperation) throws IOException;

  void close() throws IOException;

  void close(boolean flush) throws IOException;

  void delete(AtomicOperation atomicOperation) throws IOException;

  void setClusterName(String name);

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
   * Creates a new record in the cluster.
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

  boolean deleteRecord(AtomicOperation atomicOperation, long clusterPosition);

  void updateRecord(
      long clusterPosition,
      byte[] content,
      int recordVersion,
      byte recordType,
      AtomicOperation atomicOperation);

  @Nonnull
  RawBuffer readRecord(long clusterPosition, boolean prefetchRecords) throws IOException;

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
  boolean exists(long clusterPosition) throws IOException;

  long getEntries();

  long getFirstPosition() throws IOException;

  long getLastPosition() throws IOException;

  long getNextPosition() throws IOException;

  String getFileName();

  int getId();

  void synch() throws IOException;

  String getName();

  /**
   * Returns the size of the records contained in the cluster in bytes.
   */
  long getRecordsSize() throws IOException;

  String compression();

  boolean isSystemCluster();

  PhysicalPosition[] higherPositions(PhysicalPosition position) throws IOException;

  PhysicalPosition[] ceilingPositions(PhysicalPosition position) throws IOException;

  PhysicalPosition[] lowerPositions(PhysicalPosition position) throws IOException;

  PhysicalPosition[] floorPositions(PhysicalPosition position) throws IOException;

  RecordConflictStrategy getRecordConflictStrategy();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * cluster.
   */
  void acquireAtomicExclusiveLock();

  ClusterBrowsePage nextPage(long lastPosition) throws IOException;

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
