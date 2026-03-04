package com.jetbrains.youtrackdb.internal.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link YouTrackDBScheduler}, covering task scheduling, exception handling,
 * and inactive scheduler behavior.
 */
public class YouTrackDBSchedulerTest {

  // -- Active scheduler, periodic task --

  /** Verifies that a periodic task (period > 0) is executed multiple times. */
  @Test
  public void schedulePeriodicTaskExecutesRepeatedly() throws Exception {
    var scheduler = new YouTrackDBScheduler();
    scheduler.activate();

    var counter = new AtomicInteger();
    ScheduledFuture<?> future = scheduler.scheduleTask(counter::incrementAndGet, 10, 10);
    try {
      assertThat((Object) future).isNotNull();
      // Wait for at least 3 executions.
      var deadline = System.currentTimeMillis() + 5_000;
      while (counter.get() < 3 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(counter.get()).isGreaterThanOrEqualTo(3);
    } finally {
      future.cancel(false);
      scheduler.shutdown();
    }
  }

  // -- Active scheduler, one-shot task (period <= 0) --

  /** Verifies that a one-shot task (period = 0) is executed exactly once. */
  @Test
  public void scheduleOneShotTaskExecutesOnce() throws Exception {
    var scheduler = new YouTrackDBScheduler();
    scheduler.activate();

    var latch = new CountDownLatch(1);
    ScheduledFuture<?> future = scheduler.scheduleTask(latch::countDown, 0, 0);
    try {
      assertThat((Object) future).isNotNull();
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      scheduler.shutdown();
    }
  }

  // -- Inactive scheduler --

  /** When the scheduler is inactive, scheduleTask should return null and not execute the task. */
  @Test
  public void scheduleTaskReturnsNullWhenInactive() {
    var scheduler = new YouTrackDBScheduler();
    // Do NOT activate — scheduler remains inactive.

    var ran = new AtomicBoolean(false);
    ScheduledFuture<?> result = scheduler.scheduleTask(() -> ran.set(true), 0, 100);

    assertThat((Object) result).isNull();
    assertThat(ran.get()).isFalse();
  }

  // -- Exception recovery --

  /**
   * When a periodic task throws a RuntimeException, the safe wrapper should catch it and log it
   * without stopping future executions.
   */
  @Test
  public void scheduleTaskRecoversFromException() throws Exception {
    var scheduler = new YouTrackDBScheduler();
    scheduler.activate();

    var execCount = new AtomicInteger();
    Runnable failingTask = () -> {
      execCount.incrementAndGet();
      throw new RuntimeException("simulated exception");
    };

    ScheduledFuture<?> future = scheduler.scheduleTask(failingTask, 0, 10);
    try {
      // The task should keep executing despite the exception.
      var deadline = System.currentTimeMillis() + 5_000;
      while (execCount.get() < 3 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(execCount.get())
          .as("Task should continue executing after RuntimeException")
          .isGreaterThanOrEqualTo(3);
    } finally {
      future.cancel(false);
      scheduler.shutdown();
    }
  }

  // -- Error rethrow --

  /**
   * When a task throws an Error, the safe wrapper should log it and rethrow it,
   * which causes the ScheduledExecutorService to stop scheduling.
   */
  @Test
  public void scheduleTaskRethrowsError() throws Exception {
    var scheduler = new YouTrackDBScheduler();
    scheduler.activate();

    var errorThrown = new AtomicBoolean(false);
    Runnable errorTask = () -> {
      errorThrown.set(true);
      throw new AssertionError("simulated error");
    };

    ScheduledFuture<?> future = scheduler.scheduleTask(errorTask, 0, 100);
    try {
      // Wait for the task to execute.
      var deadline = System.currentTimeMillis() + 5_000;
      while (!errorThrown.get() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      assertThat(errorThrown.get()).isTrue();
      // The future should complete exceptionally because the Error is rethrown.
      Thread.sleep(100);
      assertThat(future.isDone()).isTrue();
    } finally {
      future.cancel(false);
      scheduler.shutdown();
    }
  }
}
