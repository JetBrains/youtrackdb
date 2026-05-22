package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the contract that an {@link AssertionError} thrown from inside
 * {@code AtomicOperationBinaryTracking.commitChanges} on a {@code -ea} JVM
 * surfaces to the API caller as a bare {@link Error}, NOT wrapped as
 * {@code StorageException} by the broadened {@code AtomicOperationsManager}
 * wrappers. The wrappers broaden their lambda-body catch to
 * {@code Exception | AssertionError}, but {@code commitChanges} runs in
 * {@code endAtomicOperation}'s inner try (invoked from the wrapper's
 * {@code finally} block at the {@code endAtomicOperation} call site),
 * OUTSIDE the wrapper's broadened {@code try}. There is no catch around
 * {@code endAtomicOperation}'s call to {@code operation.commitChanges},
 * so an assertion that fires there propagates back through the wrapper's
 * {@code finally} and surfaces unwrapped.
 *
 * <p>This is the documented residual gap of the wrapper-broadening
 * defense-in-depth layer. Five {@code assert} statements live inside
 * {@code commitChanges} (the WAL-instance equality at line 953, the
 * post-flush no-pending-operations invariant near line 980, two
 * file-page-changes invariants near lines 1045 and 1059, and the
 * change-LSN invariant near line 1170). On a JVM with assertions enabled
 * any of those firing escapes the wrapper as a bare {@link AssertionError}.
 *
 * <p>The cascade-containment story still holds even in this gap, via
 * the {@link AbstractStorage#setInError(Throwable)} entry-point guard:
 * the surfaced {@link AssertionError} never flips
 * {@code isInError()} to {@code true}, and the storage remains usable
 * for subsequent commits.
 *
 * <p>The fixture drives the WAL-instance-mismatch assert at line 953
 * specifically: it is the simplest of the five to trigger from outside
 * the package without forging WAL state. The test reflectively swaps the
 * {@code AtomicOperationBinaryTracking.writeAheadLog} field on the live
 * operation to a different {@link WriteAheadLog} (a mock) between
 * {@code startAtomicOperation} and the lifecycle's {@code commitChanges}
 * call. The assert at line 953 compares {@code this.writeAheadLog} to
 * the manager's WAL passed in as the {@code commitChanges} parameter and
 * fires because they no longer match.
 *
 * <p>Same-package placement is deliberate: it lets the test read the
 * {@code protected} accessor {@link AbstractStorage#isInError()} directly.
 */
public class CommitChangesAssertionErrorSurfacesAsBareErrorTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;
  private AbstractStorage storage;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
    // Sanity: a freshly-opened storage must not already be in error state, or
    // every assertion below is degenerate.
    assertThat(storage.isInError())
        .as("freshly-opened storage starts clean")
        .isFalse();

    // Sanity: this test depends on `-ea` being enabled on the test JVM, since
    // every assert statement is otherwise a no-op. Probe with a local assert
    // we know would fire if assertions are enabled. If assertions are off,
    // skip the test cleanly via JUnit's Assume (returning is unsafe — the
    // body would degenerate). The core module's surefire argLine ships `-ea`,
    // so this is normally a no-op for repository CI.
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true; // intentional side-effecting assert
    org.junit.Assume.assumeTrue(
        "Assertions must be enabled (-ea) for this regression test",
        assertionsEnabled);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * Drives an {@link AssertionError} out of
   * {@code AtomicOperationBinaryTracking.commitChanges} (the WAL-instance
   * equality assert) and asserts three contract properties:
   *
   * <ol>
   *   <li>The error surfaces to the wrapper's caller as a bare
   *       {@link AssertionError} (not wrapped as {@code StorageException}
   *       or any {@code RuntimeException}). The wrapper-broadening covers
   *       lambda-body asserts only; {@code commitChanges} runs in
   *       {@code endAtomicOperation}'s inner try invoked from the wrapper's
   *       {@code finally}, which is past the broadened catch.</li>
   *   <li>{@link AbstractStorage#isInError()} stays {@code false}: the
   *       {@code setInError} {@link AssertionError} entry-point guard
   *       keeps the storage out of permanent error state on any path
   *       that would otherwise call {@code setInError(thrown)} via
   *       {@code logAndPrepareForRethrow}.</li>
   *   <li>A subsequent {@code executeInsideAtomicOperation} on the same
   *       storage succeeds, confirming the storage stays usable end-to-end
   *       and the prior assertion did not leave the lifecycle plumbing
   *       in a broken state.</li>
   * </ol>
   *
   * <p>The fixture takes pains to leave the prior atomic op's table state
   * recoverable: {@code endAtomicOperation} runs {@code releaseLocks} and
   * {@code deactivate} in its inner {@code finally} even when
   * {@code commitChanges} throws, so the per-component locks are released
   * and the operation is deactivated. The follow-up commit runs as a fresh
   * atomic op and is not coupled to the prior one's table entry.
   */
  @Test
  public void commitChangesAssertionSurfacesBareAndStorageStaysUsable()
      throws Exception {
    var manager = storage.getAtomicOperationsManager();

    // The mock WAL is intentionally NOT the manager's WAL, so the assert at
    // commitChanges line 953 fires when this.writeAheadLog (set by the
    // reflective overwrite below) is compared to the manager-owned WAL
    // passed into commitChanges by endAtomicOperation.
    var otherWAL = mock(WriteAheadLog.class);

    // First scenario: invoke executeInsideAtomicOperation with a lambda that
    // (a) succeeds normally and (b) reflectively swaps the operation's
    // writeAheadLog field to a different WAL. When the lambda returns
    // normally, the wrapper's finally calls endAtomicOperation(op, null),
    // which calls op.commitChanges(commitTs, managerWAL) -> assert fires.
    // The AssertionError propagates through endAtomicOperation's finally
    // (releaseLocks + deactivate run) and the wrapper's finally, then
    // surfaces to the caller.
    assertThatThrownBy(() -> manager.executeInsideAtomicOperation(op -> {
      replaceAtomicOperationWAL(op, otherWAL);
    }))
        .as("the assertion fired inside commitChanges must surface to the"
            + " API caller as a bare AssertionError (NOT wrapped as"
            + " StorageException by the broadened wrapper catches — the"
            + " wrapper's try { ... } body has already completed by the"
            + " time endAtomicOperation invokes commitChanges from the"
            + " wrapper's finally)")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("commitChanges WAL instance differs");

    // Exercise the setInError AssertionError entry-point guard explicitly:
    // route the surfaced AssertionError through logAndPrepareForRethrow(Error),
    // which is the path every top-level catch (Error) clause uses to log and
    // re-prepare for rethrow. Without the guard, this call would flip
    // isInError() to true. With the guard, it stays false.
    storage.logAndPrepareForRethrow(
        new AssertionError("post-surface logAndPrepareForRethrow exercise"));

    assertThat(storage.isInError())
        .as("the setInError AssertionError guard must keep the storage out of"
            + " permanent error state on every AssertionError survivor of"
            + " the broadened wrappers — both the original surfaced one and"
            + " the synthetic re-feed through logAndPrepareForRethrow above")
        .isFalse();

    // Second scenario: subsequent commit on the same storage. This proves the
    // cascade is fully contained — the storage instance must be usable for
    // ordinary writes after the commitChanges-internal assertion surfaced.
    // A trivial executeInsideAtomicOperation that returns without doing any
    // tracked work is sufficient: it exercises the full startAtomicOperation
    // -> endAtomicOperation lifecycle, which would fail if the prior op had
    // left the manager's freezer, segment lock, or atomic-operations table
    // in an unusable shape.
    manager.executeInsideAtomicOperation(op -> {
      // intentionally empty — exercise the lifecycle, not any data work
    });

    assertThat(storage.isInError())
        .as("storage must still report not-in-error after a clean follow-up"
            + " atomic operation completes")
        .isFalse();
  }

  /**
   * Reflectively overwrites the {@code writeAheadLog} field of an
   * {@code AtomicOperationBinaryTracking} (the concrete class behind every
   * {@link AtomicOperation} returned by the manager). The field is declared
   * {@code private final}; {@link Field#setAccessible(boolean)} on the
   * project's own class plus a plain {@code Field.set} works on JDK 21 for
   * non-static final instance fields. The argument carries the
   * implementation type via the lookup, so this helper is intentionally
   * tied to the binary tracking implementation — if a future refactor
   * renames the field or moves to a different concrete operation class,
   * this lookup fails fast and signals the test needs updating.
   */
  private static void replaceAtomicOperationWAL(AtomicOperation op, WriteAheadLog wal)
      throws Exception {
    Class<?> implClass = op.getClass();
    Field walField = implClass.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(op, wal);
  }
}
