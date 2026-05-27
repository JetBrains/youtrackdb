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
 * top-level storage method (such as {@code synch}, {@code freeze},
 * {@code release}, or ~30+ siblings) hits the outer {@code catch (Error)}
 * block, descends through {@code logAndPrepareForRethrow(Error, true)}, and
 * reaches {@code setInError}. Before the guard, that path put the storage into
 * a permanent error state; after the guard, it logs and rethrows but leaves
 * {@code isInError()} false so the storage stays usable for subsequent commits.
 *
 * <p>Note that not every {@code catch (Error)} site in {@code AbstractStorage}
 * is in scope here: roughly 35 of the ~140 sites use the two-arg
 * {@code logAndPrepareForRethrow(Error, false)} overload (e.g., {@code count}),
 * which disables the read-only-mode flip entirely and so never calls
 * {@code setInError}.
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
 * invoking the {@code protected} {@code logAndPrepareForRethrow} overloads
 * (the production callers of the {@code private} {@code setInError} setter) on
 * a freshly-opened storage with each of the relevant {@link Throwable}
 * subtypes.
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

  /**
   * Feeds an {@link AssertionError} through the {@link Throwable} overload of
   * {@code logAndPrepareForRethrow} (taken by every {@code catch (final Throwable t)}
   * site such as the count/getRecordMetadata wrappers in {@code AbstractStorage}) and
   * asserts that {@code isInError()} remains {@code false}. The declared static type of
   * the local is {@link Throwable} (not {@code var}), forcing the compiler to bind the
   * call to the {@link Throwable} overload at line 5828 of {@code AbstractStorage};
   * that overload routes through line 5838's {@code setInError(throwable)}, exercising
   * a different reach into the guarded setter than the {@link Error} overload covered
   * by {@link #assertionErrorFromOuterCatchDoesNotFlipReadOnlyMode}. The wrapped
   * {@link RuntimeException} returned for rethrow must carry the original
   * {@link AssertionError} as its cause.
   */
  @Test
  public void assertionErrorTypedAsThrowableDoesNotFlipReadOnlyMode() {
    Throwable thrown = new AssertionError("simulated underflow surfaced as Throwable");
    var returned = storage.logAndPrepareForRethrow(thrown);
    assertThat(returned)
        .as("Throwable-overload must wrap the original AssertionError as its cause")
        .isInstanceOf(RuntimeException.class);
    assertThat(returned.getCause())
        .as("wrapped cause must be the original AssertionError")
        .isSameAs(thrown);
    assertThat(storage.isInError())
        .as("AssertionError through the Throwable overload must not flip the storage flag")
        .isFalse();
  }

  /**
   * Feeds a custom subclass of {@link AssertionError} through
   * {@code logAndPrepareForRethrow(Error)} and asserts {@code isInError()} remains
   * {@code false}. The guard uses {@code instanceof AssertionError}, which by
   * specification includes every subclass; this pins that contract so a future
   * regression narrowing the check to {@code e.getClass() == AssertionError.class}
   * would fail this test. Real-world subclasses include JUnit's
   * {@code org.opentest4j.AssertionFailedError} and any project-local extension; the
   * private subclass below stands in for that surface.
   */
  @Test
  public void assertionErrorSubclassIsAlsoSkipped() {
    var thrown = new CustomAssertionErrorSubclass("simulated subclass");
    storage.logAndPrepareForRethrow(thrown);
    assertThat(storage.isInError())
        .as("AssertionError subclass must also be skipped by the instanceof check")
        .isFalse();
  }

  /**
   * Verifies idempotence of the guard against pre-existing error state. First feeds an
   * {@link OutOfMemoryError} through {@code logAndPrepareForRethrow(Error)} so the storage
   * flips into the in-error state (the {@code RuntimeException} overload does not call
   * {@code setInError} at all, so it cannot be used to seed the flag here), then feeds an
   * {@link AssertionError} and asserts that {@code isInError()} is still {@code true}. The
   * guard's {@code return} statement skips {@code error.set(e)} without overwriting or
   * clearing the prior error; this pins that ordering so a future regression flipping the
   * condition (e.g., {@code if (... && error.get() == null) return;}) would fail this test.
   */
  @Test
  public void assertionErrorAfterPriorErrorLeavesPriorErrorIntact() {
    storage.logAndPrepareForRethrow(new OutOfMemoryError("genuine prior failure"));
    assertThat(storage.isInError())
        .as("genuine prior failure must flip the storage flag")
        .isTrue();
    storage.logAndPrepareForRethrow(new AssertionError("stray invariant after real failure"));
    assertThat(storage.isInError())
        .as("AssertionError arriving after a real failure must not clear the prior error")
        .isTrue();
  }

  /**
   * Pins the cause-chain walk in {@link AbstractStorage#moveToErrorStateIfNeeded}.
   * When an {@link AssertionError} is thrown from a lambda inside
   * {@code AtomicOperationsManager.executeInsideComponentOperation}, that wrapper
   * catches it and re-throws as a {@code CommonStorageComponentException} with the
   * {@code AssertionError} preserved as the cause. The outer
   * {@code executeInsideAtomicOperation} then passes the wrapper (not the original
   * {@code AssertionError}) to {@code endAtomicOperation}, which forwards it to
   * {@code moveToErrorStateIfNeeded}. Without the cause-chain walk, the
   * {@code instanceof AssertionError} check inside {@code setInError} misses the
   * wrapped case and the storage is incorrectly flipped into in-error mode.
   * The walk closes that residual cascade vector left by YTDB-958's Layer 2.
   */
  @Test
  public void wrappedAssertionErrorInCauseChainDoesNotFlipReadOnlyMode() {
    var underflow = new AssertionError("approximateEntriesCount underflow");
    var wrapper = new RuntimeException("CommonStorageComponentException stand-in", underflow);
    storage.moveToErrorStateIfNeeded(wrapper);
    assertThat(storage.isInError())
        .as("wrapper exception whose cause chain contains AssertionError must not"
            + " flip the storage into permanent error state")
        .isFalse();
  }

  /**
   * Pins the negative side of the cause-chain walk: a regular failure (no
   * AssertionError anywhere in the cause chain) must still flip the storage
   * into in-error mode. Guards against a regression that broadens the
   * skip-condition and accidentally swallows real errors.
   */
  @Test
  public void nonAssertionErrorCauseChainStillFlipsReadOnlyMode() {
    var innerCause = new IllegalStateException("inner");
    var wrapper = new RuntimeException("outer", innerCause);
    storage.moveToErrorStateIfNeeded(wrapper);
    assertThat(storage.isInError())
        .as("wrapper whose cause chain contains no AssertionError must still"
            + " flip the storage flag")
        .isTrue();
  }

  /**
   * Pins the multi-level cause-chain walk: an AssertionError nested deeper than
   * one level (mirroring the StorageException -> CommonStorageComponentException
   * -> AssertionError chain produced when an assert fires inside a component
   * operation that is then caught by the atomic-operation wrapper) must still
   * be detected.
   */
  @Test
  public void deeplyWrappedAssertionErrorIsAlsoSkipped() {
    var underflow = new AssertionError("underflow at deepest level");
    var inner = new RuntimeException("component wrapper", underflow);
    var outer = new RuntimeException("atomic-op wrapper", inner);
    storage.moveToErrorStateIfNeeded(outer);
    assertThat(storage.isInError())
        .as("AssertionError nested multiple levels deep must still be detected"
            + " by the cause-chain walk")
        .isFalse();
  }

  /**
   * Pins the cycle-defense in {@link AbstractStorage#moveToErrorStateIfNeeded}'s
   * cause-chain walk. A self-referential cause (constructed via two-element
   * initCause cycle) must not cause the walk to spin forever. Without the
   * IdentityHashMap-based visited-set defense, the walk would loop indefinitely
   * inside an atomic-op finally block, stranding any locks held by the calling
   * thread. The walk should terminate and proceed with the normal poison
   * decision (flipping the storage into in-error mode because no AssertionError
   * appears in the chain).
   */
  @Test(timeout = 5000)
  public void cyclicCauseChainTerminatesAndStillFlipsReadOnlyMode() {
    var a = new RuntimeException("a");
    var b = new RuntimeException("b");
    a.initCause(b);
    b.initCause(a);
    storage.moveToErrorStateIfNeeded(a);
    assertThat(storage.isInError())
        .as("cyclic cause chain with no AssertionError must still flip the storage")
        .isTrue();
  }

  /**
   * Pins the depth bound in the cause-chain walk. An AssertionError nested
   * deeper than the documented cascade (StorageException ->
   * CommonStorageComponentException -> AssertionError, depth 2) must still be
   * found by the walk — the MAX_CAUSE_DEPTH bound is generous enough to cover
   * pathological wrapping while still avoiding indefinite spin on cycles.
   */
  @Test
  public void assertionErrorWithinDepthBoundIsStillSkipped() {
    var underflow = new AssertionError("deep underflow");
    Throwable chain = underflow;
    for (int i = 0; i < 5; i++) {
      chain = new RuntimeException("wrapper-" + i, chain);
    }
    storage.moveToErrorStateIfNeeded(chain);
    assertThat(storage.isInError())
        .as("AssertionError within the MAX_CAUSE_DEPTH bound must skip the poison flip")
        .isFalse();
  }

  // Stand-in for real-world AssertionError subclasses (e.g., JUnit's
  // org.opentest4j.AssertionFailedError, or hypothetical project-local invariants). The
  // guard's `instanceof AssertionError` check must match this subclass.
  private static final class CustomAssertionErrorSubclass extends AssertionError {
    CustomAssertionErrorSubclass(String message) {
      super(message);
    }
  }
}
