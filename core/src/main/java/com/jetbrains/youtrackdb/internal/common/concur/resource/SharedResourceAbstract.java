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

import java.util.concurrent.locks.StampedLock;

/**
 * Shared resource abstract class. Sub classes can acquire and release shared and exclusive locks.
 *
 * <p>Uses {@link StampedLock} internally. The read/write lock views returned by
 * {@link StampedLock#asReadLock()}/{@link StampedLock#asWriteLock()} provide the same
 * mutual-exclusion semantics as {@link java.util.concurrent.locks.ReentrantReadWriteLock}
 * but avoid the AQS CAS overhead on the uncontended read path.
 *
 * <p>Because {@link StampedLock} is <strong>not reentrant</strong>, this class adds
 * thread-owner tracking to support reentrant exclusive lock acquisition and write-to-read
 * downgrade (a thread holding the exclusive lock can call {@link #acquireSharedLock()}
 * without deadlocking). This restores the reentrancy semantics that the codebase relied on
 * when {@link java.util.concurrent.locks.ReentrantReadWriteLock} was used previously.
 */
public abstract class SharedResourceAbstract {

  // Exposed as a public final field so that AtomicOperationsManager.executeReadOperation
  // can use optimistic reads (tryOptimisticRead/validate) directly on the component's
  // lock without virtual dispatch or accessor overhead on the hot read path.
  public final StampedLock stampedLock = new StampedLock();

  // Tracks which thread holds the exclusive lock and how many times it re-entered.
  // Only accessed while holding the exclusive lock (or by the owning thread checking
  // identity), so no additional synchronization is needed beyond the volatile read
  // of exclusiveOwner for the identity check.
  private volatile Thread exclusiveOwner;
  // Plain field — only accessed by the thread that holds the exclusive lock
  // (guaranteed by the exclusiveOwner identity check gating every read/write).
  private int exclusiveHoldCount;

  protected void acquireSharedLock() {
    // If this thread already holds the exclusive lock, skip acquiring the shared lock.
    // The exclusive lock is strictly stronger — holding it already guarantees visibility
    // and mutual exclusion with other writers.
    if (exclusiveOwner == Thread.currentThread()) {
      return;
    }
    stampedLock.asReadLock().lock();
  }

  protected void releaseSharedLock() {
    // Paired with the no-op in acquireSharedLock() when the exclusive lock is held.
    if (exclusiveOwner == Thread.currentThread()) {
      return;
    }
    stampedLock.asReadLock().unlock();
  }

  /**
   * Returns {@code true} if the current thread holds the exclusive lock on this resource.
   * Used by {@code AtomicOperationsManager.executeReadOperation} to detect write-lock
   * reentrancy and skip the StampedLock read path (which would deadlock).
   */
  public boolean isExclusiveOwner() {
    return exclusiveOwner == Thread.currentThread();
  }

  protected void acquireExclusiveLock() {
    if (exclusiveOwner == Thread.currentThread()) {
      exclusiveHoldCount++;
      return;
    }
    stampedLock.asWriteLock().lock();
    exclusiveOwner = Thread.currentThread();
    exclusiveHoldCount = 1;
  }

  protected void releaseExclusiveLock() {
    if (exclusiveOwner != Thread.currentThread()) {
      throw new IllegalMonitorStateException();
    }
    if (--exclusiveHoldCount == 0) {
      exclusiveOwner = null;
      stampedLock.asWriteLock().unlock();
    }
  }
}
