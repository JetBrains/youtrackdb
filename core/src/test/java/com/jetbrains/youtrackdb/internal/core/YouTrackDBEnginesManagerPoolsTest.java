package com.jetbrains.youtrackdb.internal.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests that all thread pools owned by {@link YouTrackDBEnginesManager} are created, accessible,
 * and functional.
 */
@Category(SequentialTest.class)
public class YouTrackDBEnginesManagerPoolsTest {

  // -- Storage pools -------------------------------------------------------

  @Test
  public void walFlushExecutorIsAvailableAndAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ScheduledExecutorService exec = manager.getWalFlushExecutor();
    assertThat(exec).isNotNull();
    assertThat(exec.isShutdown()).isFalse();

    var latch = new CountDownLatch(1);
    exec.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void walWriteExecutorIsAvailableAndAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ExecutorService exec = manager.getWalWriteExecutor();
    assertThat(exec).isNotNull();
    assertThat(exec.isShutdown()).isFalse();

    var latch = new CountDownLatch(1);
    exec.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void fuzzyCheckpointExecutorIsAvailableAndAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ScheduledExecutorService exec = manager.getFuzzyCheckpointExecutor();
    assertThat(exec).isNotNull();
    assertThat(exec.isShutdown()).isFalse();

    var latch = new CountDownLatch(1);
    exec.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void wowCacheFlushExecutorIsAvailableAndAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ScheduledExecutorService exec = manager.getWowCacheFlushExecutor();
    assertThat(exec).isNotNull();
    assertThat(exec.isShutdown()).isFalse();

    var latch = new CountDownLatch(1);
    exec.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  // -- Shared scheduled pool -----------------------------------------------

  @Test
  public void scheduledPoolIsAvailableAndAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ScheduledExecutorService pool = manager.getScheduledPool();
    assertThat(pool).isNotNull();
    assertThat(pool.isShutdown()).isFalse();

    var latch = new CountDownLatch(1);
    pool.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void scheduledPoolCanSchedulePeriodicTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    ScheduledExecutorService pool = manager.getScheduledPool();

    var counter = new AtomicInteger();
    ScheduledFuture<?> future = pool.scheduleWithFixedDelay(
        counter::incrementAndGet, 10, 10, TimeUnit.MILLISECONDS);
    try {
      // Wait for at least 3 executions
      var deadline = System.currentTimeMillis() + 5_000;
      while (counter.get() < 3 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(counter.get()).isGreaterThanOrEqualTo(3);
    } finally {
      future.cancel(false);
    }
  }

  // -- Main/IO executor factory methods ------------------------------------

  @Test
  public void createExecutorReturnsNonNullAndIsIdempotent() {
    var manager = YouTrackDBEnginesManager.instance();
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig();

    ExecutorService first = manager.createExecutor(config);
    ExecutorService second = manager.createExecutor(config);

    assertThat(first).isNotNull();
    assertThat(first.isShutdown()).isFalse();
    // Subsequent calls must return the same instance
    assertThat(second).isSameAs(first);
    // Getter must match
    assertThat(manager.getExecutor()).isSameAs(first);
  }

  @Test
  public void createExecutorAcceptsTasks() throws Exception {
    var manager = YouTrackDBEnginesManager.instance();
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig();
    manager.createExecutor(config);

    var latch = new CountDownLatch(1);
    manager.getExecutor().execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void createIoExecutorReturnsNullWhenDisabled() {
    // Build config with IO pool explicitly disabled.
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.EXECUTOR_POOL_IO_ENABLED, false)
        .build();

    ExecutorService result = YouTrackDBEnginesManager.instance().createIoExecutor(config);
    assertThat(result).isNull();
  }

  /** When IO pool is enabled, createIoExecutor creates a pool and returns it idempotently. */
  @Test
  public void createIoExecutorReturnsPoolWhenEnabled() throws Exception {
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.EXECUTOR_POOL_IO_ENABLED, true)
        .build();

    var manager = YouTrackDBEnginesManager.instance();
    ExecutorService first = manager.createIoExecutor(config);
    assertThat(first).isNotNull();
    assertThat(first.isShutdown()).isFalse();

    // Subsequent calls return the same instance.
    assertThat(manager.createIoExecutor(config)).isSameAs(first);
    assertThat(manager.getIoExecutor()).isSameAs(first);

    // The pool should accept tasks.
    var latch = new CountDownLatch(1);
    first.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  // -- executorMaxSize / executorBaseSize helpers ---------------------------

  @Test
  public void executorMaxSizeUsesConfiguredPositiveValue() {
    // Test the happy path: config provides a positive value.
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE, 16)
        .build();
    int result = YouTrackDBEnginesManager.executorMaxSize(
        config, GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE);
    assertThat(result).isEqualTo(16);
  }

  @Test
  public void executorMaxSizeFallsToCpuCountWhenZero() {
    // When the configured value is 0, executorMaxSize should log a warning
    // and fall back to the number of available CPUs.
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE, 0)
        .build();
    int result = YouTrackDBEnginesManager.executorMaxSize(
        config, GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE);
    assertThat(result).isEqualTo(Runtime.getRuntime().availableProcessors());
  }

  @Test
  public void executorMaxSizeFallsToCpuCountWhenNegative() {
    // When the configured value is -1, executorMaxSize should use CPU count.
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE, -1)
        .build();
    int result = YouTrackDBEnginesManager.executorMaxSize(
        config, GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE);
    assertThat(result).isEqualTo(Runtime.getRuntime().availableProcessors());
  }

  @Test
  public void executorBaseSizeForLargePool() {
    // size > 10: baseSize should be size / 10
    assertThat(YouTrackDBEnginesManager.executorBaseSize(20)).isEqualTo(2);
    assertThat(YouTrackDBEnginesManager.executorBaseSize(100)).isEqualTo(10);
  }

  @Test
  public void executorBaseSizeForMediumPool() {
    // 4 < size <= 10: baseSize should be size / 2
    assertThat(YouTrackDBEnginesManager.executorBaseSize(6)).isEqualTo(3);
    assertThat(YouTrackDBEnginesManager.executorBaseSize(10)).isEqualTo(5);
  }

  @Test
  public void executorBaseSizeForSmallPool() {
    // size <= 4: baseSize should be size itself
    assertThat(YouTrackDBEnginesManager.executorBaseSize(4)).isEqualTo(4);
    assertThat(YouTrackDBEnginesManager.executorBaseSize(1)).isEqualTo(1);
  }

  // -- shutdownExecutor ----------------------------------------------------

  /** Normal case: executor terminates within timeout. */
  @Test
  public void shutdownExecutorTerminatesGracefully() throws Exception {
    var exec = Executors.newSingleThreadExecutor();
    // Submit a quick task so the executor has something to do.
    exec.submit(() -> {
    });
    YouTrackDBEnginesManager.shutdownExecutor(exec, "test", 5, TimeUnit.SECONDS);
    assertThat(exec.isShutdown()).isTrue();
  }

  /** When executor does not terminate within timeout, shutdownNow() is called. */
  @Test
  public void shutdownExecutorForcesTerminationOnTimeout() throws Exception {
    var exec = Executors.newSingleThreadExecutor();
    // Submit a task that blocks for a long time.
    var started = new CountDownLatch(1);
    exec.submit(() -> {
      started.countDown();
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        // Expected — shutdownNow() will interrupt this.
      }
      return null;
    });
    // Wait for the task to start before shutting down.
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    // With a very short timeout, awaitTermination returns false → shutdownNow() is called.
    YouTrackDBEnginesManager.shutdownExecutor(exec, "test", 1, TimeUnit.MILLISECONDS);
    assertThat(exec.isShutdown()).isTrue();
  }

  /**
   * When the calling thread is interrupted during awaitTermination, shutdownNow() is called
   * and the interrupt flag is restored.
   */
  @Test
  public void shutdownExecutorHandlesInterruption() throws Exception {
    var exec = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    exec.submit(() -> {
      started.countDown();
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        // Expected — shutdownNow() will interrupt this.
      }
      return null;
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    // Pre-interrupt the calling thread so awaitTermination throws immediately.
    Thread.currentThread().interrupt();
    YouTrackDBEnginesManager.shutdownExecutor(exec, "test", 30, TimeUnit.SECONDS);

    assertThat(exec.isShutdown()).isTrue();
    // The interrupt flag should have been restored by the catch block.
    assertThat(Thread.interrupted()).isTrue();
  }

  /** Passing null executor is a safe no-op. */
  @Test
  public void shutdownExecutorHandlesNull() {
    YouTrackDBEnginesManager.shutdownExecutor(null, "none", 1, TimeUnit.SECONDS);
    // No exception means success.
  }

  // -- Thread groups -------------------------------------------------------

  @Test
  public void storageThreadGroupIsNotChildOfMainThreadGroup() {
    // The storage thread group must NOT be a descendant of the main thread group,
    // so that threadGroup.interrupt() during shutdown does not disrupt storage operations.
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getStorageThreadGroup()).isNotNull();
    assertThat(manager.getStorageThreadGroup().getParent())
        .isNotSameAs(manager.getThreadGroup());
  }

  @Test
  public void storageThreadGroupIsNamedCorrectly() {
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getStorageThreadGroup().getName()).isEqualTo("YouTrackDB Storage");
  }
}
