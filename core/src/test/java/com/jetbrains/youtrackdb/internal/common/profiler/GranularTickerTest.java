package com.jetbrains.youtrackdb.internal.common.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.profiler.GranularTicker.Snapshot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.junit.experimental.categories.Category;

// GranularTicker publishes a single (nanoTime, nanoTimeDifference) Snapshot behind one
// volatile reference. A single scheduled task is the only writer, and monotonicity of both
// accessors is enforced at publish time by GranularTicker.nextSnapshot. Readers do a plain
// volatile read plus field access. The @Category marker is retained to keep this
// real-clock scheduler test separate from other timing-sensitive tests.
@Category(SequentialTest.class)
public class GranularTickerTest {

  @Test
  public void testTimeApproximation() throws Exception {
    final var granularityNanos = TimeUnit.MILLISECONDS.toNanos(10);
    final var timeAdjustmentNanos = TimeUnit.MILLISECONDS.toNanos(50);
    var scheduler = Executors.newScheduledThreadPool(1);
    try (var ticker = new GranularTicker(granularityNanos, timeAdjustmentNanos, scheduler)) {
      final long wallClockAllowanceMillis = WallClockStep.measureMillis();
      ticker.start();
      var prevApproxNano = ticker.approximateNanoTime();
      var prevApproxMillis = ticker.approximateCurrentTimeMillis();

      for (var i = 0; i < 20; i++) {
        Thread.sleep(13);

        final var approxNano = ticker.approximateNanoTime();
        final var approxMillis = ticker.approximateCurrentTimeMillis();

        final var afterNano = System.nanoTime();
        final var afterMillis = System.currentTimeMillis();

        // The ticker should never run ahead of real time.
        assertThat(approxNano).isLessThanOrEqualTo(afterNano);
        assertThat(approxMillis).isLessThanOrEqualTo(
            afterMillis + wallClockAllowanceMillis);

        // The ticker should be monotonically non-decreasing.
        assertThat(approxNano).isGreaterThanOrEqualTo(prevApproxNano);
        assertThat(approxMillis).isGreaterThanOrEqualTo(prevApproxMillis);

        prevApproxNano = approxNano;
        prevApproxMillis = approxMillis;
      }

      // A delayed scheduler fire may leave the ticker arbitrarily late. Pin a real-time
      // target and wait for both accessors to catch up instead of bounding scheduler lag.
      final long targetNano = System.nanoTime();
      final long targetMillis = System.currentTimeMillis();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (ticker.approximateNanoTime() < targetNano
          || ticker.approximateCurrentTimeMillis() < targetMillis) {
        assertThat(System.nanoTime())
            .as("ticker must eventually catch up to both real-clock targets")
            .isLessThan(deadline);
        Thread.onSpinWait();
      }
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** Calling stop() on a ticker that was never started should be a safe no-op. */
  @Test
  public void stopWithoutStartIsNoOp() {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(10),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      // stop() without start() — the scheduled future is null, so nothing to cancel.
      ticker.stop();
      // No exception means success.
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * After starting and then stopping, the ticker's scheduled future is cancelled and the
   * approximate time no longer advances.
   */
  @Test
  public void startThenStopFreezesTime() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(5),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      ticker.start();
      // Let a few ticks elapse so the ticker is actively updating.
      Thread.sleep(50);
      assertThat(ticker.approximateNanoTime()).isGreaterThan(0);

      ticker.stop();
      // After stop, the approximate nano time should no longer advance.
      var frozenTime = ticker.approximateNanoTime();
      Thread.sleep(50);
      assertThat(ticker.approximateNanoTime()).isEqualTo(frozenTime);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Regression test for the stop()-vs-refresh publish race. A refresh task that has already
   * passed the early {@code stopped} check (and sampled its fresh nanoTime) can be descheduled
   * by the OS before it publishes its snapshot. If it then resumes after stop() has set the
   * flag, it must observe the flag under the lifecycle lock and publish nothing, so
   * approximateNanoTime() stays frozen. This is the race that flaked
   * {@link #startThenStopFreezesTime()} on Windows CI (~15.6 ms timer quantum plus contention).
   *
   * <p>The race is reproduced deterministically rather than by timing: the test thread holds
   * the lifecycle lock to pin an in-flight refresh at its publish point, sets {@code stopped}
   * exactly as stop() does (under the lock), then releases the lock and lets the refresh
   * resume. Without the under-lock re-check the refresh would publish a newer nanoTime and
   * unfreeze the ticker.
   */
  @Test
  public void inFlightRefreshAfterStopDoesNotUnfreezeTime() throws Exception {
    List<Runnable> capturedTasks = new ArrayList<>();
    // Fake scheduler that only captures the refresh runnable so the test can drive it on its
    // own thread instead of relying on real fixed-rate scheduling.
    ScheduledExecutorService fakeScheduler = new DelegatingScheduledExecutorService(
        Executors.newSingleThreadScheduledExecutor()) {
      @Override
      public ScheduledFuture<?> scheduleAtFixedRate(
          Runnable command, long initialDelay, long period, TimeUnit unit) {
        capturedTasks.add(command);
        return NO_OP_FUTURE;
      }
    };
    try {
      // One-hour granularity so the only fire is the one the test drives manually.
      var ticker = new GranularTicker(
          TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(1), fakeScheduler);
      ticker.start();
      assertThat(capturedTasks).hasSize(1);
      var refresh = capturedTasks.get(0);

      // This single driven fire is a recalibration fire: recalibrationInterval ==
      // max(1, 1h / 1h) == 1, so a leaked publish would recompute nanoTimeDifference and move
      // BOTH accessors. Capture the frozen value of each, plus the snapshot reference itself,
      // so the assertions below prove no publish occurred rather than inferring it from a
      // value that the nextSnapshot clamp could coincidentally preserve.
      var frozenNano = ticker.approximateNanoTime();
      var frozenMillis = ticker.approximateCurrentTimeMillis();

      Field lockField = GranularTicker.class.getDeclaredField("lifecycleLock");
      lockField.setAccessible(true);
      var lifecycleLock = lockField.get(ticker);
      Field stoppedField = GranularTicker.class.getDeclaredField("stopped");
      stoppedField.setAccessible(true);
      Field snapshotField = GranularTicker.class.getDeclaredField("snapshot");
      snapshotField.setAccessible(true);
      var frozenSnapshot = snapshotField.get(ticker);

      var refreshThread = new Thread(refresh, "in-flight-refresh");
      // Daemon so a leaked thread (if the fix ever regresses and the refresh never unblocks)
      // cannot outlive the suite and wedge the forked JVM.
      refreshThread.setDaemon(true);
      synchronized (lifecycleLock) {
        // Pin the in-flight refresh at its publish point: it passes the early stopped check
        // (stopped is still false) and samples a fresh nanoTime, then blocks acquiring the
        // lifecycle lock the test thread holds. BLOCKED is unambiguous here because
        // lifecycleLock is the only monitor on refresh()'s pre-publish path — if the refresh
        // ever terminated or blocked elsewhere instead, the bailouts below fail the test
        // loudly rather than letting it hang or pass vacuously.
        refreshThread.start();
        final var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Thread.State state;
        while ((state = refreshThread.getState()) != Thread.State.BLOCKED) {
          assertThat(state)
              .as("refresh must block on lifecycleLock, not terminate before publishing")
              .isNotEqualTo(Thread.State.TERMINATED);
          assertThat(System.nanoTime())
              .as("refresh did not reach the publish lock within the deadline; state=" + state)
              .isLessThan(deadlineNanos);
          Thread.onSpinWait();
        }
        // Set stopped under the lock, exactly as stop() does, while the refresh is pinned.
        stoppedField.setBoolean(ticker, true);
      }
      // Release the lock; the pinned refresh resumes, re-checks stopped under the lock, and
      // must publish nothing.
      refreshThread.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(refreshThread.isAlive())
          .as("the pinned refresh must finish after the lock is released")
          .isFalse();

      // Object identity: a refresh that published would store a new Snapshot instance.
      assertThat(snapshotField.get(ticker))
          .as("an in-flight refresh resuming after stop must publish no new snapshot")
          .isSameAs(frozenSnapshot);
      assertThat(ticker.approximateNanoTime())
          .as("an in-flight refresh resuming after stop must not unfreeze approximateNanoTime")
          .isEqualTo(frozenNano);
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("a recalibration refresh resuming after stop must not unfreeze wall-clock millis")
          .isEqualTo(frozenMillis);
    } finally {
      fakeScheduler.shutdownNow();
    }
  }

  /** close() delegates to stop(), behaving identically. */
  @Test
  public void closeAfterStartCancelsFutures() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(5),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      ticker.start();
      Thread.sleep(30);
      ticker.close();

      var frozenTime = ticker.approximateNanoTime();
      Thread.sleep(30);
      assertThat(ticker.approximateNanoTime()).isEqualTo(frozenTime);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Verifies that the single scheduled refresh task advances the snapshot when running, that
   * wall-clock recalibration happens on the configured cadence, and that the task skips the
   * update after stop.
   *
   * <p>Uses a very long granularity so the task never fires on its own, then manually invokes
   * the captured runnable to exercise the {@code if (!stopped)} branch. With granularity and
   * timestampRefreshRate both set to 1 hour, the recalibration interval is 1, so every fire
   * both refreshes nanoTime and recomputes the wall-clock offset.
   */
  @Test
  public void scheduledTaskSkipsUpdateAfterStop() throws Exception {
    List<Runnable> capturedTasks = new ArrayList<>();
    // Fake scheduler that only captures the runnables — no threads, no real scheduling.
    ScheduledExecutorService fakeScheduler = new DelegatingScheduledExecutorService(
        Executors.newSingleThreadScheduledExecutor()) {
      @Override
      public ScheduledFuture<?> scheduleAtFixedRate(
          Runnable command, long initialDelay, long period, TimeUnit unit) {
        capturedTasks.add(command);
        return NO_OP_FUTURE;
      }
    };
    try {
      var granularityNanos = TimeUnit.HOURS.toNanos(1);
      var ticker = new GranularTicker(
          granularityNanos, TimeUnit.HOURS.toNanos(1), fakeScheduler);
      ticker.start();
      // The single refresh task is the only scheduling submission.
      assertThat(capturedTasks).hasSize(1);

      assertThat(ticker.getGranularity()).isEqualTo(granularityNanos);

      Field snapshotField = GranularTicker.class.getDeclaredField("snapshot");
      snapshotField.setAccessible(true);

      // First fire while running: nanoTime advances past start() and a new snapshot is
      // published. Because recalibrationInterval == 1, this fire also recalibrates wall
      // clock.
      var nanoBefore = ticker.approximateNanoTime();
      var snapshotBefore = snapshotField.get(ticker);
      var wallBefore = System.currentTimeMillis();
      capturedTasks.get(0).run();
      var wallAfter = System.currentTimeMillis();
      var snapshotAfterFire = snapshotField.get(ticker);
      assertThat(ticker.approximateNanoTime())
          .as("refresh task must advance nanoTime when ticker is running")
          .isGreaterThan(nanoBefore);
      assertThat(snapshotAfterFire)
          .as("refresh task must publish a new snapshot when ticker is running")
          .isNotSameAs(snapshotBefore);
      // No earlier refresh ran: the previous millis was sampled at start(). With no
      // backward wall-clock step, the monotonic clamp cannot exceed this fire's wall sample.
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("recalibration millis must be bracketed by the surrounding wall reads")
          .isBetween(wallBefore, wallAfter);

      var expectedTick = ticker.approximateNanoTime() / granularityNanos;
      assertThat(ticker.getTick()).isEqualTo(expectedTick);

      ticker.stop();

      var nanoAfterStop = ticker.approximateNanoTime();
      var millisAfterStop = ticker.approximateCurrentTimeMillis();
      var snapshotAfterStop = snapshotField.get(ticker);

      // Fire the task after stop — it must see stopped == true and publish nothing.
      capturedTasks.get(0).run();

      assertThat(snapshotField.get(ticker))
          .as("refresh task must not publish a new snapshot after stop")
          .isSameAs(snapshotAfterStop);
      assertThat(ticker.approximateNanoTime()).isEqualTo(nanoAfterStop);
      assertThat(ticker.approximateCurrentTimeMillis()).isEqualTo(millisAfterStop);
    } finally {
      fakeScheduler.shutdownNow();
    }
  }

  /** A controlled fire publishes the supplied nano sample, then stop freezes both accessors. */
  @Test
  public void controlledRefreshPublishesExactNanoAndStops() {
    var nano = new AtomicLong(1_000_000_000L);
    var wall = new AtomicLong(5_000L);
    var tasks = new ArrayList<Runnable>();
    var scheduler = capturingScheduler(tasks);
    try {
      var ticker = new GranularTicker(1, 2, scheduler, nano::get, wall::get);
      ticker.start();
      nano.set(2_000_000_000L);
      tasks.getFirst().run();
      assertThat(ticker.approximateNanoTime())
          .as("refresh must publish the supplied nano sample").isEqualTo(nano.get());
      ticker.stop();
      nano.set(3_000_000_000L);
      tasks.getFirst().run();
      assertThat(ticker.approximateNanoTime())
          .as("stopped ticker must retain the published nano sample").isEqualTo(2_000_000_000L);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** Startup and recalibration must derive exactly the controlled wall sample. */
  @Test
  public void controlledWallOffsetIsExactAtStartAndRecalibration() {
    var nano = new AtomicLong(1_000_000_000L);
    var wall = new AtomicLong(5_000L);
    var tasks = new ArrayList<Runnable>();
    var scheduler = capturingScheduler(tasks);
    try {
      var ticker = new GranularTicker(1, 1, scheduler, nano::get, wall::get);
      ticker.start();
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("startup must derive the supplied wall millis").isEqualTo(5_000L);
      nano.set(2_000_000_000L);
      wall.set(7_500L);
      tasks.getFirst().run();
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("recalibration must derive the supplied wall millis").isEqualTo(7_500L);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** A pause after the first clock read must not put the next derived timestamp ahead. */
  @Test
  public void wallFirstSamplingDoesNotRunAheadAfterPauseBetweenReads() {
    var nano = new AtomicLong(1_000_000_000L);
    var wall = new AtomicLong(5_000L);
    var firstRead = new boolean[] {true};
    LongSupplier nanoClock = () -> {
      long sample = nano.get();
      if (firstRead[0]) {
        firstRead[0] = false;
        nano.addAndGet(100_000_000L);
        wall.addAndGet(100L);
      }
      return sample;
    };
    LongSupplier wallClock = () -> {
      long sample = wall.get();
      if (firstRead[0]) {
        firstRead[0] = false;
        nano.addAndGet(100_000_000L);
        wall.addAndGet(100L);
      }
      return sample;
    };
    var tasks = new ArrayList<Runnable>();
    var scheduler = capturingScheduler(tasks);
    try {
      // The first fire reuses the startup offset and samples the post-pause nano clock.
      var ticker = new GranularTicker(1, 2, scheduler, nanoClock, wallClock);
      ticker.start();
      tasks.getFirst().run();
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("a pause between clock reads must not put derived millis ahead of wall time")
          .isLessThanOrEqualTo(wall.get());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static ScheduledExecutorService capturingScheduler(List<Runnable> tasks) {
    return new DelegatingScheduledExecutorService(Executors.newSingleThreadScheduledExecutor()) {
      @Override
      public ScheduledFuture<?> scheduleAtFixedRate(
          Runnable command, long initialDelay, long period, TimeUnit unit) {
        tasks.add(command);
        return NO_OP_FUTURE;
      }
    };
  }

  /** A no-op {@link ScheduledFuture} that reports as not done and not cancelled. */
  private static final ScheduledFuture<?> NO_OP_FUTURE = new ScheduledFuture<>() {
    @Override
    public long getDelay(TimeUnit unit) {
      return Long.MAX_VALUE;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  };

  /** Constructor should reject null executor. */
  @Test
  public void constructorRejectsNullExecutor() {
    assertThatThrownBy(
        () -> new GranularTicker(10_000_000, 10_000_000, null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Non-recalibration fires must carry the previous snapshot's {@code nanoTimeDifference}
   * forward unchanged so readers of {@link GranularTicker#approximateCurrentTimeMillis()}
   * continue to derive the same wall-clock offset between recalibrations. Only the nanoTime
   * component of the published pair changes on such fires.
   */
  @Test
  public void nextSnapshotPreservesDiffOnNonRecalibrationTick() {
    var prev = new Snapshot(1_000_000_000L, 5_000L);

    var next = GranularTicker.nextSnapshot(prev, 2_000_000_000L, /*wallMillis*/ 42L, false);

    assertThat(next.nanoTime()).isEqualTo(2_000_000_000L);
    assertThat(next.nanoTimeDifference())
        .as("non-recalibration fire must not touch nanoTimeDifference")
        .isEqualTo(5_000L);
  }

  /**
   * A recalibration fire that is not subject to backward drift must compute a fresh
   * wall-clock offset from the sampled {@code wallMillis} and {@code freshNano}. The derived
   * wall-clock timestamp after the publish must therefore equal {@code wallMillis} exactly.
   */
  @Test
  public void nextSnapshotRecomputesDiffOnRecalibration() {
    // prev published at nano=1_000_000_000 (=> 1000 ms), diff=5000 => derived millis = 6000.
    var prev = new Snapshot(1_000_000_000L, 5_000L);
    // Fresh nano=2_000_000_000 (=> 2000 ms), fresh wall clock = 7500 ms => new diff = 5500,
    // derived millis = 7500 which is strictly greater than prev's 6000 — no clamp needed.
    var next = GranularTicker.nextSnapshot(prev, 2_000_000_000L, 7_500L, true);

    assertThat(next.nanoTime()).isEqualTo(2_000_000_000L);
    assertThat(next.nanoTimeDifference()).isEqualTo(5_500L);
    assertThat(next.nanoTime() / 1_000_000 + next.nanoTimeDifference())
        .as("derived wall-clock millis must equal the freshly sampled wallMillis")
        .isEqualTo(7_500L);
  }

  /**
   * Sub-millisecond drift between {@link System#nanoTime()} and
   * {@link System#currentTimeMillis()} at a recalibration fire can yield a candidate whose
   * derived wall-clock millis is below the previously published snapshot's derived millis.
   * {@link GranularTicker#nextSnapshot} must inflate {@code nanoTimeDifference} to absorb the
   * drift so {@link GranularTicker#approximateCurrentTimeMillis()} remains non-decreasing.
   */
  @Test
  public void nextSnapshotAbsorbsBackwardMillisDrift() {
    // prev: nano=5_000_000_000 (=> 5000 ms), diff=5000 => derived millis = 10_000.
    var prev = new Snapshot(5_000_000_000L, 5_000L);
    // Fresh wall clock = 9_999 ms at nano=5_000_000_000 => candidate diff = 4_999,
    // candidate derived millis = 9_999 which is 1 ms below prev's 10_000.
    var next = GranularTicker.nextSnapshot(prev, 5_000_000_000L, 9_999L, true);

    assertThat(next.nanoTime()).isEqualTo(5_000_000_000L);
    assertThat(next.nanoTimeDifference())
        .as("diff must be inflated by 1 ms to absorb backward drift")
        .isEqualTo(5_000L);
    assertThat(next.nanoTime() / 1_000_000 + next.nanoTimeDifference())
        .as("derived wall-clock millis must not regress")
        .isEqualTo(10_000L);
  }

  /**
   * On older hardware {@link System#nanoTime()} can appear to regress across CPUs. When the
   * freshly sampled nanoTime is below the previously published value,
   * {@link GranularTicker#nextSnapshot} must clamp it to the previous value so the published
   * pair — and therefore {@link GranularTicker#approximateNanoTime()} — never regresses.
   */
  @Test
  public void nextSnapshotClampsBackwardNanoTime() {
    var prev = new Snapshot(1_000_000_000L, 0L);

    var next = GranularTicker.nextSnapshot(prev, 500_000_000L, 1_000L, false);

    assertThat(next.nanoTime())
        .as("clamp must hold the previous high nanoTime when freshNano regresses")
        .isEqualTo(1_000_000_000L);
    assertThat(next.nanoTimeDifference()).isEqualTo(0L);
  }
}
