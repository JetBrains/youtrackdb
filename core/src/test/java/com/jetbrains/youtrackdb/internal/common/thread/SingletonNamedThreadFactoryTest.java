package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.util.UncaughtExceptionHandler;
import org.junit.Test;

/**
 * Tests for {@link SingletonNamedThreadFactory} (package-private, extends
 * BaseThreadFactory). Verifies that all threads from this factory use the
 * same fixed name (no incrementing counter).
 */
public class SingletonNamedThreadFactoryTest {

  /**
   * Verifies all threads use the same fixed name (no counter).
   */
  @Test
  public void newThread_allShareSameName() {
    var group = new ThreadGroup("test-singleton-group");
    var factory = new SingletonNamedThreadFactory("worker", group);

    var t1 = factory.newThread(() -> {
    });
    var t2 = factory.newThread(() -> {
    });
    var t3 = factory.newThread(() -> {
    });

    assertThat(t1.getName()).isEqualTo("worker");
    assertThat(t2.getName()).isEqualTo("worker");
    assertThat(t3.getName()).isEqualTo("worker");
  }

  /**
   * Verifies threads are daemon (inherited from BaseThreadFactory).
   */
  @Test
  public void newThread_isDaemon() {
    var group = new ThreadGroup("test-singleton-daemon");
    var factory = new SingletonNamedThreadFactory("daemon-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.isDaemon()).isTrue();
  }

  /**
   * Verifies threads are created in the specified thread group.
   */
  @Test
  public void newThread_usesProvidedThreadGroup() {
    var group = new ThreadGroup("singleton-grp");
    var factory = new SingletonNamedThreadFactory("grp-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.getThreadGroup()).isEqualTo(group);
  }

  /**
   * Verifies threads have an UncaughtExceptionHandler set.
   */
  @Test
  public void newThread_hasUncaughtExceptionHandler() {
    var group = new ThreadGroup("singleton-ueh");
    var factory = new SingletonNamedThreadFactory("ueh-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.getUncaughtExceptionHandler())
        .isInstanceOf(UncaughtExceptionHandler.class);
  }
}
