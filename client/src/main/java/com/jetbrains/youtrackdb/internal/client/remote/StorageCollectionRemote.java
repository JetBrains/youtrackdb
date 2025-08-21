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
package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CollectionBrowsePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Remote collection implementation
 */
public class StorageCollectionRemote implements StorageCollection {

  private String name;
  private int id;

  @Override
  public void configure(int iId, String iCollectionName) {
    id = iId;
    name = iCollectionName;
  }


  @Override
  public void configure(Storage iStorage, StorageCollectionConfiguration iConfig) {
    id = iConfig.getId();
    name = iConfig.getName();
  }

  @Override
  public void create(AtomicOperation atomicOperation) {
  }


  @Override
  public void open(AtomicOperation atomicOperation) {
  }

  @Override
  public void close() {
  }

  @Override
  public void close(boolean flush) {
  }

  @Override
  public PhysicalPosition allocatePosition(byte recordType, AtomicOperation atomicOperation) {
    throw new UnsupportedOperationException("allocatePosition");
  }

  @Override
  public PhysicalPosition createRecord(
      byte[] content,
      int recordVersion,
      byte recordType,
      PhysicalPosition allocatedPosition,
      AtomicOperation atomicOperation) {
    throw new UnsupportedOperationException("createRecord");
  }

  @Override
  public boolean deleteRecord(AtomicOperation atomicOperation, long collectionPosition) {
    throw new UnsupportedOperationException("deleteRecord");
  }

  @Override
  public void updateRecord(
      long collectionPosition,
      byte[] content,
      int recordVersion,
      byte recordType,
      AtomicOperation atomicOperation) {
    throw new UnsupportedOperationException("updateRecord");
  }

  @Override
  public void updateRecordVersion(long collectionPosition, int recordVersion,
      AtomicOperation atomicOperation) {
    throw new UnsupportedOperationException("updateRecordVersion");
  }

  @Nonnull
  @Override
  public RawBuffer readRecord(long collectionPosition) throws IOException {
    throw new UnsupportedEncodingException();
  }

  @Override
  public void setCollectionName(final String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setRecordConflictStrategy(final String conflictStrategy) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean exists() {
    throw new UnsupportedOperationException("exists");
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
  }

  @Nullable
  public Object set(ATTRIBUTES iAttribute, Object iValue) {
    return null;
  }

  @Override
  public String encryption() {
    throw new UnsupportedOperationException("encryption");
  }

  @Override
  @Nullable
  public PhysicalPosition getPhysicalPosition(PhysicalPosition iPPosition) {
    return null;
  }

  @Override
  public long getEntries() {
    return 0;
  }

  @Override
  public long getTombstonesCount() {
    throw new UnsupportedOperationException("getTombstonesCount()");
  }

  @Override
  public long getFirstPosition() {
    return 0;
  }

  @Override
  public long getLastPosition() {
    return 0;
  }

  @Override
  public long getNextFreePosition() {
    return 0;
  }

  @Override
  public String getFileName() {
    throw new UnsupportedOperationException("getFileName()");
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void synch() {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public long getRecordsSize() {
    throw new UnsupportedOperationException("getRecordsSize()");
  }

  @Override
  public boolean isSystemCollection() {
    return false;
  }

  @Override
  public PhysicalPosition[] higherPositions(PhysicalPosition position, int limit) {
    throw new UnsupportedOperationException("higherPositions()");
  }

  @Override
  public PhysicalPosition[] lowerPositions(PhysicalPosition position, int limit) {
    throw new UnsupportedOperationException("lowerPositions()");
  }

  @Override
  public PhysicalPosition[] ceilingPositions(PhysicalPosition position, int limit) {
    throw new UnsupportedOperationException("ceilingPositions()");
  }

  @Override
  public PhysicalPosition[] floorPositions(PhysicalPosition position, int limit) {
    throw new UnsupportedOperationException("floorPositions()");
  }

  @Override
  public boolean exists(long collectionPosition) throws IOException {
    throw new UnsupportedOperationException("exists()");
  }

  @Override
  public String compression() {
    throw new UnsupportedOperationException("compression()");
  }

  @Nullable
  @Override
  public RecordConflictStrategy getRecordConflictStrategy() {
    return null;
  }

  @Override
  public void acquireAtomicExclusiveLock() {
    throw new UnsupportedOperationException("remote collection doesn't support atomic locking");
  }

  @Override
  public CollectionBrowsePage nextPage(long lastPosition) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getBinaryVersion() {
    throw new UnsupportedOperationException(
        "Operation is not supported for given collection implementation");
  }
}
