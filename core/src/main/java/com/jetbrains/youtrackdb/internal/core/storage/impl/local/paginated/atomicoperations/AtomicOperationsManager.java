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
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.OneEntryPerKeyLockManager;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
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
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 12/3/13
 */
public class AtomicOperationsManager {
  private final ThreadLocal<AtomicOperation> currentOperation = new ThreadLocal<>();

  private final AbstractStorage storage;

  @Nonnull
  private final WriteAheadLog writeAheadLog;
  private final OneEntryPerKeyLockManager<String> lockManager =
      new OneEntryPerKeyLockManager<>(
          true, -1, GlobalConfiguration.COMPONENTS_LOCK_CACHE.getValueAsInteger());
  private final ReadCache readCache;
  private final WriteCache writeCache;

  private final Object segmentLock = new Object();
  private final AtomicOperationIdGen idGen;

  private final OperationsFreezer atomicOperationsFreezer = new OperationsFreezer();
  private final OperationsFreezer componentOperationsFreezer = new OperationsFreezer();
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

  public AtomicOperation startAtomicOperation(final byte[] metadata) throws IOException {
    var operation = currentOperation.get();
    if (operation != null) {
      throw new StorageException(storage.getName(), "Atomic operation already started");
    }

    atomicOperationsFreezer.startOperation();

    final long activeSegment;
    final long unitId;

    // transaction id and id of active segment should grow synchronously to maintain correct size of
    // WAL
    synchronized (segmentLock) {
      unitId = idGen.nextId();
      activeSegment = writeAheadLog.activeSegment();
    }

    atomicOperationsTable.startOperation(unitId, activeSegment);
    if (metadata != null) {
      writeAheadLog.logAtomicOperationStartRecord(true, unitId, metadata);
    } else {
      writeAheadLog.logAtomicOperationStartRecord(true, unitId);
    }

    operation = new AtomicOperationBinaryTracking(unitId, readCache, writeCache, storage.getId());

    currentOperation.set(operation);

    return operation;
  }

  public <T> T calculateInsideAtomicOperation(final byte[] metadata, final TxFunction<T> function)
      throws IOException {
    Throwable error = null;
    final var atomicOperation = startAtomicOperation(metadata);
    try {
      return function.accept(atomicOperation);
    } catch (Exception e) {
      error = e;
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during execution of atomic operation inside of storage "
                  + storage.getName()),
          e, storage.getName());
    } finally {
      endAtomicOperation(error);
    }
  }

  public void executeInsideAtomicOperation(final byte[] metadata, final TxConsumer consumer)
      throws IOException {
    Throwable error = null;
    final var atomicOperation = startAtomicOperation(metadata);
    try {
      consumer.accept(atomicOperation);
    } catch (Exception e) {
      error = e;
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Exception during execution of atomic operation inside of storage "
                  + storage.getName()),
          e, storage.getName());
    } finally {
      endAtomicOperation(error);
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
    } finally {
      endComponentOperation(atomicOperation);
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
    } finally {
      endComponentOperation(atomicOperation);
    }
  }

  private void startComponentOperation(
      final AtomicOperation atomicOperation, final String lockName) {
    acquireExclusiveLockTillOperationComplete(atomicOperation, lockName);
    atomicOperation.incrementComponentOperations();

    componentOperationsFreezer.startOperation();
  }

  private void endComponentOperation(final AtomicOperation atomicOperation) {
    atomicOperation.decrementComponentOperations();

    componentOperationsFreezer.endOperation();
  }

  public void alarmClearOfAtomicOperation() {
    final var current = currentOperation.get();

    if (current != null) {
      currentOperation.set(null);
    }
  }

  public long freezeAtomicOperations(@Nullable Supplier<? extends BaseException> throwException) {
    return atomicOperationsFreezer.freezeOperations(throwException);
  }

  public void releaseAtomicOperations(long id) {
    atomicOperationsFreezer.releaseOperations(id);
  }

  public final AtomicOperation getCurrentOperation() {
    return currentOperation.get();
  }

  /**
   * Ends the current atomic operation on this manager.
   */
  public void endAtomicOperation(final Throwable error) throws IOException {
    final var operation = currentOperation.get();

    if (operation == null) {
      LogManager.instance().error(this, "There is no atomic operation active", null);
      throw new DatabaseException(storage.getName(), "There is no atomic operation active");
    }

    try {
      storage.moveToErrorStateIfNeeded(error);

      if (error != null) {
        operation.rollbackInProgress();
      }

      try {
        final LogSequenceNumber lsn;
        if (!operation.isRollbackInProgress()) {
          lsn = operation.commitChanges(writeAheadLog);
        } else {
          lsn = null;
        }

        final var operationId = operation.getOperationUnitId();
        if (error != null) {
          atomicOperationsTable.rollbackOperation(operationId);
        } else {
          atomicOperationsTable.commitOperation(operationId);
          writeAheadLog.addEventAt(lsn, () -> atomicOperationsTable.persistOperation(operationId));
        }

      } finally {
        final var lockedObjectIterator = operation.lockedObjects().iterator();

        try {
          while (lockedObjectIterator.hasNext()) {
            final var lockedObject = lockedObjectIterator.next();
            lockedObjectIterator.remove();

            lockManager.releaseLock(this, lockedObject, OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
          }
        } finally {
          currentOperation.set(null);
        }
      }

    } finally {
      atomicOperationsFreezer.endOperation();
    }
  }

  public void ensureThatComponentsUnlocked() {
    final var operation = currentOperation.get();
    if (operation != null) {
      final var lockedObjectIterator = operation.lockedObjects().iterator();

      while (lockedObjectIterator.hasNext()) {
        final var lockedObject = lockedObjectIterator.next();
        lockedObjectIterator.remove();

        lockManager.releaseLock(this, lockedObject, OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
      }
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
  public void acquireExclusiveLockTillOperationComplete(DurableComponent durableComponent) {
    final var operation = currentOperation.get();
    assert operation != null;
    acquireExclusiveLockTillOperationComplete(operation, durableComponent.getLockName());
  }

  public void acquireReadLock(DurableComponent durableComponent) {
    assert durableComponent.getLockName() != null;

    storage.checkErrorState();
    lockManager.acquireLock(durableComponent.getLockName(), OneEntryPerKeyLockManager.LOCK.SHARED);
  }

  public void releaseReadLock(DurableComponent durableComponent) {
    assert durableComponent.getName() != null;
    assert durableComponent.getLockName() != null;

    lockManager.releaseLock(
        this, durableComponent.getLockName(), OneEntryPerKeyLockManager.LOCK.SHARED);
  }
}
