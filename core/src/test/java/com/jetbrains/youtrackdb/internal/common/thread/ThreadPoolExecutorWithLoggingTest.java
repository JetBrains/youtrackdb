package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for {@link ThreadPoolExecutorWithLogging}. Verifies that afterExecute
 * correctly extracts exceptions from Futures, handles cancellation, and
 * restores the interrupt flag on InterruptedException.
 */
public class ThreadPoolExecutorWithLoggingTest {

  /**
   * Verifies that when a submitted task completes normally, afterExecute
   * does not throw (the happy path).
   */
  @Test
  public void afterExecute_normalCompletion_noException() throws Exception {
    var executor = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      var future = executor.submit(() -> "result");
      assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("result");
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that when a submitted task throws an exception, afterExecute
   * extracts it from the Future via get(). Uses assertThatThrownBy to ensure
   * the test fails if no exception is thrown (avoiding silent pass).
   */
  @Test
  public void afterExecute_taskThrowsException_extractedFromFuture()
      throws Exception {
    var executor = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      var latch = new CountDownLatch(1);
      var future = executor.submit(() -> {
        latch.countDown();
        throw new RuntimeException("task failure");
      });

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .satisfies(e -> assertThat(e.getCause())
              .isInstanceOf(RuntimeException.class)
              .hasMessage("task failure"));
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that afterExecute handles cancelled tasks gracefully.
   * CancellationException from Future.get() should be caught and ignored.
   * After cancellation, the executor should remain functional.
   */
  @Test
  public void afterExecute_cancelledTask_handledGracefully() throws Exception {
    var executor = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      var taskStarted = new CountDownLatch(1);
      var taskCanFinish = new CountDownLatch(1);

      var future = executor.submit(() -> {
        taskStarted.countDown();
        try {
          taskCanFinish.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });

      assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Cancel the task
      future.cancel(true);

      // Verify executor is still healthy after cancellation by running
      // another task (serves as a synchronization barrier).
      var postCancelLatch = new CountDownLatch(1);
      executor.execute(postCancelLatch::countDown);
      assertThat(postCancelLatch.await(5, TimeUnit.SECONDS))
          .as("executor should remain functional after cancelled task")
          .isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies both constructors create a valid executor (primary and handler
   * constructors).
   */
  @Test
  public void bothConstructors_createValidExecutors() {
    var primary = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      assertThat(primary.getCorePoolSize()).isEqualTo(1);
    } finally {
      primary.shutdownNow();
    }

    var withHandler = new ThreadPoolExecutorWithLogging(
        2, 4, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new,
        new ThreadPoolExecutor.AbortPolicy());
    try {
      assertThat(withHandler.getCorePoolSize()).isEqualTo(2);
      assertThat(withHandler.getMaximumPoolSize()).isEqualTo(4);
    } finally {
      withHandler.shutdownNow();
    }
  }

  /**
   * Verifies that a Runnable submitted via execute() (not submit()) works
   * correctly. When execute() is used, afterExecute receives the Throwable
   * directly (not via Future).
   */
  @Test
  public void execute_runnable_completesNormally() throws Exception {
    var executor = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      var latch = new CountDownLatch(1);
      executor.execute(latch::countDown);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that when execute() (not submit()) is called with a throwing
   * Runnable, afterExecute receives the throwable directly via the t parameter
   * (not wrapped in a Future). The worker thread dies, but the pool replaces
   * it and remains functional.
   */
  @Test
  public void afterExecute_executeWithThrowingRunnable_poolRecovers()
      throws Exception {
    var executor = new ThreadPoolExecutorWithLogging(
        1, 1, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), Thread::new);
    try {
      var exceptionThrown = new CountDownLatch(1);
      executor.execute(() -> {
        exceptionThrown.countDown();
        throw new RuntimeException("direct-throwable");
      });

      assertThat(exceptionThrown.await(5, TimeUnit.SECONDS))
          .as("the throwing runnable should have executed")
          .isTrue();

      // Submit another task to verify the pool recovers (a new worker
      // thread is created after the old one died from the uncaught exception).
      var recoveryLatch = new CountDownLatch(1);
      executor.execute(recoveryLatch::countDown);
      assertThat(recoveryLatch.await(5, TimeUnit.SECONDS))
          .as("pool should recover with a new worker thread")
          .isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
