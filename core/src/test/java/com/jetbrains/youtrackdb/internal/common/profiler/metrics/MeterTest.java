package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for the counter-based tick check batching in {@link Meter}. The meter defers the
 * volatile tick read until {@link Meter#DEFAULT_TICK_CHECK_INTERVAL} records have been
 * accumulated, reducing overhead on the hot path. These tests verify that batching doesn't
 * break the flush mechanism or degrade accuracy.
 */
public class MeterTest {

  private static final TimeInterval TICK = TimeInterval.of(1, TimeUnit.MILLISECONDS);

  /**
   * Verify that records below the default tick check interval do not trigger a flush, and
   * that once the threshold is crossed the flush happens and getRate() returns a non-zero
   * value.
   */
  @Test
  public void flushHappensAfterTickCheckInterval() {
    var ticker = new StubTicker(0, TICK.toNanos());
    var ratio = Ratio.create(
        ticker,
        TICK,
        TimeInterval.of(1, TimeUnit.SECONDS)
    );

    // Record one event first to create the ThreadLocalMeter (capturing lastFlushTick = 0),
    // then advance time so the next tick check will see a changed tick.
    ratio.record(true);
    ticker.advanceTime(100, TimeUnit.MILLISECONDS);

    // Record up to DEFAULT_TICK_CHECK_INTERVAL - 2 more events (total = INTERVAL - 1) — no
    // tick check should happen because we haven't reached the threshold yet.
    for (int i = 0; i < Meter.DEFAULT_TICK_CHECK_INTERVAL - 2; i++) {
      ratio.record(true);
    }
    assertEquals(
        "No flush should have occurred yet — ratio must be 0",
        0.0, ratio.getRatio(), 0.0);

    // One more record crosses the threshold — tick check + flush should happen.
    ratio.record(true);
    assertEquals(
        "Flush should have occurred — ratio must be 1.0",
        1.0, ratio.getRatio(), 0.0);
  }

  /**
   * Verify that accuracy is preserved with batching: record a known mix of successes and
   * failures across multiple flush cycles and confirm the reported ratio matches the expected
   * value.
   */
  @Test
  public void accuracyPreservedAcrossMultipleFlushes() {
    var ticker = new StubTicker(0, TICK.toNanos());
    // Flush rate = 10ms, period = 1 minute. This ensures many records fall within one period
    // so the ratio is stable.
    var ratio = Ratio.create(
        ticker,
        TimeInterval.of(10, TimeUnit.MILLISECONDS),
        TimeInterval.of(1, TimeUnit.MINUTES)
    );

    int totalRecords = Meter.DEFAULT_TICK_CHECK_INTERVAL * 20; // 5120 records, ~20 flush cycles
    int successCount = 0;

    for (int i = 0; i < totalRecords; i++) {
      // 3 out of every 4 records are successes → expected ratio = 0.75
      boolean success = (i % 4 != 3);
      ratio.record(success);
      if (success) {
        successCount++;
      }

      // Advance time by 1ms every 64 records to keep a realistic pace and ensure
      // the tick changes between flush cycles.
      if (i % 64 == 63) {
        ticker.advanceTime(1, TimeUnit.MILLISECONDS);
      }
    }

    // Force one final flush by advancing time past the flush rate and recording enough
    // events to cross the tick check interval.
    ticker.advanceTime(100, TimeUnit.MILLISECONDS);
    for (int i = 0; i < Meter.DEFAULT_TICK_CHECK_INTERVAL; i++) {
      // Record at the same 3/4 ratio to keep the expected value unchanged.
      ratio.record(i % 4 != 3);
    }

    double expectedRatio = (double) successCount / totalRecords;
    assertEquals(
        "Ratio should match the expected 0.75 within a small tolerance",
        expectedRatio, ratio.getRatio(), 0.01);
  }

  /**
   * Verify that a high volume of records with interleaved time advances converges to the
   * correct ratio. This is similar to the existing RatioTest.shiftedRatio but specifically
   * validates behavior with the tick check batching.
   */
  @Test
  public void highVolumeRecordingConverges() {
    var ticker = new StubTicker(0, TICK.toNanos());
    var ratio = Ratio.create(
        ticker,
        TimeInterval.of(10, TimeUnit.MILLISECONDS),
        TimeInterval.of(1, TimeUnit.MINUTES)
    );

    // Record many events, advancing time gradually. Every 3rd record is a failure.
    int iterations = 100_000;
    for (int i = 0; i < iterations; i++) {
      ratio.record(i % 3 != 0);

      // Advance time by 10 microseconds every record, so 1ms every 100 records.
      ticker.advanceTime(10_000); // 10µs in nanos
    }

    // Force a final flush.
    ticker.advanceTime(100, TimeUnit.MILLISECONDS);
    for (int i = 0; i < Meter.DEFAULT_TICK_CHECK_INTERVAL; i++) {
      ratio.record(i % 3 != 0);
    }

    // 2 out of 3 records are successes.
    double expectedRatio = 2.0 / 3.0;
    assertEquals(expectedRatio, ratio.getRatio(), 0.01);
  }
}
