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
package com.jetbrains.youtrackdb.internal.common.concur.lock;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

/**
 * Original Lock Manager implementation that uses a concurrent linked hash map to store one entry
 * per key. This could be very expensive in case the number of locks are a lot. This implementation
 * works better than {@link PartitionedLockManager} when running distributed because there is no way
 * to
 *
 * @param <T> Type of keys
 */
public class OneEntryPerKeyLockManager<T> implements LockManager<T> {

  public enum LOCK {
    SHARED,
    EXCLUSIVE
  }

  private final long acquireTimeout;
  protected final ConcurrentLinkedHashMap<T, CountableLock> map;
  private final boolean enabled;
  private final int amountOfCachedInstances;

  private static final Object NULL_KEY = new Object();

  @SuppressWarnings("serial")
  private static class CountableLock {

    private final AtomicInteger countLocks = new AtomicInteger(1);
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
  }

  public OneEntryPerKeyLockManager(
      final boolean iEnabled, final int iAcquireTimeout, final int amountOfCachedInstances) {
    this(
        iEnabled,
        iAcquireTimeout,
        GlobalConfiguration.ENVIRONMENT_LOCK_MANAGER_CONCURRENCY_LEVEL.getValueAsInteger(),
        amountOfCachedInstances);
  }

  public OneEntryPerKeyLockManager(
      final boolean iEnabled,
      final int iAcquireTimeout,
      final int concurrencyLevel,
      final int amountOfCachedInstances) {

    this.amountOfCachedInstances = amountOfCachedInstances;
    final var cL = closestInteger(concurrencyLevel);

    map =
        new ConcurrentLinkedHashMap.Builder<T, CountableLock>()
            .concurrencyLevel(cL)
            .maximumWeightedCapacity(Long.MAX_VALUE)
            .build();

    acquireTimeout = iAcquireTimeout;
    enabled = iEnabled;
  }

  @Override
  public Lock acquireSharedLock(final T key) {
    return acquireLock(key, LOCK.SHARED);
  }

  @Override
  public void releaseSharedLock(final T key) {
    releaseLock(Thread.currentThread(), key, LOCK.SHARED);
  }

  @Override
  public Lock acquireExclusiveLock(final T key) {
    return acquireLock(key, LOCK.EXCLUSIVE);
  }

  @Override
  public void releaseExclusiveLock(final T key) {
    releaseLock(Thread.currentThread(), key, LOCK.EXCLUSIVE);
  }

  public Lock acquireLock(final T iResourceId, final LOCK iLockType) {
    return acquireLock(iResourceId, iLockType, acquireTimeout);
  }

  @Nullable
  public Lock acquireLock(final T iResourceId, final LOCK iLockType, long iTimeout) {
    if (!enabled) {
      return null;
    }

    var immutableResource = getImmutableResourceId(iResourceId);
    if (immutableResource == null) {
      immutableResource = (T) NULL_KEY;
    }

    CountableLock lock;
    do {
      lock = map.get(immutableResource);

      if (lock != null) {
        final var oldLockCount = lock.countLocks.get();

        if (oldLockCount >= 0) {
          if (lock.countLocks.compareAndSet(oldLockCount, oldLockCount + 1)) {
            break;
          }
        } else {
          map.remove(immutableResource, lock);
        }
      }
    } while (lock != null);

    if (lock == null) {
      while (true) {
        lock = new CountableLock();

        var oldLock = map.putIfAbsent(immutableResource, lock);
        if (oldLock == null) {
          break;
        }

        lock = oldLock;
        final var oldValue = lock.countLocks.get();

        if (oldValue >= 0) {
          if (lock.countLocks.compareAndSet(oldValue, oldValue + 1)) {
            assert map.get(immutableResource) == lock;
            break;
          }
        } else {
          map.remove(immutableResource, lock);
        }
      }
    }

    if (map.size() > amountOfCachedInstances) {
      final var keyToRemoveIterator = map.ascendingKeySetWithLimit(1).iterator();
      if (keyToRemoveIterator.hasNext()) {
        final var keyToRemove = keyToRemoveIterator.next();
        final var lockToRemove = map.get(keyToRemove);
        if (lockToRemove != null) {
          final var counter = lockToRemove.countLocks.get();
          if (counter == 0 && lockToRemove.countLocks.compareAndSet(counter, -1)) {
            assert lockToRemove.countLocks.get() == -1;
            map.remove(keyToRemove, lockToRemove);
          }
        }
      }
    }

    try {
      if (iTimeout <= 0) {
        if (iLockType == LOCK.SHARED) {
          lock.readWriteLock.readLock().lock();
        } else {
          lock.readWriteLock.writeLock().lock();
        }
      } else {
        try {
          if (iLockType == LOCK.SHARED) {
            if (!lock.readWriteLock.readLock().tryLock(iTimeout, TimeUnit.MILLISECONDS)) {
              throw new LockException(
                  "Timeout ("
                      + iTimeout
                      + "ms) on acquiring resource '"
                      + iResourceId
                      + "' because is locked from another thread");
            }
          } else {
            if (!lock.readWriteLock.writeLock().tryLock(iTimeout, TimeUnit.MILLISECONDS)) {
              throw new LockException(
                  "Timeout ("
                      + iTimeout
                      + "ms) on acquiring resource '"
                      + iResourceId
                      + "' because is locked from another thread");
            }
          }
        } catch (java.lang.InterruptedException e) {
          Thread.currentThread().interrupt();
          throw BaseException.wrapException(
              new LockException(
                  "Thread interrupted while waiting for resource '" + iResourceId + "'"),
              e, (String) null);
        }
      }

      return new CountableLockWrapper(lock, iLockType == LOCK.SHARED);
    } catch (RuntimeException e) {
      final var usages = lock.countLocks.decrementAndGet();
      if (usages == 0) {
        map.remove(immutableResource);
      }

      throw e;
    }
  }

  public void releaseLock(final Object iRequester, T iResourceId, final LOCK iLockType)
      throws LockException {
    if (!enabled) {
      return;
    }

    if (iResourceId == null) {
      iResourceId = (T) NULL_KEY;
    }

    final var lock = map.get(iResourceId);
    if (lock == null) {
      throw new LockException(
          "Error on releasing a non acquired lock by the requester '"
              + iRequester
              + "' against the resource: '"
              + iResourceId
              + "'");
    }

    lock.countLocks.decrementAndGet();

    if (iLockType == LOCK.SHARED) {
      lock.readWriteLock.readLock().unlock();
    } else {
      lock.readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public Lock[] acquireExclusiveLocksInBatch(final T... values) {
    return acquireLockInBatch(values, true);
  }

  @Override
  public Lock[] acquireSharedLocksInBatch(final T... values) {
    return acquireLockInBatch(values, false);
  }

  @Override
  public Lock[] acquireExclusiveLocksInBatch(Collection<T> values) {
    if (values == null || values.isEmpty()) {
      return new Lock[0];
    }

    final List<Comparable> comparables = new ArrayList<Comparable>();

    var seenNulls = 0;
    for (var value : values) {
      if (value instanceof Comparable) {
        comparables.add((Comparable) value);
      } else if (value == null) {
        ++seenNulls;
      } else {
        throw new IllegalArgumentException(
            "In order to lock value in batch it should implement "
                + Comparable.class.getName()
                + " interface");
      }
    }

    Collections.sort(comparables);

    final var locks = new Lock[comparables.size() + seenNulls];
    var i = 0;
    for (var j = 0; j < seenNulls; ++j) {
      locks[i++] = acquireExclusiveLock((T) NULL_KEY);
    }
    for (var value : comparables) {
      locks[i++] = acquireExclusiveLock((T) value);
    }

    return locks;
  }

  // For tests purposes.
  public int getCountCurrentLocks() {
    return map.size();
  }

  protected T getImmutableResourceId(final T iResourceId) {
    return iResourceId;
  }

  private static int closestInteger(int value) {
    return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
  }

  @SuppressWarnings("NullableProblems")
  /* internal */ static class CountableLockWrapper implements Lock {

    private final CountableLock countableLock;
    private final boolean read;

    public CountableLockWrapper(CountableLock countableLock, boolean read) {
      this.countableLock = countableLock;
      this.read = read;
    }

    /* internal */ int getLockCount() { // for testing purposes
      return countableLock.countLocks.get();
    }

    @Override
    public void lock() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void lockInterruptibly() throws java.lang.InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws java.lang.InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      countableLock.countLocks.decrementAndGet();

      if (read) {
        countableLock.readWriteLock.readLock().unlock();
      } else {
        countableLock.readWriteLock.writeLock().unlock();
      }
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }
  }

  @Nullable
  protected Lock[] acquireLockInBatch(T[] values, final boolean exclusiveMode) {
    if (values == null || values.length == 0) {
      return null;
    }

    final List<Comparable> comparables = new ArrayList<Comparable>();

    var seenNulls = 0;
    for (var value : values) {
      if (value instanceof Comparable) {
        comparables.add((Comparable) value);
      } else if (value == null) {
        ++seenNulls;
      } else {
        throw new IllegalArgumentException(
            "In order to lock value in batch it should implement "
                + Comparable.class.getName()
                + " interface");
      }
    }

    Collections.sort(comparables);

    final var locks = new Lock[comparables.size() + seenNulls];
    var i = 0;
    for (var j = 0; j < seenNulls; ++j) {
      locks[i++] =
          exclusiveMode ? acquireExclusiveLock((T) NULL_KEY) : acquireSharedLock((T) NULL_KEY);
    }
    for (var value : comparables) {
      locks[i++] = exclusiveMode ? acquireExclusiveLock((T) value) : acquireSharedLock((T) value);
    }

    return locks;
  }
}
