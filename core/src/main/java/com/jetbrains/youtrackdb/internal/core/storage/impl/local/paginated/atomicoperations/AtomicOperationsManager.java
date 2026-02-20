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
import com.jetbrains.youtrackdb.internal.common.concur.lock.OneEntryPerKeyLockManager;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class AtomicOperationsManager {

  private final AbstractStorage storage;

  @Nonnull
  private final WriteAheadLog writeAheadLog;
  private final OneEntryPerKeyLockManager<String> lockManager =
      new OneEntryPerKeyLockManager<>(
          true, -1, GlobalConfiguration.COMPONENTS_LOCK_CACHE.getValueAsInteger());
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
  }

  public AtomicOperation startAtomicOperation() {
    var snapshot = atomicOperationsTable.snapshotAtomicOperationTableState(idGen.getLastId());
    return new AtomicOperationBinaryTracking(readCache, writeCache, storage.getId(),
        snapshot, storage.getSharedSnapshotIndex(), storage.getVisibilityIndex());
  }

  public void startToApplyOperations(AtomicOperation atomicOperation) {
    writeOperationsFreezer.startOperation();

    final long activeSegment;

    // transaction id and id of active segment should grow synchronously to maintain correct size of
    // WAL
    final long commitTs;
    segmentLock.lock();
    try {
      commitTs = idGen.nextId();
      activeSegment = writeAheadLog.activeSegment();
    } finally {
      segmentLock.unlock();
    }

    atomicOperationsTable.startOperation(commitTs, activeSegment);
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
        final var lockedObjectIterator = operation.lockedObjects().iterator();

        while (lockedObjectIterator.hasNext()) {
          final var lockedObject = lockedObjectIterator.next();
          lockedObjectIterator.remove();

          lockManager.releaseLock(this, lockedObject, OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
        }

        operation.deactivate();
      }
    } finally {
      writeOperationsFreezer.endOperation();
    }
  }

  public void ensureThatComponentsUnlocked(@Nonnull final AtomicOperation operation) {
    final var lockedObjectIterator = operation.lockedObjects().iterator();

    while (lockedObjectIterator.hasNext()) {
      final var lockedObject = lockedObjectIterator.next();
      lockedObjectIterator.remove();

      lockManager.releaseLock(this, lockedObject, OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
    }
  }

  /**
   * Acquires exclusive lock with the given lock name in the given atomic operation.
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

    lockManager.acquireLock(lockName, OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
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

  public void acquireReadLock(DurableComponent durableComponent) {
    assert durableComponent.getLockName() != null;

    lockManager.acquireLock(durableComponent.getLockName(), OneEntryPerKeyLockManager.LOCK.SHARED);
  }

  public void releaseReadLock(DurableComponent durableComponent) {
    assert durableComponent.getName() != null;
    assert durableComponent.getLockName() != null;

    lockManager.releaseLock(
        this, durableComponent.getLockName(), OneEntryPerKeyLockManager.LOCK.SHARED);
  }
}
