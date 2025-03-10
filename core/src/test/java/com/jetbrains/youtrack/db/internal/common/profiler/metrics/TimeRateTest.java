package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.common.profiler.GranularTicker;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import org.junit.Test;

public class TimeRateTest extends MetricsBaseTest {

  private static final TimeInterval TICK = TimeInterval.of(1, TimeUnit.MILLISECONDS); // 1ms
  private static final int ITERATIONS_COUNT = 1_000_000;

  @Test
  public void zeroRate() {
    testRateFunction("zero", i -> 0);
  }

  @Test
  public void oneRate() {
    testRateFunction("one", i -> 1);
  }

  @Test
  public void constRate() {
    testRateFunction("const", i -> 100_000);
  }

  @Test
  public void linearRate1() {
    testRateFunction("linear1", i -> i);
  }

  @Test
  public void linearRate2() {
    testRateFunction("linear2", i -> i / 2);
  }

  @Test
  public void linearRate3() {
    testRateFunction("linear3", i -> ITERATIONS_COUNT - i);
  }

  @Test
  public void parabolicRate() {
    testRateFunction("parabola", i -> (int) (((double) i * i) / 1_000_000));
  }

  @Test
  public void discreteRate() {
    testRateFunction("discrete", i -> (i / 100 % 2) * 100 * (i / 10000 % 100));
  }

  @Test
  public void sinRate() {
    testRateFunction("sin", i -> (int) (100_000 * Math.sin(((double) i) / 10_000) + 100_000));
  }


  private static void testRateFunction(String functionName, IntUnaryOperator rateFunction) {

    final var ticker = new StubTicker(0, TICK.toNanos());
    final var meter = TimeRate.create(
        ticker,
        TICK,
        TimeInterval.of(1, TimeUnit.SECONDS),
        TimeUnit.SECONDS
    );

    for (int i = 0; i < ITERATIONS_COUNT; i++) {

      final var expectedRate = rateFunction.applyAsInt(i);

      meter.record(expectedRate);
      ticker.advanceTime(1, TimeUnit.SECONDS);
      if (i < 2) {
        // the rate is not stable yet
        continue;
      }
      assertEquals(
          "Rate is different on iteration " + i + " with function " + functionName,
          expectedRate, meter.getRate(),
          0.0);
    }
  }

  @Test
  public void fractionalConstRate() {
    final var ticker = new StubTicker(0, TICK.toNanos());
    final var meter = TimeRate.create(
        ticker,
        TimeInterval.of(10, TimeUnit.MILLISECONDS),
        TimeInterval.of(1, TimeUnit.MINUTES),
        TimeUnit.SECONDS
    );
    final var expectedRate = 2.5;

    long timeOffsetMillis = 0;

    for (int i = 0; i < ITERATIONS_COUNT; i++) {

      // 1 event every 400ms = 2.5 events per second
      if (timeOffsetMillis % 400 == 0) {
        meter.record(1);
      }

      timeOffsetMillis += 200;
      ticker.advanceTime(200, TimeUnit.MILLISECONDS);

      if (timeOffsetMillis < 30000) {
        // the rate is not stable yet
        continue;
      }

      assertEquals(
          "Rate is different on iteration " + i,
          expectedRate, meter.getRate(), 0.01 * expectedRate);
    }
  }

  @Test
  public void asyncRate() throws InterruptedException {

    final var writerThreadCount = 5;
    final var eventPeriodNanos = 1000; // 1 event per 1000ns = 1000 events per millisecond

    final var ticker = closeable(new GranularTicker(TICK.toNanos()));
    ticker.start();
    final var meter = TimeRate.create(
        ticker,
        TimeInterval.of(1, TimeUnit.MILLISECONDS),
        TimeInterval.of(100, TimeUnit.MILLISECONDS),
        TimeUnit.MILLISECONDS
    );

    // 5 threads producing 1 event every 1000ns = 5_000 events per millisecond
    for (int i = 0; i < writerThreadCount; i++) {
      closeable(Executors.newSingleThreadScheduledExecutor())
          .scheduleAtFixedRate(
              () -> meter.record(1), 0, eventPeriodNanos, TimeUnit.NANOSECONDS
          );
    }

    // letting the rate stabilize
    Thread.sleep(1000);
    final var r = new Random();

    final var iterations = 100;
    double total = 0;
    for (int i = 0; i < iterations; i++) {
      final var rate = meter.getRate();
      total += rate;

      // sleep 10 milliseconds + random jitter
      Thread.sleep(10 + r.nextInt(-5, 6));
    }

    final var expectedRate = writerThreadCount * 1_000_000 / eventPeriodNanos;
    final var avgRate = total / iterations;

    assertEquals(expectedRate, avgRate, 0.05 * expectedRate);
  }
}
