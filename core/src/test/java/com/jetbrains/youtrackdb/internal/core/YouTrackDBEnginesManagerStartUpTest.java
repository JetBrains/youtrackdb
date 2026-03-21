package com.jetbrains.youtrackdb.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests the {@link YouTrackDBEnginesManager#startUp(boolean)} static lifecycle method,
 * specifically the re-entrant call guard and the failure cleanup path.
 */
@Category(SequentialTest.class)
public class YouTrackDBEnginesManagerStartUpTest {

  private Field instanceField;
  private Field initInProgressField;
  private Object originalInstance;
  private boolean originalInitInProgress;

  @Before
  public void saveStaticState() throws Exception {
    instanceField = YouTrackDBEnginesManager.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    originalInstance = instanceField.get(null);

    initInProgressField = YouTrackDBEnginesManager.class.getDeclaredField("initInProgress");
    initInProgressField.setAccessible(true);
    originalInitInProgress = (boolean) initInProgressField.get(null);
  }

  /**
   * Restores the static singleton state to what it was before each test.
   * This is a safety net: the startUp() method's own finally block resets
   * initInProgress, but if a test fails before reaching startUp(), the
   * explicit restore here prevents state leakage to subsequent tests.
   */
  @After
  public void restoreStaticState() throws Exception {
    YouTrackDBEnginesManager.instanceFactory = null;
    instanceField.set(null, originalInstance);
    initInProgressField.set(null, originalInitInProgress);
  }

  /**
   * When startUp() is called re-entrantly from the same thread (e.g. during startup(),
   * Profiler.onStartup() triggers YouTrackDBScheduler.scheduleTask() which calls instance()),
   * the re-entrant call should detect that initialization is already in progress via the
   * initInProgress flag and return the already-assigned instance instead of starting a
   * second initialization.
   */
  @Test
  public void reEntrantStartUpCallReturnsSameInstance() throws Exception {
    // Ensure the singleton instance is initialized.
    var currentInstance = YouTrackDBEnginesManager.instance();
    assertThat(currentInstance).isNotNull();

    // Simulate being inside startUp() — the instance is assigned and initInProgress is true.
    // This reflection-based write is safe because the test is single-threaded; in production,
    // initInProgress is always accessed under initLock.
    initInProgressField.set(null, true);

    // A re-entrant call to startUp() should detect initInProgress and return the
    // existing instance immediately, without attempting a second initialization.
    var result = YouTrackDBEnginesManager.startUp(false);

    assertThat(result).isSameAs(currentInstance);
  }

  /**
   * When initInProgress is true but instance has not yet been assigned (null),
   * re-entrant startUp() must return null (the current instance value) rather
   * than falling through and creating a second YouTrackDBEnginesManager.
   * This distinguishes the initInProgress guard from the instance != null guard.
   */
  @Test
  public void reEntrantStartUpReturnsNullWhenInstanceNotYetAssigned()
      throws Exception {
    // Simulate the narrow window where startUp() has set initInProgress = true
    // but has not yet assigned instance (it is still null).
    initInProgressField.set(null, true);
    instanceField.set(null, null);

    var result = YouTrackDBEnginesManager.startUp(false);

    // The re-entrant guard must short-circuit and return the current (null)
    // instance, NOT fall through and create a new manager.
    assertThat(result).isNull();
  }

  /**
   * Calling startUp() when the singleton is already fully initialized (and
   * initInProgress is false) must return the existing instance without creating
   * a second one. This exercises the {@code if (instance != null)} early-return
   * guard independently of the initInProgress guard.
   */
  @Test
  public void startUpReturnsExistingInstanceWhenAlreadyInitialized()
      throws Exception {
    var currentInstance = YouTrackDBEnginesManager.instance();
    assertThat(currentInstance).isNotNull();

    // initInProgress is false (normal state after a successful startUp).
    initInProgressField.set(null, false);

    var result = YouTrackDBEnginesManager.startUp(false);

    assertThat(result).isSameAs(currentInstance);
  }

  /**
   * When startup() throws during startUp(), the static instance must be set back to null,
   * shutdownPools() must be called to clean up thread pools, and the initLock must be
   * released so that subsequent calls are not deadlocked. The original exception must be
   * re-thrown to the caller.
   *
   * <p>Uses a package-private factory hook ({@code instanceFactory}) to inject a subclass
   * whose {@code startup()} throws, instead of Mockito's {@code mockConstruction} which
   * is broken on JDK 25+.
   */
  @Test
  public void startUpCleansUpAndRethrowsWhenStartupFails() throws Exception {
    // Clear static state so startUp() will create a fresh instance.
    instanceField.set(null, null);
    initInProgressField.set(null, false);

    // Track the created instance so we can verify pool cleanup after the failure.
    var createdRef = new AtomicReference<YouTrackDBEnginesManager>();

    // Install a factory that creates a real manager (with real pools) but whose
    // startup() throws, simulating a failure during engine initialization.
    YouTrackDBEnginesManager.instanceFactory = insideWebContainer -> {
      var manager = new FailingStartupManager(insideWebContainer);
      createdRef.set(manager);
      return manager;
    };

    assertThatThrownBy(() -> YouTrackDBEnginesManager.startUp(false))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("simulated startup failure");

    // After the failure, the static instance must be null — no half-initialized
    // singleton should remain visible to other callers.
    assertThat(instanceField.get(null)).isNull();

    // Verify exactly one instance was created (and discarded).
    var created = createdRef.get();
    assertThat(created).isNotNull();

    // shutdownPools() must have been called on the discarded instance to clean
    // up constructor-allocated thread pools (WAL, checkpoint, cache, etc.).
    // We verify the effect: all pools must be in the shutdown state.
    assertThat(created.getWalFlushExecutor().isShutdown()).isTrue();
    assertThat(created.getWalWriteExecutor().isShutdown()).isTrue();
    assertThat(created.getFuzzyCheckpointExecutor().isShutdown()).isTrue();
    assertThat(created.getWowCacheFlushExecutor().isShutdown()).isTrue();
    assertThat(created.getScheduledPool().isShutdown()).isTrue();

    // The initLock must NOT be held after the failure — otherwise subsequent
    // startUp() calls from other threads would deadlock.
    Field lockField =
        YouTrackDBEnginesManager.class.getDeclaredField("initLock");
    lockField.setAccessible(true);
    var lock = (ReentrantLock) lockField.get(null);
    assertThat(lock.isHeldByCurrentThread()).isFalse();

    // initInProgress must be reset so that a retry can proceed.
    assertThat(initInProgressField.get(null)).isEqualTo(false);
  }

  /**
   * shutdownPools() shuts down all five constructor-allocated executor services.
   * This tests the cleanup method directly on a real (non-mock) instance to verify
   * that each pool transitions to the shutdown state.
   */
  @Test
  public void shutdownPoolsCleansUpAllExecutors() {
    // Create a real instance via the package-private constructor.
    var manager = new YouTrackDBEnginesManager(false);
    try {
      // All pools should be alive immediately after construction.
      assertThat(manager.getWalFlushExecutor().isShutdown()).isFalse();
      assertThat(manager.getWalWriteExecutor().isShutdown()).isFalse();
      assertThat(manager.getFuzzyCheckpointExecutor().isShutdown()).isFalse();
      assertThat(manager.getWowCacheFlushExecutor().isShutdown()).isFalse();
      assertThat(manager.getScheduledPool().isShutdown()).isFalse();

      // Call the package-private shutdownPools() method.
      manager.shutdownPools();

      // Every pool must be shut down after cleanup.
      assertThat(manager.getWalFlushExecutor().isShutdown()).isTrue();
      assertThat(manager.getWalWriteExecutor().isShutdown()).isTrue();
      assertThat(manager.getFuzzyCheckpointExecutor().isShutdown()).isTrue();
      assertThat(manager.getWowCacheFlushExecutor().isShutdown()).isTrue();
      assertThat(manager.getScheduledPool().isShutdown()).isTrue();
    } finally {
      // Defensive cleanup in case the test fails before shutdownPools() is reached.
      if (!manager.getScheduledPool().isShutdown()) {
        manager.shutdownPools();
      }
    }
  }

  /**
   * Subclass that overrides {@code startup()} to throw a RuntimeException,
   * simulating a failure during engine initialization. All other behavior
   * (constructor, shutdownPools, pool getters) is inherited from the real class.
   */
  static class FailingStartupManager extends YouTrackDBEnginesManager {

    FailingStartupManager(boolean insideWebContainer) {
      super(insideWebContainer);
    }

    @Override
    public YouTrackDBEnginesManager startup() {
      throw new RuntimeException("simulated startup failure");
    }
  }
}
