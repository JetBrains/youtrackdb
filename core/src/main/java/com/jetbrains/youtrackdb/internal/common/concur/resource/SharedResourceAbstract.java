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
package com.jetbrains.youtrackdb.internal.common.concur.resource;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Shared resource abstract class. Sub classes can acquire and release shared and exclusive locks.
 *
 * <p>Uses {@link ReentrantReadWriteLock} internally. The lock is natively reentrant for both
 * read and write acquisitions, and supports write-to-read downgrade (a thread holding the
 * write lock can acquire the read lock without deadlocking).
 *
 * <p>When the current thread holds the exclusive (write) lock, shared lock acquire/release
 * calls are short-circuited — the write lock is strictly stronger and already guarantees
 * visibility and mutual exclusion.
 */
public abstract class SharedResourceAbstract {

  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  protected void acquireSharedLock() {
    // If this thread already holds the exclusive lock, skip acquiring the shared lock.
    // The exclusive lock is strictly stronger — holding it already guarantees visibility
    // and mutual exclusion with other writers.
    if (rwLock.isWriteLockedByCurrentThread()) {
      return;
    }
    rwLock.readLock().lock();
  }

  protected void releaseSharedLock() {
    // Paired with the no-op in acquireSharedLock() when the exclusive lock is held.
    if (rwLock.isWriteLockedByCurrentThread()) {
      return;
    }
    rwLock.readLock().unlock();
  }

  /**
   * Returns {@code true} if the current thread holds the exclusive lock on this resource.
   */
  public boolean isExclusiveOwner() {
    return rwLock.isWriteLockedByCurrentThread();
  }

  protected void acquireExclusiveLock() {
    rwLock.writeLock().lock();
  }

  protected void releaseExclusiveLock() {
    rwLock.writeLock().unlock();
  }
}
