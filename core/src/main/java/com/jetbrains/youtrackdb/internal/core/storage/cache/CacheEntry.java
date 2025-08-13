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

package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LRUList;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.io.Closeable;

/**
 * @since 7/23/13
 */
public interface CacheEntry extends Closeable {

  CachePointer getCachePointer();

  void clearCachePointer();

  long getFileId();

  int getPageIndex();

  void acquireExclusiveLock();

  void releaseExclusiveLock();

  void acquireSharedLock();

  void releaseSharedLock();

  int getUsagesCount();

  void incrementUsages();

  /**
   * DEBUG only !!
   *
   * @return Whether lock acquired on current entry
   */
  boolean isLockAcquiredByCurrentThread();

  void decrementUsages();

  WALChanges getChanges();

  LogSequenceNumber getEndLSN();

  LogSequenceNumber getInitialLSN();

  void setInitialLSN(LogSequenceNumber lsn);

  void setEndLSN(LogSequenceNumber endLSN);

  boolean acquireEntry();

  void releaseEntry();

  boolean isReleased();

  boolean isAlive();

  boolean freeze();

  boolean isFrozen();

  void makeDead();

  boolean isDead();

  CacheEntry getNext();

  CacheEntry getPrev();

  void setPrev(CacheEntry prev);

  void setNext(CacheEntry next);

  void setContainer(LRUList lruList);

  LRUList getContainer();

  boolean isNewlyAllocatedPage();

  void markAllocated();

  void clearAllocationFlag();

  boolean insideCache();

  PageKey getPageKey();
}
