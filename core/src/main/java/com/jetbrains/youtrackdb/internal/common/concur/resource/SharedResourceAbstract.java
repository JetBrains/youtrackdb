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
 */
public abstract class SharedResourceAbstract {

  protected final StampedLock stampedLock = new StampedLock();

  protected void acquireSharedLock() {
    stampedLock.asReadLock().lock();
  }

  protected void releaseSharedLock() {
    stampedLock.asReadLock().unlock();
  }

  protected void acquireExclusiveLock() {
    stampedLock.asWriteLock().lock();
  }

  protected void releaseExclusiveLock() {
    stampedLock.asWriteLock().unlock();
  }
}
