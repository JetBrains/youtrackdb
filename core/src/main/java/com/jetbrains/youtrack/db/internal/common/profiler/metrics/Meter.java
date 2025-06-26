package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import it.unimi.dsi.fastutil.longs.LongLongPair;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import org.apache.commons.lang.Validate;

/**
 * Internal class that collects any ratio-based metrics. Key implementation details:
 * <ul>
 *   <li>Thread-local lock-free metric collection with periodic flushes to the shared state.</li>
 *   <li>Two overlapping buckets for the current and previous periods. Values are always written
 *   to both buckets, but are read only from the bucket that is considered "current" according
 *   to the current tick.</li>
 * </ul>
 */
class Meter {

  /**
   * Metric collection mode.
   */
  enum Mode {
    /**
     * In this mode meter will collect total number of events over a certain period.
     */
    TIME_RATE(false, true, (b, now) -> Math.max(now - b.nanoTime, 1)),
    /**
     * In this mode meter will compute ratio of successful events over total number of events.
     */
    SUCCESS_RATIO(true, false, (b, now) -> b.total.sum());

    private final boolean collectTotal; // whether we collect total number of events
    private final boolean collectRealTime; // whether we are interested in measuring current time
    private final BiFunction<Bucket, Long, Long> ratioDenominator;

    Mode(boolean collectTotal, boolean collectRealTime,
        BiFunction<Bucket, Long, Long> ratioDenominator) {
      this.collectTotal = collectTotal;
      this.collectRealTime = collectRealTime;
      this.ratioDenominator = ratioDenominator;
    }
  }

  private final Ticker ticker;
  private final Mode mode;

  private final long flushRateTicks;
  private final long periodTicks;

  private final Bucket[] buckets; // two overlapping buckets

  private final Lock lock = new ReentrantLock();

  private final ThreadLocal<ThreadLocalMeter> threadLocalMeter =
      ThreadLocal.withInitial(ThreadLocalMeter::new);

  /**
   * Creates a new meter.
   *
   * @param ticker         Ticker to use for time measurements.
   * @param mode           Mode of the meter. See {@link Mode}.
   * @param flushRateTicks Rate (in ticks) at which the meter will flush its data to the shared
   *                       state.
   * @param periodTicks    Period (in ticks) over which the meter will collect data.
   */
  Meter(
      Ticker ticker,
      Mode mode,
      long flushRateTicks,
      long periodTicks
  ) {
    this.mode = mode;
    Validate.isTrue(periodTicks > 0, "Period must be positive");
    Validate.isTrue(flushRateTicks > 0, "Flush rate must be positive");
    Validate.isTrue(periodTicks % flushRateTicks == 0, "Period must be a multiple of flush rate");
    this.ticker = ticker;
    this.flushRateTicks = flushRateTicks;
    this.periodTicks = periodTicks;

    final var currentTick = ticker.getTick();
    final var currentNanoTime = currentNanoTime();
    this.buckets = new Bucket[]{
        new Bucket(currentTick, currentNanoTime, mode.collectTotal),
        new Bucket(currentTick, currentNanoTime, mode.collectTotal)
    };
  }

  private long currentNanoTime() {
    // for success ratio mode we don't need current time
    return mode.collectRealTime ? ticker.currentNanoTime() : 0;
  }

  /**
   * Add new data to the meter.
   */
  void record(long success, long total) {
    threadLocalMeter.get().record(success, total);
  }

  /**
   * Get the current rate from the meter.
   *
   * @return A pair of two longs: nominator and denominator of the rate. The reason why we return a
   * pair of longs instead of a double or BigDecimal is to avoid any precision loss at this point.
   */
  LongLongPair getRate() {

    final var currentTick = ticker.getTick();

    // current bucket index is a function of the current tick
    final var periodOrd = currentTick / periodTicks;
    final var periodStartTick = periodOrd * periodTicks;
    final var currentIndex = (int) (periodOrd % 2);
    final var currentBucket = buckets[currentIndex];
    final var prevBucket = buckets[1 - currentIndex];

    lock.lock();

    try {
      final var currentNanoTime = currentNanoTime();
      // we might need to reset the previous bucket if it hasn't been reset yet by writing threads
      if (prevBucket.resetAtTick < periodStartTick) {
        prevBucket.reset(currentTick, currentNanoTime);
      }

      // reading the data from current bucket:
      // nominator is always a number of successful events
      // denominator is computed according to the mode in which the meter is running
      final var nom = currentBucket.success.sum();
      return nom == 0 ?
          ZERO_RATE :
          LongLongPair.of(nom, mode.ratioDenominator.apply(currentBucket, currentNanoTime));
    } finally {
      lock.unlock();
    }
  }

  private static final LongLongPair ZERO_RATE = LongLongPair.of(0, 0);

  // Thread-local meter that accumulates data until it's time to flush it to the shared state.
  private final class ThreadLocalMeter {

    private long lastFlushTick = ticker.getTick();

    private long localSuccess;
    private long localTotal;

    public void record(long success, long total) {
      // accumulating the data
      localSuccess += success;
      localTotal += total;
      long currentTick = ticker.getTick();

      // checking if it's time to flush the data
      if (currentTick - lastFlushTick >= flushRateTicks) {

        // again, current bucket index is a function of the current tick
        final var periodOrd = currentTick / periodTicks;
        final var periodStartTick = periodOrd * periodTicks;
        final var currentIndex = (int) (periodOrd % 2);
        final var prevBucket = buckets[1 - currentIndex];

        if (prevBucket.resetAtTick < periodStartTick) {
          lock.lock();
          try {
            // resetting the previous bucket
            if (prevBucket.resetAtTick < periodStartTick) {
              prevBucket.reset(currentTick, currentNanoTime());
            }
          } finally {
            lock.unlock();
          }
        }

        // flushing the data
        for (Bucket bucket : buckets) {
          bucket.add(localSuccess, localTotal);
        }

        // resetting the local counters
        lastFlushTick = currentTick;
        localSuccess = 0;
        localTotal = 0;
      }
    }
  }

  static class Bucket {

    private final LongAdder success = new LongAdder();
    private final LongAdder total;
    private volatile long nanoTime;

    private volatile long resetAtTick;

    Bucket(long resetAtTick, long currentNanoTime, boolean withTotal) {
      this.resetAtTick = resetAtTick;
      this.nanoTime = currentNanoTime;
      this.total = withTotal ? new LongAdder() : null;
    }

    void reset(long resetAtTick, long currentNanoTime) {
      this.resetAtTick = resetAtTick;
      this.nanoTime = currentNanoTime;
      this.success.reset();
      if (total != null) {
        total.reset();
      }
    }

    void add(long success, long total) {
      if (success > 0) {
        this.success.add(success);
      }
      if (this.total != null && total > 0) {
        this.total.add(total);
      }
    }
  }
}
