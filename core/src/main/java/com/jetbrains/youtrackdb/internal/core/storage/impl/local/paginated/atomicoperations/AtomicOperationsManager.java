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

import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Manages the lifecycle of atomic operations for the storage engine.
 *
 * <p>Write locks are acquired directly on each {@link StorageComponent}'s
 * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract
 * ReentrantReadWriteLock}, giving per-component granularity.
 *
 * @since 12/3/13
 */
public class AtomicOperationsManager {

  private final AbstractStorage storage;

  @Nonnull
  private final WriteAheadLog writeAheadLog;

  private final ReadCache readCache;
  private final WriteCache writeCache;

  private final AtomicOperationIdGen idGen;
  private final ScalableRWLock segmentLock = new ScalableRWLock();

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
    // Read lastId under segmentLock to guarantee that all operations with
    // commitTs <= lastId have already been registered in the operations table
    // (via startOperation). Without this lock, a concurrent writer thread could
    // have called idGen.nextId() (volatile write) but not yet called
    // startOperation(), causing the snapshot scan to miss the in-progress
    // operation. The reader would then treat the writer's commitTs as visible
    // (below the snapshot boundary), violating snapshot isolation when the
    // writer commits between two reads within the same transaction.
    final long lastId;
    segmentLock.sharedLock();
    try {
      lastId = idGen.getLastId();
    } finally {
      segmentLock.sharedUnlock();
    }
    var snapshot = atomicOperationsTable.snapshotAtomicOperationTableState(lastId);
    return new AtomicOperationBinaryTracking(readCache, writeCache, writeAheadLog,
        storage.getId(),
        snapshot, storage.getSharedSnapshotIndex(), storage.getVisibilityIndex(),
        storage.getSnapshotIndexSize(),
        storage.getSharedEdgeSnapshotIndex(), storage.getEdgeVisibilityIndex(),
        storage.getEdgeSnapshotIndexSize());
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
    segmentLock.exclusiveLock();
    try {
      commitTs = idGen.nextId();
      activeSegment = writeAheadLog.activeSegment();
      atomicOperationsTable.startOperation(commitTs, activeSegment);
    } finally {
      segmentLock.exclusiveUnlock();
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
    } catch (Exception | AssertionError e) {
      // AssertionError is included so a -ea-only assert thrown from the lambda body
      // routes through endAtomicOperation(op, error) for rollback, rather than
      // bypassing the catch, leaving error=null, and committing the op as if it
      // succeeded. The error variable is typed Throwable and BaseException.wrapException
      // accepts Throwable as its cause, so no further signature change is needed.
      // Error superclasses other than AssertionError (OutOfMemoryError,
      // StackOverflowError, LinkageError) are deliberately not caught here — the
      // rollback path is not the right response for those.
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
    } catch (Exception | AssertionError e) {
      // AssertionError is included so a -ea-only assert thrown from the lambda body
      // routes through endAtomicOperation(op, error) for rollback, rather than
      // bypassing the catch, leaving error=null, and committing the op as if it
      // succeeded. The error variable is typed Throwable and BaseException.wrapException
      // accepts Throwable as its cause, so no further signature change is needed.
      // Error superclasses other than AssertionError (OutOfMemoryError,
      // StackOverflowError, LinkageError) are deliberately not caught here — the
      // rollback path is not the right response for those.
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
      final StorageComponent component,
      final TxConsumer consumer) {
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, component);
    try {
      consumer.accept(atomicOperation);
      // Flush pending logical WAL records after successful component operation.
      // If the consumer throws, pending ops are NOT flushed — they will be
      // discarded when the atomic operation is rolled back.
      atomicOperation.flushPendingOperations();
    } catch (Exception | AssertionError e) {
      // AssertionError is included so a -ea-only assert thrown from the consumer
      // surfaces as a CommonStorageComponentException with component+storage context
      // rather than escaping as a bare Error. Error superclasses other than
      // AssertionError (OutOfMemoryError, StackOverflowError, LinkageError) are
      // deliberately not caught here.
      if (e instanceof CoreException coreException) {
        coreException.setComponentName(component.getLockName());
        coreException.setDbName(storage.getName());
      }

      throw BaseException.wrapException(
          new CommonStorageComponentException(
              "Exception during execution of component operation inside component "
                  + component.getLockName()
                  + " in storage "
                  + storage.getName(),
              component.getLockName(), storage.getName()),
          e, storage.getName());
    }
  }

  public <T> T calculateInsideComponentOperation(
      final AtomicOperation atomicOperation,
      final StorageComponent component,
      final TxFunction<T> function) {
    Objects.requireNonNull(atomicOperation);
    acquireExclusiveLockTillOperationComplete(atomicOperation, component);
    try {
      final T result = function.accept(atomicOperation);
      // Flush pending logical WAL records after successful component operation.
      // Capture return value first so flush happens before return.
      atomicOperation.flushPendingOperations();
      return result;
    } catch (Exception | AssertionError e) {
      // AssertionError is included so a -ea-only assert thrown from the function
      // surfaces as a CommonStorageComponentException with component+storage context
      // rather than escaping as a bare Error. Error superclasses other than
      // AssertionError (OutOfMemoryError, StackOverflowError, LinkageError) are
      // deliberately not caught here.
      throw BaseException.wrapException(
          new CommonStorageComponentException(
              "Exception during execution of component operation inside component "
                  + component.getLockName()
                  + " in storage "
                  + storage.getName(),
              component.getLockName(), storage.getName()),
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
    // Local mutable copy of the inbound error: Hook A below may capture a
    // persist failure into this slot and convert the commit into a rollback,
    // so the parameter cannot stay final downstream.
    Throwable currentError = error;
    try {
      storage.moveToErrorStateIfNeeded(currentError);

      if (currentError != null) {
        operation.rollbackInProgress();
      }

      try {
        // Hook A — persist accumulated index count deltas to BTree entry
        // point pages before WAL commit. Runs only on the success path
        // (no inbound error, not already rolling back). The isPersisted()
        // guard short-circuits the second pass when the legacy inline call
        // inside AbstractStorage.commit has already persisted; the latch
        // is set at the end of AbstractStorage.persistIndexCountDeltas and
        // closes the dual-invocation window until the inline call is
        // deleted in a later step. After deletion the latch remains as a
        // defensive belt against any future re-entry into persist on the
        // same atomic operation.
        //
        // Failure handling: capture RuntimeException | AssertionError into
        // the local currentError slot, mark the storage in error mode
        // (subject to the AssertionError guard at AbstractStorage.setInError
        // lines 1769–1771, which deliberately skips the error-state flip
        // for asserts so a stray dev/test invariant violation does not
        // poison the storage), and flip the operation to rollback so the
        // subsequent commitChanges call is skipped. The captured throwable
        // is re-raised after the inner releaseLocks below to keep the lock
        // window correctness story intact (releaseLocks must run before the
        // exception escapes). IOException is not caught here because
        // AbstractStorage.persistIndexCountDeltas does not declare it — the
        // underlying BTree.addToApproximateEntriesCount routes IO failures
        // through executeInsideComponentOperation, which converts them into
        // CommonStorageComponentException (a runtime) before the persist
        // method returns.
        //
        // Bounded catch: LinkageError, OutOfMemoryError, StackOverflowError,
        // and InternalError are deliberately NOT caught. They escape this
        // method and reach AbstractStorage.commit's outer catch (Error) at
        // the call site, which routes through logAndPrepareForRethrow(Error)
        // and lands setInError — genuine VM errors still poison the
        // storage, matching the precedent in the four
        // executeInsideAtomicOperation wrapper catches.
        if (currentError == null && !operation.isRollbackInProgress()) {
          var holder = operation.getIndexCountDeltas();
          if (holder != null && !holder.isPersisted()) {
            try {
              storage.persistIndexCountDeltas(operation);
            } catch (RuntimeException | AssertionError persistFailure) {
              currentError = persistFailure;
              storage.moveToErrorStateIfNeeded(persistFailure);
              operation.rollbackInProgress();
            }
          }
        }

        final LogSequenceNumber lsn;
        var commitTs = operation.getCommitTs();
        if (!operation.isRollbackInProgress()) {
          lsn = operation.commitChanges(commitTs, writeAheadLog);
        } else {
          lsn = null;
        }

        if (currentError != null) {
          atomicOperationsTable.rollbackOperation(commitTs);
        } else {
          atomicOperationsTable.commitOperation(commitTs);
          if (lsn != null) {
            writeAheadLog.addEventAt(
                lsn, () -> atomicOperationsTable.persistOperation(commitTs));
          } else {
            // Pure non-durable operation — no WAL record to wait for.
            atomicOperationsTable.persistOperation(commitTs);
          }
        }

      } finally {
        releaseLocks(operation);
        operation.deactivate();
      }

      // Re-raise a persist failure captured by Hook A above. Must run after
      // the inner-finally releaseLocks so the per-index locks are released
      // before the exception escapes. The wrap is structurally required for
      // AssertionError: endAtomicOperation declares only throws IOException,
      // so a raw throw of an arbitrary Throwable would not compile. Routing
      // the AssertionError through StorageException (a RuntimeException via
      // BaseException) lands at AbstractStorage.commit's outer
      // catch (RuntimeException), which calls
      // logAndPrepareForRethrow(RuntimeException) — that overload does not
      // call setInError again, so the explicit moveToErrorStateIfNeeded
      // above is the single source of the in-error flip on this path.
      // RuntimeException short-circuits as-is so existing error-type
      // contracts (e.g. ConcurrentModificationException) survive intact.
      if (currentError != null && currentError != error) {
        if (currentError instanceof RuntimeException re) {
          throw re;
        }
        // currentError is an AssertionError here — the inner-catch types
        // (RuntimeException | AssertionError) leave AssertionError as the
        // only non-RuntimeException residual.
        throw BaseException.wrapException(
            new StorageException(storage.getName(), "Error during transaction commit"),
            currentError, storage.getName());
      }
    } finally {
      writeOperationsFreezer.endOperation();
    }
  }

  public void ensureThatComponentsUnlocked(@Nonnull final AtomicOperation operation) {
    releaseLocks(operation);
  }

  private void releaseLocks(AtomicOperation operation) {
    // Release StorageComponent locks (the common case).
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

    // Clear the combined dedup set
    var nameIter = operation.lockedObjects().iterator();
    while (nameIter.hasNext()) {
      nameIter.next();
      nameIter.remove();
    }
  }

  /**
   * Acquires exclusive lock on the given {@link StorageComponent} for the lifetime of the
   * atomic operation. Uses the component's own
   * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract
   * ReentrantReadWriteLock} directly — no external map lookup needed. The lock is natively
   * reentrant.
   */
  public void acquireExclusiveLockTillOperationComplete(
      @Nonnull AtomicOperation operation, @Nonnull StorageComponent component) {
    storage.checkErrorState();

    if (operation.containsInLockedObjects(component.getLockName())) {
      return;
    }

    component.lockExclusive();
    operation.addLockedComponent(component);
    operation.addLockedObject(component.getLockName());
  }

}
