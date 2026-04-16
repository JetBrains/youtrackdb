package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests for {@link NonDaemonThreadFactory}. Verifies thread creation with
 * correct naming, non-daemon flag, and uncaught exception handler.
 */
public class NonDaemonThreadFactoryTest {

  /**
   * Verifies that NonDaemonThreadFactory does NOT explicitly set daemon=true
   * (unlike BaseThreadFactory which does). The daemon status inherits from
   * the creating thread. When created from the main thread (non-daemon),
   * threads will be non-daemon by default.
   *
   * <p>Note: in surefire's parallel test runner, the creating thread may be
   * daemon, so we verify by creating from a known non-daemon thread.
   */
  @Test
  public void newThread_isNotDaemon_whenCreatedFromNonDaemonThread()
      throws Exception {
    var factory = new NonDaemonThreadFactory("test-prefix");
    var result = new boolean[1];
    // Create the thread from a known non-daemon thread to verify behavior.
    var creator = new Thread(() -> {
      var thread = factory.newThread(() -> {
      });
      result[0] = thread.isDaemon();
    });
    creator.setDaemon(false);
    creator.start();
    creator.join(5000);
    assertThat(result[0])
        .as("thread created from non-daemon should be non-daemon")
        .isFalse();
  }

  /**
   * Verifies the thread name follows the "{prefix} #{N}" pattern with
   * incrementing counter.
   */
  @Test
  public void newThread_nameFollowsPattern() {
    var factory = new NonDaemonThreadFactory("worker");
    var t1 = factory.newThread(() -> {
    });
    var t2 = factory.newThread(() -> {
    });
    var t3 = factory.newThread(() -> {
    });

    assertThat(t1.getName()).isEqualTo("worker #1");
    assertThat(t2.getName()).isEqualTo("worker #2");
    assertThat(t3.getName()).isEqualTo("worker #3");
  }

  /**
   * Verifies that each thread has an UncaughtExceptionHandler set.
   */
  @Test
  public void newThread_hasUncaughtExceptionHandler() {
    var factory = new NonDaemonThreadFactory("handler-test");
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.getUncaughtExceptionHandler()).isNotNull();
  }

  /**
   * Verifies that separate factory instances have independent counters.
   */
  @Test
  public void separateFactories_haveIndependentCounters() {
    var f1 = new NonDaemonThreadFactory("a");
    var f2 = new NonDaemonThreadFactory("b");

    var t1 = f1.newThread(() -> {
    });
    var t2 = f2.newThread(() -> {
    });

    assertThat(t1.getName()).isEqualTo("a #1");
    assertThat(t2.getName()).isEqualTo("b #1");
  }

  /**
   * Verifies the thread receives the provided runnable.
   */
  @Test
  public void newThread_runsProvidedRunnable() throws Exception {
    var factory = new NonDaemonThreadFactory("runner");
    var ran = new boolean[1];
    var thread = factory.newThread(() -> ran[0] = true);
    thread.start();
    thread.join(5000);
    assertThat(ran[0]).isTrue();
  }
}
