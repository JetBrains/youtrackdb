package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ScalingThreadPoolExecutor} and its inner ScalingQueue.
 * Verifies pool growth beyond core size, probabilistic queue rejection,
 * maxPoolReached flag behavior, and bounded queue variant.
 */
public class ScalingThreadPoolExecutorTest {

  /**
   * Creates a NamedThreadFactory in the current thread group for test pools.
   */
  private NamedThreadFactory testFactory(String name) {
    return new NamedThreadFactory(name, Thread.currentThread().getThreadGroup());
  }

  /**
   * Verifies that tasks are executed even when only core threads exist
   * and the queue hasn't grown beyond target capacity.
   */
  @Test
  public void basicExecution_tasksComplete() throws Exception {
    var executor = new ScalingThreadPoolExecutor(
        1, 4, 60, TimeUnit.SECONDS, 10, testFactory("test-basic"));
    try {
      var latch = new CountDownLatch(3);
      for (int i = 0; i < 3; i++) {
        executor.execute(latch::countDown);
      }
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that the pool grows beyond core size when the queue reaches
   * the target capacity (ScalingQueue rejects offers, triggering pool growth).
   */
  @Test
  public void poolGrowsBeyondCoreSize_whenQueueReachesTargetCapacity()
      throws Exception {
    // Core=1, Max=4, target queue capacity=1 (offers always rejected when
    // queue is non-empty, forcing pool growth).
    var executor = new ScalingThreadPoolExecutor(
        1, 4, 60, TimeUnit.SECONDS, 1, testFactory("test-grow"));
    try {
      var blockingLatch = new CountDownLatch(1);
      var tasksStarted = new AtomicInteger();

      // Submit 4 blocking tasks — should force pool to grow to 4 threads.
      for (int i = 0; i < 4; i++) {
        executor.execute(() -> {
          tasksStarted.incrementAndGet();
          try {
            blockingLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }

      // Wait for pool to grow and tasks to start.
      Thread.sleep(500);

      assertThat(executor.getPoolSize())
          .as("pool should grow beyond core (1) to handle blocked tasks")
          .isGreaterThan(1);
      assertThat(tasksStarted.get())
          .as("multiple tasks should have started due to pool growth")
          .isGreaterThan(1);

      blockingLatch.countDown();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that afterExecute updates the maxPoolReached flag correctly.
   * When pool size equals max pool size, maxPoolReached should be set to true,
   * causing the queue to accept all future offers.
   *
   * <p>With targetCapacity=1, ScalingQueue.offer() rejects when queue.size() > 0.
   * We need 3 blocking tasks: task1 occupies the core thread, task2 is queued
   * (queue was empty at offer time), task3's offer sees size=1 and rejects,
   * triggering pool growth to max=2.
   */
  @Test
  public void afterExecute_setsMaxPoolReached_whenPoolAtMax()
      throws Exception {
    var executor = new ScalingThreadPoolExecutor(
        1, 2, 60, TimeUnit.SECONDS, 1, testFactory("test-maxpool"));
    try {
      var blockingLatch = new CountDownLatch(1);

      // Task 1: occupies the core thread.
      executor.execute(() -> {
        try {
          blockingLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      // Task 2: queued (queue is empty when offered, trigger=0, 0>0=false).
      executor.execute(() -> {
        try {
          blockingLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      // Task 3: offer sees queue size=1 > trigger=0, rejected → pool grows.
      executor.execute(() -> {
        try {
          blockingLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });

      Thread.sleep(500);
      assertThat(executor.getPoolSize()).isEqualTo(2);

      // Now submit a 4th task — since maxPoolReached should be true after
      // pool reached max, it should be queued via offer (not rejected).
      var fourthDone = new CountDownLatch(1);
      executor.execute(fourthDone::countDown);

      blockingLatch.countDown();
      assertThat(fourthDone.await(5, TimeUnit.SECONDS))
          .as("fourth task should complete after blocking tasks finish")
          .isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that when targetCapacity <= 1 and queue is non-empty, offer()
   * always returns false (triggers pool growth).
   *
   * <p>Need 3 tasks: task1 occupies core, task2 is queued (queue was empty),
   * task3 sees queue.size()=1 > trigger=0, offer rejects, pool grows.
   */
  @Test
  public void targetCapacityOne_alwaysRejectsWhenNonEmpty() throws Exception {
    var executor = new ScalingThreadPoolExecutor(
        1, 2, 60, TimeUnit.SECONDS, 1, testFactory("test-tc1"));
    try {
      var blockLatch = new CountDownLatch(1);
      var doneLatch = new CountDownLatch(3);

      // Task 1: blocks the core thread.
      executor.execute(() -> {
        try {
          blockLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      });
      // Task 2: queued (queue empty, size=0 > 0 = false, offer succeeds).
      executor.execute(doneLatch::countDown);
      // Task 3: queue has task 2, size=1 > 0 = true, offer rejects → growth.
      executor.execute(doneLatch::countDown);

      // Wait for pool growth
      Thread.sleep(300);
      assertThat(executor.getPoolSize())
          .as("pool should grow to 2 since target capacity is 1")
          .isEqualTo(2);

      blockLatch.countDown();
      assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // --- Bounded queue variant ---

  /**
   * Verifies the bounded queue constructor creates a pool that works
   * correctly.
   */
  @Test
  public void boundedQueue_executesTasksCorrectly() throws Exception {
    var executor = new ScalingThreadPoolExecutor(
        1, 4, 60, TimeUnit.SECONDS, 5, 20,
        testFactory("test-bounded"));
    try {
      var latch = new CountDownLatch(5);
      for (int i = 0; i < 5; i++) {
        executor.execute(latch::countDown);
      }
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that the bounded queue constructor rejects
   * targetCapacity >= maxQueueCapacity.
   */
  @Test
  public void boundedQueue_targetGteMax_throwsIAE() {
    assertThatThrownBy(() -> new ScalingThreadPoolExecutor(
        1, 2, 60, TimeUnit.SECONDS, 10, 10,
        testFactory("test-iae")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Target capacity must be less than max");
  }

  /**
   * Verifies that the rejection handler for ScalingThreadPoolExecutor calls
   * safeOffer on the ScalingQueue (i.e., rejected tasks are re-queued).
   */
  @Test
  public void rejectedTask_reQueued_viaSafeOffer() throws Exception {
    // Core=1, Max=1, target=1. With core=max, pool can't grow, so all
    // extra tasks go through the rejection handler which calls safeOffer.
    var executor = new ScalingThreadPoolExecutor(
        1, 1, 60, TimeUnit.SECONDS, 1, testFactory("test-reject"));
    try {
      var blockLatch = new CountDownLatch(1);
      var allDone = new CountDownLatch(3);

      // Submit a blocking task, then 2 more.
      executor.execute(() -> {
        try {
          blockLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          allDone.countDown();
        }
      });

      // These will be rejected by offer() and re-queued via safeOffer.
      executor.execute(allDone::countDown);
      executor.execute(allDone::countDown);

      blockLatch.countDown();
      assertThat(allDone.await(5, TimeUnit.SECONDS))
          .as("all tasks including re-queued ones should complete")
          .isTrue();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Verifies that when the queue becomes empty, maxPoolReached is reset
   * to false (allowing future pool growth).
   *
   * <p>With targetCapacity=1, growth requires 3 tasks: task1 occupies core,
   * task2 is queued (queue was empty), task3 sees size=1 > trigger=0 and
   * triggers growth.
   */
  @Test
  public void emptyQueue_resetsMaxPoolReached() throws Exception {
    var executor = new ScalingThreadPoolExecutor(
        1, 2, 60, TimeUnit.SECONDS, 1, testFactory("test-reset"));
    try {
      // Submit and wait for a task to complete — queue becomes empty.
      var done = new CountDownLatch(1);
      executor.execute(done::countDown);
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

      // Wait for afterExecute to process and ScalingQueue.offer() to
      // reset maxPoolReached on the next isEmpty() check.
      Thread.sleep(200);

      // Now submit 3 blocking tasks — pool should be able to grow.
      var blockLatch = new CountDownLatch(1);
      var started = new AtomicInteger();
      // Task 1: occupies core thread
      // Task 2: queued (queue empty at offer)
      // Task 3: offer rejects (size=1 > 0), pool grows
      for (int i = 0; i < 3; i++) {
        executor.execute(() -> {
          started.incrementAndGet();
          try {
            blockLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }

      Thread.sleep(500);
      assertThat(started.get())
          .as("pool should grow again after queue was empty (2 running + 1 queued)")
          .isGreaterThanOrEqualTo(2);

      blockLatch.countDown();
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
