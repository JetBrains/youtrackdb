package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import java.util.concurrent.TimeUnit;

public record TimeInterval(long amount, TimeUnit unit) {

  public long toNanos() {
    return unit.toNanos(amount);
  }

  public static TimeInterval of(long amount, TimeUnit unit) {
    return new TimeInterval(amount, unit);
  }
}
