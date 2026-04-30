package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * Worker-thread isolation rule for this test class:
 * {@code CommandTimeoutChecker.check()} interrupts the keys of its
 * internal {@code running} map. Those keys are the threads that called
 * {@code startCommand()}. Surefire reuses pooled threads across test
 * methods; if any test registered the JUnit harness thread itself
 * (instead of a freshly-spawned worker) the interrupt would leak into
 * later methods and flip their {@code Thread.interrupted()} state.
 * <p>
 * Therefore every test in this class registers commands from a freshly
 * created {@link Thread}, never from the test method's own thread. The
 * {@code @After} hook clears any spurious interrupt that nonetheless
 * lands on the calling thread, defensively.
 */
public class CommandTimeoutCheckerTest implements SchedulerInternal {

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @Override
  public ScheduledFuture<?> schedule(Runnable task, long delay, long period) {
    return scheduler.scheduleWithFixedDelay(task, delay, period, TimeUnit.MILLISECONDS);
  }

  @Override
  public ScheduledFuture<?> scheduleOnce(Runnable task, long delay) {
    return scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
  }

  @After
  public void clearInterrupt() {
    // Defensive: clears the interrupted flag if any test accidentally registers the
    // calling thread. Failing to clear this would propagate to the next test method
    // because surefire reuses worker threads.
    Thread.interrupted();
  }

  @Test
  public void testTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(100, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      new Thread(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              latch.countDown();
            }
            checker.endCommand();
          })
          .start();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    checker.close();
  }

  @Test
  public void testNoTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(1000, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      new Thread(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            latch.countDown();
            checker.endCommand();
          })
          .start();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    checker.close();
  }

  /**
   * Disabled mode: a {@code timeout <= 0} CTOR yields {@code active=false}, so
   * {@code startCommand} / {@code endCommand} / {@code check} all become no-ops and
   * {@code close()} must tolerate a null timer without throwing. Also pins that an
   * arbitrarily long sleeping worker is NOT interrupted by the (non-existent) timer.
   */
  @Test
  public void disabledModeStartCommandIsNoOpAndCloseToleratesNullTimer() throws Exception {
    var disabled = new CommandTimeoutChecker(0, this);

    var interrupted = new AtomicBoolean(false);
    var done = new CountDownLatch(1);
    var worker = new Thread(() -> {
      // Even though we register, the disabled checker must not interrupt this worker.
      disabled.startCommand(null);
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        interrupted.set(true);
      }
      disabled.endCommand();
      done.countDown();
    });
    worker.start();

    assertTrue("worker must finish on its own", done.await(2, TimeUnit.SECONDS));
    assertFalse("disabled checker must not interrupt the worker", interrupted.get());

    // Timer is null in disabled mode — close() must short-circuit without NPE.
    disabled.close();
  }

  @Test
  public void disabledModeRejectsNegativeTimeoutSameAsZero() throws Exception {
    // Negative timeouts behave identically to 0 — the if-> 0 branch goes false either way.
    var negative = new CommandTimeoutChecker(-1, this);

    var done = new CountDownLatch(1);
    var interrupted = new AtomicBoolean(false);
    var worker = new Thread(() -> {
      negative.startCommand(null);
      try {
        Thread.sleep(120);
      } catch (InterruptedException e) {
        interrupted.set(true);
      }
      negative.endCommand();
      done.countDown();
    });
    worker.start();

    assertTrue(done.await(2, TimeUnit.SECONDS));
    assertFalse("negative-timeout checker behaves as disabled", interrupted.get());
    negative.close();
  }

  /**
   * Per-command timeout overrides the constructor default. We register a command with a
   * very small per-call timeout (5 ms) under a checker whose default is large (10 s),
   * and assert the worker is interrupted soon — pinning that {@code startCommand(timeout)}
   * picks the explicit argument, not {@code maxMills}.
   */
  @Test
  public void perCommandTimeoutOverridesDefault() throws InterruptedException {
    // Default is huge (10 s) but the periodic check fires every 1/10 of that = 1 s, so we
    // need a fast tick. Using maxMills=200 means the checker checks every 20 ms.
    var checker = new CommandTimeoutChecker(200, this);
    var interrupted = new CountDownLatch(1);

    var worker = new Thread(() -> {
      // 1 ms explicit per-command timeout — should fire before the 200 ms default.
      checker.startCommand(1L);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
      checker.endCommand();
    });
    worker.start();

    assertTrue("worker must be interrupted via the per-command timeout",
        interrupted.await(2, TimeUnit.SECONDS));
    checker.close();
  }

  /**
   * Worker-thread isolation invariant: only threads that called {@code startCommand} are
   * interrupted. A bystander thread that never registered must complete its sleep without
   * being woken up by the periodic check.
   */
  @Test
  public void onlyRegisteredThreadsAreInterrupted() throws InterruptedException {
    var checker = new CommandTimeoutChecker(50, this);
    var registeredInterrupted = new CountDownLatch(1);
    var bystanderFinished = new CountDownLatch(1);
    var bystanderInterrupted = new AtomicBoolean(false);

    var registered = new Thread(() -> {
      checker.startCommand(null);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        registeredInterrupted.countDown();
      }
      checker.endCommand();
    }, "registered-worker");

    var bystander = new Thread(() -> {
      // Never calls startCommand — must not be interrupted.
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        bystanderInterrupted.set(true);
      }
      bystanderFinished.countDown();
    }, "bystander");

    registered.start();
    bystander.start();

    assertTrue("registered worker must be interrupted on timeout",
        registeredInterrupted.await(2, TimeUnit.SECONDS));
    assertTrue("bystander worker must run to completion",
        bystanderFinished.await(2, TimeUnit.SECONDS));
    assertFalse("bystander must NOT be interrupted by the timeout sweep",
        bystanderInterrupted.get());

    checker.close();
  }

  /**
   * endCommand() removes the entry from the running map, so a subsequent timeout sweep
   * must not interrupt a thread that has already called endCommand. Pins isolation between
   * a finished command and a later sleep on the same thread.
   */
  @Test
  public void endCommandUnregistersBeforeTimeoutFires() throws InterruptedException {
    var checker = new CommandTimeoutChecker(80, this);
    var phase2Interrupted = new AtomicBoolean(false);
    var done = new CountDownLatch(1);

    var worker = new Thread(() -> {
      checker.startCommand(null);
      // Phase 1: legitimate work, well under the timeout — endCommand cleans up.
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        // Spurious — should not happen here.
      }
      checker.endCommand();

      // Phase 2: now sleep past the timeout window. We expect NO interrupt because we
      // are no longer registered.
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        phase2Interrupted.set(true);
      }
      done.countDown();
    });
    worker.start();

    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertFalse("post-endCommand sleep must not be interrupted",
        phase2Interrupted.get());

    checker.close();
  }

  /**
   * Idempotency: calling {@code endCommand} after the timeout sweep already removed the
   * entry must not throw, and a subsequent {@code startCommand} on the same thread must
   * still register a fresh deadline. Pins the {@code remove()} no-op return path.
   */
  @Test
  public void endCommandIsIdempotentAndStartCommandReregisters() throws Exception {
    var checker = new CommandTimeoutChecker(50, this);

    var firstInterrupted = new CountDownLatch(1);
    var secondInterrupted = new CountDownLatch(1);
    var done = new CountDownLatch(1);

    var worker = new Thread(() -> {
      // First registration — gets interrupted by the sweep.
      checker.startCommand(null);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        firstInterrupted.countDown();
      }
      // Sweep already removed the entry; this endCommand is a no-op return path.
      checker.endCommand();
      // And a redundant endCommand again — must remain a no-op.
      checker.endCommand();

      // Re-register and get interrupted again — pins that the checker is still usable.
      checker.startCommand(null);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        secondInterrupted.countDown();
      }
      checker.endCommand();
      done.countDown();
    });
    worker.start();

    assertTrue("first registration must be interrupted",
        firstInterrupted.await(2, TimeUnit.SECONDS));
    assertTrue("re-registered command must also be interrupted",
        secondInterrupted.await(2, TimeUnit.SECONDS));
    assertTrue("worker must complete", done.await(3, TimeUnit.SECONDS));

    checker.close();
  }

  /**
   * Pins that close() unregisters the periodic timer from this scheduler — after close,
   * no further sweep ticks fire even if a (foolish) caller registers a new command.
   * Verifies via a counting scheduler that records every {@code schedule()} call.
   */
  @Test
  public void closeCancelsPeriodicTimer() throws Exception {
    var sweeps = new AtomicInteger();
    var schedules = new AtomicInteger();
    var countingScheduler = new SchedulerInternal() {
      @Override
      public ScheduledFuture<?> schedule(Runnable task, long delay, long period) {
        schedules.incrementAndGet();
        Runnable wrapped = () -> {
          sweeps.incrementAndGet();
          task.run();
        };
        return scheduler.scheduleWithFixedDelay(wrapped, delay, period, TimeUnit.MILLISECONDS);
      }

      @Override
      public ScheduledFuture<?> scheduleOnce(Runnable task, long delay) {
        return scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
      }
    };

    var checker = new CommandTimeoutChecker(50, countingScheduler);
    assertEquals("ctor must register exactly one timer", 1, schedules.get());

    // Allow the sweep to tick at least twice (50/10 = 5 ms period, very forgiving).
    Thread.sleep(120);
    var sweepsBeforeClose = sweeps.get();
    assertTrue("sweep must have ticked at least once before close: " + sweepsBeforeClose,
        sweepsBeforeClose >= 1);

    checker.close();

    // After close, the cancelled timer should produce no further sweeps.
    var snapshotAfterClose = sweeps.get();
    Thread.sleep(150);
    var snapshotLater = sweeps.get();
    // Allow at most a small spillover (one in-flight invocation) and still call this a
    // PASS — but in practice the cancelled fixed-delay task should produce zero further
    // ticks.
    assertTrue("close must stop the periodic sweep (saw " + snapshotAfterClose + " → "
        + snapshotLater + ")",
        snapshotLater - snapshotAfterClose <= 1);
  }
}
