package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.common.profiler.GranularTicker;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import it.unimi.dsi.fastutil.objects.ObjectDoublePair;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.Test;

public class RatioTest extends MetricsBaseTest {

  private static final TimeInterval TICK = TimeInterval.of(1, TimeUnit.MILLISECONDS); // 1ms
  private static final int ITERATIONS_COUNT = 1_000_000;

  @Test
  public void zeroRatio() {
    testRatioFunction("zero", 100, i -> ObjectDoublePair.of(new long[]{0, 0}, 0.0));
  }

  @Test
  public void oneRatio() {
    testRatioFunction("one", 100, i -> ObjectDoublePair.of(new long[]{100, 100}, 1.0));
  }

  @Test
  public void halfRatio() {
    testRatioFunction("half", 100, i -> ObjectDoublePair.of(new long[]{50}, 0.5));
  }

  @Test
  public void halfRatio2() {
    testRatioFunction("half2", 100, i -> ObjectDoublePair.of(new long[]{0, 100, 50}, 0.5));
  }

  @Test
  public void discreteRatio() {
    testRatioFunction("discrete", ITERATIONS_COUNT,
        i -> ObjectDoublePair.of(
            new long[]{i / 1000 % 2 == 0 ? i : 0},
            i / 1000 % 2 == 0 ? (double) i / ITERATIONS_COUNT : 0
        ));
  }

  @Test
  public void linearRatio() {
    testRatioFunction("linear", ITERATIONS_COUNT,
        i -> ObjectDoublePair.of(new long[]{i}, (double) i / ITERATIONS_COUNT));
  }

  private void testRatioFunction(
      String functionName,
      int base,
      IntFunction<ObjectDoublePair<long[]>> ratioFunction
  ) {

    final var ticker = new StubTicker(0, TICK.toNanos());
    final var meter = Ratio.create(
        ticker,
        TICK,
        TimeInterval.of(1, TimeUnit.SECONDS)
    );

    for (int i = 0; i < ITERATIONS_COUNT; i++) {
      final var e = ratioFunction.apply(i);
      for (long l : e.left()) {
        meter.record(l, base);
      }
      final var expectedRate = e.rightDouble();

      ticker.advanceTime(1000, TimeUnit.MILLISECONDS);

      if (i < 100000) {
        // the ratio is not stable yet
        continue;
      }

      assertEquals(
          "Ratio is different on iteration " + i + " with function " + functionName,
          expectedRate, meter.getRatio(),
          0.0);
    }
  }

  @Test
  public void shiftedRatio() {
    final var ticker = new StubTicker(0, TICK.toNanos());
    final var meter = Ratio.create(
        ticker,
        TimeInterval.of(10, TimeUnit.MILLISECONDS),
        TimeInterval.of(1, TimeUnit.MINUTES)
    );

    final var expectedRate = 7.0 / 12;

    long timeOffsetMillis = 0;

    for (int i = 0; i < ITERATIONS_COUNT; i++) {

      // 1 success every 500ms
      if (timeOffsetMillis % 500 == 0) {
        meter.record(true);
      }

      // 1 failure every 700ms
      if (timeOffsetMillis % 700 == 0) {
        meter.record(false);
      }

      timeOffsetMillis += 100;
      ticker.advanceTime(100, TimeUnit.MILLISECONDS);

      if (timeOffsetMillis < 30000) {
        // the rate is not stable yet
        continue;
      }

      assertEquals(
          "Rate is different on iteration " + i,
          expectedRate, meter.getRatio(), 0.01 * expectedRate);
    }
  }

  @Test
  public void asyncRatio() throws InterruptedException {

    final var writerThreadCount = 5;
    final var eventPeriodNanos = 1000; // 1 event per 1000ns
    final var successEveryNthIt = 3;

    final var ticker = closeable(new GranularTicker(TICK.toNanos()));
    ticker.start();
    final var meter = Ratio.create(
        ticker,
        TimeInterval.of(1, TimeUnit.MILLISECONDS),
        TimeInterval.of(100, TimeUnit.MILLISECONDS)
    );

    // 10 threads producing 1 event every 500ns = 20_000 events per millisecond
    for (int i = 0; i < writerThreadCount; i++) {
      final var it = new ModifiableLong(0);
      closeable(Executors.newSingleThreadScheduledExecutor())
          .scheduleAtFixedRate(
              () -> {
                meter.record(it.getValue() % successEveryNthIt == 0);
                it.increment();
              }, 0, eventPeriodNanos, TimeUnit.NANOSECONDS
          );
    }

    // letting the rate stabilize
    Thread.sleep(1000);
    final var r = new Random();

    final var iterations = 100;
    double total = 0;
    for (int i = 0; i < iterations; i++) {
      total += meter.getRatio();

      // sleep 10 milliseconds + random jitter
      Thread.sleep(10 + r.nextInt(-5, 6));
    }

    final var expectedRatio = 1.0 / successEveryNthIt;
    final var avgRatio = total / iterations;

    assertEquals(expectedRatio, avgRatio, 0.01 * expectedRatio);
  }
}
