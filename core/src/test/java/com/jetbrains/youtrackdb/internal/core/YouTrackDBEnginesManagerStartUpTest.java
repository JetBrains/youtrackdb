package com.jetbrains.youtrackdb.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests the {@link YouTrackDBEnginesManager#startUp(boolean)} static lifecycle method,
 * specifically the re-entrant call guard and the failure cleanup path.
 */
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
   * When startup() throws during startUp(), the static instance must be set back to null
   * and all constructor-allocated thread pools must be shut down to prevent resource leaks.
   * The original exception must be re-thrown to the caller.
   */
  @Test
  public void startUpCleansUpAndRethrowsWhenStartupFails() throws Exception {
    // Clear static state so startUp() will create a fresh instance.
    instanceField.set(null, null);
    initInProgressField.set(null, false);

    // Intercept the YouTrackDBEnginesManager constructor so that the created instance's
    // startup() method throws, simulating a failure during engine initialization.
    // CALLS_REAL_METHODS ensures that the catch block's call to shutdownPools() invokes
    // the real cleanup logic (which gracefully handles the null executor fields since
    // the mock's constructor does not run).
    try (var mocked = Mockito.mockConstruction(
        YouTrackDBEnginesManager.class,
        Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS),
        (mock, context) -> doThrow(new RuntimeException("simulated startup failure"))
            .when(mock).startup())) {

      assertThatThrownBy(() -> YouTrackDBEnginesManager.startUp(false))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("simulated startup failure");

      // After the failure, the static instance must be null — no half-initialized
      // singleton should remain visible to other callers.
      assertThat(instanceField.get(null)).isNull();

      // Verify exactly one instance was created (and discarded).
      assertThat(mocked.constructed()).hasSize(1);
    }
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
}
