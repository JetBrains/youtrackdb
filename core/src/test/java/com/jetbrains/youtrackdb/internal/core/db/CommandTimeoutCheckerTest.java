package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  @Override
  public ScheduledFuture<?> schedule(Runnable task, long delay, long period) {
    return scheduler.scheduleWithFixedDelay(task, delay, period, TimeUnit.MILLISECONDS);
  }

  @Override
  public ScheduledFuture<?> scheduleOnce(Runnable task, long delay) {
    return scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
  }

  /**
   * Helper: start a worker thread tracked for {@code @After} cleanup. JUnit 4 instantiates
   * a fresh test object per @Test, so without this discipline each test method would leak
   * its spawned threads (and the per-instance {@link #scheduler}) into surefire JVM until
   * process exit.
   */
  private Thread spawn(Runnable body) {
    var t = new Thread(body);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  /** Named-thread variant — used by tests that assert by thread name. */
  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  @After
  public void clearInterrupt() {
    // Defensive: clears the interrupted flag if any test accidentally registers the
    // calling thread. Failing to clear this would propagate to the next test method
    // because surefire reuses worker threads.
    Thread.interrupted();
  }

  @After
  public void joinSpawnedWorkersAndShutdownScheduler() throws InterruptedException {
    // Join workers first so their final endCommand calls land before scheduler shutdown.
    // Bounded join — a worker that is still sleeping at this point indicates a leaked
    // spawn or a missing latch.countDown; the bound prevents the test class from hanging
    // indefinitely.
    for (var t : spawnedWorkers) {
      t.join(2_000);
    }
    spawnedWorkers.clear();
    scheduler.shutdownNow();
    scheduler.awaitTermination(2, TimeUnit.SECONDS);
  }

  @Test
  public void testTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(100, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      spawn(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              latch.countDown();
            }
            checker.endCommand();
          });
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    checker.close();
  }

  @Test
  public void testNoTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(1000, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      spawn(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            latch.countDown();
            checker.endCommand();
          });
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
    spawn(() -> {
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

    assertTrue("worker must finish on its own", done.await(2, TimeUnit.SECONDS));
    assertFalse("disabled checker must not interrupt the worker", interrupted.get());

    // Timer is null in disabled mode — close() must short-circuit without NPE.
    disabled.close();
  }

  @Test
  public void disabledModeAcceptsNegativeTimeoutSameAsZero() throws Exception {
    // Negative timeouts behave identically to 0 — the if-> 0 branch goes false either way.
    // The dispatcher silently accepts the negative value; "Accepts" (not "Rejects")
    // matches the observed shape since no exception is thrown.
    var negative = new CommandTimeoutChecker(-1, this);

    var done = new CountDownLatch(1);
    var interrupted = new AtomicBoolean(false);
    var worker = spawn(() -> {
      negative.startCommand(null);
      try {
        Thread.sleep(120);
      } catch (InterruptedException e) {
        interrupted.set(true);
      }
      negative.endCommand();
      done.countDown();
    });

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

    spawn(() -> {
      // 1 ms explicit per-command timeout — should fire before the 200 ms default.
      checker.startCommand(1L);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
      checker.endCommand();
    });

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

    spawn(() -> {
      checker.startCommand(null);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        registeredInterrupted.countDown();
      }
      checker.endCommand();
    }, "registered-worker");

    spawn(() -> {
      // Never calls startCommand — must not be interrupted.
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        bystanderInterrupted.set(true);
      }
      bystanderFinished.countDown();
    }, "bystander");

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

    spawn(() -> {
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

    spawn(() -> {
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

    assertTrue("first registration must be interrupted",
        firstInterrupted.await(2, TimeUnit.SECONDS));
    assertTrue("re-registered command must also be interrupted",
        secondInterrupted.await(2, TimeUnit.SECONDS));
    assertTrue("worker must complete", done.await(3, TimeUnit.SECONDS));

    checker.close();
  }

  /**
   * Pins observed-shape behaviour when a per-command timeout of {@code Long.MAX_VALUE} is
   * supplied: the production code stores {@code System.currentTimeMillis() + timeout}, which
   * silently wraps to a large negative number. The next sweep then sees the deadline as
   * already past and interrupts the worker immediately. This pin is observed-shape — a
   * future correctness fix would clamp the addition before storing, in which case this
   * worker would NOT be interrupted and the pin would fail.
   */
  // WHEN-FIXED: deferred-cleanup track — guard the deadline addition against overflow so a
  // Long.MAX_VALUE per-command timeout means "effectively never" rather than "fire now".
  @Test
  public void perCommandTimeoutOfLongMaxValueOverflowsAndInterruptsImmediately()
      throws InterruptedException {
    var checker = new CommandTimeoutChecker(50, this);
    var interrupted = new CountDownLatch(1);

    spawn(() -> {
      checker.startCommand(Long.MAX_VALUE);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
      checker.endCommand();
    });

    assertTrue("Long.MAX_VALUE timeout should overflow → immediate interrupt (observed shape)",
        interrupted.await(2, TimeUnit.SECONDS));
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

    // Poll for the first sweep tick instead of sleeping a fixed window. The fixed-delay
    // period is 50/10 = 5 ms, but the executor's first thread can take ~50-100 ms on a
    // contended runner, so the previous fixed Thread.sleep(120) was tight.
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (sweeps.get() < 1 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    var sweepsBeforeClose = sweeps.get();
    assertTrue("sweep must have ticked at least once before close: " + sweepsBeforeClose,
        sweepsBeforeClose >= 1);

    checker.close();

    // After close, the cancelled timer should produce no further sweeps. Drain a
    // generous window — fixed-delay does not re-fire after cancel(), so any spillover
    // is at most one in-flight invocation that managed to start before cancel().
    var snapshotAfterClose = sweeps.get();
    Thread.sleep(150);
    var snapshotLater = sweeps.get();
    assertTrue("close must stop the periodic sweep (saw " + snapshotAfterClose + " → "
        + snapshotLater + ")",
        snapshotLater - snapshotAfterClose <= 1);
  }
}
