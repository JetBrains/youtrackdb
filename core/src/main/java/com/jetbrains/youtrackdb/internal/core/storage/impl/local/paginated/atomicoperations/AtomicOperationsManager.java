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
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
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

  // Apply-phase epoch shared by all atomic operations of this storage. Owned here (one
  // manager per storage) rather than by ReadCache, because on the disk engine a single
  // read cache is shared by all storages of the engine — an engine-global epoch would
  // let commits in one database spuriously invalidate optimistic reads in another.
  // Writers bump it around the cache-apply section of commitChanges; readers capture
  // and validate it via OptimisticReadScope.
  private final ApplyPhaseEpoch applyPhaseEpoch = new ApplyPhaseEpoch();

  /**
   * TEST-ONLY accessor for this storage's apply-phase epoch, exposed package-private for
   * the test bridge in the same test package (used by the YTDB-1178 mixed-apply-state
   * regression tests to make baseline-relative assertions on the epoch counters).
   * Production code must not call this — writers bump the epoch only through
   * {@code AtomicOperationBinaryTracking.commitChanges} and readers observe it only
   * through {@code OptimisticReadScope}.
   */
  ApplyPhaseEpoch getApplyPhaseEpoch() {
    return applyPhaseEpoch;
  }

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
        storage.getEdgeSnapshotIndexSize(), applyPhaseEpoch);
  }

  public void startToApplyOperations(AtomicOperation atomicOperation) {
    startToApplyOperations(atomicOperation, false, null);
  }

  /**
   * The schema-armed variant of {@link #startToApplyOperations(AtomicOperation)}: a
   * schema-carrying commit's apply threads its arm signal and the shared gate-exception factory
   * into the freezer, whose loop-top and park-decision checkpoints then abort the entrant loudly
   * instead of parking it while an operator freeze is active. Data commits and the internal
   * atomic-operation wrappers use the unarmed variant — their freezer semantics are byte-for-byte
   * unchanged.
   */
  public void startToApplyOperations(AtomicOperation atomicOperation, final boolean schemaArmed,
      @Nullable final Supplier<? extends BaseException> schemaGate) {
    writeOperationsFreezer.startOperation(schemaArmed, schemaGate);

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

  public long freezeWriteOperations(final FreezeKind kind,
      @Nullable Supplier<? extends BaseException> throwException) {
    return writeOperationsFreezer.freezeOperations(kind, throwException);
  }

  public void unfreezeWriteOperations(long id) {
    writeOperationsFreezer.releaseOperations(id);
  }

  /**
   * Whether an operator freeze is currently registered — the schema-commit gate's kind probe,
   * consumed by the storage-level entry probe, the write-lock abort predicate, and (inside the
   * freezer) the loop-top and park-decision checkpoints.
   */
  public boolean isOperatorFreezeActive() {
    return writeOperationsFreezer.isOperatorFreezeActive();
  }

  /**
   * Test-observability only: see {@link OperationsFreezer#registeredOperatorFreezeIdCount()}.
   */
  public int registeredOperatorFreezeIdCount() {
    return writeOperationsFreezer.registeredOperatorFreezeIdCount();
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
        // latch is set at the end of AbstractStorage.persistIndexCountDeltas
        // and serves as a defensive belt against any future re-entry into
        // persist on the same atomic operation: the persist hook short-
        // circuits when the holder is already latched, so a nested or
        // mistakenly-replayed lifecycle pass cannot double-persist.
        //
        // Failure handling: capture RuntimeException | AssertionError into
        // the local currentError slot, mark the storage in error mode
        // (subject to the AssertionError skip-branch in
        // AbstractStorage.setInError, which deliberately skips the
        // error-state flip for asserts so a stray dev/test invariant
        // violation does not poison the storage), and flip the operation to
        // rollback so the
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
          // Load-bearing invariant behind the open-time orphan-pass gate in
          // AbstractStorage.open (YTDB-1039): a rolled-back atomic operation
          // must never reach commitChanges. commitChanges is the only path that
          // performs a physical AsyncFile extend, installs a dirty cache page,
          // or submits an EnsurePageIsValidInFileTask; if a rollback could
          // reach it, a rolled-back transaction could leave a physical orphan on
          // a cleanly-closed file, and the open-time gate (which skips the
          // recovery orphan-truncation pass whenever the open replayed no WAL)
          // would then skip a pass that was actually needed. The enclosing if
          // already guards this, so the assert is structurally redundant today;
          // it exists to pin the contract AT THE CALL SITE so a future refactor
          // that merged the branches, moved this call, or weakened the rollback
          // predicate trips a test (run under -ea) rather than silently breaking
          // the gate's safety. The assert pins only the write-path half of the
          // invariant (no physical write on rollback); the read-extend half (no
          // correct production read extends a file outside crash recovery) is a
          // separate component-correctness invariant and is not assertable here.
          assert !operation.isRollbackInProgress()
              : "commitChanges reached on a rolled-back operation:"
                  + " a rollback must perform no physical write";
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

        // Apply hook for index count deltas and histogram deltas. Runs on the
        // success path only, after commitChanges and atomicOperationsTable
        // commit/persist, and before the inner-finally releaseLocks below.
        //
        // Placement before releaseLocks is load-bearing. The per-index lock
        // acquired by AbstractStorage.lockIndexes at AbstractStorage.java:2255
        // lives on operation.lockedComponents() and is released by
        // releaseLocks below. Holding the lock during apply keeps the next
        // transaction's pure-delta encoding from reading a stale in-memory
        // counter at clear() or buildInitialHistogram. Downstream consumers:
        // the clear() pure-delta encoding in BTreeMultiValueIndexEngine and
        // BTreeSingleValueIndexEngine, and the buildInitialHistogram
        // recalibration on both engines. Regression guard:
        // EndAtomicOperationHookOrderingTest.
        //
        // Cache-only contract: apply failure must not mask a successful
        // commit. The WAL record is already durable; counters will be
        // recalibrated by load() on restart or buildInitialHistogram() on
        // recovery. Catch covers RuntimeException and AssertionError to
        // absorb the engine-mutator clamp-and-error path. Bounded catch:
        // LinkageError, OutOfMemoryError, StackOverflowError, and
        // InternalError escape so genuine VM errors still poison the storage.
        //
        // Gate mirrors the persist hook above: currentError == null AND
        // !operation.isRollbackInProgress(). The second clause is structurally
        // redundant on this path (the only failure source between the persist
        // gate and here is commitChanges, whose throw bypasses this block via
        // the inner-finally), but it keeps Hook A and Hook B visually
        // symmetric so a future intermediate mutator that flips rollback in
        // place cannot silently bypass the apply gate.
        if (currentError == null && !operation.isRollbackInProgress()) {
          var indexHolder = operation.getIndexCountDeltas();
          if (indexHolder != null && !indexHolder.isApplied()) {
            tryApply(() -> storage.applyIndexCountDeltas(operation),
                "Index count delta application failed after successful commit");
          }
          var histogramHolder = operation.getHistogramDeltas();
          if (histogramHolder != null && !histogramHolder.isApplied()) {
            tryApply(() -> storage.applyHistogramDeltas(operation),
                "Histogram delta application failed after successful commit");
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
        // currentError != error is true only when Hook A's catch above
        // overwrote the slot, since Hook A is the only mutator inside this
        // method. The bounded catch above narrows persistFailure to
        // RuntimeException | AssertionError, so currentError here must be
        // one of those; a future broadening of Hook A's catch that did
        // not update this dispatch would be caught by the assert below.
        assert currentError instanceof RuntimeException
            || currentError instanceof AssertionError
            : "Hook A inner-catch must yield RuntimeException | AssertionError, got "
                + currentError.getClass().getName();
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

  /**
   * Runs a single apply call inside the lifecycle apply hook and absorbs any
   * {@link RuntimeException} or {@link AssertionError} into a warn-level log
   * record. Centralises the cache-only contract so the index-count and
   * histogram branches above stay structurally identical: changing the catch
   * surface or the log level once updates both sites.
   *
   * <p>VM errors ({@link OutOfMemoryError}, {@link LinkageError},
   * {@link StackOverflowError}, {@link InternalError}) are deliberately not
   * caught here. They escape to {@code AbstractStorage.commit}'s outer
   * {@code catch (Error)} so genuine VM failures still poison the storage,
   * matching the precedent set by the persist hook above and the four
   * executeInsideAtomicOperation wrapper catches in this class.
   */
  private void tryApply(Runnable applyCall, String warnMessage) {
    try {
      applyCall.run();
    } catch (RuntimeException | AssertionError applyFailure) {
      LogManager.instance().warn(this, warnMessage, applyFailure);
    }
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
