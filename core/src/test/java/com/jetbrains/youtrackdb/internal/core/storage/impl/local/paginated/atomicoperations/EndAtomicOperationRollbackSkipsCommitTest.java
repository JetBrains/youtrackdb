package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.DEFAULT_COMMIT_TS;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.mockOperation;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.mockStorage;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.primeFreezer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the invariant that a rolled-back atomic operation never reaches
 * {@code commitChanges} (the write-path half of the rollback-leaves-no-physical-
 * footprint property).
 *
 * <p>This is the load-bearing assumption behind the open-time orphan-pass gate
 * added by YTDB-1039. {@code AbstractStorage.open} now runs the recovery-time
 * orphan-truncation pass only when this open replayed the WAL (the
 * {@code wereDataRestoredAfterOpen} dirty gate). That gate is safe only if a
 * cleanly-closed disk database can never carry a physical orphan, and the only
 * way a transaction produces a physical footprint (an {@code AsyncFile} extend, a
 * dirtied real cache page, an {@code EnsurePageIsValidInFileTask}) is inside
 * {@link AtomicOperation#commitChanges(long, WriteAheadLog)}. If a rolled-back
 * operation could reach {@code commitChanges}, a rolled-back-then-cleanly-closed
 * session could leave an orphan on disk, and the dirty gate would skip a pass
 * that was actually needed.
 *
 * <p>The guard that enforces this lives in
 * {@link AtomicOperationsManager#endAtomicOperation(AtomicOperation, Throwable)}:
 * {@code commitChanges} is invoked only inside the
 * {@code if (!operation.isRollbackInProgress())} block, and a production
 * {@code assert} at that call site re-states the negation so a future refactor
 * that merged the branches, moved the call, or weakened the rollback predicate
 * trips a test under {@code -ea} instead of silently breaking the gate. The
 * {@code assert} lines are excluded from JaCoCo coverage by the project coverage
 * gate, so the regression value lives here, in the spy-based assertions below,
 * not in the {@code assert} alone.
 *
 * <p>Both rollback entries into {@code endAtomicOperation} are covered:
 *
 * <ul>
 *   <li>the inbound-error path — {@code endAtomicOperation} is entered with a
 *       non-null {@code error}, which flips the operation into rollback before
 *       the persist hook and the commit gate; and
 *   <li>the index-delta-persist-failure flip — the success-path persist hook
 *       ({@code persistIndexCountDeltas}) throws, and its catch flips the
 *       operation into rollback so the subsequent commit gate is skipped.
 * </ul>
 *
 * <p>Each rollback test asserts {@code commitChanges} is NEVER invoked. A
 * positive control asserts {@code commitChanges} IS invoked on a clean success
 * path, so a future change to {@code mockOperation} that stopped wiring
 * {@code commitChanges} altogether (which would make the never-invoked
 * assertions pass vacuously) fails here.
 *
 * <p>The property has a second, read-extend half (no correct production read
 * extends a file outside crash recovery). That half is a component-correctness
 * invariant, not assertable at this seam, and is documented in the gate comment
 * in {@code AbstractStorage.open}; it is deliberately out of scope for this test.
 *
 * @see EndAtomicOperationPersistHookTest
 */
public class EndAtomicOperationRollbackSkipsCommitTest {

  private static final String STORAGE_NAME = "endAtomicOpRollbackSkipsCommit";

  private AbstractStorage storage;
  private AtomicOperationsTable table;
  private WriteAheadLog wal;

  @Before
  public void setUp() {
    // Plain mocks mirroring EndAtomicOperationPersistHookTest: only the two
    // getters the manager's constructor calls (getName, getWALInstance) are
    // stubbed; read-cache, write-cache, and id-gen are untouched on the
    // endAtomicOperation path so they stay at Mockito's default (null) return.
    wal = mock(WriteAheadLog.class);
    storage = mockStorage(STORAGE_NAME, wal);
    table = mock(AtomicOperationsTable.class);
  }

  /**
   * Inbound-error rollback entry: when {@code endAtomicOperation} is entered
   * with a non-null inbound error, the operation is flipped into rollback up
   * front, so the commit gate ({@code if (!operation.isRollbackInProgress())})
   * skips {@code commitChanges}. The write-path half of the invariant holds: no
   * physical apply runs for a rolled-back operation, so a rolled-back-then-
   * cleanly-closed session leaves no orphan.
   */
  @Test
  public void commitChangesNeverReachedOnInboundErrorRollback() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var inbound = new RuntimeException("inbound error driving the rollback entry");

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, inbound);

    // Write-path half: commitChanges must not run for a rolled-back op.
    verify(operation, never()).commitChanges(anyLong(), any(WriteAheadLog.class));
    // The operation was recorded as a rollback, never a commit, confirming the
    // never-invoked assertion above reflects a genuine rollback path and not a
    // mis-wired mock.
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
  }

  /**
   * Index-delta-persist-failure rollback entry: on the success path (null
   * inbound error), the persist hook calls
   * {@code AbstractStorage.persistIndexCountDeltas} before the commit gate.
   * When that call throws, the hook's catch flips the operation into rollback,
   * so the commit gate skips {@code commitChanges}. This is the second of the
   * two rollback entries, and is the one a refactor of the persist hook could
   * most plausibly disturb, so the invariant must be pinned here too.
   */
  @Test
  public void commitChangesNeverReachedOnPersistFailureRollback() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var persistFailure = new RuntimeException("simulated index-delta persist failure");
    doThrow(persistFailure).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      // The captured persist failure is re-raised after releaseLocks; the
      // throw is expected and irrelevant to this test — what matters is that
      // commitChanges did not run. Falling through without a throw is also
      // acceptable for this test's purpose, so no fail() here.
    } catch (RuntimeException expected) {
      // Re-raised persist failure — expected, see method Javadoc.
    }

    // Write-path half: the persist-failure flip skipped commitChanges.
    verify(operation, never()).commitChanges(anyLong(), any(WriteAheadLog.class));
    // Persist was attempted exactly once, confirming the rollback was driven by
    // the persist-failure flip rather than an unrelated short-circuit.
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
  }

  /**
   * Positive control: on a clean success path (null inbound error, persist hook
   * succeeds, operation never flipped to rollback), {@code commitChanges} IS
   * invoked. Without this, the two never-invoked assertions above could pass
   * vacuously if a future change to {@code mockOperation} stopped wiring
   * {@code commitChanges} as a callable stub. This test fails in that case,
   * keeping the rollback assertions meaningful.
   */
  @Test
  public void commitChangesReachedOnCleanSuccessPath() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    // Success path: commitChanges runs exactly once and the table records a
    // commit, not a rollback. This anchors the never-invoked assertions in the
    // rollback tests against a vacuous-pass regression.
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
  }
}
