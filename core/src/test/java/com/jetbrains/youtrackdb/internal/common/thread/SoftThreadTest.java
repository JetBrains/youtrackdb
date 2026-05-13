package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.util.UncaughtExceptionHandler;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link SoftThread}. Verifies lifecycle hooks (startup, execute,
 * shutdown), exception handling (Exception logged and loop continues,
 * Error calls shutdown and re-throws), shutdown mechanisms (sendShutdown,
 * softShutdown, interrupt), and daemon flag behavior.
 */
public class SoftThreadTest {

  /**
   * Creates a concrete SoftThread subclass for testing. Counts execute()
   * calls, can throw exceptions, and signals when started/shutdown.
   */
  private static class TestSoftThread extends SoftThread {
    final AtomicInteger executeCount = new AtomicInteger();
    final CountDownLatch startupLatch = new CountDownLatch(1);
    final CountDownLatch shutdownLatch = new CountDownLatch(1);
    final CountDownLatch firstExecuteLatch = new CountDownLatch(1);
    volatile RuntimeException exceptionToThrow;
    volatile Error errorToThrow;

    TestSoftThread(String name) {
      super(name);
    }

    TestSoftThread(ThreadGroup group, String name) {
      super(group, name);
    }

    @Override
    public void startup() {
      startupLatch.countDown();
    }

    @Override
    public void shutdown() {
      shutdownLatch.countDown();
    }

    @Override
    protected void execute() throws Exception {
      int count = executeCount.incrementAndGet();
      if (count == 1) {
        firstExecuteLatch.countDown();
      }
      if (errorToThrow != null) {
        throw errorToThrow;
      }
      if (exceptionToThrow != null) {
        throw exceptionToThrow;
      }
      // After at least one successful execution, shut down to prevent
      // infinite loop.
      if (count >= 3) {
        sendShutdown();
      }
    }
  }

  /**
   * Verifies the basic lifecycle: startup() called before execute(),
   * execute() called in a loop, shutdown() called when loop ends.
   */
  @Test
  public void lifecycle_startupExecuteShutdown() throws Exception {
    var thread = new TestSoftThread("test-lifecycle");
    thread.start();

    assertThat(thread.startupLatch.await(5, TimeUnit.SECONDS))
        .as("startup() should be called")
        .isTrue();
    assertThat(thread.firstExecuteLatch.await(5, TimeUnit.SECONDS))
        .as("execute() should be called at least once")
        .isTrue();
    assertThat(thread.shutdownLatch.await(5, TimeUnit.SECONDS))
        .as("shutdown() should be called after loop exits")
        .isTrue();
    thread.join(5000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(thread.executeCount.get())
        .as("execute() should have been called multiple times")
        .isGreaterThanOrEqualTo(3);
  }

  /**
   * Verifies sendShutdown() sets the shutdown flag, causing the loop to exit.
   */
  @Test
  public void sendShutdown_stopsLoop() throws Exception {
    var thread = new TestSoftThread("test-sendShutdown") {
      @Override
      protected void execute() {
        executeCount.incrementAndGet();
        firstExecuteLatch.countDown();
        // Block until shutdown is requested to prevent tight loop
        while (!isShutdownFlag()) {
          Thread.yield();
        }
      }
    };
    thread.start();

    assertThat(thread.firstExecuteLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(thread.isShutdownFlag()).isFalse();

    thread.sendShutdown();
    assertThat(thread.isShutdownFlag()).isTrue();

    thread.join(5000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(thread.shutdownLatch.await(1, TimeUnit.SECONDS))
        .as("shutdown() should have been called")
        .isTrue();
  }

  /**
   * Verifies softShutdown() sets the shutdown flag (same as sendShutdown).
   */
  @Test
  public void softShutdown_setsShutdownFlag() throws Exception {
    var thread = new TestSoftThread("test-softShutdown") {
      @Override
      protected void execute() {
        executeCount.incrementAndGet();
        firstExecuteLatch.countDown();
        while (!isShutdownFlag()) {
          Thread.yield();
        }
      }
    };
    thread.start();

    assertThat(thread.firstExecuteLatch.await(5, TimeUnit.SECONDS)).isTrue();
    thread.softShutdown();
    assertThat(thread.isShutdownFlag()).isTrue();
    thread.join(5000);
    assertThat(thread.isAlive()).isFalse();
  }

  /**
   * Verifies that interrupting the thread causes the run() loop to exit
   * when execute() re-sets the interrupt flag after InterruptedException.
   *
   * <p>Note: SoftThread's run() catches Exception (including InterruptedException),
   * which clears the interrupt flag. The loop only exits on interrupt if
   * execute() re-interrupts the current thread so that isInterrupted()
   * returns true on the next loop check.
   */
  @Test
  public void interrupt_stopsLoop_whenExecutePreservesFlag() throws Exception {
    var thread = new TestSoftThread("test-interrupt") {
      @Override
      protected void execute() throws InterruptedException {
        executeCount.incrementAndGet();
        firstExecuteLatch.countDown();
        try {
          // Block until interrupted
          Thread.sleep(60_000);
        } catch (InterruptedException e) {
          // Re-set the interrupt flag so that the while loop exits
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    };
    thread.start();

    assertThat(thread.firstExecuteLatch.await(5, TimeUnit.SECONDS)).isTrue();
    thread.interrupt();
    thread.join(5000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(thread.shutdownLatch.await(1, TimeUnit.SECONDS))
        .as("shutdown() should be called when interrupt flag is preserved")
        .isTrue();
  }

  /**
   * Verifies that when execute() throws an Exception, the loop continues
   * (exception is logged and swallowed) and shutdown is eventually called.
   */
  @Test
  public void executeThrowsException_loopContinues() throws Exception {
    // Override execute to throw for the first 2 calls, then shut down
    var callCount = new AtomicInteger();
    var testThread = new SoftThread("test-exception-loop") {
      final CountDownLatch shutdownLatch = new CountDownLatch(1);

      @Override
      public void shutdown() {
        shutdownLatch.countDown();
      }

      @Override
      protected void execute() {
        int c = callCount.incrementAndGet();
        if (c <= 2) {
          throw new RuntimeException("test exception " + c);
        }
        sendShutdown();
      }
    };
    testThread.start();

    assertThat(testThread.shutdownLatch.await(5, TimeUnit.SECONDS))
        .as("thread should eventually shut down despite exceptions")
        .isTrue();
    testThread.join(5000);
    assertThat(callCount.get())
        .as("execute() should have continued after exception")
        .isGreaterThanOrEqualTo(3);
  }

  /**
   * Verifies that when execute() throws an Error, shutdown() is called and
   * the Error is re-thrown (the thread terminates).
   */
  @Test
  public void executeThrowsError_shutdownCalledAndErrorRethrown()
      throws Exception {
    var shutdownCalled = new AtomicBoolean(false);
    var caughtError = new AtomicReference<Throwable>();
    var thread = new SoftThread("test-error") {
      @Override
      public void shutdown() {
        shutdownCalled.set(true);
      }

      @Override
      protected void execute() {
        throw new OutOfMemoryError("test error");
      }
    };
    thread.setDumpExceptions(true);
    // Capture the error via uncaught exception handler to prevent
    // test framework noise.
    thread.setUncaughtExceptionHandler((t, e) -> caughtError.set(e));
    thread.start();
    thread.join(5000);

    assertThat(thread.isAlive()).isFalse();
    assertThat(shutdownCalled.get())
        .as("shutdown() should be called when Error is thrown")
        .isTrue();
    assertThat(caughtError.get())
        .as("Error should be re-thrown after shutdown()")
        .isInstanceOf(OutOfMemoryError.class)
        .hasMessage("test error");
  }

  /**
   * Verifies the single-arg constructor sets the thread name and installs
   * the project's UncaughtExceptionHandler. (Unlike the ThreadGroup
   * constructor, it does not explicitly set daemon=true — daemon status
   * is inherited from the creating thread.)
   */
  @Test
  public void singleArgConstructor_setsNameAndUEH() {
    var thread = new TestSoftThread("test-named");
    assertThat(thread.getName()).isEqualTo("test-named");
    assertThat(thread.getUncaughtExceptionHandler())
        .isInstanceOf(UncaughtExceptionHandler.class);
  }

  /**
   * Verifies the ThreadGroup constructor creates a daemon thread.
   */
  @Test
  public void threadGroupConstructor_createsDaemonThread() {
    var group = new ThreadGroup("test-group");
    var thread = new TestSoftThread(group, "test-daemon");
    assertThat(thread.isDaemon()).isTrue();
    assertThat(thread.getName()).isEqualTo("test-daemon");
    assertThat(thread.getThreadGroup()).isEqualTo(group);
  }

  /**
   * Verifies isShutdownFlag() returns false initially and true after
   * sendShutdown().
   */
  @Test
  public void isShutdownFlag_reflectsState() {
    var thread = new TestSoftThread("test-flag");
    assertThat(thread.isShutdownFlag()).isFalse();
    thread.sendShutdown();
    assertThat(thread.isShutdownFlag()).isTrue();
  }

  /**
   * Verifies setDumpExceptions controls whether exceptions are logged.
   * With dumpExceptions=false, exceptions in execute() are silently swallowed.
   */
  @Test
  public void setDumpExceptions_false_silentlyContinues() throws Exception {
    var callCount = new AtomicInteger();
    var shutdownLatch = new CountDownLatch(1);
    var thread = new SoftThread("test-nodump") {
      @Override
      public void shutdown() {
        shutdownLatch.countDown();
      }

      @Override
      protected void execute() {
        int c = callCount.incrementAndGet();
        if (c <= 2) {
          throw new RuntimeException("silent " + c);
        }
        sendShutdown();
      }
    };
    thread.setDumpExceptions(false);
    thread.start();

    assertThat(shutdownLatch.await(5, TimeUnit.SECONDS)).isTrue();
    thread.join(5000);
    assertThat(callCount.get())
        .as("loop should continue even with dumpExceptions=false")
        .isGreaterThanOrEqualTo(3);
  }

  /**
   * Verifies that beforeExecution() is called before each execute() and
   * afterExecution() is called after.
   */
  @Test
  public void beforeAndAfterExecution_calledAroundExecute() throws Exception {
    var order = new CopyOnWriteArrayList<String>();
    var doneLatch = new CountDownLatch(1);
    var thread = new SoftThread("test-hooks") {
      @Override
      protected void beforeExecution() {
        order.add("before");
      }

      @Override
      protected void execute() {
        order.add("execute");
        sendShutdown();
      }

      @Override
      protected void afterExecution() {
        order.add("after");
      }

      @Override
      public void shutdown() {
        doneLatch.countDown();
      }
    };
    thread.start();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    thread.join(5000);

    assertThat(order).containsExactly("before", "execute", "after");
  }

  /**
   * Verifies that both constructors set an UncaughtExceptionHandler.
   */
  @Test
  public void bothConstructors_setUncaughtExceptionHandler() {
    var thread1 = new TestSoftThread("test-ueh1");
    assertThat(thread1.getUncaughtExceptionHandler())
        .isInstanceOf(UncaughtExceptionHandler.class);

    var group = new ThreadGroup("test-ueh-group");
    var thread2 = new TestSoftThread(group, "test-ueh2");
    assertThat(thread2.getUncaughtExceptionHandler())
        .isInstanceOf(UncaughtExceptionHandler.class);
  }
}
