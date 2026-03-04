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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Manages the lifecycle of atomic operations for the storage engine.
 *
 * <p>Write locks are acquired directly on each {@link DurableComponent}'s
 * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract#stampedLock
 * stampedLock}, giving per-component granularity with zero map lookups on the read path.
 * Synthetic (non-component) locks — used only by link bag ordering — are stored in a small
 * {@link ConcurrentHashMap}.
 *
 * @since 12/3/13
 */
public class AtomicOperationsManager {

  private final AbstractStorage storage;

  @Nonnull
  private final WriteAheadLog writeAheadLog;

  // Small map for synthetic locks that have no DurableComponent backing
  // (currently only link bag ordering locks from AbstractStorage.lockLinkBags).
  private final ConcurrentHashMap<String, StampedLock> syntheticLocks =
      new ConcurrentHashMap<>();

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
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, component);
    try {
      consumer.accept(atomicOperation);
    } catch (Exception e) {
      if (e instanceof CoreException coreException) {
        coreException.setComponentName(component.getLockName());
        coreException.setDbName(storage.getName());
      }

      throw BaseException.wrapException(
          new CommonDurableComponentException(
              "Exception during execution of component operation inside component "
                  + component.getLockName()
                  + " in storage "
                  + storage.getName(), component.getLockName(), storage.getName()),
          e, storage.getName());
    }
  }

  public void executeInsideComponentOperation(
      final AtomicOperation atomicOperation, final String lockName, final TxConsumer consumer) {
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, lockName);
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
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, component);
    try {
      return function.accept(atomicOperation);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommonDurableComponentException(
              "Exception during execution of component operation inside component "
                  + component.getLockName()
                  + " in storage "
                  + storage.getName(), component.getLockName(), storage.getName()),
          e, storage.getName());
    }
  }

  public <T> T calculateInsideComponentOperation(
      final AtomicOperation atomicOperation, final String lockName, final TxFunction<T> function) {
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, lockName);
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
        releaseLocks(operation);
        operation.deactivate();
      }
    } finally {
      writeOperationsFreezer.endOperation();
    }
  }

  public void ensureThatComponentsUnlocked(@Nonnull final AtomicOperation operation) {
    releaseLocks(operation);
  }

  private void releaseLocks(AtomicOperation operation) {
    // Release DurableComponent locks (the common case).
    // Check isExclusiveOwner() to make this method idempotent — it may be called
    // twice (once by endAtomicOperation, once by ensureThatComponentsUnlocked
    // in the finally block of AbstractStorage.commit).
    var compIter = operation.lockedComponents().iterator();
    while (compIter.hasNext()) {
      var component = compIter.next();
      if (component.isExclusiveOwner()) {
        component.unlockExclusive();
      }
      compIter.remove();
    }

    // Release synthetic locks (link bag ordering).
    // Guard with isWriteLocked() for defense-in-depth, matching the component
    // path's isExclusiveOwner() guard.
    var synthIter = operation.lockedSyntheticNames().iterator();
    while (synthIter.hasNext()) {
      String lockName = synthIter.next();
      synthIter.remove();
      var lock = syntheticLocks.get(lockName);
      if (lock != null && lock.isWriteLocked()) {
        lock.asWriteLock().unlock();
      }
    }

    // Clear the combined dedup set
    var nameIter = operation.lockedObjects().iterator();
    while (nameIter.hasNext()) {
      nameIter.next();
      nameIter.remove();
    }
  }

  /**
   * Acquires exclusive lock on the given {@link DurableComponent} for the lifetime of the
   * atomic operation. Uses the component's own
   * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract
   * StampedLock} directly — no external map lookup needed. The lock is reentrant via
   * {@code SharedResourceAbstract}'s owner-tracking.
   */
  public void acquireExclusiveLockTillOperationComplete(
      @Nonnull AtomicOperation operation, @Nonnull DurableComponent component) {
    storage.checkErrorState();

    if (operation.containsInLockedObjects(component.getLockName())) {
      return;
    }

    component.lockExclusive();
    operation.addLockedComponent(component);
    operation.addLockedObject(component.getLockName());
  }

  /**
   * Acquires a synthetic exclusive lock by name. Used for lock names that have no
   * DurableComponent backing (e.g., link bag ordering locks from
   * {@code AbstractStorage.lockLinkBags}). For DurableComponent-backed locks,
   * prefer the {@link #acquireExclusiveLockTillOperationComplete(AtomicOperation,
   * DurableComponent)} overload which uses the component's own StampedLock directly.
   */
  public void acquireExclusiveLockTillOperationComplete(
      AtomicOperation operation, String lockName) {
    storage.checkErrorState();

    if (operation.containsInLockedObjects(lockName)) {
      return;
    }

    // Synthetic lock path — only for link bag ordering locks
    var lock = syntheticLocks.computeIfAbsent(lockName, k -> new StampedLock());
    lock.asWriteLock().lock();
    operation.addLockedObject(lockName);
    operation.addLockedSyntheticName(lockName);
  }

  /**
   * Executes a read operation under an optimistic StampedLock with automatic fallback
   * to a blocking read lock on contention. Uses the component's own StampedLock directly
   * — zero map lookups on the read path. The happy path (no concurrent writer) costs
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
   * <p><b>Write-owner visibility for reader threads:</b> the volatile read inside
   * {@code tryOptimisticRead()} synchronizes with the volatile write inside the prior
   * {@code unlock()}, so any reader that gets a non-zero stamp is guaranteed to see the
   * cleared {@code exclusiveOwner}. If the unlock is not yet visible,
   * {@code tryOptimisticRead()} returns 0 and the reader falls through to
   * {@code readLock()}, which provides a full happens-before edge.
   *
   * @param component the durable component whose StampedLock to use
   * @param action    the read action to execute (must be idempotent and retryable —
   *                  it may be invoked twice if the optimistic read is invalidated)
   * @return the result of the action
   */
  public <T> T executeReadOperation(
      DurableComponent component, Callable<T> action) throws IOException {
    // Fast path: if the current thread already holds the exclusive lock on this
    // component (e.g., a read method called during an active atomic operation),
    // execute directly. StampedLock is non-reentrant — acquiring a read lock would
    // deadlock. Uses SharedResourceAbstract.isExclusiveOwner() which checks the
    // volatile exclusiveOwner field.
    if (component.isExclusiveOwner()) {
      try {
        return action.call();
      } catch (Exception e) {
        throwAsIOOrRuntime(e);
        return null; // unreachable
      }
    }

    var lock = component.stampedLock;

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
