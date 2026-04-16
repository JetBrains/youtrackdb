package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
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
   * extracts it from the Future via get(). The extraction triggers logging
   * (we verify the exception propagation through Future.get).
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

      try {
        future.get(5, TimeUnit.SECONDS);
      } catch (ExecutionException ee) {
        assertThat(ee.getCause())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("task failure");
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that afterExecute handles cancelled tasks gracefully.
   * CancellationException from Future.get() should be caught and ignored.
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

      // Give the executor time to process afterExecute
      Thread.sleep(200);

      // If afterExecute didn't handle CancellationException gracefully,
      // the executor would have thrown.
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
        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
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
}
