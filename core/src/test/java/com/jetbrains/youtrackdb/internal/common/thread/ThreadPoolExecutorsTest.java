package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for {@link ThreadPoolExecutors} factory methods. Verifies that each
 * factory creates an executor with the correct pool sizes, thread naming,
 * daemon flags, and queue types.
 */
public class ThreadPoolExecutorsTest {

  // --- newScalingThreadPool ---

  /**
   * Verifies newScalingThreadPool(6-arg) creates a ScalingThreadPoolExecutor
   * with the correct core/max pool sizes and daemon threads using
   * NamedThreadFactory.
   */
  @Test
  public void newScalingThreadPool_sixArg_createsWithCorrectSizes() {
    var executor = ThreadPoolExecutors.newScalingThreadPool(
        "test-scaling", 2, 8, 10, 60, TimeUnit.SECONDS);
    try {
      assertThat(executor).isInstanceOf(ScalingThreadPoolExecutor.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(2);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(8);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newScalingThreadPool creates daemon threads with names matching
   * the baseName-N pattern.
   */
  @Test
  public void newScalingThreadPool_createsDaemonThreadsWithCorrectNames()
      throws Exception {
    var executor = ThreadPoolExecutors.newScalingThreadPool(
        "test-scale", 1, 4, 10, 60, TimeUnit.SECONDS);
    try {
      var latch = new CountDownLatch(1);
      var threadName = new String[1];
      var isDaemon = new boolean[1];
      executor.execute(() -> {
        threadName[0] = Thread.currentThread().getName();
        isDaemon[0] = Thread.currentThread().isDaemon();
        latch.countDown();
      });
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(threadName[0]).startsWith("test-scale-");
      assertThat(isDaemon[0])
          .as("threads from BaseThreadFactory should be daemon")
          .isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newScalingThreadPool(7-arg) with explicit thread group creates
   * a ScalingThreadPoolExecutor.
   */
  @Test
  public void newScalingThreadPool_sevenArg_createsWithThreadGroup() {
    var group = new ThreadGroup("test-group");
    var executor = ThreadPoolExecutors.newScalingThreadPool(
        "test-scaling-tg", group, 1, 4, 5, 30, TimeUnit.SECONDS);
    try {
      assertThat(executor).isInstanceOf(ScalingThreadPoolExecutor.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(1);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(4);
    } finally {
      executor.shutdownNow();
    }
  }

  // --- newBlockingScalingThreadPool ---

  /**
   * Verifies newBlockingScalingThreadPool creates a ScalingThreadPoolExecutor
   * with bounded queue.
   */
  @Test
  public void newBlockingScalingThreadPool_createsBoundedScalingPool() {
    var group = new ThreadGroup("test-blocking");
    var executor = ThreadPoolExecutors.newBlockingScalingThreadPool(
        "test-blocking", group, 2, 6, 5, 20, 30, TimeUnit.SECONDS);
    try {
      assertThat(executor).isInstanceOf(ScalingThreadPoolExecutor.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(2);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(6);
    } finally {
      executor.shutdownNow();
    }
  }

  // --- newCachedThreadPool ---

  /**
   * Verifies newCachedThreadPool(name-only) creates a pool with 0 core,
   * MAX_VALUE max.
   */
  @Test
  public void newCachedThreadPool_nameOnly_createsUnboundedPool() {
    var executor = ThreadPoolExecutors.newCachedThreadPool("test-cached");
    try {
      assertThat(executor).isInstanceOf(ThreadPoolExecutorWithLogging.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(0);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(Integer.MAX_VALUE);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newCachedThreadPool(name, group) creates a pool with 0 core,
   * MAX_VALUE max.
   */
  @Test
  public void newCachedThreadPool_nameAndGroup_createsUnboundedPool() {
    var group = new ThreadGroup("test-cached-group");
    var executor = ThreadPoolExecutors.newCachedThreadPool(
        "test-cached-g", group);
    try {
      assertThat(executor).isInstanceOf(ThreadPoolExecutorWithLogging.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(0);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(Integer.MAX_VALUE);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newCachedThreadPool with maxThreads and maxQueue creates a
   * bounded pool with LinkedBlockingQueue.
   */
  @Test
  public void newCachedThreadPool_withMaxThreadsAndQueue_createsBoundedPool() {
    var group = new ThreadGroup("test-cached-bounded");
    var executor = ThreadPoolExecutors.newCachedThreadPool(
        "test-cached-b", group, 4, 10);
    try {
      assertThat(executor).isInstanceOf(ThreadPoolExecutorWithLogging.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(0);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(4);
      // maxQueue > 0 means LinkedBlockingQueue
      assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(10);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newCachedThreadPool with maxQueue=0 uses SynchronousQueue (no
   * buffering — tasks must be handed off directly to threads).
   */
  @Test
  public void newCachedThreadPool_maxQueueZero_usesSynchronousQueue() {
    var group = new ThreadGroup("test-cached-sync");
    var executor = ThreadPoolExecutors.newCachedThreadPool(
        "test-cached-s", group, 4, 0);
    try {
      var tpe = (ThreadPoolExecutor) executor;
      // SynchronousQueue has 0 remaining capacity
      assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(0);
      assertThat(tpe.getQueue().size()).isEqualTo(0);
    } finally {
      executor.shutdownNow();
    }
  }

  // --- newSingleThreadPool ---

  /**
   * Verifies newSingleThreadPool(name-only) creates a pool with core=1,
   * max=1.
   */
  @Test
  public void newSingleThreadPool_nameOnly_createsSingleThreadPool() {
    var executor = ThreadPoolExecutors.newSingleThreadPool("test-single");
    try {
      assertThat(executor).isInstanceOf(ThreadPoolExecutorWithLogging.class);
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(1);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newSingleThreadPool(name, group) creates a pool with core=1,
   * max=1 and threads use the singleton name (no counter suffix).
   */
  @Test
  public void newSingleThreadPool_nameAndGroup_usesSingletonName()
      throws Exception {
    var group = new ThreadGroup("test-single-group");
    var executor = ThreadPoolExecutors.newSingleThreadPool(
        "test-singleton", group);
    try {
      var latch = new CountDownLatch(1);
      var threadName = new String[1];
      executor.execute(() -> {
        threadName[0] = Thread.currentThread().getName();
        latch.countDown();
      });
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      // SingletonNamedThreadFactory returns the exact name (no counter)
      assertThat(threadName[0]).isEqualTo("test-singleton");
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newSingleThreadPool with maxQueue and rejection handler uses
   * a bounded queue and the provided handler.
   */
  @Test
  public void newSingleThreadPool_withMaxQueueAndHandler_createsBoundedPool() {
    var rejected = new boolean[1];
    ExecutorService executor = ThreadPoolExecutors.newSingleThreadPool(
        "test-single-bounded", 5,
        (r, e) -> rejected[0] = true);
    try {
      var tpe = (ThreadPoolExecutor) executor;
      assertThat(tpe.getCorePoolSize()).isEqualTo(1);
      assertThat(tpe.getMaximumPoolSize()).isEqualTo(1);
      assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(5);
    } finally {
      executor.shutdownNow();
    }
  }

  // --- newSingleThreadScheduledPool ---

  /**
   * Verifies newSingleThreadScheduledPool(name-only) creates a
   * ScheduledThreadPoolExecutorWithLogging with core=1.
   */
  @Test
  public void newSingleThreadScheduledPool_nameOnly_createsScheduledPool() {
    var executor = ThreadPoolExecutors.newSingleThreadScheduledPool(
        "test-sched");
    try {
      assertThat(executor).isInstanceOf(
          ScheduledThreadPoolExecutorWithLogging.class);
      assertThat(executor).isInstanceOf(ScheduledExecutorService.class);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verifies newSingleThreadScheduledPool(name, group) creates a scheduled
   * pool and threads use the singleton name.
   */
  @Test
  public void newSingleThreadScheduledPool_nameAndGroup_usesSingletonName()
      throws Exception {
    var group = new ThreadGroup("test-sched-group");
    var executor = ThreadPoolExecutors.newSingleThreadScheduledPool(
        "test-sched-name", group);
    try {
      var latch = new CountDownLatch(1);
      var threadName = new String[1];
      ((ScheduledExecutorService) executor).schedule(() -> {
        threadName[0] = Thread.currentThread().getName();
        latch.countDown();
      }, 0, TimeUnit.MILLISECONDS);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(threadName[0]).isEqualTo("test-sched-name");
    } finally {
      executor.shutdownNow();
    }
  }

  // --- newScheduledThreadPool ---

  /**
   * Verifies newScheduledThreadPool creates a scheduled pool with the given
   * core size and daemon threads with incrementing names.
   */
  @Test
  public void newScheduledThreadPool_createsPoolWithCorrectCoreSize()
      throws Exception {
    var group = new ThreadGroup("test-multi-sched");
    var executor = ThreadPoolExecutors.newScheduledThreadPool(
        "test-msched", group, 3);
    try {
      assertThat(executor).isInstanceOf(
          ScheduledThreadPoolExecutorWithLogging.class);
      var latch = new CountDownLatch(1);
      var threadName = new String[1];
      ((ScheduledExecutorService) executor).schedule(() -> {
        threadName[0] = Thread.currentThread().getName();
        latch.countDown();
      }, 0, TimeUnit.MILLISECONDS);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      // NamedThreadFactory uses baseName-N pattern
      assertThat(threadName[0]).startsWith("test-msched-");
    } finally {
      executor.shutdownNow();
    }
  }

  // --- Thread execution verification ---

  /**
   * Verifies that a task submitted to a scaling pool actually executes.
   */
  @Test
  public void newScalingThreadPool_executesSubmittedTasks() throws Exception {
    var executor = ThreadPoolExecutors.newScalingThreadPool(
        "test-exec", 1, 2, 5, 10, TimeUnit.SECONDS);
    try {
      var latch = new CountDownLatch(1);
      executor.execute(latch::countDown);
      assertThat(latch.await(5, TimeUnit.SECONDS))
          .as("submitted task should execute")
          .isTrue();
    } finally {
      executor.shutdownNow();
    }
  }
}
