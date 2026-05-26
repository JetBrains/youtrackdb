package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the broadened catch at the pre-{@code endTxCommit} clause
 * of {@link AbstractStorage#commit}: a persisted-side {@code AssertionError}
 * from {@code BTree.addToApproximateEntriesCount} (raised inside
 * {@code persistIndexCountDeltas}) must be caught by the broadened catch so
 * that the inner finally's rollback path runs instead of the success path
 * ({@code endTxCommit} + {@code applyIndexCountDeltas} + {@code applyHistogramDeltas}).
 *
 * <p>Without the broadened catch, the underflow assertion bubbles out of the
 * inner try, skips the {@code catch (IOException | RuntimeException)} clause
 * because {@code AssertionError extends Error}, and leaves the local
 * {@code error} variable null when the inner finally runs. The finally's
 * {@code error == null} branch then runs {@code endTxCommit} — which commits
 * the WAL atomic operation despite the half-baked counter state. The
 * AssertionError still propagates to the outer {@code catch (Error)}, but the
 * transaction is already durable on the WAL, so the offending record persists.
 * The cascade observed in {@code Pre_Tests_Test_REST_2026.2.51599.log}
 * (330 underflows / 2,643 poisoned commits / Gradle OOM) is the production
 * manifestation of this trap.
 *
 * <p>With the broadened catch, the {@code AssertionError} enters the catch
 * block, the local {@code error} is set, the catch wraps the assertion as a
 * {@code StorageException} (so no {@code Error} escapes the commit path's
 * inner try), the inner finally runs the {@code rollback(error,
 * atomicOperation)} branch (which calls {@code endAtomicOperation(operation,
 * error)} and marks the atomic operation as rollback-in-progress), and only
 * then does the wrapped throw propagate to the API caller.
 *
 * <p>Scope: pins only the rollback-routing contract. Keeping the storage
 * usable for subsequent commits after an {@code AssertionError} survivor
 * reaches the outer {@code catch (Error)} is the {@code setInError}
 * entry-point guard's contract, covered by a separate regression test.
 */
public class PersistedSideAssertionRoutedToRollbackTest {

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
   * Forces a persisted-side underflow on {@code BTree.addToApproximateEntriesCount}
   * during the main commit path and verifies the broadened catch routes the
   * resulting {@code AssertionError} through the inner finally's rollback
   * branch. Verification points: (1) the assertion surfaces to the API
   * caller as a {@code StorageException} with the original
   * {@code AssertionError} as its root cause; (2) the atomic operation is
   * marked as rollback-in-progress (proof that the inner finally took the
   * {@code error != null} branch rather than the {@code endTxCommit} success
   * branch).
   */
  @Test
  public void persistedSideAssertionFromBTreeRoutesThroughBroadenedCatchAndRollback()
      throws Exception {
    // Set up a UNIQUE index so the BTreeSingleValueIndexEngine path is used.
    SchemaClass cls = db.createVertexClass("PersonPSA");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonPSA_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Seed one record so the persisted entry-point count is 1. The assertion
    // at BTree.java:1020 fires when current + delta < 0, so we need a small
    // positive starting count and a large negative injected delta.
    db.begin();
    var seed = db.newVertex("PersonPSA");
    seed.setProperty("name", "Alice");
    db.commit();

    // IndexAbstract.getIndexId() returns the external id (engineAPIVersion in
    // the high 5 bits, internal engine id in the low 27 bits — see
    // AbstractStorage.generateIndexId). The IndexCountDeltaHolder is keyed by
    // the internal engine id, so we mask to 27 bits.
    int externalIndexId = ((IndexAbstract) db.getSharedContext().getIndexManager()
        .getIndex("PersonPSA_name")).getIndexId();
    int engineId = externalIndexId & 0x7_FF_FF_FF;

    // Run a transaction that injects a large negative delta into the
    // per-transaction IndexCountDeltaHolder for the target engine id. The
    // accumulated +1 from the legitimate insert is dwarfed, so persistCountDelta
    // computes updated = 1 + accumulated + injected < 0 and the assert at
    // BTree.java:1020 fires inside persistIndexCountDeltas (running inside the
    // pre-endTxCommit inner try).
    Throwable caught = null;
    AtomicOperation capturedOp = null;
    try {
      db.begin();
      var bob = db.newVertex("PersonPSA");
      bob.setProperty("name", "Bob");
      // The newVertex above accumulates +1 on the delta via IndexCountDelta;
      // overwrite the totalDelta with an oversized negative value so the
      // BTree assert trips during persistIndexCountDeltas.
      var tx = db.getActiveTransaction();
      var atomicOp = tx.getAtomicOperation();
      capturedOp = atomicOp;
      var holder = atomicOp.getOrCreateIndexCountDeltas();
      var delta = holder.getOrCreate(engineId);
      Field totalDeltaField = IndexCountDelta.class.getDeclaredField("totalDelta");
      totalDeltaField.setAccessible(true);
      totalDeltaField.setLong(delta, -1_000_000L);

      db.commit();
    } catch (Throwable t) {
      caught = t;
    }

    // The broadened catch contract — first verification point. The catch
    // wraps both IOException and AssertionError as StorageException so the
    // API caller's contract stays uniform (no Error escapes the commit
    // path's inner try). The original AssertionError is the cause; the
    // logAndPrepareForRethrow path at the outer catch re-wraps as
    // StorageException too, so we walk the cause chain to find the
    // underlying assertion.
    assertThat(caught)
        .as("Persisted-side BTree assertion must surface to the API caller "
            + "after being routed through the broadened catch")
        .isNotNull();
    Throwable rootCause = caught;
    while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
      rootCause = rootCause.getCause();
    }
    assertThat(rootCause)
        .as("Root cause of the surfaced exception must be the BTree underflow "
            + "AssertionError")
        .isInstanceOf(AssertionError.class);

    // The rollback-routing contract — second verification point.
    // The inner finally's `if (error != null) { rollback(...); }` branch
    // calls `endAtomicOperation(op, error)`, which sets the atomic
    // operation's rollbackInProgress flag (see
    // AtomicOperationsManager.endAtomicOperation, the `if (error != null)
    // { operation.rollbackInProgress(); }` block). Without the broadened
    // catch, the inner finally would run the `error == null` branch
    // (endTxCommit), which commits the atomic op rather than rolling it
    // back — and rollbackInProgress() would never be called for this op.
    assertThat(capturedOp)
        .as("Atomic operation must have been captured before the commit attempt")
        .isNotNull();
    assertThat(capturedOp.isRollbackInProgress())
        .as("The atomic operation must be marked as rollback-in-progress, "
            + "proving the inner finally took the rollback branch rather "
            + "than the endTxCommit success branch")
        .isTrue();
  }
}
