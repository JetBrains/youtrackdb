package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.DEFAULT_COMMIT_TS;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.mockStorage;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.primeFreezer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Regression test for the relative ordering of the persist hook (before
 * {@code commitChanges}) and the apply hook (after {@code commitChanges} and
 * before the inner-finally {@code releaseLocks}) inside
 * {@link AtomicOperationsManager#endAtomicOperation(AtomicOperation, Throwable)}.
 *
 * <p>The persist hook's individual contract is exercised by {@link
 * EndAtomicOperationPersistHookTest}; this class focuses on the
 * cross-hook ordering invariants and the apply hook's lock-window guarantee:
 *
 * <ul>
 *   <li>Happy path: persist runs before {@code commitChanges}; both
 *       {@code applyIndexCountDeltas} and {@code applyHistogramDeltas} run
 *       after {@code commitChanges} and before {@code releaseLocks}.</li>
 *   <li>Persist failure: when the persist call throws, {@code commitChanges}
 *       is skipped, the operation is recorded as a rollback, and neither
 *       apply hook fires.</li>
 *   <li>Lock-held during apply: the per-index lock acquired by
 *       {@code AbstractStorage.lockIndexes} at {@code AbstractStorage.java:2255}
 *       is still tracked in {@code operation.lockedComponents()} at the
 *       moment {@code applyIndexCountDeltas} and {@code applyHistogramDeltas}
 *       fire. The inner-finally {@code releaseLocks} clears the set only
 *       after the apply hook returns.</li>
 *   <li>Apply failure is swallowed: a {@code RuntimeException} or
 *       {@code AssertionError} from either apply call must not escape the
 *       lifecycle method on the success path (cache-only contract).</li>
 *   <li>Nested-op isolation: when a nested {@code endAtomicOperation} call
 *       fires from inside the apply hook (the shape used by
 *       {@code IndexHistogramManager.flushSnapshotToPage} via
 *       {@code executeInsideAtomicOperation}), the outer operation's
 *       {@code lockedComponents} set is not mutated. The nested call
 *       releases only its own components.</li>
 * </ul>
 *
 * <p>The lock-window invariant is the load-bearing reason the apply hook
 * lives inside {@code endAtomicOperation} before {@code releaseLocks} rather
 * than at the post-{@code endTxCommit} call site in
 * {@code AbstractStorage.commit}. Future pure-delta encoding for {@code
 * clear()} and {@code buildInitialHistogram} on the BTree engines depends on
 * the apply running while the lock is still held, so the next transaction's
 * delta computation reads a settled in-memory counter rather than a stale
 * value.
 *
 * @see EndAtomicOperationPersistHookTest
 */
public class EndAtomicOperationHookOrderingTest {

  private static final String STORAGE_NAME = "endAtomicOpHookOrdering";

  private AbstractStorage storage;
  private AtomicOperationsTable table;
  private WriteAheadLog wal;

  @Before
  public void setUp() {
    wal = mock(WriteAheadLog.class);
    storage = mockStorage(STORAGE_NAME, wal);
    table = mock(AtomicOperationsTable.class);
  }

  /**
   * Builds an {@link AtomicOperation} mock with a fresh, mutable
   * {@code lockedComponents()} backing list. Mirrors the shape produced by
   * {@code AbstractStorage.lockIndexes}, which appends one or more
   * {@link StorageComponent} entries (the per-index BTree components) into
   * the operation's locked-components set. The list is mutable so {@code
   * AtomicOperationsManager.releaseLocks} can iterate and call
   * {@code remove()} on the iterator without throwing.
   *
   * <p>The returned operation also tracks {@code isRollbackInProgress()}
   * state via {@code rollbackInProgress()} so the persist hook's rollback
   * signal flows through observably, and stubs {@code commitChanges} to
   * return a non-null LSN by default so the happy-path table commit branch
   * runs (callers that exercise non-durable operations may re-stub).
   */
  private AtomicOperation mockOperationWithLockedComponents(
      IndexCountDeltaHolder indexHolder,
      HistogramDeltaHolder histogramHolder,
      List<StorageComponent> lockedList,
      Set<String> lockedNames) throws IOException {
    var operation = mock(AtomicOperation.class);
    when(operation.getCommitTs()).thenReturn(DEFAULT_COMMIT_TS);
    when(operation.lockedComponents()).thenReturn(lockedList);
    when(operation.lockedObjects()).thenReturn(lockedNames);
    when(operation.getIndexCountDeltas()).thenReturn(indexHolder);
    when(operation.getHistogramDeltas()).thenReturn(histogramHolder);
    when(operation.commitChanges(any(Long.class), any(WriteAheadLog.class)))
        .thenReturn(new LogSequenceNumber(0L, 0));
    var rollbackFlag = new boolean[] {false};
    when(operation.isRollbackInProgress()).thenAnswer(inv -> rollbackFlag[0]);
    doAnswer(inv -> {
      rollbackFlag[0] = true;
      return null;
    }).when(operation).rollbackInProgress();
    return operation;
  }

  /**
   * Builds a {@link StorageComponent} mock whose {@code isExclusiveOwner()}
   * returns {@code true} until {@code unlockExclusive()} fires, after which
   * it returns {@code false}. Mirrors the real component lifecycle the
   * manager's {@code releaseLocks} relies on, so iteration order assertions
   * work as expected: the apply hook sees a non-empty list of owner-true
   * components, the release sweep finds them owner-true and unlocks each
   * one, and a second release sweep (the defensive double-call from
   * {@code AbstractStorage.commit}'s outer finally via {@code
   * ensureThatComponentsUnlocked}) finds them owner-false and skips.
   */
  private StorageComponent mockComponent(String lockName) {
    var component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn(lockName);
    var owner = new boolean[] {true};
    when(component.isExclusiveOwner()).thenAnswer(inv -> owner[0]);
    doAnswer(inv -> {
      owner[0] = false;
      return null;
    }).when(component).unlockExclusive();
    return component;
  }

  /**
   * Happy path ordering: persist runs before {@code commitChanges}, both
   * apply hooks run after {@code commitChanges} and before {@code
   * releaseLocks}. Pins the relative order via {@link InOrder} on the
   * methods observable from the manager's call graph:
   * {@code storage.persistIndexCountDeltas} ->
   * {@code operation.commitChanges} ->
   * {@code storage.applyIndexCountDeltas} ->
   * {@code storage.applyHistogramDeltas} ->
   * {@code component.unlockExclusive} (the first thing {@code releaseLocks}
   * does once it finds an owner-true component).
   */
  @Test
  public void happyPathPersistBeforeCommitApplyBeforeRelease() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var lockedNames = new HashSet<String>();
    lockedNames.add("index.idx");
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, lockedNames);
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyIndexCountDeltas(operation);
    doNothing().when(storage).applyHistogramDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    InOrder order = Mockito.inOrder(storage, operation, component);
    order.verify(storage).persistIndexCountDeltas(operation);
    order.verify(operation).commitChanges(DEFAULT_COMMIT_TS, wal);
    order.verify(storage).applyIndexCountDeltas(operation);
    order.verify(storage).applyHistogramDeltas(operation);
    order.verify(component).unlockExclusive();

    // Commit-side recorded as a successful commit; no rollback record.
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
    // releaseLocks ran and emptied the locked-components list.
    assertTrue("releaseLocks must drain lockedComponents",
        lockedList.isEmpty());
  }

  /**
   * Persist failure: the captured throwable flips the operation into
   * rollback, so {@code commitChanges} is skipped and neither apply hook
   * fires. Pins the apply-hook gate {@code (currentError == null)}: a
   * persist failure must short-circuit apply along with commit.
   */
  @Test
  public void persistFailureSkipsCommitAndBothApplyHooks() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());
    var persistFailure = new RuntimeException("simulated persist failure");
    doThrow(persistFailure).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
    } catch (RuntimeException re) {
      // RuntimeException short-circuit re-raise; expected by the persist hook.
    }

    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(storage, never()).applyIndexCountDeltas(operation);
    verify(storage, never()).applyHistogramDeltas(operation);
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
    // releaseLocks still ran inside the inner-finally; lockedComponents drained.
    assertTrue("releaseLocks must drain lockedComponents on rollback",
        lockedList.isEmpty());
    // Component was actually unlocked.
    verify(component, times(1)).unlockExclusive();
  }

  /**
   * Lock-window invariant: at the moment {@code applyIndexCountDeltas} runs,
   * the per-index lock acquired by {@code lockIndexes} is still tracked in
   * {@code operation.lockedComponents()}. The {@code releaseLocks} sweep
   * fires inside the inner-finally after the apply hook returns; the
   * locked-components list must be non-empty during the apply call.
   *
   * <p>Captured via a {@code doAnswer} on {@code applyIndexCountDeltas} that
   * snapshots the size of {@code operation.lockedComponents()} at call
   * time. A non-zero size proves the apply hook fires before
   * {@code releaseLocks} drains the list.
   */
  @Test
  public void applyIndexCountHookSeesLockedComponentsHeld() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());

    var sizeAtApply = new AtomicInteger(-1);
    var ownerAtApply = new AtomicReference<Boolean>(null);
    doAnswer(inv -> {
      // Snapshot the lock state as the apply hook sees it. Reading from the
      // backing list keeps the snapshot decoupled from the operation mock's
      // stubbing (the manager queries lockedComponents() through releaseLocks
      // only after this answer returns).
      sizeAtApply.set(lockedList.size());
      ownerAtApply.set(component.isExclusiveOwner());
      return null;
    }).when(storage).applyIndexCountDeltas(operation);
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyHistogramDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    assertEquals(
        "lockedComponents must still contain the per-index component when apply runs",
        1, sizeAtApply.get());
    assertNotNull("Component owner state must have been captured at apply time",
        ownerAtApply.get());
    assertTrue("Component must still be exclusive-owner when apply runs",
        ownerAtApply.get());
    // The release sweep drained the list afterwards.
    assertTrue("Backing lockedComponents list must be empty post-release",
        lockedList.isEmpty());
  }

  /**
   * Histogram parallel of the lock-window invariant. Same shape as the
   * index-count hook test above; ensures both apply branches respect the
   * before-{@code releaseLocks} placement.
   */
  @Test
  public void applyHistogramHookSeesLockedComponentsHeld() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());

    var sizeAtApply = new AtomicInteger(-1);
    doAnswer(inv -> {
      sizeAtApply.set(lockedList.size());
      return null;
    }).when(storage).applyHistogramDeltas(operation);
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    assertEquals(
        "lockedComponents must still contain the per-index component when histogram apply runs",
        1, sizeAtApply.get());
  }

  /**
   * Apply failure is swallowed: a {@code RuntimeException} from
   * {@code applyIndexCountDeltas} must not escape the lifecycle method on
   * the success path. The histogram apply hook still runs (each branch has
   * its own catch), the commit is still recorded, and {@code releaseLocks}
   * runs in the inner-finally.
   */
  @Test
  public void applyIndexCountFailureIsSwallowed() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doThrow(new RuntimeException("simulated apply failure"))
        .when(storage).applyIndexCountDeltas(operation);
    doNothing().when(storage).applyHistogramDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    // No throw from endAtomicOperation — the apply failure must be swallowed.
    manager.endAtomicOperation(operation, null);

    verify(storage, times(1)).applyIndexCountDeltas(operation);
    // Histogram apply still runs even when index-count apply throws.
    verify(storage, times(1)).applyHistogramDeltas(operation);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(component, times(1)).unlockExclusive();
    assertTrue("releaseLocks must run even when apply fails",
        lockedList.isEmpty());
  }

  /**
   * Apply failure on the histogram branch is also swallowed. Symmetric to
   * the index-count case above.
   */
  @Test
  public void applyHistogramFailureIsSwallowed() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyIndexCountDeltas(operation);
    doThrow(new AssertionError("simulated histogram apply assertion"))
        .when(storage).applyHistogramDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    // No throw — AssertionError on the histogram branch is also swallowed.
    manager.endAtomicOperation(operation, null);

    verify(storage, times(1)).applyHistogramDeltas(operation);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(component, times(1)).unlockExclusive();
  }

  /**
   * Latch-already-set short-circuit: when the {@link
   * IndexCountDeltaHolder#isApplied()} latch is set going into
   * {@code endAtomicOperation}, the apply hook must not call
   * {@code storage.applyIndexCountDeltas} a second time. Closes the
   * window where both the lifecycle hook and the pre-existing inline
   * call inside {@code AbstractStorage.commit} would otherwise double-apply
   * the deltas within a single transaction.
   *
   * <p>Symmetric to the {@code persistHookSkipsWhenHolderAlreadyPersisted}
   * coverage in {@link EndAtomicOperationPersistHookTest}.
   */
  @Test
  public void applyIndexCountHookSkipsWhenHolderAlreadyApplied() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    indexHolder.setApplied();
    var histogramHolder = new HistogramDeltaHolder();
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder,
        new ArrayList<>(), new HashSet<>());
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyHistogramDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, never()).applyIndexCountDeltas(operation);
    // Histogram apply still fires — the two latches are independent.
    verify(storage, times(1)).applyHistogramDeltas(operation);
  }

  /**
   * Histogram parallel of the latch short-circuit: when {@link
   * HistogramDeltaHolder#isApplied()} is already set, the histogram apply
   * hook must not fire a second time.
   */
  @Test
  public void applyHistogramHookSkipsWhenHolderAlreadyApplied() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    histogramHolder.setApplied();
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder,
        new ArrayList<>(), new HashSet<>());
    doNothing().when(storage).persistIndexCountDeltas(operation);
    doNothing().when(storage).applyIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    // Index-count apply still fires — independent latch.
    verify(storage, times(1)).applyIndexCountDeltas(operation);
    verify(storage, never()).applyHistogramDeltas(operation);
  }

  /**
   * Null-holder short-circuits: when neither delta holder has been
   * accumulated for the atomic operation (e.g. recovery-time atomic ops
   * during {@code AbstractStorage.open}), the apply hooks must not call
   * the storage at all. Mirrors the existing {@code if (holder == null)
   * return;} early-exit in {@code AbstractStorage.applyIndexCountDeltas}
   * and {@code applyHistogramDeltas}.
   */
  @Test
  public void applyHooksSkipWhenHoldersAreNull() throws IOException {
    var operation = mockOperationWithLockedComponents(
        null, null, new ArrayList<>(), new HashSet<>());
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, never()).applyIndexCountDeltas(operation);
    verify(storage, never()).applyHistogramDeltas(operation);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
  }

  /**
   * Apply hooks skip on the rollback path. When {@code endAtomicOperation}
   * is entered with a non-null inbound error, the apply hooks are gated by
   * {@code currentError == null} and must not fire. Symmetric to the
   * persist hook's {@code persistHookSkipsOnInboundError} contract.
   */
  @Test
  public void applyHooksSkipOnInboundError() throws IOException {
    var indexHolder = new IndexCountDeltaHolder();
    var histogramHolder = new HistogramDeltaHolder();
    var component = mockComponent("index.idx");
    var lockedList = new ArrayList<StorageComponent>();
    lockedList.add(component);
    var operation = mockOperationWithLockedComponents(
        indexHolder, histogramHolder, lockedList, new HashSet<>());
    var inbound = new RuntimeException("inbound rollback");

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, inbound);

    verify(storage, never()).applyIndexCountDeltas(operation);
    verify(storage, never()).applyHistogramDeltas(operation);
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(component, times(1)).unlockExclusive();
  }

  /**
   * Nested-op isolation: when the apply hook triggers a nested
   * {@code endAtomicOperation} on a separate inner operation (the shape
   * used by {@code IndexHistogramManager.flushSnapshotToPage} via
   * {@code executeInsideAtomicOperation}), the inner call's {@code
   * releaseLocks} sweep iterates only the inner operation's
   * {@code lockedComponents()}. The outer operation's locked-components
   * set is not mutated by the nested call.
   *
   * <p>The nested-op exposure is inherited from the existing manual
   * {@code applyHistogramDeltas} call inside {@code AbstractStorage.commit}
   * and is not widened by moving the apply into the lifecycle. The outer
   * lock-set integrity contract must hold across the nested call: a future
   * refactor that lets a nested call reach into the outer
   * {@code lockedComponents} would fail this test before the wider system
   * could leak a stuck lock.
   */
  @Test
  public void nestedEndAtomicOperationDoesNotMutateOuterLockedComponents()
      throws IOException {
    var outerComponent = mockComponent("outer.idx");
    var outerList = new ArrayList<StorageComponent>();
    outerList.add(outerComponent);

    var outerOperation = mockOperationWithLockedComponents(
        new IndexCountDeltaHolder(), new HistogramDeltaHolder(),
        outerList, new HashSet<>());

    var innerComponent = mockComponent("inner.idx");
    var innerList = new ArrayList<StorageComponent>();
    innerList.add(innerComponent);
    var innerOperation = mockOperationWithLockedComponents(
        new IndexCountDeltaHolder(), new HistogramDeltaHolder(),
        innerList, new HashSet<>());

    var manager = new AtomicOperationsManager(storage, table);

    // Snapshot the outer lockedComponents at the moment the histogram apply
    // hook fires the nested call. Reading after the nested call returns
    // proves the outer set was not mutated by the nested releaseLocks sweep.
    var outerSizeWhenInnerCalled = new AtomicInteger(-1);
    var outerSizeAfterNested = new AtomicInteger(-1);

    doNothing().when(storage).persistIndexCountDeltas(any(AtomicOperation.class));
    doNothing().when(storage).applyIndexCountDeltas(any(AtomicOperation.class));
    // Outer histogram apply triggers the nested endAtomicOperation. Inner
    // call's apply stubs return cleanly; the nested releaseLocks must drain
    // only the inner backing list.
    doAnswer(inv -> {
      AtomicOperation arg = inv.getArgument(0);
      if (arg == outerOperation) {
        outerSizeWhenInnerCalled.set(outerList.size());
        // The nested freezer entry mirrors the outer call's contract: every
        // endAtomicOperation pairs with a startOperation/endOperation cycle
        // through the freezer.
        primeFreezer(manager);
        manager.endAtomicOperation(innerOperation, null);
        outerSizeAfterNested.set(outerList.size());
      }
      return null;
    }).when(storage).applyHistogramDeltas(any(AtomicOperation.class));

    primeFreezer(manager);
    manager.endAtomicOperation(outerOperation, null);

    assertEquals(
        "Outer lockedComponents must contain the outer component when nested call begins",
        1, outerSizeWhenInnerCalled.get());
    assertEquals(
        "Outer lockedComponents must still contain the outer component after nested returns",
        1, outerSizeAfterNested.get());
    // Inner releaseLocks drained the inner list only.
    assertTrue("Inner releaseLocks must drain inner lockedComponents",
        innerList.isEmpty());
    // Inner component was actually unlocked by the nested call.
    verify(innerComponent, atLeastOnce()).unlockExclusive();
    // Outer releaseLocks ran in the outer inner-finally after the nested
    // call returned and drained the outer list.
    assertTrue("Outer releaseLocks must drain outer lockedComponents",
        outerList.isEmpty());
    verify(outerComponent, atLeastOnce()).unlockExclusive();
    // Both calls committed cleanly.
    verify(table, times(2)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(any(Long.class));
  }

  /**
   * Sanity that {@link EndAtomicOperationHookTestSupport#mockStorage} keeps
   * returning a usable storage mock for downstream tests in this class.
   * Pins the contract so a future change to the helper that returns
   * {@code null} or a partial mock fails here rather than as a cryptic
   * {@link NullPointerException} inside the manager's constructor.
   */
  @Test
  public void storageMockExposesStableName() {
    assertEquals(STORAGE_NAME, storage.getName());
    assertFalse("WAL must be non-null on the mock storage",
        storage.getWALInstance() == null);
  }

  /**
   * Test isolation: each test gets a fresh empty preexisting locked-objects
   * set so the manager's {@code releaseLocks} does not see leftover state.
   * Pins the {@code Collections.emptySet()} default contract.
   */
  @Test
  public void lockedObjectsEmptyByDefault() throws IOException {
    var operation = mockOperationWithLockedComponents(
        null, null, new ArrayList<>(), Collections.emptySet());
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
  }
}
