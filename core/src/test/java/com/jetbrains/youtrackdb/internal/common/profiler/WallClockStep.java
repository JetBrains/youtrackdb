package com.jetbrains.youtrackdb.internal.common.profiler;

import java.util.concurrent.TimeUnit;

/** Measures the largest observed wall-clock tick for real-clock ticker assertions. */
public final class WallClockStep {

  private WallClockStep() {
  }

  public static long measureMillis() {
    long previous = System.currentTimeMillis();
    long maxStep = 0;
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    for (int transitions = 0; transitions < 5;) {
      long current = System.currentTimeMillis();
      if (current != previous) {
        maxStep = Math.max(maxStep, current - previous);
        previous = current;
        transitions++;
      }
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("wall clock did not make five transitions within 10 seconds");
      }
      Thread.onSpinWait();
    }
    // The sampled nanoTime is divided by 1_000_000, so truncation can add one millisecond.
    // Windows wall time can jump a full clock step between nanoTime and the following wall read.
    return 1 + maxStep;
  }
}
