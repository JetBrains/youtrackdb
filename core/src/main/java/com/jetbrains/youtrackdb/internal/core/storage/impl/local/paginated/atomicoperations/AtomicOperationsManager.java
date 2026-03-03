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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommonDurableComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Manages the lifecycle of atomic operations for the storage engine.
 *
 * @since 12/3/13
 */
public class AtomicOperationsManager {

  private final AbstractStorage storage;

  @Nonnull
  private final WriteAheadLog writeAheadLog;
  private final StampedLock[] componentLocks;
  // Tracks the thread that holds the write lock on each stripe. Used by
  // executeReadOperation to detect write-lock reentrancy (StampedLock is
  // non-reentrant). Only the owning thread can match == Thread.currentThread(),
  // so plain (non-volatile) access is sufficient — single-thread consistency
  // guarantees the writing thread sees its own store.
  private final Thread[] stripeWriteOwners;
  private final int stripeMask;
  private final ReadCache readCache;
  private final WriteCache writeCache;

  private final AtomicOperationIdGen idGen;
  private final ReentrantLock segmentLock = new ReentrantLock();

  private final OperationsFreezer writeOperationsFreezer = new OperationsFreezer();
  private final AtomicOperationsTable atomicOperationsTable;

  public AtomicOperationsManager(
      AbstractStorage storage, AtomicOperationsTable atomicOperationsTable) {
    this.storage = storage;
    this.writeAheadLog = storage.getWALInstance();
    this.readCache = storage.getReadCache();
    this.writeCache = storage.getWriteCache();

    this.idGen = storage.getIdGen();
    this.atomicOperationsTable = atomicOperationsTable;

    int stripeCount = closestPowerOfTwo(
        GlobalConfiguration.ENVIRONMENT_LOCK_MANAGER_CONCURRENCY_LEVEL.getValueAsInteger());
    this.componentLocks = new StampedLock[stripeCount];
    this.stripeWriteOwners = new Thread[stripeCount];
    for (int i = 0; i < stripeCount; i++) {
      this.componentLocks[i] = new StampedLock();
    }
    this.stripeMask = stripeCount - 1;
  }

  private static int closestPowerOfTwo(int value) {
    if (value <= 1) {
      return 1;
    }
    return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
  }

  int stripeIndex(String lockName) {
    return lockName.hashCode() & 0x7fffffff & stripeMask;
  }

  public AtomicOperation startAtomicOperation() {
    var snapshot = atomicOperationsTable.snapshotAtomicOperationTableState(idGen.getLastId());
    return new AtomicOperationBinaryTracking(readCache, writeCache, storage.getId(),
        snapshot, storage.getSharedSnapshotIndex(), storage.getVisibilityIndex(),
        storage.getSnapshotIndexSize());
  }

  public void startToApplyOperations(AtomicOperation atomicOperation) {
    writeOperationsFreezer.startOperation();

    final long activeSegment;

    // Transaction id, active segment, and table registration must all happen
    // under the same lock to guarantee that operations appear in the table in
    // strictly increasing timestamp order. Without this, a higher-TS operation
    // could register before a lower-TS one, creating NOT_STARTED gaps that
    // violate Snapshot Isolation's assumption that all TXs below minActiveTs
    // are completed.
    final long commitTs;
    segmentLock.lock();
    try {
      commitTs = idGen.nextId();
      activeSegment = writeAheadLog.activeSegment();
      atomicOperationsTable.startOperation(commitTs, activeSegment);
    } finally {
      segmentLock.unlock();
    }

    atomicOperation.startToApplyOperations(commitTs);
  }

  public <T> T calculateInsideAtomicOperation(final TxFunction<T> function)
      throws IOException {
    Throwable error = null;
    final var atomicOperation = startAtomicOperation();
    try {
      startToApplyOperations(atomicOperation);
      return function.accept(atomicOperation);
    } catch (Exception e) {
      error = e;
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during execution of atomic operation inside of storage "
                  + storage.getName()),
          e, storage.getName());
    } finally {
      endAtomicOperation(atomicOperation, error);
    }
  }

  public void executeInsideAtomicOperation(final TxConsumer consumer)
      throws IOException {
    Throwable error = null;
    final var atomicOperation = startAtomicOperation();
    try {
      startToApplyOperations(atomicOperation);
      consumer.accept(atomicOperation);
    } catch (Exception e) {
      error = e;
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during execution of atomic operation inside of storage "
                  + storage.getName()),
          e, storage.getName());
    } finally {
      endAtomicOperation(atomicOperation, error);
    }
  }

  public void executeInsideComponentOperation(
      final AtomicOperation atomicOperation,
      final DurableComponent component,
      final TxConsumer consumer) {
    executeInsideComponentOperation(atomicOperation, component.getLockName(), consumer);
  }

  public void executeInsideComponentOperation(
      final AtomicOperation atomicOperation, final String lockName, final TxConsumer consumer) {
    Objects.requireNonNull(atomicOperation);
    startComponentOperation(atomicOperation, lockName);
    try {
      consumer.accept(atomicOperation);
    } catch (Exception e) {
      if (e instanceof CoreException coreException) {
        coreException.setComponentName(lockName);
        coreException.setDbName(storage.getName());
      }

      throw BaseException.wrapException(
          new CommonDurableComponentException(
              "Exception during execution of component operation inside component "
                  + lockName
                  + " in storage "
                  + storage.getName(), lockName, storage.getName()),
          e, storage.getName());
    }
  }

  public <T> T calculateInsideComponentOperation(
      final AtomicOperation atomicOperation,
      final DurableComponent component,
      final TxFunction<T> function) {
    return calculateInsideComponentOperation(atomicOperation, component.getLockName(), function);
  }

  public <T> T calculateInsideComponentOperation(
      final AtomicOperation atomicOperation, final String lockName, final TxFunction<T> function) {
    Objects.requireNonNull(atomicOperation);
    startComponentOperation(atomicOperation, lockName);
    try {
      return function.accept(atomicOperation);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommonDurableComponentException(
              "Exception during execution of component operation inside component "
                  + lockName
                  + " in storage "
                  + storage.getName(), lockName, storage.getName()),
          e, storage.getName());
    }
  }

  private void startComponentOperation(final AtomicOperation atomicOperation,
      final String lockName) {
    acquireExclusiveLockTillOperationComplete(atomicOperation, lockName);
  }


  public long freezeWriteOperations(@Nullable Supplier<? extends BaseException> throwException) {
    return writeOperationsFreezer.freezeOperations(throwException);
  }

  public void unfreezeWriteOperations(long id) {
    writeOperationsFreezer.releaseOperations(id);
  }

  /**
   * Ends the current atomic operation on this manager.
   */
  public void endAtomicOperation(@Nonnull final AtomicOperation operation, final Throwable error)
      throws IOException {
    try {
      storage.moveToErrorStateIfNeeded(error);

      if (error != null) {
        operation.rollbackInProgress();
      }

      try {
        final LogSequenceNumber lsn;
        var commitTs = operation.getCommitTs();
        if (!operation.isRollbackInProgress()) {
          lsn = operation.commitChanges(commitTs, writeAheadLog);
        } else {
          lsn = null;
        }

        if (error != null) {
          atomicOperationsTable.rollbackOperation(commitTs);
        } else {
          atomicOperationsTable.commitOperation(commitTs);
          writeAheadLog.addEventAt(lsn, () -> atomicOperationsTable.persistOperation(commitTs));
        }

      } finally {
        // Clear name tracking
        final var lockedObjectIterator = operation.lockedObjects().iterator();
        while (lockedObjectIterator.hasNext()) {
          lockedObjectIterator.next();
          lockedObjectIterator.remove();
        }

        // Release each unique stripe once and clear the set
        releaseStripes(operation);

        operation.deactivate();
      }
    } finally {
      writeOperationsFreezer.endOperation();
    }
  }

  public void ensureThatComponentsUnlocked(@Nonnull final AtomicOperation operation) {
    // Clear name tracking
    final var lockedObjectIterator = operation.lockedObjects().iterator();
    while (lockedObjectIterator.hasNext()) {
      lockedObjectIterator.next();
      lockedObjectIterator.remove();
    }

    // Release each unique stripe once and clear the set
    releaseStripes(operation);
  }

  private void releaseStripes(AtomicOperation operation) {
    var stripes = operation.lockedStripes();
    var stripesIter = stripes.intIterator();
    while (stripesIter.hasNext()) {
      int stripe = stripesIter.nextInt();
      // Must null out owner BEFORE unlock to avoid racing with the next writer
      // who would set their own thread here after acquiring the lock.
      stripeWriteOwners[stripe] = null;
      componentLocks[stripe].asWriteLock().unlock();
    }
    // Clear separately — fastutil IntOpenHashSet.intIterator() does not support
    // remove during iteration.
    stripes.clear();
  }

  /**
   * Acquires exclusive lock with the given lock name in the given atomic operation.
   * If two component names hash to the same stripe, the second write lock acquisition
   * is skipped because the stripe is already exclusively held.
   *
   * @param operation the atomic operation to acquire the lock in.
   * @param lockName  the lock name to acquire.
   */
  public void acquireExclusiveLockTillOperationComplete(
      AtomicOperation operation, String lockName) {
    storage.checkErrorState();

    if (operation.containsInLockedObjects(lockName)) {
      return;
    }

    int stripe = stripeIndex(lockName);
    if (!operation.containsLockedStripe(stripe)) {
      componentLocks[stripe].asWriteLock().lock();
      stripeWriteOwners[stripe] = Thread.currentThread();
      operation.addLockedStripe(stripe);
    }
    operation.addLockedObject(lockName);
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for the
   * {@code durableComponent}.
   */
  public void acquireExclusiveLockTillOperationComplete(@Nonnull AtomicOperation operation,
      @Nonnull DurableComponent durableComponent) {
    storage.checkErrorState();

    acquireExclusiveLockTillOperationComplete(operation, durableComponent.getLockName());
  }

  /**
   * Executes a read operation under an optimistic StampedLock with automatic fallback
   * to a blocking read lock on contention. The happy path (no concurrent writer) costs
   * only two volatile reads and zero CAS operations.
   *
   * <p>The protocol:
   * <ol>
   *   <li>Try an optimistic read (volatile read, no CAS)</li>
   *   <li>Execute the action</li>
   *   <li>If the stamp is still valid, return the result</li>
   *   <li>If an exception occurred and the stamp is valid, it's a real error — rethrow</li>
   *   <li>Otherwise, acquire a blocking read lock and retry the action</li>
   * </ol>
   *
   * @param component the durable component whose stripe lock to use
   * @param action    the read action to execute (must be idempotent and retryable —
   *                  it may be invoked twice if the optimistic read is invalidated)
   * @return the result of the action
   */
  public <T> T executeReadOperation(
      DurableComponent component, Callable<T> action) throws IOException {
    int stripe = stripeIndex(component.getLockName());
    var lock = componentLocks[stripe];

    // Fast path: if the current thread already holds the write lock on this stripe
    // (e.g., a read method called during an active atomic operation), execute directly.
    // StampedLock is non-reentrant — acquiring a read lock would deadlock.
    if (stripeWriteOwners[stripe] == Thread.currentThread()) {
      try {
        return action.call();
      } catch (Exception e) {
        throwAsIOOrRuntime(e);
        return null; // unreachable
      }
    }

    // Attempt 1: optimistic read (no CAS)
    long stamp = lock.tryOptimisticRead();
    if (stamp != 0) {
      try {
        T result = action.call();
        if (lock.validate(stamp)) {
          return result;
        }
      } catch (Exception e) {
        if (lock.validate(stamp)) {
          // Real error, not caused by concurrent modification
          throwAsIOOrRuntime(e);
        }
        // Concurrent modification caused the exception — fall through to a single
        // bounded retry under the blocking read lock. If the error is genuine, it
        // will re-surface on the retry.
      }
    }

    // Attempt 2: blocking read lock (CAS only on contention)
    stamp = lock.readLock();
    try {
      return action.call();
    } catch (Exception e) {
      throwAsIOOrRuntime(e);
      return null; // unreachable
    } finally {
      lock.unlockRead(stamp);
    }
  }

  private static void throwAsIOOrRuntime(Exception e) throws IOException {
    if (e instanceof IOException ioe) {
      throw ioe;
    }
    if (e instanceof RuntimeException re) {
      throw re;
    }
    throw new IOException(e);
  }
}
