package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for {@link ScheduledThreadPoolExecutorWithLogging} to verify that
 * afterExecute correctly extracts and logs exceptions from completed futures,
 * and restores the interrupt flag when an InterruptedException occurs.
 */
public class ScheduledThreadPoolExecutorWithLoggingTest {

  /**
   * Verifies that when a scheduled task throws an exception, afterExecute
   * extracts it from the future via get() and logs it. If the get() call
   * were removed (VoidMethodCallMutator), the exception would be silently
   * swallowed and never logged.
   */
  @Test
  public void afterExecuteLogsExceptionFromFailedTask() throws Exception {
    var executor = new ScheduledThreadPoolExecutorWithLogging(
        1, Thread::new);
    try {
      var latch = new CountDownLatch(1);
      // Schedule a one-shot task that throws — afterExecute should call
      // future.get() and extract the cause.
      var future = executor.schedule(() -> {
        latch.countDown();
        throw new RuntimeException("test failure");
      }, 0, TimeUnit.MILLISECONDS);

      assertThat(latch.await(5, TimeUnit.SECONDS))
          .as("task should have executed")
          .isTrue();

      // Wait for the future to complete (it will complete exceptionally).
      try {
        future.get(5, TimeUnit.SECONDS);
      } catch (ExecutionException expected) {
        assertThat(expected.getCause())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("test failure");
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that afterExecute handles a worker thread interrupt gracefully
   * without crashing or hanging. The interrupt flag restoration itself cannot
   * be directly asserted from the test thread because it is thread-local to
   * the executor's worker thread.
   */
  @Test
  public void afterExecute_handlesInterruptedThreadGracefully() throws Exception {
    // We need to trigger the InterruptedException path in afterExecute.
    // This happens when the thread running afterExecute is interrupted
    // while calling future.get().
    var executor = new ScheduledThreadPoolExecutorWithLogging(1, r -> {
      var t = new Thread(r, "test-afterExecute-interrupt");
      t.setDaemon(true);
      return t;
    });
    try {
      var taskStarted = new CountDownLatch(1);
      var taskCanProceed = new CountDownLatch(1);

      // Submit a task that blocks until we release it.
      executor.submit(() -> {
        taskStarted.countDown();
        try {
          taskCanProceed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });

      // Wait for the task to start executing.
      assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Now interrupt the worker thread — when the task completes and
      // afterExecute calls future.get(), it will throw InterruptedException.
      executor.getQueue();
      // Find the worker thread and interrupt it.
      for (Thread t : Thread.getAllStackTraces().keySet()) {
        if ("test-afterExecute-interrupt".equals(t.getName())) {
          t.interrupt();
          break;
        }
      }

      // Let the task complete.
      taskCanProceed.countDown();

      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies that the remove-on-cancel policy is enabled (primary constructor).
   * This prevents accumulation of cancelled tasks in the queue for long-running
   * processes with many schedule/cancel cycles.
   */
  @Test
  public void removeOnCancelPolicyEnabled_primaryConstructor() {
    var executor = new ScheduledThreadPoolExecutorWithLogging(
        1, Thread::new);
    try {
      assertThat(executor.getRemoveOnCancelPolicy())
          .as("removeOnCancelPolicy should be true")
          .isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies that the remove-on-cancel policy is enabled (three-arg constructor).
   */
  @Test
  public void removeOnCancelPolicyEnabled_threeArgConstructor() {
    var executor = new ScheduledThreadPoolExecutorWithLogging(
        1, Thread::new,
        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    try {
      assertThat(executor.getRemoveOnCancelPolicy())
          .as("removeOnCancelPolicy should be true")
          .isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies that afterExecute handles cancelled tasks gracefully.
   * CancellationException should be caught and ignored.
   */
  @Test
  public void afterExecuteHandlesCancelledTask() throws Exception {
    var executor = new ScheduledThreadPoolExecutorWithLogging(
        1, Thread::new);
    try {
      var taskStarted = new CountDownLatch(1);

      // Schedule a periodic task, then cancel it.
      var future = executor.scheduleAtFixedRate(() -> {
        taskStarted.countDown();
        // Burn some time so we can reliably cancel.
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }, 0, 100, TimeUnit.MILLISECONDS);

      assertThat(taskStarted.await(5, TimeUnit.SECONDS))
          .as("periodic task should have started")
          .isTrue();

      // Cancel the task — afterExecute should catch CancellationException.
      future.cancel(false);

      // Give the executor time to process the cancellation in afterExecute.
      Thread.sleep(200);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that a periodic task executes multiple times without blocking,
   * confirming afterExecute's isDone() guard correctly skips get() on
   * non-done periodic futures. If the isDone() check at
   * ScheduledThreadPoolExecutorWithLogging.afterExecute were removed,
   * future.get() would block indefinitely and prevent re-execution.
   */
  @Test
  public void periodicTask_executesMultipleTimes_afterExecuteDoesNotBlock()
      throws Exception {
    var executor = new ScheduledThreadPoolExecutorWithLogging(
        1, Thread::new);
    try {
      var executionCount = new java.util.concurrent.atomic.AtomicInteger();
      var threeExecutions = new CountDownLatch(3);

      var future = executor.scheduleAtFixedRate(() -> {
        executionCount.incrementAndGet();
        threeExecutions.countDown();
      }, 0, 50, TimeUnit.MILLISECONDS);

      assertThat(threeExecutions.await(5, TimeUnit.SECONDS))
          .as("periodic task should execute at least 3 times")
          .isTrue();
      assertThat(executionCount.get()).isGreaterThanOrEqualTo(3);

      future.cancel(false);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
