package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the {@link AbstractStorage#setInError(Throwable)} entry-point guard that
 * skips {@link AssertionError}. The guard is the third defense-in-depth layer
 * behind the broadened pre-{@code endTxCommit} catch at
 * {@link AbstractStorage#commit} and the broadened catches in the four
 * {@code AtomicOperationsManager} wrappers. It closes the residual cascade
 * vector left by those two layers: any {@link AssertionError} that escapes a
 * top-level storage method (such as {@code synch}, {@code count},
 * {@code freeze}, {@code release}, or ~30+ siblings) hits the outer
 * {@code catch (Error)} block, descends through
 * {@code logAndPrepareForRethrow(Error)}, and reaches {@code setInError}. Before
 * the guard, that path put the storage into a permanent error state; after the
 * guard, it logs and rethrows but leaves {@code isInError()} false so the
 * storage stays usable for subsequent commits.
 *
 * <p>Production behavior is unchanged: the JVM default is {@code -ea OFF}, so
 * {@code assert} statements never fire in shipping deployments. The test JVM
 * runs with {@code -ea} (see {@code core/pom.xml}), making the guard
 * exercisable here.
 *
 * <p>The companion storage cascade-containment test (see the corresponding
 * later step in this track) exercises the same guard end-to-end via the four
 * engine-mutator clamp+error path. This test focuses on the
 * {@code setInError}/{@code logAndPrepareForRethrow} surface in isolation,
 * directly invoking the package-protected method on a freshly-opened storage
 * with each of the relevant {@link Throwable} subtypes.
 */
public class SetInErrorAssertionErrorGuardTest {

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
    assertThat(storage.isInError()).as("freshly-opened storage starts clean").isFalse();
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * Feeds an {@link AssertionError} through {@code logAndPrepareForRethrow(Error)} and
   * asserts that {@code isInError()} remains {@code false}. This is the path taken by
   * every top-level storage method's outer {@code catch (Error)} clause. The error must
   * still be returned from the helper (callers rethrow it), but the read-only-mode flip
   * must not have happened.
   */
  @Test
  public void assertionErrorFromOuterCatchDoesNotFlipReadOnlyMode() {
    var thrown = new AssertionError("simulated dev/test invariant violation");
    var returned = storage.logAndPrepareForRethrow(thrown);
    assertThat(returned)
        .as("logAndPrepareForRethrow must still return the original error for rethrow")
        .isSameAs(thrown);
    assertThat(storage.isInError())
        .as("AssertionError must not flip the storage into permanent error state")
        .isFalse();
    // A second invocation on the same storage instance must still see a usable storage,
    // confirming the guard is idempotent under repeated AssertionError flow.
    storage.logAndPrepareForRethrow(new AssertionError("second underflow"));
    assertThat(storage.isInError())
        .as("repeated AssertionErrors must not eventually flip the flag either")
        .isFalse();
  }

  /**
   * Feeds an arbitrary {@link Throwable} (non-{@link RuntimeException},
   * non-{@link AssertionError}) through {@code logAndPrepareForRethrow(Throwable)} and
   * asserts that {@code isInError()} flips to {@code true}. This pins the negative side
   * of the guard: the change is scoped to {@code AssertionError} only, and every other
   * {@link Throwable} subtype still triggers the error state as before.
   */
  @Test
  public void nonAssertionErrorThrowableStillFlipsReadOnlyMode() {
    var thrown = new Throwable("simulated arbitrary failure");
    storage.logAndPrepareForRethrow(thrown);
    assertThat(storage.isInError())
        .as("non-AssertionError throwables must still flip the storage flag")
        .isTrue();
  }

  /**
   * Feeds an {@link OutOfMemoryError} through {@code logAndPrepareForRethrow(Error)} and
   * asserts {@code isInError()} flips to {@code true}. {@link OutOfMemoryError},
   * {@link StackOverflowError}, and {@link LinkageError} are deliberately excluded from
   * the guard's scope (only {@link AssertionError} is skipped), so they must still
   * trigger the read-only-mode flip on the path through
   * {@code logAndPrepareForRethrow(Error)}.
   */
  @Test
  public void outOfMemoryErrorStillFlipsReadOnlyMode() {
    var thrown = new OutOfMemoryError("simulated OOM");
    var returned = storage.logAndPrepareForRethrow(thrown);
    assertThat(returned)
        .as("logAndPrepareForRethrow must still return the original error for rethrow")
        .isSameAs(thrown);
    assertThat(storage.isInError())
        .as("OutOfMemoryError must still flip the storage into permanent error state")
        .isTrue();
  }
}
