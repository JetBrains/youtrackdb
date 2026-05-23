package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the IOException / AssertionError fallback throw in the
 * pre-{@code endTxCommit} catch clause of {@link AbstractStorage#commit}: a
 * non-{@link RuntimeException} throwable that escapes the inner try must be
 * wrapped as {@link com.jetbrains.youtrackdb.internal.core.exception.StorageException}
 * (so the caller's contract stays uniform — no {@link Error} or checked
 * exception escapes), and the inner finally must route through the rollback
 * branch.
 *
 * <p>Companion to {@link PersistedSideAssertionRoutedToRollbackTest}, which
 * pins the rollback-routing contract for a {@code BTree.addToApproximateEntriesCount}
 * underflow. After the broadened catches in
 * {@code AtomicOperationsManager.executeInsideAtomicOperation} and the three
 * sibling wrappers, every {@code AssertionError} fired from inside one of
 * those wrappers is wrapped as a
 * {@link com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException}
 * (a {@link RuntimeException}) before it reaches the
 * {@code AbstractStorage.commit} catch — so it takes the
 * {@code if (e instanceof RuntimeException)} branch and the fallback
 * {@code throw BaseException.wrapException(...)} statement at the end of the
 * catch is no longer exercised by the BTree path. This test reaches that
 * fallback via a different code path inside the inner try: the
 * {@code recordSerializationContext.executeOperations(...)} call, which is
 * NOT wrapped by {@code executeInsideComponentOperation} and so propagates
 * an {@link AssertionError} from a custom
 * {@link RecordSerializationOperation} straight up to the pre-{@code endTxCommit}
 * catch.
 */
public class CommitNonRuntimeExceptionFallbackTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * Pushes a custom {@link RecordSerializationOperation} that throws an
   * {@link AssertionError} onto the transaction's
   * {@code RecordSerializationContext}, then runs {@code db.commit()}.
   * Inside {@code AbstractStorage.commit}, the call to
   * {@code recordSerializationContext.executeOperations(atomicOperation, this)}
   * runs the malicious operation, the {@code AssertionError} propagates up
   * unwrapped (the call site is not inside any
   * {@code executeInsideComponentOperation} wrapper), and the pre-{@code endTxCommit}
   * catch at {@code AbstractStorage.commit} catches it via its
   * {@code IOException | RuntimeException | AssertionError} clause. Since
   * the throwable is not a {@link RuntimeException}, the {@code if}-branch
   * does not match and the fallback {@code throw BaseException.wrapException(...)}
   * statement runs, wrapping the {@link AssertionError} as a
   * {@link com.jetbrains.youtrackdb.internal.core.exception.StorageException}.
   * The inner finally's {@code error != null} branch routes through
   * {@code rollback(error, atomicOperation)}.
   *
   * <p>Verification points: (1) the assertion surfaces to the API caller as a
   * {@code StorageException} with the original {@link AssertionError} as its
   * root cause (proving the fallback wrap line ran); (2) the storage's
   * {@code isInError()} stays {@code true} only if the outer
   * {@code catch (Error)} would have flipped it — which is the next layer's
   * concern and not pinned here. This test pins only the catch-fallback
   * wrap contract.
   */
  @Test
  public void nonRuntimeExceptionFromExecuteOperationsWrapsAsStorageException() {
    db.begin();
    var tx = db.getActiveTransaction();
    var ctx = tx.getRecordSerializationContext();
    var thrown = new AssertionError("simulated non-RuntimeException inside inner try");
    // The custom operation runs inside RecordSerializationContext.executeOperations,
    // which is invoked from AbstractStorage.commit's inner try at
    // recordSerializationContext.executeOperations(atomicOperation, this).
    // The call site does NOT pass through executeInsideComponentOperation, so
    // the AssertionError reaches the pre-endTxCommit catch unwrapped.
    ctx.push(new RecordSerializationOperation() {
      @Override
      public void execute(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation atomicOperation,
          AbstractStorage paginatedStorage) {
        throw thrown;
      }
    });

    // Force the commit path to actually run the inner try (it short-circuits
    // for empty transactions).
    var seed = db.newVertex("V");
    seed.setProperty("p", "x");

    // Capture the atomic operation before the commit attempt so we can
    // assert below that the inner finally took the rollback branch (which
    // sets rollbackInProgress on the captured op via
    // AtomicOperationsManager.endAtomicOperation). The same pattern is used
    // by PersistedSideAssertionRoutedToRollbackTest in the same package.
    AtomicOperation capturedOp = tx.getAtomicOperation();

    Throwable caught = null;
    try {
      db.commit();
    } catch (Throwable t) {
      caught = t;
    }

    assertThat(caught)
        .as("Commit must surface the non-RuntimeException as a wrapped exception")
        .isNotNull();

    // First load-bearing assertion: the caught throwable is a StorageException.
    // The fallback wrap line at AbstractStorage.commit converts the
    // AssertionError into a StorageException; the unwrapped-bypass path (the
    // catch missing the AssertionError clause) would surface the bare
    // AssertionError unchanged. This is the falsifiability tighten point:
    // without it, a mutation that reverts the catch to
    // `IOException | RuntimeException` still passes the root-cause walk
    // below (because AssertionError == AssertionError trivially).
    assertThat(caught)
        .as("Caught throwable must be a StorageException wrapping the original "
            + "AssertionError, proving the fallback wrap line ran")
        .isInstanceOf(StorageException.class);

    // Second load-bearing assertion: the immediate cause is the originally
    // thrown AssertionError. BaseException.wrapException sets the cause
    // directly; deeper layers may add additional wrap frames, so the
    // immediate-cause check pins the wrap site at the pre-endTxCommit catch.
    assertThat(caught.getCause())
        .as("Immediate cause must be the originally-thrown AssertionError, "
            + "proving the fallback wrap line at the pre-endTxCommit catch ran")
        .isSameAs(thrown);

    // Walk the cause chain. Multiple layers may wrap the throwable before it
    // reaches the API caller, but the original AssertionError must be the
    // ultimate root cause. This survives any future re-wrapping by
    // logAndPrepareForRethrow or sibling layers.
    Throwable rootCause = caught;
    while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
      rootCause = rootCause.getCause();
    }
    assertThat(rootCause)
        .as("Root cause must be the originally-thrown AssertionError, proving "
            + "the pre-endTxCommit catch's fallback wrapException line ran")
        .isSameAs(thrown);

    // Third load-bearing assertion: the inner finally took the rollback
    // branch. endAtomicOperation(op, error) with a non-null error sets
    // operation.rollbackInProgress(); the unwrapped-bypass path would have
    // run the success branch (endTxCommit followed by applyIndexCountDeltas)
    // instead. Pins the inner-finally routing claim in the test's contract.
    assertThat(capturedOp.isRollbackInProgress())
        .as("Atomic operation must be marked rollback-in-progress, proving "
            + "the inner finally took the rollback branch rather than the "
            + "endTxCommit success branch")
        .isTrue();
  }
}
