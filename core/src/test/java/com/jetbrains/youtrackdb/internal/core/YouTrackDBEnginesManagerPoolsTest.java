package com.jetbrains.youtrackdb.internal.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests that all thread pools owned by {@link YouTrackDBEnginesManager} are created, accessible,
 * and functional.
 */
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
    var config = (YouTrackDBConfigImpl) com.jetbrains.youtrackdb.internal.core.config
        .YouTrackDBConfig.defaultConfig();

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
    var config = (YouTrackDBConfigImpl) com.jetbrains.youtrackdb.internal.core.config
        .YouTrackDBConfig.defaultConfig();
    manager.createExecutor(config);

    var latch = new CountDownLatch(1);
    manager.getExecutor().execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  // -- Thread groups -------------------------------------------------------

  @Test
  public void storageThreadGroupIsChildOfMainThreadGroup() {
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getStorageThreadGroup()).isNotNull();
    assertThat(manager.getStorageThreadGroup().getParent())
        .isSameAs(manager.getThreadGroup());
  }

  @Test
  public void storageThreadGroupIsNamedCorrectly() {
    var manager = YouTrackDBEnginesManager.instance();
    assertThat(manager.getStorageThreadGroup().getName()).isEqualTo("YouTrackDB Storage");
  }
}
