package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests for {@link NamedThreadFactory} (package-private, extends
 * BaseThreadFactory). Verifies thread naming with incrementing counter,
 * daemon flag, thread group, and uncaught exception handler.
 */
public class NamedThreadFactoryTest {

  /**
   * Verifies thread names follow the "{baseName}-{N}" pattern with
   * incrementing counter starting at 1.
   */
  @Test
  public void newThread_nameUsesIncrementingCounter() {
    var group = new ThreadGroup("test-named-group");
    var factory = new NamedThreadFactory("io", group);

    var t1 = factory.newThread(() -> {
    });
    var t2 = factory.newThread(() -> {
    });
    var t3 = factory.newThread(() -> {
    });

    assertThat(t1.getName()).isEqualTo("io-1");
    assertThat(t2.getName()).isEqualTo("io-2");
    assertThat(t3.getName()).isEqualTo("io-3");
  }

  /**
   * Verifies threads are daemon (inherited from BaseThreadFactory).
   */
  @Test
  public void newThread_isDaemon() {
    var group = new ThreadGroup("test-daemon-group");
    var factory = new NamedThreadFactory("daemon-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.isDaemon()).isTrue();
  }

  /**
   * Verifies threads are created in the specified thread group.
   */
  @Test
  public void newThread_usesProvidedThreadGroup() {
    var group = new ThreadGroup("custom-group");
    var factory = new NamedThreadFactory("grp-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.getThreadGroup()).isEqualTo(group);
  }

  /**
   * Verifies threads have an UncaughtExceptionHandler set.
   */
  @Test
  public void newThread_hasUncaughtExceptionHandler() {
    var group = new ThreadGroup("ueh-group");
    var factory = new NamedThreadFactory("ueh-test", group);
    var thread = factory.newThread(() -> {
    });
    assertThat(thread.getUncaughtExceptionHandler()).isNotNull();
  }

  /**
   * Verifies the runnable is passed through to the created thread.
   */
  @Test
  public void newThread_executesProvidedRunnable() throws Exception {
    var group = new ThreadGroup("run-group");
    var factory = new NamedThreadFactory("run-test", group);
    var ran = new boolean[1];
    var thread = factory.newThread(() -> ran[0] = true);
    thread.start();
    thread.join(5000);
    assertThat(ran[0]).isTrue();
  }
}
